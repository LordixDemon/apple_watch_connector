//! CryptoKitPrivate seed constructor, followed by RFC 9383 P-256 SPAKE2+.
use crate::{PairingError, Result, crypto};
use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use p256::{
    AffinePoint, EncodedPoint, ProjectivePoint, Scalar,
    elliptic_curve::{
        Field, Group, PrimeField,
        bigint::{Encoding, NonZero, U384},
        sec1::{FromEncodedPoint, ToEncodedPoint},
    },
};
use sha2::{Digest, Sha256};
use zeroize::{Zeroize, ZeroizeOnDrop, Zeroizing};

const M: [u8; 33] = [
    0x02, 0x88, 0x6e, 0x2f, 0x97, 0xac, 0xe4, 0x6e, 0x55, 0xba, 0x9d, 0xd7, 0x24, 0x25, 0x79, 0xf2,
    0x99, 0x3b, 0x64, 0xe1, 0x6e, 0xf3, 0xdc, 0xab, 0x95, 0xaf, 0xd4, 0x97, 0x33, 0x3d, 0x8f, 0xa1,
    0x2f,
];
const N: [u8; 33] = [
    0x03, 0xd8, 0xbb, 0xd6, 0xc6, 0x39, 0xc6, 0x29, 0x37, 0xb0, 0x4d, 0x99, 0x7f, 0x38, 0xc3, 0x77,
    0x07, 0x19, 0xc6, 0x29, 0xd7, 0x01, 0x4d, 0x49, 0xa2, 0x4b, 0x4f, 0x98, 0xba, 0xa1, 0x29, 0x2b,
    0x49,
];
pub(crate) fn hkdf256(seed: &[u8], salt: &[u8], info: &[u8], length: usize) -> Zeroizing<Vec<u8>> {
    let mut out = Zeroizing::new(vec![0; length]);
    Hkdf::<Sha256>::new(Some(salt), seed)
        .expand(info, &mut out)
        .expect("Bounded HKDF output");
    out
}
fn hmac256(key: &[u8], bytes: &[u8]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).unwrap();
    mac.update(bytes);
    mac.finalize().into_bytes().into()
}
fn point(bytes: &[u8]) -> Result<ProjectivePoint> {
    let encoded = EncodedPoint::from_bytes(bytes)
        .map_err(|_| PairingError("Invalid SPAKE2+ point encoding"))?;
    let affine = Option::<AffinePoint>::from(AffinePoint::from_encoded_point(&encoded))
        .ok_or(PairingError("SPAKE2+ point is not on P-256"))?;
    let result = ProjectivePoint::from(affine);
    if bool::from(result.is_identity()) {
        return Err(PairingError("SPAKE2+ point is the identity"));
    }
    Ok(result)
}
fn encoded(point: &ProjectivePoint) -> Result<[u8; 65]> {
    if bool::from(point.is_identity()) {
        return Err(PairingError("SPAKE2+ secret is the identity"));
    }
    Ok(point
        .to_affine()
        .to_encoded_point(false)
        .as_bytes()
        .try_into()
        .unwrap())
}
fn reduce_legacy(bytes: &[u8]) -> Scalar {
    // Apple's 40-byte expansion uses modulo (order - 1), then adds one.
    // crypto-bigint's remainder is constant-time with respect to the dividend.
    let mut padded = Zeroizing::new([0; 48]);
    padded[8..].copy_from_slice(bytes);
    let value = Zeroizing::new(U384::from_be_slice(&*padded));
    let modulus = NonZero::new(U384::from_be_hex("00000000000000000000000000000000ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550")).unwrap();
    let reduced = Zeroizing::new((*value % modulus).wrapping_add(&U384::ONE).to_be_bytes());
    Scalar::from_repr(<[u8; 32]>::try_from(&reduced[16..]).unwrap().into()).unwrap()
}
pub(crate) fn legacy_scalars(seed: &[u8], salt: &[u8]) -> (Scalar, Scalar) {
    let expanded = hkdf256(seed, salt, b"SPAKE2+ Authentication for IKEv2", 80);
    (
        reduce_legacy(&expanded[..40]),
        reduce_legacy(&expanded[40..]),
    )
}
#[derive(Zeroize, ZeroizeOnDrop)]
pub(crate) struct Prover {
    w0: Scalar,
    w1: Scalar,
    ephemeral: Scalar,
    context: Vec<u8>,
    idi: Vec<u8>,
    idr: Vec<u8>,
    pub share: [u8; 65],
    expected: Option<[u8; 32]>,
    shared: Option<[u8; 32]>,
}
impl Prover {
    pub fn new(seed: &[u8], salt: &[u8], context: &[u8], idi: &[u8], idr: &[u8]) -> Result<Self> {
        let (w0, w1) = legacy_scalars(seed, salt);
        let ephemeral = loop {
            let candidate = Zeroizing::new(crypto::random::<32>()?);
            if let Some(value) = Option::<Scalar>::from(Scalar::from_repr((*candidate).into()))
                && !bool::from(value.is_zero())
            {
                break value;
            }
        };
        Self::from_scalars(w0, w1, ephemeral, context, idi, idr)
    }
    pub(crate) fn from_scalars(
        w0: Scalar,
        w1: Scalar,
        ephemeral: Scalar,
        context: &[u8],
        idi: &[u8],
        idr: &[u8],
    ) -> Result<Self> {
        let share = encoded(&(ProjectivePoint::GENERATOR * ephemeral + point(&M)? * w0))?;
        Ok(Self {
            w0,
            w1,
            ephemeral,
            context: context.to_vec(),
            idi: idi.to_vec(),
            idr: idr.to_vec(),
            share,
            expected: None,
            shared: None,
        })
    }
    pub fn peer_share(&mut self, bytes: &[u8]) -> Result<[u8; 32]> {
        if self.expected.is_some() {
            return Err(PairingError("SPAKE2+ peer share already processed"));
        }
        if bytes.len() != 65 || bytes[0] != 4 {
            return Err(PairingError("Expected uncompressed SPAKE2+ share"));
        }
        let peer = point(bytes)?;
        let adjusted = peer - point(&N)? * self.w0;
        let z = Zeroizing::new(encoded(&(adjusted * self.ephemeral))?);
        let v = Zeroizing::new(encoded(&(adjusted * self.w1))?);
        let w0 = Zeroizing::new(self.w0.to_bytes());
        let m = encoded(&point(&M)?)?;
        let n = encoded(&point(&N)?)?;
        let mut transcript = Zeroizing::new(Vec::new());
        for entry in [
            &self.context[..],
            &self.idi,
            &self.idr,
            &m,
            &n,
            &self.share,
            bytes,
            &*z,
            &*v,
            &w0,
        ] {
            transcript.extend_from_slice(&(entry.len() as u64).to_le_bytes());
            transcript.extend_from_slice(entry);
        }
        let main = Zeroizing::new(<[u8; 32]>::from(Sha256::digest(&*transcript)));
        let confirmation = hkdf256(&*main, &[], b"ConfirmationKeys", 64);
        self.expected = Some(hmac256(&confirmation[32..], &self.share));
        self.shared = Some(
            hkdf256(&*main, &[], b"SharedKey", 32)[..]
                .try_into()
                .unwrap(),
        );
        Ok(hmac256(&confirmation[..32], bytes))
    }
    pub fn finish(&mut self, confirmation: &[u8]) -> Result<Zeroizing<[u8; 32]>> {
        let expected = self
            .expected
            .take()
            .ok_or(PairingError("SPAKE2+ share is missing"))?;
        let expected = Zeroizing::new(expected);
        let result = crypto::verify_auth(confirmation, &*expected);
        let shared = Zeroizing::new(
            self.shared
                .take()
                .ok_or(PairingError("SPAKE2+ key is missing"))?,
        );
        self.zeroize();
        result?;
        Ok(shared)
    }
}
