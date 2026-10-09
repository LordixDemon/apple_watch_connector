use bytes::BytesMut;
use quinn_proto::{
    DatagramEvent, Dir, Endpoint, EndpointConfig, ServerConfig, TransportConfig,
    crypto::rustls::QuicServerConfig,
};
use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use std::{
    net::SocketAddr,
    sync::Arc,
    time::{Duration, Instant},
};
use watch_replicator_quic::{ALPN, Engine, MAX_DATAGRAM, State};

fn handshake(wrong_pin: bool, exchange: bool, cancel_request: bool) -> State {
    let server_key = rcgen::KeyPair::generate().unwrap();
    let certificate = rcgen::CertificateParams::default()
        .self_signed(&server_key)
        .unwrap();
    let (_, parsed) = x509_parser::parse_x509_certificate(certificate.der()).unwrap();
    let mut pin = parsed.public_key().raw.to_vec();
    if wrong_pin {
        *pin.last_mut().unwrap() ^= 1;
    }
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut tls = rustls::ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .unwrap()
        .with_no_client_auth()
        .with_single_cert(
            vec![certificate.der().clone()],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(server_key.serialize_der())),
        )
        .unwrap();
    tls.alpn_protocols = vec![ALPN.to_vec()];
    let mut config = ServerConfig::with_crypto(Arc::new(QuicServerConfig::try_from(tls).unwrap()));
    let mut transport = TransportConfig::default();
    transport
        .initial_mtu(1200)
        .min_mtu(1200)
        .mtu_discovery_config(None);
    config.transport_config(Arc::new(transport));
    let mut server = Endpoint::new(
        Arc::new(EndpointConfig::default()),
        Some(Arc::new(config)),
        false,
        None,
    );
    let local: SocketAddr = "[::1]:50000".parse().unwrap();
    let remote: SocketAddr = "[::1]:65275".parse().unwrap();
    let key = rcgen::KeyPair::generate().unwrap();
    let start = Instant::now();
    let mut client = Engine::new(&key.serialize_der(), vec![pin], local, remote, start).unwrap();
    let mut peer = None;
    let request: Vec<u8> = (0..60000).map(|i| (i % 251) as u8).collect();
    let response: Vec<u8> = (0..65000).map(|i| (i % 239) as u8).collect();
    let mut request_received = Vec::new();
    let mut response_received = Vec::new();
    let mut request_sent = false;
    let mut stream = None;
    let mut response_offset = 0;
    let mut response_finished = false;
    let mut response_stream = None;
    for tick in 0..1000 {
        let now = start + Duration::from_millis(tick);
        for data in client.poll(now).unwrap() {
            assert!(data.len() <= MAX_DATAGRAM);
            let mut scratch = Vec::new();
            match server.handle(
                now,
                local,
                Some(remote.ip()),
                None,
                BytesMut::from(data.as_slice()),
                &mut scratch,
            ) {
                Some(DatagramEvent::NewConnection(incoming)) => {
                    peer = Some(server.accept(incoming, now, &mut scratch, None).unwrap())
                }
                Some(DatagramEvent::ConnectionEvent(_, event)) => {
                    peer.as_mut().unwrap().1.handle_event(event)
                }
                _ => {}
            }
        }
        if let Some((handle, conn)) = peer.as_mut() {
            if exchange {
                if stream.is_none() {
                    stream = conn.streams().accept(Dir::Bi);
                }
                if stream.is_some() {
                    assert!(
                        conn.streams().accept(Dir::Bi).is_none(),
                        "Messages opened another native connection"
                    );
                }
                if let Some(id) = stream {
                    if request_received.len() < request.len() {
                        let mut receive = conn.recv_stream(id);
                        let mut chunks = receive.read(true).unwrap();
                        loop {
                            match chunks.next(4096) {
                                Ok(Some(chunk)) => request_received.extend_from_slice(&chunk.bytes),
                                Err(quinn_proto::ReadError::Blocked) => break,
                                other => panic!("Unexpected request stream state: {other:?}"),
                            }
                        }
                        let _ = chunks.finalize();
                    }
                    if request_received.len() == request.len() && !response_finished {
                        assert_eq!(request_received, request);
                        let reply = if cancel_request {
                            *response_stream.get_or_insert_with(|| {
                                conn.send_stream(id)
                                    .reset(quinn_proto::VarInt::from_u32(0))
                                    .unwrap();
                                conn.streams().open(Dir::Uni).unwrap()
                            })
                        } else {
                            id
                        };
                        match conn.send_stream(reply).write(&response[response_offset..]) {
                            Ok(count) => response_offset += count,
                            Err(quinn_proto::WriteError::Blocked) => {}
                            other => panic!("Unexpected response stream state: {other:?}"),
                        }
                        if response_offset == response.len() {
                            conn.send_stream(reply).finish().unwrap();
                            response_finished = true;
                        }
                    }
                }
            }
            if conn.poll_timeout().is_some_and(|due| due <= now) {
                conn.handle_timeout(now);
            }
            for _ in 0..8 {
                let mut data = Vec::new();
                let Some(tx) = conn.poll_transmit(now, 1, &mut data) else {
                    break;
                };
                client.receive(&data[..tx.size], now).unwrap();
            }
            while let Some(event) = conn.poll_endpoint_events() {
                if let Some(event) = server.handle_event(*handle, event) {
                    conn.handle_event(event);
                }
            }
            while conn.poll().is_some() {}
        }
        if exchange && client.state() == State::Connected {
            if !request_sent {
                for part in request.chunks(20000) {
                    client.send(part).unwrap();
                }
                request_sent = true;
            }
            let chunks = client.read().unwrap();
            assert!(chunks.len() <= 4);
            for chunk in chunks {
                assert!(chunk.data.len() <= 4096);
                response_received.extend_from_slice(&chunk.data);
                if chunk.finished {
                    if chunk.stream == 0 && cancel_request {
                        continue;
                    }
                    assert_eq!(response_received, response);
                    return client.state();
                }
            }
        } else if client.state() != State::Connecting {
            return client.state();
        }
    }
    assert!(!exchange, "Bidirectional stream did not finish");
    client.state()
}

#[test]
fn authenticates_exact_discovered_public_key() {
    assert_eq!(handshake(false, false, false), State::Connected);
}
#[test]
fn rejects_other_certificate_without_disabling_signatures() {
    assert_eq!(handshake(true, false, false), State::Failed);
}

#[test]
fn delivers_ordered_bounded_bidirectional_stream_and_fin() {
    assert_eq!(handshake(false, true, false), State::Connected);
}

#[test]
fn retains_group_after_canceled_request_and_reads_independent_reply() {
    assert_eq!(handshake(false, true, true), State::Connected);
}

#[test]
fn rejects_unbounded_input_and_backwards_time() {
    let key = rcgen::KeyPair::generate().unwrap();
    let now = Instant::now();
    let mut client = Engine::new(
        &key.serialize_der(),
        vec![vec![1]],
        "[::1]:50000".parse().unwrap(),
        "[::1]:65275".parse().unwrap(),
        now,
    )
    .unwrap();
    assert!(client.receive(&vec![0; MAX_DATAGRAM + 1], now).is_err());
    assert!(client.poll(now - Duration::from_millis(1)).is_err());
}
