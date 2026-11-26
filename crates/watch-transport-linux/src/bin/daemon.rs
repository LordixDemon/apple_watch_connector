//! Headless Linux daemon owning LinuxBackend and the local control UNIX socket.
//! Keeps the Apple Watch session, notification relay, and HealthDaemon sync running
//! with zero GUI window; yields HCI_CHANNEL_USER cleanly on SIGTERM/SIGINT.
//! Contributed by Nocehi.
#[cfg(not(target_os = "linux"))]
fn main() {
    eprintln!("watch-linux-daemon is Linux-only");
    std::process::exit(1);
}

#[cfg(target_os = "linux")]
fn main() {
    use serde_json::{Value, json};
    use std::{
        io::{BufRead, BufReader, Read, Write},
        os::unix::{
            fs::{DirBuilderExt, PermissionsExt},
            net::{UnixListener, UnixStream},
        },
        path::{Path, PathBuf},
        sync::{
            Arc, Mutex,
            atomic::{AtomicBool, Ordering},
        },
        time::{Duration, Instant},
    };
    use watch_transport_linux::LinuxBackend;

    const MAX_REQUEST: u64 = 256 * 1024;
    const METHODS: [&str; 5] = [
        "sendBulletin",
        "removeBulletin",
        "bulletinStatus",
        "healthStatus",
        "pullHealth",
    ];

    static STOP: AtomicBool = AtomicBool::new(false);
    extern "C" fn handle_signal(_sig: libc::c_int) {
        STOP.store(true, Ordering::SeqCst);
    }
    unsafe {
        libc::signal(libc::SIGTERM, handle_signal as *const () as libc::sighandler_t);
        libc::signal(libc::SIGINT, handle_signal as *const () as libc::sighandler_t);
    }

    fn socket_path() -> Option<PathBuf> {
        if let Some(path) = std::env::var_os("WATCH_COMPANION_CONTROL_SOCKET") {
            return Some(PathBuf::from(path));
        }
        let runtime = std::env::var_os("XDG_RUNTIME_DIR")?;
        Some(PathBuf::from(runtime).join("watch-companion/control.sock"))
    }

    fn bind_socket(path: &Path) -> std::io::Result<UnixListener> {
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
                    "another watch companion owns the control socket",
                ));
            }
            std::fs::remove_file(path)?;
        }
        let listener = UnixListener::bind(path)?;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
        listener.set_nonblocking(true)?;
        Ok(listener)
    }

    fn serve(stream: UnixStream, backend: &Arc<Mutex<LinuxBackend>>) -> std::io::Result<()> {
        stream.set_nonblocking(false)?;
        stream.set_read_timeout(Some(Duration::from_secs(2)))?;
        stream.set_write_timeout(Some(Duration::from_secs(2)))?;
        let mut line = String::new();
        BufReader::new((&stream).take(MAX_REQUEST)).read_line(&mut line)?;
        let response = match serde_json::from_str::<Value>(line.trim_end()) {
            Ok(request)
                if request["method"]
                    .as_str()
                    .is_some_and(|m| METHODS.contains(&m)) =>
            {
                backend
                    .lock()
                    .map(|mut b| b.command(request))
                    .unwrap_or_else(|_| json!({"status":"CORE_UNAVAILABLE"}))
            }
            Ok(_) => json!({"status":"UNSUPPORTED_OPERATION"}),
            Err(_) => json!({"status":"INVALID_REQUEST"}),
        };
        let mut writer = &stream;
        writer.write_all(response.to_string().as_bytes())?;
        writer.write_all(b"\n")
    }

    let Some(sock_path) = socket_path() else {
        eprintln!("watch-linux-daemon: XDG_RUNTIME_DIR is not set");
        std::process::exit(2);
    };
    let listener = match bind_socket(&sock_path) {
        Ok(l) => l,
        Err(err) => {
            eprintln!("watch-linux-daemon: failed to bind {}: {err}", sock_path.display());
            std::process::exit(1);
        }
    };

    let backend = Arc::new(Mutex::new(LinuxBackend::new()));
    {
        let mut b = backend.lock().unwrap();
        let res = b.command(json!({"method":"resume"}));
        eprintln!("watch-linux-daemon: initial resume -> {res}");
    }

    let sock_backend = Arc::clone(&backend);
    let sock_thread = std::thread::Builder::new()
        .name("watch-daemon-sock".into())
        .spawn(move || {
            while !STOP.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let _ = serve(stream, &sock_backend);
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(Duration::from_millis(50));
                    }
                    Err(_) => std::thread::sleep(Duration::from_millis(200)),
                }
            }
        })
        .expect("spawn socket thread");

    let mut failures: u32 = 0;
    let mut next_retry: Option<Instant> = None;
    let mut last_phase = String::new();

    while !STOP.load(Ordering::SeqCst) {
        std::thread::sleep(Duration::from_secs(2));
        if STOP.load(Ordering::SeqCst) {
            break;
        }
        let snap = backend.lock().unwrap().command(json!({"method":"snapshot"}));
        let phase = snap["phase"].as_str().unwrap_or("UNKNOWN").to_owned();
        let ready = snap["watchReady"].as_bool().unwrap_or(false);
        if phase != last_phase {
            eprintln!("watch-linux-daemon: phase={phase} watchReady={ready}");
            last_phase = phase.clone();
        }
        if ready {
            failures = 0;
            next_retry = None;
        } else if phase == "IDLE" || phase == "FAILED" {
            let now = Instant::now();
            match next_retry {
                None => {
                    failures = failures.saturating_add(1);
                    let delay = match failures {
                        1 => Duration::from_secs(10),
                        2 => Duration::from_secs(20),
                        _ => Duration::from_secs(45),
                    };
                    eprintln!("watch-linux-daemon: scheduling reconnect in {}s", delay.as_secs());
                    next_retry = Some(now + delay);
                }
                Some(when) if now >= when => {
                    next_retry = None;
                    let res = backend.lock().unwrap().command(json!({"method":"resume"}));
                    eprintln!("watch-linux-daemon: reconnect resume -> {res}");
                }
                _ => {}
            }
        }
    }

    eprintln!("watch-linux-daemon: shutting down cleanly");
    {
        let mut b = backend.lock().unwrap();
        let _ = b.command(json!({"method":"stop"}));
    }
    let deadline = Instant::now() + Duration::from_secs(4);
    while Instant::now() < deadline {
        let phase = backend
            .lock()
            .unwrap()
            .command(json!({"method":"snapshot"}))["phase"]
            .as_str()
            .unwrap_or("")
            .to_owned();
        if phase == "IDLE" || phase == "FAILED" {
            break;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let _ = sock_thread.join();
    let _ = std::fs::remove_file(&sock_path);
}
