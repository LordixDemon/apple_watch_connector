//! Portable codecs for the Apple Watch setup transport recovered from iOS 26.6
//! (23G71), plus deterministic Qualcomm DIAG framing used by the stock
//! OnePlus factory ingress.
//!
//! This crate intentionally contains no Bluetooth or device-access code. It is
//! a deterministic implementation of the BT_CL signaling and NetworkRelay
//! uIKE framing recovered from `bluetoothd`, `terminusd`, and `NetworkRelay`.

use std::fmt;

/// `NRTLVTypeIKEv2PointToPoint` according to
/// `_createStringFromNRTLVType` in NetworkRelay.
pub const IKEV2_POINT_TO_POINT_TYPE: u8 = 0x04;

const HEADER_LEN: usize = 3;
const CHECKSUM_LEN: usize = 2;
const MAX_PAYLOAD_LEN: usize = u16::MAX as usize;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrameError {
    PayloadTooLarge { length: usize, maximum: usize },
    UnexpectedType { received: u8, expected: u8 },
    ChecksumMismatch { received: u16, expected: u16 },
}

impl fmt::Display for FrameError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::PayloadTooLarge { length, maximum } => {
                write!(f, "payload length {length} exceeds {maximum}")
            }
            Self::UnexpectedType { received, expected } => {
                write!(
                    f,
                    "unexpected NRTLV type 0x{received:02x}; expected 0x{expected:02x}"
                )
            }
            Self::ChecksumMismatch { received, expected } => {
                write!(
                    f,
                    "checksum mismatch: received 0x{received:04x}, expected 0x{expected:04x}"
                )
            }
        }
    }
}

impl std::error::Error for FrameError {}

/// Compute the 16-bit one's-complement Internet checksum in network byte order.
///
/// The input is split into big-endian 16-bit words. An odd final byte is the
/// high byte of a word whose low byte is zero. End-around carry is applied
/// before the one's complement.
pub fn internet_checksum(data: &[u8]) -> u16 {
    let mut sum = 0u32;
    let (chunks, remainder) = data.as_chunks::<2>();

    for chunk in chunks {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
        sum = (sum & 0xffff) + (sum >> 16);
    }

    if let [last] = remainder {
        sum += (*last as u32) << 8;
    }

    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }

    !(sum as u16)
}

/// Encode one IKEv2/uIKE payload as:
///
/// `0x04 || uint16_be(payload_len) || payload || checksum16_be`
///
/// The checksum covers the type, length and payload, but not the checksum field
/// itself.
pub fn encode_uike_frame(payload: &[u8]) -> Result<Vec<u8>, FrameError> {
    if payload.len() > MAX_PAYLOAD_LEN {
        return Err(FrameError::PayloadTooLarge {
            length: payload.len(),
            maximum: MAX_PAYLOAD_LEN,
        });
    }

    let mut frame = Vec::with_capacity(HEADER_LEN + payload.len() + CHECKSUM_LEN);
    frame.push(IKEV2_POINT_TO_POINT_TYPE);
    frame.extend_from_slice(&(payload.len() as u16).to_be_bytes());
    frame.extend_from_slice(payload);

    let checksum = internet_checksum(&frame);
    frame.extend_from_slice(&checksum.to_be_bytes());
    Ok(frame)
}

/// Incremental decoder for a byte stream from the Scalable Pipe equivalent.
///
/// It accepts fragmented input and returns zero or more complete IKE payloads.
/// On a malformed frame the buffered bytes are retained for forensic
/// inspection; call [`Self::reset`] before attempting to decode a new stream.
#[derive(Debug, Default)]
pub struct UikeStreamDecoder {
    buffer: Vec<u8>,
}

impl UikeStreamDecoder {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push(&mut self, bytes: &[u8]) -> Result<Vec<Vec<u8>>, FrameError> {
        self.buffer.extend_from_slice(bytes);
        let mut payloads = Vec::new();

        loop {
            if self.buffer.len() < HEADER_LEN {
                break;
            }

            let received_type = self.buffer[0];
            if received_type != IKEV2_POINT_TO_POINT_TYPE {
                return Err(FrameError::UnexpectedType {
                    received: received_type,
                    expected: IKEV2_POINT_TO_POINT_TYPE,
                });
            }

            let payload_len = u16::from_be_bytes([self.buffer[1], self.buffer[2]]) as usize;
            let checksum_offset = HEADER_LEN + payload_len;
            let frame_len = checksum_offset + CHECKSUM_LEN;

            if self.buffer.len() < frame_len {
                break;
            }

            let received_checksum = u16::from_be_bytes([
                self.buffer[checksum_offset],
                self.buffer[checksum_offset + 1],
            ]);
            let expected_checksum = internet_checksum(&self.buffer[..checksum_offset]);

            if received_checksum != expected_checksum {
                return Err(FrameError::ChecksumMismatch {
                    received: received_checksum,
                    expected: expected_checksum,
                });
            }

            payloads.push(self.buffer[HEADER_LEN..checksum_offset].to_vec());
            self.buffer.drain(..frame_len);
        }

        Ok(payloads)
    }

    pub fn buffered_len(&self) -> usize {
        self.buffer.len()
    }

    pub fn buffered_bytes(&self) -> &[u8] {
        &self.buffer
    }

    pub fn reset(&mut self) {
        self.buffer.clear();
    }
}

/// Apple BT_CL signaling carried on fixed LE L2CAP CID `0x003a`.
///
/// A negotiated service such as `com.apple.terminusPairing` does not carry its
/// data in `RAW_DATA` (`0x91`). `CREATE_CHANNEL`/`ACCEPT_CHANNEL` establish a
/// separate dynamic L2CAP CID whose payload is the Scalable Pipe byte stream.
pub mod bt_cl {
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
}

/// Qualcomm DIAG wire framing used by the exact CPH2653 `diag-router`.
///
/// This module deliberately performs no USB or device I/O. It only builds and
/// validates byte strings so a packet can be reviewed and logged before a
/// separately authorized physical experiment.
pub mod qualcomm_diag {
    use std::fmt;

    pub const CONTROL_CHAR: u8 = 0x7e;
    pub const ESCAPE_CHAR: u8 = 0x7d;
    pub const ESCAPE_MASK: u8 = 0x20;
    pub const NON_HDLC_VERSION: u8 = 1;
    pub const NON_HDLC_HEADER_LEN: usize = 4;

    pub const FTM_BOOTSTRAP_START: [u8; 6] = [0x4b, 0x0b, 0xfb, 0x04, 0x00, 0x01];
    pub const FTM_BOOTSTRAP_STOP: [u8; 6] = [0x4b, 0x0b, 0xfb, 0x04, 0x00, 0x00];
    pub const HCI_READ_LOCAL_VERSION_INFORMATION: [u8; 4] = [0x01, 0x01, 0x10, 0x00];
    pub const LOG_BT_HCI_EVENT: u16 = 0x1366;
    pub const LOG_BT_HCI_ACL: u16 = 0x1368;

    const DIAG_SUBSYS_CMD: u8 = 0x4b;
    const FTM_DIAG_SUBSYS_ID: u8 = 0x0b;
    const FTM_BT_HCI_COMMAND: u16 = 0x0004;
    const FTM_BT_HCI_HEADER_LEN: usize = 10;
    const LOG_CONFIG_COMMAND: u32 = 0x73;
    const LOG_CONFIG_SET_MASK_OPERATION: u32 = 3;
    const LOG_CONFIG_HEADER_LEN: usize = 16;
    const MAX_LOG_ITEM: u16 = 0x0fff;

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub enum DiagFrameError {
        PayloadTooLarge {
            length: usize,
            maximum: usize,
        },
        EmptyH4Packet,
        EmptyLogCodeSet,
        MixedLogEquipIds {
            expected: u8,
            received: u8,
            log_code: u16,
        },
        MissingTerminator,
        UnexpectedControl {
            offset: usize,
        },
        DanglingEscape,
        TooShort {
            actual: usize,
            minimum: usize,
        },
        ChecksumMismatch {
            received: u16,
            expected: u16,
        },
        InvalidNonHdlcStart {
            received: u8,
        },
        InvalidNonHdlcVersion {
            received: u8,
            expected: u8,
        },
        NonHdlcLengthMismatch {
            declared: usize,
            actual: usize,
        },
    }

    impl fmt::Display for DiagFrameError {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            match self {
                Self::PayloadTooLarge { length, maximum } => {
                    write!(f, "DIAG payload length {length} exceeds {maximum}")
                }
                Self::EmptyH4Packet => write!(f, "an H4 packet must contain a packet-type byte"),
                Self::EmptyLogCodeSet => write!(f, "a DIAG log mask needs at least one log code"),
                Self::MixedLogEquipIds {
                    expected,
                    received,
                    log_code,
                } => {
                    write!(
                        f,
                        "log code 0x{log_code:04x} uses equip ID {received}; expected {expected}"
                    )
                }
                Self::MissingTerminator => write!(f, "DIAG frame has no final 0x7e"),
                Self::UnexpectedControl { offset } => {
                    write!(f, "unescaped 0x7e inside DIAG frame at offset {offset}")
                }
                Self::DanglingEscape => write!(f, "DIAG frame ends with a dangling 0x7d escape"),
                Self::TooShort { actual, minimum } => {
                    write!(f, "DIAG frame is {actual} bytes; need at least {minimum}")
                }
                Self::ChecksumMismatch { received, expected } => {
                    write!(
                        f,
                        "DIAG CRC mismatch: received 0x{received:04x}, expected 0x{expected:04x}"
                    )
                }
                Self::InvalidNonHdlcStart { received } => {
                    write!(
                        f,
                        "non-HDLC frame starts with 0x{received:02x}; expected 0x7e"
                    )
                }
                Self::InvalidNonHdlcVersion { received, expected } => {
                    write!(f, "non-HDLC version is {received}; expected {expected}")
                }
                Self::NonHdlcLengthMismatch { declared, actual } => {
                    write!(
                        f,
                        "non-HDLC length mismatch: header declares {declared}, actual payload is {actual}"
                    )
                }
            }
        }
    }

    impl std::error::Error for DiagFrameError {}

    /// Qualcomm's reflected CRC-16/X-25 calculation:
    /// seed `0xffff`, polynomial `0x8408`, final one's complement.
    pub fn crc16_l(payload: &[u8]) -> u16 {
        let mut crc = 0xffffu16;
        for &byte in payload {
            crc ^= byte as u16;
            for _ in 0..8 {
                crc = if crc & 1 != 0 {
                    (crc >> 1) ^ 0x8408
                } else {
                    crc >> 1
                };
            }
        }
        !crc
    }

    fn push_escaped(output: &mut Vec<u8>, byte: u8) {
        if matches!(byte, CONTROL_CHAR | ESCAPE_CHAR) {
            output.push(ESCAPE_CHAR);
            output.push(byte ^ ESCAPE_MASK);
        } else {
            output.push(byte);
        }
    }

    /// Encode `payload || crc16_le || 0x7e`, escaping `0x7d` and `0x7e`.
    ///
    /// Qualcomm's encoder does not prepend a delimiter; the final `0x7e`
    /// terminates the frame.
    pub fn encode_hdlc(payload: &[u8]) -> Vec<u8> {
        let crc = crc16_l(payload);
        let mut frame = Vec::with_capacity((payload.len() + 2) * 2 + 1);
        for &byte in payload {
            push_escaped(&mut frame, byte);
        }
        for byte in crc.to_le_bytes() {
            push_escaped(&mut frame, byte);
        }
        frame.push(CONTROL_CHAR);
        frame
    }

    /// Decode one complete Qualcomm HDLC frame and verify its CRC.
    pub fn decode_hdlc(frame: &[u8]) -> Result<Vec<u8>, DiagFrameError> {
        if frame.last() != Some(&CONTROL_CHAR) {
            return Err(DiagFrameError::MissingTerminator);
        }

        let mut decoded = Vec::with_capacity(frame.len().saturating_sub(1));
        let mut offset = 0usize;
        while offset + 1 < frame.len() {
            let byte = frame[offset];
            if byte == CONTROL_CHAR {
                return Err(DiagFrameError::UnexpectedControl { offset });
            }
            if byte == ESCAPE_CHAR {
                offset += 1;
                if offset + 1 >= frame.len() {
                    return Err(DiagFrameError::DanglingEscape);
                }
                decoded.push(frame[offset] ^ ESCAPE_MASK);
            } else {
                decoded.push(byte);
            }
            offset += 1;
        }

        const MIN_DECODED_LEN: usize = 3; // one command byte plus two CRC bytes
        if decoded.len() < MIN_DECODED_LEN {
            return Err(DiagFrameError::TooShort {
                actual: decoded.len(),
                minimum: MIN_DECODED_LEN,
            });
        }

        let payload_len = decoded.len() - 2;
        let received = u16::from_le_bytes([decoded[payload_len], decoded[payload_len + 1]]);
        let expected = crc16_l(&decoded[..payload_len]);
        if received != expected {
            return Err(DiagFrameError::ChecksumMismatch { received, expected });
        }
        decoded.truncate(payload_len);
        Ok(decoded)
    }

    /// Encode Qualcomm framing version 1:
    /// `0x7e || 0x01 || uint16_le(payload_len) || payload || 0x7e`.
    pub fn encode_non_hdlc(payload: &[u8]) -> Result<Vec<u8>, DiagFrameError> {
        if payload.len() > u16::MAX as usize {
            return Err(DiagFrameError::PayloadTooLarge {
                length: payload.len(),
                maximum: u16::MAX as usize,
            });
        }

        let mut frame = Vec::with_capacity(NON_HDLC_HEADER_LEN + payload.len() + 1);
        frame.push(CONTROL_CHAR);
        frame.push(NON_HDLC_VERSION);
        frame.extend_from_slice(&(payload.len() as u16).to_le_bytes());
        frame.extend_from_slice(payload);
        frame.push(CONTROL_CHAR);
        Ok(frame)
    }

    /// Decode and strictly validate one Qualcomm non-HDLC version-1 frame.
    pub fn decode_non_hdlc(frame: &[u8]) -> Result<Vec<u8>, DiagFrameError> {
        const MIN_FRAME_LEN: usize = NON_HDLC_HEADER_LEN + 1;
        if frame.len() < MIN_FRAME_LEN {
            return Err(DiagFrameError::TooShort {
                actual: frame.len(),
                minimum: MIN_FRAME_LEN,
            });
        }
        if frame[0] != CONTROL_CHAR {
            return Err(DiagFrameError::InvalidNonHdlcStart { received: frame[0] });
        }
        if frame[1] != NON_HDLC_VERSION {
            return Err(DiagFrameError::InvalidNonHdlcVersion {
                received: frame[1],
                expected: NON_HDLC_VERSION,
            });
        }
        if frame.last() != Some(&CONTROL_CHAR) {
            return Err(DiagFrameError::MissingTerminator);
        }

        let declared = u16::from_le_bytes([frame[2], frame[3]]) as usize;
        let actual = frame.len() - NON_HDLC_HEADER_LEN - 1;
        if declared != actual {
            return Err(DiagFrameError::NonHdlcLengthMismatch { declared, actual });
        }
        Ok(frame[NON_HDLC_HEADER_LEN..frame.len() - 1].to_vec())
    }

    pub fn ftm_bootstrap(start: bool) -> [u8; 6] {
        if start {
            FTM_BOOTSTRAP_START
        } else {
            FTM_BOOTSTRAP_STOP
        }
    }

    /// Wrap one complete H4 packet for `bt_ftm_diag_dispatch`:
    ///
    /// `4b 0b 04 00 || 00 00 || h4_len:u16_le || 00 00 || h4`.
    ///
    /// The exact CPH2653 `ftmdaemon` reads only the subsystem header, the
    /// length at offset 6 and the H4 bytes at offset 10. Reserved fields are
    /// deliberately zeroed.
    pub fn build_ftm_h4_request(h4: &[u8]) -> Result<Vec<u8>, DiagFrameError> {
        if h4.is_empty() {
            return Err(DiagFrameError::EmptyH4Packet);
        }
        if h4.len() > u16::MAX as usize {
            return Err(DiagFrameError::PayloadTooLarge {
                length: h4.len(),
                maximum: u16::MAX as usize,
            });
        }

        let mut request = Vec::with_capacity(FTM_BT_HCI_HEADER_LEN + h4.len());
        request.push(DIAG_SUBSYS_CMD);
        request.push(FTM_DIAG_SUBSYS_ID);
        request.extend_from_slice(&FTM_BT_HCI_COMMAND.to_le_bytes());
        request.extend_from_slice(&[0x00, 0x00]);
        request.extend_from_slice(&(h4.len() as u16).to_le_bytes());
        request.extend_from_slice(&[0x00, 0x00]);
        request.extend_from_slice(h4);
        Ok(request)
    }

    /// Build DIAG `0x73`, operation 3, for log codes from one equipment ID.
    ///
    /// Qualcomm indexes each requested log code directly (`item / 8`,
    /// `item % 8`). `num_items` controls the transmitted byte count through
    /// `ceil(num_items / 8)`, so the smallest safe value is normally one past
    /// the highest enabled item. The protocol maximum `0x0fff` is already
    /// sufficient for item `0x0fff` because it produces a 512-byte mask.
    pub fn build_log_mask_set(log_codes: &[u16]) -> Result<Vec<u8>, DiagFrameError> {
        let first = *log_codes.first().ok_or(DiagFrameError::EmptyLogCodeSet)?;
        let equip_id = ((first >> 12) & 0x0f) as u8;
        let mut highest_item = first & MAX_LOG_ITEM;

        for &log_code in &log_codes[1..] {
            let received = ((log_code >> 12) & 0x0f) as u8;
            if received != equip_id {
                return Err(DiagFrameError::MixedLogEquipIds {
                    expected: equip_id,
                    received,
                    log_code,
                });
            }
            highest_item = highest_item.max(log_code & MAX_LOG_ITEM);
        }

        let num_items = if highest_item == MAX_LOG_ITEM {
            MAX_LOG_ITEM
        } else {
            highest_item + 1
        };
        let mask_len = (num_items as usize).div_ceil(8);
        let mut request = Vec::with_capacity(LOG_CONFIG_HEADER_LEN + mask_len);
        request.extend_from_slice(&LOG_CONFIG_COMMAND.to_le_bytes());
        request.extend_from_slice(&LOG_CONFIG_SET_MASK_OPERATION.to_le_bytes());
        request.extend_from_slice(&(equip_id as u32).to_le_bytes());
        request.extend_from_slice(&(num_items as u32).to_le_bytes());
        request.resize(LOG_CONFIG_HEADER_LEN + mask_len, 0);

        for &log_code in log_codes {
            let item = (log_code & MAX_LOG_ITEM) as usize;
            request[LOG_CONFIG_HEADER_LEN + item / 8] |= 1 << (item % 8);
        }
        Ok(request)
    }

    pub fn build_bluetooth_hci_log_mask() -> Vec<u8> {
        build_log_mask_set(&[LOG_BT_HCI_EVENT, LOG_BT_HCI_ACL])
            .expect("the two constant Bluetooth log codes share one equipment ID")
    }
}

/// Strict raw-HCI building/parsing for the staged OnePlus Qualcomm experiment.
///
/// The byte vectors here reuse the scan and connection parameters already
/// proven against the user's physical Apple Watch through the CM748.  This
/// module itself performs no USB, DIAG, Bluetooth, or radio I/O.
pub mod oneplus_hci {
    use crate::framing::{bt_cl, qualcomm_diag};
    use std::fmt;

    pub const H4_COMMAND: u8 = 0x01;
    pub const H4_ACL: u8 = 0x02;

    pub const OPCODE_DISCONNECT: u16 = 0x0406;
    pub const OPCODE_RESET: u16 = 0x0c03;
    pub const OPCODE_SET_EVENT_MASK: u16 = 0x0c01;
    pub const OPCODE_READ_LOCAL_VERSION: u16 = 0x1001;
    pub const OPCODE_LE_SET_EVENT_MASK: u16 = 0x2001;
    pub const OPCODE_LE_SET_SCAN_PARAMETERS: u16 = 0x200b;
    pub const OPCODE_LE_SET_SCAN_ENABLE: u16 = 0x200c;
    pub const OPCODE_LE_CREATE_CONNECTION: u16 = 0x200d;
    pub const OPCODE_LE_CREATE_CONNECTION_CANCEL: u16 = 0x200e;

    pub const EVENT_COMMAND_COMPLETE: u8 = 0x0e;
    pub const EVENT_COMMAND_STATUS: u8 = 0x0f;
    pub const EVENT_LE_META: u8 = 0x3e;
    pub const LE_SUBEVENT_CONNECTION_COMPLETE: u8 = 0x01;
    pub const LE_SUBEVENT_ADVERTISING_REPORT: u8 = 0x02;

    pub const WATCH_BT_CL_FEATURES: u32 = 0x0000_1521;
    pub const FIRST_AUTOMATICALLY_FLUSHABLE: u16 = 0x2000;
    pub const MAX_CONNECTION_HANDLE: u16 = 0x0eff;

    const DIAG_LOG_COMMAND: u8 = 0x10;
    const DIAG_LOG_HEADER_LEN: usize = 12;
    const DIAG_LOG_ENVELOPE_LEN: usize = 4;
    const HCI_ACL_HEADER_LEN: usize = 4;
    const L2CAP_HEADER_LEN: usize = 4;
    const WATCH_SETUP_SERVICE_UUID: u16 = 0xfe25;
    const WATCH_SETUP_SUBTYPE: u8 = 0x06;

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub enum CodecError {
        ParametersTooLong {
            length: usize,
            maximum: usize,
        },
        PayloadTooLarge {
            length: usize,
            maximum: usize,
        },
        InvalidConnectionHandle {
            handle: u16,
        },
        InvalidAddressType {
            address_type: u8,
        },
        TooShort {
            context: &'static str,
            actual: usize,
            minimum: usize,
        },
        LengthMismatch {
            context: &'static str,
            declared: usize,
            actual: usize,
        },
        UnexpectedValue {
            context: &'static str,
        },
        HciStatus {
            context: &'static str,
            status: u8,
        },
        UnsupportedLogCode {
            code: u16,
        },
        Malformed(&'static str),
    }

    impl fmt::Display for CodecError {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            match self {
                Self::ParametersTooLong { length, maximum } => {
                    write!(f, "HCI parameters length {length} exceeds {maximum}")
                }
                Self::PayloadTooLarge { length, maximum } => {
                    write!(f, "HCI payload length {length} exceeds {maximum}")
                }
                Self::InvalidConnectionHandle { handle } => {
                    write!(f, "invalid HCI connection handle 0x{handle:04x}")
                }
                Self::InvalidAddressType { address_type } => {
                    write!(f, "unsupported LE address type 0x{address_type:02x}")
                }
                Self::TooShort {
                    context,
                    actual,
                    minimum,
                } => {
                    write!(f, "{context} is {actual} bytes; need at least {minimum}")
                }
                Self::LengthMismatch {
                    context,
                    declared,
                    actual,
                } => {
                    write!(
                        f,
                        "{context} length mismatch: declared {declared}, actual {actual}"
                    )
                }
                Self::UnexpectedValue { context } => write!(f, "unexpected {context}"),
                Self::HciStatus { context, status } => {
                    write!(f, "{context} returned HCI status 0x{status:02x}")
                }
                Self::UnsupportedLogCode { code } => {
                    write!(f, "unsupported Bluetooth DIAG log code 0x{code:04x}")
                }
                Self::Malformed(reason) => write!(f, "malformed HCI data: {reason}"),
            }
        }
    }

    impl std::error::Error for CodecError {}

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub enum BluetoothLog {
        Event(Vec<u8>),
        Acl(Vec<u8>),
    }

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct CommandComplete {
        pub num_hci_command_packets: u8,
        pub opcode: u16,
        pub status: u8,
        /// Includes the status byte as its first byte.
        pub return_parameters: Vec<u8>,
    }

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub struct CommandStatus {
        pub status: u8,
        pub num_hci_command_packets: u8,
        pub opcode: u16,
    }

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub struct LeConnectionComplete {
        pub connection_handle: u16,
        pub role: u8,
        pub peer_address_type: u8,
        /// Address bytes in HCI/on-wire little-endian order.
        pub peer_address: [u8; 6],
        pub connection_interval: u16,
        pub peripheral_latency: u16,
        pub supervision_timeout: u16,
        pub central_clock_accuracy: u8,
    }

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct AdvertisingReport {
        pub event_type: u8,
        pub address_type: u8,
        /// Address bytes in HCI/on-wire little-endian order.
        pub address: [u8; 6],
        pub data: Vec<u8>,
        pub rssi: i8,
    }

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct WatchSetupPayload {
        pub watch_setup_data: Vec<u8>,
        pub header_version: u8,
        pub candidate_identifier: [u8; 4],
        pub metadata: Vec<u8>,
    }

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct AclL2capPacket {
        pub connection_handle: u16,
        pub packet_boundary_flag: u8,
        pub destination_cid: u16,
        pub payload: Vec<u8>,
    }

    fn read_u16_le(bytes: &[u8], offset: usize) -> u16 {
        u16::from_le_bytes([bytes[offset], bytes[offset + 1]])
    }

    fn push_u16_le(output: &mut Vec<u8>, value: u16) {
        output.extend_from_slice(&value.to_le_bytes());
    }

    fn validate_event_envelope(event: &[u8]) -> Result<usize, CodecError> {
        if event.len() < 2 {
            return Err(CodecError::TooShort {
                context: "HCI event",
                actual: event.len(),
                minimum: 2,
            });
        }
        let declared = event[1] as usize;
        let actual = event.len() - 2;
        if declared != actual {
            return Err(CodecError::LengthMismatch {
                context: "HCI event",
                declared,
                actual,
            });
        }
        Ok(declared)
    }

    /// Build a complete H4 command: `01 || opcode_le || len8 || parameters`.
    pub fn build_h4_command(opcode: u16, parameters: &[u8]) -> Result<Vec<u8>, CodecError> {
        if parameters.len() > u8::MAX as usize {
            return Err(CodecError::ParametersTooLong {
                length: parameters.len(),
                maximum: u8::MAX as usize,
            });
        }
        let mut h4 = Vec::with_capacity(4 + parameters.len());
        h4.push(H4_COMMAND);
        push_u16_le(&mut h4, opcode);
        h4.push(parameters.len() as u8);
        h4.extend_from_slice(parameters);
        Ok(h4)
    }

    /// Exact setup sequence used before scanning for this Watch.
    pub fn watch_scan_setup_h4_commands(connect: bool) -> Result<Vec<Vec<u8>>, CodecError> {
        let classic_mask = if connect {
            [0x10, 0, 0, 0, 0, 0, 0, 0x20]
        } else {
            [0, 0, 0, 0, 0, 0, 0, 0x20]
        };
        let le_mask = if connect {
            [0x03, 0, 0, 0, 0, 0, 0, 0]
        } else {
            [0x02, 0, 0, 0, 0, 0, 0, 0]
        };
        let scan_parameters = [0x00, 0x60, 0x00, 0x30, 0x00, 0x00, 0x00];

        Ok(vec![
            build_h4_command(OPCODE_RESET, &[])?,
            build_h4_command(OPCODE_SET_EVENT_MASK, &classic_mask)?,
            build_h4_command(OPCODE_LE_SET_EVENT_MASK, &le_mask)?,
            build_h4_command(OPCODE_LE_SET_SCAN_PARAMETERS, &scan_parameters)?,
            build_le_scan_enable_h4(true, false)?,
        ])
    }

    pub fn build_le_scan_enable_h4(
        enabled: bool,
        filter_duplicates: bool,
    ) -> Result<Vec<u8>, CodecError> {
        build_h4_command(
            OPCODE_LE_SET_SCAN_ENABLE,
            &[u8::from(enabled), u8::from(filter_duplicates)],
        )
    }

    /// Legacy LE Create Connection parameters proven in the CM748 physical run.
    pub fn build_le_create_connection_h4(
        peer_address_type: u8,
        peer_address: [u8; 6],
    ) -> Result<Vec<u8>, CodecError> {
        if peer_address_type > 1 {
            return Err(CodecError::InvalidAddressType {
                address_type: peer_address_type,
            });
        }

        let mut parameters = Vec::with_capacity(25);
        push_u16_le(&mut parameters, 0x0060); // 60 ms scan interval
        push_u16_le(&mut parameters, 0x0060); // continuous initiating scan
        parameters.push(0x00); // no Filter Accept List
        parameters.push(peer_address_type);
        parameters.extend_from_slice(&peer_address);
        parameters.push(0x00); // public own address
        push_u16_le(&mut parameters, 0x0018); // 30 ms minimum interval
        push_u16_le(&mut parameters, 0x0028); // 50 ms maximum interval
        push_u16_le(&mut parameters, 0x0000); // no peripheral latency
        push_u16_le(&mut parameters, 0x01f4); // 5 s supervision timeout
        push_u16_le(&mut parameters, 0x0000); // no CE recommendation
        push_u16_le(&mut parameters, 0x0000);
        build_h4_command(OPCODE_LE_CREATE_CONNECTION, &parameters)
    }

    pub fn build_disconnect_h4(connection_handle: u16) -> Result<Vec<u8>, CodecError> {
        if connection_handle > MAX_CONNECTION_HANDLE {
            return Err(CodecError::InvalidConnectionHandle {
                handle: connection_handle,
            });
        }
        let [low, high] = connection_handle.to_le_bytes();
        build_h4_command(OPCODE_DISCONNECT, &[low, high, 0x13])
    }

    /// Wrap one complete L2CAP PDU in one unfragmented H4 ACL packet.
    pub fn build_l2cap_h4_acl(
        connection_handle: u16,
        destination_cid: u16,
        payload: &[u8],
    ) -> Result<Vec<u8>, CodecError> {
        if connection_handle > MAX_CONNECTION_HANDLE {
            return Err(CodecError::InvalidConnectionHandle {
                handle: connection_handle,
            });
        }
        if payload.len() > u16::MAX as usize {
            return Err(CodecError::PayloadTooLarge {
                length: payload.len(),
                maximum: u16::MAX as usize,
            });
        }

        let l2cap = bt_cl::encode_l2cap_pdu(destination_cid, payload).map_err(|_| {
            CodecError::PayloadTooLarge {
                length: payload.len(),
                maximum: u16::MAX as usize,
            }
        })?;
        if l2cap.len() > u16::MAX as usize {
            return Err(CodecError::PayloadTooLarge {
                length: l2cap.len(),
                maximum: u16::MAX as usize,
            });
        }

        let handle_with_flags = connection_handle | FIRST_AUTOMATICALLY_FLUSHABLE;
        let mut h4 = Vec::with_capacity(1 + HCI_ACL_HEADER_LEN + l2cap.len());
        h4.push(H4_ACL);
        push_u16_le(&mut h4, handle_with_flags);
        push_u16_le(&mut h4, l2cap.len() as u16);
        h4.extend_from_slice(&l2cap);
        Ok(h4)
    }

    /// First Apple BT_CL packet after LE link establishment.
    pub fn build_bt_cl_version_h4_acl(connection_handle: u16) -> Result<Vec<u8>, CodecError> {
        let version_payload = bt_cl::version_payload(bt_cl::CURRENT_VERSION, WATCH_BT_CL_FEATURES);
        let cl = bt_cl::encode_pdu(
            bt_cl::CURRENT_VERSION,
            bt_cl::VERSION_INFO,
            &version_payload,
        )
        .map_err(|_| CodecError::Malformed("constant BT_CL VERSION vector"))?;
        build_l2cap_h4_acl(connection_handle, bt_cl::SIGNALING_CID, &cl)
    }

    pub fn build_bt_cl_version_diag_request(connection_handle: u16) -> Result<Vec<u8>, CodecError> {
        let h4 = build_bt_cl_version_h4_acl(connection_handle)?;
        qualcomm_diag::build_ftm_h4_request(&h4)
            .map_err(|_| CodecError::Malformed("constant H4 packet did not fit DIAG"))
    }

    /// Parse the exact `DIAG_LOG_F` envelope emitted by `libdiag`.
    pub fn parse_bluetooth_diag_log(payload: &[u8]) -> Result<BluetoothLog, CodecError> {
        const MIN_LEN: usize = DIAG_LOG_ENVELOPE_LEN + DIAG_LOG_HEADER_LEN;
        if payload.len() < MIN_LEN {
            return Err(CodecError::TooShort {
                context: "DIAG_LOG_F",
                actual: payload.len(),
                minimum: MIN_LEN,
            });
        }
        if payload[0] != DIAG_LOG_COMMAND || payload[1] != 0 {
            return Err(CodecError::UnexpectedValue {
                context: "DIAG_LOG_F command/more fields",
            });
        }

        let included = read_u16_le(payload, 2) as usize;
        let item_length = read_u16_le(payload, 4) as usize;
        let actual_item_length = payload.len() - DIAG_LOG_ENVELOPE_LEN;
        if included != actual_item_length {
            return Err(CodecError::LengthMismatch {
                context: "DIAG included log item",
                declared: included,
                actual: actual_item_length,
            });
        }
        if item_length != actual_item_length {
            return Err(CodecError::LengthMismatch {
                context: "DIAG inner log item",
                declared: item_length,
                actual: actual_item_length,
            });
        }
        if item_length < DIAG_LOG_HEADER_LEN {
            return Err(CodecError::TooShort {
                context: "DIAG log item",
                actual: item_length,
                minimum: DIAG_LOG_HEADER_LEN,
            });
        }

        let code = read_u16_le(payload, 6);
        let data = payload[DIAG_LOG_ENVELOPE_LEN + DIAG_LOG_HEADER_LEN..].to_vec();
        match code {
            qualcomm_diag::LOG_BT_HCI_EVENT => Ok(BluetoothLog::Event(data)),
            qualcomm_diag::LOG_BT_HCI_ACL => Ok(BluetoothLog::Acl(data)),
            _ => Err(CodecError::UnsupportedLogCode { code }),
        }
    }

    pub fn parse_command_complete(event: &[u8]) -> Result<CommandComplete, CodecError> {
        let parameter_length = validate_event_envelope(event)?;
        if event[0] != EVENT_COMMAND_COMPLETE {
            return Err(CodecError::UnexpectedValue {
                context: "HCI Command Complete event code",
            });
        }
        if parameter_length < 4 {
            return Err(CodecError::TooShort {
                context: "HCI Command Complete parameters",
                actual: parameter_length,
                minimum: 4,
            });
        }
        Ok(CommandComplete {
            num_hci_command_packets: event[2],
            opcode: read_u16_le(event, 3),
            status: event[5],
            return_parameters: event[5..].to_vec(),
        })
    }

    pub fn parse_command_status(event: &[u8]) -> Result<CommandStatus, CodecError> {
        let parameter_length = validate_event_envelope(event)?;
        if event[0] != EVENT_COMMAND_STATUS {
            return Err(CodecError::UnexpectedValue {
                context: "HCI Command Status event code",
            });
        }
        if parameter_length != 4 {
            return Err(CodecError::LengthMismatch {
                context: "HCI Command Status parameters",
                declared: 4,
                actual: parameter_length,
            });
        }
        Ok(CommandStatus {
            status: event[2],
            num_hci_command_packets: event[3],
            opcode: read_u16_le(event, 4),
        })
    }

    pub fn parse_le_connection_complete(event: &[u8]) -> Result<LeConnectionComplete, CodecError> {
        let parameter_length = validate_event_envelope(event)?;
        if event[0] != EVENT_LE_META || event.get(2) != Some(&LE_SUBEVENT_CONNECTION_COMPLETE) {
            return Err(CodecError::UnexpectedValue {
                context: "LE Connection Complete event/subevent",
            });
        }
        if parameter_length != 19 {
            return Err(CodecError::LengthMismatch {
                context: "LE Connection Complete parameters",
                declared: 19,
                actual: parameter_length,
            });
        }
        if event[3] != 0 {
            return Err(CodecError::HciStatus {
                context: "LE Connection Complete",
                status: event[3],
            });
        }

        let connection_handle = read_u16_le(event, 4);
        if connection_handle > MAX_CONNECTION_HANDLE {
            return Err(CodecError::InvalidConnectionHandle {
                handle: connection_handle,
            });
        }
        let peer_address: [u8; 6] = event[8..14]
            .try_into()
            .expect("the exact event length was validated");
        Ok(LeConnectionComplete {
            connection_handle,
            role: event[6],
            peer_address_type: event[7],
            peer_address,
            connection_interval: read_u16_le(event, 14),
            peripheral_latency: read_u16_le(event, 16),
            supervision_timeout: read_u16_le(event, 18),
            central_clock_accuracy: event[20],
        })
    }

    pub fn parse_legacy_advertising_reports(
        event: &[u8],
    ) -> Result<Vec<AdvertisingReport>, CodecError> {
        validate_event_envelope(event)?;
        if event.len() < 4
            || event[0] != EVENT_LE_META
            || event[2] != LE_SUBEVENT_ADVERTISING_REPORT
        {
            return Err(CodecError::UnexpectedValue {
                context: "legacy LE Advertising Report event/subevent",
            });
        }

        let report_count = event[3] as usize;
        let mut offset = 4usize;
        let mut reports = Vec::with_capacity(report_count);
        for _ in 0..report_count {
            const FIXED_BEFORE_DATA: usize = 9;
            if event.len().saturating_sub(offset) < FIXED_BEFORE_DATA {
                return Err(CodecError::TooShort {
                    context: "legacy advertising report",
                    actual: event.len().saturating_sub(offset),
                    minimum: FIXED_BEFORE_DATA,
                });
            }
            let event_type = event[offset];
            let address_type = event[offset + 1];
            let address: [u8; 6] = event[offset + 2..offset + 8]
                .try_into()
                .expect("the fixed advertising prefix was validated");
            let data_length = event[offset + 8] as usize;
            offset += FIXED_BEFORE_DATA;
            if event.len().saturating_sub(offset) < data_length + 1 {
                return Err(CodecError::TooShort {
                    context: "legacy advertising data and RSSI",
                    actual: event.len().saturating_sub(offset),
                    minimum: data_length + 1,
                });
            }
            let data = event[offset..offset + data_length].to_vec();
            offset += data_length;
            let rssi = event[offset] as i8;
            offset += 1;
            reports.push(AdvertisingReport {
                event_type,
                address_type,
                address,
                data,
                rssi,
            });
        }

        if offset != event.len() {
            return Err(CodecError::LengthMismatch {
                context: "legacy advertising report list",
                declared: offset,
                actual: event.len(),
            });
        }
        Ok(reports)
    }

    pub fn parse_watch_setup_payload(
        advertising_data: &[u8],
    ) -> Result<Option<WatchSetupPayload>, CodecError> {
        let mut offset = 0usize;
        while offset < advertising_data.len() {
            let field_length = advertising_data[offset] as usize;
            if field_length == 0 {
                if advertising_data[offset..].iter().all(|&byte| byte == 0) {
                    return Ok(None);
                }
                return Err(CodecError::Malformed("non-zero bytes after AD terminator"));
            }
            let field_end = offset + 1 + field_length;
            if field_length < 1 || field_end > advertising_data.len() {
                return Err(CodecError::LengthMismatch {
                    context: "advertising AD structure",
                    declared: field_end,
                    actual: advertising_data.len(),
                });
            }

            let ad_type = advertising_data[offset + 1];
            let value_start = offset + 2;
            if ad_type == 0x16 && field_end.saturating_sub(value_start) >= 3 {
                let service_uuid = read_u16_le(advertising_data, value_start);
                let mut cursor = value_start + 2;
                let encoded_subtype = advertising_data[cursor];
                cursor += 1;
                let subtype = encoded_subtype & 0x7f;
                let mut payload_end = field_end;

                if encoded_subtype & 0x80 != 0 {
                    if cursor >= field_end {
                        return Err(CodecError::TooShort {
                            context: "explicit Watch Setup length",
                            actual: 0,
                            minimum: 1,
                        });
                    }
                    let explicit_length = (advertising_data[cursor] & 0x1f) as usize;
                    cursor += 1;
                    payload_end = cursor + explicit_length;
                    if payload_end > field_end {
                        return Err(CodecError::LengthMismatch {
                            context: "explicit Watch Setup payload",
                            declared: payload_end,
                            actual: field_end,
                        });
                    }
                }

                if service_uuid == WATCH_SETUP_SERVICE_UUID && subtype == WATCH_SETUP_SUBTYPE {
                    let watch_setup_data = advertising_data[cursor..payload_end].to_vec();
                    if watch_setup_data.len() < 5 {
                        return Err(CodecError::TooShort {
                            context: "Watch Setup data",
                            actual: watch_setup_data.len(),
                            minimum: 5,
                        });
                    }
                    let header_version = (watch_setup_data[0] >> 5) & 0x07;
                    if header_version != 1 {
                        return Err(CodecError::UnexpectedValue {
                            context: "Watch Setup header version",
                        });
                    }
                    let candidate_identifier: [u8; 4] = watch_setup_data[1..5]
                        .try_into()
                        .expect("Watch Setup minimum length was validated");
                    let metadata = watch_setup_data[5..].to_vec();
                    return Ok(Some(WatchSetupPayload {
                        watch_setup_data,
                        header_version,
                        candidate_identifier,
                        metadata,
                    }));
                }
            }
            offset = field_end;
        }
        Ok(None)
    }

    pub fn is_expected_watch_setup_target(
        report: &AdvertisingReport,
        expected_identifier: [u8; 4],
        minimum_rssi: i8,
    ) -> Result<bool, CodecError> {
        let Some(watch_setup) = parse_watch_setup_payload(&report.data)? else {
            return Ok(false);
        };
        Ok(watch_setup.candidate_identifier == expected_identifier
            && report.event_type == 0x00
            && report.address_type <= 0x01
            && report.rssi != 127
            && report.rssi >= minimum_rssi)
    }

    /// Parse one complete, unfragmented HCI ACL packet without its H4 byte.
    pub fn parse_complete_l2cap_acl(acl: &[u8]) -> Result<AclL2capPacket, CodecError> {
        const MIN_LEN: usize = HCI_ACL_HEADER_LEN + L2CAP_HEADER_LEN;
        if acl.len() < MIN_LEN {
            return Err(CodecError::TooShort {
                context: "HCI ACL/L2CAP packet",
                actual: acl.len(),
                minimum: MIN_LEN,
            });
        }
        let handle_with_flags = read_u16_le(acl, 0);
        let connection_handle = handle_with_flags & 0x0fff;
        if connection_handle > MAX_CONNECTION_HANDLE {
            return Err(CodecError::InvalidConnectionHandle {
                handle: connection_handle,
            });
        }
        let packet_boundary_flag = ((handle_with_flags >> 12) & 0x03) as u8;
        let broadcast_flag = ((handle_with_flags >> 14) & 0x03) as u8;
        if !matches!(packet_boundary_flag, 0 | 2) || broadcast_flag != 0 {
            return Err(CodecError::UnexpectedValue {
                context: "first ACL packet boundary/broadcast flags",
            });
        }

        let declared_acl_length = read_u16_le(acl, 2) as usize;
        let actual_acl_length = acl.len() - HCI_ACL_HEADER_LEN;
        if declared_acl_length != actual_acl_length {
            return Err(CodecError::LengthMismatch {
                context: "HCI ACL payload",
                declared: declared_acl_length,
                actual: actual_acl_length,
            });
        }
        let declared_l2cap_length = read_u16_le(acl, 4) as usize;
        let actual_l2cap_length = acl.len() - HCI_ACL_HEADER_LEN - L2CAP_HEADER_LEN;
        if declared_l2cap_length != actual_l2cap_length {
            return Err(CodecError::LengthMismatch {
                context: "complete L2CAP payload",
                declared: declared_l2cap_length,
                actual: actual_l2cap_length,
            });
        }

        Ok(AclL2capPacket {
            connection_handle,
            packet_boundary_flag,
            destination_cid: read_u16_le(acl, 6),
            payload: acl[8..].to_vec(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_empty_payload_vector() {
        assert_eq!(
            encode_uike_frame(&[]).unwrap(),
            [0x04, 0x00, 0x00, 0xfb, 0xff]
        );
    }

    #[test]
    fn exact_even_payload_vector_with_odd_checksum_offset() {
        // Prefix: 04 00 02 de ad. The trailing ad is padded with 00 for the
        // checksum calculation, yielding 0x4c21.
        assert_eq!(
            encode_uike_frame(&[0xde, 0xad]).unwrap(),
            [0x04, 0x00, 0x02, 0xde, 0xad, 0x4c, 0x21]
        );
    }

    #[test]
    fn exact_odd_payload_vector() {
        assert_eq!(
            encode_uike_frame(&[0x01, 0x02, 0x03]).unwrap(),
            [0x04, 0x00, 0x03, 0x01, 0x02, 0x03, 0xf6, 0xfb]
        );
    }

    #[test]
    fn fragmented_input_and_multiple_frames() {
        let first = encode_uike_frame(&[1, 2, 3, 4]).unwrap();
        let second = encode_uike_frame(&[5, 6]).unwrap();
        let mut stream = first;
        stream.extend_from_slice(&second);

        let mut decoder = UikeStreamDecoder::new();
        assert!(decoder.push(&stream[..2]).unwrap().is_empty());
        assert_eq!(decoder.buffered_len(), 2);
        assert!(decoder.push(&stream[2..6]).unwrap().is_empty());

        let payloads = decoder.push(&stream[6..]).unwrap();
        assert_eq!(payloads, [vec![1, 2, 3, 4], vec![5, 6]]);
        assert_eq!(decoder.buffered_len(), 0);
    }

    #[test]
    fn rejects_bad_checksum_and_keeps_evidence() {
        let mut frame = encode_uike_frame(&[0xaa, 0xbb]).unwrap();
        let last = frame.len() - 1;
        frame[last] ^= 0x01;

        let mut decoder = UikeStreamDecoder::new();
        assert!(matches!(
            decoder.push(&frame),
            Err(FrameError::ChecksumMismatch { .. })
        ));
        assert_eq!(decoder.buffered_bytes(), frame);
    }

    #[test]
    fn rejects_wrong_tlv_type() {
        let mut frame = encode_uike_frame(&[0x11]).unwrap();
        frame[0] = 0x05;
        let checksum_offset = frame.len() - CHECKSUM_LEN;
        let checksum = internet_checksum(&frame[..checksum_offset]);
        frame[checksum_offset..].copy_from_slice(&checksum.to_be_bytes());

        let mut decoder = UikeStreamDecoder::new();
        assert_eq!(
            decoder.push(&frame),
            Err(FrameError::UnexpectedType {
                received: 0x05,
                expected: 0x04,
            })
        );
    }

    #[test]
    fn rejects_payload_larger_than_uint16() {
        let payload = vec![0u8; MAX_PAYLOAD_LEN + 1];
        assert_eq!(
            encode_uike_frame(&payload),
            Err(FrameError::PayloadTooLarge {
                length: MAX_PAYLOAD_LEN + 1,
                maximum: MAX_PAYLOAD_LEN,
            })
        );
    }

    #[test]
    fn exact_bt_cl_version_vector() {
        use bt_cl::*;

        let cl_pdu = encode_pdu(
            CURRENT_VERSION,
            VERSION_INFO,
            &version_payload(CURRENT_VERSION, 0x0000_1521),
        )
        .unwrap();
        assert_eq!(cl_pdu, [0x09, 0x05, 0x0b, 0x21, 0x15, 0x00, 0x00]);

        let l2cap_pdu = encode_l2cap_pdu(SIGNALING_CID, &cl_pdu).unwrap();
        assert_eq!(
            l2cap_pdu,
            [
                0x07, 0x00, 0x3a, 0x00, 0x09, 0x05, 0x0b, 0x21, 0x15, 0x00, 0x00
            ]
        );
        assert_eq!(
            decode_l2cap_pdu(&l2cap_pdu).unwrap(),
            L2capPdu {
                destination_cid: SIGNALING_CID,
                payload: cl_pdu,
            }
        );
    }

    #[test]
    fn exact_bt_cl_terminus_service_vector_and_round_trip() {
        use bt_cl::*;

        let record = ServiceRecord {
            service_id: 1,
            service_type: 1,
            name: TERMINUS_PAIRING_NAME.to_vec(),
            flags: 1,
        };
        let payload = remote_services_payload(std::slice::from_ref(&record)).unwrap();
        let pdu = encode_pdu(CURRENT_VERSION, REMOTE_SERVICES, &payload).unwrap();

        let mut expected = vec![0x01, 0x21, 0x00, 0x01, 0x1f, 0x01, 0x00, 0x01, 0x1a];
        expected.extend_from_slice(TERMINUS_PAIRING_NAME);
        expected.push(0x01);
        assert_eq!(pdu, expected);

        let decoded = decode_pdu(CURRENT_VERSION, &pdu).unwrap();
        assert_eq!(decoded.opcode, REMOTE_SERVICES);
        assert_eq!(parse_remote_services(&decoded.payload).unwrap(), [record]);
    }

    #[test]
    fn exact_bt_cl_create_and_accept_vectors() {
        use bt_cl::*;

        let create = CreateChannel {
            initiator_local_cid: 0x0040,
            common_service_id: 0x0001,
        };
        assert_eq!(
            encode_pdu(
                CURRENT_VERSION,
                CREATE_CHANNEL,
                &create_channel_payload(create),
            )
            .unwrap(),
            [0x03, 0x04, 0x00, 0x40, 0x00, 0x01, 0x00]
        );
        assert_eq!(
            parse_create_channel(&[0x40, 0x00, 0x01, 0x00]).unwrap(),
            create
        );

        let accept = AcceptChannel {
            status: 0,
            service_id: 0x0001,
            responder_local_cid: 0x0041,
        };
        assert_eq!(
            encode_pdu(
                CURRENT_VERSION,
                ACCEPT_CHANNEL,
                &accept_channel_payload(accept),
            )
            .unwrap(),
            [0x04, 0x05, 0x00, 0x00, 0x01, 0x00, 0x41, 0x00]
        );
        assert_eq!(
            parse_accept_channel(&[0x00, 0x01, 0x00, 0x41, 0x00]).unwrap(),
            accept
        );
    }

    #[test]
    fn bt_cl_header_changes_at_version_8_but_version_info_stays_short() {
        use bt_cl::*;

        assert_eq!(
            encode_pdu(7, CREATE_CHANNEL, &[1, 2, 3, 4]).unwrap(),
            [0x03, 0x04, 1, 2, 3, 4]
        );
        assert_eq!(
            encode_pdu(8, CREATE_CHANNEL, &[1, 2, 3, 4]).unwrap(),
            [0x03, 0x04, 0x00, 1, 2, 3, 4]
        );
        assert_eq!(
            encode_pdu(8, VERSION_INFO, &[1, 2, 3, 4, 5]).unwrap(),
            [0x09, 0x05, 1, 2, 3, 4, 5]
        );
    }

    #[test]
    fn bt_cl_decoder_rejects_trailing_bytes() {
        use bt_cl::*;

        assert_eq!(
            decode_pdu(
                CURRENT_VERSION,
                &[CREATE_CHANNEL, 0x04, 0x00, 1, 2, 3, 4, 5]
            ),
            Err(CodecError::LengthMismatch {
                declared: 4,
                actual: 5,
            })
        );
    }

    #[test]
    fn dynamic_cid_carries_the_uike_stream_directly() {
        use bt_cl::*;

        let uike = encode_uike_frame(&[]).unwrap();
        assert_eq!(
            encode_l2cap_pdu(0x0041, &uike).unwrap(),
            [0x05, 0x00, 0x41, 0x00, 0x04, 0x00, 0x00, 0xfb, 0xff]
        );
    }

    #[test]
    fn qualcomm_crc_matches_crc16_x25_check_vector() {
        use qualcomm_diag::*;

        assert_eq!(crc16_l(b"123456789"), 0x906e);
        assert_eq!(
            encode_hdlc(b"123456789"),
            [
                0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x6e, 0x90, 0x7e
            ]
        );
    }

    #[test]
    fn exact_qualcomm_ftm_bootstrap_frames() {
        use qualcomm_diag::*;

        assert_eq!(ftm_bootstrap(true), [0x4b, 0x0b, 0xfb, 0x04, 0x00, 0x01]);
        assert_eq!(
            encode_hdlc(&ftm_bootstrap(true)),
            [0x4b, 0x0b, 0xfb, 0x04, 0x00, 0x01, 0x41, 0x5a, 0x7e]
        );
        assert_eq!(
            encode_non_hdlc(&ftm_bootstrap(true)).unwrap(),
            [
                0x7e, 0x01, 0x06, 0x00, 0x4b, 0x0b, 0xfb, 0x04, 0x00, 0x01, 0x7e
            ]
        );
    }

    #[test]
    fn qualcomm_framing_round_trips_escaped_bytes() {
        use qualcomm_diag::*;

        let payload = [0x4b, 0x7d, 0x7e, 0x00];
        let hdlc = encode_hdlc(&payload);
        assert!(hdlc.windows(2).any(|pair| pair == [0x7d, 0x5d]));
        assert!(hdlc.windows(2).any(|pair| pair == [0x7d, 0x5e]));
        assert_eq!(decode_hdlc(&hdlc).unwrap(), payload);

        let non_hdlc = encode_non_hdlc(&payload).unwrap();
        assert_eq!(decode_non_hdlc(&non_hdlc).unwrap(), payload);
    }

    #[test]
    fn qualcomm_decoders_reject_corruption() {
        use qualcomm_diag::*;

        let mut hdlc = encode_hdlc(&FTM_BOOTSTRAP_START);
        hdlc[0] ^= 1;
        assert!(matches!(
            decode_hdlc(&hdlc),
            Err(DiagFrameError::ChecksumMismatch { .. })
        ));

        let mut non_hdlc = encode_non_hdlc(&FTM_BOOTSTRAP_START).unwrap();
        non_hdlc[2] = 5;
        assert_eq!(
            decode_non_hdlc(&non_hdlc),
            Err(DiagFrameError::NonHdlcLengthMismatch {
                declared: 5,
                actual: 6,
            })
        );
    }

    #[test]
    fn exact_qualcomm_ftm_hci_read_version_request() {
        use qualcomm_diag::*;

        assert_eq!(
            build_ftm_h4_request(&HCI_READ_LOCAL_VERSION_INFORMATION).unwrap(),
            [
                0x4b, 0x0b, 0x04, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x10, 0x00
            ]
        );
        assert_eq!(
            build_ftm_h4_request(&[]),
            Err(DiagFrameError::EmptyH4Packet)
        );
    }

    #[test]
    fn exact_qualcomm_bluetooth_log_mask() {
        use qualcomm_diag::*;

        let request = build_bluetooth_hci_log_mask();
        assert_eq!(request.len(), 126);
        assert_eq!(
            &request[..16],
            [
                0x73, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x69, 0x03,
                0x00, 0x00
            ]
        );

        let payload = &request[16..];
        assert_eq!(payload.len(), 110);
        assert!(payload[..108].iter().all(|&byte| byte == 0));
        assert_eq!(payload[108], 0x40); // item 0x366, bit 6
        assert_eq!(payload[109], 0x01); // item 0x368, bit 0
    }

    #[test]
    fn qualcomm_log_mask_rejects_mixed_equipment_ids() {
        use qualcomm_diag::*;

        assert_eq!(
            build_log_mask_set(&[LOG_BT_HCI_EVENT, 0x2368]),
            Err(DiagFrameError::MixedLogEquipIds {
                expected: 1,
                received: 2,
                log_code: 0x2368,
            })
        );
        assert_eq!(
            build_log_mask_set(&[]),
            Err(DiagFrameError::EmptyLogCodeSet)
        );
    }

    #[test]
    fn exact_oneplus_watch_scan_and_connect_h4_vectors() {
        use oneplus_hci as hci;

        let setup = hci::watch_scan_setup_h4_commands(true).unwrap();
        assert_eq!(
            setup,
            [
                vec![0x01, 0x03, 0x0c, 0x00],
                vec![
                    0x01, 0x01, 0x0c, 0x08, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20,
                ],
                vec![
                    0x01, 0x01, 0x20, 0x08, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                ],
                vec![
                    0x01, 0x0b, 0x20, 0x07, 0x00, 0x60, 0x00, 0x30, 0x00, 0x00, 0x00,
                ],
                vec![0x01, 0x0c, 0x20, 0x02, 0x01, 0x00],
            ]
        );
        assert_eq!(
            hci::build_le_scan_enable_h4(false, false).unwrap(),
            [0x01, 0x0c, 0x20, 0x02, 0x00, 0x00]
        );

        let address = [0x59, 0xd7, 0xbf, 0xee, 0xe7, 0x53];
        let create = hci::build_le_create_connection_h4(1, address).unwrap();
        assert_eq!(
            create,
            [
                0x01, 0x0d, 0x20, 0x19, 0x60, 0x00, 0x60, 0x00, 0x00, 0x01, 0x59, 0xd7, 0xbf, 0xee,
                0xe7, 0x53, 0x00, 0x18, 0x00, 0x28, 0x00, 0x00, 0x00, 0xf4, 0x01, 0x00, 0x00, 0x00,
                0x00,
            ]
        );
        let diag = qualcomm_diag::build_ftm_h4_request(&create).unwrap();
        assert_eq!(&diag[..10], [0x4b, 0x0b, 0x04, 0, 0, 0, 0x1d, 0, 0, 0]);
        assert_eq!(&diag[10..], create);
    }

    #[test]
    fn parses_physical_watch_setup_and_builds_fresh_address_handoff() {
        use oneplus_hci as hci;

        let advertising_data = vec![
            0x02, 0x01, 0x1a, 0x10, 0x16, 0x25, 0xfe, 0x06, 0x20, 0x8a, 0xf4, 0xb4, 0xce, 0x64,
            0x38, 0x50, 0x00, 0xd0, 0x10, 0x00, 0x02, 0x0a, 0x00,
        ];
        let address = [0x69, 0xcb, 0x25, 0x2d, 0x6a, 0x64];
        let mut event = vec![0x3e, 0, 0x02, 0x01, 0x00, 0x01];
        event.extend_from_slice(&address);
        event.push(advertising_data.len() as u8);
        event.extend_from_slice(&advertising_data);
        event.push((-31i8) as u8);
        event[1] = (event.len() - 2) as u8;

        let reports = hci::parse_legacy_advertising_reports(&event).unwrap();
        assert_eq!(reports.len(), 1);
        assert_eq!(reports[0].address, address);
        assert_eq!(reports[0].rssi, -31);

        let setup = hci::parse_watch_setup_payload(&reports[0].data)
            .unwrap()
            .unwrap();
        assert_eq!(setup.header_version, 1);
        assert_eq!(setup.candidate_identifier, [0x8a, 0xf4, 0xb4, 0xce]);
        assert_eq!(setup.metadata, [0x64, 0x38, 0x50, 0, 0xd0, 0x10, 0]);
        assert!(
            hci::is_expected_watch_setup_target(&reports[0], [0x8a, 0xf4, 0xb4, 0xce], -75)
                .unwrap()
        );

        let create =
            hci::build_le_create_connection_h4(reports[0].address_type, reports[0].address)
                .unwrap();
        assert_eq!(&create[10..16], address);
    }

    #[test]
    fn parses_staged_hci_command_and_connection_events() {
        use oneplus_hci as hci;

        let complete = hci::parse_command_complete(&[
            0x0e, 0x0c, 0x01, 0x01, 0x10, 0x00, 0x0d, 0x34, 0x12, 0x0d, 0x5d, 0x00, 0x78, 0x56,
        ])
        .unwrap();
        assert_eq!(complete.opcode, hci::OPCODE_READ_LOCAL_VERSION);
        assert_eq!(complete.status, 0);
        assert_eq!(complete.num_hci_command_packets, 1);

        let status = hci::parse_command_status(&[0x0f, 0x04, 0x00, 0x01, 0x0d, 0x20]).unwrap();
        assert_eq!(status.opcode, hci::OPCODE_LE_CREATE_CONNECTION);
        assert_eq!(status.status, 0);

        let connection = hci::parse_le_connection_complete(&[
            0x3e, 0x13, 0x01, 0x00, 0x0b, 0x00, 0x00, 0x01, 0x69, 0xcb, 0x25, 0x2d, 0x6a, 0x64,
            0x28, 0x00, 0x00, 0x00, 0xf4, 0x01, 0x05,
        ])
        .unwrap();
        assert_eq!(connection.connection_handle, 0x000b);
        assert_eq!(connection.role, 0);
        assert_eq!(
            connection.peer_address,
            [0x69, 0xcb, 0x25, 0x2d, 0x6a, 0x64]
        );
        assert_eq!(connection.connection_interval, 0x0028);
        assert_eq!(connection.supervision_timeout, 0x01f4);
        assert_eq!(
            hci::build_disconnect_h4(connection.connection_handle).unwrap(),
            [0x01, 0x06, 0x04, 0x03, 0x0b, 0x00, 0x13]
        );
    }

    #[test]
    fn exact_oneplus_diag_bt_cl_version_acl_round_trip() {
        use oneplus_hci as hci;

        let h4 = hci::build_bt_cl_version_h4_acl(0x0041).unwrap();
        assert_eq!(
            h4,
            [
                0x02, 0x41, 0x20, 0x0b, 0x00, 0x07, 0x00, 0x3a, 0x00, 0x09, 0x05, 0x0b, 0x21, 0x15,
                0x00, 0x00,
            ]
        );
        assert_eq!(
            hci::build_bt_cl_version_diag_request(0x0041).unwrap(),
            [
                0x4b, 0x0b, 0x04, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x02, 0x41, 0x20, 0x0b,
                0x00, 0x07, 0x00, 0x3a, 0x00, 0x09, 0x05, 0x0b, 0x21, 0x15, 0x00, 0x00,
            ]
        );

        let acl = hci::parse_complete_l2cap_acl(&h4[1..]).unwrap();
        assert_eq!(acl.connection_handle, 0x0041);
        assert_eq!(acl.packet_boundary_flag, 2);
        assert_eq!(acl.destination_cid, bt_cl::SIGNALING_CID);
        let cl = bt_cl::decode_pdu(bt_cl::CURRENT_VERSION, &acl.payload).unwrap();
        assert_eq!(cl.opcode, bt_cl::VERSION_INFO);
        assert_eq!(cl.payload, [0x0b, 0x21, 0x15, 0x00, 0x00]);

        let item_length = 12 + h4.len() - 1;
        let mut diag_log = vec![0x10, 0x00];
        diag_log.extend_from_slice(&(item_length as u16).to_le_bytes());
        diag_log.extend_from_slice(&(item_length as u16).to_le_bytes());
        diag_log.extend_from_slice(&qualcomm_diag::LOG_BT_HCI_ACL.to_le_bytes());
        diag_log.extend_from_slice(&[0; 8]);
        diag_log.extend_from_slice(&h4[1..]);
        assert_eq!(
            hci::parse_bluetooth_diag_log(&diag_log).unwrap(),
            hci::BluetoothLog::Acl(h4[1..].to_vec())
        );
    }

    #[test]
    fn oneplus_hci_rejects_wrong_lengths_and_targets() {
        use oneplus_hci as hci;

        assert!(matches!(
            hci::build_le_create_connection_h4(2, [0; 6]),
            Err(hci::CodecError::InvalidAddressType { .. })
        ));
        assert!(matches!(
            hci::build_disconnect_h4(0x0f00),
            Err(hci::CodecError::InvalidConnectionHandle { .. })
        ));
        assert!(matches!(
            hci::parse_command_status(&[0x0f, 0x04, 0, 1, 0x0d]),
            Err(hci::CodecError::LengthMismatch { .. })
        ));

        let mut bad_log = vec![0x10, 0, 12, 0, 12, 0, 0x66, 0x13];
        bad_log.extend_from_slice(&[0; 8]);
        bad_log.push(0x0e);
        assert!(matches!(
            hci::parse_bluetooth_diag_log(&bad_log),
            Err(hci::CodecError::LengthMismatch { .. })
        ));
    }
}
