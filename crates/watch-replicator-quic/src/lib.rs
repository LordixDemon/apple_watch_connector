//! Socket-free QUIC for the authenticated NetworkRelay tunnel.
//! Only discovery-pinned TLS peers may establish a connection. No HTTP, DNS,
//! Watch setup, retries on another address, file publication or implicit mutation.

use bytes::BytesMut;
use quinn_proto::{
    ClientConfig, Connection, ConnectionHandle, DatagramEvent, Dir, Endpoint, EndpointConfig,
    Event, RandomConnectionIdGenerator, StreamId, TransportConfig, VarInt,
    crypto::rustls::QuicClientConfig,
};
use rustls::{
    DigitallySignedStruct, SignatureScheme,
    client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier},
    crypto::{CryptoProvider, verify_tls12_signature, verify_tls13_signature},
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime},
};
use std::{
    collections::VecDeque,
    net::SocketAddr,
    sync::Arc,
    time::{Duration, Instant},
};

mod android;

pub const MAX_DATAGRAM: usize = 1200;
pub const MAX_BATCH: usize = 4;
pub const ALPN: &[u8] = b"application-service";

#[derive(Debug)]
struct PinnedPeer {
    keys: Vec<Vec<u8>>,
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinnedPeer {
    fn verify_server_cert(
        &self,
        cert: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        // The authenticated IKE response establishes identity, rather than Web PKI.
        // CertificateVerify still proves possession of this exact advertised key.
        let (rest, parsed) = x509_parser::parse_x509_certificate(cert.as_ref()).map_err(|_| {
            rustls::Error::InvalidCertificate(rustls::CertificateError::BadEncoding)
        })?;
        if !rest.is_empty()
            || !self
                .keys
                .iter()
                .any(|k| k.as_slice() == parsed.public_key().raw)
        {
            return Err(rustls::Error::InvalidCertificate(
                rustls::CertificateError::UnknownIssuer,
            ));
        }
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(
            message,
            cert,
            signature,
            &self.provider.signature_verification_algorithms,
        )
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(
            message,
            cert,
            signature,
            &self.provider.signature_verification_algorithms,
        )
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Connecting,
    Connected,
    Failed,
}

pub struct StreamChunk {
    pub stream: u64,
    pub finished: bool,
    pub data: Vec<u8>,
}

/// Owned by one transport thread. The caller supplies monotonically increasing
/// time and bounded datagrams from the exact authenticated pair/UDP tuple.
pub struct Engine {
    endpoint: Endpoint,
    connection: Connection,
    handle: ConnectionHandle,
    local: SocketAddr,
    remote: SocketAddr,
    last_now: Instant,
    state: State,
    failure: Option<String>,
    scratch: Vec<u8>,
    receive_streams: Vec<StreamId>,
    pending_send: Option<(StreamId, Vec<u8>, usize)>,
    application_stream: Option<StreamId>,
    outbound: VecDeque<Vec<u8>>,
}

impl Engine {
    pub fn new(
        pkcs8: &[u8],
        pins: Vec<Vec<u8>>,
        local: SocketAddr,
        remote: SocketAddr,
        now: Instant,
    ) -> Result<Self, String> {
        if pkcs8.is_empty()
            || pkcs8.len() > 4096
            || pins.is_empty()
            || pins.len() > 8
            || pins.iter().any(|k| k.is_empty() || k.len() > 512)
            || local.port() == 0
            || remote.port() == 0
            || local.is_ipv4() != remote.is_ipv4()
        {
            return Err("Invalid bounded QUIC identity or endpoint".into());
        }
        let private = PrivatePkcs8KeyDer::from(pkcs8.to_vec());
        let key = rcgen::KeyPair::try_from(&PrivateKeyDer::Pkcs8(private.clone_key()))
            .map_err(|_| "Unsupported QUIC private key")?;
        let certificate = rcgen::CertificateParams::default()
            .self_signed(&key)
            .map_err(|_| "QUIC local certificate generation failed")?;
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let mut tls = rustls::ClientConfig::builder_with_provider(provider.clone())
            .with_protocol_versions(&[&rustls::version::TLS13])
            .map_err(|_| "TLS13 unavailable")?
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(PinnedPeer {
                keys: pins,
                provider,
            }))
            .with_client_auth_cert(
                vec![certificate.der().clone()],
                PrivateKeyDer::Pkcs8(private),
            )
            .map_err(|_| "QUIC local identity rejected")?;
        tls.alpn_protocols = vec![ALPN.to_vec()];
        tls.enable_sni = false;
        let crypto =
            QuicClientConfig::try_from(tls).map_err(|_| "QUIC TLS configuration failed")?;
        let mut transport = TransportConfig::default();
        transport
            .initial_mtu(MAX_DATAGRAM as u16)
            .min_mtu(MAX_DATAGRAM as u16)
            .mtu_discovery_config(None)
            .max_concurrent_bidi_streams(VarInt::from_u32(8))
            .max_concurrent_uni_streams(VarInt::from_u32(8))
            .receive_window(VarInt::from_u32(256 * 1024))
            .stream_receive_window(VarInt::from_u32(64 * 1024))
            .max_idle_timeout(Some(
                Duration::from_secs(30)
                    .try_into()
                    .map_err(|_| "Invalid timeout")?,
            ));
        let mut client = ClientConfig::new(Arc::new(crypto));
        client.transport_config(Arc::new(transport));
        let mut config = EndpointConfig::default();
        config
            .max_udp_payload_size(MAX_DATAGRAM as u16)
            .map_err(|_| "Invalid datagram limit")?;
        // Actual native application-service parameters use four-byte source CIDs.
        config.cid_generator(|| Box::new(RandomConnectionIdGenerator::new(4)));
        let mut endpoint = Endpoint::new(Arc::new(config), None, false, None);
        let (handle, connection) = endpoint
            .connect(now, client, remote, "application-service")
            .map_err(|_| "QUIC connection initialization failed")?;
        Ok(Self {
            endpoint,
            connection,
            handle,
            local,
            remote,
            last_now: now,
            state: State::Connecting,
            failure: None,
            scratch: Vec::with_capacity(MAX_DATAGRAM),
            receive_streams: Vec::new(),
            pending_send: None,
            application_stream: None,
            outbound: VecDeque::new(),
        })
    }

    pub fn state(&self) -> State {
        self.state
    }
    pub fn failure(&self) -> Option<&str> {
        self.failure.as_deref()
    }

    pub fn receive(&mut self, datagram: &[u8], now: Instant) -> Result<(), String> {
        self.check_time(now)?;
        if datagram.is_empty() || datagram.len() > MAX_DATAGRAM {
            return Err("QUIC datagram outside bounds".into());
        }
        self.scratch.clear();
        if let Some(DatagramEvent::ConnectionEvent(handle, event)) = self.endpoint.handle(
            now,
            self.remote,
            Some(self.local.ip()),
            None,
            BytesMut::from(datagram),
            &mut self.scratch,
        ) && handle == self.handle
        {
            self.connection.handle_event(event);
        }
        Ok(())
    }

    pub fn poll(&mut self, now: Instant) -> Result<Vec<Vec<u8>>, String> {
        self.check_time(now)?;
        if self.pending_send.is_none()
            && let Some(bytes) = self.outbound.pop_front()
        {
            let stream = match self.application_stream {
                Some(stream) => stream,
                None => {
                    let stream = self
                        .connection
                        .streams()
                        .open(Dir::Bi)
                        .ok_or("QUIC stream credit unavailable")?;
                    self.receive_streams.push(stream);
                    self.application_stream = Some(stream);
                    stream
                }
            };
            self.pending_send = Some((stream, bytes, 0));
        }
        if let Some((stream, bytes, offset)) = self.pending_send.as_mut() {
            match self
                .connection
                .send_stream(*stream)
                .write(&bytes[*offset..])
            {
                Ok(count) => *offset += count,
                Err(quinn_proto::WriteError::Blocked) => {}
                Err(_) => return Err("QUIC application stream was closed".into()),
            }
            if *offset == bytes.len() {
                self.pending_send = None;
            }
        }
        if self.connection.poll_timeout().is_some_and(|due| due <= now) {
            self.connection.handle_timeout(now);
        }
        let mut result = Vec::new();
        for _ in 0..MAX_BATCH {
            self.scratch.clear();
            let Some(transmit) = self.connection.poll_transmit(now, 1, &mut self.scratch) else {
                break;
            };
            if transmit.destination != self.remote
                || transmit.size > MAX_DATAGRAM
                || transmit.segment_size.is_some()
            {
                return Err("QUIC attempted an unapproved route or datagram".into());
            }
            result.push(self.scratch[..transmit.size].to_vec());
        }
        while let Some(event) = self.connection.poll_endpoint_events() {
            if let Some(event) = self.endpoint.handle_event(self.handle, event) {
                self.connection.handle_event(event);
            }
        }
        while let Some(event) = self.connection.poll() {
            match event {
                Event::Connected => self.state = State::Connected,
                Event::ConnectionLost { reason } => {
                    self.state = State::Failed;
                    // Rust's typed QUIC error contains no private key or packet bytes.
                    self.failure = Some(reason.to_string());
                }
                _ => {}
            }
        }
        Ok(result)
    }

    /// Bounded messages share the native connection's bidirectional stream.
    /// Opening a new stream for every ACK replaces the Watch's existing connection.
    pub fn send(&mut self, data: &[u8]) -> Result<(), String> {
        if self.state != State::Connected
            || data.is_empty()
            || data.len() > 64 * 1024
            || self.outbound.len() >= 8
            || self.outbound.iter().map(Vec::len).sum::<usize>()
                + self
                    .pending_send
                    .as_ref()
                    .map_or(0, |(_, bytes, _)| bytes.len())
                + data.len()
                > 256 * 1024
            || (self.application_stream.is_none() && self.receive_streams.len() >= 16)
        {
            return Err("QUIC application send outside bounded ready state".into());
        }
        self.outbound.push_back(data.to_vec());
        Ok(())
    }

    /// Ordered plaintext, at most4 chunks of4KiB per call. Credits are finalized.
    pub fn read(&mut self) -> Result<Vec<StreamChunk>, String> {
        for direction in [Dir::Bi, Dir::Uni] {
            while self.receive_streams.len() < 16 {
                let Some(stream) = self.connection.streams().accept(direction) else {
                    break;
                };
                self.receive_streams.push(stream);
            }
        }
        let mut out = Vec::new();
        let mut completed = Vec::new();
        for &stream in &self.receive_streams {
            let mut recv = self.connection.recv_stream(stream);
            let Ok(mut chunks) = recv.read(true) else {
                completed.push(stream);
                continue;
            };
            while out.len() < 4 {
                match chunks.next(4096) {
                    Ok(Some(chunk)) => out.push(StreamChunk {
                        stream: stream.into(),
                        finished: false,
                        data: chunk.bytes.to_vec(),
                    }),
                    Ok(None) => {
                        out.push(StreamChunk {
                            stream: stream.into(),
                            finished: true,
                            data: Vec::new(),
                        });
                        completed.push(stream);
                        break;
                    }
                    Err(quinn_proto::ReadError::Blocked) => break,
                    Err(quinn_proto::ReadError::Reset(code)) => {
                        // Cancellation of one request stream does not close the group.
                        // Native NetworkMessenger can reply on an independent stream.
                        // Java still rejects partial frames at this end marker.
                        if code != VarInt::from_u32(0) {
                            let _ = chunks.finalize();
                            return Err(format!(
                                "QUIC application stream reset code={}",
                                u64::from(code)
                            ));
                        }
                        out.push(StreamChunk {
                            stream: stream.into(),
                            finished: true,
                            data: Vec::new(),
                        });
                        completed.push(stream);
                        break;
                    }
                }
            }
            // The owning HAL immediately polls transport output after this call.
            let _ = chunks.finalize();
            if out.len() == 4 {
                break;
            }
        }
        self.receive_streams.retain(|id| !completed.contains(id));
        Ok(out)
    }

    fn check_time(&mut self, now: Instant) -> Result<(), String> {
        if now < self.last_now {
            return Err("QUIC time moved backwards".into());
        }
        self.last_now = now;
        Ok(())
    }
}
