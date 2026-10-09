//! macOS/Windows runtime: UTF-8 JSON commands/snapshots; each returned allocation must
//! be released exactly once by aw_core_free. No key material crosses this ABI.
use serde_json::{Value, json};
#[cfg(any(test, not(target_os = "windows")))]
use std::ffi::CStr;
use std::time::{Duration, Instant};
use std::{
    ffi::{CString, c_char},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{Mutex, OnceLock},
};
use watch_core::{
    session::{Phase, Session},
    transport::{Transport, TransportEvent},
};
use watch_pairing::{PairingEngine, PairingStage};
#[cfg(not(target_os = "windows"))]
use watch_transport_macos::MacTransport as PlatformTransport;
#[cfg(target_os = "windows")]
use watch_transport_windows::WindowsTransport as PlatformTransport;
use zeroize::Zeroizing;

struct ActivePairing {
    epoch: u64,
    id: String,
    engine: PairingEngine,
    stage: PairingStage,
    since: Instant,
}

struct Runtime {
    session: Session,
    transport: PlatformTransport,
    pairing: Option<ActivePairing>,
}
impl Runtime {
    fn fail_pairing(&mut self, epoch: u64, id: &str, message: &str) {
        if !self.session.owns_pairing(epoch, id) {
            return;
        }
        self.session.observe(TransportEvent::Failed {
            epoch,
            message: message.into(),
        });
        self.pairing = None;
        self.transport.disconnect(epoch, id);
    }
    fn progress(&mut self) {
        let Some(active) = self.pairing.as_mut() else {
            return;
        };
        let stage = active.engine.stage();
        if active.stage != stage {
            active.stage = stage;
            active.since = Instant::now();
        }
        let (phase, name) = match stage {
            PairingStage::ControlSaInit => (Phase::PairingSecurity, "CONTROL_SA_INIT"),
            PairingStage::ControlKeyExchange => (Phase::PairingSecurity, "CONTROL_KEY_EXCHANGE"),
            PairingStage::ControlAuthentication => {
                (Phase::PairingSecurity, "CONTROL_AUTHENTICATION")
            }
            PairingStage::SelectingCode => (Phase::PairingSecurity, "SELECTING_CODE"),
            PairingStage::PinRequired => (Phase::PinRequired, "PIN_REQUIRED"),
            PairingStage::DerivingPin => (Phase::PinDerivation, "DERIVING_PIN"),
            PairingStage::PinSaInit => (Phase::PinAuthentication, "PIN_SA_INIT"),
            PairingStage::PinKeyExchange => (Phase::PinAuthentication, "PIN_KEY_EXCHANGE"),
            PairingStage::PinShare => (Phase::PinAuthentication, "PIN_SHARE"),
            PairingStage::PinConfirmation => (Phase::PinAuthentication, "PIN_CONFIRMATION"),
            PairingStage::PinAuthentication => (Phase::PinAuthentication, "PIN_AUTHENTICATION"),
            PairingStage::PinAuthenticated => (Phase::PinAuthenticated, "PIN_AUTHENTICATED"),
        };
        self.session
            .pairing_progress(active.epoch, &active.id, phase, name);
    }
    fn tick(&mut self) {
        #[cfg(target_os = "windows")]
        while let Some(event) = self.transport.next_event() {
            self.observe(event);
        }
        let expired = self.pairing.as_ref().and_then(|active| {
            let limit = match active.stage {
                PairingStage::PinRequired => 600,
                PairingStage::DerivingPin => 60,
                PairingStage::PinAuthenticated => return None,
                _ => 30,
            };
            (active.since.elapsed() > Duration::from_secs(limit))
                .then(|| (active.epoch, active.id.clone()))
        });
        if let Some((epoch, id)) = expired {
            self.fail_pairing(epoch, &id, "The watch pairing exchange timed out");
        }
    }
    fn observe(&mut self, event: TransportEvent) {
        match event {
            TransportEvent::PairingPipeOpened { epoch, id } => {
                if !self.session.owns_pairing(epoch, &id) || self.pairing.is_some() {
                    return;
                }
                self.session.observe(TransportEvent::PairingPipeOpened {
                    epoch,
                    id: id.clone(),
                });
                let Some(setup) = self.session.selected_setup() else {
                    self.fail_pairing(
                        epoch,
                        &id,
                        "The selected watch has no verified setup advertisement",
                    );
                    return;
                };
                match PairingEngine::new(setup)
                    .and_then(|engine| engine.start().map(|initial| (engine, initial)))
                {
                    Ok((engine, initial)) => {
                        self.transport.send_pairing(epoch, &id, &initial);
                        self.pairing = Some(ActivePairing {
                            epoch,
                            id,
                            stage: engine.stage(),
                            engine,
                            since: Instant::now(),
                        });
                        self.progress();
                    }
                    Err(error) => self.fail_pairing(epoch, &id, &error.to_string()),
                }
            }
            TransportEvent::PairingPipeData { epoch, id, bytes } => {
                if !self.session.owns_pairing(epoch, &id) {
                    return;
                }
                let Some(active) = self
                    .pairing
                    .as_mut()
                    .filter(|p| p.epoch == epoch && p.id == id)
                else {
                    return;
                };
                match active.engine.receive(&bytes) {
                    Ok(packets) => {
                        for packet in packets {
                            self.transport.send_pairing(epoch, &id, &packet);
                        }
                        self.progress();
                    }
                    Err(error) => self.fail_pairing(epoch, &id, &error.to_string()),
                }
            }
            other => {
                self.session.observe(other);
                if let Some(active) = &self.pairing
                    && !self.session.owns_pairing(active.epoch, &active.id)
                {
                    self.pairing = None;
                }
            }
        }
    }
}
static RUNTIME: OnceLock<Mutex<Runtime>> = OnceLock::new();
fn runtime() -> &'static Mutex<Runtime> {
    RUNTIME.get_or_init(|| {
        Mutex::new(Runtime {
            session: Session::default(),
            #[cfg(not(target_os = "windows"))]
            transport: PlatformTransport::new(observe),
            #[cfg(target_os = "windows")]
            transport: PlatformTransport::new(),
            pairing: None,
        })
    })
}
#[cfg(not(target_os = "windows"))]
extern "C" fn observe(pointer: *const c_char) {
    let _ = catch_unwind(|| {
        if pointer.is_null() {
            return;
        }
        // Native shim owns a live NUL-terminated UTF-8 string for this callback only.
        let bytes = unsafe { CStr::from_ptr(pointer) }.to_bytes();
        if bytes.len() > 16_384 {
            return;
        }
        if let Ok(event) = serde_json::from_slice::<TransportEvent>(bytes)
            && let Ok(mut state) = runtime().lock()
        {
            state.observe(event);
        }
    });
}
fn dispatch(mut request: Value) -> Value {
    let Ok(mut state) = runtime().lock() else {
        return json!({"status":"CORE_UNAVAILABLE"});
    };
    state.tick();
    match request.get("method").and_then(Value::as_str) {
        Some("snapshot") => {
            let capabilities = state.transport.capabilities();
            json!(state.session.snapshot(capabilities))
        }
        Some("scan") => {
            if !state.transport.capabilities().discovery {
                return json!({"status":"UNSUPPORTED_PLATFORM"});
            }
            match state.session.start_scan() {
                Ok(epoch) => {
                    state.transport.start_scan(epoch);
                    json!({"status":"QUEUED"})
                }
                Err(error) => json!({"status":error}),
            }
        }
        Some("connect") => {
            let Some(id) = request.get("id").and_then(Value::as_str) else {
                return json!({"status":"INVALID_REQUEST"});
            };
            if id.len() > 64 || id.contains('\0') {
                return json!({"status":"INVALID_REQUEST"});
            }
            match state.session.connect(id) {
                Ok(epoch) => {
                    state.transport.stop_scan();
                    state.transport.connect(epoch, id);
                    json!({"status":"QUEUED"})
                }
                Err(error) => json!({"status":error}),
            }
        }
        Some("stop") => {
            state.pairing = None;
            state.transport.stop_scan();
            if let Some((epoch, id)) = state.session.stop() {
                state.transport.disconnect(epoch, &id);
            }
            json!({"status":"QUEUED"})
        }
        Some("submitPin") => {
            let Some(Value::String(pin)) = request.get_mut("pin").map(Value::take) else {
                return json!({"status":"INVALID_REQUEST"});
            };
            let pin = Zeroizing::new(pin);
            if pin.len() != 6 || !pin.bytes().all(|b| b.is_ascii_digit()) {
                return json!({"status":"INVALID_REQUEST"});
            }
            let Some(active) = state.pairing.as_mut() else {
                return json!({"status":"NO_PIN_CHALLENGE"});
            };
            let challenge = match active.engine.prepare_pin() {
                Ok(challenge) => challenge,
                Err(_) => return json!({"status":"NO_PIN_CHALLENGE"}),
            };
            let epoch = active.epoch;
            let id = active.id.clone();
            let code = Zeroizing::new(pin.as_bytes().to_vec());
            state.progress();
            std::thread::spawn(move || {
                let result = challenge.derive(code);
                if let Ok(mut state) = runtime().lock() {
                    if !state.session.owns_pairing(epoch, &id) {
                        return;
                    }
                    let Some(active) = state.pairing.as_mut().filter(|p| {
                        p.epoch == epoch
                            && p.id == id
                            && p.engine.stage() == PairingStage::DerivingPin
                    }) else {
                        return;
                    };
                    match result.and_then(|pin| active.engine.finish_pin_derivation(pin)) {
                        Ok(initial) => {
                            state.transport.send_pairing(epoch, &id, &initial);
                            state.progress();
                        }
                        Err(error) => state.fail_pairing(epoch, &id, &error.to_string()),
                    }
                }
            });
            json!({"status":"QUEUED"})
        }
        Some("pair") => {
            if request.get("mode").and_then(Value::as_str) != Some("code") {
                return json!({"status":"INVALID_REQUEST"});
            }
            let capabilities = state.transport.capabilities();
            if !capabilities.pairing {
                return json!({
                    "status":"WATCH_PROTOCOL_UNAVAILABLE",
                    "reason":capabilities.pairing_unavailable_reason
                });
            }
            match state.session.start_scan() {
                Ok(epoch) => {
                    state.transport.start_scan(epoch);
                    json!({"status":"QUEUED"})
                }
                Err(error) => json!({"status":error}),
            }
        }
        Some("activate" | "confirmSetup") => {
            json!({"status":"WATCH_PROTOCOL_UNAVAILABLE"})
        }
        _ => json!({"status":"UNKNOWN_COMMAND"}),
    }
}
/// # Safety
/// pointer addresses length readable bytes for the call. Null is permitted only
/// for an empty input. Return value belongs exclusively to aw_core_free.
#[cfg_attr(not(target_os = "windows"), unsafe(no_mangle))]
pub unsafe extern "C" fn aw_core_command(pointer: *const u8, length: usize) -> *mut c_char {
    let output = catch_unwind(AssertUnwindSafe(|| {
        if pointer.is_null() || length > 16_384 {
            return json!({"status":"INVALID_REQUEST"});
        }
        let bytes = unsafe { std::slice::from_raw_parts(pointer, length) };
        match serde_json::from_slice(bytes) {
            Ok(request) => dispatch(request),
            Err(_) => json!({"status":"INVALID_REQUEST"}),
        }
    }))
    .unwrap_or_else(|_| json!({"status":"CORE_UNAVAILABLE"}));
    CString::new(output.to_string())
        .expect("JSON escapes NUL")
        .into_raw()
}
/// # Safety
/// pointer is null or an unfreed pointer returned by aw_core_command.
#[cfg_attr(not(target_os = "windows"), unsafe(no_mangle))]
pub unsafe extern "C" fn aw_core_free(pointer: *mut c_char) {
    if !pointer.is_null() {
        drop(unsafe { CString::from_raw(pointer) });
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn abi_rejects_invalid_json_and_releases_owned_output() {
        let bytes = b"invalid";
        let result = unsafe { aw_core_command(bytes.as_ptr(), bytes.len()) };
        assert_eq!(
            unsafe { CStr::from_ptr(result) }.to_str().unwrap(),
            "{\"status\":\"INVALID_REQUEST\"}"
        );
        unsafe {
            aw_core_free(result);
            aw_core_free(std::ptr::null_mut());
        }
    }
    #[test]
    fn command_receipts_cannot_activate_or_complete_a_watch() {
        for method in ["activate", "confirmSetup"] {
            assert_eq!(
                dispatch(json!({"method":method}))["status"],
                "WATCH_PROTOCOL_UNAVAILABLE"
            );
        }
        assert_eq!(
            dispatch(json!({"method":"pair"}))["status"],
            "INVALID_REQUEST"
        );
        assert_eq!(
            dispatch(json!({"method":"submitPin", "pin":"123456"}))["status"],
            "NO_PIN_CHALLENGE"
        );
        assert_eq!(dispatch(json!({"method":"snapshot"}))["watchReady"], false);
        assert_eq!(
            dispatch(json!({"method":"snapshot"}))["coreVersion"],
            env!("CARGO_PKG_VERSION")
        );
    }
}
