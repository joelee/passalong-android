//! A store handle with what `passalong choose` does: list (through the
//! list cache when there is one), show metadata, load, print, and delete.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use chrono::{DateTime, Utc};
use passalong_core::cache::{self, ListCache};
use passalong_core::config::{self, Config};
use passalong_core::download::{download_into, read_verified};
use passalong_core::model::{ItemId, ItemKind, ItemMeta, sanitise_file_name};
use passalong_core::store::{BackendRegistry, Store, StoreError};

use crate::env::AppEnv;
use crate::error::PassalongError;
use crate::model::Item;

/// The items a fresh list cache holds, and how old it is.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CachedItems {
    /// The items, newest first.
    pub items: Vec<Item>,
    /// Seconds since the cache was last compared with the store.
    pub age_secs: i64,
}

/// An open store: the connection `choose` makes first, kept for the
/// actions.
#[derive(uniffi::Object)]
pub struct PassalongStore {
    store: Box<dyn Store>,
    cache: Option<Cache>,
    description: String,
    download_dir: PathBuf,
}

/// Parses `config_toml` as the core does, with `home` as the app's home
/// directory and `hostname` as the default device name, connects, and
/// opens the store as its header says. With `cache_file`, the list cache
/// is kept there for stores the CLI caches (`ssh`, with `serve.list_cache`
/// on).
#[uniffi::export(async_runtime = "tokio")]
pub async fn open_store(
    config_toml: String,
    home: String,
    hostname: String,
    cache_file: Option<String>,
) -> Result<Arc<PassalongStore>, PassalongError> {
    let env = AppEnv::new(home, hostname);
    let config = config::parse(&config_toml, Path::new("config.toml"), &env)?;
    let mut registry = BackendRegistry::with_builtin();
    passalong_ssh::register(&mut registry);
    passalong_https::register(&mut registry);
    let store = registry.open(&config).await?;
    tracing::info!(kind = %config.server.kind, "store opened");
    Ok(Arc::new(PassalongStore {
        store,
        cache: cache_file.and_then(|path| Cache::for_config(&config, PathBuf::from(path))),
        description: describe(&config),
        download_dir: config.client.download_dir.clone(),
    }))
}

#[uniffi::export(async_runtime = "tokio")]
impl PassalongStore {
    /// Where the items are, for the screen: `ssh user@host:port path` or
    /// `local path`.
    pub fn description(&self) -> String {
        self.description.clone()
    }

    /// Whether this store keeps a list cache.
    pub fn has_cache(&self) -> bool {
        self.cache.is_some()
    }

    /// The list cache when it is fresh enough to show instead of the
    /// store, with its age, as `choose` opens.
    pub fn cached(&self) -> Option<CachedItems> {
        let now = Utc::now();
        let cache = self.cache.as_ref()?.usable(now)?;
        Some(CachedItems {
            items: cache.items.iter().map(Item::from).collect(),
            age_secs: (now - cache.checked_at).num_seconds().max(0),
        })
    }

    /// Lists every item, newest first, from the store, and brings the list
    /// cache up to date. With `reuse`, a saved cache is refreshed: one id
    /// listing plus the metadata of new ids (`r`); otherwise every item's
    /// metadata is read again (`R`).
    pub async fn list(&self, reuse: bool) -> Result<Vec<Item>, PassalongError> {
        let items = match &self.cache {
            Some(cache) => cache.read(self.store.as_ref(), Utc::now(), reuse).await?,
            None => self.store.list().await?,
        };
        tracing::info!(count = items.len() as u64, "items listed");
        Ok(items.iter().map(Item::from).collect())
    }

    /// One item's metadata, read from the store, as `passalong get` does.
    pub async fn get_meta(&self, id: String) -> Result<Item, PassalongError> {
        let id = parse_id(&id)?;
        Ok(Item::from(&self.store.get_meta(&id).await?))
    }

    /// A text item's content, verified against its SHA-256, as
    /// `passalong cat` prints it. Refused for anything but text, since the
    /// app shows it in a text view.
    pub async fn read_text(&self, id: String) -> Result<String, PassalongError> {
        let id = parse_id(&id)?;
        let (meta, content) = self.store.get(&id).await?;
        if !is_text(&meta) {
            return Err(PassalongError::Store(format!(
                "item {id} is binary ({}); load it instead",
                meta.mime
            )));
        }
        let bytes = read_verified(content, &meta).await?;
        let text = String::from_utf8(bytes)
            .map_err(|_| PassalongError::Store(format!("item {id} is not UTF-8 text")))?;
        tracing::info!(id = %id, size = meta.size, "item read");
        Ok(text)
    }

    /// Downloads a file item into `dir`, or into `client.download_dir`
    /// without one, under its own name, or a numbered one when that is
    /// taken, verified before it appears, as `passalong load` does. Returns
    /// the path written.
    pub async fn download(
        &self,
        id: String,
        dir: Option<String>,
    ) -> Result<String, PassalongError> {
        let id = parse_id(&id)?;
        let (meta, content) = self.store.get(&id).await?;
        let name = match meta.kind {
            ItemKind::Text => format!("{id}.txt"),
            ItemKind::File => sanitise_file_name(meta.name.as_deref().unwrap_or_default())
                .map_err(|err| PassalongError::Store(format!("item {id}: {err}")))?,
        };
        let dir = dir.map_or_else(|| self.download_dir.clone(), PathBuf::from);
        tokio::fs::create_dir_all(&dir).await.map_err(|err| {
            PassalongError::Io(format!(
                "cannot create the download directory {}: {err}",
                dir.display()
            ))
        })?;
        let target = download_into(content, &meta, &dir, &name).await?;
        tracing::info!(id = %id, size = meta.size, path = %target.display(), "item written");
        Ok(target.display().to_string())
    }

    /// Deletes an item and returns its metadata. Its entry leaves the list
    /// cache at once.
    pub async fn delete(&self, id: String) -> Result<Item, PassalongError> {
        let id = parse_id(&id)?;
        let meta = self.store.delete(&id).await?;
        if let Some(cache) = &self.cache {
            cache.apply_delete(&id);
        }
        tracing::info!(id = %id, "item deleted");
        Ok(Item::from(&meta))
    }
}

impl std::fmt::Debug for PassalongStore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PassalongStore")
            .field("description", &self.description)
            .field("cache", &self.cache.as_ref().map(|cache| &cache.path))
            .finish_non_exhaustive()
    }
}

fn parse_id(id: &str) -> Result<ItemId, PassalongError> {
    ItemId::parse(id).map_err(|err| PassalongError::Store(format!("invalid item id `{id}`: {err}")))
}

/// Whether an item prints as text: text items, and files of a `text/` type.
fn is_text(meta: &ItemMeta) -> bool {
    meta.kind == ItemKind::Text || meta.mime.starts_with("text/")
}

/// Where the items are, as `passalong check` names the server: `ssh
/// user@host:port path`, `https url`, or `local path`.
fn describe(config: &Config) -> String {
    let server = &config.server;
    match (
        server.kind.as_str(),
        &server.ssh,
        &server.https,
        &server.local,
    ) {
        ("ssh", Some(ssh), _, _) => format!(
            "ssh {}@{}:{} {}",
            ssh.user, ssh.host, ssh.port, ssh.remote_path
        ),
        ("https", _, Some(https), _) => format!("https {}", https.url),
        (kind, _, _, Some(local)) => format!("{kind} {}", local.path.display()),
        (kind, _, _, _) => kind.to_owned(),
    }
}

/// The list cache file of one store, as the CLI keeps it: shown while it
/// was checked within `max_age`, refreshed or rewritten otherwise.
struct Cache {
    path: PathBuf,
    identity: String,
    max_age: Duration,
}

impl Cache {
    /// The cache of `config`'s store at `path`, or `None` when the CLI
    /// would not cache it either: a `local` store, `serve.list_cache` off,
    /// or an unreadable key file.
    fn for_config(config: &Config, path: PathBuf) -> Option<Self> {
        let identity = ListCache::store_identity(config).filter(|_| config.serve.list_cache)?;
        let interval = Duration::from_secs(config.serve.list_cache_check_secs);
        Some(Self::new(path, identity, cache::max_age(interval)))
    }

    fn new(path: PathBuf, identity: String, max_age: Duration) -> Self {
        Self {
            path,
            identity,
            max_age,
        }
    }

    /// The saved cache of this store, however old.
    fn load(&self) -> Option<ListCache> {
        ListCache::load(&self.path).filter(|cache| cache.store == self.identity)
    }

    /// The saved cache when it is recent enough to be shown instead of the
    /// store.
    fn usable(&self, now: DateTime<Utc>) -> Option<ListCache> {
        self.load()
            .filter(|cache| cache.usable(&self.identity, now, self.max_age))
    }

    /// The store's items, read from `store` and written to the cache. With
    /// `reuse`, a saved cache is refreshed; otherwise every item's metadata
    /// is read.
    async fn read(
        &self,
        store: &dyn Store,
        now: DateTime<Utc>,
        reuse: bool,
    ) -> Result<Vec<ItemMeta>, StoreError> {
        let cache = match self.load().filter(|_| reuse) {
            Some(mut cache) => {
                cache.refresh(store, now).await?;
                cache
            }
            None => ListCache::read(store, self.identity.clone(), now).await?,
        };
        self.save(&cache);
        Ok(cache.items)
    }

    fn apply_delete(&self, id: &ItemId) {
        if let Some(mut cache) = self.load() {
            cache.apply_delete(id);
            self.save(&cache);
        }
    }

    /// A cache that cannot be written is logged, never an error: the store
    /// was read anyway.
    fn save(&self, cache: &ListCache) {
        if let Err(err) = cache.save(&self.path) {
            tracing::warn!(path = %self.path.display(), error = %err, "cannot write the list cache");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use passalong_core::model::NewItem;
    use tempfile::TempDir;

    /// A local store in a temporary folder, opened through the bridge and
    /// directly, so tests can add items behind the bridge's back.
    struct Rig {
        dir: TempDir,
        bridge: Arc<PassalongStore>,
        direct: Box<dyn Store>,
    }

    impl Rig {
        async fn new() -> Self {
            let dir = TempDir::new().unwrap();
            let home = dir.path().join("home");
            let text = format!(
                "[client]\ndevice_name = \"box\"\n[server]\nkind = \"local\"\n[server.local]\npath = \"{}\"\n",
                dir.path().join("store").display()
            );
            let bridge = open_store(
                text.clone(),
                home.display().to_string(),
                "unused".into(),
                Some(dir.path().join("cache.json").display().to_string()),
            )
            .await
            .unwrap();
            let env = AppEnv::new(home.display().to_string(), "unused");
            let config = config::parse(&text, Path::new("c.toml"), &env).unwrap();
            let direct = passalong_core::store::open_store(&config).await.unwrap();
            Self {
                dir,
                bridge,
                direct,
            }
        }

        async fn put_text(&self, text: &str) -> ItemMeta {
            self.put(NewItem::text("box"), text.as_bytes()).await
        }

        async fn put(&self, item: NewItem, bytes: &[u8]) -> ItemMeta {
            let content: passalong_core::fs::BoxRead =
                Box::new(std::io::Cursor::new(bytes.to_vec()));
            self.direct.put(item, content).await.unwrap().meta
        }
    }

    #[tokio::test]
    async fn a_local_store_lists_newest_first_without_a_cache() {
        let rig = Rig::new().await;
        assert_eq!(rig.bridge.list(true).await.unwrap(), []);
        assert!(!rig.bridge.has_cache(), "local stores are not cached");
        assert_eq!(rig.bridge.cached(), None);
        assert_eq!(
            rig.bridge.description(),
            format!("local {}", rig.dir.path().join("store").display())
        );
        let older = rig.put_text("older").await;
        tokio::time::sleep(Duration::from_millis(1100)).await;
        let newer = rig.put_text("newer").await;
        let listed = rig.bridge.list(false).await.unwrap();
        assert_eq!(
            listed
                .iter()
                .map(|item| item.id.as_str())
                .collect::<Vec<_>>(),
            [newer.id.as_str(), older.id.as_str()]
        );
        assert_eq!(listed[0].preview.as_deref(), Some("newer"));
    }

    #[tokio::test]
    async fn metadata_text_and_deletion_work_as_the_cli_commands_do() {
        let rig = Rig::new().await;
        let meta = rig.put_text("hello").await;
        let id = meta.id.to_string();
        assert_eq!(
            rig.bridge.get_meta(id.clone()).await.unwrap(),
            Item::from(&meta)
        );
        assert_eq!(rig.bridge.read_text(id.clone()).await.unwrap(), "hello");
        let deleted = rig.bridge.delete(id.clone()).await.unwrap();
        assert_eq!(deleted.id, id);
        assert_eq!(rig.bridge.list(true).await.unwrap(), []);
        for err in [
            rig.bridge.get_meta(id.clone()).await.unwrap_err(),
            rig.bridge.read_text(id.clone()).await.unwrap_err(),
            rig.bridge.delete(id.clone()).await.unwrap_err(),
            rig.bridge.download(id.clone(), None).await.unwrap_err(),
        ] {
            assert!(
                matches!(&err, PassalongError::NotFound(m) if m.contains(&id)),
                "{err:?}"
            );
        }
        let err = rig.bridge.get_meta("nonsense".into()).await.unwrap_err();
        assert!(
            matches!(&err, PassalongError::Store(m) if m.contains("invalid item id")),
            "{err:?}"
        );
    }

    #[tokio::test]
    async fn binary_items_are_not_read_as_text_but_text_files_are() {
        let rig = Rig::new().await;
        let pdf = rig.put(NewItem::file("a.pdf", "box"), b"%PDF").await;
        let err = rig.bridge.read_text(pdf.id.to_string()).await.unwrap_err();
        assert!(
            matches!(&err, PassalongError::Store(m) if m.contains("is binary (application/pdf)")),
            "{err:?}"
        );
        let notes = rig.put(NewItem::file("notes.txt", "box"), b"plain").await;
        assert_eq!(
            rig.bridge.read_text(notes.id.to_string()).await.unwrap(),
            "plain"
        );
        let bad = rig
            .put(NewItem::file("bad.txt", "box"), &[0xff, 0xfe])
            .await;
        let err = rig.bridge.read_text(bad.id.to_string()).await.unwrap_err();
        assert!(
            matches!(&err, PassalongError::Store(m) if m.contains("not UTF-8")),
            "{err:?}"
        );
    }

    #[tokio::test]
    async fn downloads_go_to_the_download_dir_or_the_given_one_under_free_names() {
        let rig = Rig::new().await;
        let file = rig.put(NewItem::file("report.pdf", "box"), b"%PDF").await;
        let id = file.id.to_string();
        let first = rig.bridge.download(id.clone(), None).await.unwrap();
        let expected = rig.dir.path().join("home/Downloads/report.pdf");
        assert_eq!(first, expected.display().to_string());
        assert_eq!(std::fs::read(&expected).unwrap(), b"%PDF");
        let second = rig.bridge.download(id.clone(), None).await.unwrap();
        assert_eq!(
            second,
            rig.dir
                .path()
                .join("home/Downloads/report (1).pdf")
                .display()
                .to_string()
        );
        let elsewhere = rig.dir.path().join("elsewhere");
        let third = rig
            .bridge
            .download(id.clone(), Some(elsewhere.display().to_string()))
            .await
            .unwrap();
        assert_eq!(third, elsewhere.join("report.pdf").display().to_string());
        let text = rig.put_text("hello").await;
        let path = rig
            .bridge
            .download(text.id.to_string(), None)
            .await
            .unwrap();
        assert!(path.ends_with(&format!("{}.txt", text.id)), "{path}");
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "hello");
        let blocked = rig.dir.path().join("blocker");
        std::fs::write(&blocked, "x").unwrap();
        let err = rig
            .bridge
            .download(id, Some(blocked.join("sub").display().to_string()))
            .await
            .unwrap_err();
        assert!(matches!(err, PassalongError::Io(_)), "{err:?}");
    }

    #[tokio::test]
    async fn opening_refuses_bad_configs_and_unusable_stores() {
        let dir = TempDir::new().unwrap();
        let home = dir.path().display().to_string();
        let err = open_store(
            "[server]\nkind = \"ssh\"\n".into(),
            home.clone(),
            "d".into(),
            None,
        )
        .await
        .unwrap_err();
        assert!(matches!(err, PassalongError::Config(_)), "{err:?}");
        let text = "[server]\nkind = \"s3\"\n";
        let err = open_store(text.into(), home.clone(), "d".into(), None)
            .await
            .unwrap_err();
        assert!(matches!(err, PassalongError::Config(_)), "{err:?}");
        // A key file for a store that is not encrypted is refused, as the
        // CLI refuses it.
        let key = dir.path().join(".config/passalong/store.key");
        std::fs::create_dir_all(key.parent().unwrap()).unwrap();
        std::fs::write(&key, "not a key\n").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&key, std::fs::Permissions::from_mode(0o600)).unwrap();
        }
        let text = format!(
            "[server]\nkind = \"local\"\n[server.local]\npath = \"{}\"\n",
            dir.path().join("store").display()
        );
        let err = open_store(text, home, "d".into(), None).await.unwrap_err();
        assert!(matches!(err, PassalongError::Encryption(_)), "{err:?}");
    }

    #[tokio::test]
    async fn a_server_config_without_an_api_key_is_refused_before_connecting() {
        let dir = TempDir::new().unwrap();
        let home = dir.path().display().to_string();
        let text = "[server]\nkind = \"https\"\n[server.https]\nurl = \"https://127.0.0.1:1\"\n";
        let err = open_store(text.into(), home, "d".into(), None)
            .await
            .unwrap_err();
        assert!(
            matches!(&err, PassalongError::Config(m) if m.contains("no API key in") && m.contains("api.key")),
            "{err:?}"
        );
    }

    /// The cache alone, over a local store, since the bridge only caches
    /// `ssh` stores: fresh caches are shown with their age, `r` refreshes,
    /// `R` reads everything again, and a deletion leaves the cache at once.
    #[tokio::test]
    async fn the_cache_is_shown_while_fresh_and_kept_current() {
        let rig = Rig::new().await;
        let cache = Cache::new(
            rig.dir.path().join("cache.json"),
            "test plain".into(),
            Duration::from_secs(120),
        );
        let t0: DateTime<Utc> = "2026-09-12T09:53:11Z".parse().unwrap();
        assert!(cache.usable(t0).is_none(), "no file yet");
        let a = rig.put_text("a").await;
        assert_eq!(
            cache.read(rig.direct.as_ref(), t0, true).await.unwrap(),
            std::slice::from_ref(&a)
        );
        let b = rig.put_text("b").await;
        // Within the limit the saved cache is used, and misses `b`.
        let fresh = cache.usable(t0 + chrono::TimeDelta::seconds(119)).unwrap();
        assert_eq!(fresh.items, std::slice::from_ref(&a));
        assert!(cache.usable(t0 + chrono::TimeDelta::seconds(121)).is_none());
        // Refreshing adds `b` under the new time.
        let t1 = t0 + chrono::TimeDelta::seconds(300);
        let items = cache.read(rig.direct.as_ref(), t1, true).await.unwrap();
        assert_eq!(items.len(), 2);
        assert!(items.contains(&b));
        assert_eq!(cache.usable(t1).unwrap().checked_at, t1);
        cache.apply_delete(&a.id);
        assert_eq!(cache.usable(t1).unwrap().items, std::slice::from_ref(&b));
        // Reading without reuse rewrites the file from the store.
        rig.direct.delete(&b.id).await.unwrap();
        assert_eq!(
            cache.read(rig.direct.as_ref(), t1, false).await.unwrap(),
            std::slice::from_ref(&a)
        );
        // Another store's cache is ignored.
        let other = Cache::new(
            cache.path.clone(),
            "other plain".into(),
            Duration::from_secs(120),
        );
        assert!(other.usable(t1).is_none());
        // An unwritable cache is not an error.
        let unwritable = Cache::new(
            rig.dir.path().join("blocker/cache.json"),
            "test plain".into(),
            Duration::from_secs(120),
        );
        std::fs::write(rig.dir.path().join("blocker"), "x").unwrap();
        assert_eq!(
            unwritable
                .read(rig.direct.as_ref(), t1, true)
                .await
                .unwrap(),
            [a]
        );
    }

    #[test]
    fn ssh_stores_are_cached_when_the_config_says_so() {
        let dir = TempDir::new().unwrap();
        let env = AppEnv::new(dir.path().display().to_string(), "d");
        let ssh = |extra: &str| {
            config::parse(
                &format!(
                    "[server]\nkind = \"ssh\"\n[server.ssh]\nhost = \"h\"\nuser = \"u\"\nhost_key = \"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF2M9DqIpW9GMebpvjNg+bobwAbQKRBqPVMatyvyI4gq\"\nidentity_file = \"~/k\"\nremote_path = \"/r\"\n{extra}"
                ),
                Path::new("c.toml"),
                &env,
            )
            .unwrap()
        };
        let cache = Cache::for_config(&ssh(""), dir.path().join("c.json")).unwrap();
        assert_eq!(cache.identity, "ssh u@h:22 /r plain");
        assert_eq!(cache.max_age, Duration::from_secs(120));
        assert_eq!(describe(&ssh("")), "ssh u@h:22 /r");
        let https = config::parse(
            "[server]\nkind = \"https\"\n[server.https]\nurl = \"https://box:8443\"\n",
            Path::new("c.toml"),
            &env,
        )
        .unwrap();
        assert_eq!(describe(&https), "https https://box:8443");
        assert!(
            Cache::for_config(&https, dir.path().join("c.json")).is_none(),
            "no API key file yet, so no identity"
        );
        assert!(
            Cache::for_config(
                &ssh("[serve]\nlist_cache = false\n"),
                dir.path().join("c.json")
            )
            .is_none()
        );
        let slow = ssh("[serve]\nlist_cache_check_secs = 600\n");
        assert_eq!(
            Cache::for_config(&slow, dir.path().join("c.json"))
                .unwrap()
                .max_age,
            Duration::from_secs(1200)
        );
    }
}
