//! Portable encrypted BT_CL handoff, matching the working Java bridge's
//! BtClNormalLinkHandoff and its native wire fixtures. This negotiates a pipe;
//! it does not assert that IDS, activation or setup is complete.
use crate::framing::bt_cl::{self, CodecError, CreateChannel, ServiceRecord};
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Phase {
    ReadyToAdvertise,
    WaitingForCommonServices,
    WaitingForAcceptChannel,
    PipeOpen,
}
#[derive(Debug, PartialEq, Eq)]
pub enum HandoffError {
    EncryptionRequired,
    InvalidPeerVersion,
    InvalidDynamicCid,
    UnexpectedPhase,
    UnexpectedOpcode,
    UnexpectedService,
    Rejected(u8),
    Codec(CodecError),
}
impl fmt::Display for HandoffError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "BT_CL normal handoff: {self:?}")
    }
}
impl std::error::Error for HandoffError {}
impl From<CodecError> for HandoffError {
    fn from(error: CodecError) -> Self {
        Self::Codec(error)
    }
}

pub struct NormalLinkHandoff {
    peer_version: u8,
    local_cid: u16,
    remote_cid: Option<u16>,
    phase: Phase,
    service: ServiceRecord,
}
impl NormalLinkHandoff {
    /// The OS backend supplies the allocated local CID and observed encryption
    /// status. A queued encryption command is not confirmation.
    pub fn begin(
        peer_version: u8,
        local_cid: u16,
        encryption_confirmed: bool,
    ) -> Result<Self, HandoffError> {
        if peer_version == 0 {
            return Err(HandoffError::InvalidPeerVersion);
        }
        if !encryption_confirmed {
            return Err(HandoffError::EncryptionRequired);
        }
        if local_cid < 0x0040 {
            return Err(HandoffError::InvalidDynamicCid);
        }
        Ok(Self {
            peer_version,
            local_cid,
            remote_cid: None,
            phase: Phase::ReadyToAdvertise,
            service: ServiceRecord {
                service_id: 2,
                service_type: 1,
                name: b"com.apple.terminusLink\0".to_vec(),
                flags: 1, // Native service requests ERTM with FCS disabled.
            },
        })
    }
    pub fn from_reconnected_channel(
        peer_version: u8,
        local_cid: u16,
        remote_cid: u16,
        encryption_confirmed: bool,
    ) -> Result<Self, HandoffError> {
        let mut result = Self::begin(peer_version, local_cid, encryption_confirmed)?;
        if remote_cid < 0x0040 {
            return Err(HandoffError::InvalidDynamicCid);
        }
        result.remote_cid = Some(remote_cid);
        result.phase = Phase::PipeOpen;
        Ok(result)
    }
    pub fn phase(&self) -> Phase {
        self.phase
    }
    pub fn channel_pair(&self) -> Option<(u16, u16)> {
        self.remote_cid.map(|remote| (self.local_cid, remote))
    }
    /// Advertise exactly once: watchOS retains the first record and rejects
    /// duplicate registration. Retry policy must not replay SERVICE_ADDED.
    pub fn advertise(&mut self) -> Result<Vec<u8>, HandoffError> {
        if self.phase != Phase::ReadyToAdvertise {
            return Err(HandoffError::UnexpectedPhase);
        }
        let payload = bt_cl::service_added_payload(&self.service)?;
        let packet = bt_cl::encode_pdu(self.peer_version, bt_cl::SERVICE_ADDED, &payload)?;
        self.phase = Phase::WaitingForCommonServices;
        Ok(packet)
    }
    pub fn accept_common_services(&mut self, packet: &[u8]) -> Result<Vec<u8>, HandoffError> {
        if self.phase != Phase::WaitingForCommonServices {
            return Err(HandoffError::UnexpectedPhase);
        }
        let pdu = bt_cl::decode_pdu(self.peer_version, packet)?;
        if pdu.opcode != bt_cl::COMMON_SERVICES {
            return Err(HandoffError::UnexpectedOpcode);
        }
        // Primary normal link must be the sole accepted service. Enforce the
        // complete body, including count, so trailing IDs cannot be ignored.
        if pdu.payload != bt_cl::common_services_payload(&[self.service.service_id])? {
            return Err(HandoffError::UnexpectedService);
        }
        let payload = bt_cl::create_channel_payload(CreateChannel {
            initiator_local_cid: self.local_cid,
            common_service_id: self.service.service_id,
        });
        let packet = bt_cl::encode_pdu(self.peer_version, bt_cl::CREATE_CHANNEL, &payload)?;
        self.phase = Phase::WaitingForAcceptChannel;
        Ok(packet)
    }
    pub fn accept_channel(&mut self, packet: &[u8]) -> Result<(), HandoffError> {
        if self.phase != Phase::WaitingForAcceptChannel {
            return Err(HandoffError::UnexpectedPhase);
        }
        let pdu = bt_cl::decode_pdu(self.peer_version, packet)?;
        if pdu.opcode != bt_cl::ACCEPT_CHANNEL {
            return Err(HandoffError::UnexpectedOpcode);
        }
        let accepted = bt_cl::parse_accept_channel(&pdu.payload)?;
        if accepted.status != 0 {
            return Err(HandoffError::Rejected(accepted.status));
        }
        if accepted.service_id != self.service.service_id {
            return Err(HandoffError::UnexpectedService);
        }
        if accepted.responder_local_cid < 0x0040 {
            return Err(HandoffError::InvalidDynamicCid);
        }
        self.remote_cid = Some(accepted.responder_local_cid);
        self.phase = Phase::PipeOpen;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const COMMON: &[u8] = &[2, 3, 0, 1, 2, 0];
    const ACCEPT: &[u8] = &[4, 5, 0, 0, 2, 0, 0x42, 0];
    #[test]
    fn same_normal_link_wire_sequence_as_java() {
        let mut state = NormalLinkHandoff::begin(11, 0x41, true).unwrap();
        let mut expected = vec![5, 0x1c, 0, 2, 0, 1, 0x17];
        expected.extend_from_slice(b"com.apple.terminusLink\0");
        expected.push(1);
        assert_eq!(state.advertise().unwrap(), expected);
        assert_eq!(state.advertise(), Err(HandoffError::UnexpectedPhase));
        assert_eq!(
            state.accept_common_services(COMMON).unwrap(),
            [3, 4, 0, 0x41, 0, 2, 0]
        );
        assert_eq!(state.channel_pair(), None);
        state.accept_channel(ACCEPT).unwrap();
        assert_eq!(state.channel_pair(), Some((0x41, 0x42)));
        assert_eq!(state.phase(), Phase::PipeOpen);
        assert_eq!(
            state.accept_channel(ACCEPT),
            Err(HandoffError::UnexpectedPhase)
        );
    }
    #[test]
    fn rejected_or_wrong_messages_never_commit_a_channel() {
        let mut state = NormalLinkHandoff::begin(11, 0x51, true).unwrap();
        assert_eq!(
            state.accept_channel(ACCEPT),
            Err(HandoffError::UnexpectedPhase)
        );
        state.advertise().unwrap();
        assert_eq!(
            state.accept_common_services(&[2, 3, 0, 1, 1, 0]),
            Err(HandoffError::UnexpectedService)
        );
        assert_eq!(state.phase(), Phase::WaitingForCommonServices);
        state.accept_common_services(COMMON).unwrap();
        for (packet, error) in [
            ([4, 5, 0, 3, 2, 0, 0x42, 0], HandoffError::Rejected(3)),
            ([4, 5, 0, 0, 1, 0, 0x42, 0], HandoffError::UnexpectedService),
            ([4, 5, 0, 0, 2, 0, 0x3f, 0], HandoffError::InvalidDynamicCid),
            ([3, 5, 0, 0, 2, 0, 0x42, 0], HandoffError::UnexpectedOpcode),
        ] {
            assert_eq!(state.accept_channel(&packet), Err(error));
            assert_eq!(state.channel_pair(), None);
            assert_eq!(state.phase(), Phase::WaitingForAcceptChannel);
        }
    }
    #[test]
    fn encryption_and_allocated_dynamic_cids_are_required() {
        assert!(matches!(
            NormalLinkHandoff::begin(11, 0x41, false),
            Err(HandoffError::EncryptionRequired)
        ));
        assert!(matches!(
            NormalLinkHandoff::begin(0, 0x41, true),
            Err(HandoffError::InvalidPeerVersion)
        ));
        assert!(matches!(
            NormalLinkHandoff::begin(11, 0x3a, true),
            Err(HandoffError::InvalidDynamicCid)
        ));
        assert!(matches!(
            NormalLinkHandoff::from_reconnected_channel(11, 0x51, 0x3a, true),
            Err(HandoffError::InvalidDynamicCid)
        ));
        let mut state = NormalLinkHandoff::from_reconnected_channel(11, 0x51, 0x52, true).unwrap();
        assert_eq!(state.channel_pair(), Some((0x51, 0x52)));
        assert_eq!(state.advertise(), Err(HandoffError::UnexpectedPhase));
    }
    #[test]
    fn legacy_peer_keeps_its_short_header() {
        let mut state = NormalLinkHandoff::begin(7, 0x61, true).unwrap();
        let packet = state.advertise().unwrap();
        assert_eq!(packet[0..2], [5, 28]);
        assert_eq!(
            state.accept_common_services(&[2, 3, 1, 2, 0]).unwrap(),
            [3, 4, 0x61, 0, 2, 0]
        );
        state.accept_channel(&[4, 5, 0, 2, 0, 0x62, 0]).unwrap();
        assert_eq!(state.channel_pair(), Some((0x61, 0x62)));
    }
}
