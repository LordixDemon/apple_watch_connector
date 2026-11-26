//! Linux command facade and ownership of the portable protocol worker.
use crate::state::{State, token};
use serde_json::{Value, json};
use std::{
    io::Write,
    path::PathBuf,
    process::{Child, ChildStdin, Command, Stdio},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

pub struct LinuxBackend {
    state: Arc<Mutex<State>>,
    child: Option<Child>,
    input: Option<ChildStdin>,
    protocol: PathBuf,
    helper: PathBuf,
    state_dir: PathBuf,
    resume_after_stop: bool,
    stop_deadline: Option<Instant>,
    adapters: crate::adapters::AdapterRegistry,
}
impl Default for LinuxBackend {
    fn default() -> Self {
        Self::new()
    }
}
impl LinuxBackend {
    pub fn new() -> Self {
        let executable = std::env::current_exe().unwrap_or_default();
        let bundle = executable.parent().unwrap_or(std::path::Path::new("."));
        #[cfg(not(target_os = "windows"))]
        let protocol = std::env::var_os("WATCH_LINUX_PROTOCOL_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| bundle.join("lib/protocol"));
        #[cfg(not(target_os = "windows"))]
        let helper = std::env::var_os("WATCH_LINUX_HCI_HELPER")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("/usr/local/libexec/watch-companion/watch-linux-hci"));
        #[cfg(not(target_os = "windows"))]
        let state_dir = std::env::var_os("XDG_STATE_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".local/state")
            })
            .join("watch-companion");
        #[cfg(target_os = "windows")]
        let protocol = std::env::var_os("WATCH_WINDOWS_PROTOCOL_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| bundle.join("protocol"));
        #[cfg(target_os = "windows")]
        let helper = std::env::var_os("WATCH_WINDOWS_HCI_HELPER")
            .map(PathBuf::from)
            .unwrap_or_else(|| bundle.join("watch-windows-hci.exe"));
        #[cfg(target_os = "windows")]
        let state_dir = std::env::var_os("WATCH_WINDOWS_STATE_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                PathBuf::from(std::env::var_os("LOCALAPPDATA").unwrap_or_default())
                    .join("watch-companion")
            });
        let mut state = State::default();
        state.restore_public_pair(&state_dir);
        Self {
            state: Arc::new(Mutex::new(state)),
            child: None,
            input: None,
            protocol,
            helper,
            adapters: crate::adapters::AdapterRegistry::new(state_dir.clone()),
            state_dir,
            resume_after_stop: false,
            stop_deadline: None,
        }
    }
    fn java(&self) -> PathBuf {
        #[cfg(target_os = "windows")]
        {
            std::env::var_os("WATCH_WINDOWS_JAVA")
                .map(PathBuf::from)
                .unwrap_or_else(|| {
                    self.protocol
                        .parent()
                        .unwrap_or(std::path::Path::new("."))
                        .join("jre/bin/java.exe")
                })
        }
        #[cfg(not(target_os = "windows"))]
        {
            PathBuf::from("java")
        }
    }
    fn java_command(&self) -> Command {
        #[allow(unused_mut)]
        let mut command = Command::new(self.java());
        #[cfg(target_os = "windows")]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000);
        }
        command
    }
    fn host_class(&self) -> &'static str {
        if cfg!(target_os = "windows") {
            "dev.applewatchandroid.bridge.WindowsProtocolHost"
        } else {
            "dev.applewatchandroid.bridge.LinuxProtocolHost"
        }
    }
    fn protocol_fingerprint(&self) -> Option<String> {
        let path = self.protocol.join("protocol-manifest.json");
        let metadata = std::fs::symlink_metadata(&path).ok()?;
        if !metadata.is_file() || metadata.len() > 512 * 1024 {
            return None;
        }
        let bytes = std::fs::read(&path).ok()?;
        let value = serde_json::from_slice::<Value>(&bytes).ok()?;
        let artifacts = value.get("artifacts")?.as_object()?;
        let candidates = if cfg!(target_os = "windows") {
            ["watch-windows-protocol.jar", "watch-linux-protocol.jar"]
        } else {
            ["watch-linux-protocol.jar", "watch-windows-protocol.jar"]
        };
        for name in candidates {
            if let Some(digest) = artifacts.get(name).and_then(Value::as_str)
                && digest.len() == 64
                && digest
                    .bytes()
                    .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
            {
                return Some(digest[..12].to_owned());
            }
        }
        None
    }
    fn available(&self) -> bool {
        #[cfg(not(target_os = "windows"))]
        {
            self.helper.is_file() && self.protocol.join("watch-linux-protocol.jar").is_file()
        }
        #[cfg(target_os = "windows")]
        {
            self.helper.is_file()
                && self.protocol.join("watch-windows-protocol.jar").is_file()
                && self.java().is_file()
                && self.adapters.raw_hci_available()
        }
    }
    fn reap(&mut self) {
        if self.child.is_some() && self.state.lock().unwrap().phase == "FAILED" {
            self.input = None;
            self.stop_deadline
                .get_or_insert_with(|| Instant::now() + Duration::from_secs(5));
        }
        let mut resume = false;
        let ended = if let Some(child) = self.child.as_mut() {
            match child.try_wait() {
                Ok(Some(status)) => Some(format!("Protocol worker ended: {status}")),
                Ok(None)
                    if !self
                        .stop_deadline
                        .is_some_and(|deadline| Instant::now() >= deadline) =>
                {
                    None
                }
                result => {
                    let reason = match result {
                        Err(error) => format!("Protocol worker wait failed: {error}"),
                        _ => "Protocol worker stop timed out".into(),
                    };
                    self.input = None;
                    let _ = crate::worker::finish_child(child, Duration::ZERO);
                    Some(reason)
                }
            }
        } else {
            None
        };
        if let Some(reason) = ended {
            self.adapters.invalidate();
            self.child = None;
            self.input = None;
            self.stop_deadline = None;
            let mut state = self.state.lock().unwrap();
            state.ready = false;
            state.ids_ready = false;
            state.about = None;
            state.watch_settings.clear();
            state.activation_challenge = None;
            state.pending_initial_about_epoch = None;
            state.adapter = "UNKNOWN".into();
            if state.phase == "DISCONNECTING" {
                state.phase = "IDLE".into();
                state.error = None;
                state.le_link_summary = None;
                resume = self.resume_after_stop;
                self.resume_after_stop = false;
            } else if state.phase != "FAILED" {
                state.fail(reason);
            }
            if !resume {
                self.resume_after_stop = false;
            }
        }
        if resume {
            self.start(true);
        }
    }
    fn confirm_setup(&mut self) -> Value {
        let pair = {
            let state = self.state.lock().unwrap();
            if !state.activated || state.ready {
                return json!({"status":"NO_PENDING_SETUP"});
            }
            let Some(pair) = state.pair_id.clone() else {
                return json!({"status":"NO_PAIR"});
            };
            pair
        };
        let child = self
            .java_command()
            .arg(format!("-Dwatch.files={}", self.state_dir.display()))
            .arg("-cp")
            .arg(self.protocol.join("*"))
            .arg(self.host_class())
            .arg(&self.helper)
            .arg("confirm")
            .arg(pair)
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn();
        let Ok(mut child) = child else {
            return json!({"status":"UNAVAILABLE"});
        };
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            match child.try_wait() {
                Ok(Some(status)) if status.success() => break,
                Ok(Some(_)) => return json!({"status":"REJECTED"}),
                Err(_) => {
                    let _ = crate::worker::finish_child(&mut child, Duration::ZERO);
                    return json!({"status":"UNAVAILABLE"});
                }
                Ok(None) if Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(20))
                }
                _ => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return json!({"status":"UNAVAILABLE"});
                }
            }
        }
        if self.child.is_none() {
            return self.start(true);
        }
        let result = self.send("STOP");
        if result["status"] == "QUEUED" {
            self.resume_after_stop = true;
            self.begin_stop();
        }
        result
    }
    fn start(&mut self, resume: bool) -> Value {
        self.reap();
        if self.child.is_some() {
            return json!({"status":"BUSY"});
        }
        if !self.available() {
            return json!({"status":"UNAVAILABLE","reason":"Raw HCI helper, protocol runtime or controller driver is missing"});
        }
        let adapter = match self.adapters.resolve() {
            Ok(adapter) => adapter,
            Err(status) => return json!({"status":status}),
        };
        self.adapters.freeze();
        let epoch;
        {
            let mut state = self.state.lock().unwrap();
            epoch = state.epoch + 1;
            *state = State {
                epoch,
                phase: if resume { "CONNECTING" } else { "DISCOVERING" }.into(),
                ..State::default()
            };
            if resume {
                state.restore_public_pair(&self.state_dir);
            }
        }
        let result = self
            .java_command()
            .arg(format!("-Dwatch.files={}", self.state_dir.display()))
            .arg(format!(
                "-Dwatch.tmp={}",
                self.state_dir.join("tmp").display()
            ))
            .arg(format!("-Dwatch.hci={}", adapter.index))
            .arg(format!("-Dwatch.adapter={}", adapter.id))
            .args(["-cp"])
            .arg(self.protocol.join("*"))
            .arg(self.host_class())
            .arg(&self.helper)
            .arg(if resume { "resume" } else { "pair" })
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn();
        match result {
            Ok(mut child) => {
                self.input = child.stdin.take();
                let output = child.stdout.take().expect("piped stdout");
                let state = Arc::clone(&self.state);
                std::thread::spawn(move || crate::worker::read_events(output, state, epoch));
                self.child = Some(child);
                json!({"status":"QUEUED"})
            }
            Err(error) => {
                let mut state = self.state.lock().unwrap();
                state.phase = "FAILED".into();
                state.error = Some(error.to_string());
                json!({"status":"UNAVAILABLE"})
            }
        }
    }
    fn send(&mut self, line: &str) -> Value {
        match self.input.as_mut().map(|input| {
            input
                .write_all(line.as_bytes())
                .and_then(|()| input.write_all(b"\n"))
                .and_then(|()| input.flush())
        }) {
            Some(Ok(())) => json!({"status":"QUEUED"}),
            _ => {
                if self.child.is_some() {
                    self.state
                        .lock()
                        .unwrap()
                        .fail("Protocol command input is unavailable");
                    self.input = None;
                }
                json!({"status":"UNAVAILABLE"})
            }
        }
    }
    fn begin_stop(&mut self) {
        self.state.lock().unwrap().begin_stop();
        self.input = None;
        self.stop_deadline = Some(Instant::now() + Duration::from_secs(5));
    }
    fn maybe_request_initial_about(&mut self) {
        if self.child.is_none() {
            return;
        }
        let epoch = {
            let mut state = self.state.lock().unwrap();
            if !state.ready {
                return;
            }
            let pending = state.pending_initial_about_epoch.take();
            match (pending, state.transport_epoch.as_deref()) {
                (Some(epoch), Some(current)) if epoch == current => epoch,
                _ => return,
            }
        };
        let _ = self.send(&format!("DESKTOP_COMMAND_V1:{epoch}:REQUEST_DEVICE_ABOUT"));
    }
    fn snapshot(&mut self) -> Value {
        self.reap();
        self.maybe_request_initial_about();
        let adapters = self.adapters.snapshot(self.child.is_some());
        let available = self.available();
        let protocol_fingerprint = self.protocol_fingerprint();
        let mut state = self.state.lock().unwrap();
        let selected = state.selected.clone();
        state.devices.retain(|d| {
            d.value["id"].as_str() == selected.as_deref()
                || d.seen.elapsed() < Duration::from_secs(20)
        });
        let product = state
            .devices
            .iter()
            .find(|d| d.value["id"].as_str() == selected.as_deref())
            .map(|d| d.value["productType"].clone())
            .or_else(|| state.product.as_ref().map(|p| json!(p)));
        let mut snapshot = json!({"apiVersion":1,"coreVersion":env!("CARGO_PKG_VERSION"),"coreGitRev":env!("WATCH_GIT_REV"),"protocolFingerprint":protocol_fingerprint,"leLinkSummary":state.le_link_summary,"epoch":state.epoch,
            "logPath":self.state_dir.join("protocol.log").to_string_lossy(),"phase":state.phase,"adapterState":state.adapter,"selectedId":state.selected,
            "devices":state.devices.iter().map(|d|d.value.clone()).collect::<Vec<_>>(),
            "pairId":state.pair_id,"productType":product,"activationConfirmed":state.activated,
            "softwareUpdate":state.update,
            "watchReady":state.ready,"operationalEligible":state.verified,"pinRequired":state.phase=="PIN_REQUIRED",
            "pairingStreamOpen":self.child.is_some() && !["IDLE","DISCOVERING","CONNECTING","DISCONNECTING","FAILED"].contains(&state.phase.as_str()),
            "pairingStage":state.phase,"error":state.error,"journal":state.journal.iter().cloned().collect::<Vec<_>>().join("\n"),
            "capabilities":{"platform":if cfg!(target_os="windows") {"windows"} else {"linux"},"backend":if cfg!(target_os="windows") {"usb_hci_shared_protocol"} else {"hci_user_channel_shared_protocol"},
                "adapterSelection":true,"discovery":available,"bluetoothLink":available,"fixedL2cap":available,"pairing":available,
                "features":{"softwareUpdates":available,"telemetry":available,"watchSettings":available},
                "operationalIds":available,"ownerConfirmation":true,"wifi":false,"pairingUnavailableReason":if available {None} else {Some(if cfg!(target_os="windows") {"WINDOWS_RAW_HCI_REQUIRED"} else {"LINUX_BACKEND_NOT_INSTALLED"})}}});
        if let Some(obj) = snapshot.as_object_mut() {
            if let Some(ch) = &state.activation_challenge {
                obj.insert("activationChallengeId".into(), json!(ch.id));
                obj.insert("activationTitle".into(), json!(ch.title));
                obj.insert("activationMessage".into(), json!(ch.message));
                obj.insert("activationFields".into(), json!(ch.fields));
            }
            if state.ready {
                if let Some(about) = &state.about {
                    if let Some(observed_at) = about.get("observedAt") {
                        obj.insert("aboutObservedAt".into(), observed_at.clone());
                    }
                    for key in [
                        "batteryLevel",
                        "isCharging",
                        "chargingObserved",
                        "availableStorageBytes",
                        "numberOfApps",
                        "numberOfSongs",
                        "numberOfPhotos",
                        "purgeableSpaceBytes",
                        "userDeletableSpaceBytes",
                    ] {
                        if let Some(value) = about.get(key) {
                            obj.insert(key.into(), value.clone());
                        }
                    }
                }
                for (name, entry) in &state.watch_settings {
                    if let Some(val) = entry.get("value") {
                        obj.insert(format!("watchSetting_{name}"), val.clone());
                    }
                    if let Some(observed_at) = entry.get("observedAt") {
                        obj.insert(
                            format!("watchSetting_{name}_observedAt"),
                            observed_at.clone(),
                        );
                    }
                }
            }
        }
        snapshot
            .as_object_mut()
            .unwrap()
            .extend(adapters.as_object().unwrap().clone());
        snapshot
    }
    pub fn command(&mut self, request: Value) -> Value {
        self.reap();
        match request["method"].as_str() {
            Some("snapshot") => self.snapshot(),
            Some("scan" | "pair") => self.start(false),
            Some("resume") => self.start(true),
            Some("confirmSetup") => self.confirm_setup(),
            Some(
                method @ ("querySoftwareUpdate"
                | "checkSoftwareUpdate"
                | "downloadSoftwareUpdate"
                | "installSoftwareUpdate"),
            ) => {
                let state = self.state.lock().unwrap();
                let Some(epoch) = state.transport_epoch.as_deref() else {
                    return json!({"status":"UNAVAILABLE"});
                };
                if !state.ready
                    || state.pair_id.as_deref() != request["pairId"].as_str()
                    || Some(epoch) != request["epoch"].as_str()
                {
                    return json!({"status":"UNAVAILABLE"});
                }
                let command = match method {
                    "querySoftwareUpdate" => "UPDATE_QUERY".to_owned(),
                    "checkSoftwareUpdate" => "UPDATE_SCAN".to_owned(),
                    _ => {
                        let Some(build) = request["build"].as_str().filter(|s| {
                            !s.is_empty()
                                && s.len() <= 32
                                && s.bytes().all(|b| b.is_ascii_alphanumeric())
                        }) else {
                            return json!({"status":"REJECTED"});
                        };
                        if state
                            .update
                            .as_ref()
                            .and_then(|u| u.target_build.as_deref())
                            != Some(build)
                        {
                            return json!({"status":"REJECTED"});
                        }
                        format!(
                            "{}:{build}",
                            if method == "downloadSoftwareUpdate" {
                                "UPDATE_DOWNLOAD"
                            } else {
                                "UPDATE_INSTALL"
                            }
                        )
                    }
                };
                let envelope = format!("WATCH_UPDATE_COMMAND_V1:{epoch}:{command}");
                drop(state);
                self.send(&envelope)
            }
            Some("refreshAdapters") => {
                if self.child.is_some() {
                    return json!({"status":"BUSY"});
                }
                self.adapters.refresh(true, false);
                json!({"status":"QUEUED"})
            }
            Some("selectAdapter") => {
                if self.child.is_some() {
                    return json!({"status":"BUSY"});
                }
                let Some(id) = request["id"].as_str() else {
                    return json!({"status":"INVALID_REQUEST"});
                };
                match self.adapters.select(id) {
                    Ok(()) => json!({"status":"APPLIED"}),
                    Err(status) => json!({"status":status}),
                }
            }
            Some("connect") => {
                let Some(id) = request["id"].as_str().filter(|id| token(id)) else {
                    return json!({"status":"INVALID_REQUEST"});
                };
                {
                    let mut state = self.state.lock().unwrap();
                    if state.phase != "DISCOVERING"
                        || !state.devices.iter().any(|d| {
                            d.value["id"] == id && d.seen.elapsed() < Duration::from_secs(20)
                        })
                    {
                        return json!({"status":"UNKNOWN_DEVICE"});
                    }
                    state.selected = Some(id.into());
                    state.phase = "CONNECTING".into();
                }
                self.send(&format!("SELECT_DISCOVERED_WATCH_V1:{id}"))
            }
            Some("submitPin") => {
                let Some(pin) = request["pin"]
                    .as_str()
                    .filter(|p| p.len() == 6 && p.bytes().all(|b| b.is_ascii_digit()))
                else {
                    return json!({"status":"INVALID_REQUEST"});
                };
                if self.state.lock().unwrap().phase != "PIN_REQUIRED" {
                    return json!({"status":"NO_PIN_CHALLENGE"});
                }
                let result = self.send(&format!("PIN:{pin}"));
                if result["status"] == "QUEUED" {
                    self.state.lock().unwrap().phase = "PIN_AUTHENTICATION".into();
                }
                result
            }
            Some("refreshWatchState") => {
                let state = self.state.lock().unwrap();
                let Some(epoch) = state.transport_epoch.as_deref() else {
                    return json!({"status":"UNAVAILABLE"});
                };
                if !state.ready {
                    return json!({"status":"UNAVAILABLE"});
                }
                let envelope = format!("DESKTOP_COMMAND_V1:{epoch}:REQUEST_DEVICE_ABOUT");
                drop(state);
                self.send(&envelope)
            }
            Some("pingWatch") => {
                let state = self.state.lock().unwrap();
                let Some(epoch) = state.transport_epoch.as_deref() else {
                    return json!({"status":"UNAVAILABLE"});
                };
                if !state.ready {
                    return json!({"status":"UNAVAILABLE"});
                }
                let envelope = format!("DESKTOP_COMMAND_V1:{epoch}:PING_WATCH");
                drop(state);
                self.send(&envelope)
            }
            Some("setWatchSetting") => {
                let Some(setting) = request["setting"]
                    .as_str()
                    .filter(|s| ["RIGHT_WRIST", "INVERT_SCREEN", "TIME_24_HOUR"].contains(s))
                else {
                    return json!({"status":"REJECTED"});
                };
                let Some(value) = request["value"].as_bool() else {
                    return json!({"status":"REJECTED"});
                };
                let state = self.state.lock().unwrap();
                let Some(epoch) = state.transport_epoch.as_deref() else {
                    return json!({"status":"UNAVAILABLE"});
                };
                if !state.ready {
                    return json!({"status":"UNAVAILABLE"});
                }
                let envelope =
                    format!("DESKTOP_COMMAND_V1:{epoch}:SET_WATCH_SETTING:{setting}:{value}");
                drop(state);
                self.send(&envelope)
            }
            Some("activationResponse") => {
                let envelope = {
                    let state = self.state.lock().unwrap();
                    let Some(ch) = state.activation_challenge.as_ref() else {
                        return json!({"status":"REJECTED"});
                    };
                    if request["challengeId"].as_i64() != Some(i64::from(ch.id)) {
                        return json!({"status":"REJECTED"});
                    }
                    match request["action"].as_str() {
                        Some("cancel") => format!("ACTIVATION-CANCEL-V1:{}", ch.id),
                        Some("retry") => format!("ACTIVATION-RETRY-V1:{}", ch.id),
                        Some("submit") => {
                            let Some(credentials) = request["credentials"].as_object() else {
                                return json!({"status":"REJECTED"});
                            };
                            if ch.fields.is_empty() {
                                return json!({"status":"REJECTED"});
                            }
                            let mut line = format!("ACTIVATION-CREDENTIALS-V1:{}", ch.id);
                            for field in &ch.fields {
                                let Some(val) = credentials
                                    .get(field)
                                    .and_then(Value::as_str)
                                    .filter(|v| !v.is_empty() && v.len() <= 4096)
                                else {
                                    return json!({"status":"REJECTED"});
                                };
                                line.push(':');
                                line.push_str(field);
                                line.push('=');
                                line.push_str(&crate::b64::encode(val.as_bytes()));
                            }
                            line
                        }
                        _ => return json!({"status":"REJECTED"}),
                    }
                };
                let result = self.send(&envelope);
                if result["status"] == "QUEUED" {
                    let mut state = self.state.lock().unwrap();
                    state.activation_challenge = None;
                    state.phase = "ACTIVATING".into();
                }
                result
            }
            Some("retryActivation") => {
                let state = self.state.lock().unwrap();
                if state.pair_id.is_none()
                    || state.activated
                    || !["ACTIVATING", "ACTIVATION_REJECTED"].contains(&state.phase.as_str())
                {
                    return json!({"status":"NO_PENDING_ACTIVATION"});
                }
                drop(state);
                self.send("RETRY_ACTIVATION")
            }
            Some("stop") => {
                self.resume_after_stop = false;
                if self.child.is_none() {
                    return json!({"status":"IDLE"});
                }
                let result = self.send("STOP");
                self.begin_stop();
                result
            }
            _ => json!({"status":"UNSUPPORTED_OPERATION"}),
        }
    }
}
impl Drop for LinuxBackend {
    fn drop(&mut self) {
        let _ = self.send("STOP");
        if self.child.is_some() {
            self.state.lock().unwrap().begin_stop();
        }
        self.input = None;
        if let Some(mut child) = self.child.take() {
            let _ = crate::worker::finish_child(&mut child, Duration::from_secs(2));
        }
    }
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;
    #[test]
    fn ota_commands_require_owned_epoch_even_when_legacy_ready_flag_is_true() {
        let mut backend = LinuxBackend::new();
        {
            let mut state = backend.state.lock().unwrap();
            state.ready = true;
            state.pair_id = Some("11111111-1111-4111-8111-111111111111".into());
        }
        assert_eq!(backend.command(json!({"method":"installSoftwareUpdate", "pairId":"11111111-1111-4111-8111-111111111111"}))["status"], "UNAVAILABLE");
        backend.state.lock().unwrap().transport_epoch =
            Some("22222222-2222-4222-8222-222222222222".into());
        assert_eq!(backend.command(json!({"method":"querySoftwareUpdate", "pairId":"11111111-1111-4111-8111-111111111111",
            "epoch":"33333333-3333-4333-8333-333333333333"}))["status"], "UNAVAILABLE");
    }

    fn with_child(command: &mut Command) -> LinuxBackend {
        let mut backend = LinuxBackend::new();
        let mut child = command.stdin(Stdio::piped()).spawn().unwrap();
        backend.input = child.stdin.take();
        backend.child = Some(child);
        let mut state = backend.state.lock().unwrap();
        state.phase = "OPERATIONAL".into();
        state.ready = true;
        state.ids_ready = true;
        state.pair_id = Some("4a8c08cd-7bdb-5718-b9f6-316651d517b5".into());
        drop(state);
        backend
    }

    #[test]
    fn explicit_stop_reaps_worker_and_preserves_pair() {
        let mut backend = with_child(
            Command::new("/bin/sh").args(["-c", "read -r value; test \"$value\" = STOP"]),
        );
        assert_eq!(
            backend.command(json!({"method":"selectAdapter","id":"aa:bb:cc:dd:ee:01"}))["status"],
            "BUSY"
        );
        assert_eq!(
            backend.command(json!({"method":"refreshAdapters"}))["status"],
            "BUSY"
        );
        assert_eq!(
            backend.command(json!({"method":"stop"}))["status"],
            "QUEUED"
        );
        {
            let mut state = backend.state.lock().unwrap();
            state.observe("EVENT:SETUP_COMPLETE:operational");
            assert!(!state.ready && !state.ids_ready);
            assert_eq!(state.phase, "DISCONNECTING");
        }
        let deadline = Instant::now() + Duration::from_secs(1);
        while backend.child.is_some() && Instant::now() < deadline {
            backend.reap();
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(backend.child.is_none());
        let state = backend.state.lock().unwrap();
        assert_eq!(state.phase, "IDLE");
        assert!(state.pair_id.is_some());
    }

    #[test]
    fn expired_stop_reaps_unresponsive_worker() {
        let mut backend = with_child(Command::new("/bin/sleep").arg("30"));
        backend.begin_stop();
        backend.stop_deadline = Some(Instant::now());
        backend.reap();
        assert!(backend.child.is_none() && backend.input.is_none());
        assert_eq!(backend.state.lock().unwrap().phase, "IDLE");
    }

    #[test]
    fn desktop_commands_and_activation_response_encode_valid_stdin_lines() {
        let out_file =
            std::env::temp_dir().join(format!("watch-desktop-cmd-{}.txt", std::process::id()));
        let mut backend = with_child(Command::new("/bin/sh").args([
            "-c",
            &format!("cat > \"{}\"", out_file.display()),
        ]));
        let epoch = "22222222-2222-4222-8222-222222222222";
        {
            let mut state = backend.state.lock().unwrap();
            state.transport_epoch = Some(epoch.into());
            state.pending_initial_about_epoch = Some(epoch.into());
            state.activation_challenge = Some(crate::state::ActivationChallenge {
                id: 11,
                title: "Apple Watch activation".into(),
                message: "Enter credentials".into(),
                fields: vec!["login".into(), "password".into()],
            });
            state.about = Some({
                let mut m = serde_json::Map::new();
                m.insert("observedAt".into(), json!(1750000000123_i64));
                m.insert("batteryLevel".into(), json!(77));
                m
            });
            state.watch_settings.insert(
                "RIGHT_WRIST".into(),
                json!({"value": true, "observedAt": 1750000000200_i64}),
            );
        }

        let snap = backend.command(json!({"method":"snapshot"}));
        assert_eq!(snap["activationChallengeId"], 11);
        assert_eq!(snap["batteryLevel"], 77);
        assert_eq!(snap["aboutObservedAt"], 1750000000123_i64);
        assert_eq!(snap["watchSetting_RIGHT_WRIST"], true);

        assert_eq!(backend.command(json!({"method":"refreshWatchState"}))["status"], "QUEUED");
        assert_eq!(backend.command(json!({"method":"pingWatch"}))["status"], "QUEUED");
        assert_eq!(
            backend.command(json!({"method":"setWatchSetting","setting":"RIGHT_WRIST","value":true}))["status"],
            "QUEUED"
        );
        assert_eq!(
            backend.command(json!({"method":"setWatchSetting","setting":"BAD","value":true}))["status"],
            "REJECTED"
        );
        assert_eq!(
            backend.command(json!({
                "method":"activationResponse",
                "challengeId": 999,
                "action":"cancel"
            }))["status"],
            "REJECTED"
        );
        assert_eq!(
            backend.command(json!({
                "method":"activationResponse",
                "challengeId": 11,
                "action":"submit",
                "credentials":{"login":"user@icloud.com","password":"secret-value"}
            }))["status"],
            "QUEUED"
        );
        assert!(backend.state.lock().unwrap().activation_challenge.is_none());

        backend.input = None;
        let _ = crate::worker::finish_child(&mut backend.child.take().unwrap(), Duration::from_secs(2));
        let recorded = std::fs::read_to_string(&out_file).unwrap();
        let _ = std::fs::remove_file(&out_file);
        assert!(recorded.contains(&format!("DESKTOP_COMMAND_V1:{epoch}:REQUEST_DEVICE_ABOUT")));
        assert!(recorded.contains(&format!("DESKTOP_COMMAND_V1:{epoch}:PING_WATCH")));
        assert!(recorded.contains(&format!(
            "DESKTOP_COMMAND_V1:{epoch}:SET_WATCH_SETTING:RIGHT_WRIST:true"
        )));
        let expected_login = crate::b64::encode(b"user@icloud.com");
        let expected_pw = crate::b64::encode(b"secret-value");
        assert!(recorded.contains(&format!(
            "ACTIVATION-CREDENTIALS-V1:11:login={expected_login}:password={expected_pw}"
        )));
    }

    #[test]
    fn snapshot_reports_build_rev_protocol_fingerprint_and_le_link_summary() {
        let dir = std::env::temp_dir().join(format!(
            "watch-protocol-fp-test-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        let mut backend = LinuxBackend::new();
        backend.protocol = dir.clone();

        let snap_missing = backend.command(json!({"method":"snapshot"}));
        assert!(
            snap_missing["coreGitRev"]
                .as_str()
                .is_some_and(|rev| !rev.is_empty())
        );
        assert!(snap_missing["protocolFingerprint"].is_null());

        let win_sha = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        std::fs::write(
            dir.join("protocol-manifest.json"),
            format!(r#"{{"artifacts":{{"watch-windows-protocol.jar":"{win_sha}"}}}}"#),
        )
        .unwrap();
        let snap_win = backend.command(json!({"method":"snapshot"}));
        assert_eq!(snap_win["protocolFingerprint"], "abcdef012345");

        let linux_sha = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff";
        std::fs::write(
            dir.join("protocol-manifest.json"),
            format!(r#"{{"artifacts":{{"watch-linux-protocol.jar":"{linux_sha}"}}}}"#),
        )
        .unwrap();
        {
            let mut state = backend.state.lock().unwrap();
            state.observe(
                "[WatchHal] LE LINK NEGOTIATION DONE: handle=0x0040 phyObserved=true(status=0x00,tx=2,rx=2) dleObserved=true(tx=251B,rx=251B).",
            );
            state.observe("[WatchHal] WINDOWS SESSION FAILED: SMP Pairing Response timed out");
        }
        let snap_linux = backend.command(json!({"method":"snapshot"}));
        assert_eq!(snap_linux["protocolFingerprint"], "112233445566");
        assert!(
            snap_linux["leLinkSummary"]
                .as_str()
                .is_some_and(|s| s.contains("LE LINK NEGOTIATION DONE:"))
        );

        std::fs::write(
            dir.join("protocol-manifest.json"),
            r#"{"artifacts":{"watch-linux-protocol.jar":"INVALID"}}"#,
        )
        .unwrap();
        assert!(backend.protocol_fingerprint().is_none());

        let _ = std::fs::remove_dir_all(&dir);
    }
}
