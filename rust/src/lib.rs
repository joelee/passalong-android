//! The bridge between the Android app and the passalong Rust core.
//!
//! Everything the app needs from `passalong-core` and `passalong-ssh` is
//! exported here through UniFFI: the setup steps `passalong init` performs
//! (host key discovery, an identity, the config file) and a store handle
//! with what `passalong choose` does (list, metadata, load, cat, delete).
//! Async functions become Kotlin `suspend` functions and run on the
//! bridge's tokio runtime; the app calls nothing here on the main thread.

uniffi::setup_scaffolding!();

#[cfg(target_os = "android")]
mod android_tls;
mod env;
mod error;
mod logging;
mod model;
mod setup;
mod store;

pub use env::AppEnv;
pub use error::PassalongError;
pub use logging::init_logging;
pub use model::{Item, kind_label};
pub use setup::{
    ConfigSummary, HostKey, PresentedCertificate, SetupAnswers, fetch_host_key, generate_identity,
    probe_server, render_config, validate_config,
};
pub use store::{CachedItems, PassalongStore, open_store};

/// The version of the passalong core this bridge is built on.
#[uniffi::export]
pub fn core_version() -> String {
    passalong_core::VERSION.to_owned()
}

/// The version of this bridge crate.
#[uniffi::export]
pub fn bridge_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[cfg(test)]
mod tests {
    #[test]
    fn versions_are_the_pinned_ones() {
        assert_eq!(super::core_version(), "0.3.0");
        assert_eq!(super::core_version(), passalong_ssh::VERSION);
        assert_eq!(super::core_version(), passalong_https::VERSION);
        assert_eq!(super::bridge_version(), env!("CARGO_PKG_VERSION"));
    }
}
