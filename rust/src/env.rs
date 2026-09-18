//! The environment the core sees on Android.
//!
//! An Android app has no `HOME` and no meaningful host name, but the core's
//! configuration expands `~` and defaults `client.device_name` from them.
//! [`AppEnv`] gives the core the app's private files directory as its home,
//! so `~/Downloads`, `~/PassAlong`, and the key file all land inside the
//! app's storage, and the name the app chooses as the host name. It never
//! reads the process environment, so nothing outside the app leaks in.

use passalong_core::config::EnvProvider;

/// [`EnvProvider`] for the app: a fixed home directory and host name.
#[derive(Debug, Clone)]
pub struct AppEnv {
    home: String,
    hostname: String,
}

impl AppEnv {
    /// An environment whose `HOME` is `home` and whose host name is
    /// `hostname`.
    pub fn new(home: impl Into<String>, hostname: impl Into<String>) -> Self {
        Self {
            home: home.into(),
            hostname: hostname.into(),
        }
    }
}

impl EnvProvider for AppEnv {
    fn var(&self, key: &str) -> Option<String> {
        (key == "HOME").then(|| self.home.clone())
    }

    fn hostname(&self) -> String {
        self.hostname.clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_home_is_set_and_the_hostname_is_the_given_one() {
        let env = AppEnv::new("/data/app", "pixel");
        assert_eq!(env.var("HOME").as_deref(), Some("/data/app"));
        assert_eq!(env.var("PASSALONG_SSH_KEY_PASSPHRASE"), None);
        assert_eq!(env.var("XDG_CONFIG_HOME"), None);
        assert_eq!(env.hostname(), "pixel");
    }
}
