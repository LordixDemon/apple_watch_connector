//! Windows full-protocol facade. Raw USB ownership is explicit; no GATT fallback.
use serde_json::json;
use std::{
    ffi::{CString, c_char},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{Mutex, OnceLock},
};
use watch_transport_linux::DesktopProtocolBackend as LinuxBackend;
static BACKEND: OnceLock<Mutex<LinuxBackend>> = OnceLock::new();
/// # Safety
/// pointer addresses length readable bytes. Returned output belongs to aw_core_free.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn aw_core_command(pointer: *const u8, length: usize) -> *mut c_char {
    let output = catch_unwind(AssertUnwindSafe(|| {
        if pointer.is_null() || length > 16384 {
            return json!({"status":"INVALID_REQUEST"});
        }
        let bytes = unsafe { std::slice::from_raw_parts(pointer, length) };
        match serde_json::from_slice(bytes) {
            Ok(request) => BACKEND
                .get_or_init(|| Mutex::new(LinuxBackend::new()))
                .lock()
                .map(|mut backend| backend.command(request))
                .unwrap_or_else(|_| json!({"status":"CORE_UNAVAILABLE"})),
            Err(_) => json!({"status":"INVALID_REQUEST"}),
        }
    }))
    .unwrap_or_else(|_| json!({"status":"CORE_UNAVAILABLE"}));
    CString::new(output.to_string())
        .expect("JSON escapes NUL")
        .into_raw()
}
/// # Safety
/// pointer is null or an unfreed allocation returned by aw_core_command.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn aw_core_free(pointer: *mut c_char) {
    if !pointer.is_null() {
        drop(unsafe { CString::from_raw(pointer) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn malformed_requests_return_owned_json_and_null_free_is_safe() {
        for bytes in [b"{".as_slice(), b"\xff".as_slice()] {
            let pointer = unsafe { aw_core_command(bytes.as_ptr(), bytes.len()) };
            let value: serde_json::Value = unsafe {
                serde_json::from_slice(std::ffi::CStr::from_ptr(pointer).to_bytes()).unwrap()
            };
            assert_eq!(value["status"], "INVALID_REQUEST");
            unsafe {
                aw_core_free(pointer);
            }
        }
        unsafe {
            aw_core_free(std::ptr::null_mut());
        }
    }
}
