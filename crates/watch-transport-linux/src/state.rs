//! Observed Linux session facts; no child process or controller ownership.
use serde_json::{Value, json};
use std::{collections::VecDeque, time::Instant};

pub(crate) struct Device {
    pub(crate) value: Value,
    pub(crate) seen: Instant,
}
pub(crate) struct State {
    pub(crate) epoch: u64,
    pub(crate) phase: String,
    pub(crate) adapter: String,
    pub(crate) selected: Option<String>,
    pub(crate) devices: Vec<Device>,
    pub(crate) pair_id: Option<String>,
    pub(crate) product: Option<String>,
    pub(crate) verified: bool,
    pub(crate) activated: bool,
    pub(crate) ids_ready: bool,
    pub(crate) ready: bool,
    pub(crate) error: Option<String>,
    pub(crate) journal: VecDeque<String>,
}
impl Default for State {
    fn default() -> Self {
        Self {
            epoch: 0,
            phase: "IDLE".into(),
            adapter: "UNKNOWN".into(),
            selected: None,
            devices: vec![],
            pair_id: None,
            product: None,
            verified: false,
            activated: false,
            ids_ready: false,
            ready: false,
            error: None,
            journal: VecDeque::new(),
        }
    }
}
pub(crate) fn token(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(i, c)| {
            if [8, 13, 18, 23].contains(&i) {
                c == b'-'
            } else {
                c.is_ascii_hexdigit()
            }
        })
}
impl State {
    pub(crate) fn fail(&mut self, reason: impl Into<String>) {
        self.phase = "FAILED".into();
        self.error = Some(reason.into());
        self.ready = false;
        self.ids_ready = false;
    }

    pub(crate) fn begin_stop(&mut self) {
        self.phase = "DISCONNECTING".into();
        self.ready = false;
        self.ids_ready = false;
    }

    pub(crate) fn restore_public_pair(&mut self, directory: &std::path::Path) {
        let path = directory.join("identity/pair-public.json");
        let Ok(metadata) = std::fs::symlink_metadata(&path) else {
            return;
        };
        if !metadata.is_file() || metadata.len() > 4096 {
            return;
        }
        let Ok(bytes) = std::fs::read(path) else {
            return;
        };
        let Ok(value) = serde_json::from_slice::<Value>(&bytes) else {
            return;
        };
        let Some(id) = value["pairId"].as_str().filter(|id| token(id)) else {
            return;
        };
        self.pair_id = Some(id.into());
        self.product = value["productType"]
            .as_str()
            .filter(|p| p.starts_with("Watch") && p.len() < 32)
            .map(str::to_owned);
        self.activated = value["activationConfirmed"] == true;
        self.verified = self.activated
            && (value["protocolVerified"] == true || value["ownerConfirmed"] == true);
    }
    pub(crate) fn observe(&mut self, line: &str) {
        // Defensive fence in addition to the worker's private IPC interception.
        if line.starts_with("BOND_SECRET_")
            || line.starts_with("PAIRING_SESSION_")
            || line.starts_with("LOCAL_IDENTITY_")
            || line.starts_with("LOCAL_IDS_PUBLIC_")
            || line.starts_with("RESTORED_PAIRING_")
        {
            self.fail("Worker exposed a private protocol envelope");
            return;
        }
        // Only a new worker epoch can leave failure or explicit disconnection.
        if ["DISCONNECTING", "FAILED"].contains(&self.phase.as_str()) {
            return;
        }
        if let Some(report) = line.split("WATCH_DISCOVERED_V1:").nth(1) {
            let fields: Vec<_> = report.split(':').collect();
            if self.phase == "DISCOVERING"
                && fields.len() == 4
                && token(fields[0])
                && fields[1].starts_with("Watch")
                && fields[1].len() < 32
                && fields[2].len() < 32
                && let Ok(rssi) = fields[3].parse::<i16>()
            {
                let value = json!({"id":fields[0],"name":fields[1],"productType":fields[1],"watchOs":fields[2],"rssi":rssi});
                if let Some(device) = self.devices.iter_mut().find(|d| d.value["id"] == fields[0]) {
                    device.value = value;
                    device.seen = Instant::now();
                } else if self.devices.len() < 64 {
                    self.devices.push(Device {
                        value,
                        seen: Instant::now(),
                    });
                }
            }
        }
        if let Some(pair) = line.strip_prefix("LINUX_PAIR_ID_V1:").filter(|p| token(p)) {
            self.pair_id = Some(pair.into());
        }
        if line == "LINUX_RECORD_STATE_V1:ACTIVATION_CONFIRMED" {
            self.activated = true;
        }
        if let Some(phase) = line.strip_prefix("WATCH_SETUP_PHASE_V1:")
            && [
                "CONFIGURING",
                "ACTIVATING",
                "ACTIVATION_INPUT",
                "ACTIVATED",
                "SYNCING",
                "WAITING_FOR_WATCH",
                "VERIFYING_RECONNECT",
                "FAILED",
            ]
            .contains(&phase)
        {
            if phase == "FAILED" {
                self.fail("Watch setup failed");
            } else {
                self.phase = phase.into();
            }
            if [
                "ACTIVATED",
                "SYNCING",
                "WAITING_FOR_WATCH",
                "VERIFYING_RECONNECT",
            ]
            .contains(&phase)
            {
                self.activated = true;
            }
        }
        if line.contains("HAL INITIALIZED: exclusive controller ownership confirmed.") {
            self.adapter = "POWERED_ON".into();
        }
        if line.contains("LE ACL CONNECTED:") {
            self.ready = false;
            self.ids_ready = false;
            self.phase = "PAIRING_SECURITY".into();
        }
        if line == "BRIDGE_TRANSPORT_V1:DOWN" {
            self.ready = false;
            self.ids_ready = false;
            if self.phase != "DISCONNECTING" {
                self.phase = "RECONNECTING".into();
            }
        }
        if line.contains("PIN_REQUIRED: enter the six-digit code") {
            self.phase = "PIN_REQUIRED".into();
        }
        if line.contains("PBBRIDGE ACTIVATION FAILED RX:") {
            self.phase = "ACTIVATION_REJECTED".into();
            self.error =
                Some("Watch rejected the activation response; see protocol diagnostics".into());
        }
        if line.contains("OPERATIONAL IDS READY: same activated pair;") {
            self.ids_ready = true;
            self.activated = true;
            self.phase = "IDS".into();
            if self.verified && self.pair_id.is_some() {
                self.ready = true;
                self.phase = "OPERATIONAL".into();
            }
        }
        // A transport ACK or an activation response never satisfies this barrier.
        if line == "EVENT:SETUP_COMPLETE:operational" && self.pair_id.is_some() {
            self.ids_ready = true;
            self.ready = true;
            self.verified = true;
            self.activated = true;
            self.phase = "OPERATIONAL".into();
        }
        if line.contains("LINUX SESSION FAILED:")
            || line.contains("WINDOWS SESSION FAILED:")
            || line.contains("WINDOWS HCI FAILED:")
            || ["LINUX_SECRET_STORE_FAILED", "LINUX_OUTPUT_FAILED"].contains(&line)
        {
            self.fail(line.chars().take(256).collect::<String>());
        }
        let packet_noise = [
            "[ERTM",
            "NORMAL RX:",
            "NORMAL ERTM",
            "HCI ACL CREDIT",
            "NORMAL TX:",
            "NORMAL IDS RX",
            "[Lowpan",
            "[NormalLink",
            "[TCP",
            "TCP CHECKSUM:",
            "[LiveIds",
            "[NR RX]",
        ]
        .iter()
        .any(|marker| line.contains(marker));
        if !packet_noise && !line.starts_with("BRIDGE_APPLICATION_V1:") {
            self.journal.push_back(line.chars().take(384).collect());
            while self.journal.len() > 48 {
                self.journal.pop_front();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn native_transport_failure_keeps_its_cause_after_worker_exit() {
        let mut state = State::default();
        state.observe(
            "[WatchHal] [T+123ms] WINDOWS HCI FAILED: USB HCI ACL read endpoint 0x82: IO error",
        );
        state.observe("[WatchHal] WINDOWS SESSION FAILED: Broken pipe");
        assert_eq!(state.phase, "FAILED");
        assert!(state.error.as_deref().unwrap().contains("endpoint 0x82"));
        assert!(!state.ready);
    }
    #[test]
    fn late_events_cannot_revive_stopped_or_failed_sessions() {
        for failure in [false, true] {
            let mut state = State {
                ready: true,
                ids_ready: true,
                verified: true,
                activated: true,
                pair_id: Some("4a8c08cd-7bdb-5718-b9f6-316651d517b5".into()),
                ..State::default()
            };
            if failure {
                state.observe("LINUX_OUTPUT_FAILED");
            } else {
                state.begin_stop();
            }
            let phase = state.phase.clone();
            state.observe("OPERATIONAL IDS READY: same activated pair;");
            state.observe("EVENT:SETUP_COMPLETE:operational");
            state.observe("BRIDGE_TRANSPORT_V1:DOWN");
            assert_eq!(state.phase, phase);
            assert!(!state.ready && !state.ids_ready);
            assert!(state.verified && state.activated);
        }
    }
    #[test]
    fn setup_failure_clears_live_readiness() {
        let mut state = State {
            ready: true,
            ids_ready: true,
            ..State::default()
        };
        state.observe("WATCH_SETUP_PHASE_V1:FAILED");
        assert_eq!(state.phase, "FAILED");
        assert!(!state.ready && !state.ids_ready);
        assert!(state.error.is_some());
    }
    #[test]
    fn stored_pair_is_visible_but_does_not_claim_a_live_connection() {
        let directory =
            std::env::temp_dir().join(format!("watch-public-pair-{}", std::process::id()));
        std::fs::create_dir_all(directory.join("identity")).unwrap();
        std::fs::write(directory.join("identity/pair-public.json"), br#"{"pairId":"4a8c08cd-7bdb-5718-b9f6-316651d517b5","productType":"Watch7,5","activationConfirmed":true,"protocolVerified":false}"#).unwrap();
        let mut state = State::default();
        state.restore_public_pair(&directory);
        assert!(state.pair_id.is_some());
        assert!(state.activated);
        assert!(!state.verified);
        assert!(!state.ready);
        std::fs::remove_dir_all(directory).unwrap();
    }
    #[test]
    fn packet_noise_does_not_erase_activation_rejection() {
        let mut state = State::default();
        state.observe("PBBRIDGE ACTIVATION FAILED RX: Invalid activation signature");
        for _ in 0..100 {
            state.observe("[ERTM TX] RR: reqSeq=11");
        }
        assert_eq!(state.phase, "ACTIVATION_REJECTED");
        assert!(state.error.is_some());
        assert_eq!(state.journal.len(), 1);
        assert!(!state.ready);
    }
    #[test]
    fn operational_link_loss_clears_readiness_but_keeps_the_confirmed_pair() {
        let mut state = State {
            verified: true,
            activated: true,
            pair_id: Some("4a8c08cd-7bdb-5718-b9f6-316651d517b5".into()),
            ..State::default()
        };
        state.observe(
            "OPERATIONAL IDS READY: same activated pair; Setup/Albert/Buddy replay disabled.",
        );
        assert!(state.ready);
        state.observe("BRIDGE_TRANSPORT_V1:DOWN");
        assert!(!state.ready);
        assert!(state.verified);
        assert!(state.pair_id.is_some());
        assert_eq!(state.phase, "RECONNECTING");
    }
    #[test]
    fn sent_sync_and_activation_never_create_a_ready_watch() {
        let mut state = State::default();
        state.observe("WATCH_SETUP_PHASE_V1:ACTIVATED");
        state.observe("WATCH_SETUP_PHASE_V1:WAITING_FOR_WATCH");
        state.observe("EVENT:SETUP_COMPLETE:operational");
        assert!(state.activated);
        assert!(!state.ready);
        state.observe("LINUX_PAIR_ID_V1:4a8c08cd-7bdb-5718-b9f6-316651d517b5");
        state.observe("EVENT:SETUP_COMPLETE:operational");
        assert!(state.ready);
    }
    #[test]
    fn stale_discovery_and_private_envelopes_are_rejected() {
        let mut state = State::default();
        state.observe("WATCH_DISCOVERED_V1:4a8c08cd-7bdb-5718-b9f6-316651d517b5:Watch7,5:26.2:-40");
        assert!(state.devices.is_empty());
        state.phase = "DISCOVERING".into();
        state.observe("WATCH_DISCOVERED_V1:4a8c08cd-7bdb-5718-b9f6-316651d517b5:Watch7,5:26.2:-40");
        assert_eq!(state.devices.len(), 1);
        state.observe("BOND_SECRET_V1:private");
        assert_eq!(state.phase, "FAILED");
        assert!(!state.journal.iter().any(|line| line.contains("private")));
    }
}
