//! Versioned desktop C ABI. Platform composition keeps controller ownership isolated.
#[cfg(target_os = "linux")]
mod linux;
#[cfg(not(any(target_os = "linux", target_os = "windows")))]
mod runtime;
#[cfg(target_os = "windows")]
mod windows;
