use crate::{
    ControlSession, ControlStage, PairingError, Result,
    pin::{PinChallenge, PinSession, PinStage},
};
use sha2::{Digest, Sha256};
use watch_protocol::framing::UikeStreamDecoder;
use zeroize::Zeroizing;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairingStage {
    ControlSaInit,
    ControlKeyExchange,
    ControlAuthentication,
    SelectingCode,
    PinRequired,
    DerivingPin,
    PinSaInit,
    PinKeyExchange,
    PinShare,
    PinConfirmation,
    PinAuthentication,
    PinAuthenticated,
}
pub struct PairingEngine {
    control: ControlSession,
    pin: Option<PinSession>,
    deriving: bool,
    setup: [u8; 12],
    decoder: UikeStreamDecoder,
    receipts: Vec<([u8; 32], Vec<Vec<u8>>)>,
}
impl PairingEngine {
    pub fn new(setup: [u8; 12]) -> Result<Self> {
        Ok(Self {
            control: ControlSession::new()?,
            pin: None,
            deriving: false,
            setup,
            decoder: UikeStreamDecoder::new(),
            receipts: Vec::new(),
        })
    }
    pub fn start(&self) -> Result<Vec<u8>> {
        self.control.start()
    }
    pub fn stage(&self) -> PairingStage {
        if self.deriving {
            return PairingStage::DerivingPin;
        }
        if let Some(pin) = &self.pin {
            return match pin.stage {
                PinStage::SaInit => PairingStage::PinSaInit,
                PinStage::AdditionalKeyExchange => PairingStage::PinKeyExchange,
                PinStage::Share => PairingStage::PinShare,
                PinStage::Confirmation => PairingStage::PinConfirmation,
                PinStage::Authentication => PairingStage::PinAuthentication,
                PinStage::Authenticated => PairingStage::PinAuthenticated,
            };
        }
        match self.control.stage() {
            ControlStage::SaInit => PairingStage::ControlSaInit,
            ControlStage::AdditionalKeyExchange => PairingStage::ControlKeyExchange,
            ControlStage::Authentication => PairingStage::ControlAuthentication,
            ControlStage::SelectingCode => PairingStage::SelectingCode,
            ControlStage::PinRequired => PairingStage::PinRequired,
        }
    }
    pub fn prepare_pin(&mut self) -> Result<PinChallenge> {
        if self.stage() != PairingStage::PinRequired {
            return Err(PairingError("No pending code challenge"));
        }
        let salt = Zeroizing::new(self.control.salt()?.to_vec());
        self.deriving = true;
        Ok(PinChallenge {
            salt,
            setup: self.setup,
        })
    }
    pub fn finish_pin_derivation(&mut self, pin: PinSession) -> Result<Vec<u8>> {
        if !self.deriving || self.pin.is_some() {
            return Err(PairingError("The code derivation was cancelled"));
        }
        let initial = pin.start()?;
        self.deriving = false;
        self.pin = Some(pin);
        Ok(initial)
    }
    pub fn receive(&mut self, bytes: &[u8]) -> Result<Vec<Vec<u8>>> {
        if bytes.len() > 2048 {
            return Err(PairingError("Pairing input chunk exceeds limit"));
        }
        let packets = self
            .decoder
            .push(bytes)
            .map_err(|_| PairingError("Malformed uIKE stream"))?;
        let mut out = Vec::new();
        for packet in packets {
            if packet.len() < 28 {
                return Err(PairingError("Truncated IKE packet"));
            }
            let fingerprint: [u8; 32] = Sha256::digest(&packet).into();
            if let Some((_, replies)) = self.receipts.iter().find(|(hash, _)| *hash == fingerprint)
            {
                out.extend(replies.clone());
                continue;
            }
            let replies = if packet[..8] == self.control.spi() {
                self.control.packet(&packet)?
            } else if let Some(pin) = &mut self.pin
                && packet[..8] == pin.spi
            {
                pin.packet(&packet)?
            } else {
                return Err(PairingError(
                    "IKE packet does not belong to the current pairing attempt",
                ));
            };
            if self.receipts.len() >= 128 {
                return Err(PairingError("Pairing packet limit exceeded"));
            }
            self.receipts.push((fingerprint, replies.clone()));
            out.extend(replies);
        }
        Ok(out)
    }
}
