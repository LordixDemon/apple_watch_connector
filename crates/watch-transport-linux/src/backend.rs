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
            state.adapter = "UNKNOWN".into();
            if state.phase == "DISCONNECTING" {
                state.phase = "IDLE".into();
                state.error = None;
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
    fn snapshot(&mut self) -> Value {
        self.reap();
        let adapters = self.adapters.snapshot(self.child.is_some());
        let available = self.available();
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
        let mut snapshot = json!({"apiVersion":1,"coreVersion":env!("CARGO_PKG_VERSION"),"epoch":state.epoch,
            "logPath":self.state_dir.join("protocol.log").to_string_lossy(),"phase":state.phase,"adapterState":state.adapter,"selectedId":state.selected,
            "devices":state.devices.iter().map(|d|d.value.clone()).collect::<Vec<_>>(),
            "pairId":state.pair_id,"productType":product,"activationConfirmed":state.activated,
            "watchReady":state.ready,"operationalEligible":state.verified,"pinRequired":state.phase=="PIN_REQUIRED",
            "pairingStreamOpen":self.child.is_some() && !["IDLE","DISCOVERING","CONNECTING","DISCONNECTING","FAILED"].contains(&state.phase.as_str()),
            "pairingStage":state.phase,"error":state.error,"journal":state.journal.iter().cloned().collect::<Vec<_>>().join("\n"),
            "capabilities":{"platform":if cfg!(target_os="windows") {"windows"} else {"linux"},"backend":if cfg!(target_os="windows") {"usb_hci_shared_protocol"} else {"hci_user_channel_shared_protocol"},
                "adapterSelection":true,"discovery":available,"bluetoothLink":available,"fixedL2cap":available,"pairing":available,
                "operationalIds":available,"ownerConfirmation":true,"wifi":false,"pairingUnavailableReason":if available {None} else {Some(if cfg!(target_os="windows") {"WINDOWS_RAW_HCI_REQUIRED"} else {"LINUX_BACKEND_NOT_INSTALLED"})}}});
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
}
