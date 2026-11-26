use serde_json::json;
use std::{
    ffi::{CString, c_char},
    panic::{AssertUnwindSafe, catch_unwind},
    sync::{Mutex, OnceLock},
};
use watch_transport_linux::LinuxBackend;

static BACKEND: OnceLock<Mutex<LinuxBackend>> = OnceLock::new();

fn backend() -> &'static Mutex<LinuxBackend> {
    BACKEND.get_or_init(|| {
        control::spawn();
        Mutex::new(LinuxBackend::new())
    })
}

/// Same-user local control socket for desktop notification relay and Health
/// snapshots. Only non-privileged bulletin/health methods are reachable;
/// pairing, PIN, and software update commands remain FFI-only.
/// Contributed by Nocehi.
mod control {
    use super::backend;
    use serde_json::{Value, json};
    use std::{
        io::{BufRead, BufReader, Read, Write},
        os::unix::{
            fs::{DirBuilderExt, PermissionsExt},
            net::{UnixListener, UnixStream},
        },
        path::PathBuf,
        time::Duration,
    };

    const MAX_REQUEST: u64 = 256 * 1024;
    const METHODS: [&str; 5] = [
        "sendBulletin",
        "removeBulletin",
        "bulletinStatus",
        "healthStatus",
        "pullHealth",
    ];

    pub(super) fn socket_path() -> Option<PathBuf> {
        if let Some(path) = std::env::var_os("WATCH_COMPANION_CONTROL_SOCKET") {
            return Some(PathBuf::from(path));
        }
        let runtime = std::env::var_os("XDG_RUNTIME_DIR")?;
        Some(PathBuf::from(runtime).join("watch-companion/control.sock"))
    }

    pub(super) fn spawn() {
        if std::env::var_os("WATCH_COMPANION_CONTROL_DISABLE").is_some() {
            return;
        }
        let Some(path) = socket_path() else { return };
        let listener = match bind(&path) {
            Ok(listener) => listener,
            Err(error) => {
                eprintln!("watch-companion control socket unavailable: {error}");
                return;
            }
        };
        let _ = std::thread::Builder::new()
            .name("watch-control".into())
            .spawn(move || {
                for stream in listener.incoming().flatten() {
                    let _ = serve(stream);
                }
            });
    }

    fn bind(path: &std::path::Path) -> std::io::Result<UnixListener> {
        if let Some(dir) = path.parent() {
            std::fs::DirBuilder::new()
                .recursive(true)
                .mode(0o700)
                .create(dir)?;
            std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))?;
        }
        if path.exists() {
            if UnixStream::connect(path).is_ok() {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::AddrInUse,
                    "another companion owns the control socket",
                ));
            }
            std::fs::remove_file(path)?;
        }
        let listener = UnixListener::bind(path)?;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
        Ok(listener)
    }

    fn serve(stream: UnixStream) -> std::io::Result<()> {
        stream.set_read_timeout(Some(Duration::from_secs(2)))?;
        stream.set_write_timeout(Some(Duration::from_secs(2)))?;
        let mut line = String::new();
        BufReader::new((&stream).take(MAX_REQUEST)).read_line(&mut line)?;
        let response = dispatch(&line);
        let mut writer = &stream;
        writer.write_all(response.to_string().as_bytes())?;
        writer.write_all(b"\n")
    }

    pub(super) fn dispatch(line: &str) -> Value {
        let Ok(request) = serde_json::from_str::<Value>(line.trim_end()) else {
            return json!({"status":"INVALID_REQUEST"});
        };
        if !request["method"]
            .as_str()
            .is_some_and(|method| METHODS.contains(&method))
        {
            return json!({"status":"UNSUPPORTED_OPERATION"});
        }
        backend()
            .lock()
            .map(|mut backend| backend.command(request))
            .unwrap_or_else(|_| json!({"status":"CORE_UNAVAILABLE"}))
    }
}

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
            Ok(request) => backend()
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
    fn control_socket_rejects_non_bulletin_methods_before_backend() {
        for line in [
            "{",
            "{\"method\":\"pair\"}",
            "{\"method\":\"submitPin\",\"pin\":\"123456\"}",
            "{\"method\":\"installSoftwareUpdate\"}",
            "{\"method\":\"stop\"}",
        ] {
            assert_ne!(control::dispatch(line)["status"], "QUEUED");
        }
        assert!(BACKEND.get().is_none());
    }
    #[test]
    fn malformed_input_has_owned_json_output_and_null_free_is_safe() {
        for bytes in [b"{".as_slice(), b"\xff".as_slice()] {
            let pointer = unsafe { aw_core_command(bytes.as_ptr(), bytes.len()) };
            let result: serde_json::Value = unsafe {
                serde_json::from_slice(std::ffi::CStr::from_ptr(pointer).to_bytes()).unwrap()
            };
            assert_eq!(result["status"], "INVALID_REQUEST");
            unsafe {
                aw_core_free(pointer);
            }
        }
        unsafe {
            aw_core_free(std::ptr::null_mut());
        }
    }
}
