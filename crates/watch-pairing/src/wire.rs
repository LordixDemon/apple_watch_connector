//! Wire layout ported from the working IkeV2Codec/IkeV2SessionCrypto Java code.
use crate::{PairingError, Result};
pub(crate) const CONTROL_ID: &[u8] = b"com.apple.networkrelay.companionlink.pairing.control";
pub(crate) const MAX_PLAINTEXT: usize = 16_384;

pub(crate) fn be16(bytes: &[u8], offset: usize) -> Result<u16> {
    let v = bytes
        .get(offset..offset + 2)
        .ok_or(PairingError("Truncated IKE field"))?;
    Ok(u16::from_be_bytes([v[0], v[1]]))
}
pub(crate) fn be32(bytes: &[u8], offset: usize) -> Result<u32> {
    let v = bytes
        .get(offset..offset + 4)
        .ok_or(PairingError("Truncated IKE field"))?;
    Ok(u32::from_be_bytes(v.try_into().unwrap()))
}
pub(crate) fn header(
    spi_i: &[u8; 8],
    spi_r: &[u8; 8],
    next: u8,
    exchange: u8,
    flags: u8,
    message: u32,
    length: usize,
) -> Vec<u8> {
    let mut out = Vec::with_capacity(length);
    out.extend_from_slice(spi_i);
    out.extend_from_slice(spi_r);
    out.extend_from_slice(&[next, 0x20, exchange, flags]);
    out.extend_from_slice(&message.to_be_bytes());
    out.extend_from_slice(&(length as u32).to_be_bytes());
    out
}
pub(crate) fn validate_header(
    packet: &[u8],
    spi_i: &[u8; 8],
    spi_r: Option<&[u8; 8]>,
    exchange: u8,
    flags: u8,
    message: u32,
) -> Result<()> {
    if packet.len() < 28
        || packet.len() > MAX_PLAINTEXT + 256
        || packet[..8] != *spi_i
        || spi_r.is_some_and(|spi| packet[8..16] != *spi)
        || packet[17] != 0x20
        || packet[18] != exchange
        || packet[19] != flags
        || be32(packet, 20)? != message
        || be32(packet, 24)? as usize != packet.len()
    {
        return Err(PairingError("Unexpected IKE header"));
    }
    Ok(())
}
pub(crate) fn payload(next: u8, body: &[u8]) -> Vec<u8> {
    assert!(body.len() < u16::MAX as usize - 4);
    let mut out = vec![next, 0];
    out.extend_from_slice(&((body.len() + 4) as u16).to_be_bytes());
    out.extend_from_slice(body);
    out
}
pub(crate) fn notify(next: u8, kind: u16, data: &[u8]) -> Vec<u8> {
    let mut body = vec![0, 0];
    body.extend_from_slice(&kind.to_be_bytes());
    body.extend_from_slice(data);
    payload(next, &body)
}
pub(crate) struct Payload<'a> {
    pub kind: u8,
    pub flags: u8,
    pub body: &'a [u8],
}
pub(crate) fn parse_payloads(mut kind: u8, bytes: &[u8]) -> Result<Vec<Payload<'_>>> {
    if bytes.len() > MAX_PLAINTEXT {
        return Err(PairingError("IKE payload chain exceeds limit"));
    }
    let mut offset = 0;
    let mut out = Vec::new();
    while kind != 0 {
        let length = be16(bytes, offset + 2)? as usize;
        if length < 4 || offset + length > bytes.len() || out.len() >= 64 {
            return Err(PairingError("Invalid IKE payload chain"));
        }
        out.push(Payload {
            kind,
            flags: bytes[offset + 1],
            body: &bytes[offset + 4..offset + length],
        });
        kind = bytes[offset];
        offset += length;
    }
    if offset != bytes.len() {
        return Err(PairingError("Trailing IKE payload bytes"));
    }
    Ok(out)
}
pub(crate) fn transform(next: bool, kind: u8, id: u16, attributes: &[u8]) -> Vec<u8> {
    let mut out = vec![if next { 3 } else { 0 }, 0];
    out.extend_from_slice(&((8 + attributes.len()) as u16).to_be_bytes());
    out.extend_from_slice(&[kind, 0]);
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(attributes);
    out
}
fn proposal() -> Vec<u8> {
    let transforms = [
        transform(true, 1, 20, &[0x80, 14, 1, 0]),
        transform(true, 1, 28, &[]),
        transform(true, 2, 7, &[]),
        transform(true, 6, 37, &[]),
        transform(true, 4, 32, &[]),
        transform(false, 4, 31, &[]),
    ]
    .concat();
    let mut out = vec![0, 0];
    out.extend_from_slice(&((8 + transforms.len()) as u16).to_be_bytes());
    out.extend_from_slice(&[1, 1, 0, 6]);
    out.extend(transforms);
    out
}
pub(crate) fn sa_init(
    spi: &[u8; 8],
    nonce: &[u8; 32],
    public: &[u8; 56],
    pairing: bool,
) -> Vec<u8> {
    let mut ke = vec![0, 32, 0, 0];
    ke.extend_from_slice(public);
    let mut body = [
        payload(34, &proposal()),
        payload(40, &ke),
        payload(41, nonce),
        notify(41, 0x4004, &[0; 20]),
        notify(41, 0x4005, &[0; 20]),
        notify(41, 0x402e, &[]),
    ]
    .concat();
    if pairing {
        body.extend(notify(41, 0x4028, &[0x2a, 0xf9]));
        body.extend(notify(41, 0x4033, &[]));
    }
    body.extend(notify(0, 0x4036, &[]));
    let mut out = header(spi, &[0; 8], 33, 34, 8, 0, body.len() + 28);
    out.extend(body);
    out
}
fn validate_proposal(body: &[u8]) -> Result<()> {
    if body.len() < 8
        || body[..2] != [0, 0]
        || be16(body, 2)? as usize != body.len()
        || body[4..8] != [1, 1, 0, 4]
    {
        return Err(PairingError("Unexpected selected IKE proposal"));
    }
    let mut offset = 8;
    let mut seen = [false; 4];
    for index in 0..4 {
        let length = be16(body, offset + 2)? as usize;
        if length < 8
            || offset + length > body.len()
            || body[offset] != if index < 3 { 3 } else { 0 }
            || body[offset + 1] != 0
            || body[offset + 5] != 0
        {
            return Err(PairingError("Invalid selected IKE transform"));
        }
        let attributes = &body[offset + 8..offset + length];
        let slot = match (body[offset + 4], be16(body, offset + 6)?, attributes) {
            (1, 20, [0x80, 14, 1, 0]) => 0,
            (2, 7, []) => 1,
            (6, 37, []) => 2,
            (4, 32, []) => 3,
            _ => return Err(PairingError("Unsupported selected IKE transform")),
        };
        if seen[slot] {
            return Err(PairingError("Duplicate selected IKE transform"));
        }
        seen[slot] = true;
        offset += length;
    }
    if offset != body.len() || seen != [true; 4] {
        return Err(PairingError("Incomplete selected IKE proposal"));
    }
    Ok(())
}
pub(crate) struct SaResponse {
    pub spi: [u8; 8],
    pub nonce: [u8; 32],
    pub public: [u8; 56],
    pub packet: Vec<u8>,
}
pub(crate) fn parse_sa_response(packet: &[u8], spi: &[u8; 8], pairing: bool) -> Result<SaResponse> {
    validate_header(packet, spi, None, 34, 32, 0)?;
    let spi_r: [u8; 8] = packet[8..16].try_into().unwrap();
    if spi_r == [0; 8] {
        return Err(PairingError("Watch returned an empty responder SPI"));
    }
    let mut nonce = None;
    let mut public = None;
    let mut sa = false;
    let mut childless = false;
    let mut intermediate = false;
    let mut password = false;
    let mut ppk = false;
    for part in parse_payloads(packet[16], &packet[28..])? {
        match part.kind {
            33 if !sa => {
                validate_proposal(part.body)?;
                sa = true;
            }
            34 if public.is_none() && part.body.len() == 60 && part.body[..4] == [0, 32, 0, 0] => {
                public = Some(part.body[4..].try_into().unwrap())
            }
            40 if nonce.is_none() && part.body.len() == 32 => {
                nonce = Some(part.body.try_into().unwrap())
            }
            41 => {
                let kind = be16(part.body, 2)?;
                if kind < 0x4000 {
                    return Err(PairingError("Watch rejected IKE SA initialization"));
                }
                if part.body[1] != 0
                    || (part.body[0] != 0 && !(kind == 0x4022 && part.body[0] == 1))
                {
                    return Err(PairingError("Unexpected SA initialization notification"));
                }
                match kind {
                    0x4022 => childless = true,
                    0x4036 => intermediate = true,
                    0x4028 if part.body[4..] == [0x2a, 0xf9] => password = true,
                    0x4033 if part.body.len() == 4 => ppk = true,
                    _ => (),
                }
            }
            33 | 34 | 40 => return Err(PairingError("Duplicate or invalid IKE SA payload")),
            _ if part.flags & 0x80 != 0 => {
                return Err(PairingError("Unknown critical IKE SA payload"));
            }
            _ => (),
        }
    }
    if !sa || !childless || !intermediate || (pairing && (!password || !ppk)) {
        return Err(PairingError(
            "Watch did not select the required childless IKE profile",
        ));
    }
    Ok(SaResponse {
        spi: spi_r,
        nonce: nonce.ok_or(PairingError("Missing responder nonce"))?,
        public: public.ok_or(PairingError("Missing responder X448 key"))?,
        packet: packet.to_vec(),
    })
}
pub(crate) fn parse_ke(first: u8, bytes: &[u8]) -> Result<&[u8]> {
    let parts = parse_payloads(first, bytes)?;
    if parts.len() != 1
        || parts[0].kind != 34
        || parts[0].body.len() != 1572
        || parts[0].body[..4] != [0, 37, 0, 0]
    {
        return Err(PairingError("Invalid ML-KEM-1024 response"));
    }
    Ok(&parts[0].body[4..])
}
pub(crate) fn parse_pin_salt(first: u8, bytes: &[u8]) -> Result<Vec<u8>> {
    let mut method = None;
    let mut salt = None;
    let mut response = false;
    for part in parse_payloads(first, bytes)? {
        if part.kind != 41 {
            if part.flags & 0x80 != 0 {
                return Err(PairingError("Unknown critical PIN payload"));
            }
            continue;
        }
        let kind = be16(part.body, 2)?;
        if kind < 0x4000 {
            return Err(PairingError("Watch rejected PIN authentication"));
        }
        if kind != 0xc546 {
            continue;
        }
        if response || part.body[..2] != [0, 0] {
            return Err(PairingError("Invalid PIN salt notification"));
        }
        response = true;
        let mut offset = 4;
        while offset < part.body.len() {
            let kind = part.body[offset];
            let length = be16(part.body, offset + 1)? as usize;
            offset += 3;
            let value = part
                .body
                .get(offset..offset + length)
                .ok_or(PairingError("Truncated PIN TLV"))?;
            match kind {
                1 if method.is_none() && value.len() == 1 => method = Some(value[0]),
                2 if salt.is_none() && (32..=256).contains(&value.len()) => {
                    salt = Some(value.to_vec())
                }
                1 | 2 => return Err(PairingError("Duplicate or invalid PIN TLV")),
                _ => (),
            }
            offset += length;
        }
    }
    if method != Some(2) {
        return Err(PairingError("Watch did not select code pairing"));
    }
    salt.ok_or(PairingError("Watch did not provide a PIN salt"))
}
