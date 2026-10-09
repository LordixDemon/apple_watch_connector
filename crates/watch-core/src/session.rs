use crate::transport::{Capabilities, TransportEvent};
use serde::Serialize;
use std::{
    collections::BTreeMap,
    time::{Duration, Instant},
};
use watch_protocol::advertisement::WatchAdvertisement;
const MAX_DEVICES: usize = 64;
const DEVICE_EXPIRY: Duration = Duration::from_secs(20);
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Phase {
    Idle,
    Discovering,
    Connecting,
    BluetoothLink,
    PairingSecurity,
    PinRequired,
    PinDerivation,
    PinAuthentication,
    PinAuthenticated,
    Disconnecting,
    Failed,
}
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Device {
    pub id: String,
    pub name: String,
    pub rssi: i16,
    pub product_type: Option<String>,
    pub watch_os: Option<String>,
    #[serde(skip)]
    seen: Instant,
    #[serde(skip)]
    setup: Option<[u8; 12]>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Snapshot {
    pub api_version: u32,
    pub core_version: &'static str,
    pub epoch: u64,
    pub revision: u64,
    pub phase: Phase,
    pub adapter_state: String,
    pub selected_id: Option<String>,
    pub watch_ready: bool,
    pub pin_required: bool,
    pub pairing_stage: Option<String>,
    pub pairing_endpoint_registered: bool,
    pub pairing_stream_open: bool,
    pub capabilities: Capabilities,
    pub devices: Vec<Device>,
    pub error: Option<String>,
}
pub struct Session {
    epoch: u64,
    revision: u64,
    phase: Phase,
    adapter: String,
    selected: Option<String>,
    devices: BTreeMap<String, Device>,
    error: Option<String>,
    pairing_stage: Option<String>,
    endpoint_registered: bool,
    stream_open: bool,
}
impl Default for Session {
    fn default() -> Self {
        Self {
            epoch: 0,
            revision: 0,
            phase: Phase::Idle,
            adapter: "UNKNOWN".into(),
            selected: None,
            devices: BTreeMap::new(),
            error: None,
            pairing_stage: None,
            endpoint_registered: false,
            stream_open: false,
        }
    }
}
impl Session {
    pub fn start_scan(&mut self) -> Result<u64, &'static str> {
        if !matches!(self.phase, Phase::Idle | Phase::Failed) || self.selected.is_some() {
            return Err("BUSY");
        }
        self.epoch += 1;
        self.revision += 1;
        self.phase = Phase::Discovering;
        self.devices.clear();
        self.error = None;
        self.selected = None;
        self.clear_pairing();
        Ok(self.epoch)
    }
    pub fn connect(&mut self, id: &str) -> Result<u64, &'static str> {
        if self.phase != Phase::Discovering {
            return Err("BUSY");
        }
        self.expire();
        if !self.devices.contains_key(id) {
            return Err("UNKNOWN_DEVICE");
        }
        if self.devices[id].setup.is_none() {
            return Err("NOT_A_SETUP_WATCH");
        }
        self.phase = Phase::Connecting;
        self.selected = Some(id.into());
        self.revision += 1;
        Ok(self.epoch)
    }
    pub fn stop(&mut self) -> Option<(u64, String)> {
        self.clear_pairing();
        self.revision += 1;
        if let Some(id) = self.selected.clone() {
            self.phase = Phase::Disconnecting;
            Some((self.epoch, id))
        } else {
            self.epoch += 1;
            self.phase = Phase::Idle;
            self.error = None;
            None
        }
    }
    pub fn observe(&mut self, event: TransportEvent) {
        match event {
            TransportEvent::Adapter { state } => {
                self.adapter = state;
                if matches!(
                    self.adapter.as_str(),
                    "UNAUTHORIZED" | "UNSUPPORTED" | "POWERED_OFF" | "RESETTING"
                ) {
                    self.epoch += 1;
                    self.phase = Phase::Failed;
                    self.selected = None;
                    self.error = Some(self.adapter.clone());
                    self.clear_pairing();
                }
            }
            TransportEvent::Discovered {
                epoch,
                id,
                name,
                rssi,
                apple,
                setup,
            } => {
                if epoch != self.epoch || self.phase != Phase::Discovering || id.len() > 64 {
                    return;
                }
                let watch = WatchAdvertisement::from_service_data(&setup);
                if !apple && watch.is_none() {
                    return;
                }
                self.expire();
                if self.devices.len() >= MAX_DEVICES && !self.devices.contains_key(&id) {
                    return;
                }
                self.devices.insert(
                    id.clone(),
                    Device {
                        id,
                        name: name.chars().take(96).collect(),
                        rssi,
                        product_type: watch.as_ref().map(WatchAdvertisement::product_type),
                        watch_os: watch.map(|w| {
                            w.system_version
                                .iter()
                                .map(u16::to_string)
                                .collect::<Vec<_>>()
                                .join(".")
                        }),
                        seen: Instant::now(),
                        setup: WatchAdvertisement::binding_data(&setup),
                    },
                );
            }
            TransportEvent::Connected { epoch, id } => {
                if epoch != self.epoch
                    || self.phase != Phase::Connecting
                    || self.selected.as_ref() != Some(&id)
                {
                    return;
                }
                self.phase = Phase::BluetoothLink;
            }
            TransportEvent::Disconnected { epoch, id } => {
                if epoch != self.epoch || self.selected.as_ref() != Some(&id) {
                    return;
                }
                if self.phase != Phase::Failed {
                    self.phase = Phase::Idle;
                }
                self.selected = None;
                self.clear_pairing();
            }
            TransportEvent::PairingEndpointRegistered { epoch, id } => {
                if !self.owns_pairing(epoch, &id) {
                    return;
                }
                self.endpoint_registered = true;
            }
            TransportEvent::PairingPipeOpened { epoch, id } => {
                if !self.owns_pairing(epoch, &id) {
                    return;
                }
                self.stream_open = true;
                self.phase = Phase::PairingSecurity;
            }
            TransportEvent::PairingPipeData { .. } => return, // consumed privately by the engine
            TransportEvent::Failed { epoch, message } => {
                if epoch != self.epoch {
                    return;
                }
                self.phase = Phase::Failed;
                self.error = Some(message.chars().take(256).collect());
                self.clear_pairing();
            }
        }
        self.revision += 1;
    }
    fn clear_pairing(&mut self) {
        self.pairing_stage = None;
        self.endpoint_registered = false;
        self.stream_open = false;
    }
    pub fn owns_pairing(&self, epoch: u64, id: &str) -> bool {
        self.epoch == epoch
            && self.selected.as_deref() == Some(id)
            && !matches!(
                self.phase,
                Phase::Idle | Phase::Discovering | Phase::Disconnecting | Phase::Failed
            )
    }
    pub fn selected_setup(&self) -> Option<[u8; 12]> {
        self.devices.get(self.selected.as_ref()?)?.setup
    }
    pub fn pairing_progress(&mut self, epoch: u64, id: &str, phase: Phase, stage: &str) {
        if !self.owns_pairing(epoch, id) {
            return;
        }
        if self.phase != phase || self.pairing_stage.as_deref() != Some(stage) {
            self.phase = phase;
            self.pairing_stage = Some(stage.into());
            self.revision += 1;
        }
    }
    fn expire(&mut self) {
        self.devices
            .retain(|id, d| self.selected.as_ref() == Some(id) || d.seen.elapsed() < DEVICE_EXPIRY);
    }
    pub fn snapshot(&mut self, capabilities: Capabilities) -> Snapshot {
        self.expire();
        Snapshot {
            api_version: 1,
            core_version: env!("CARGO_PKG_VERSION"),
            epoch: self.epoch,
            revision: self.revision,
            phase: self.phase,
            adapter_state: self.adapter.clone(),
            selected_id: self.selected.clone(),
            watch_ready: false,
            pin_required: self.phase == Phase::PinRequired,
            pairing_stage: self.pairing_stage.clone(),
            pairing_endpoint_registered: self.endpoint_registered,
            pairing_stream_open: self.stream_open,
            capabilities,
            devices: self.devices.values().cloned().collect(),
            error: self.error.clone(),
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn device(epoch: u64, id: &str) -> TransportEvent {
        TransportEvent::Discovered {
            epoch,
            id: id.into(),
            name: "Observed".into(),
            rssi: -40,
            apple: true,
            setup: vec![
                6, 0x20, 0x86, 0x93, 0xf4, 0xce, 0x64, 0x38, 0x50, 0, 0xd0, 0x10, 0,
            ],
        }
    }
    #[test]
    fn no_connection_without_observed_device_and_no_ready_from_ble() {
        let mut s = Session::default();
        let epoch = s.start_scan().unwrap();
        assert_eq!(s.connect("invented"), Err("UNKNOWN_DEVICE"));
        s.observe(device(epoch, "real"));
        s.connect("real").unwrap();
        assert_eq!(s.phase, Phase::Connecting);
        s.observe(TransportEvent::Connected {
            epoch,
            id: "real".into(),
        });
        assert_eq!(s.phase, Phase::BluetoothLink);
        assert_eq!(s.start_scan(), Err("BUSY"));
    }
    #[test]
    fn cancellation_invalidates_late_discovery_and_old_epochs() {
        let mut s = Session::default();
        let epoch = s.start_scan().unwrap();
        s.stop();
        let newer = s.start_scan().unwrap();
        s.observe(device(epoch, "old"));
        assert!(s.devices.is_empty());
        s.observe(device(newer, "new"));
        s.connect("new").unwrap();
        s.stop();
        s.observe(TransportEvent::Connected {
            epoch: newer,
            id: "new".into(),
        });
        assert_eq!(s.phase, Phase::Disconnecting);
        s.observe(TransportEvent::Disconnected {
            epoch: newer,
            id: "new".into(),
        });
        assert_eq!(s.phase, Phase::Idle);
    }
    #[test]
    fn permission_loss_invalidates_connection_success() {
        let mut s = Session::default();
        let epoch = s.start_scan().unwrap();
        s.observe(device(epoch, "real"));
        s.connect("real").unwrap();
        s.observe(TransportEvent::Adapter {
            state: "UNAUTHORIZED".into(),
        });
        s.observe(TransportEvent::Connected {
            epoch,
            id: "real".into(),
        });
        assert_eq!(s.phase, Phase::Failed);
        assert!(s.selected.is_none());
    }
    #[test]
    fn selected_identity_survives_advertisement_expiry_until_disconnection() {
        let mut s = Session::default();
        let epoch = s.start_scan().unwrap();
        s.observe(device(epoch, "selected"));
        s.observe(device(epoch, "other"));
        s.connect("selected").unwrap();
        for d in s.devices.values_mut() {
            d.seen = Instant::now() - DEVICE_EXPIRY - Duration::from_secs(1);
        }
        s.expire();
        assert_eq!(s.devices.len(), 1);
        assert!(s.devices.contains_key("selected"));
        s.observe(TransportEvent::Connected {
            epoch,
            id: "selected".into(),
        });
        s.expire();
        assert!(s.devices.contains_key("selected"));
        s.stop();
        s.observe(TransportEvent::Disconnected {
            epoch,
            id: "selected".into(),
        });
        s.expire();
        assert!(s.devices.is_empty());
    }
    #[test]
    fn bounded_inventory_does_not_guess_apple_devices_are_watches() {
        let mut s = Session::default();
        let epoch = s.start_scan().unwrap();
        for i in 0..100 {
            s.observe(TransportEvent::Discovered {
                epoch,
                id: i.to_string(),
                name: "Apple beacon".into(),
                rssi: -40,
                apple: true,
                setup: vec![],
            });
        }
        assert_eq!(s.devices.len(), MAX_DEVICES);
        assert!(s.devices.values().all(|d| d.product_type.is_none()));
        assert_eq!(s.connect("0"), Err("NOT_A_SETUP_WATCH"));
    }
    #[test]
    fn pairing_callbacks_cannot_restore_a_cancelled_or_failed_attempt() {
        let mut s = Session::default();
        let epoch = s.start_scan().unwrap();
        s.observe(device(epoch, "selected"));
        s.connect("selected").unwrap();
        s.observe(TransportEvent::PairingEndpointRegistered {
            epoch,
            id: "other".into(),
        });
        assert!(!s.endpoint_registered);
        s.observe(TransportEvent::PairingPipeOpened {
            epoch,
            id: "selected".into(),
        });
        assert!(s.stream_open);
        s.pairing_progress(
            epoch,
            "selected",
            Phase::PinAuthenticated,
            "PIN_AUTHENTICATED",
        );
        assert_eq!(s.phase, Phase::PinAuthenticated);
        s.observe(TransportEvent::Failed {
            epoch,
            message: "stream failed".into(),
        });
        assert!(!s.stream_open);
        assert!(s.pairing_stage.is_none());
        s.observe(TransportEvent::PairingPipeOpened {
            epoch,
            id: "selected".into(),
        });
        assert_eq!(s.phase, Phase::Failed);
        s.stop();
        s.observe(TransportEvent::PairingEndpointRegistered {
            epoch,
            id: "selected".into(),
        });
        s.observe(TransportEvent::PairingPipeOpened {
            epoch,
            id: "selected".into(),
        });
        s.pairing_progress(epoch, "selected", Phase::PinRequired, "PIN_REQUIRED");
        assert_eq!(s.phase, Phase::Disconnecting);
        assert!(!s.endpoint_registered);
        assert!(!s.stream_open);
        s.observe(TransportEvent::Disconnected {
            epoch,
            id: "selected".into(),
        });
        let newer = s.start_scan().unwrap();
        s.observe(device(newer, "selected"));
        s.connect("selected").unwrap();
        s.observe(TransportEvent::PairingPipeOpened {
            epoch,
            id: "selected".into(),
        });
        assert_eq!(s.phase, Phase::Connecting);
        assert!(!s.stream_open);
    }
}
