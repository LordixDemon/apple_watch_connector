//! Portable uIKE pairing. Secret state stays here, outside the UI C ABI.
mod control;
mod crypto;
mod engine;
mod pin;
mod spake;
mod wire;
pub use control::{ControlSession, ControlStage};
pub use engine::{PairingEngine, PairingStage};
pub use pin::PinSession;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PairingError(pub &'static str);
impl std::fmt::Display for PairingError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.0)
    }
}
impl std::error::Error for PairingError {}
type Result<T> = std::result::Result<T, PairingError>;
#[cfg(test)]
mod tests;
