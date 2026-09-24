//! The one error type the app sees, with the core's messages kept intact:
//! they already say what is wrong and how to fix it.

use passalong_core::download::DownloadError;
use passalong_core::store::StoreError;
use passalong_ssh::error::SshError;

/// What went wrong, grouped by what the app can do about it.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum PassalongError {
    /// The configuration is missing, invalid, or names something unusable.
    #[error("{0}")]
    Config(String),
    /// The server cannot be reached, refuses the login, or presents another
    /// host key than the pinned one.
    #[error("{0}")]
    Connect(String),
    /// No item has this id (any more).
    #[error("{0}")]
    NotFound(String),
    /// The store is encrypted and this device cannot open it, or its
    /// encryption is in a state that needs `passalong encrypt`.
    #[error("{0}")]
    Encryption(String),
    /// A passalong-server refused this device for good: its API key is
    /// wrong, expired, or revoked. Only a new key from the operator helps.
    #[error("{0}")]
    Denied(String),
    /// Any other storage problem, including an item that fails its SHA-256
    /// check.
    #[error("{0}")]
    Store(String),
    /// A file on this device cannot be read or written.
    #[error("{0}")]
    Io(String),
}

impl From<StoreError> for PassalongError {
    fn from(err: StoreError) -> Self {
        match err {
            StoreError::NotFound(_) => Self::NotFound(err.to_string()),
            StoreError::Config(_) | StoreError::UnsupportedBackend(_) => {
                Self::Config(err.to_string())
            }
            StoreError::Encryption(_) => Self::Encryption(err.to_string()),
            StoreError::Backend(_) => Self::Connect(err.to_string()),
            StoreError::Denied(_) => Self::Denied(err.to_string()),
            _ => Self::Store(err.to_string()),
        }
    }
}

impl From<SshError> for PassalongError {
    fn from(err: SshError) -> Self {
        Self::Connect(err.to_string())
    }
}

impl From<DownloadError> for PassalongError {
    fn from(err: DownloadError) -> Self {
        match err {
            DownloadError::Create { .. } | DownloadError::Write { .. } => Self::Io(chain(&err)),
            _ => Self::Store(chain(&err)),
        }
    }
}

impl From<passalong_core::config::ConfigError> for PassalongError {
    fn from(err: passalong_core::config::ConfigError) -> Self {
        Self::Config(err.to_string())
    }
}

/// The error and its sources, joined, since the app shows one line.
fn chain(err: &dyn std::error::Error) -> String {
    let mut text = err.to_string();
    let mut source = err.source();
    while let Some(cause) = source {
        text.push_str(": ");
        text.push_str(&cause.to_string());
        source = cause.source();
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    type Wrap = fn(String) -> PassalongError;

    #[test]
    fn store_errors_are_grouped_by_what_the_app_can_do() {
        let cases: [(StoreError, Wrap); 6] = [
            (
                StoreError::NotFound("abcd".into()),
                PassalongError::NotFound,
            ),
            (StoreError::Denied("revoked".into()), PassalongError::Denied),
            (StoreError::Config("x".into()), PassalongError::Config),
            (
                StoreError::UnsupportedBackend("s3".into()),
                PassalongError::Config,
            ),
            (StoreError::Backend("down".into()), PassalongError::Connect),
            (StoreError::Content("short".into()), PassalongError::Store),
        ];
        for (err, expected) in cases {
            let message = err.to_string();
            assert_eq!(PassalongError::from(err), expected(message));
        }
        let ssh = SshError::Timeout {
            address: "h:22".into(),
            secs: 3,
        };
        assert_eq!(
            PassalongError::from(ssh),
            PassalongError::Connect("connecting to h:22 timed out after 3 s".into())
        );
    }

    #[test]
    fn download_errors_carry_their_causes() {
        let err = DownloadError::Write {
            path: "/tmp/x".into(),
            source: std::io::Error::other("disk full"),
        };
        assert_eq!(
            PassalongError::from(err),
            PassalongError::Io("cannot write /tmp/x: disk full".into())
        );
        let err = DownloadError::Read(std::io::Error::other("reset"));
        assert!(
            matches!(PassalongError::from(err), PassalongError::Store(m) if m == "reading the item: reset")
        );
    }
}
