//! Exclusive USB HCI broker for Windows. Enumeration never redirects a device.
use crate::windows_usb_backend::UsbBackend;
use rusb::{Context, Device, DeviceHandle, Direction, TransferType, UsbContext, UsbOption};
use serde_json::{Value, json};
use std::{
    io::{self, Read, Write},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    time::Duration,
};

const SLICE: Duration = Duration::from_millis(100);
const READ_TIMEOUT: Duration = Duration::from_secs(1);
#[path = "realtek.rs"]
mod realtek;

pub fn driver_version() -> Option<[u16; 4]> {
    use windows::{
        Win32::{
            Storage::FileSystem::{
                GetFileVersionInfoSizeW, GetFileVersionInfoW, VS_FIXEDFILEINFO, VerQueryValueW,
            },
            System::SystemInformation::GetSystemDirectoryW,
        },
        core::{PCWSTR, w},
    };
    let mut directory = [0u16; 32768];
    // Resolve the actual system directory rather than trusting an environment path.
    let count = unsafe { GetSystemDirectoryW(Some(&mut directory)) } as usize;
    if count == 0 || count >= directory.len() {
        return None;
    }
    let mut path = directory[..count].to_vec();
    path.extend("\\drivers\\UsbDk.sys".encode_utf16());
    path.push(0);
    unsafe {
        let size = GetFileVersionInfoSizeW(PCWSTR(path.as_ptr()), None);
        if size == 0 || size > 1024 * 1024 {
            return None;
        }
        let mut data = vec![0u8; size as usize];
        GetFileVersionInfoW(PCWSTR(path.as_ptr()), None, size, data.as_mut_ptr().cast()).ok()?;
        let mut pointer = std::ptr::null_mut();
        let mut length = 0;
        if !VerQueryValueW(data.as_ptr().cast(), w!("\\"), &mut pointer, &mut length).as_bool()
            || pointer.is_null()
            || (length as usize) < std::mem::size_of::<VS_FIXEDFILEINFO>()
        {
            return None;
        }
        let info = std::ptr::read_unaligned(pointer.cast::<VS_FIXEDFILEINFO>());
        if info.dwSignature != 0xfeef04bd {
            return None;
        }
        Some([
            (info.dwFileVersionMS >> 16) as u16,
            info.dwFileVersionMS as u16,
            (info.dwFileVersionLS >> 16) as u16,
            info.dwFileVersionLS as u16,
        ])
    }
}
pub fn driver_compatible() -> bool {
    // 1.0.22 sends an unsolicited D0 power IRP and can crash Bluetooth drivers.
    // Only the pinned signed release is enabled until another is validated.
    // https://github.com/daynix/UsbDk/issues/115
    driver_version().is_some_and(version_supported)
}
fn version_supported(version: [u16; 4]) -> bool {
    version == [1, 0, 21, 0]
}
fn context(redirect: bool) -> rusb::Result<Context> {
    if redirect {
        Context::with_options(&[UsbOption::use_usbdk()])
    } else {
        Context::new()
    }
}
pub fn usbdk_available() -> bool {
    driver_compatible() && context(true).is_ok()
}
fn discovery_backend() -> UsbBackend {
    let preference = std::env::var("WATCH_WINDOWS_USB_BACKEND").ok();
    // Detect again on each background refresh, never once for the process.
    let native =
        !matches!(preference.as_deref(), Some("winusb" | "usbdk")) && native_controller_available();
    UsbBackend::select(preference.as_deref(), native)
}
fn native_controller_available() -> bool {
    let Ok(context) = context(false) else {
        return false;
    };
    let Ok(devices) = context.devices() else {
        return false;
    };
    devices.iter().any(|device| native_controller(&device))
}
fn native_controller(device: &Device<Context>) -> bool {
    let Ok(pipes) = endpoints(device) else {
        return false;
    };
    let Ok(handle) = device.open() else {
        return false;
    };
    // WinUSB initialization only queries descriptors. It never redirects the
    // Windows Bluetooth stack or sends HCI commands during enumeration.
    if handle.claim_interface(pipes.interface).is_err() {
        return false;
    }
    let _ = handle.release_interface(pipes.interface);
    true
}
fn id(device: &Device<Context>, backend: UsbBackend) -> rusb::Result<String> {
    let descriptor = device.device_descriptor()?;
    let ports = device
        .port_numbers()?
        .iter()
        .map(u8::to_string)
        .collect::<Vec<_>>()
        .join(".");
    if ports.is_empty() {
        return Err(rusb::Error::NotSupported);
    }
    Ok(format!(
        "{}:{}:{}:{:04x}:{:04x}",
        backend.prefix(),
        device.bus_number(),
        ports,
        descriptor.vendor_id(),
        descriptor.product_id()
    ))
}
#[derive(Clone, Copy, Debug)]
struct Endpoints {
    interface: u8,
    voice_interface: Option<u8>,
    events: u8,
    event_packet_size: u16,
    acl_in: u8,
    acl_packet_size: u16,
    acl_out: u8,
}
fn endpoints(device: &Device<Context>) -> rusb::Result<Endpoints> {
    let config = device.config_descriptor(0)?;
    let voice_interface = config.interfaces().find_map(|interface| {
        interface.descriptors().find_map(|alternate| {
            ((
                alternate.class_code(),
                alternate.sub_class_code(),
                alternate.protocol_code(),
            ) == (0xe0, 1, 1)
                && alternate
                    .endpoint_descriptors()
                    .any(|endpoint| endpoint.transfer_type() == TransferType::Isochronous))
            .then_some(alternate.interface_number())
        })
    });
    for interface in config.interfaces() {
        for alternate in interface.descriptors() {
            if alternate.setting_number() != 0
                || (
                    alternate.class_code(),
                    alternate.sub_class_code(),
                    alternate.protocol_code(),
                ) != (0xe0, 1, 1)
            {
                continue;
            }
            let mut events = None;
            let mut acl_in = None;
            let mut acl_out = None;
            for endpoint in alternate.endpoint_descriptors() {
                match (endpoint.transfer_type(), endpoint.direction()) {
                    (TransferType::Interrupt, Direction::In) => {
                        events = Some((endpoint.address(), endpoint.max_packet_size()))
                    }
                    (TransferType::Bulk, Direction::In) => {
                        acl_in = Some((endpoint.address(), endpoint.max_packet_size()))
                    }
                    (TransferType::Bulk, Direction::Out) => acl_out = Some(endpoint.address()),
                    _ => {}
                }
            }
            if let (Some(events), Some(acl_in), Some(acl_out)) = (events, acl_in, acl_out) {
                return Ok(Endpoints {
                    interface: alternate.interface_number(),
                    voice_interface,
                    events: events.0,
                    event_packet_size: events.1,
                    acl_in: acl_in.0,
                    acl_packet_size: acl_in.1,
                    acl_out,
                });
            }
        }
    }
    Err(rusb::Error::NotSupported)
}
pub fn inventory() -> Result<Value, String> {
    // UsbDk's bus numbers are filter IDs; WinUSB numbers root hubs instead.
    // Enumerating and opening must use the same backend identity namespace.
    // Creating a UsbDk context and reading descriptors never redirects a device.
    let backend = discovery_backend();
    let native = backend == UsbBackend::WinUsb;
    let usbdk = usbdk_available();
    let context = context(usbdk && !native).map_err(|e| e.to_string())?;
    let devices = context.devices().map_err(|e| e.to_string())?;
    let mut adapters = Vec::new();
    for device in devices.iter() {
        if endpoints(&device).is_err() || native && !native_controller(&device) {
            continue;
        }
        if let (Ok(id), Ok(descriptor)) = (id(&device, backend), device.device_descriptor()) {
            adapters.push(json!({"id":id,"address":id,"name":format!("USB Bluetooth {:04x}:{:04x}",descriptor.vendor_id(),descriptor.product_id()),"index":adapters.len(),"controller":format!("usb{}",adapters.len())}));
        }
    }
    let available = if native { !adapters.is_empty() } else { usbdk };
    Ok(
        json!({"adapters":adapters,"rawHciAvailable":available,"usbBackend":if native {"winusb"} else {"usbdk"},"usbdkAvailable":usbdk,"usbdkVersion":driver_version()}),
    )
}
fn usb_error(error: rusb::Error) -> io::Error {
    io::Error::other(error.to_string())
}
pub fn valid_outgoing(packet: &[u8]) -> bool {
    match packet.first() {
        Some(1) => packet.len() >= 4 && packet.len() == 4 + packet[3] as usize,
        Some(2) => {
            packet.len() >= 5
                && packet.len() == 5 + u16::from_le_bytes([packet[3], packet[4]]) as usize
        }
        _ => false,
    }
}
struct Decoder {
    kind: u8,
    bytes: Vec<u8>,
}
impl Decoder {
    fn accept(&mut self, data: &[u8]) -> io::Result<Vec<Vec<u8>>> {
        self.bytes.extend_from_slice(data);
        let header = if self.kind == 4 { 2 } else { 4 };
        let mut packets = Vec::new();
        while self.bytes.len() >= header {
            let payload = if self.kind == 4 {
                self.bytes[1] as usize
            } else {
                u16::from_le_bytes([self.bytes[2], self.bytes[3]]) as usize
            };
            let length = header + payload;
            if length >= u16::MAX as usize {
                return Err(io::Error::other("HCI packet exceeds envelope"));
            }
            if self.bytes.len() < length {
                break;
            }
            let mut packet = vec![self.kind];
            packet.extend(self.bytes.drain(..length));
            packets.push(packet);
        }
        Ok(packets)
    }
}
enum Message {
    Outgoing(Vec<u8>),
    Incoming(Vec<u8>),
    Stop,
    Error(String),
}
fn receive(
    handle: Arc<DeviceHandle<Context>>,
    pipe: u8,
    interrupt: bool,
    packet_size: u16,
    stop: Arc<AtomicBool>,
    send: mpsc::SyncSender<Message>,
) {
    let mut decoder = Decoder {
        kind: if interrupt { 4 } else { 2 },
        bytes: Vec::new(),
    };
    // Whole USB packets fit even at the end of a transfer (64/512/1024 bytes).
    // The decoder separately assembles HCI frames across transfer boundaries.
    let mut buffer = vec![0; packet_size as usize];
    let mut recoveries = 0;
    while !stop.load(Ordering::SeqCst) {
        let result = if interrupt {
            handle.read_interrupt(pipe, &mut buffer, READ_TIMEOUT)
        } else {
            handle.read_bulk(pipe, &mut buffer, READ_TIMEOUT)
        };
        match result {
            Ok(count) => match decoder.accept(&buffer[..count]) {
                Ok(packets) => {
                    for packet in packets {
                        if send.try_send(Message::Incoming(packet)).is_err() {
                            stop.store(true, Ordering::SeqCst);
                            return;
                        }
                    }
                }
                Err(error) => {
                    let _ = send.try_send(Message::Error(error.to_string()));
                    return;
                }
            },
            Err(rusb::Error::Timeout) => {}
            Err(rusb::Error::Io | rusb::Error::Pipe) if recoveries < 2 => {
                // Reset a stalled receive pipe after Windows hands it over.
                // No packet is synthesized; upper protocol retries must still
                // obtain an actual Watch reply before advancing any state.
                recoveries += 1;
                match handle.clear_halt(pipe) {
                    Ok(()) => {
                        decoder.bytes.clear();
                        eprintln!("USB HCI receive pipe 0x{pipe:02x} reset ({recoveries}/2)");
                    }
                    Err(error) => {
                        let _ = send.try_send(Message::Error(format!(
                            "USB ACL receive pipe reset failed: {error}"
                        )));
                        return;
                    }
                }
            }
            Err(error) => {
                let _ = send.try_send(Message::Error(format!(
                    "USB HCI {} read endpoint 0x{pipe:02x}: {error}",
                    if interrupt { "event" } else { "ACL" }
                )));
                return;
            }
        }
    }
}
/// A selected device is redirected only by this explicit acquisition call.
pub fn run(selected: &str) -> io::Result<()> {
    let backend = UsbBackend::from_identity(selected)
        .ok_or_else(|| io::Error::other("Invalid selected USB Bluetooth identity"))?;
    let native = backend == UsbBackend::WinUsb;
    if !native && !driver_compatible() {
        return Err(io::Error::other(
            "Unsupported UsbDk driver. Install signed UsbDk 1.0.21; version 1.0.22 can cause WDF_VIOLATION.",
        ));
    }
    let context = context(!native).map_err(usb_error)?;
    let devices = context.devices().map_err(usb_error)?;
    let candidates = devices
        .iter()
        .filter(|device| id(device, backend).is_ok_and(|id| id == selected))
        .collect::<Vec<_>>();
    if candidates.len() != 1 {
        return Err(io::Error::other(
            "Selected USB Bluetooth controller is missing or ambiguous",
        ));
    }
    let device = &candidates[0];
    let pipes = endpoints(device).map_err(usb_error)?;
    let handle = Arc::new(device.open().map_err(usb_error)?);
    // Windows may leave USB endpoint/voice alternate settings from its session.
    // Reset only this explicitly acquired controller before starting HCI traffic.
    handle
        .reset()
        .map_err(|error| io::Error::other(format!("USB controller reset: {error}")))?;
    handle.claim_interface(pipes.interface).map_err(usb_error)?;
    handle
        .set_alternate_setting(pipes.interface, 0)
        .map_err(usb_error)?;
    if let Some(voice) = pipes
        .voice_interface
        .filter(|&voice| voice != pipes.interface)
    {
        handle.claim_interface(voice).map_err(usb_error)?;
        handle.set_alternate_setting(voice, 0).map_err(usb_error)?;
    }
    handle.clear_halt(pipes.acl_in).map_err(usb_error)?;
    handle.clear_halt(pipes.acl_out).map_err(usb_error)?;
    let descriptor = device.device_descriptor().map_err(usb_error)?;
    if native && descriptor.vendor_id() == 0x0bda && descriptor.product_id() == 0xb00e {
        realtek::initialize(&handle, pipes)?;
    }
    let stop = Arc::new(AtomicBool::new(false));
    let (send, messages) = mpsc::sync_channel(256);
    let readers = [
        (pipes.events, true, pipes.event_packet_size),
        (pipes.acl_in, false, pipes.acl_packet_size),
    ]
    .map(|(pipe, interrupt, packet_size)| {
        let (handle, stop, send) = (handle.clone(), stop.clone(), send.clone());
        std::thread::spawn(move || receive(handle, pipe, interrupt, packet_size, stop, send))
    });
    std::thread::spawn(move || {
        let mut input = io::stdin().lock();
        loop {
            let mut header = [0; 2];
            if let Err(error) = input.read_exact(&mut header) {
                let _ = send.send(if error.kind() == io::ErrorKind::UnexpectedEof {
                    Message::Stop
                } else {
                    Message::Error(error.to_string())
                });
                return;
            }
            let mut packet = vec![0; u16::from_be_bytes(header) as usize];
            if let Err(error) = input.read_exact(&mut packet) {
                let _ = send.send(Message::Error(error.to_string()));
                return;
            }
            if !valid_outgoing(&packet) {
                let _ = send.send(Message::Error("Invalid outgoing H4 packet".into()));
                return;
            }
            if send.send(Message::Outgoing(packet)).is_err() {
                return;
            }
        }
    });
    let result = (|| {
        let mut output = io::stdout().lock();
        output.write_all(&[0, 1, 0])?;
        output.flush()?;
        loop {
            if stop.load(Ordering::SeqCst) {
                return Err(io::Error::other("USB HCI event queue overflow"));
            }
            match messages.recv_timeout(SLICE) {
                Ok(Message::Stop) => return Ok(()),
                Ok(Message::Error(error)) => return Err(io::Error::other(error)),
                Ok(Message::Incoming(packet)) => {
                    output.write_all(&(packet.len() as u16).to_be_bytes())?;
                    output.write_all(&packet)?;
                    output.flush()?;
                }
                Ok(Message::Outgoing(packet)) => {
                    let payload = &packet[1..];
                    let count = if packet[0] == 1 {
                        handle.write_control(0x20, 0, 0, 0, payload, Duration::from_secs(2))
                    } else {
                        handle.write_bulk(pipes.acl_out, payload, Duration::from_secs(2))
                    }
                    .map_err(|error| {
                        io::Error::other(format!(
                            "USB HCI {} write: {error}",
                            if packet[0] == 1 { "command" } else { "ACL" }
                        ))
                    })?;
                    if count != payload.len() {
                        return Err(io::Error::other("Short USB HCI write"));
                    }
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {}
                Err(_) => return Err(io::Error::other("HCI command stream ended")),
            }
        }
    })();
    stop.store(true, Ordering::SeqCst);
    for reader in readers {
        let _ = reader.join();
    }
    let _ = handle.release_interface(pipes.interface);
    if let Some(voice) = pipes
        .voice_interface
        .filter(|&voice| voice != pipes.interface)
    {
        let _ = handle.release_interface(voice);
    }
    drop(handle); // UsbDk restores the Windows driver.
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn known_power_irp_regression_is_never_enabled() {
        assert!(version_supported([1, 0, 21, 0]));
        assert!(!version_supported([1, 0, 22, 0]));
        assert!(!version_supported([1, 0, 21, 1]));
    }
    #[test]
    fn usb_fragments_and_coalesced_acl_packets_preserve_boundaries() {
        let mut decoder = Decoder {
            kind: 2,
            bytes: vec![],
        };
        assert!(decoder.accept(&[1, 0, 2]).unwrap().is_empty());
        assert_eq!(
            decoder.accept(&[0, 7, 8, 2, 0, 0, 0]).unwrap(),
            vec![vec![2, 1, 0, 2, 0, 7, 8], vec![2, 2, 0, 0, 0]]
        );
    }
    #[test]
    fn direction_and_truncation_are_rejected() {
        assert!(valid_outgoing(&[1, 3, 12, 0]));
        assert!(!valid_outgoing(&[4, 14, 0]));
        assert!(!valid_outgoing(&[2, 1, 0, 3, 0, 7]));
    }
}
