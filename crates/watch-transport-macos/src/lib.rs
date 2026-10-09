//! CoreBluetooth OS adapter. The Objective-C shim only owns Apple objects and
//! forwards bounded observations; the session and protocol remain in Rust.
use std::ffi::c_char;
use watch_core::transport::{Capabilities, Transport};
pub type EventCallback = extern "C" fn(*const c_char);
pub struct MacTransport;
impl MacTransport {
    /// Callback consumes the JSON synchronously; pointers must not be retained.
    pub fn new(callback: EventCallback) -> Self {
        #[cfg(target_os = "macos")]
        unsafe {
            aw_macos_initialize(callback);
        }
        #[cfg(not(target_os = "macos"))]
        let _ = callback;
        Self
    }
}
#[cfg(target_os = "macos")]
unsafe extern "C" {
    fn aw_macos_initialize(callback: EventCallback);
    fn aw_macos_scan(epoch: u64);
    fn aw_macos_stop_scan();
    fn aw_macos_connect(epoch: u64, id: *const c_char);
    fn aw_macos_disconnect(epoch: u64, id: *const c_char);
    fn aw_macos_pairing_send(epoch: u64, id: *const c_char, bytes: *const u8, length: usize);
    fn aw_macos_pairing_bootstrap_allowed() -> bool;
}
impl Transport for MacTransport {
    fn capabilities(&self) -> Capabilities {
        let supported = cfg!(target_os = "macos");
        #[cfg(target_os = "macos")]
        let bootstrap_allowed = unsafe { aw_macos_pairing_bootstrap_allowed() };
        #[cfg(not(target_os = "macos"))]
        let bootstrap_allowed = false;
        Capabilities {
            platform: std::env::consts::OS.into(),
            backend: "core_bluetooth".into(),
            discovery: supported,
            bluetooth_link: supported,
            fixed_l2cap: false,
            pairing: supported && bootstrap_allowed,
            operational_ids: false,
            pairing_unavailable_reason: (supported && !bootstrap_allowed)
                .then(|| "MACOS_PAIRING_BOOTSTRAP_RESTRICTED".into()),
        }
    }
    fn start_scan(&mut self, epoch: u64) {
        #[cfg(target_os = "macos")]
        unsafe {
            aw_macos_scan(epoch);
        }
        #[cfg(not(target_os = "macos"))]
        let _ = epoch;
    }
    fn stop_scan(&mut self) {
        #[cfg(target_os = "macos")]
        unsafe {
            aw_macos_stop_scan();
        }
    }
    fn connect(&mut self, epoch: u64, id: &str) {
        #[cfg(target_os = "macos")]
        if let Ok(id) = std::ffi::CString::new(id) {
            // Shim copies the ID before dispatching onto the main queue.
            unsafe {
                aw_macos_connect(epoch, id.as_ptr());
            }
        }
        #[cfg(not(target_os = "macos"))]
        let _ = (epoch, id);
    }
    fn disconnect(&mut self, epoch: u64, id: &str) {
        #[cfg(target_os = "macos")]
        if let Ok(id) = std::ffi::CString::new(id) {
            unsafe {
                aw_macos_disconnect(epoch, id.as_ptr());
            }
        }
        #[cfg(not(target_os = "macos"))]
        let _ = (epoch, id);
    }
    fn send_pairing(&mut self, epoch: u64, id: &str, bytes: &[u8]) {
        #[cfg(target_os = "macos")]
        if let Ok(id) = std::ffi::CString::new(id) {
            // Native adapter copies both buffers before dispatch, then checks
            // peer/epoch again on its queue. Neither pointer escapes the call.
            unsafe {
                aw_macos_pairing_send(epoch, id.as_ptr(), bytes.as_ptr(), bytes.len());
            }
        }
        #[cfg(not(target_os = "macos"))]
        let _ = (epoch, id, bytes);
    }
}
