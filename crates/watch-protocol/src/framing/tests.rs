use super::*;

#[test]
fn exact_empty_payload_vector() {
    assert_eq!(
        encode_uike_frame(&[]).unwrap(),
        [0x04, 0x00, 0x00, 0xfb, 0xff]
    );
}

#[test]
fn exact_even_payload_vector_with_odd_checksum_offset() {
    // Prefix: 04 00 02 de ad. The trailing ad is padded with 00 for the
    // checksum calculation, yielding 0x4c21.
    assert_eq!(
        encode_uike_frame(&[0xde, 0xad]).unwrap(),
        [0x04, 0x00, 0x02, 0xde, 0xad, 0x4c, 0x21]
    );
}

#[test]
fn exact_odd_payload_vector() {
    assert_eq!(
        encode_uike_frame(&[0x01, 0x02, 0x03]).unwrap(),
        [0x04, 0x00, 0x03, 0x01, 0x02, 0x03, 0xf6, 0xfb]
    );
}

#[test]
fn fragmented_input_and_multiple_frames() {
    let first = encode_uike_frame(&[1, 2, 3, 4]).unwrap();
    let second = encode_uike_frame(&[5, 6]).unwrap();
    let mut stream = first;
    stream.extend_from_slice(&second);

    let mut decoder = UikeStreamDecoder::new();
    assert!(decoder.push(&stream[..2]).unwrap().is_empty());
    assert_eq!(decoder.buffered_len(), 2);
    assert!(decoder.push(&stream[2..6]).unwrap().is_empty());

    let payloads = decoder.push(&stream[6..]).unwrap();
    assert_eq!(payloads, [vec![1, 2, 3, 4], vec![5, 6]]);
    assert_eq!(decoder.buffered_len(), 0);
}

#[test]
fn rejects_bad_checksum_and_keeps_evidence() {
    let mut frame = encode_uike_frame(&[0xaa, 0xbb]).unwrap();
    let last = frame.len() - 1;
    frame[last] ^= 0x01;

    let mut decoder = UikeStreamDecoder::new();
    assert!(matches!(
        decoder.push(&frame),
        Err(FrameError::ChecksumMismatch { .. })
    ));
    assert_eq!(decoder.buffered_bytes(), frame);
}

#[test]
fn rejects_wrong_tlv_type() {
    let mut frame = encode_uike_frame(&[0x11]).unwrap();
    frame[0] = 0x05;
    let checksum_offset = frame.len() - CHECKSUM_LEN;
    let checksum = internet_checksum(&frame[..checksum_offset]);
    frame[checksum_offset..].copy_from_slice(&checksum.to_be_bytes());

    let mut decoder = UikeStreamDecoder::new();
    assert_eq!(
        decoder.push(&frame),
        Err(FrameError::UnexpectedType {
            received: 0x05,
            expected: 0x04,
        })
    );
}

#[test]
fn rejects_payload_larger_than_uint16() {
    let payload = vec![0u8; MAX_PAYLOAD_LEN + 1];
    assert_eq!(
        encode_uike_frame(&payload),
        Err(FrameError::PayloadTooLarge {
            length: MAX_PAYLOAD_LEN + 1,
            maximum: MAX_PAYLOAD_LEN,
        })
    );
}

#[test]
fn exact_bt_cl_version_vector() {
    use bt_cl::*;

    let cl_pdu = encode_pdu(
        CURRENT_VERSION,
        VERSION_INFO,
        &version_payload(CURRENT_VERSION, 0x0000_1521),
    )
    .unwrap();
    assert_eq!(cl_pdu, [0x09, 0x05, 0x0b, 0x21, 0x15, 0x00, 0x00]);

    let l2cap_pdu = encode_l2cap_pdu(SIGNALING_CID, &cl_pdu).unwrap();
    assert_eq!(
        l2cap_pdu,
        [
            0x07, 0x00, 0x3a, 0x00, 0x09, 0x05, 0x0b, 0x21, 0x15, 0x00, 0x00
        ]
    );
    assert_eq!(
        decode_l2cap_pdu(&l2cap_pdu).unwrap(),
        L2capPdu {
            destination_cid: SIGNALING_CID,
            payload: cl_pdu,
        }
    );
}

#[test]
fn exact_bt_cl_terminus_service_vector_and_round_trip() {
    use bt_cl::*;

    let record = ServiceRecord {
        service_id: 1,
        service_type: 1,
        name: TERMINUS_PAIRING_NAME.to_vec(),
        flags: 1,
    };
    let payload = remote_services_payload(std::slice::from_ref(&record)).unwrap();
    let pdu = encode_pdu(CURRENT_VERSION, REMOTE_SERVICES, &payload).unwrap();

    let mut expected = vec![0x01, 0x21, 0x00, 0x01, 0x1f, 0x01, 0x00, 0x01, 0x1a];
    expected.extend_from_slice(TERMINUS_PAIRING_NAME);
    expected.push(0x01);
    assert_eq!(pdu, expected);

    let decoded = decode_pdu(CURRENT_VERSION, &pdu).unwrap();
    assert_eq!(decoded.opcode, REMOTE_SERVICES);
    assert_eq!(parse_remote_services(&decoded.payload).unwrap(), [record]);
}

#[test]
fn exact_bt_cl_create_and_accept_vectors() {
    use bt_cl::*;

    let create = CreateChannel {
        initiator_local_cid: 0x0040,
        common_service_id: 0x0001,
    };
    assert_eq!(
        encode_pdu(
            CURRENT_VERSION,
            CREATE_CHANNEL,
            &create_channel_payload(create),
        )
        .unwrap(),
        [0x03, 0x04, 0x00, 0x40, 0x00, 0x01, 0x00]
    );
    assert_eq!(
        parse_create_channel(&[0x40, 0x00, 0x01, 0x00]).unwrap(),
        create
    );

    let accept = AcceptChannel {
        status: 0,
        service_id: 0x0001,
        responder_local_cid: 0x0041,
    };
    assert_eq!(
        encode_pdu(
            CURRENT_VERSION,
            ACCEPT_CHANNEL,
            &accept_channel_payload(accept),
        )
        .unwrap(),
        [0x04, 0x05, 0x00, 0x00, 0x01, 0x00, 0x41, 0x00]
    );
    assert_eq!(
        parse_accept_channel(&[0x00, 0x01, 0x00, 0x41, 0x00]).unwrap(),
        accept
    );
}

#[test]
fn bt_cl_header_changes_at_version_8_but_version_info_stays_short() {
    use bt_cl::*;

    assert_eq!(
        encode_pdu(7, CREATE_CHANNEL, &[1, 2, 3, 4]).unwrap(),
        [0x03, 0x04, 1, 2, 3, 4]
    );
    assert_eq!(
        encode_pdu(8, CREATE_CHANNEL, &[1, 2, 3, 4]).unwrap(),
        [0x03, 0x04, 0x00, 1, 2, 3, 4]
    );
    assert_eq!(
        encode_pdu(8, VERSION_INFO, &[1, 2, 3, 4, 5]).unwrap(),
        [0x09, 0x05, 1, 2, 3, 4, 5]
    );
}

#[test]
fn bt_cl_decoder_rejects_trailing_bytes() {
    use bt_cl::*;

    assert_eq!(
        decode_pdu(
            CURRENT_VERSION,
            &[CREATE_CHANNEL, 0x04, 0x00, 1, 2, 3, 4, 5]
        ),
        Err(CodecError::LengthMismatch {
            declared: 4,
            actual: 5,
        })
    );
}

#[test]
fn dynamic_cid_carries_the_uike_stream_directly() {
    use bt_cl::*;

    let uike = encode_uike_frame(&[]).unwrap();
    assert_eq!(
        encode_l2cap_pdu(0x0041, &uike).unwrap(),
        [0x05, 0x00, 0x41, 0x00, 0x04, 0x00, 0x00, 0xfb, 0xff]
    );
}

#[test]
fn qualcomm_crc_matches_crc16_x25_check_vector() {
    use qualcomm_diag::*;

    assert_eq!(crc16_l(b"123456789"), 0x906e);
    assert_eq!(
        encode_hdlc(b"123456789"),
        [
            0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x6e, 0x90, 0x7e
        ]
    );
}

#[test]
fn exact_qualcomm_ftm_bootstrap_frames() {
    use qualcomm_diag::*;

    assert_eq!(ftm_bootstrap(true), [0x4b, 0x0b, 0xfb, 0x04, 0x00, 0x01]);
    assert_eq!(
        encode_hdlc(&ftm_bootstrap(true)),
        [0x4b, 0x0b, 0xfb, 0x04, 0x00, 0x01, 0x41, 0x5a, 0x7e]
    );
    assert_eq!(
        encode_non_hdlc(&ftm_bootstrap(true)).unwrap(),
        [
            0x7e, 0x01, 0x06, 0x00, 0x4b, 0x0b, 0xfb, 0x04, 0x00, 0x01, 0x7e
        ]
    );
}

#[test]
fn qualcomm_framing_round_trips_escaped_bytes() {
    use qualcomm_diag::*;

    let payload = [0x4b, 0x7d, 0x7e, 0x00];
    let hdlc = encode_hdlc(&payload);
    assert!(hdlc.windows(2).any(|pair| pair == [0x7d, 0x5d]));
    assert!(hdlc.windows(2).any(|pair| pair == [0x7d, 0x5e]));
    assert_eq!(decode_hdlc(&hdlc).unwrap(), payload);

    let non_hdlc = encode_non_hdlc(&payload).unwrap();
    assert_eq!(decode_non_hdlc(&non_hdlc).unwrap(), payload);
}

#[test]
fn qualcomm_decoders_reject_corruption() {
    use qualcomm_diag::*;

    let mut hdlc = encode_hdlc(&FTM_BOOTSTRAP_START);
    hdlc[0] ^= 1;
    assert!(matches!(
        decode_hdlc(&hdlc),
        Err(DiagFrameError::ChecksumMismatch { .. })
    ));

    let mut non_hdlc = encode_non_hdlc(&FTM_BOOTSTRAP_START).unwrap();
    non_hdlc[2] = 5;
    assert_eq!(
        decode_non_hdlc(&non_hdlc),
        Err(DiagFrameError::NonHdlcLengthMismatch {
            declared: 5,
            actual: 6,
        })
    );
}

#[test]
fn exact_qualcomm_ftm_hci_read_version_request() {
    use qualcomm_diag::*;

    assert_eq!(
        build_ftm_h4_request(&HCI_READ_LOCAL_VERSION_INFORMATION).unwrap(),
        [
            0x4b, 0x0b, 0x04, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x10, 0x00
        ]
    );
    assert_eq!(
        build_ftm_h4_request(&[]),
        Err(DiagFrameError::EmptyH4Packet)
    );
}

#[test]
fn exact_qualcomm_bluetooth_log_mask() {
    use qualcomm_diag::*;

    let request = build_bluetooth_hci_log_mask();
    assert_eq!(request.len(), 126);
    assert_eq!(
        &request[..16],
        [
            0x73, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x69, 0x03,
            0x00, 0x00
        ]
    );

    let payload = &request[16..];
    assert_eq!(payload.len(), 110);
    assert!(payload[..108].iter().all(|&byte| byte == 0));
    assert_eq!(payload[108], 0x40); // item 0x366, bit 6
    assert_eq!(payload[109], 0x01); // item 0x368, bit 0
}

#[test]
fn qualcomm_log_mask_rejects_mixed_equipment_ids() {
    use qualcomm_diag::*;

    assert_eq!(
        build_log_mask_set(&[LOG_BT_HCI_EVENT, 0x2368]),
        Err(DiagFrameError::MixedLogEquipIds {
            expected: 1,
            received: 2,
            log_code: 0x2368,
        })
    );
    assert_eq!(
        build_log_mask_set(&[]),
        Err(DiagFrameError::EmptyLogCodeSet)
    );
}

#[test]
fn exact_oneplus_watch_scan_and_connect_h4_vectors() {
    use oneplus_hci as hci;

    let setup = hci::watch_scan_setup_h4_commands(true).unwrap();
    assert_eq!(
        setup,
        [
            vec![0x01, 0x03, 0x0c, 0x00],
            vec![
                0x01, 0x01, 0x0c, 0x08, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20,
            ],
            vec![
                0x01, 0x01, 0x20, 0x08, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            ],
            vec![
                0x01, 0x0b, 0x20, 0x07, 0x00, 0x60, 0x00, 0x30, 0x00, 0x00, 0x00,
            ],
            vec![0x01, 0x0c, 0x20, 0x02, 0x01, 0x00],
        ]
    );
    assert_eq!(
        hci::build_le_scan_enable_h4(false, false).unwrap(),
        [0x01, 0x0c, 0x20, 0x02, 0x00, 0x00]
    );

    let address = [0x59, 0xd7, 0xbf, 0xee, 0xe7, 0x53];
    let create = hci::build_le_create_connection_h4(1, address).unwrap();
    assert_eq!(
        create,
        [
            0x01, 0x0d, 0x20, 0x19, 0x60, 0x00, 0x60, 0x00, 0x00, 0x01, 0x59, 0xd7, 0xbf, 0xee,
            0xe7, 0x53, 0x00, 0x18, 0x00, 0x28, 0x00, 0x00, 0x00, 0xf4, 0x01, 0x00, 0x00, 0x00,
            0x00,
        ]
    );
    let diag = qualcomm_diag::build_ftm_h4_request(&create).unwrap();
    assert_eq!(&diag[..10], [0x4b, 0x0b, 0x04, 0, 0, 0, 0x1d, 0, 0, 0]);
    assert_eq!(&diag[10..], create);
}

#[test]
fn parses_physical_watch_setup_and_builds_fresh_address_handoff() {
    use oneplus_hci as hci;

    let advertising_data = vec![
        0x02, 0x01, 0x1a, 0x10, 0x16, 0x25, 0xfe, 0x06, 0x20, 0x8a, 0xf4, 0xb4, 0xce, 0x64,
        0x38, 0x50, 0x00, 0xd0, 0x10, 0x00, 0x02, 0x0a, 0x00,
    ];
    let address = [0x69, 0xcb, 0x25, 0x2d, 0x6a, 0x64];
    let mut event = vec![0x3e, 0, 0x02, 0x01, 0x00, 0x01];
    event.extend_from_slice(&address);
    event.push(advertising_data.len() as u8);
    event.extend_from_slice(&advertising_data);
    event.push((-31i8) as u8);
    event[1] = (event.len() - 2) as u8;

    let reports = hci::parse_legacy_advertising_reports(&event).unwrap();
    assert_eq!(reports.len(), 1);
    assert_eq!(reports[0].address, address);
    assert_eq!(reports[0].rssi, -31);

    let setup = hci::parse_watch_setup_payload(&reports[0].data)
        .unwrap()
        .unwrap();
    assert_eq!(setup.header_version, 1);
    assert_eq!(setup.candidate_identifier, [0x8a, 0xf4, 0xb4, 0xce]);
    assert_eq!(setup.metadata, [0x64, 0x38, 0x50, 0, 0xd0, 0x10, 0]);
    assert!(
        hci::is_expected_watch_setup_target(&reports[0], [0x8a, 0xf4, 0xb4, 0xce], -75)
            .unwrap()
    );

    let create =
        hci::build_le_create_connection_h4(reports[0].address_type, reports[0].address)
            .unwrap();
    assert_eq!(&create[10..16], address);
}

#[test]
fn parses_staged_hci_command_and_connection_events() {
    use oneplus_hci as hci;

    let complete = hci::parse_command_complete(&[
        0x0e, 0x0c, 0x01, 0x01, 0x10, 0x00, 0x0d, 0x34, 0x12, 0x0d, 0x5d, 0x00, 0x78, 0x56,
    ])
    .unwrap();
    assert_eq!(complete.opcode, hci::OPCODE_READ_LOCAL_VERSION);
    assert_eq!(complete.status, 0);
    assert_eq!(complete.num_hci_command_packets, 1);

    let status = hci::parse_command_status(&[0x0f, 0x04, 0x00, 0x01, 0x0d, 0x20]).unwrap();
    assert_eq!(status.opcode, hci::OPCODE_LE_CREATE_CONNECTION);
    assert_eq!(status.status, 0);

    let connection = hci::parse_le_connection_complete(&[
        0x3e, 0x13, 0x01, 0x00, 0x0b, 0x00, 0x00, 0x01, 0x69, 0xcb, 0x25, 0x2d, 0x6a, 0x64,
        0x28, 0x00, 0x00, 0x00, 0xf4, 0x01, 0x05,
    ])
    .unwrap();
    assert_eq!(connection.connection_handle, 0x000b);
    assert_eq!(connection.role, 0);
    assert_eq!(
        connection.peer_address,
        [0x69, 0xcb, 0x25, 0x2d, 0x6a, 0x64]
    );
    assert_eq!(connection.connection_interval, 0x0028);
    assert_eq!(connection.supervision_timeout, 0x01f4);
    assert_eq!(
        hci::build_disconnect_h4(connection.connection_handle).unwrap(),
        [0x01, 0x06, 0x04, 0x03, 0x0b, 0x00, 0x13]
    );
}

#[test]
fn exact_oneplus_diag_bt_cl_version_acl_round_trip() {
    use oneplus_hci as hci;

    let h4 = hci::build_bt_cl_version_h4_acl(0x0041).unwrap();
    assert_eq!(
        h4,
        [
            0x02, 0x41, 0x20, 0x0b, 0x00, 0x07, 0x00, 0x3a, 0x00, 0x09, 0x05, 0x0b, 0x21, 0x15,
            0x00, 0x00,
        ]
    );
    assert_eq!(
        hci::build_bt_cl_version_diag_request(0x0041).unwrap(),
        [
            0x4b, 0x0b, 0x04, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x02, 0x41, 0x20, 0x0b,
            0x00, 0x07, 0x00, 0x3a, 0x00, 0x09, 0x05, 0x0b, 0x21, 0x15, 0x00, 0x00,
        ]
    );

    let acl = hci::parse_complete_l2cap_acl(&h4[1..]).unwrap();
    assert_eq!(acl.connection_handle, 0x0041);
    assert_eq!(acl.packet_boundary_flag, 2);
    assert_eq!(acl.destination_cid, bt_cl::SIGNALING_CID);
    let cl = bt_cl::decode_pdu(bt_cl::CURRENT_VERSION, &acl.payload).unwrap();
    assert_eq!(cl.opcode, bt_cl::VERSION_INFO);
    assert_eq!(cl.payload, [0x0b, 0x21, 0x15, 0x00, 0x00]);

    let item_length = 12 + h4.len() - 1;
    let mut diag_log = vec![0x10, 0x00];
    diag_log.extend_from_slice(&(item_length as u16).to_le_bytes());
    diag_log.extend_from_slice(&(item_length as u16).to_le_bytes());
    diag_log.extend_from_slice(&qualcomm_diag::LOG_BT_HCI_ACL.to_le_bytes());
    diag_log.extend_from_slice(&[0; 8]);
    diag_log.extend_from_slice(&h4[1..]);
    assert_eq!(
        hci::parse_bluetooth_diag_log(&diag_log).unwrap(),
        hci::BluetoothLog::Acl(h4[1..].to_vec())
    );
}

#[test]
fn oneplus_hci_rejects_wrong_lengths_and_targets() {
    use oneplus_hci as hci;

    assert!(matches!(
        hci::build_le_create_connection_h4(2, [0; 6]),
        Err(hci::CodecError::InvalidAddressType { .. })
    ));
    assert!(matches!(
        hci::build_disconnect_h4(0x0f00),
        Err(hci::CodecError::InvalidConnectionHandle { .. })
    ));
    assert!(matches!(
        hci::parse_command_status(&[0x0f, 0x04, 0, 1, 0x0d]),
        Err(hci::CodecError::LengthMismatch { .. })
    ));

    let mut bad_log = vec![0x10, 0, 12, 0, 12, 0, 0x66, 0x13];
    bad_log.extend_from_slice(&[0; 8]);
    bad_log.push(0x0e);
    assert!(matches!(
        hci::parse_bluetooth_diag_log(&bad_log),
        Err(hci::CodecError::LengthMismatch { .. })
    ));
}
