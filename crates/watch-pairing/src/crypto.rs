use crate::{
    PairingError, Result,
    wire::{self, SaResponse},
};
use aes_gcm::{
    Aes256Gcm, KeyInit,
    aead::{Aead, Payload},
};
use hmac::{Hmac, Mac};
use sha2::Sha512;
use subtle::ConstantTimeEq;
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

pub(crate) fn random<const N: usize>() -> Result<[u8; N]> {
    let mut out = [0; N];
    getrandom::fill(&mut out).map_err(|_| PairingError("OS randomness is unavailable"))?;
    Ok(out)
}
pub(crate) fn prf(key: &[u8], parts: &[&[u8]]) -> [u8; 64] {
    let mut mac = <Hmac<Sha512> as Mac>::new_from_slice(key).expect("HMAC accepts any key length");
    for part in parts {
        mac.update(part);
    }
    mac.finalize().into_bytes().into()
}
pub(crate) fn prf_plus(key: &[u8], seed: &[u8], length: usize) -> Zeroizing<Vec<u8>> {
    assert!(length <= 255 * 64);
    let mut out = Zeroizing::new(Vec::with_capacity(length + 64));
    let mut previous = Zeroizing::new(Vec::<u8>::new());
    for counter in 1..=length.div_ceil(64) {
        let next = Zeroizing::new(prf(key, &[&previous, seed, &[counter as u8]]));
        previous.zeroize();
        previous.extend_from_slice(&*next);
        out.extend_from_slice(&*next);
    }
    out.truncate(length);
    out
}

#[derive(Zeroize, ZeroizeOnDrop)]
pub(crate) struct Keys {
    pub d: [u8; 64],
    pub ei: [u8; 36],
    pub er: [u8; 36],
    pub pi: [u8; 64],
    pub pr: [u8; 64],
    pub spi_i: [u8; 8],
    pub spi_r: [u8; 8],
    pub ni: [u8; 32],
    pub nr: [u8; 32],
    iv: u64,
}
impl Keys {
    fn expand(
        skeyseed: &[u8],
        spi_i: [u8; 8],
        spi_r: [u8; 8],
        ni: [u8; 32],
        nr: [u8; 32],
    ) -> Result<Self> {
        let seed = [
            ni.as_slice(),
            nr.as_slice(),
            spi_i.as_slice(),
            spi_r.as_slice(),
        ]
        .concat();
        let material = prf_plus(skeyseed, &seed, 264);
        Ok(Self {
            d: material[..64].try_into().unwrap(),
            ei: material[64..100].try_into().unwrap(),
            er: material[100..136].try_into().unwrap(),
            pi: material[136..200].try_into().unwrap(),
            pr: material[200..264].try_into().unwrap(),
            spi_i,
            spi_r,
            ni,
            nr,
            iv: u64::from_be_bytes(random()?),
        })
    }
    pub fn initial(
        secret: &[u8; 56],
        spi_i: [u8; 8],
        ni: [u8; 32],
        peer: &SaResponse,
    ) -> Result<Self> {
        let shared = Zeroizing::new(
            x448::x448(*secret, peer.public)
                .ok_or(PairingError("Watch provided an invalid X448 public key"))?,
        );
        let nonces = [ni.as_slice(), peer.nonce.as_slice()].concat();
        let seed = Zeroizing::new(prf(&nonces, &[&*shared]));
        Self::expand(&*seed, spi_i, peer.spi, ni, peer.nonce)
    }
    pub fn update(&self, shared: &[u8]) -> Result<Self> {
        let seed = Zeroizing::new(prf(&self.d, &[shared, &self.ni, &self.nr]));
        Self::expand(&*seed, self.spi_i, self.spi_r, self.ni, self.nr)
    }
    pub fn int_auth(&self, response: bool, first: u8, flags: u8, plaintext: &[u8]) -> [u8; 64] {
        let mut input = wire::header(
            &self.spi_i,
            &self.spi_r,
            46,
            43,
            if response { 32 } else { 8 },
            1,
            32 + plaintext.len(),
        );
        input.extend_from_slice(&[first, flags]);
        input.extend_from_slice(&((plaintext.len() + 4) as u16).to_be_bytes());
        input.extend_from_slice(plaintext);
        prf(if response { &self.pr } else { &self.pi }, &[&input])
    }
    pub fn seal(
        &mut self,
        exchange: u8,
        message: u32,
        flags: u8,
        first: u8,
        plaintext: &[u8],
    ) -> Result<Vec<Vec<u8>>> {
        if plaintext.len() > wire::MAX_PLAINTEXT {
            return Err(PairingError("IKE plaintext exceeds limit"));
        }
        let fragmented = plaintext.len() + 57 > 1280;
        let limit = if fragmented { 1219 } else { 1223 };
        let count = plaintext.len().div_ceil(limit).max(1);
        let mut out = Vec::with_capacity(count);
        for index in 0..count {
            self.iv = self
                .iv
                .checked_add(1)
                .ok_or(PairingError("IKE nonce counter exhausted"))?;
            let start = index * limit;
            let chunk = &plaintext[start..plaintext.len().min(start + limit)];
            out.push(encrypt_packet(
                self,
                ProtectedHeader {
                    exchange,
                    message,
                    flags,
                    first: if index == 0 { first } else { 0 },
                    fragment: if fragmented {
                        (index as u16 + 1, count as u16)
                    } else {
                        (0, 0)
                    },
                },
                chunk,
                self.iv.to_be_bytes(),
            )?);
        }
        Ok(out)
    }
    pub fn decrypt(&self, packet: &[u8], exchange: u8, message: u32, flags: u8) -> Result<Part> {
        wire::validate_header(
            packet,
            &self.spi_i,
            Some(&self.spi_r),
            exchange,
            flags,
            message,
        )?;
        let fragmented = match packet[16] {
            46 => false,
            53 => true,
            _ => return Err(PairingError("IKE response is not encrypted")),
        };
        let header_len = if fragmented { 36 } else { 32 };
        if packet.len() < header_len + 25 || wire::be16(packet, 30)? as usize != packet.len() - 28 {
            return Err(PairingError("Truncated encrypted IKE payload"));
        }
        let (number, count) = if fragmented {
            (wire::be16(packet, 32)?, wire::be16(packet, 34)?)
        } else {
            (0, 0)
        };
        if packet[29] != 0
            || (fragmented
                && (number == 0
                    || count == 0
                    || count > 16
                    || number > count
                    || (number == 1 && packet[28] == 0)
                    || (number > 1 && packet[28] != 0)))
        {
            return Err(PairingError("Invalid encrypted IKE fragment"));
        }
        let mut plain = Zeroizing::new(aes(
            false,
            &self.er,
            packet[header_len..header_len + 8].try_into().unwrap(),
            &packet[..header_len],
            &packet[header_len + 8..],
        )?);
        let padding = *plain.last().ok_or(PairingError("IKE padding is missing"))? as usize + 1;
        if padding > plain.len() {
            return Err(PairingError("Invalid IKE padding"));
        }
        let unpadded_len = plain.len() - padding;
        plain.truncate(unpadded_len);
        Ok(Part {
            number,
            count,
            first: packet[28],
            plaintext: plain,
        })
    }
}
fn aes(encrypt: bool, key: &[u8; 36], iv: [u8; 8], aad: &[u8], input: &[u8]) -> Result<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(&key[..32]).unwrap();
    let mut nonce = [0; 12];
    nonce[..4].copy_from_slice(&key[32..]);
    nonce[4..].copy_from_slice(&iv);
    let payload = Payload { msg: input, aad };
    if encrypt {
        cipher.encrypt((&nonce).into(), payload)
    } else {
        cipher.decrypt((&nonce).into(), payload)
    }
    .map_err(|_| PairingError("IKE AES-GCM authentication failed"))
}
struct ProtectedHeader {
    exchange: u8,
    message: u32,
    flags: u8,
    first: u8,
    fragment: (u16, u16),
}
fn encrypt_packet(
    keys: &Keys,
    header: ProtectedHeader,
    plaintext: &[u8],
    iv: [u8; 8],
) -> Result<Vec<u8>> {
    let ProtectedHeader {
        exchange,
        message,
        flags,
        first,
        fragment,
    } = header;
    let fragmented = fragment.0 != 0;
    let header_len = if fragmented { 36 } else { 32 };
    let length = header_len + 8 + plaintext.len() + 1 + 16;
    let mut aad = wire::header(
        &keys.spi_i,
        &keys.spi_r,
        if fragmented { 53 } else { 46 },
        exchange,
        flags,
        message,
        length,
    );
    aad.extend_from_slice(&[first, 0]);
    aad.extend_from_slice(&((length - 28) as u16).to_be_bytes());
    if fragmented {
        aad.extend_from_slice(&fragment.0.to_be_bytes());
        aad.extend_from_slice(&fragment.1.to_be_bytes());
    }
    let mut padded = Zeroizing::new(plaintext.to_vec());
    padded.push(0);
    let encrypted = aes(true, &keys.ei, iv, &aad, &padded)?;
    aad.extend_from_slice(&iv);
    aad.extend(encrypted);
    Ok(aad)
}
pub(crate) struct Part {
    pub number: u16,
    pub count: u16,
    pub first: u8,
    pub plaintext: Zeroizing<Vec<u8>>,
}
#[derive(Default)]
pub(crate) struct Fragments {
    first: u8,
    pieces: Vec<Option<Zeroizing<Vec<u8>>>>,
}
impl Fragments {
    pub fn push(&mut self, part: Part) -> Result<Option<(u8, Zeroizing<Vec<u8>>)>> {
        if part.count == 0 {
            if !self.pieces.is_empty() {
                return Err(PairingError("Mixed encrypted IKE fragments"));
            }
            return Ok(Some((part.first, part.plaintext)));
        }
        if self.pieces.is_empty() {
            self.pieces.resize_with(part.count as usize, || None);
        }
        if self.pieces.len() != part.count as usize {
            return Err(PairingError("IKE fragment count changed"));
        }
        let slot = &mut self.pieces[part.number as usize - 1];
        if let Some(previous) = slot {
            if **previous != *part.plaintext {
                return Err(PairingError("Conflicting IKE fragment retransmission"));
            }
            return Ok(None);
        }
        if part.number == 1 {
            self.first = part.first;
        }
        *slot = Some(part.plaintext);
        if self.pieces.iter().flatten().map(|s| s.len()).sum::<usize>() > wire::MAX_PLAINTEXT {
            return Err(PairingError("IKE fragment assembly exceeds limit"));
        }
        if self.pieces.iter().any(Option::is_none) {
            return Ok(None);
        }
        let mut bytes = Zeroizing::new(Vec::new());
        for piece in &self.pieces {
            bytes.extend_from_slice(piece.as_ref().unwrap());
        }
        let first = self.first;
        self.pieces.clear();
        self.first = 0;
        Ok(Some((first, bytes)))
    }
}
pub(crate) fn null_auth(key: &[u8], signed: &[&[u8]]) -> [u8; 64] {
    let pad = Zeroizing::new(prf(key, &[b"Key Pad for IKEv2"]));
    prf(&*pad, signed)
}
pub(crate) fn verify_auth(actual: &[u8], expected: &[u8]) -> Result<()> {
    if actual.ct_eq(expected).into() {
        Ok(())
    } else {
        Err(PairingError("Watch IKE authentication failed"))
    }
}
