//! Public OTA observations. A transport receipt or UpdateInstalled (pre-reboot)
//! never proves that the Watch booted the target system.
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Phase {
    Unknown,
    Idle,
    Checking,
    Available,
    DownloadingPreparing,
    WaitingPreparation,
    Prepared,
    WaitingInstallation,
    Installing,
    VerifyingRestart,
    Complete,
    Failed,
}

impl Phase {
    pub fn from_manager_state(state: u8) -> Option<Self> {
        Some(match state {
            1 => Self::Idle,
            2 => Self::Checking,
            3 => Self::Available,
            4 => Self::DownloadingPreparing,
            5 => Self::WaitingPreparation,
            6 => Self::Prepared,
            7 | 8 => Self::WaitingInstallation,
            9 => Self::Installing,
            10 => Self::VerifyingRestart,
            _ => return None,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Observation {
    pub pair_id: String,
    pub epoch: String,
    pub known: bool,
    pub phase: Phase,
    pub observed_at: u64,
    pub scan_completed: bool,
    pub install_attempted: bool,
    pub target_version: Option<String>,
    pub target_build: Option<String>,
    pub installed_version: Option<String>,
    pub installed_build: Option<String>,
    pub download_bytes: Option<u64>,
    pub required_space_bytes: Option<u64>,
    pub progress: Option<f64>,
    pub stalled: bool,
    pub terms_accepted: bool,
    pub error_code: Option<i64>,
}

impl Observation {
    pub fn valid(&self) -> bool {
        fn uuid(s: &str) -> bool {
            s.len() == 36
                && s.bytes().enumerate().all(|(i, c)| {
                    if [8, 13, 18, 23].contains(&i) {
                        c == b'-'
                    } else {
                        c.is_ascii_hexdigit()
                    }
                })
        }
        fn version(s: &str) -> bool {
            !s.is_empty()
                && s.len() <= 32
                && s.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'.')
        }
        uuid(&self.pair_id)
            && uuid(&self.epoch)
            && (!self.known || self.observed_at > 0)
            && self
                .progress
                .is_none_or(|p| p.is_finite() && (0.0..=1.0).contains(&p))
            && [
                &self.target_version,
                &self.target_build,
                &self.installed_version,
                &self.installed_build,
            ]
            .iter()
            .all(|v| v.as_ref().is_none_or(|s| version(s)))
            && (self.phase != Phase::Complete
                || (self.known
                    && self.target_build.is_some()
                    && self.target_build == self.installed_build))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn native_installed_is_only_a_restart_barrier() {
        assert_eq!(Phase::from_manager_state(10), Some(Phase::VerifyingRestart));
        assert_eq!(Phase::from_manager_state(255), None);
        assert_eq!(Phase::from_manager_state(0), None);
    }
    #[test]
    fn complete_requires_a_matching_observed_build() {
        let mut state = Observation {
            pair_id: "11111111-1111-4111-8111-111111111111".into(),
            epoch: "22222222-2222-4222-8222-222222222222".into(),
            known: true,
            phase: Phase::Complete,
            observed_at: 1,
            scan_completed: false,
            install_attempted: false,
            target_version: Some("27.0.1".into()),
            target_build: Some("24R365".into()),
            installed_version: Some("26.2".into()),
            installed_build: Some("23S303".into()),
            download_bytes: None,
            required_space_bytes: None,
            progress: None,
            stalled: false,
            terms_accepted: false,
            error_code: None,
        };
        assert!(!state.valid());
        state.installed_build = state.target_build.clone();
        assert!(state.valid());
        state.progress = Some(f64::NAN);
        assert!(!state.valid());
    }
}
