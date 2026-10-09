//! Windows BLE transport. WinRT owns the controller; the shared Rust session
//! consumes observations on its polling thread. GATT is not the Watch's raw
//! L2CAP pairing/IDS transport, so those capabilities remain unavailable.
use std::sync::{
    Arc,
    atomic::{AtomicU64, Ordering},
    mpsc::{self, Receiver, Sender},
};
use watch_core::transport::{Capabilities, Transport, TransportEvent};

#[cfg(target_os = "windows")]
mod native;

#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
enum Command {
    Scan {
        epoch: u64,
        generation: u64,
    },
    StopScan,
    Connect {
        epoch: u64,
        generation: u64,
        id: String,
    },
    Disconnect {
        epoch: u64,
        id: String,
    },
    Shutdown,
}

pub struct WindowsTransport {
    commands: Sender<Command>,
    events: Receiver<TransportEvent>,
    failures: Sender<TransportEvent>,
    discoveries: Receiver<TransportEvent>,
    generation: Arc<AtomicU64>,
}
impl Default for WindowsTransport {
    fn default() -> Self {
        Self::new()
    }
}
impl WindowsTransport {
    pub fn new() -> Self {
        let (commands, requests) = mpsc::channel();
        let (send, events) = mpsc::channel();
        let failures = send.clone();
        let (discover, discoveries) = mpsc::sync_channel(128);
        let generation = Arc::new(AtomicU64::new(0));
        #[cfg(target_os = "windows")]
        {
            let worker_generation = generation.clone();
            let failures = send.clone();
            if std::thread::Builder::new()
                .name("watch-windows-bluetooth".into())
                .spawn(move || native::run(requests, send, discover, worker_generation))
                .is_err()
            {
                let _ = failures.send(TransportEvent::Adapter {
                    state: "UNSUPPORTED".into(),
                });
            }
        }
        #[cfg(not(target_os = "windows"))]
        {
            drop(requests);
            drop(discover);
            let _ = send.send(TransportEvent::Adapter {
                state: "UNSUPPORTED".into(),
            });
        }
        Self {
            commands,
            events,
            failures,
            discoveries,
            generation,
        }
    }
    pub fn next_event(&mut self) -> Option<TransportEvent> {
        self.events
            .try_recv()
            .ok()
            .or_else(|| self.discoveries.try_recv().ok())
    }
    fn advance(&self) -> u64 {
        self.generation.fetch_add(1, Ordering::SeqCst) + 1
    }
    fn queue(&self, command: Command, epoch: u64) -> bool {
        if self.commands.send(command).is_err() {
            let _ = self.failures.send(TransportEvent::Failed {
                epoch,
                message: "Windows Bluetooth worker is unavailable".into(),
            });
            return false;
        }
        true
    }
}
impl Transport for WindowsTransport {
    fn capabilities(&self) -> Capabilities {
        Capabilities {
            platform: "windows".into(),
            backend: "windows_winrt".into(),
            discovery: cfg!(target_os = "windows"),
            bluetooth_link: cfg!(target_os = "windows"),
            fixed_l2cap: false,
            pairing: false,
            operational_ids: false,
            pairing_unavailable_reason: Some("WINDOWS_PAIRING_BOOTSTRAP_RESTRICTED".into()),
        }
    }
    fn start_scan(&mut self, epoch: u64) {
        let generation = self.advance();
        self.queue(Command::Scan { epoch, generation }, epoch);
    }
    fn stop_scan(&mut self) {
        self.advance();
        let _ = self.commands.send(Command::StopScan);
    }
    fn connect(&mut self, epoch: u64, id: &str) {
        let generation = self.advance();
        if !self.queue(
            Command::Connect {
                epoch,
                generation,
                id: id.into(),
            },
            epoch,
        ) {
            let _ = self.failures.send(TransportEvent::Disconnected {
                epoch,
                id: id.into(),
            });
        }
    }
    fn disconnect(&mut self, epoch: u64, id: &str) {
        self.advance();
        if !self.queue(
            Command::Disconnect {
                epoch,
                id: id.into(),
            },
            epoch,
        ) {
            let _ = self.failures.send(TransportEvent::Disconnected {
                epoch,
                id: id.into(),
            });
        }
    }
    fn send_pairing(&mut self, _epoch: u64, _id: &str, _bytes: &[u8]) {
        // No raw pairing endpoint exists on WinRT. Capabilities gate requests.
    }
}
impl Drop for WindowsTransport {
    fn drop(&mut self) {
        self.advance();
        let _ = self.commands.send(Command::Shutdown);
    }
}

/// UUID-shaped discovery token expected by the desktop UI. Includes address
/// type so public and random addresses cannot alias. Never persisted as a bond.
#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
fn discovery_id(address: u64, address_type: i32) -> String {
    format!("00000000-0000-0000-{address_type:04x}-{address:012x}")
}

/// AD type 0x16 begins with a little-endian 16-bit service UUID.
#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
fn setup_data(section: &[u8]) -> Option<Vec<u8>> {
    if section.len() > 66 || section.get(..2)? != [0x25, 0xfe] {
        return None;
    }
    let data = &section[2..];
    watch_protocol::advertisement::WatchAdvertisement::from_service_data(data)?;
    Some(data.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn service_sections_require_verified_watch_metadata() {
        let mut section = vec![0x25, 0xfe];
        section.extend_from_slice(&[
            6, 0x20, 0x86, 0x93, 0xf4, 0xce, 0x64, 0x38, 0x50, 0, 0xd0, 0x10, 0,
        ]);
        assert_eq!(setup_data(&section).unwrap(), section[2..]);
        for end in 0..section.len() {
            assert!(setup_data(&section[..end]).is_none());
        }
        section[0] = 0x26;
        assert!(setup_data(&section).is_none());
        section[0] = 0x25;
        section.resize(67, 0);
        assert!(setup_data(&section).is_none());
    }

    #[test]
    fn public_and_random_addresses_are_distinct_ui_tokens() {
        assert_eq!(
            discovery_id(0x123456789abc, 0),
            "00000000-0000-0000-0000-123456789abc"
        );
        assert_ne!(
            discovery_id(0x123456789abc, 0),
            discovery_id(0x123456789abc, 1)
        );
    }

    #[test]
    fn ble_link_does_not_claim_pairing_or_ids() {
        let transport = WindowsTransport::new();
        let caps = transport.capabilities();
        assert!(!caps.pairing && !caps.fixed_l2cap && !caps.operational_ids);
        assert_eq!(caps.backend, "windows_winrt");
    }

    #[test]
    fn unavailable_worker_releases_selection_and_allows_a_new_scan() {
        use watch_core::session::{Phase, Session};
        let (commands, requests) = mpsc::channel();
        drop(requests);
        let (failures, events) = mpsc::channel();
        let (_, discoveries) = mpsc::channel();
        let mut transport = WindowsTransport {
            commands,
            events,
            failures,
            discoveries,
            generation: Arc::new(AtomicU64::new(0)),
        };
        let mut session = Session::default();
        let epoch = session.start_scan().unwrap();
        session.observe(TransportEvent::Discovered {
            epoch,
            id: "observed".into(),
            name: "Watch".into(),
            rssi: -40,
            apple: false,
            setup: vec![
                6, 0x20, 0x86, 0x93, 0xf4, 0xce, 0x64, 0x38, 0x50, 0, 0xd0, 0x10, 0,
            ],
        });
        session.connect("observed").unwrap();
        transport.connect(epoch, "observed");
        while let Some(event) = transport.next_event() {
            session.observe(event);
        }
        let snapshot = session.snapshot(transport.capabilities());
        assert_eq!(snapshot.phase, Phase::Failed);
        assert!(snapshot.selected_id.is_none());
        assert!(session.start_scan().is_ok());
    }
}
