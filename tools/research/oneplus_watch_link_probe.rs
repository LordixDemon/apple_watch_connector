//! Fail-closed Qualcomm DIAG sequencer for the exact user's OnePlus 13.
//!
//! Default mode is read-only USB enumeration.  The two write modes require
//! the exact WLAN-FTM PID, serial, interface/endpoints and acknowledgement.
//! No arbitrary HCI, DIAG, RF, NV, SSR, property, reboot or filesystem API is
//! exposed.

use rusb::{
    Context, DeviceDescriptor, DeviceHandle, Direction, Error as UsbError, TransferType, UsbContext,
};
use std::collections::VecDeque;
use std::env;
use std::error::Error;
use std::fmt;
use std::time::{Duration, Instant};
use uike_framing::{bt_cl, oneplus_hci as hci, qualcomm_diag as diag};

const VID_ONEPLUS: u16 = 0x22d9;
const PID_NORMAL: u16 = 0x2769;
const PID_WLAN_FTM: u16 = 0x276c;
const EXACT_SERIAL: &str = "9fb456e4";
const EXACT_TARGET_ACK: &str = "CPH2653-9fb456e4-22D9:276C";
const WATCH_TARGET_ACK: &str = "8AF4B4CE";
const WATCH_IDENTIFIER: [u8; 4] = [0x8a, 0xf4, 0xb4, 0xce];
const WATCH_MIN_RSSI: i8 = -75;

const LOG_CONFIG_COMMAND: u32 = 0x73;
const LOG_CONFIG_SET: u32 = 3;
const LOG_CONFIG_GET: u32 = 4;
const LOG_EQUIPMENT_BT: u32 = 1;
const LOG_MASK_MAX_ITEMS: u32 = 0x0fff;
const LOG_MASK_MAX_BYTES: usize = 512;
const LOG_MASK_REQUIRED_ITEMS: u32 = 0x369;

const USB_WRITE_TIMEOUT: Duration = Duration::from_secs(2);
const USB_READ_SLICE: Duration = Duration::from_millis(250);
const MAX_USB_READ: usize = 16_384;
const MAX_DIAG_FRAME: usize = 65_536;

const COMMAND_RESPONSE_TIMEOUT: Duration = Duration::from_secs(4);
const MASK_RESPONSE_TIMEOUT: Duration = Duration::from_millis(1500);
const WATCH_SCAN_TIMEOUT: Duration = Duration::from_secs(15);
const WATCH_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const WATCH_CL_TIMEOUT: Duration = Duration::from_secs(5);

type ProbeResult<T> = Result<T, ProbeError>;

#[derive(Debug)]
struct ProbeError(String);

impl fmt::Display for ProbeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl Error for ProbeError {}

impl From<UsbError> for ProbeError {
    fn from(error: UsbError) -> Self {
        Self(format!("USB error: {error}"))
    }
}

fn failure(message: impl Into<String>) -> ProbeError {
    ProbeError(message.into())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Mode {
    List,
    SelfTest,
    ReadLocalVersion,
    WatchLink,
}

#[derive(Debug)]
struct Options {
    mode: Mode,
    interface: Option<u8>,
    bulk_in: Option<u8>,
    bulk_out: Option<u8>,
    target_acknowledged: bool,
    watch_acknowledged: bool,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            mode: Mode::List,
            interface: None,
            bulk_in: None,
            bulk_out: None,
            target_acknowledged: false,
            watch_acknowledged: false,
        }
    }
}

fn usage(program: &str) {
    println!(
        "Usage:
  {program} [--list]
  {program} --self-test
  {program} --execute-read-local-version \\
      --interface N --bulk-in 0xNN --bulk-out 0xNN \\
      --acknowledge-exact-target {EXACT_TARGET_ACK}
  {program} --execute-watch-link \\
      --interface N --bulk-in 0xNN --bulk-out 0xNN \\
      --acknowledge-exact-target {EXACT_TARGET_ACK} \\
      --acknowledge-watch-target {WATCH_TARGET_ACK}

Default/list mode performs descriptor reads only.
Every write mode refuses all devices except
22D9:276C/{EXACT_SERIAL}.  Watch mode is hard-coded to
candidateIdentifier={WATCH_TARGET_ACK}."
    );
}

fn parse_u8(text: &str, label: &str) -> ProbeResult<u8> {
    let parsed = if let Some(hex) = text.strip_prefix("0x").or_else(|| text.strip_prefix("0X")) {
        u16::from_str_radix(hex, 16)
    } else {
        text.parse::<u16>()
    }
    .map_err(|_| failure(format!("invalid {label}: {text}")))?;
    u8::try_from(parsed).map_err(|_| failure(format!("{label} is outside u8: {text}")))
}

fn set_mode(options: &mut Options, mode: Mode) -> ProbeResult<()> {
    if options.mode != Mode::List {
        return Err(failure("only one mode may be selected"));
    }
    options.mode = mode;
    Ok(())
}

fn parse_options() -> ProbeResult<Options> {
    let mut options = Options::default();
    let mut arguments = env::args();
    let program = arguments
        .next()
        .unwrap_or_else(|| "oneplus_watch_link_probe".to_string());
    let mut rest = arguments.peekable();

    while let Some(argument) = rest.next() {
        match argument.as_str() {
            "--list" => {
                if options.mode != Mode::List {
                    return Err(failure("--list cannot be combined with another mode"));
                }
            }
            "--self-test" => set_mode(&mut options, Mode::SelfTest)?,
            "--execute-read-local-version" => {
                set_mode(&mut options, Mode::ReadLocalVersion)?;
            }
            "--execute-watch-link" => set_mode(&mut options, Mode::WatchLink)?,
            "--interface" => {
                let value = rest
                    .next()
                    .ok_or_else(|| failure("--interface requires a value"))?;
                options.interface = Some(parse_u8(&value, "interface")?);
            }
            "--bulk-in" => {
                let value = rest
                    .next()
                    .ok_or_else(|| failure("--bulk-in requires a value"))?;
                options.bulk_in = Some(parse_u8(&value, "bulk-in endpoint")?);
            }
            "--bulk-out" => {
                let value = rest
                    .next()
                    .ok_or_else(|| failure("--bulk-out requires a value"))?;
                options.bulk_out = Some(parse_u8(&value, "bulk-out endpoint")?);
            }
            "--acknowledge-exact-target" => {
                let value = rest
                    .next()
                    .ok_or_else(|| failure("--acknowledge-exact-target requires a value"))?;
                if value != EXACT_TARGET_ACK {
                    return Err(failure("exact-target acknowledgement does not match"));
                }
                options.target_acknowledged = true;
            }
            "--acknowledge-watch-target" => {
                let value = rest
                    .next()
                    .ok_or_else(|| failure("--acknowledge-watch-target requires a value"))?;
                if value.to_ascii_uppercase() != WATCH_TARGET_ACK {
                    return Err(failure("Watch-target acknowledgement does not match"));
                }
                options.watch_acknowledged = true;
            }
            "--help" | "-h" => {
                usage(&program);
                std::process::exit(0);
            }
            _ => return Err(failure(format!("unknown argument: {argument}"))),
        }
    }

    if matches!(options.mode, Mode::ReadLocalVersion | Mode::WatchLink) {
        let bulk_in = options
            .bulk_in
            .ok_or_else(|| failure("write mode requires --bulk-in"))?;
        let bulk_out = options
            .bulk_out
            .ok_or_else(|| failure("write mode requires --bulk-out"))?;
        if options.interface.is_none() || !options.target_acknowledged {
            return Err(failure(
                "write mode requires interface and exact-target acknowledgement",
            ));
        }
        if bulk_in & 0x80 == 0 || bulk_out & 0x80 != 0 {
            return Err(failure("bulk endpoint directions are invalid"));
        }
        if options.mode == Mode::WatchLink && !options.watch_acknowledged {
            return Err(failure(
                "Watch link requires the exact Watch-target acknowledgement",
            ));
        }
    } else if options.interface.is_some()
        || options.bulk_in.is_some()
        || options.bulk_out.is_some()
        || options.target_acknowledged
        || options.watch_acknowledged
    {
        return Err(failure("read-only/self-test mode rejects write arguments"));
    }

    Ok(options)
}

fn hex(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|byte| format!("{byte:02X}"))
        .collect::<Vec<_>>()
        .join(" ")
}

fn display_address(on_wire: [u8; 6]) -> String {
    on_wire
        .iter()
        .rev()
        .map(|byte| format!("{byte:02X}"))
        .collect::<Vec<_>>()
        .join(":")
}

fn dump_configuration<T: UsbContext>(
    device: &rusb::Device<T>,
    descriptor: &DeviceDescriptor,
) -> ProbeResult<()> {
    let configuration = device
        .active_config_descriptor()
        .or_else(|_| device.config_descriptor(0))?;
    println!(
        "  config={} interfaces={} class={:02X}/{:02X}/{:02X}",
        configuration.number(),
        configuration.num_interfaces(),
        descriptor.class_code(),
        descriptor.sub_class_code(),
        descriptor.protocol_code()
    );
    for interface in configuration.interfaces() {
        for alternate in interface.descriptors() {
            let adb = alternate.class_code() == 0xff
                && alternate.sub_class_code() == 0x42
                && alternate.protocol_code() == 0x01;
            println!(
                "  interface={} alt={} class={:02X}/{:02X}/{:02X}{}",
                alternate.interface_number(),
                alternate.setting_number(),
                alternate.class_code(),
                alternate.sub_class_code(),
                alternate.protocol_code(),
                if adb { " [ADB]" } else { "" }
            );
            for endpoint in alternate.endpoint_descriptors() {
                println!(
                    "    endpoint=0x{:02X} direction={:?} type={:?} max_packet={}",
                    endpoint.address(),
                    endpoint.direction(),
                    endpoint.transfer_type(),
                    endpoint.max_packet_size()
                );
            }
        }
    }
    Ok(())
}

fn open_exact_device(
    context: &Context,
    require_wlan_ftm: bool,
) -> ProbeResult<(DeviceHandle<Context>, DeviceDescriptor)> {
    let devices = context.devices()?;
    let mut exact_match: Option<(DeviceHandle<Context>, DeviceDescriptor)> = None;
    let mut matches = 0usize;

    for device in devices.iter() {
        let descriptor = device.device_descriptor()?;
        if descriptor.vendor_id() != VID_ONEPLUS
            || !matches!(descriptor.product_id(), PID_NORMAL | PID_WLAN_FTM)
        {
            continue;
        }

        let handle = match device.open() {
            Ok(handle) => handle,
            Err(error) => {
                eprintln!(
                    "OnePlus {:04X}:{:04X} open failed: {error}",
                    descriptor.vendor_id(),
                    descriptor.product_id()
                );
                continue;
            }
        };
        let serial = handle
            .read_serial_number_string_ascii(&descriptor)
            .unwrap_or_else(|_| "<unreadable>".to_string());
        let product = handle
            .read_product_string_ascii(&descriptor)
            .unwrap_or_else(|_| "<unreadable>".to_string());
        println!(
            "device={:04X}:{:04X} bus={} address={} product=\"{}\" serial=\"{}\"",
            descriptor.vendor_id(),
            descriptor.product_id(),
            device.bus_number(),
            device.address(),
            product,
            serial
        );
        dump_configuration(&device, &descriptor)?;

        if serial == EXACT_SERIAL {
            matches += 1;
            exact_match = Some((handle, descriptor));
        }
    }

    if matches != 1 {
        return Err(failure(format!(
            "exact serial match count is {matches}; expected 1"
        )));
    }
    let (handle, descriptor) = exact_match.ok_or_else(|| failure("exact target disappeared"))?;
    if require_wlan_ftm && descriptor.product_id() != PID_WLAN_FTM {
        return Err(failure(format!(
            "write mode requires exact WLAN-FTM PID 22D9:276C; observed {:04X}:{:04X}",
            descriptor.vendor_id(),
            descriptor.product_id()
        )));
    }
    Ok((handle, descriptor))
}

fn validate_interface(
    handle: &DeviceHandle<Context>,
    interface_number: u8,
    bulk_in: u8,
    bulk_out: u8,
) -> ProbeResult<()> {
    let device = handle.device();
    let configuration = device
        .active_config_descriptor()
        .or_else(|_| device.config_descriptor(0))?;
    let mut selected = None;

    for interface in configuration.interfaces() {
        for alternate in interface.descriptors() {
            if alternate.interface_number() == interface_number && alternate.setting_number() == 0 {
                if selected.is_some() {
                    return Err(failure("multiple alt=0 descriptors for interface"));
                }
                selected = Some(alternate);
            }
        }
    }

    let alternate = selected.ok_or_else(|| failure("requested interface alt=0 is absent"))?;
    if alternate.class_code() == 0xff
        && alternate.sub_class_code() == 0x42
        && alternate.protocol_code() == 0x01
    {
        return Err(failure("requested interface is ADB; refusing it"));
    }
    let mut found_in = false;
    let mut found_out = false;
    for endpoint in alternate.endpoint_descriptors() {
        if endpoint.transfer_type() != TransferType::Bulk {
            continue;
        }
        if endpoint.address() == bulk_in && endpoint.direction() == Direction::In {
            found_in = true;
        }
        if endpoint.address() == bulk_out && endpoint.direction() == Direction::Out {
            found_out = true;
        }
    }
    if !found_in || !found_out {
        return Err(failure(
            "requested interface lacks the exact bulk IN/OUT endpoints",
        ));
    }
    println!(
        "validated DIAG candidate: interface={} in=0x{:02X} out=0x{:02X}",
        interface_number, bulk_in, bulk_out
    );
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct LogMask {
    num_items: u32,
    bytes: Vec<u8>,
}

fn build_log_mask_get() -> Vec<u8> {
    let mut request = Vec::with_capacity(12);
    request.extend_from_slice(&LOG_CONFIG_COMMAND.to_le_bytes());
    request.extend_from_slice(&LOG_CONFIG_GET.to_le_bytes());
    request.extend_from_slice(&LOG_EQUIPMENT_BT.to_le_bytes());
    request
}

fn build_log_mask_set(mask: &LogMask) -> ProbeResult<Vec<u8>> {
    if mask.num_items > LOG_MASK_MAX_ITEMS
        || mask.bytes.len() != (mask.num_items as usize).div_ceil(8)
        || mask.bytes.len() > LOG_MASK_MAX_BYTES
    {
        return Err(failure("unsafe DIAG log mask shape"));
    }
    let mut request = Vec::with_capacity(16 + mask.bytes.len());
    request.extend_from_slice(&LOG_CONFIG_COMMAND.to_le_bytes());
    request.extend_from_slice(&LOG_CONFIG_SET.to_le_bytes());
    request.extend_from_slice(&LOG_EQUIPMENT_BT.to_le_bytes());
    request.extend_from_slice(&mask.num_items.to_le_bytes());
    request.extend_from_slice(&mask.bytes);
    Ok(request)
}

fn read_u32_le(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(
        bytes[offset..offset + 4]
            .try_into()
            .expect("validated fixed-width field"),
    )
}

fn parse_log_mask_response(
    payload: &[u8],
    expected_operation: u32,
) -> ProbeResult<Option<LogMask>> {
    if payload.len() < 20
        || read_u32_le(payload, 0) != LOG_CONFIG_COMMAND
        || read_u32_le(payload, 4) != expected_operation
    {
        return Ok(None);
    }
    if read_u32_le(payload, 8) != 0 || read_u32_le(payload, 12) != LOG_EQUIPMENT_BT {
        return Err(failure(
            "DIAG log-mask response returned failure/equipment mismatch",
        ));
    }
    let num_items = read_u32_le(payload, 16);
    if num_items > LOG_MASK_MAX_ITEMS {
        return Err(failure("DIAG log-mask response exceeds router maximum"));
    }
    let mask_length = (num_items as usize).div_ceil(8);
    if payload.len() != 20 + mask_length || mask_length > LOG_MASK_MAX_BYTES {
        return Err(failure("DIAG log-mask response has inconsistent length"));
    }
    Ok(Some(LogMask {
        num_items,
        bytes: payload[20..].to_vec(),
    }))
}

fn add_bluetooth_logs(original: &LogMask) -> ProbeResult<LogMask> {
    if original.num_items > LOG_MASK_MAX_ITEMS
        || original.bytes.len() != (original.num_items as usize).div_ceil(8)
    {
        return Err(failure("captured log mask is not internally consistent"));
    }
    let mut enabled = original.clone();
    if enabled.num_items < LOG_MASK_REQUIRED_ITEMS {
        enabled.num_items = LOG_MASK_REQUIRED_ITEMS;
        enabled
            .bytes
            .resize((LOG_MASK_REQUIRED_ITEMS as usize).div_ceil(8), 0);
    }
    enabled.bytes[108] |= 0x40;
    enabled.bytes[109] |= 0x01;
    Ok(enabled)
}

struct DiagTransport<'handle> {
    handle: &'handle mut DeviceHandle<Context>,
    bulk_in: u8,
    bulk_out: u8,
    partial: Vec<u8>,
    queued: VecDeque<Vec<u8>>,
    valid_frames: usize,
    invalid_frames: usize,
}

impl<'handle> DiagTransport<'handle> {
    fn new(handle: &'handle mut DeviceHandle<Context>, bulk_in: u8, bulk_out: u8) -> Self {
        Self {
            handle,
            bulk_in,
            bulk_out,
            partial: Vec::new(),
            queued: VecDeque::new(),
            valid_frames: 0,
            invalid_frames: 0,
        }
    }

    fn write_payload(&mut self, label: &str, payload: &[u8]) -> ProbeResult<()> {
        let frame = diag::encode_hdlc(payload);
        println!("TX {label} payload ({}): {}", payload.len(), hex(payload));
        println!("TX {label} HDLC ({}): {}", frame.len(), hex(&frame));
        let transferred = self
            .handle
            .write_bulk(self.bulk_out, &frame, USB_WRITE_TIMEOUT)?;
        if transferred != frame.len() {
            return Err(failure(format!(
                "short bulk OUT for {label}: {transferred}/{}",
                frame.len()
            )));
        }
        Ok(())
    }

    fn accept_usb_chunk(&mut self, chunk: &[u8]) -> ProbeResult<()> {
        println!("RX USB chunk ({}): {}", chunk.len(), hex(chunk));
        for &byte in chunk {
            if byte == diag::CONTROL_CHAR {
                if self.partial.is_empty() {
                    continue;
                }
                self.partial.push(byte);
                let frame = std::mem::take(&mut self.partial);
                match diag::decode_hdlc(&frame) {
                    Ok(payload) => {
                        self.valid_frames += 1;
                        println!("RX DIAG payload ({}): {}", payload.len(), hex(&payload));
                        self.queued.push_back(payload);
                    }
                    Err(error) => {
                        self.invalid_frames += 1;
                        return Err(failure(format!("invalid DIAG HDLC frame: {error}")));
                    }
                }
            } else {
                if self.partial.len() >= MAX_DIAG_FRAME {
                    self.invalid_frames += 1;
                    self.partial.clear();
                    return Err(failure("DIAG frame exceeded fail-closed limit"));
                }
                self.partial.push(byte);
            }
        }
        Ok(())
    }

    fn next_payload(&mut self, timeout: Duration) -> ProbeResult<Option<Vec<u8>>> {
        if let Some(payload) = self.queued.pop_front() {
            return Ok(Some(payload));
        }
        let deadline = Instant::now() + timeout;
        let mut buffer = vec![0u8; MAX_USB_READ];
        loop {
            let now = Instant::now();
            if now >= deadline {
                return Ok(None);
            }
            let slice = USB_READ_SLICE.min(deadline.saturating_duration_since(now));
            match self.handle.read_bulk(self.bulk_in, &mut buffer, slice) {
                Ok(transferred) => {
                    self.accept_usb_chunk(&buffer[..transferred])?;
                    if let Some(payload) = self.queued.pop_front() {
                        return Ok(Some(payload));
                    }
                }
                Err(UsbError::Timeout) => {}
                Err(error) => return Err(error.into()),
            }
        }
    }

    fn drain(&mut self, duration: Duration) -> ProbeResult<()> {
        let deadline = Instant::now() + duration;
        let mut discarded = 0usize;
        while Instant::now() < deadline {
            let remaining = deadline.saturating_duration_since(Instant::now());
            match self.next_payload(remaining)? {
                Some(_) => discarded += 1,
                None => break,
            }
        }
        self.queued.clear();
        if !self.partial.is_empty() {
            eprintln!(
                "drain boundary: discarded {}-byte incomplete frame",
                self.partial.len()
            );
            self.partial.clear();
            self.invalid_frames += 1;
        }
        println!("preflight drain: discarded_payloads={discarded}");
        Ok(())
    }
}

fn bluetooth_log(payload: &[u8]) -> ProbeResult<Option<hci::BluetoothLog>> {
    if payload.len() < 8 || payload[0] != 0x10 {
        return Ok(None);
    }
    let code = u16::from_le_bytes([payload[6], payload[7]]);
    if !matches!(code, diag::LOG_BT_HCI_EVENT | diag::LOG_BT_HCI_ACL) {
        return Ok(None);
    }
    hci::parse_bluetooth_diag_log(payload)
        .map(Some)
        .map_err(|error| failure(format!("malformed Bluetooth DIAG log: {error}")))
}

/// Uses one deadline for the entire response, including unrelated payloads.
fn wait_for_response<T>(
    timeout: Duration,
    mut next_payload: impl FnMut(Duration) -> ProbeResult<Option<Vec<u8>>>,
    mut match_payload: impl FnMut(&[u8]) -> ProbeResult<Option<T>>,
) -> ProbeResult<Option<T>> {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        let Some(payload) = next_payload(remaining)? else {
            break;
        };
        if let Some(response) = match_payload(&payload)? {
            return Ok(Some(response));
        }
    }
    Ok(None)
}

fn wait_log_mask(
    transport: &mut DiagTransport<'_>,
    operation: u32,
    expected: Option<&LogMask>,
) -> ProbeResult<LogMask> {
    wait_for_response(
        MASK_RESPONSE_TIMEOUT,
        |remaining| transport.next_payload(remaining),
        |payload| {
            let Some(mask) = parse_log_mask_response(payload, operation)? else {
                return Ok(None);
            };
            if let Some(expected_mask) = expected
                && &mask != expected_mask
            {
                return Err(failure(
                    "DIAG log-mask acknowledgement differs from request",
                ));
            }
            Ok(Some(mask))
        },
    )?
    .ok_or_else(|| failure("timed out waiting for exact DIAG log-mask response"))
}

fn wait_bootstrap(
    transport: &mut DiagTransport<'_>,
    start: bool,
    timeout: Duration,
) -> ProbeResult<()> {
    let expected = [0x4b, 0x0b, 0xfb, 0x04, 0x00, u8::from(start), 0x00];
    wait_for_response(
        timeout,
        |remaining| transport.next_payload(remaining),
        |payload| Ok((payload == expected).then_some(())),
    )?
    .ok_or_else(|| {
        failure(format!(
            "timed out waiting for exact ftm-{} response",
            if start { "start" } else { "stop" }
        ))
    })
}

fn send_h4(transport: &mut DiagTransport<'_>, label: &str, h4: &[u8]) -> ProbeResult<()> {
    let request = diag::build_ftm_h4_request(h4)
        .map_err(|error| failure(format!("cannot wrap {label} in FTM request: {error}")))?;
    transport.write_payload(label, &request)
}

fn wait_command_complete(
    transport: &mut DiagTransport<'_>,
    opcode: u16,
    timeout: Duration,
) -> ProbeResult<hci::CommandComplete> {
    wait_for_response(
        timeout,
        |remaining| transport.next_payload(remaining),
        |payload| {
            let Some(hci::BluetoothLog::Event(event)) = bluetooth_log(payload)? else {
                return Ok(None);
            };
            if event.first() != Some(&hci::EVENT_COMMAND_COMPLETE) {
                return Ok(None);
            }
            let complete = hci::parse_command_complete(&event)
                .map_err(|error| failure(format!("bad Command Complete: {error}")))?;
            if complete.opcode != opcode {
                return Ok(None);
            }
            if complete.status != 0 {
                return Err(failure(format!(
                    "HCI opcode 0x{opcode:04X} failed with status 0x{:02X}",
                    complete.status
                )));
            }
            Ok(Some(complete))
        },
    )?
    .ok_or_else(|| {
        failure(format!(
            "timed out waiting for Command Complete opcode 0x{opcode:04X}"
        ))
    })
}

fn send_command_complete(
    transport: &mut DiagTransport<'_>,
    label: &str,
    h4: &[u8],
) -> ProbeResult<hci::CommandComplete> {
    if h4.len() < 4 || h4[0] != hci::H4_COMMAND {
        return Err(failure("internal command vector is not complete H4"));
    }
    let opcode = u16::from_le_bytes([h4[1], h4[2]]);
    send_h4(transport, label, h4)?;
    wait_command_complete(transport, opcode, COMMAND_RESPONSE_TIMEOUT)
}

#[derive(Debug, Clone, Copy)]
struct WatchTarget {
    address_type: u8,
    address: [u8; 6],
    rssi: i8,
}

fn wait_watch_target(transport: &mut DiagTransport<'_>) -> ProbeResult<WatchTarget> {
    let deadline = Instant::now() + WATCH_SCAN_TIMEOUT;
    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        let Some(payload) = transport.next_payload(remaining)? else {
            break;
        };
        let Some(hci::BluetoothLog::Event(event)) = bluetooth_log(&payload)? else {
            continue;
        };
        if event.first() != Some(&hci::EVENT_LE_META)
            || event.get(2) != Some(&hci::LE_SUBEVENT_ADVERTISING_REPORT)
        {
            continue;
        }
        let reports = hci::parse_legacy_advertising_reports(&event)
            .map_err(|error| failure(format!("bad Advertising Report: {error}")))?;
        for report in reports {
            if hci::is_expected_watch_setup_target(&report, WATCH_IDENTIFIER, WATCH_MIN_RSSI)
                .map_err(|error| failure(format!("bad Watch Setup payload: {error}")))?
            {
                println!(
                    "WATCH TARGET: address={} type={} RSSI={} candidate={}",
                    display_address(report.address),
                    report.address_type,
                    report.rssi,
                    WATCH_TARGET_ACK
                );
                return Ok(WatchTarget {
                    address_type: report.address_type,
                    address: report.address,
                    rssi: report.rssi,
                });
            }
        }
    }
    Err(failure(format!(
        "target Watch Setup {WATCH_TARGET_ACK} not found above {WATCH_MIN_RSSI} dBm"
    )))
}

fn wait_connection(
    transport: &mut DiagTransport<'_>,
    target: WatchTarget,
    state: &mut CleanupState,
) -> ProbeResult<u16> {
    let deadline = Instant::now() + WATCH_CONNECT_TIMEOUT;
    let mut accepted_status = false;
    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        let Some(payload) = transport.next_payload(remaining)? else {
            break;
        };
        let Some(hci::BluetoothLog::Event(event)) = bluetooth_log(&payload)? else {
            continue;
        };
        match event.first().copied() {
            Some(hci::EVENT_COMMAND_STATUS) => {
                let status = hci::parse_command_status(&event)
                    .map_err(|error| failure(format!("bad Command Status: {error}")))?;
                if status.opcode != hci::OPCODE_LE_CREATE_CONNECTION {
                    continue;
                }
                if status.status != 0 {
                    return Err(failure(format!(
                        "LE Create Connection rejected with status 0x{:02X}",
                        status.status
                    )));
                }
                accepted_status = true;
                println!("LE Create Connection Command Status: success");
            }
            Some(hci::EVENT_LE_META)
                if event.get(2) == Some(&hci::LE_SUBEVENT_CONNECTION_COMPLETE) =>
            {
                let connection = hci::parse_le_connection_complete(&event)
                    .map_err(|error| failure(format!("LE connection failed: {error}")))?;
                /*
                 * Record the live handle before validating the peer and event
                 * ordering. Even a surprising success must be disconnected
                 * by cleanup, not treated as a pending create that can merely
                 * be cancelled.
                 */
                state.create_attempted = false;
                state.connection_handle = Some(connection.connection_handle);
                if connection.peer_address_type != target.address_type
                    || connection.peer_address != target.address
                {
                    return Err(failure(
                        "LE Connection Complete peer differs from locked Watch target",
                    ));
                }
                if !accepted_status {
                    return Err(failure(
                        "LE Connection Complete arrived without accepted Command Status",
                    ));
                }
                println!(
                    "WATCH LINK: handle=0x{:04X} peer={} interval={} timeout={}",
                    connection.connection_handle,
                    display_address(connection.peer_address),
                    connection.connection_interval,
                    connection.supervision_timeout
                );
                return Ok(connection.connection_handle);
            }
            _ => {}
        }
    }
    Err(failure("timed out waiting for exact Watch LE link"))
}

fn wait_bt_cl_version(transport: &mut DiagTransport<'_>, handle: u16) -> ProbeResult<(u8, u32)> {
    let deadline = Instant::now() + WATCH_CL_TIMEOUT;
    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        let Some(payload) = transport.next_payload(remaining)? else {
            break;
        };
        let Some(hci::BluetoothLog::Acl(acl)) = bluetooth_log(&payload)? else {
            continue;
        };
        let packet = hci::parse_complete_l2cap_acl(&acl)
            .map_err(|error| failure(format!("bad RX ACL/L2CAP packet: {error}")))?;
        if packet.connection_handle != handle || packet.destination_cid != bt_cl::SIGNALING_CID {
            continue;
        }
        let pdu = bt_cl::decode_pdu(bt_cl::CURRENT_VERSION, &packet.payload)
            .map_err(|error| failure(format!("bad BT_CL response: {error}")))?;
        if pdu.opcode != bt_cl::VERSION_INFO {
            println!("BT_CL non-VERSION opcode 0x{:02X}; waiting", pdu.opcode);
            continue;
        }
        if pdu.payload.len() != 5 {
            return Err(failure("BT_CL VERSION payload is not exactly five bytes"));
        }
        let version = pdu.payload[0];
        let features = u32::from_le_bytes(
            pdu.payload[1..5]
                .try_into()
                .expect("VERSION payload exact length was validated"),
        );
        println!("BT_CL VERSION: peer_version={version} features=0x{features:08X}");
        return Ok((version, features));
    }
    Err(failure(
        "timed out waiting for Watch BT_CL VERSION on fixed CID 0x003A",
    ))
}

fn wait_disconnect(transport: &mut DiagTransport<'_>, handle: u16) -> ProbeResult<()> {
    let deadline = Instant::now() + COMMAND_RESPONSE_TIMEOUT;
    let mut accepted_status = false;
    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        let Some(payload) = transport.next_payload(remaining)? else {
            break;
        };
        let Some(hci::BluetoothLog::Event(event)) = bluetooth_log(&payload)? else {
            continue;
        };
        if event.first() == Some(&hci::EVENT_COMMAND_STATUS) {
            let status = hci::parse_command_status(&event)
                .map_err(|error| failure(format!("bad disconnect status: {error}")))?;
            if status.opcode == hci::OPCODE_DISCONNECT {
                if status.status != 0 {
                    return Err(failure(format!(
                        "Disconnect rejected with status 0x{:02X}",
                        status.status
                    )));
                }
                accepted_status = true;
            }
            continue;
        }
        if event.len() == 6 && event[0] == 0x05 && event[1] == 0x04 {
            let event_handle = u16::from_le_bytes([event[3], event[4]]);
            if event[2] != 0 || event_handle != handle {
                return Err(failure("Disconnection Complete status/handle mismatch"));
            }
            if !accepted_status {
                return Err(failure(
                    "Disconnection Complete arrived without accepted Command Status",
                ));
            }
            println!(
                "WATCH DISCONNECTED: handle=0x{handle:04X} reason=0x{:02X}",
                event[5]
            );
            return Ok(());
        }
    }
    Err(failure("timed out waiting for Disconnection Complete"))
}

#[derive(Default)]
struct CleanupState {
    original_mask: Option<LogMask>,
    mask_change_attempted: bool,
    ftm_start_attempted: bool,
    scan_enable_attempted: bool,
    create_attempted: bool,
    connection_handle: Option<u16>,
}

fn best_effort_hci_cleanup(transport: &mut DiagTransport<'_>, state: &mut CleanupState) {
    if let Some(handle) = state.connection_handle {
        eprintln!("cleanup: disconnecting handle 0x{handle:04X}");
        if let Ok(command) = hci::build_disconnect_h4(handle)
            && send_h4(transport, "cleanup HCI Disconnect", &command).is_ok()
            && wait_disconnect(transport, handle).is_ok()
        {
            state.connection_handle = None;
        }
    } else if state.create_attempted {
        eprintln!("cleanup: canceling possible LE Create Connection");
        if let Ok(cancel) = hci::build_h4_command(hci::OPCODE_LE_CREATE_CONNECTION_CANCEL, &[]) {
            match send_command_complete(transport, "cleanup LE Create Connection Cancel", &cancel) {
                Ok(_) => state.create_attempted = false,
                Err(error) => eprintln!("cleanup warning: create cancel failed: {error}"),
            }
        }
    }
    if state.scan_enable_attempted {
        eprintln!("cleanup: disabling possible LE scan");
        if let Ok(disable) = hci::build_le_scan_enable_h4(false, false) {
            match send_command_complete(transport, "cleanup LE Scan Disable", &disable) {
                Ok(_) => state.scan_enable_attempted = false,
                Err(error) => eprintln!("cleanup warning: scan disable failed: {error}"),
            }
        }
    }
}

fn best_effort_session_cleanup(transport: &mut DiagTransport<'_>, state: &mut CleanupState) {
    if state.ftm_start_attempted {
        best_effort_hci_cleanup(transport, state);
        eprintln!("cleanup: sending exact ftm-stop");
        match transport.write_payload("cleanup ftm-stop", &diag::FTM_BOOTSTRAP_STOP) {
            Ok(()) => match wait_bootstrap(transport, false, Duration::from_secs(1)) {
                Ok(()) => state.ftm_start_attempted = false,
                Err(error) => eprintln!("cleanup warning: ftm-stop not acknowledged: {error}"),
            },
            Err(error) => eprintln!("cleanup warning: ftm-stop write failed: {error}"),
        }
    }
    if state.mask_change_attempted {
        if let Some(original) = state.original_mask.as_ref() {
            eprintln!("cleanup: restoring exact original DIAG log mask");
            match build_log_mask_set(original) {
                Ok(request) => {
                    match transport.write_payload("cleanup log-mask-restore", &request) {
                        Ok(()) => match wait_log_mask(transport, LOG_CONFIG_SET, Some(original)) {
                            Ok(_) => state.mask_change_attempted = false,
                            Err(error) => {
                                eprintln!("cleanup warning: mask restore not acknowledged: {error}")
                            }
                        },
                        Err(error) => {
                            eprintln!("cleanup warning: mask restore write failed: {error}")
                        }
                    }
                }
                Err(error) => eprintln!("cleanup warning: cannot rebuild original mask: {error}"),
            }
        } else {
            eprintln!("cleanup warning: original log-mask snapshot is missing");
        }
    }
}

fn start_session(transport: &mut DiagTransport<'_>, state: &mut CleanupState) -> ProbeResult<()> {
    transport.drain(Duration::from_millis(500))?;

    let get = build_log_mask_get();
    transport.write_payload("log-mask-get", &get)?;
    let original = wait_log_mask(transport, LOG_CONFIG_GET, None)?;
    transport.write_payload("log-mask-get-confirm", &get)?;
    let confirmed = wait_log_mask(transport, LOG_CONFIG_GET, None)?;
    if confirmed != original {
        return Err(failure(
            "two consecutive equipment-1 log-mask snapshots differ; refusing state change",
        ));
    }
    println!(
        "captured and confirmed original equipment-1 mask: num_items={} bytes={}",
        original.num_items,
        original.bytes.len()
    );
    let enabled = add_bluetooth_logs(&original)?;
    state.original_mask = Some(original.clone());
    if enabled != original {
        let set = build_log_mask_set(&enabled)?;
        state.mask_change_attempted = true;
        transport.write_payload("log-mask-add-Bluetooth", &set)?;
        wait_log_mask(transport, LOG_CONFIG_SET, Some(&enabled))?;
    } else {
        println!("Bluetooth event/ACL log bits already enabled");
    }

    state.ftm_start_attempted = true;
    transport.write_payload("ftm-start", &diag::FTM_BOOTSTRAP_START)?;
    wait_bootstrap(transport, true, Duration::from_millis(2500))?;
    Ok(())
}

fn finish_session(transport: &mut DiagTransport<'_>, state: &mut CleanupState) -> ProbeResult<()> {
    best_effort_hci_cleanup(transport, state);
    if state.connection_handle.is_some() || state.create_attempted || state.scan_enable_attempted {
        return Err(failure(
            "HCI cleanup did not reach a confirmed idle state before ftm-stop",
        ));
    }

    if state.ftm_start_attempted {
        transport.write_payload("ftm-stop", &diag::FTM_BOOTSTRAP_STOP)?;
        wait_bootstrap(transport, false, Duration::from_secs(1))?;
        state.ftm_start_attempted = false;
    }
    if state.mask_change_attempted {
        let original = state
            .original_mask
            .as_ref()
            .ok_or_else(|| failure("original mask vanished before restore"))?;
        let restore = build_log_mask_set(original)?;
        transport.write_payload("log-mask-restore", &restore)?;
        wait_log_mask(transport, LOG_CONFIG_SET, Some(original))?;
        state.mask_change_attempted = false;
    }
    Ok(())
}

fn execute_local_version(transport: &mut DiagTransport<'_>) -> ProbeResult<()> {
    let h4 = diag::HCI_READ_LOCAL_VERSION_INFORMATION;
    let complete = send_command_complete(transport, "HCI Read Local Version", &h4)?;
    if complete.return_parameters.len() != 9 {
        return Err(failure("Read Local Version returned an unexpected length"));
    }
    println!(
        "PASS: Qualcomm controller answered HCI opcode 0x1001: {}",
        hex(&complete.return_parameters)
    );
    Ok(())
}

fn execute_watch_link(
    transport: &mut DiagTransport<'_>,
    state: &mut CleanupState,
) -> ProbeResult<()> {
    let setup = hci::watch_scan_setup_h4_commands(true)
        .map_err(|error| failure(format!("cannot build scan setup: {error}")))?;
    let labels = [
        "HCI Reset",
        "HCI Set Event Mask",
        "HCI LE Set Event Mask",
        "HCI LE Set Scan Parameters",
        "HCI LE Set Scan Enable",
    ];
    for (index, (label, command)) in labels.iter().zip(setup.iter()).enumerate() {
        if index == setup.len() - 1 {
            state.scan_enable_attempted = true;
        }
        send_command_complete(transport, label, command)?;
    }

    println!(
        "WATCH SEARCH: candidate={} minimum_rssi={} timeout={}s",
        WATCH_TARGET_ACK,
        WATCH_MIN_RSSI,
        WATCH_SCAN_TIMEOUT.as_secs()
    );
    let target = wait_watch_target(transport)?;

    let disable = hci::build_le_scan_enable_h4(false, false)
        .map_err(|error| failure(format!("cannot build scan disable: {error}")))?;
    send_command_complete(transport, "HCI LE Set Scan Disable", &disable)?;
    state.scan_enable_attempted = false;

    let create = hci::build_le_create_connection_h4(target.address_type, target.address)
        .map_err(|error| failure(format!("cannot build LE Create Connection: {error}")))?;
    println!(
        "fresh target handoff: address={} type={} RSSI={}",
        display_address(target.address),
        target.address_type,
        target.rssi
    );
    state.create_attempted = true;
    send_h4(transport, "HCI LE Create Connection", &create)?;
    let handle = wait_connection(transport, target, state)?;

    let version = hci::build_bt_cl_version_h4_acl(handle)
        .map_err(|error| failure(format!("cannot build BT_CL VERSION ACL: {error}")))?;
    send_h4(transport, "Apple BT_CL VERSION on CID 0x003A", &version)?;
    let (peer_version, peer_features) = wait_bt_cl_version(transport, handle)?;

    let disconnect = hci::build_disconnect_h4(handle)
        .map_err(|error| failure(format!("cannot build disconnect: {error}")))?;
    send_h4(transport, "HCI Disconnect", &disconnect)?;
    wait_disconnect(transport, handle)?;
    state.connection_handle = None;

    println!(
        "PASS: Watch LE link and BT_CL VERSION on CID 0x003A; peer_version={} features=0x{:08X}",
        peer_version, peer_features
    );
    Ok(())
}

fn execute(mut handle: DeviceHandle<Context>, options: &Options) -> ProbeResult<()> {
    let interface = options
        .interface
        .ok_or_else(|| failure("missing interface after option validation"))?;
    let bulk_in = options
        .bulk_in
        .ok_or_else(|| failure("missing bulk-in after option validation"))?;
    let bulk_out = options
        .bulk_out
        .ok_or_else(|| failure("missing bulk-out after option validation"))?;
    validate_interface(&handle, interface, bulk_in, bulk_out)?;
    handle.claim_interface(interface)?;

    let mut state = CleanupState::default();
    let run_result;
    {
        let mut transport = DiagTransport::new(&mut handle, bulk_in, bulk_out);
        run_result = (|| {
            start_session(&mut transport, &mut state)?;
            match options.mode {
                Mode::ReadLocalVersion => execute_local_version(&mut transport)?,
                Mode::WatchLink => execute_watch_link(&mut transport, &mut state)?,
                _ => return Err(failure("internal execute-mode mismatch")),
            }
            finish_session(&mut transport, &mut state)?;
            Ok(())
        })();
        if run_result.is_err() {
            best_effort_session_cleanup(&mut transport, &mut state);
        }
        println!(
            "transport summary: valid_frames={} invalid_frames={} residual_scan={} \
             residual_create={} residual_handle={:?} residual_ftm={} residual_mask={}",
            transport.valid_frames,
            transport.invalid_frames,
            state.scan_enable_attempted,
            state.create_attempted,
            state.connection_handle,
            state.ftm_start_attempted,
            state.mask_change_attempted
        );
    }

    let release_result = handle.release_interface(interface);
    if let Err(error) = release_result {
        return Err(failure(format!("release interface failed: {error}")));
    }
    run_result
}

fn self_test() -> ProbeResult<()> {
    let get = build_log_mask_get();
    if get != [0x73, 0, 0, 0, 0x04, 0, 0, 0, 0x01, 0, 0, 0] {
        return Err(failure("self-test: exact log-mask GET vector differs"));
    }

    let original = LogMask {
        num_items: 8,
        bytes: vec![0xa5],
    };
    let enabled = add_bluetooth_logs(&original)?;
    if enabled.num_items != LOG_MASK_REQUIRED_ITEMS
        || enabled.bytes[0] != 0xa5
        || enabled.bytes[108] != 0x40
        || enabled.bytes[109] != 0x01
    {
        return Err(failure(
            "self-test: preserving Bluetooth mask transform failed",
        ));
    }
    let set = build_log_mask_set(&enabled)?;
    let mut response = Vec::with_capacity(20 + enabled.bytes.len());
    response.extend_from_slice(&LOG_CONFIG_COMMAND.to_le_bytes());
    response.extend_from_slice(&LOG_CONFIG_SET.to_le_bytes());
    response.extend_from_slice(&0u32.to_le_bytes());
    response.extend_from_slice(&LOG_EQUIPMENT_BT.to_le_bytes());
    response.extend_from_slice(&enabled.num_items.to_le_bytes());
    response.extend_from_slice(&enabled.bytes);
    if parse_log_mask_response(&response, LOG_CONFIG_SET)? != Some(enabled.clone()) {
        return Err(failure("self-test: exact log-mask response parser failed"));
    }
    if set.len() != 16 + enabled.bytes.len() {
        return Err(failure("self-test: log-mask set length differs"));
    }

    let setup = hci::watch_scan_setup_h4_commands(true)
        .map_err(|error| failure(format!("self-test: scan vectors: {error}")))?;
    if setup.last().map(Vec::as_slice) != Some([0x01, 0x0c, 0x20, 0x02, 0x01, 0x00].as_slice()) {
        return Err(failure("self-test: scan-enable vector differs"));
    }
    let disable = hci::build_le_scan_enable_h4(false, false)
        .map_err(|error| failure(format!("self-test: scan disable: {error}")))?;
    if disable != [0x01, 0x0c, 0x20, 0x02, 0x00, 0x00] {
        return Err(failure("self-test: scan-disable vector differs"));
    }
    let version = hci::build_bt_cl_version_diag_request(0x0041)
        .map_err(|error| failure(format!("self-test: VERSION vector: {error}")))?;
    if version
        != [
            0x4b, 0x0b, 0x04, 0, 0, 0, 0x10, 0, 0, 0, 0x02, 0x41, 0x20, 0x0b, 0, 0x07, 0, 0x3a, 0,
            0x09, 0x05, 0x0b, 0x21, 0x15, 0, 0,
        ]
    {
        return Err(failure(
            "self-test: exact Watch VERSION DIAG vector differs",
        ));
    }

    println!("self-test PASS");
    println!("log-mask GET: {}", hex(&get));
    println!("BT_CL VERSION DIAG: {}", hex(&version));
    Ok(())
}

fn real_main() -> ProbeResult<()> {
    let options = parse_options()?;
    if options.mode == Mode::SelfTest {
        return self_test();
    }

    let context = Context::new()?;
    let require_wlan_ftm = matches!(options.mode, Mode::ReadLocalVersion | Mode::WatchLink);
    let (handle, descriptor) = open_exact_device(&context, require_wlan_ftm)?;

    if options.mode == Mode::List {
        println!(
            "read-only enumeration PASS: exact serial {} at {:04X}:{:04X}",
            EXACT_SERIAL,
            descriptor.vendor_id(),
            descriptor.product_id()
        );
        return Ok(());
    }

    println!(
        "WRITE MODE ARMED: {:04X}:{:04X}/{} mode={:?}",
        descriptor.vendor_id(),
        descriptor.product_id(),
        EXACT_SERIAL,
        options.mode
    );
    execute(handle, &options)
}

fn main() {
    if let Err(error) = real_main() {
        eprintln!("ERROR: {error}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn response_wait_skips_unrelated_payloads_without_resetting_timeout() {
        let timeout = Duration::from_secs(1);
        let mut remaining_times = Vec::new();
        let mut payloads = VecDeque::from([vec![1], vec![2], vec![3]]);
        let response = wait_for_response(
            timeout,
            |remaining| {
                remaining_times.push(remaining);
                Ok(payloads.pop_front())
            },
            |payload| Ok((payload == [2]).then_some(42)),
        )
        .unwrap();
        assert_eq!(response, Some(42));
        assert_eq!(payloads, VecDeque::from([vec![3]]));
        assert_eq!(remaining_times.len(), 2);
        assert!(remaining_times[0] <= timeout);
        assert!(remaining_times[1] <= remaining_times[0]);
    }

    #[test]
    fn response_wait_stops_when_transport_times_out() {
        let response = wait_for_response(
            Duration::from_secs(1),
            |_| Ok(None),
            |_| -> ProbeResult<Option<()>> { panic!("no payload to match") },
        )
        .unwrap();
        assert_eq!(response, None);
    }

    #[test]
    fn response_wait_does_not_read_after_deadline() {
        let response = wait_for_response(
            Duration::ZERO,
            |_| panic!("deadline already expired"),
            |_| -> ProbeResult<Option<()>> { panic!("no payload to match") },
        )
        .unwrap();
        assert_eq!(response, None);
    }

    #[test]
    fn response_wait_propagates_transport_errors() {
        let error = wait_for_response(
            Duration::from_secs(1),
            |_| Err(failure("read failed")),
            |_| -> ProbeResult<Option<()>> { panic!("no payload to match") },
        )
        .unwrap_err();
        assert_eq!(error.to_string(), "read failed");
    }

    #[test]
    fn response_wait_fails_immediately_on_malformed_payload() {
        let mut payloads = VecDeque::from([vec![1], vec![2]]);
        let error = wait_for_response(
            Duration::from_secs(1),
            |_| Ok(payloads.pop_front()),
            |_| -> ProbeResult<Option<()>> { Err(failure("malformed response")) },
        )
        .unwrap_err();
        assert_eq!(error.to_string(), "malformed response");
        assert_eq!(payloads, VecDeque::from([vec![2]]));
    }

    #[test]
    fn complete_offline_self_test_passes() {
        self_test().unwrap();
    }

    #[test]
    fn log_mask_transform_preserves_every_existing_bit() {
        let original = LogMask {
            num_items: 32,
            bytes: vec![0xa5, 0x5a, 0xff, 0x81],
        };
        let enabled = add_bluetooth_logs(&original).unwrap();
        assert_eq!(&enabled.bytes[..original.bytes.len()], original.bytes);
        assert_eq!(enabled.bytes[108], 0x40);
        assert_eq!(enabled.bytes[109], 0x01);
    }

    #[test]
    fn log_mask_response_rejects_length_and_status_corruption() {
        let mut response = vec![0u8; 21];
        response[0..4].copy_from_slice(&LOG_CONFIG_COMMAND.to_le_bytes());
        response[4..8].copy_from_slice(&LOG_CONFIG_GET.to_le_bytes());
        response[12..16].copy_from_slice(&LOG_EQUIPMENT_BT.to_le_bytes());
        response[16..20].copy_from_slice(&8u32.to_le_bytes());
        assert!(
            parse_log_mask_response(&response, LOG_CONFIG_GET)
                .unwrap()
                .is_some()
        );

        let mut wrong_length = response.clone();
        wrong_length.push(0);
        assert!(parse_log_mask_response(&wrong_length, LOG_CONFIG_GET).is_err());

        response[8] = 1;
        assert!(parse_log_mask_response(&response, LOG_CONFIG_GET).is_err());
    }
}
