//! Qualcomm DIAG wire framing used by the exact CPH2653 `diag-router`.
//!
//! This module deliberately performs no USB or device I/O. It only builds and
//! validates byte strings so a packet can be reviewed and logged before a
//! separately authorized physical experiment.

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
