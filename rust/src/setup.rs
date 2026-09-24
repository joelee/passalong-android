//! What `passalong init` does, for the app's setup screen: discover the
//! server's host key, make this device an identity, and write the config.

use std::path::{Path, PathBuf};
use std::time::Duration;

use passalong_core::api_key::{ApiKey, load_api_key, save_api_key as save_api_key_file};
use passalong_core::config::{self, HttpsInitAnswers, InitAnswers, TlsPin};
use passalong_core::encryption::{SystemGit, load_key_file};
use passalong_https::api::{EncryptionState, Role};
use russh::keys::ssh_key::rand_core::{TryCryptoRng, TryRng};
use russh::keys::ssh_key::{Algorithm, LineEnding, PrivateKey};

use crate::env::AppEnv;
use crate::error::PassalongError;

/// A server host key seen during discovery, not yet trusted.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HostKey {
    /// The key as an OpenSSH `type base64` line, ready for
    /// `server.ssh.host_key`.
    pub openssh_line: String,
    /// SHA-256 fingerprint, as `ssh-keygen -l` prints it, for the user to
    /// compare with the server's own.
    pub fingerprint: String,
    /// Algorithm name, such as `ssh-ed25519`.
    pub algorithm: String,
}

/// Fetches the host key `host:port` presents, without trusting it and
/// without logging in, giving up after `timeout_secs`.
#[uniffi::export(async_runtime = "tokio")]
pub async fn fetch_host_key(
    host: String,
    port: u16,
    timeout_secs: u64,
) -> Result<HostKey, PassalongError> {
    let found =
        passalong_ssh::fetch_host_key(&host, port, Duration::from_secs(timeout_secs)).await?;
    Ok(HostKey {
        openssh_line: found.openssh_line,
        fingerprint: found.fingerprint,
        algorithm: found.algorithm,
    })
}

/// The certificate a passalong-server presents, seen with a handshake that
/// stops before any request, so nothing, and no API key, reaches a server
/// the user has not confirmed.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PresentedCertificate {
    /// The pin of its public key, `sha256/<base64>`, as
    /// `passalong-server tls fingerprint` prints it.
    pub pin: String,
    /// Whether the operating system's certificate authorities vouch for the
    /// certificate and the name; on Android, through the system trust store.
    pub trusted: bool,
}

/// Fetches the certificate the server at `url` presents, as `passalong init`
/// does before showing its pin. Plain `http://` is refused.
#[uniffi::export(async_runtime = "tokio")]
pub async fn probe_server(url: String) -> Result<PresentedCertificate, PassalongError> {
    let presented = passalong_https::tls::presented(url.trim()).await?;
    tracing::info!(trusted = presented.trusted, "server certificate probed");
    Ok(PresentedCertificate {
        pin: presented.pin.to_string(),
        trusted: presented.trusted,
    })
}

/// The values the setup screen collects, as `passalong init` asks them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct SetupAnswers {
    /// `client.device_name`.
    pub device_name: String,
    /// `server.ssh.host`.
    pub host: String,
    /// `server.ssh.port`.
    pub port: u16,
    /// `server.ssh.user`.
    pub user: String,
    /// `server.ssh.host_key`, the confirmed OpenSSH key line.
    pub host_key: String,
    /// `server.ssh.identity_file`, the private key [`generate_identity`]
    /// wrote.
    pub identity_file: String,
    /// `server.ssh.remote_path`.
    pub remote_path: String,
}

/// Renders the complete, commented config file `passalong init` writes.
#[uniffi::export]
pub fn render_config(answers: SetupAnswers) -> String {
    config::render(&InitAnswers {
        device_name: answers.device_name,
        host: answers.host,
        port: answers.port,
        user: answers.user,
        host_key: answers.host_key,
        identity_file: answers.identity_file,
        remote_path: answers.remote_path,
    })
}

/// What a valid config says, for the app's screens.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ConfigSummary {
    /// `client.device_name`.
    pub device_name: String,
    /// `server.kind`.
    pub server_kind: String,
    /// `server.ssh.host`, empty for other backends.
    pub host: String,
    /// `server.ssh.port`, 0 for other backends.
    pub port: u16,
    /// `server.ssh.user`, empty for other backends.
    pub user: String,
    /// `server.ssh.remote_path`, or `server.local.path`.
    pub remote_path: String,
    /// `server.ssh.identity_file`, absolute, empty for other backends.
    pub identity_file: String,
    /// `client.download_dir`, absolute.
    pub download_dir: String,
    /// `client.key_file`, absolute, where an encrypted store's key would be.
    pub key_file: Option<String>,
}

/// Parses and validates `config_toml` as the core does, with `home` as the
/// app's home directory and `hostname` as the default device name, without
/// connecting to anything.
#[uniffi::export]
pub fn validate_config(
    config_toml: String,
    home: String,
    hostname: String,
) -> Result<ConfigSummary, PassalongError> {
    let env = AppEnv::new(home, hostname);
    let config = config::parse(&config_toml, Path::new("config.toml"), &env)?;
    let ssh = config.server.ssh.as_ref();
    let shown = |path: &Path| path.display().to_string();
    Ok(ConfigSummary {
        device_name: config.client.device_name.clone(),
        server_kind: config.server.kind.clone(),
        host: ssh.map(|s| s.host.clone()).unwrap_or_default(),
        port: ssh.map(|s| s.port).unwrap_or_default(),
        user: ssh.map(|s| s.user.clone()).unwrap_or_default(),
        remote_path: ssh
            .map(|s| s.remote_path.clone())
            .or_else(|| config.server.local.as_ref().map(|l| shown(&l.path)))
            .unwrap_or_default(),
        identity_file: ssh.map(|s| shown(&s.identity_file)).unwrap_or_default(),
        download_dir: shown(&config.client.download_dir),
        key_file: config.client.key_file.as_deref().map(shown),
    })
}

/// Makes this device an ed25519 identity: the private key is written to
/// `path`, readable by the app only, and the public key beside it as
/// `<path>.pub`, both in OpenSSH format. Returns the public key line, which
/// goes into the server's `authorized_keys`. An existing key at `path` is
/// kept unless `replace` is set, since the server may trust it already.
#[uniffi::export]
pub fn generate_identity(
    path: String,
    comment: String,
    replace: bool,
) -> Result<String, PassalongError> {
    let path = Path::new(&path);
    let io = |what: &str, err: std::io::Error| PassalongError::Io(format!("{what}: {err}"));
    if !replace && path.exists() {
        return Err(PassalongError::Io(format!(
            "{} already exists; keep it, or replace it and add the new public key to the server",
            path.display()
        )));
    }
    if let Some(parent) = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
    {
        std::fs::create_dir_all(parent)
            .map_err(|err| io(&format!("cannot create {}", parent.display()), err))?;
    }
    let mut key = PrivateKey::random(&mut SysRng, Algorithm::Ed25519)
        .map_err(|err| PassalongError::Io(format!("cannot generate a key: {err}")))?;
    key.set_comment(comment);
    // Both writers create the file with mode 0600 on Unix.
    key.write_openssh_file(path, LineEnding::LF)
        .map_err(|err| PassalongError::Io(format!("cannot write {}: {err}", path.display())))?;
    let public = key
        .public_key()
        .to_openssh()
        .map_err(|err| PassalongError::Io(format!("cannot encode the public key: {err}")))?;
    let mut public_path = path.as_os_str().to_owned();
    public_path.push(".pub");
    std::fs::write(&public_path, format!("{public}\n")).map_err(|err| {
        io(
            &format!("cannot write {}", Path::new(&public_path).display()),
            err,
        )
    })?;
    tracing::info!(path = %path.display(), "identity generated");
    Ok(public)
}

// ---------------------------------------------------------------------------
// A passalong-server (`init` for `https`)
// ---------------------------------------------------------------------------

/// The values the setup screen collects for a passalong-server, as
/// `passalong init` asks them.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HttpsSetupAnswers {
    /// `client.device_name`.
    pub device_name: String,
    /// `server.https.url`.
    pub url: String,
    /// `server.https.tls_pin`, confirmed against the server's own; `None`
    /// to trust the operating system's certificate authorities.
    pub tls_pin: Option<String>,
    /// `server.https.api_key_file`, absolute.
    pub api_key_file: String,
}

/// Renders the complete, commented config file `passalong init` writes for
/// a passalong-server.
#[uniffi::export]
pub fn render_https_config(answers: HttpsSetupAnswers) -> Result<String, PassalongError> {
    let tls_pin = answers
        .tls_pin
        .as_deref()
        .map(|pin| {
            TlsPin::parse(pin).map_err(|err| PassalongError::Config(format!("tls_pin: {err}")))
        })
        .transpose()?;
    Ok(config::render_https(&HttpsInitAnswers {
        device_name: answers.device_name,
        url: answers.url,
        tls_pin,
        api_key_file: PathBuf::from(answers.api_key_file),
    }))
}

/// Reads a pin as the CLI does, `sha256/<base64>` or `curl`'s
/// `sha256//<base64>`, and returns it in the first form, so pins can be
/// compared as text.
#[uniffi::export]
pub fn parse_tls_pin(text: String) -> Result<String, PassalongError> {
    TlsPin::parse(&text)
        .map(|pin| pin.to_string())
        .map_err(PassalongError::Config)
}

/// Checks that `text` has the shape of an API key, `pal_<id>_<secret>`,
/// ignoring surrounding white space, and returns the key's public id. The
/// secret is never returned or logged.
#[uniffi::export]
pub fn parse_api_key(text: String) -> Result<String, PassalongError> {
    ApiKey::parse(&text)
        .map(|key| key.id().to_owned())
        .map_err(|err| PassalongError::Config(err.to_string()))
}

/// Whether an API key is saved at `path`.
#[uniffi::export]
pub fn has_api_key(path: String) -> Result<bool, PassalongError> {
    load_api_key(Path::new(&path), &SystemGit::new())
        .map(|key| key.is_some())
        .map_err(|err| PassalongError::Io(err.to_string()))
}

/// Saves the API key `text` at `path`, readable by the app alone, as
/// `passalong init` saves it beside the config.
#[uniffi::export]
pub fn save_api_key(path: String, text: String) -> Result<(), PassalongError> {
    let key = ApiKey::parse(&text).map_err(|err| PassalongError::Config(err.to_string()))?;
    save_api_key_file(Path::new(&path), &key, &SystemGit::new())
        .map_err(|err| PassalongError::Io(err.to_string()))?;
    tracing::info!(path = %path, "API key saved");
    Ok(())
}

/// What a passalong-server says about itself, the API key in use, and the
/// workspace that key opens: `getViewer` and `getWorkspace`, as `init`
/// asks before writing anything and `check` reports.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ServerInfo {
    /// The server's version, such as `0.1.0`.
    pub server_version: String,
    /// The API version it speaks.
    pub api_version: u32,
    /// Where the server's source is, when it says.
    pub source_url: Option<String>,
    /// The key's public id.
    pub key_id: String,
    /// The key's label, such as the device's name.
    pub key_label: Option<String>,
    /// `read-write`, `read-only`, or `unknown`.
    pub key_role: String,
    /// When the key expires, seconds since the Unix epoch; `None` for never.
    pub key_expires_at: Option<i64>,
    /// The workspace's name.
    pub workspace: String,
    /// How many items it holds.
    pub item_count: u64,
    /// Its quota in bytes.
    pub quota_bytes: u64,
    /// The bytes its items take.
    pub used_bytes: u64,
    /// `plaintext`, `sealed`, `rewriting`, or `unknown`.
    pub encryption_state: String,
    /// The key id readers of a sealed workspace need.
    pub encryption_key_id: Option<String>,
}

/// Asks the server `config_toml` names about the key and the workspace,
/// with `api_key` as typed, or else the key saved in `api_key_file`. Nothing
/// is written; the TLS pin, when the config has one, is checked first.
#[uniffi::export(async_runtime = "tokio")]
pub async fn inspect_server(
    config_toml: String,
    home: String,
    hostname: String,
    api_key: Option<String>,
) -> Result<ServerInfo, PassalongError> {
    let env = AppEnv::new(home, hostname);
    let config = config::parse(&config_toml, Path::new("config.toml"), &env)?;
    let https = config.server.https.as_ref().ok_or_else(|| {
        PassalongError::Config("the `server.https` section is missing".to_owned())
    })?;
    let key = match api_key {
        Some(text) => {
            ApiKey::parse(&text).map_err(|err| PassalongError::Config(err.to_string()))?
        }
        None => load_api_key(&https.api_key_file, &SystemGit::new())
            .map_err(|err| PassalongError::Io(err.to_string()))?
            .ok_or_else(|| {
                PassalongError::Config(format!(
                    "no API key in {}: type the key the server's operator gave you",
                    https.api_key_file.display()
                ))
            })?,
    };
    let client = passalong_https::Client::new(https, key)?;
    let viewer = client.viewer().await?;
    let workspace = client.workspace().await?;
    tracing::info!(kind = "https", "server inspected");
    Ok(ServerInfo {
        server_version: viewer.server.version,
        api_version: viewer.server.api_version,
        source_url: viewer.server.source_url,
        key_id: viewer.key.id,
        key_label: viewer.key.label,
        key_role: match viewer.key.role {
            Role::ReadWrite => "read-write",
            Role::ReadOnly => "read-only",
            _ => "unknown",
        }
        .to_owned(),
        key_expires_at: viewer
            .key
            .expires_at
            .as_deref()
            .and_then(|at| chrono::DateTime::parse_from_rfc3339(at).ok())
            .map(|at| at.timestamp()),
        workspace: workspace.name,
        item_count: workspace.item_count,
        quota_bytes: workspace.quota_bytes,
        used_bytes: workspace.used_bytes,
        encryption_state: match workspace.encryption.state {
            EncryptionState::Plaintext => "plaintext",
            EncryptionState::Sealed => "sealed",
            EncryptionState::Rewriting => "rewriting",
            _ => "unknown",
        }
        .to_owned(),
        encryption_key_id: workspace.encryption.key_id,
    })
}

/// Whether this device holds a store key (`client.key_file`), which decides
/// whether a server's "not encrypted" claim needs the user's confirmation:
/// a device with a key never sends plaintext, whatever the server says.
#[uniffi::export]
pub fn device_has_store_key(
    config_toml: String,
    home: String,
    hostname: String,
) -> Result<bool, PassalongError> {
    let env = AppEnv::new(home, hostname);
    let config = config::parse(&config_toml, Path::new("config.toml"), &env)?;
    match &config.client.key_file {
        Some(path) => load_key_file(path, &SystemGit::new())
            .map(|key| key.is_some())
            .map_err(|err| PassalongError::Encryption(err.to_string())),
        None => Ok(false),
    }
}

/// The operating system's randomness, for key generation.
struct SysRng;

impl TryRng for SysRng {
    type Error = core::convert::Infallible;

    fn try_next_u32(&mut self) -> Result<u32, Self::Error> {
        let mut bytes = [0_u8; 4];
        self.try_fill_bytes(&mut bytes)?;
        Ok(u32::from_le_bytes(bytes))
    }

    fn try_next_u64(&mut self) -> Result<u64, Self::Error> {
        let mut bytes = [0_u8; 8];
        self.try_fill_bytes(&mut bytes)?;
        Ok(u64::from_le_bytes(bytes))
    }

    fn try_fill_bytes(&mut self, dst: &mut [u8]) -> Result<(), Self::Error> {
        // Key generation needs an infallible source. A system whose random
        // device fails cannot make a key at all, so stopping is the only
        // honest outcome.
        getrandom::fill(dst).expect("the operating system's random source failed");
        Ok(())
    }
}

// `Rng` and `CryptoRng` come from rand_core's blanket impls for an
// infallible `TryRng`.
impl TryCryptoRng for SysRng {}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    // A throwaway public key; public keys are not secrets.
    const KEY_A: &str =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF2M9DqIpW9GMebpvjNg+bobwAbQKRBqPVMatyvyI4gq";

    fn answers(identity: &str) -> SetupAnswers {
        SetupAnswers {
            device_name: "pixel".into(),
            host: "nas.local".into(),
            port: 2222,
            user: "passalong".into(),
            host_key: KEY_A.into(),
            identity_file: identity.into(),
            remote_path: "/srv/passalong".into(),
        }
    }

    #[test]
    fn the_rendered_config_validates_back_to_the_answers_with_app_paths() {
        let text = render_config(answers("~/keys/id_ed25519"));
        let summary = validate_config(text, "/data/app".into(), "unused".into()).unwrap();
        assert_eq!(summary.device_name, "pixel");
        assert_eq!(summary.server_kind, "ssh");
        assert_eq!(summary.host, "nas.local");
        assert_eq!(summary.port, 2222);
        assert_eq!(summary.user, "passalong");
        assert_eq!(summary.remote_path, "/srv/passalong");
        assert_eq!(summary.identity_file, "/data/app/keys/id_ed25519");
        assert_eq!(summary.download_dir, "/data/app/Downloads");
        assert_eq!(
            summary.key_file.as_deref(),
            Some("/data/app/.config/passalong/store.key")
        );
    }

    #[test]
    fn the_device_name_defaults_to_the_given_hostname() {
        let text = "[server]\nkind = \"local\"\n[server.local]\npath = \"~/store\"\n";
        let summary = validate_config(text.into(), "/data/app".into(), "pixel-9".into()).unwrap();
        assert_eq!(summary.device_name, "pixel-9");
        assert_eq!(summary.server_kind, "local");
        assert_eq!(summary.remote_path, "/data/app/store");
        assert_eq!(summary.host, "");
        assert_eq!(summary.port, 0);
        assert_eq!(summary.identity_file, "");
    }

    #[test]
    fn an_invalid_config_names_the_problem() {
        let err = validate_config("[server]\nkind = \"ssh\"\n".into(), "/h".into(), "d".into())
            .unwrap_err();
        assert!(
            matches!(&err, PassalongError::Config(m) if m.contains("server.ssh.host")),
            "{err:?}"
        );
    }

    #[test]
    fn generating_an_identity_writes_both_keys_privately_and_keeps_an_existing_one() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("keys/id_ed25519");
        let public = generate_identity(path.display().to_string(), "pixel".into(), false).unwrap();
        assert!(public.starts_with("ssh-ed25519 AAAA"), "{public}");
        assert!(public.ends_with(" pixel"), "{public}");
        let written = std::fs::read_to_string(path.with_extension("pub")).unwrap();
        assert_eq!(written, format!("{public}\n"));
        let private = std::fs::read_to_string(&path).unwrap();
        assert!(private.starts_with("-----BEGIN OPENSSH PRIVATE KEY-----"));
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&path).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600, "{mode:o}");
        }
        // The core can load it, and it is the key the public line names.
        let loaded = russh::keys::load_secret_key(&path, None).unwrap();
        assert_eq!(loaded.public_key().to_openssh().unwrap(), public);

        let err = generate_identity(path.display().to_string(), "pixel".into(), false).unwrap_err();
        assert!(
            matches!(&err, PassalongError::Io(m) if m.contains("already exists")),
            "{err:?}"
        );
        assert_eq!(std::fs::read_to_string(&path).unwrap(), private, "kept");

        let replaced = generate_identity(path.display().to_string(), "pixel".into(), true).unwrap();
        assert_ne!(replaced, public);
        assert_ne!(std::fs::read_to_string(&path).unwrap(), private);
    }

    #[test]
    fn a_key_cannot_be_written_into_a_file_used_as_a_folder() {
        let dir = TempDir::new().unwrap();
        let blocker = dir.path().join("file");
        std::fs::write(&blocker, "x").unwrap();
        let path = blocker.join("id_ed25519");
        let err = generate_identity(path.display().to_string(), "c".into(), false).unwrap_err();
        assert!(
            matches!(&err, PassalongError::Io(m) if m.contains("cannot create")),
            "{err:?}"
        );
    }

    #[test]
    fn pins_and_api_keys_are_checked_by_shape_and_pins_come_back_canonical() {
        let pin = "sha256/Zmh6rfhivXdsj8GLjp+OIAiXFIVu4jOzkCpZHQ1fKSU=";
        assert_eq!(parse_tls_pin(pin.into()).unwrap(), pin);
        assert_eq!(
            parse_tls_pin(format!("  {}\n", pin.replacen("/", "//", 1))).unwrap(),
            pin
        );
        for bad in [
            "",
            "sha256/short",
            "md5/abc",
            "Zmh6rfhivXdsj8GLjp+OIAiXFIVu4jOzkCpZHQ1fKSU=",
        ] {
            assert!(
                matches!(parse_tls_pin(bad.into()), Err(PassalongError::Config(_))),
                "{bad}"
            );
        }
        assert_eq!(
            parse_api_key(" pal_0123abcd_s3cr3t-x\n".into()).unwrap(),
            "0123abcd"
        );
        for bad in ["", "pal_", "pal_abc", "key_a_b", "pal_a_b c"] {
            let err = parse_api_key(bad.into()).unwrap_err();
            assert!(
                matches!(&err, PassalongError::Config(m) if m.contains("API key")),
                "{bad}: {err:?}"
            );
        }
    }

    #[test]
    fn api_keys_are_saved_privately_and_found_again() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join("keys/api.key");
        let shown = path.display().to_string();
        assert!(!has_api_key(shown.clone()).unwrap());
        let err = save_api_key(shown.clone(), "nonsense".into()).unwrap_err();
        assert!(matches!(err, PassalongError::Config(_)), "{err:?}");
        assert!(!path.exists());
        save_api_key(shown.clone(), "pal_0123abcd_s3cr3t".into()).unwrap();
        assert!(has_api_key(shown.clone()).unwrap());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&path).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600, "{mode:o}");
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
            let err = has_api_key(shown).unwrap_err();
            assert!(
                matches!(&err, PassalongError::Io(m) if m.contains("other users")),
                "{err:?}"
            );
        }
    }

    #[test]
    fn the_rendered_server_config_validates_back_with_or_without_a_pin() {
        let answers = |pin: Option<&str>| HttpsSetupAnswers {
            device_name: "phone".into(),
            url: "https://box.example:8443".into(),
            tls_pin: pin.map(str::to_owned),
            api_key_file: "/data/app/api.key".into(),
        };
        let pin = "sha256/Zmh6rfhivXdsj8GLjp+OIAiXFIVu4jOzkCpZHQ1fKSU=";
        let text = render_https_config(answers(Some(pin))).unwrap();
        assert!(text.contains(&format!("tls_pin = \"{pin}\"")), "{text}");
        let summary = validate_config(text, "/data/app".into(), "unused".into()).unwrap();
        assert_eq!(summary.server_kind, "https");
        assert_eq!(summary.device_name, "phone");
        assert_eq!(summary.remote_path, "");
        let text = render_https_config(answers(None)).unwrap();
        assert!(text.contains("No tls_pin"), "{text}");
        validate_config(text, "/data/app".into(), "unused".into()).unwrap();
        let err = render_https_config(answers(Some("nonsense"))).unwrap_err();
        assert!(
            matches!(&err, PassalongError::Config(m) if m.starts_with("tls_pin:")),
            "{err:?}"
        );
    }

    #[test]
    fn a_device_without_a_store_key_says_so() {
        let dir = TempDir::new().unwrap();
        let home = dir.path().display().to_string();
        let text = "[server]\nkind = \"https\"\n[server.https]\nurl = \"https://box:8443\"\n";
        assert!(!device_has_store_key(text.into(), home.clone(), "d".into()).unwrap());
        let err = device_has_store_key("nonsense".into(), home, "d".into()).unwrap_err();
        assert!(matches!(err, PassalongError::Config(_)), "{err:?}");
    }

    #[tokio::test]
    async fn inspecting_needs_a_server_section_and_a_key_before_any_connection() {
        let dir = TempDir::new().unwrap();
        let home = dir.path().display().to_string();
        let ssh = render_config(answers("~/k"));
        let err = inspect_server(ssh, home.clone(), "d".into(), None)
            .await
            .unwrap_err();
        assert!(
            matches!(&err, PassalongError::Config(m) if m.contains("server.https")),
            "{err:?}"
        );
        let text = "[server]\nkind = \"https\"\n[server.https]\nurl = \"https://127.0.0.1:1\"\n";
        let err = inspect_server(text.into(), home.clone(), "d".into(), None)
            .await
            .unwrap_err();
        assert!(
            matches!(&err, PassalongError::Config(m) if m.contains("no API key in")),
            "{err:?}"
        );
        let err = inspect_server(text.into(), home.clone(), "d".into(), Some("bad".into()))
            .await
            .unwrap_err();
        assert!(matches!(err, PassalongError::Config(_)), "{err:?}");
        let err = inspect_server(
            text.into(),
            home,
            "d".into(),
            Some("pal_0123abcd_s3cr3t".into()),
        )
        .await
        .unwrap_err();
        assert!(matches!(err, PassalongError::Connect(_)), "{err:?}");
    }

    #[tokio::test]
    async fn probing_a_server_that_is_not_there_is_a_connect_error() {
        // Port 1 on localhost: refused at once, no certificate.
        let err = probe_server("https://127.0.0.1:1".into())
            .await
            .unwrap_err();
        assert!(
            matches!(&err, PassalongError::Connect(m) if m.contains("cannot reach https://127.0.0.1:1")),
            "{err:?}"
        );
        let err = probe_server("http://127.0.0.1:1".into()).await.unwrap_err();
        assert!(matches!(err, PassalongError::Connect(_)), "{err:?}");
    }

    #[tokio::test]
    async fn fetching_a_host_key_from_nowhere_is_a_connect_error() {
        // Port 1 on localhost: refused at once, no key.
        let err = fetch_host_key("127.0.0.1".into(), 1, 5).await.unwrap_err();
        assert!(matches!(err, PassalongError::Connect(_)), "{err:?}");
    }
}
