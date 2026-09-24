//! The bridge against a real passalong-server: what the setup screen and
//! the choose screen do. Ignored without `PASSALONG_SERVER_BIN`; `just
//! test-https` runs them.

mod support;

use passalong_ffi::{
    HttpsSetupAnswers, PassalongError, has_api_key, inspect_server, open_store, parse_api_key,
    probe_server, render_https_config, save_api_key,
};
use support::{TestServer, WORKSPACE};
use tempfile::TempDir;

fn home(dir: &TempDir) -> String {
    dir.path().display().to_string()
}

#[tokio::test]
#[ignore = "needs passalong-server: just test-https"]
async fn the_probe_shows_the_pin_and_that_the_system_does_not_trust_it() {
    let server = TestServer::start();
    let presented = probe_server(server.url.clone()).await.unwrap();
    assert_eq!(presented.pin, server.pin);
    assert!(!presented.trusted, "self-signed");
}

#[tokio::test]
#[ignore = "needs passalong-server: just test-https"]
async fn inspecting_with_a_typed_key_says_what_the_server_and_the_key_are() {
    let server = TestServer::start();
    let dir = TempDir::new().unwrap();
    let config = server.config(dir.path(), true);
    let info = inspect_server(config, home(&dir), "phone".into(), Some(server.key.clone()))
        .await
        .unwrap();
    assert_eq!(info.server_version, "0.1.0");
    assert_eq!(info.api_version, 1);
    assert_eq!(info.key_id, parse_api_key(server.key.clone()).unwrap());
    assert_eq!(info.key_label.as_deref(), Some("test"));
    assert_eq!(info.key_role, "read-write");
    assert_eq!(info.key_expires_at, None);
    assert_eq!(info.workspace, WORKSPACE);
    assert_eq!(info.item_count, 0);
    assert_eq!(info.encryption_state, "plaintext");
    assert_eq!(info.encryption_key_id, None);
    assert!(info.quota_bytes > 0);
    assert_eq!(info.used_bytes, 0);
    assert!(!has_api_key(dir.path().join("api.key").display().to_string()).unwrap());
}

#[tokio::test]
#[ignore = "needs passalong-server: just test-https"]
async fn a_saved_key_is_used_when_none_is_typed_and_a_wrong_one_is_denied() {
    let server = TestServer::start();
    let dir = TempDir::new().unwrap();
    let path = dir.path().join("api.key").display().to_string();
    let config = server.config(dir.path(), true);
    let err = inspect_server(config.clone(), home(&dir), "phone".into(), None)
        .await
        .unwrap_err();
    assert!(
        matches!(&err, PassalongError::Config(m) if m.contains("no API key in")),
        "{err:?}"
    );
    save_api_key(path.clone(), server.key.clone()).unwrap();
    assert!(has_api_key(path.clone()).unwrap());
    let info = inspect_server(config.clone(), home(&dir), "phone".into(), None)
        .await
        .unwrap();
    assert_eq!(info.workspace, WORKSPACE);
    // A key of the right shape the server does not know.
    let wrong = server.key.replace(|c: char| c.is_ascii_digit(), "0");
    let err = inspect_server(config, home(&dir), "phone".into(), Some(wrong))
        .await
        .unwrap_err();
    assert!(matches!(err, PassalongError::Denied(_)), "{err:?}");
}

#[tokio::test]
#[ignore = "needs passalong-server: just test-https"]
async fn a_wrong_pin_fails_before_any_request_naming_the_presented_one() {
    let server = TestServer::start();
    let dir = TempDir::new().unwrap();
    let wrong = "sha256/Zmh6rfhivXdsj8GLjp+OIAiXFIVu4jOzkCpZHQ1fKSU=";
    let config = server.config(dir.path(), true).replace(&server.pin, wrong);
    let err = inspect_server(config, home(&dir), "phone".into(), Some(server.key.clone()))
        .await
        .unwrap_err();
    assert!(
        matches!(&err, PassalongError::Connect(m) if m.contains(&server.pin)),
        "{err:?}"
    );
    // Without a pin, the system does not trust a self-signed certificate.
    let config = server.config(dir.path(), false);
    let err = inspect_server(config, home(&dir), "phone".into(), Some(server.key.clone()))
        .await
        .unwrap_err();
    assert!(matches!(err, PassalongError::Connect(_)), "{err:?}");
}

#[tokio::test]
#[ignore = "needs passalong-server: just test-https"]
async fn the_rendered_config_opens_the_workspace_and_the_cache_names_it() {
    let server = TestServer::start();
    let dir = TempDir::new().unwrap();
    let key_file = dir.path().join("api.key").display().to_string();
    save_api_key(key_file.clone(), server.key.clone()).unwrap();
    let text = render_https_config(HttpsSetupAnswers {
        device_name: "phone".into(),
        url: server.url.clone(),
        tls_pin: Some(server.pin.clone()),
        api_key_file: key_file,
    })
    .unwrap();
    let cache = dir.path().join("list-cache.json");
    let store = open_store(
        text,
        home(&dir),
        "phone".into(),
        Some(cache.display().to_string()),
    )
    .await
    .unwrap();
    assert_eq!(store.description(), format!("https {}", server.url));
    assert!(
        store.has_cache(),
        "servers are cached, as the CLI caches them"
    );
    assert_eq!(store.list(true).await.unwrap(), []);
    let saved = std::fs::read_to_string(&cache).unwrap();
    assert!(saved.contains(&server.url), "{saved}");
    assert_eq!(store.cached().unwrap().items, []);
}
