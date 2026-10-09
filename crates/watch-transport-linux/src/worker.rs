//! Bounded event input and deterministic child-process cleanup.
use crate::state::State;
use std::{
    io::{self, BufRead, BufReader, Read},
    process::Child,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

const MAX_LINE: usize = 512 * 1024;

pub(crate) fn read_events(output: impl Read, state: Arc<Mutex<State>>, epoch: u64) {
    let mut reader = BufReader::new(output);
    loop {
        let mut bytes = Vec::new();
        let line = match reader
            .by_ref()
            .take(MAX_LINE as u64 + 1)
            .read_until(b'\n', &mut bytes)
        {
            Ok(0) => Err("Protocol event stream closed"),
            Ok(_) if bytes.len() > MAX_LINE => Err("Protocol event exceeded limit"),
            Ok(_) if bytes.last() != Some(&b'\n') => Err("Protocol event was truncated"),
            Ok(_) => std::str::from_utf8(&bytes).map_err(|_| "Protocol event is not UTF-8"),
            Err(_) => Err("Protocol event read failed"),
        };
        let Ok(mut state) = state.lock() else {
            return;
        };
        if state.epoch != epoch {
            return;
        }
        if ["DISCONNECTING", "FAILED"].contains(&state.phase.as_str()) {
            return;
        }
        match line {
            Ok(line) => state.observe(line.trim_end()),
            Err(reason) => state.fail(reason),
        }
        if state.phase == "FAILED" {
            return;
        }
    }
}

/// Close command input, allow normal cleanup, then kill and reap if necessary.
/// Callers must also drop any independently taken ChildStdin handle.
pub(crate) fn finish_child(child: &mut Child, grace: Duration) -> io::Result<bool> {
    drop(child.stdin.take());
    let deadline = Instant::now() + grace;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => return Ok(true),
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(10)),
            _ => {
                let kill = child.kill();
                // Reap even when kill races an exit; dropping Child does not reap it.
                let wait = child.wait();
                wait?;
                kill?;
                return Ok(false);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;
    #[cfg(unix)]
    use std::process::{Command, Stdio};

    #[test]
    fn malformed_events_invalidate_ready_without_exposing_bytes() {
        for bytes in [
            vec![b'x'; MAX_LINE + 1],
            vec![0xff, b'\n'],
            b"partial".to_vec(),
        ] {
            let state = Arc::new(Mutex::new(State {
                epoch: 4,
                ready: true,
                ids_ready: true,
                ..State::default()
            }));
            read_events(Cursor::new(bytes), Arc::clone(&state), 4);
            let state = state.lock().unwrap();
            assert_eq!(state.phase, "FAILED");
            assert!(!state.ready);
            assert!(!state.ids_ready);
            assert!(state.journal.is_empty());
        }
    }

    #[test]
    fn previous_worker_epoch_cannot_change_new_session() {
        let state = Arc::new(Mutex::new(State {
            epoch: 2,
            phase: "DISCOVERING".into(),
            ..State::default()
        }));
        read_events(
            Cursor::new(b"WATCH_SETUP_PHASE_V1:ACTIVATED\n"),
            Arc::clone(&state),
            1,
        );
        let state = state.lock().unwrap();
        assert_eq!(state.phase, "DISCOVERING");
        assert!(!state.activated);
    }

    #[test]
    fn output_eof_invalidates_connection_but_does_not_override_stop() {
        for stopping in [false, true] {
            let mut initial = State {
                ready: true,
                ids_ready: true,
                ..State::default()
            };
            if stopping {
                initial.begin_stop();
            }
            let state = Arc::new(Mutex::new(initial));
            read_events(Cursor::new(b""), Arc::clone(&state), 0);
            let state = state.lock().unwrap();
            assert_eq!(
                state.phase,
                if stopping { "DISCONNECTING" } else { "FAILED" }
            );
            assert!(!state.ready && !state.ids_ready);
        }
    }

    #[cfg(unix)]
    #[test]
    fn child_receives_eof_and_is_reaped_before_deadline() {
        let mut child = Command::new("/bin/sh")
            .args(["-c", "read -r value; exit 0"])
            .stdin(Stdio::piped())
            .spawn()
            .unwrap();
        assert!(finish_child(&mut child, Duration::from_secs(1)).unwrap());
        assert!(child.try_wait().unwrap().unwrap().success());
    }

    #[cfg(unix)]
    #[test]
    fn child_ignoring_input_is_killed_and_reaped() {
        let mut child = Command::new("/bin/sleep")
            .arg("30")
            .stdin(Stdio::piped())
            .spawn()
            .unwrap();
        assert!(!finish_child(&mut child, Duration::from_millis(10)).unwrap());
        assert!(child.try_wait().unwrap().is_some());
    }
}
