use serde::{Deserialize, Serialize};
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Capabilities {
    pub platform: String,
    pub backend: String,
    pub discovery: bool,
    pub bluetooth_link: bool,
    pub fixed_l2cap: bool,
    pub pairing: bool,
    pub operational_ids: bool,
    pub pairing_unavailable_reason: Option<String>,
}
#[derive(Debug, Deserialize)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum TransportEvent {
    Adapter {
        state: String,
    },
    Discovered {
        epoch: u64,
        id: String,
        name: String,
        rssi: i16,
        #[serde(default)]
        apple: bool,
        #[serde(default)]
        setup: Vec<u8>,
    },
    Connected {
        epoch: u64,
        id: String,
    },
    Disconnected {
        epoch: u64,
        id: String,
    },
    PairingEndpointRegistered {
        epoch: u64,
        id: String,
    },
    PairingPipeOpened {
        epoch: u64,
        id: String,
    },
    PairingPipeData {
        epoch: u64,
        id: String,
        bytes: Vec<u8>,
    },
    Failed {
        epoch: u64,
        message: String,
    },
}
pub trait Transport: Send {
    fn capabilities(&self) -> Capabilities;
    fn start_scan(&mut self, epoch: u64);
    fn stop_scan(&mut self);
    fn connect(&mut self, epoch: u64, id: &str);
    fn disconnect(&mut self, epoch: u64, id: &str);
    fn send_pairing(&mut self, epoch: u64, id: &str, bytes: &[u8]);
}
