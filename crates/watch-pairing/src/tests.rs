use super::*;
use crypto::{Fragments, Keys};
use ml_kem::{
    Decapsulate, KeyExport, TryKeyInit,
    ml_kem_1024::{DecapsulationKey, EncapsulationKey},
};
use p256::{Scalar, elliptic_curve::PrimeField};
use watch_protocol::framing::UikeStreamDecoder;
use zeroize::Zeroizing;
fn hex(input: &str) -> Vec<u8> {
    input
        .as_bytes()
        .as_chunks::<2>()
        .0
        .iter()
        .map(|bytes| u8::from_str_radix(std::str::from_utf8(bytes).unwrap(), 16).unwrap())
        .collect()
}
fn sequence<const N: usize>(start: u8) -> [u8; N] {
    std::array::from_fn(|i| start.wrapping_add(i as u8))
}
fn vector(name: &str) -> Vec<u8> {
    let json: serde_json::Value =
        serde_json::from_str(include_str!("../test-vectors/java-control.json")).unwrap();
    hex(json[name].as_str().unwrap())
}
fn java_keys() -> Keys {
    let peer = wire::SaResponse {
        spi: sequence(17),
        nonce: sequence(192),
        public: vector("x448PeerPublic").try_into().unwrap(),
        packet: vec![],
    };
    Keys::initial(&sequence(64), sequence(1), sequence(160), &peer).unwrap()
}
#[test]
fn java_x448_sha512_key_schedule_intauth_and_aes_gcm() {
    let keys = java_keys();
    assert_eq!(keys.d.as_slice(), vector("skD"));
    assert_eq!(keys.ei.as_slice(), vector("skEi"));
    assert_eq!(keys.er.as_slice(), vector("skEr"));
    assert_eq!(keys.pi.as_slice(), vector("skPi"));
    assert_eq!(keys.pr.as_slice(), vector("skPr"));
    let plain = hex("0000000c0000c54601000102");
    assert_eq!(
        keys.int_auth(true, 41, 0, &plain).as_slice(),
        vector("intAuth")
    );
    let packet = vector("encryptedPinMethod");
    let part = keys.decrypt(&packet, 37, 3, 32).unwrap();
    assert_eq!(*part.plaintext, plain);
    let mut altered = packet.clone();
    *altered.last_mut().unwrap() ^= 1;
    assert!(keys.decrypt(&altered, 37, 3, 32).is_err());
    for offset in [0, 8, 17, 18, 19, 20, 24, 28, 29, 30] {
        let mut altered = packet.clone();
        altered[offset] ^= 1;
        assert!(keys.decrypt(&altered, 37, 3, 32).is_err());
    }
}
#[test]
fn java_mlkem1024_seed_public_key_and_decapsulation() {
    let key = DecapsulationKey::from_seed(sequence::<64>(0).into());
    assert_eq!(
        key.encapsulation_key().to_bytes().as_slice(),
        vector("kemPublic")
    );
    assert_eq!(
        key.decapsulate_slice(&vector("kemCiphertext"))
            .unwrap()
            .as_slice(),
        vector("kemShared")
    );
    let mut changed = vector("kemCiphertext");
    changed[20] ^= 1;
    assert_ne!(
        key.decapsulate_slice(&changed).unwrap().as_slice(),
        vector("kemShared")
    );
}
#[test]
fn java_sa_init_wire_vector_and_required_profile() {
    let spi = sequence(1);
    let nonce = sequence(160);
    let public = sequence(0);
    let packet = wire::sa_init(&spi, &nonce, &public, false);
    assert_eq!(packet.len(), 264);
    assert_eq!(
        packet[..92],
        hex(
            "01020304050607080000000000000000212022080000000000000108220000400000003c010100060300000c01000014800e0100030000080100001c030000080200000703000008060000250300000804000020000000080400001f"
        )
    );
    let pin = wire::sa_init(&spi, &nonce, &public, true);
    assert_eq!(pin.len(), 282);
    assert_eq!(
        pin[256..],
        hex("2900000a000040282af929000008000040330000000800004036")
    );
    let response = response(&spi, &sequence(17), &nonce, &public, true);
    assert!(wire::parse_sa_response(&response, &spi, true).is_ok());
    for length in 0..response.len() {
        assert!(wire::parse_sa_response(&response[..length], &spi, true).is_err());
    }
    let control_only = response_without_pairing(&spi, &sequence(17), &nonce, &public);
    assert!(wire::parse_sa_response(&control_only, &spi, true).is_err());
    assert!(wire::parse_sa_response(&control_only, &spi, false).is_ok());
}
fn response_without_pairing(
    spi: &[u8; 8],
    peer_spi: &[u8; 8],
    nonce: &[u8; 32],
    public: &[u8; 56],
) -> Vec<u8> {
    response(spi, peer_spi, nonce, public, false)
}
fn response(
    spi: &[u8; 8],
    peer_spi: &[u8; 8],
    nonce: &[u8; 32],
    public: &[u8; 56],
    pin: bool,
) -> Vec<u8> {
    let transforms = [
        wire::transform(true, 1, 20, &[0x80, 14, 1, 0]),
        wire::transform(true, 2, 7, &[]),
        wire::transform(true, 6, 37, &[]),
        wire::transform(false, 4, 32, &[]),
    ]
    .concat();
    let mut sa = vec![0, 0, 0, 44, 1, 1, 0, 4];
    sa.extend(transforms);
    let mut ke = vec![0, 32, 0, 0];
    ke.extend_from_slice(public);
    let mut plain = [
        wire::payload(34, &sa),
        wire::payload(40, &ke),
        wire::payload(41, nonce),
        wire::notify(41, 0x4022, &[]),
    ]
    .concat();
    if pin {
        plain.extend(wire::notify(41, 0x4028, &[0x2a, 0xf9]));
        plain.extend(wire::notify(41, 0x4033, &[]));
    }
    plain.extend(wire::notify(0, 0x4036, &[]));
    let mut out = wire::header(spi, peer_spi, 33, 34, 32, 0, plain.len() + 28);
    out.extend(plain);
    out
}
#[test]
fn encrypted_fragments_reorder_replay_conflict_and_bound_checks() {
    let mut sender = java_keys();
    sender.ei = sender.er;
    let receiver = java_keys();
    let plaintext = vec![0xaa; 1576];
    let packets = sender.seal(43, 1, 32, 34, &plaintext).unwrap();
    assert_eq!(packets.len(), 2);
    assert_eq!(packets[0].len(), 1280);
    let mut assembly = Fragments::default();
    assert!(
        assembly
            .push(receiver.decrypt(&packets[1], 43, 1, 32).unwrap())
            .unwrap()
            .is_none()
    );
    assert!(
        assembly
            .push(receiver.decrypt(&packets[1], 43, 1, 32).unwrap())
            .unwrap()
            .is_none()
    );
    let (first, result) = assembly
        .push(receiver.decrypt(&packets[0], 43, 1, 32).unwrap())
        .unwrap()
        .unwrap();
    assert_eq!(first, 34);
    assert_eq!(*result, plaintext);
    let mut assembly = Fragments::default();
    assembly
        .push(receiver.decrypt(&packets[0], 43, 1, 32).unwrap())
        .unwrap();
    let mut part = receiver.decrypt(&packets[0], 43, 1, 32).unwrap();
    part.plaintext[0] ^= 1;
    assert!(assembly.push(part).is_err());
}
#[test]
fn apple_legacy_scalar_reduction_matches_java() {
    let (w0, w1) = spake::legacy_scalars(&sequence::<44>(0), &sequence::<64>(160));
    assert_eq!(
        w0.to_bytes().as_slice(),
        hex("4612a34cbd64364db838190024043d55bc69b5545675a213a5749361a053ea0d")
    );
    assert_eq!(
        w1.to_bytes().as_slice(),
        hex("c234c18f98065a5d03c60b5a8901626bcb0340abaf97f04eda3fcad7c36d308c")
    );
}
fn scalar(value: &str) -> Scalar {
    Scalar::from_repr(<[u8; 32]>::try_from(hex(value)).unwrap().into()).unwrap()
}
fn rfc_prover() -> spake::Prover {
    spake::Prover::from_scalars(
        scalar("bb8e1bbcf3c48f62c08db243652ae55d3e5586053fca77102994f23ad95491b3"),
        scalar("7e945f34d78785b8a3ef44d0df5a1a97d6b3b460409a345ca7830387a74b1dba"),
        scalar("d1232c8e8693d02368976c174e2088851b8365d0d79a9eee709c6a05a2fad539"),
        b"SPAKE2+-P256-SHA256-HKDF-SHA256-HMAC-SHA256 Test Vectors",
        b"client",
        b"server",
    )
    .unwrap()
}
#[test]
fn rfc9383_share_confirmation_and_shared_key_match_java() {
    let mut prover = rfc_prover();
    assert_eq!(
        prover.share.as_slice(),
        hex(
            "04ef3bd051bf78a2234ec0df197f7828060fe9856503579bb1733009042c15c0c1de127727f418b5966afadfdd95a6e4591d171056b333dab97a79c7193e341727"
        )
    );
    let peer = hex(
        "04c0f65da0d11927bdf5d560c69e1d7d939a05b0e88291887d679fcadea75810fb5cc1ca7494db39e82ff2f50665255d76173e09986ab46742c798a9a68437b048",
    );
    assert_eq!(
        prover.peer_share(&peer).unwrap().as_slice(),
        hex("926cc713504b9b4d76c9162ded04b5493e89109f6d89462cd33adc46fda27527")
    );
    assert!(prover.peer_share(&peer).is_err());
    assert_eq!(
        prover
            .finish(&hex(
                "9747bcc4f8fe9f63defee53ac9b07876d907d55047e6ff2def2e7529089d3e68"
            ))
            .unwrap()
            .as_slice(),
        hex("0c5f8ccd1413423a54f6c1fb26ff01534a87f893779c6e68666d772bfd91f3e7")
    );
    assert!(prover.finish(&[0; 32]).is_err());
    let mut wrong = rfc_prover();
    wrong.peer_share(&peer).unwrap();
    assert!(wrong.finish(&[0; 32]).is_err());
    assert!(wrong.finish(&[0; 32]).is_err());
    assert!(rfc_prover().peer_share(&[0; 65]).is_err());
}
#[test]
fn control_exchange_requires_verified_auth_before_a_pin_challenge() {
    for invalid_auth in [false, true] {
        let mut control = ControlSession::new().unwrap();
        let initial = UikeStreamDecoder::new()
            .push(&control.start().unwrap())
            .unwrap()
            .pop()
            .unwrap();
        let spi = initial[..8].try_into().unwrap();
        let parts = wire::parse_payloads(initial[16], &initial[28..]).unwrap();
        let public = parts.iter().find(|p| p.kind == 34).unwrap().body[4..]
            .try_into()
            .unwrap();
        let ni = parts
            .iter()
            .find(|p| p.kind == 40)
            .unwrap()
            .body
            .try_into()
            .unwrap();
        let peer_secret = sequence(128);
        let peer_public = x448::x448(peer_secret, x448::X448_BASEPOINT_BYTES).unwrap();
        let response = response(&spi, &sequence(17), &sequence(192), &peer_public, false);
        let synthetic = wire::SaResponse {
            spi: sequence(17),
            nonce: sequence(192),
            public,
            packet: response.clone(),
        };
        let mut keys = Keys::initial(&peer_secret, spi, ni, &synthetic).unwrap();
        std::mem::swap(&mut keys.ei, &mut keys.er);
        let requests = control
            .receive(&control::frame(&response).unwrap())
            .unwrap();
        let mut decoder = UikeStreamDecoder::new();
        let mut assembly = Fragments::default();
        let mut inner = None;
        for request in &requests {
            for packet in decoder.push(request).unwrap() {
                inner = assembly
                    .push(keys.decrypt(&packet, 43, 1, 8).unwrap())
                    .unwrap()
                    .or(inner);
            }
        }
        let (_, plaintext) = inner.unwrap();
        let ek = EncapsulationKey::new_from_slice(&plaintext[8..]).unwrap();
        let (ciphertext, shared) = ek.encapsulate_deterministic(&sequence::<32>(160).into());
        let mut body = vec![0, 37, 0, 0];
        body.extend_from_slice(&ciphertext);
        let plain = wire::payload(0, &body);
        let int_r = keys.int_auth(true, 34, 0, &plain);
        let int_i = keys.int_auth(false, 34, 0, &plaintext);
        let packets = keys.seal(43, 1, 32, 34, &plain).unwrap();
        let mut auth_requests = Vec::new();
        for packet in packets {
            auth_requests.extend(control.receive(&control::frame(&packet).unwrap()).unwrap());
        }
        let mut updated = keys.update(&shared).unwrap();
        std::mem::swap(&mut updated.ei, &mut updated.er);
        let request = UikeStreamDecoder::new()
            .push(&auth_requests[0])
            .unwrap()
            .pop()
            .unwrap();
        updated.decrypt(&request, 35, 2, 8).unwrap();
        let idr = [&[11, 0, 0, 0][..], wire::CONTROL_ID].concat();
        let id_auth = crypto::prf(&updated.pr, &[&idr]);
        let mut auth = crypto::null_auth(
            &updated.pr,
            &[
                &response,
                &ni,
                &id_auth,
                &int_i,
                &int_r,
                &2u32.to_be_bytes(),
            ],
        );
        if invalid_auth {
            auth[0] ^= 1;
        }
        let auth_body = [&[13, 0, 0, 0][..], &auth].concat();
        let plaintext = [wire::payload(39, &idr), wire::payload(0, &auth_body)].concat();
        let response = updated
            .seal(35, 2, 32, 36, &plaintext)
            .unwrap()
            .pop()
            .unwrap();
        let result = control.receive(&control::frame(&response).unwrap());
        if invalid_auth {
            assert!(result.is_err());
            assert_ne!(control.stage(), ControlStage::PinRequired);
            continue;
        }
        let requests = result.unwrap();
        assert_eq!(requests.len(), 1);
        assert_eq!(control.stage(), ControlStage::SelectingCode);
        let ack = updated.seal(37, 3, 32, 0, &[]).unwrap().pop().unwrap();
        control.receive(&control::frame(&ack).unwrap()).unwrap();
        assert_eq!(control.stage(), ControlStage::SelectingCode);
        let mut tlv = vec![1, 0, 1, 2, 2, 0, 32];
        tlv.extend_from_slice(&sequence::<32>(160));
        let plain = wire::notify(0, 0xc546, &tlv);
        let watch_request = updated.seal(37, 0, 0, 41, &plain).unwrap().pop().unwrap();
        let framed = control::frame(&watch_request).unwrap();
        let replies = control.receive(&framed).unwrap();
        assert_eq!(control.stage(), ControlStage::PinRequired);
        assert_eq!(control.salt().unwrap(), sequence::<32>(160));
        assert_eq!(control.receive(&framed).unwrap(), replies);
        let packet = UikeStreamDecoder::new()
            .push(&replies[0])
            .unwrap()
            .pop()
            .unwrap();
        let ack = updated.decrypt(&packet, 37, 0, 0x28).unwrap();
        assert_eq!(ack.first, 0);
        assert!(ack.plaintext.is_empty());
    }
}
#[test]
fn code_without_a_verified_watch_challenge_is_rejected() {
    let mut engine = PairingEngine::new([0; 12]).unwrap();
    assert!(engine.prepare_pin().is_err());
    let initial = engine.start().unwrap();
    let mut wrong = UikeStreamDecoder::new()
        .push(&initial)
        .unwrap()
        .pop()
        .unwrap();
    wrong[0] ^= 1;
    assert!(engine.receive(&control::frame(&wrong).unwrap()).is_err());
    assert!(engine.prepare_pin().is_err());
    let challenge = pin::PinChallenge {
        salt: Zeroizing::new(vec![0; 32]),
        setup: [0; 12],
    };
    assert!(
        challenge
            .derive(Zeroizing::new(b"12x456".to_vec()))
            .is_err()
    );
}
#[test]
fn salted_pin_ppk_matches_the_working_java_vector() {
    let challenge = pin::PinChallenge {
        salt: Zeroizing::new(sequence::<32>(160).to_vec()),
        setup: hex("208837d4ce64385000d01000").try_into().unwrap(),
    };
    let session = challenge
        .derive(Zeroizing::new(b"012345".to_vec()))
        .unwrap();
    // The independently recovered PPK pipeline can be observed via a dedicated
    // test-only accessor; none of these values enters the public snapshot.
    assert_eq!(
        session.test_seed(),
        hex(
            "b8d66ad794eb29d1b50c3e7f3fb7d3502a84a3644e30d47ecdb2119510929070208837d4ce64385000d01000"
        )
    );
    assert_eq!(
        session.test_ppk(),
        hex(
            "6b5a77e96b100f1c14139afe4b9ccc8e60164d40220df33104879859cc828881aa342997cfba8a2affe94d701ac58529a225b2a5f1f75920382c71be4619d00f"
        )
    );
}
