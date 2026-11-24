//! Strict raw-HCI building/parsing for the staged OnePlus Qualcomm experiment.
//!
//! The byte vectors here reuse the scan and connection parameters already
//! proven against the user's physical Apple Watch through the CM748.  This
//! module itself performs no USB, DIAG, Bluetooth, or radio I/O.

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
