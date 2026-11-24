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

pub mod bt_cl;
pub mod oneplus_hci;
pub mod qualcomm_diag;

#[cfg(test)]
mod tests;
