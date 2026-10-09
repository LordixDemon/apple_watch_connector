use crate::{
    PairingError, Result,
    crypto::{self, Fragments, Keys},
    wire::{self, SaResponse},
};
use ml_kem::{Decapsulate, KeyExport, ml_kem_1024::DecapsulationKey};
use sha2::{Digest, Sha256};
use watch_protocol::framing::{UikeStreamDecoder, encode_uike_frame};
use zeroize::Zeroizing;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ControlStage {
    SaInit,
    AdditionalKeyExchange,
    Authentication,
    SelectingCode,
    PinRequired,
}

/// One peer's control SA. This SA selects PIN authentication; a separate SA
/// proves the supplied PIN. No reset, erase, activation or IDS command is sent.
pub struct ControlSession {
    stage: ControlStage,
    spi: [u8; 8],
    nonce: [u8; 32],
    secret: Zeroizing<[u8; 56]>,
    initial_packet: Vec<u8>,
    peer: Option<SaResponse>,
    keys: Option<Keys>,
    kem: Option<DecapsulationKey>,
    int_i: Zeroizing<[u8; 64]>,
    int_r: Zeroizing<[u8; 64]>,
    decoder: UikeStreamDecoder,
    fragments: Fragments,
    peer_fragments: Fragments,
    salt: Option<Zeroizing<Vec<u8>>>,
    receipts: Vec<([u8; 32], Vec<Vec<u8>>)>,
}
impl ControlSession {
    pub fn new() -> Result<Self> {
        let mut spi = crypto::random()?;
        if spi == [0; 8] {
            spi[7] = 1;
        }
        let nonce = crypto::random()?;
        let secret = Zeroizing::new(crypto::random()?);
        let public = x448::x448(*secret, x448::X448_BASEPOINT_BYTES).expect("Valid X448 basepoint");
        let initial_packet = wire::sa_init(&spi, &nonce, &public, false);
        Ok(Self {
            stage: ControlStage::SaInit,
            spi,
            nonce,
            secret,
            initial_packet,
            peer: None,
            keys: None,
            kem: None,
            int_i: Zeroizing::new([0; 64]),
            int_r: Zeroizing::new([0; 64]),
            decoder: UikeStreamDecoder::new(),
            fragments: Fragments::default(),
            peer_fragments: Fragments::default(),
            salt: None,
            receipts: Vec::new(),
        })
    }
    pub fn stage(&self) -> ControlStage {
        self.stage
    }
    pub(crate) fn spi(&self) -> [u8; 8] {
        self.spi
    }
    pub fn start(&self) -> Result<Vec<u8>> {
        frame(&self.initial_packet)
    }
    pub(crate) fn salt(&self) -> Result<&[u8]> {
        self.salt
            .as_deref()
            .map(|s| s.as_slice())
            .ok_or(PairingError("No verified PIN challenge"))
    }
    pub fn receive(&mut self, bytes: &[u8]) -> Result<Vec<Vec<u8>>> {
        if bytes.len() > 2048 {
            return Err(PairingError("Pairing input chunk exceeds limit"));
        }
        let packets = self
            .decoder
            .push(bytes)
            .map_err(|_| PairingError("Malformed uIKE stream"))?;
        let mut out = Vec::new();
        for packet in packets {
            let fingerprint: [u8; 32] = Sha256::digest(&packet).into();
            if let Some((_, cached)) = self.receipts.iter().find(|(hash, _)| *hash == fingerprint) {
                out.extend(cached.clone());
                continue;
            }
            let replies = self.packet(&packet)?;
            if self.receipts.len() >= 64 {
                return Err(PairingError("Control exchange packet limit exceeded"));
            }
            self.receipts.push((fingerprint, replies.clone()));
            out.extend(replies);
        }
        Ok(out)
    }
    pub(crate) fn packet(&mut self, packet: &[u8]) -> Result<Vec<Vec<u8>>> {
        match self.stage {
            ControlStage::SaInit => {
                let peer = wire::parse_sa_response(packet, &self.spi, false)?;
                let mut keys = Keys::initial(&self.secret, self.spi, self.nonce, &peer)?;
                let seed = Zeroizing::new(crypto::random::<64>()?);
                let kem = DecapsulationKey::from_seed((*seed).into());
                let public = kem.encapsulation_key().to_bytes();
                let mut body = vec![0, 37, 0, 0];
                body.extend_from_slice(&public);
                let plaintext = wire::payload(0, &body);
                *self.int_i = keys.int_auth(false, 34, 0, &plaintext);
                let requests = keys.seal(43, 1, 8, 34, &plaintext)?;
                self.keys = Some(keys);
                self.peer = Some(peer);
                self.kem = Some(kem);
                self.stage = ControlStage::AdditionalKeyExchange;
                frames(requests)
            }
            ControlStage::AdditionalKeyExchange => {
                let keys = self.keys.as_ref().unwrap();
                let part = keys.decrypt(packet, 43, 1, 32)?;
                let Some((first, plaintext)) = self.fragments.push(part)? else {
                    return Ok(Vec::new());
                };
                let ciphertext = wire::parse_ke(first, &plaintext)?;
                *self.int_r = keys.int_auth(true, first, 0, &plaintext);
                let kem = self.kem.take().unwrap();
                let mut shared = kem
                    .decapsulate_slice(ciphertext)
                    .map_err(|_| PairingError("Malformed ML-KEM ciphertext"))?;
                let mut updated = keys.update(&shared)?;
                zeroize::Zeroize::zeroize(&mut shared);
                let idi = [13, 0, 0, 0];
                let idr = [&[11, 0, 0, 0][..], wire::CONTROL_ID].concat();
                let id_auth = Zeroizing::new(crypto::prf(&updated.pi, &[&idi]));
                let auth = Zeroizing::new(crypto::null_auth(
                    &updated.pi,
                    &[
                        &self.initial_packet,
                        &updated.nr,
                        &*id_auth,
                        &*self.int_i,
                        &*self.int_r,
                        &2u32.to_be_bytes(),
                    ],
                ));
                let mut auth_body = Zeroizing::new(vec![13, 0, 0, 0]);
                auth_body.extend_from_slice(&*auth);
                let plaintext = Zeroizing::new(
                    [
                        wire::payload(41, &idi),
                        wire::notify(36, 0x4000, &[]),
                        wire::payload(39, &idr),
                        wire::payload(0, &auth_body),
                    ]
                    .concat(),
                );
                let requests = updated.seal(35, 2, 8, 35, &plaintext)?;
                self.keys = Some(updated);
                self.stage = ControlStage::Authentication;
                frames(requests)
            }
            ControlStage::Authentication => {
                let keys = self.keys.as_ref().unwrap();
                let part = keys.decrypt(packet, 35, 2, 32)?;
                let Some((first, plaintext)) = self.fragments.push(part)? else {
                    return Ok(Vec::new());
                };
                self.verify_control_auth(first, &plaintext)?;
                let plaintext = wire::notify(0, 0xc545, &[1, 0, 1, 2]);
                let requests = self.keys.as_mut().unwrap().seal(37, 3, 8, 41, &plaintext)?;
                self.stage = ControlStage::SelectingCode;
                frames(requests)
            }
            ControlStage::SelectingCode | ControlStage::PinRequired => {
                // The physical Watch may ACK our request with an empty response
                // and deliver C546 as its own informational request, message 0.
                if packet.len() < 28 {
                    return Err(PairingError("Truncated PIN response"));
                }
                let is_peer_request = packet[19] == 0;
                let message = if is_peer_request { 0 } else { 3 };
                let part = self.keys.as_ref().unwrap().decrypt(
                    packet,
                    37,
                    message,
                    if is_peer_request { 0 } else { 32 },
                )?;
                let fragments = if is_peer_request {
                    &mut self.peer_fragments
                } else {
                    &mut self.fragments
                };
                let Some((first, plaintext)) = fragments.push(part)? else {
                    return Ok(Vec::new());
                };
                if first == 0 && plaintext.is_empty() && !is_peer_request {
                    return Ok(Vec::new());
                }
                let salt = Zeroizing::new(wire::parse_pin_salt(first, &plaintext)?);
                if let Some(previous) = &self.salt {
                    crypto::verify_auth(previous, &salt)?;
                }
                self.salt = Some(salt);
                self.stage = ControlStage::PinRequired;
                if is_peer_request {
                    frames(self.keys.as_mut().unwrap().seal(37, 0, 0x28, 0, &[])?)
                } else {
                    Ok(Vec::new())
                }
            }
        }
    }
    fn verify_control_auth(&self, first: u8, plaintext: &[u8]) -> Result<()> {
        let keys = self.keys.as_ref().unwrap();
        let mut idr = None;
        let mut auth = None;
        for part in wire::parse_payloads(first, plaintext)? {
            match part.kind {
                36 if idr.is_none()
                    && part.body.len() == wire::CONTROL_ID.len() + 4
                    && part.body[..4] == [11, 0, 0, 0]
                    && part.body[4..] == *wire::CONTROL_ID =>
                {
                    idr = Some(part.body)
                }
                39 if auth.is_none()
                    && part.body.len() == 68
                    && part.body[..4] == [13, 0, 0, 0] =>
                {
                    auth = Some(&part.body[4..])
                }
                41 if wire::be16(part.body, 2)? >= 0x4000 => (),
                33 | 35 | 36 | 39 | 41 | 44 | 45 => {
                    return Err(PairingError("Unexpected control authentication payload"));
                }
                _ if part.flags & 0x80 != 0 => {
                    return Err(PairingError("Unknown critical authentication payload"));
                }
                _ => (),
            }
        }
        let idr = idr.ok_or(PairingError("Watch control identity is missing"))?;
        let auth = auth.ok_or(PairingError("Watch control authentication is missing"))?;
        let id_auth = Zeroizing::new(crypto::prf(&keys.pr, &[idr]));
        let expected = Zeroizing::new(crypto::null_auth(
            &keys.pr,
            &[
                &self.peer.as_ref().unwrap().packet,
                &self.nonce,
                &*id_auth,
                &*self.int_i,
                &*self.int_r,
                &2u32.to_be_bytes(),
            ],
        ));
        crypto::verify_auth(auth, &*expected)
    }
}
pub(crate) fn frame(packet: &[u8]) -> Result<Vec<u8>> {
    encode_uike_frame(packet).map_err(|_| PairingError("IKE output exceeds framing limit"))
}
pub(crate) fn frames(packets: Vec<Vec<u8>>) -> Result<Vec<Vec<u8>>> {
    packets.iter().map(|p| frame(p)).collect()
}
