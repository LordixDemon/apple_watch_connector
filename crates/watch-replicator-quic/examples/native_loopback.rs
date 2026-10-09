//! Controlled127.0.0.1 simulator interoperability probe, never a Watch endpoint.
use std::{
    fs,
    net::UdpSocket,
    time::{Duration, Instant},
};
use watch_replicator_quic::{Engine, MAX_DATAGRAM, State};

fn main() {
    let args: Vec<_> = std::env::args().collect();
    assert_eq!(args.len(), 3);
    let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
    socket.connect("127.0.0.1:55333").unwrap();
    socket
        .set_read_timeout(Some(Duration::from_millis(10)))
        .unwrap();
    let key = rcgen::KeyPair::generate().unwrap();
    let mut engine = Engine::new(
        &key.serialize_der(),
        vec![fs::read(&args[1]).unwrap()],
        socket.local_addr().unwrap(),
        "127.0.0.1:55333".parse().unwrap(),
        Instant::now(),
    )
    .unwrap();
    let frame = fs::read(&args[2]).unwrap();
    assert!(frame.len() <= 4096);
    let start = Instant::now();
    let mut sent = false;
    while start.elapsed() < Duration::from_secs(30) {
        for packet in engine.poll(Instant::now()).unwrap() {
            socket.send(&packet).unwrap();
        }
        if engine.state() == State::Failed {
            panic!("Controlled QUIC failed: {:?}", engine.failure());
        }
        if engine.state() == State::Connected && !sent {
            println!("Controlled pinned native QUIC connected");
            engine.send(&frame).unwrap();
            sent = true;
        }
        for chunk in engine.read().unwrap() {
            println!(
                "Controlled native response stream={} bytes={} finished={}",
                chunk.stream,
                chunk.data.len(),
                chunk.finished
            );
            if chunk.data == b"native-loopback" {
                return;
            }
        }
        let mut packet = [0u8; MAX_DATAGRAM];
        if let Ok(size) = socket.recv(&mut packet) {
            engine.receive(&packet[..size], Instant::now()).unwrap();
        }
    }
    panic!("Controlled native application response was absent");
}
