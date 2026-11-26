//! Observed Linux session facts; no child process or controller ownership.
use serde_json::{Value, json};
use std::{collections::VecDeque, time::Instant};

pub(crate) struct Device {
    pub(crate) value: Value,
    pub(crate) seen: Instant,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ActivationChallenge {
    pub(crate) id: i32,
    pub(crate) title: String,
    pub(crate) message: String,
    pub(crate) fields: Vec<String>,
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
    pub(crate) transport_epoch: Option<String>,
    pub(crate) update: Option<watch_core::update::Observation>,
    pub(crate) about: Option<serde_json::Map<String, Value>>,
    pub(crate) watch_settings: serde_json::Map<String, Value>,
    pub(crate) activation_challenge: Option<ActivationChallenge>,
    pub(crate) pending_initial_about_epoch: Option<String>,
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
            transport_epoch: None,
            update: None,
            about: None,
            watch_settings: serde_json::Map::new(),
            activation_challenge: None,
            pending_initial_about_epoch: None,
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
        self.about = None;
        self.watch_settings.clear();
        self.activation_challenge = None;
        self.pending_initial_about_epoch = None;
    }

    pub(crate) fn begin_stop(&mut self) {
        self.phase = "DISCONNECTING".into();
        self.ready = false;
        self.ids_ready = false;
        self.about = None;
        self.watch_settings.clear();
        self.activation_challenge = None;
        self.pending_initial_about_epoch = None;
    }

    fn push_journal(&mut self, line: &str) {
        self.journal.push_back(line.chars().take(384).collect());
        while self.journal.len() > 48 {
            self.journal.pop_front();
        }
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
        if let Some(epoch) = line
            .strip_prefix("BRIDGE_TRANSPORT_V1:READY:")
            .filter(|s| token(s))
        {
            self.transport_epoch = Some(epoch.to_owned());
            if self.ready && self.about.is_none() {
                self.pending_initial_about_epoch = Some(epoch.to_owned());
            }
        }
        if let Some(rest) = line.strip_prefix("ACTIVATION-CHALLENGE-V1:") {
            let parts: Vec<&str> = rest.split(':').collect();
            if parts.len() >= 4
                && let Some(id) = parts[0].parse::<i32>().ok().filter(|id| *id >= 0)
            {
                let fields: Vec<String> = parts[3]
                    .split(',')
                    .filter(|f| {
                        !f.is_empty()
                            && f.len() <= 64
                            && f.bytes()
                                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
                    })
                    .map(str::to_owned)
                    .collect();
                if !fields.is_empty() && fields.len() <= 8 && fields.len() == parts[3].split(',').count() {
                    let retry = parts.get(4) == Some(&"retry");
                    self.activation_challenge = Some(ActivationChallenge {
                        id,
                        title: "Apple Watch activation".into(),
                        message: if retry {
                            "Apple rejected the previous attempt. Check your credentials.".into()
                        } else {
                            "Enter the owner credentials requested by Apple.".into()
                        },
                        fields,
                    });
                    self.phase = "ACTIVATION_INPUT".into();
                    self.push_journal(
                        "Activation service requested owner credentials; values logged=false.",
                    );
                }
            }
            return;
        }
        if let Some(rest) = line.strip_prefix("ACTIVATION-ALERT-V1:") {
            let parts: Vec<&str> = rest.split(':').collect();
            if parts.len() >= 3
                && let Some(id) = parts[0].parse::<i32>().ok().filter(|id| *id >= 0)
                && let Some(title_bytes) = crate::b64::decode(parts[1]).filter(|b| b.len() <= 4096)
                && let Some(msg_bytes) = crate::b64::decode(parts[2]).filter(|b| b.len() <= 4096)
                && let (Ok(title), Ok(message)) = (
                    String::from_utf8(title_bytes),
                    String::from_utf8(msg_bytes),
                )
            {
                self.activation_challenge = Some(ActivationChallenge {
                    id,
                    title,
                    message,
                    fields: Vec::new(),
                });
                self.phase = "ACTIVATION_INPUT".into();
                self.push_journal(
                    "Activation service requires owner action; private message logged=false.",
                );
            }
            return;
        }
        if let Some(json) = line.strip_prefix("DESKTOP_ABOUT_V1:") {
            if json.len() <= 4096
                && self.ready
                && let Ok(Value::Object(map)) = serde_json::from_str::<Value>(json)
                && let Some(observed_at) = map
                    .get("observedAt")
                    .and_then(Value::as_i64)
                    .filter(|&v| v > 0 && v <= 8_640_000_000_000_000)
            {
                let mut filtered = serde_json::Map::new();
                filtered.insert("observedAt".into(), json!(observed_at));
                if let Some(level) = map
                    .get("batteryLevel")
                    .and_then(Value::as_i64)
                    .filter(|&v| (0..=100).contains(&v))
                {
                    filtered.insert("batteryLevel".into(), json!(level));
                }
                if let Some(charging) = map.get("isCharging").and_then(Value::as_bool) {
                    filtered.insert("isCharging".into(), json!(charging));
                }
                if let Some(charging_observed) =
                    map.get("chargingObserved").and_then(Value::as_bool)
                {
                    filtered.insert("chargingObserved".into(), json!(charging_observed));
                }
                for key in [
                    "availableStorageBytes",
                    "numberOfApps",
                    "numberOfSongs",
                    "numberOfPhotos",
                    "purgeableSpaceBytes",
                    "userDeletableSpaceBytes",
                ] {
                    if let Some(val) = map.get(key).and_then(Value::as_i64).filter(|&v| v >= 0) {
                        filtered.insert(key.into(), json!(val));
                    }
                }
                self.about = Some(filtered);
            }
            return;
        }
        if let Some(json) = line.strip_prefix("DESKTOP_WATCH_SETTINGS_V1:") {
            if json.len() <= 4096
                && self.ready
                && let Ok(Value::Object(map)) = serde_json::from_str::<Value>(json)
            {
                let mut filtered = serde_json::Map::new();
                for key in ["RIGHT_WRIST", "INVERT_SCREEN", "TIME_24_HOUR"] {
                    if let Some(entry) = map.get(key).and_then(Value::as_object)
                        && let Some(val) = entry.get("value").and_then(Value::as_bool)
                        && let Some(observed_at) = entry
                            .get("observedAt")
                            .and_then(Value::as_i64)
                            .filter(|&t| t > 0 && t <= 8_640_000_000_000_000)
                    {
                        filtered.insert(
                            key.into(),
                            json!({"value": val, "observedAt": observed_at}),
                        );
                    }
                }
                self.watch_settings = filtered;
            }
            return;
        }
        if let Some(json) = line.strip_prefix("WATCH_UPDATE_V1:") {
            if json.len() <= 8192
                && self.ready
                && let Ok(observation) =
                    serde_json::from_str::<watch_core::update::Observation>(json)
                && observation.valid()
                && self.pair_id.as_deref() == Some(observation.pair_id.as_str())
                && self.transport_epoch.as_deref() == Some(observation.epoch.as_str())
            {
                self.update = Some(observation);
            }
            return; // Public snapshot only; never copy an archive into the journal.
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
                self.activation_challenge = None;
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
            self.transport_epoch = None;
            self.update = None;
            self.about = None;
            self.watch_settings.clear();
            self.activation_challenge = None;
            self.pending_initial_about_epoch = None;
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
            self.activation_challenge = None;
            self.phase = "IDS".into();
            if self.verified && self.pair_id.is_some() {
                self.ready = true;
                self.phase = "OPERATIONAL".into();
                if self.about.is_none()
                    && let Some(epoch) = &self.transport_epoch
                {
                    self.pending_initial_about_epoch = Some(epoch.clone());
                }
            }
        }
        // A transport ACK or an activation response never satisfies this barrier.
        if line == "EVENT:SETUP_COMPLETE:operational" && self.pair_id.is_some() {
            self.ids_ready = true;
            self.ready = true;
            self.verified = true;
            self.activated = true;
            self.activation_challenge = None;
            self.phase = "OPERATIONAL".into();
            if self.about.is_none()
                && let Some(epoch) = &self.transport_epoch
            {
                self.pending_initial_about_epoch = Some(epoch.clone());
            }
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
            self.push_journal(line);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ota_observations_are_owned_by_both_pair_and_transport_epoch() {
        let pair = "11111111-1111-4111-8111-111111111111";
        let epoch = "22222222-2222-4222-8222-222222222222";
        let mut state = State {
            ready: true,
            verified: true,
            activated: true,
            pair_id: Some(pair.into()),
            ..State::default()
        };
        state.observe(&format!("BRIDGE_TRANSPORT_V1:READY:{epoch}"));
        let observation = json!({"pairId":pair,"epoch":epoch,"known":true,"phase":"AVAILABLE",
            "observedAt":100000,"scanCompleted":true,"installAttempted":false,
            "installedVersion":"26.2","installedBuild":"23S303","targetVersion":"27.0.1",
            "targetBuild":"24R365","downloadBytes":null,"requiredSpaceBytes":null,"progress":null,
            "stalled":false,"termsAccepted":true,"errorCode":null});
        let publish = |value: &Value| format!("WATCH_UPDATE_V1:{value}");
        let before = state.journal.len();
        state.observe(&publish(&observation));
        assert_eq!(
            state.update.as_ref().unwrap().phase,
            watch_core::update::Phase::Available
        );
        assert_eq!(state.journal.len(), before);
        for field in ["pairId", "epoch"] {
            let mut foreign = observation.clone();
            foreign[field] = json!("33333333-3333-4333-8333-333333333333");
            foreign["targetBuild"] = json!("24R366");
            state.observe(&publish(&foreign));
            assert_eq!(
                state.update.as_ref().unwrap().target_build.as_deref(),
                Some("24R365")
            );
        }
        state.observe("BRIDGE_TRANSPORT_V1:DOWN");
        assert!(state.update.is_none());
        state.observe(&publish(&observation));
        assert!(state.update.is_none());
    }
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

    #[test]
    fn base64_codec_roundtrips_and_rejects_malformed_input() {
        for sample in [b"".as_slice(), b"a", b"ab", b"abc", b"apple@icloud.com", b"\x00\xff\x7f"] {
            let encoded = crate::b64::encode(sample);
            assert_eq!(crate::b64::decode(&encoded).as_deref(), Some(sample));
        }
        for bad in ["abc", "ab=c", "====", "YWJj====", "Zm9*", "Z==="] {
            assert!(crate::b64::decode(bad).is_none(), "{bad}");
        }
    }

    #[test]
    fn activation_challenge_and_alert_update_phase_without_leaking_secrets() {
        let mut state = State::default();
        let account_b64 = crate::b64::encode(b"owner@example.com");
        state.observe(&format!(
            "ACTIVATION-CHALLENGE-V1:7:FMIPLock:{account_b64}:login,password:retry"
        ));
        assert_eq!(state.phase, "ACTIVATION_INPUT");
        let ch = state.activation_challenge.as_ref().unwrap();
        assert_eq!(ch.id, 7);
        assert_eq!(ch.fields, vec!["login", "password"]);
        assert!(ch.message.contains("rejected the previous attempt"));
        assert!(!state.journal.iter().any(|l| l.contains(&account_b64)));

        let title_b64 = crate::b64::encode("Activation Alert".as_bytes());
        let msg_b64 = crate::b64::encode("Unlock on iCloud".as_bytes());
        state.observe(&format!("ACTIVATION-ALERT-V1:9:{title_b64}:{msg_b64}"));
        let alert = state.activation_challenge.as_ref().unwrap();
        assert_eq!(alert.id, 9);
        assert_eq!(alert.title, "Activation Alert");
        assert_eq!(alert.message, "Unlock on iCloud");
        assert!(alert.fields.is_empty());

        state.observe("WATCH_SETUP_PHASE_V1:ACTIVATED");
        assert!(state.activation_challenge.is_none());
    }

    #[test]
    fn desktop_about_and_settings_require_ready_and_clear_on_link_loss() {
        let mut state = State::default();
        let about_line = r#"DESKTOP_ABOUT_V1:{"observedAt":1750000000123,"batteryLevel":84,"isCharging":true,"chargingObserved":true,"availableStorageBytes":123456,"numberOfApps":5,"numberOfSongs":2,"numberOfPhotos":10,"purgeableSpaceBytes":100,"userDeletableSpaceBytes":50}"#;
        let settings_line = r#"DESKTOP_WATCH_SETTINGS_V1:{"RIGHT_WRIST":{"value":true,"observedAt":1750000000500}}"#;
        state.observe(about_line);
        state.observe(settings_line);
        assert!(state.about.is_none() && state.watch_settings.is_empty());

        state.ready = true;
        state.observe(about_line);
        state.observe(settings_line);
        assert_eq!(
            state.about.as_ref().unwrap().get("batteryLevel").and_then(Value::as_i64),
            Some(84)
        );
        assert_eq!(
            state.watch_settings.get("RIGHT_WRIST").and_then(|v| v.get("value")).and_then(Value::as_bool),
            Some(true)
        );

        state.observe("BRIDGE_TRANSPORT_V1:DOWN");
        assert!(state.about.is_none());
        assert!(state.watch_settings.is_empty());
    }
}
