use crate::{
    PairingError, Result,
    control::{frame, frames},
    crypto::{self, Fragments, Keys},
    spake::Prover,
    wire::{self, SaResponse},
};
use hkdf::Hkdf;
use ml_kem::{Decapsulate, KeyExport, ml_kem_1024::DecapsulationKey};
use sha2::{Sha256, Sha512};
use zeroize::Zeroizing;

const SALTED_PIN_ID: &[u8] = b"com.apple.networkrelay.companionlink.pairing.auth.saltedPin";
const IDI: [u8; 4] = [13, 0, 0, 0];
fn idr() -> Vec<u8> {
    [&[11, 0, 0, 0][..], SALTED_PIN_ID].concat()
}

/// Owned challenge for a CPU worker. It cannot expose the salt or binding to UI.
pub struct PinChallenge {
    pub(crate) salt: Zeroizing<Vec<u8>>,
    pub(crate) setup: [u8; 12],
}
impl PinChallenge {
    /// Consumes and clears the caller's code, including malformed input.
    /// PBKDF2 intentionally runs outside the UI/core mutex.
    pub fn derive(self, pin: Zeroizing<Vec<u8>>) -> Result<PinSession> {
        if pin.len() != 6 || !pin.iter().all(u8::is_ascii_digit) {
            return Err(PairingError("Watch code must contain six digits"));
        }
        let numeric = pin
            .iter()
            .fold(0u32, |value, byte| value * 10 + (byte - b'0') as u32);
        let authentication = Zeroizing::new(numeric.to_be_bytes());
        let mut pin_key = Zeroizing::new([0; 32]);
        pbkdf2::pbkdf2_hmac::<Sha256>(&*authentication, &self.salt, 1_600_000, &mut *pin_key);
        let mut seed = Zeroizing::new([0; 44]);
        seed[..32].copy_from_slice(&*pin_key);
        seed[32..].copy_from_slice(&self.setup);
        let mut ppk = Zeroizing::new([0; 64]);
        Hkdf::<Sha512>::new(Some(&self.salt), &*seed)
            .expand(b"Derived IKEv2 PPK for terminus device pairing", &mut *ppk)
            .unwrap();
        PinSession::new(seed, ppk, authentication)
    }
}
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum PinStage {
    SaInit,
    AdditionalKeyExchange,
    Share,
    Confirmation,
    Authentication,
    Authenticated,
}
/// Separate mandatory-PPK SA created only after a verified control PIN challenge.
pub struct PinSession {
    pub(crate) stage: PinStage,
    pub(crate) spi: [u8; 8],
    nonce: [u8; 32],
    secret: Zeroizing<[u8; 56]>,
    initial: Vec<u8>,
    peer: Option<SaResponse>,
    pub(crate) keys: Option<Keys>,
    kem: Option<DecapsulationKey>,
    seed: Zeroizing<[u8; 44]>,
    ppk: Zeroizing<[u8; 64]>,
    _authentication: Zeroizing<[u8; 4]>,
    int_i: Zeroizing<[u8; 64]>,
    int_r: Zeroizing<[u8; 64]>,
    fragments: Fragments,
    prover: Option<Prover>,
    share_i: [u8; 65],
    share_r: [u8; 65],
    shared: Option<Zeroizing<[u8; 32]>>,
}
impl PinSession {
    #[cfg(test)]
    pub(crate) fn test_seed(&self) -> &[u8] {
        &*self.seed
    }
    #[cfg(test)]
    pub(crate) fn test_ppk(&self) -> &[u8] {
        &*self.ppk
    }
    fn new(
        seed: Zeroizing<[u8; 44]>,
        ppk: Zeroizing<[u8; 64]>,
        authentication: Zeroizing<[u8; 4]>,
    ) -> Result<Self> {
        let mut spi = crypto::random()?;
        if spi == [0; 8] {
            spi[7] = 1;
        }
        let nonce = crypto::random()?;
        let secret = Zeroizing::new(crypto::random()?);
        let public = x448::x448(*secret, x448::X448_BASEPOINT_BYTES).unwrap();
        let initial = wire::sa_init(&spi, &nonce, &public, true);
        Ok(Self {
            stage: PinStage::SaInit,
            spi,
            nonce,
            secret,
            initial,
            peer: None,
            keys: None,
            kem: None,
            seed,
            ppk,
            _authentication: authentication,
            int_i: Zeroizing::new([0; 64]),
            int_r: Zeroizing::new([0; 64]),
            fragments: Fragments::default(),
            prover: None,
            share_i: [0; 65],
            share_r: [0; 65],
            shared: None,
        })
    }
    pub(crate) fn start(&self) -> Result<Vec<u8>> {
        frame(&self.initial)
    }
    pub(crate) fn packet(&mut self, packet: &[u8]) -> Result<Vec<Vec<u8>>> {
        match self.stage {
            PinStage::SaInit => {
                let peer = wire::parse_sa_response(packet, &self.spi, true)?;
                let mut keys = Keys::initial(&self.secret, self.spi, self.nonce, &peer)?;
                let seed = Zeroizing::new(crypto::random::<64>()?);
                let kem = DecapsulationKey::from_seed((*seed).into());
                let mut body = vec![0, 37, 0, 0];
                body.extend_from_slice(&kem.encapsulation_key().to_bytes());
                let plain = wire::payload(0, &body);
                *self.int_i = keys.int_auth(false, 34, 0, &plain);
                let packets = keys.seal(43, 1, 8, 34, &plain)?;
                self.peer = Some(peer);
                self.keys = Some(keys);
                self.kem = Some(kem);
                self.stage = PinStage::AdditionalKeyExchange;
                frames(packets)
            }
            PinStage::AdditionalKeyExchange => {
                let keys = self.keys.as_ref().unwrap();
                let part = keys.decrypt(packet, 43, 1, 32)?;
                let Some((first, plain)) = self.fragments.push(part)? else {
                    return Ok(Vec::new());
                };
                let ciphertext = wire::parse_ke(first, &plain)?;
                *self.int_r = keys.int_auth(true, first, 0, &plain);
                let kem = self.kem.take().unwrap();
                let mut shared = kem
                    .decapsulate_slice(ciphertext)
                    .map_err(|_| PairingError("Invalid pairing ML-KEM ciphertext"))?;
                let mut updated = keys.update(&shared)?;
                zeroize::Zeroize::zeroize(&mut shared);
                updated.d = crypto::prf(&*self.ppk, &[&updated.d, &[1]]);
                updated.pi = crypto::prf(&*self.ppk, &[&updated.pi, &[1]]);
                updated.pr = crypto::prf(&*self.ppk, &[&updated.pr, &[1]]);
                let salt = [updated.ni.as_slice(), updated.nr.as_slice()].concat();
                let context = [updated.spi_i.as_slice(), updated.spi_r.as_slice()].concat();
                let prover = Prover::new(&*self.seed, &salt, &context, &IDI, &idr())?;
                self.share_i = prover.share;
                let plain = [
                    wire::payload(41, &IDI),
                    wire::notify(36, 0x4000, &[]),
                    wire::payload(49, &idr()),
                    wire::payload(41, &self.share_i),
                    wire::notify(0, 0x4034, &[1]),
                ]
                .concat();
                let packets = updated.seal(35, 2, 8, 35, &plain)?;
                self.prover = Some(prover);
                self.keys = Some(updated);
                self.stage = PinStage::Share;
                frames(packets)
            }
            PinStage::Share => {
                let part = self.keys.as_ref().unwrap().decrypt(packet, 35, 2, 32)?;
                if part.count != 0 || part.first != 36 {
                    return Err(PairingError("Unexpected pairing share response"));
                }
                let parts = wire::parse_payloads(part.first, &part.plaintext)?;
                if !(2..=3).contains(&parts.len())
                    || parts[0].body != idr()
                    || parts[1].kind != 49
                    || parts[1].body.len() != 65
                    || parts.iter().any(|p| p.flags != 0)
                    || (parts.len() == 3
                        && (parts[2].kind != 41 || parts[2].body != [0, 0, 0x40, 0x34, 1]))
                {
                    return Err(PairingError("Invalid salted-PIN responder share"));
                }
                self.share_r.copy_from_slice(parts[1].body);
                let confirmation =
                    Zeroizing::new(self.prover.as_mut().unwrap().peer_share(&self.share_r)?);
                let plain = Zeroizing::new(wire::payload(0, &*confirmation));
                let packets = self.keys.as_mut().unwrap().seal(35, 3, 8, 49, &plain)?;
                self.stage = PinStage::Confirmation;
                frames(packets)
            }
            PinStage::Confirmation => {
                let part = self.keys.as_ref().unwrap().decrypt(packet, 35, 3, 32)?;
                let parts = wire::parse_payloads(part.first, &part.plaintext)?;
                if part.count != 0
                    || parts.len() != 1
                    || parts[0].kind != 49
                    || parts[0].flags != 0
                    || parts[0].body.len() != 32
                {
                    return Err(PairingError("Invalid SPAKE2+ confirmation response"));
                }
                self.shared = Some(self.prover.as_mut().unwrap().finish(parts[0].body)?);
                self.prover = None;
                let auth = Zeroizing::new(self.authentication(false)?);
                let mut body = Zeroizing::new(vec![12, 0, 0, 0]);
                body.extend_from_slice(&*auth);
                let plain = Zeroizing::new(
                    [wire::payload(41, &body), wire::notify(0, 0x4034, &[1])].concat(),
                );
                let packets = self.keys.as_mut().unwrap().seal(35, 4, 8, 39, &plain)?;
                self.stage = PinStage::Authentication;
                frames(packets)
            }
            PinStage::Authentication => {
                let part = self.keys.as_ref().unwrap().decrypt(packet, 35, 4, 32)?;
                let parts = wire::parse_payloads(part.first, &part.plaintext)?;
                if part.count != 0
                    || parts.len() != 3
                    || parts.iter().any(|p| p.flags != 0)
                    || parts[0].kind != 41
                    || parts[0].body != [0, 0, 0x40, 0]
                    || parts[1].kind != 39
                    || parts[1].body.len() != 68
                    || parts[1].body[..4] != [12, 0, 0, 0]
                    || parts[2].kind != 41
                    || parts[2].body != [0, 0, 0x40, 0x34]
                {
                    return Err(PairingError(
                        "Invalid mandatory-PPK authentication response",
                    ));
                }
                let expected = Zeroizing::new(self.authentication(true)?);
                crypto::verify_auth(&parts[1].body[4..], &*expected)?;
                self.shared = None;
                self.stage = PinStage::Authenticated;
                Ok(Vec::new())
            }
            PinStage::Authenticated => Err(PairingError(
                "Bluetooth bond handoff is required before continuing",
            )),
        }
    }
    fn authentication(&self, responder: bool) -> Result<[u8; 64]> {
        let keys = self.keys.as_ref().unwrap();
        let prime = if responder { &keys.pr } else { &keys.pi };
        let body = if responder { idr() } else { IDI.to_vec() };
        let id_auth = Zeroizing::new(crypto::prf(prime, &[&body]));
        let ordered: &[&[u8]] = if responder {
            &[&self.share_r, &self.share_i]
        } else {
            &[&self.share_i, &self.share_r]
        };
        let gspm = Zeroizing::new(crypto::prf(prime, ordered));
        let key = self
            .shared
            .as_ref()
            .ok_or(PairingError("SPAKE2+ key is missing"))?;
        Ok(crypto::prf(
            &**key,
            &[
                if responder {
                    &self.peer.as_ref().unwrap().packet
                } else {
                    &self.initial
                },
                if responder { &keys.ni } else { &keys.nr },
                &*id_auth,
                &*self.int_i,
                &*self.int_r,
                &2u32.to_be_bytes(),
                &*gspm,
            ],
        ))
    }
}
