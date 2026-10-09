//! Linux composition: Rust owns worker/session lifetime and the HCI broker;
//! the shared JVM engine supplies the verified Watch protocol.
#[cfg(not(target_os = "windows"))]
mod adapters;
#[cfg(target_os = "windows")]
#[path = "windows_adapters.rs"]
mod adapters;
mod backend;
mod state;
#[cfg(all(test, not(target_os = "windows")))]
#[path = "windows_adapters.rs"]
mod windows_adapters;
#[cfg(any(test, target_os = "windows"))]
mod windows_usb_backend;
mod worker;

#[cfg(target_os = "windows")]
pub mod usb;

pub use backend::LinuxBackend;
pub use backend::LinuxBackend as DesktopProtocolBackend;
