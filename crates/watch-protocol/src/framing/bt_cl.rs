//! Apple BT_CL signaling carried on fixed LE L2CAP CID `0x003a`.
//!
//! A negotiated service such as `com.apple.terminusPairing` does not carry its
//! data in `RAW_DATA` (`0x91`). `CREATE_CHANNEL`/`ACCEPT_CHANNEL` establish a
//! separate dynamic L2CAP CID whose payload is the Scalable Pipe byte stream.

use std::fmt;

pub const SIGNALING_CID: u16 = 0x003a;
pub const CURRENT_VERSION: u8 = 0x0b;
pub const WIDE_LENGTH_VERSION: u8 = 8;

pub const REMOTE_SERVICES: u8 = 0x01;
pub const COMMON_SERVICES: u8 = 0x02;
pub const CREATE_CHANNEL: u8 = 0x03;
pub const ACCEPT_CHANNEL: u8 = 0x04;
pub const SERVICE_ADDED: u8 = 0x05;
pub const VERSION_INFO: u8 = 0x09;
pub const RAW_DATA: u8 = 0x91;

pub const TERMINUS_PAIRING_NAME: &[u8] = b"com.apple.terminusPairing\0";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CodecError {
    PayloadTooLarge { length: usize, maximum: usize },
    TooShort { actual: usize, minimum: usize },
    LengthMismatch { declared: usize, actual: usize },
    TooManyServices { count: usize },
    ServiceNameTooLong { length: usize, maximum: usize },
    Malformed(&'static str),
}

impl fmt::Display for CodecError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::PayloadTooLarge { length, maximum } => {
                write!(f, "CL payload length {length} exceeds {maximum}")
            }
            Self::TooShort { actual, minimum } => {
                write!(f, "CL data is {actual} bytes; need at least {minimum}")
            }
            Self::LengthMismatch { declared, actual } => {
                write!(
                    f,
                    "CL length mismatch: header declares {declared}, actual payload is {actual}"
                )
            }
            Self::TooManyServices { count } => {
                write!(f, "CL service count {count} exceeds 255")
            }
            Self::ServiceNameTooLong { length, maximum } => {
                write!(f, "CL service name length {length} exceeds {maximum}")
            }
            Self::Malformed(reason) => write!(f, "malformed CL payload: {reason}"),
        }
    }
}

impl std::error::Error for CodecError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pdu {
    pub opcode: u8,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct L2capPdu {
    /// CID allocated locally by the receiving peer.
    pub destination_cid: u16,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceRecord {
    pub service_id: u16,
    pub service_type: u8,
    /// Exact wire bytes. Apple endpoint names normally include their final
    /// NUL byte, and `nameLength` includes it.
    pub name: Vec<u8>,
    pub flags: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CreateChannel {
    pub initiator_local_cid: u16,
    /// Service ID chosen by the initiator in `REMOTE_SERVICES` and echoed
    /// by the responder in `COMMON_SERVICES`.
    pub common_service_id: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AcceptChannel {
    pub status: u8,
    pub service_id: u16,
    pub responder_local_cid: u16,
}

fn uses_u8_length(peer_version: u8, opcode: u8) -> bool {
    peer_version < WIDE_LENGTH_VERSION || matches!(opcode, 0x08 | VERSION_INFO)
}

/// Wrap a BT_CL or negotiated Scalable Pipe payload in the standard L2CAP
/// basic header: `length:uint16_le || destinationCID:uint16_le || payload`.
pub fn encode_l2cap_pdu(destination_cid: u16, payload: &[u8]) -> Result<Vec<u8>, CodecError> {
    if payload.len() > u16::MAX as usize {
        return Err(CodecError::PayloadTooLarge {
            length: payload.len(),
            maximum: u16::MAX as usize,
        });
    }

    let mut pdu = Vec::with_capacity(4 + payload.len());
    pdu.extend_from_slice(&(payload.len() as u16).to_le_bytes());
    pdu.extend_from_slice(&destination_cid.to_le_bytes());
    pdu.extend_from_slice(payload);
    Ok(pdu)
}

pub fn decode_l2cap_pdu(bytes: &[u8]) -> Result<L2capPdu, CodecError> {
    if bytes.len() < 4 {
        return Err(CodecError::TooShort {
            actual: bytes.len(),
            minimum: 4,
        });
    }

    let declared = u16::from_le_bytes([bytes[0], bytes[1]]) as usize;
    let actual = bytes.len() - 4;
    if declared != actual {
        return Err(CodecError::LengthMismatch { declared, actual });
    }

    Ok(L2capPdu {
        destination_cid: u16::from_le_bytes([bytes[2], bytes[3]]),
        payload: bytes[4..].to_vec(),
    })
}

/// Encode one complete BT_CL signaling PDU.
///
/// Versions below 8 use a one-byte payload length. Version 8 and later use
/// a little-endian `u16`, except opcodes `0x08` and `0x09`, which always
/// retain the legacy one-byte length.
pub fn encode_pdu(peer_version: u8, opcode: u8, payload: &[u8]) -> Result<Vec<u8>, CodecError> {
    let maximum = if uses_u8_length(peer_version, opcode) {
        u8::MAX as usize
    } else {
        u16::MAX as usize
    };

    if payload.len() > maximum {
        return Err(CodecError::PayloadTooLarge {
            length: payload.len(),
            maximum,
        });
    }

    let mut pdu =
        Vec::with_capacity(1 + if maximum == u8::MAX as usize { 1 } else { 2 } + payload.len());
    pdu.push(opcode);
    if maximum == u8::MAX as usize {
        pdu.push(payload.len() as u8);
    } else {
        pdu.extend_from_slice(&(payload.len() as u16).to_le_bytes());
    }
    pdu.extend_from_slice(payload);
    Ok(pdu)
}

/// Decode one complete L2CAP payload as a BT_CL signaling PDU.
///
/// The Apple receiver requires the encoded and actual payload lengths to
/// match exactly; trailing bytes are therefore rejected.
pub fn decode_pdu(peer_version: u8, bytes: &[u8]) -> Result<Pdu, CodecError> {
    if bytes.is_empty() {
        return Err(CodecError::TooShort {
            actual: 0,
            minimum: 2,
        });
    }

    let opcode = bytes[0];
    let short = uses_u8_length(peer_version, opcode);
    let header_len = if short { 2 } else { 3 };
    if bytes.len() < header_len {
        return Err(CodecError::TooShort {
            actual: bytes.len(),
            minimum: header_len,
        });
    }

    let declared = if short {
        bytes[1] as usize
    } else {
        u16::from_le_bytes([bytes[1], bytes[2]]) as usize
    };
    let actual = bytes.len() - header_len;
    if declared != actual {
        return Err(CodecError::LengthMismatch { declared, actual });
    }

    Ok(Pdu {
        opcode,
        payload: bytes[header_len..].to_vec(),
    })
}

/// Build the fixed-format `VERSION_INFO` payload:
/// `version:u8 || features:u32_le`.
pub fn version_payload(version: u8, features: u32) -> [u8; 5] {
    let feature_bytes = features.to_le_bytes();
    [
        version,
        feature_bytes[0],
        feature_bytes[1],
        feature_bytes[2],
        feature_bytes[3],
    ]
}

fn validate_service_name(name: &[u8]) -> Result<(), CodecError> {
    // The surrounding recordLength byte equals nameLength + 5.
    const MAX_NAME_LEN: usize = u8::MAX as usize - 5;
    if name.len() > MAX_NAME_LEN {
        return Err(CodecError::ServiceNameTooLong {
            length: name.len(),
            maximum: MAX_NAME_LEN,
        });
    }
    Ok(())
}

/// Encode the payload for opcode `REMOTE_SERVICES` (`0x01`).
pub fn remote_services_payload(records: &[ServiceRecord]) -> Result<Vec<u8>, CodecError> {
    if records.len() > u8::MAX as usize {
        return Err(CodecError::TooManyServices {
            count: records.len(),
        });
    }

    let mut payload = vec![records.len() as u8];
    for record in records {
        validate_service_name(&record.name)?;
        let record_len = record.name.len() + 5;
        payload.push(record_len as u8);
        payload.extend_from_slice(&record.service_id.to_le_bytes());
        payload.push(record.service_type);
        payload.push(record.name.len() as u8);
        payload.extend_from_slice(&record.name);
        payload.push(record.flags);
    }
    Ok(payload)
}

/// Parse the payload for opcode `REMOTE_SERVICES` (`0x01`).
pub fn parse_remote_services(payload: &[u8]) -> Result<Vec<ServiceRecord>, CodecError> {
    if payload.is_empty() {
        return Err(CodecError::TooShort {
            actual: 0,
            minimum: 1,
        });
    }

    let count = payload[0] as usize;
    let mut cursor = 1usize;
    let mut records = Vec::with_capacity(count);

    for _ in 0..count {
        if cursor >= payload.len() {
            return Err(CodecError::Malformed("missing service record length"));
        }
        let record_len = payload[cursor] as usize;
        cursor += 1;
        if record_len < 5 {
            return Err(CodecError::Malformed("service record shorter than 5 bytes"));
        }
        if payload.len() - cursor < record_len {
            return Err(CodecError::Malformed("truncated service record"));
        }

        let end = cursor + record_len;
        let service_id = u16::from_le_bytes([payload[cursor], payload[cursor + 1]]);
        let service_type = payload[cursor + 2];
        let name_len = payload[cursor + 3] as usize;
        if record_len != name_len + 5 {
            return Err(CodecError::Malformed(
                "recordLength does not equal nameLength + 5",
            ));
        }
        let name_start = cursor + 4;
        let name_end = name_start + name_len;
        let flags = payload[name_end];

        records.push(ServiceRecord {
            service_id,
            service_type,
            name: payload[name_start..name_end].to_vec(),
            flags,
        });
        cursor = end;
    }

    if cursor != payload.len() {
        return Err(CodecError::Malformed(
            "trailing bytes after declared service records",
        ));
    }
    Ok(records)
}

/// Encode `count:u8 || serviceID:uint16_le[count]`.
pub fn common_services_payload(service_ids: &[u16]) -> Result<Vec<u8>, CodecError> {
    if service_ids.len() > u8::MAX as usize {
        return Err(CodecError::TooManyServices {
            count: service_ids.len(),
        });
    }

    let mut payload = Vec::with_capacity(1 + service_ids.len() * 2);
    payload.push(service_ids.len() as u8);
    for service_id in service_ids {
        payload.extend_from_slice(&service_id.to_le_bytes());
    }
    Ok(payload)
}

/// Encode the payload for opcode `SERVICE_ADDED` (`0x05`).
pub fn service_added_payload(record: &ServiceRecord) -> Result<Vec<u8>, CodecError> {
    validate_service_name(&record.name)?;
    let mut payload = Vec::with_capacity(record.name.len() + 5);
    payload.extend_from_slice(&record.service_id.to_le_bytes());
    payload.push(record.service_type);
    payload.push(record.name.len() as u8);
    payload.extend_from_slice(&record.name);
    payload.push(record.flags);
    Ok(payload)
}

/// Encode `initiatorLocalCID:uint16_le || commonServiceID:uint16_le`.
pub fn create_channel_payload(channel: CreateChannel) -> [u8; 4] {
    let cid = channel.initiator_local_cid.to_le_bytes();
    let service = channel.common_service_id.to_le_bytes();
    [cid[0], cid[1], service[0], service[1]]
}

pub fn parse_create_channel(payload: &[u8]) -> Result<CreateChannel, CodecError> {
    if payload.len() != 4 {
        return Err(CodecError::LengthMismatch {
            declared: 4,
            actual: payload.len(),
        });
    }
    Ok(CreateChannel {
        initiator_local_cid: u16::from_le_bytes([payload[0], payload[1]]),
        common_service_id: u16::from_le_bytes([payload[2], payload[3]]),
    })
}

/// Encode
/// `status:u8 || serviceID:uint16_le || responderLocalCID:uint16_le`.
pub fn accept_channel_payload(channel: AcceptChannel) -> [u8; 5] {
    let service = channel.service_id.to_le_bytes();
    let cid = channel.responder_local_cid.to_le_bytes();
    [channel.status, service[0], service[1], cid[0], cid[1]]
}

pub fn parse_accept_channel(payload: &[u8]) -> Result<AcceptChannel, CodecError> {
    if payload.len() != 5 {
        return Err(CodecError::LengthMismatch {
            declared: 5,
            actual: payload.len(),
        });
    }
    Ok(AcceptChannel {
        status: payload[0],
        service_id: u16::from_le_bytes([payload[1], payload[2]]),
        responder_local_cid: u16::from_le_bytes([payload[3], payload[4]]),
    })
}
