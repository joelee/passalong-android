//! A real passalong-server per test, run from the binary
//! `PASSALONG_SERVER_BIN` names, under a home of its own so it never touches
//! a server the developer runs for real. Written from the server's
//! documentation and `--help` alone: the server is AGPL-3.0-or-later and no
//! code of it is used here.
//!
//! Each server gets a self-signed certificate for `localhost`, a workspace
//! called [`WORKSPACE`], and one read-write key that never expires; more
//! keys come from [`TestServer::create_key`]. The server is killed when the
//! test drops it.

use std::io::{BufRead, BufReader};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use tempfile::TempDir;

/// The workspace every test server has.
pub const WORKSPACE: &str = "home";

/// A running passalong-server.
pub struct TestServer {
    home: TempDir,
    bin: PathBuf,
    child: Child,
    /// `https://127.0.0.1:<port>`.
    pub url: String,
    /// The pin of its self-signed certificate, `sha256/...`.
    pub pin: String,
    /// A read-write key of [`WORKSPACE`] that never expires.
    pub key: String,
}

impl TestServer {
    /// Starts a server on a free port.
    pub fn start() -> Self {
        let bin = PathBuf::from(std::env::var("PASSALONG_SERVER_BIN").unwrap_or_else(|_| {
            panic!("PASSALONG_SERVER_BIN is not set: run these tests with `just test-https`")
        }));
        let home = TempDir::new().unwrap();
        run(&bin, home.path(), &["init"]);
        run(
            &bin,
            home.path(),
            &[
                "tls",
                "self-signed",
                "--host",
                "localhost",
                "--ip",
                "127.0.0.1",
            ],
        );
        let pin = run(&bin, home.path(), &["tls", "fingerprint"])
            .split_whitespace()
            .find(|word| word.starts_with("sha256/"))
            .expect("tls fingerprint prints the pin")
            .to_owned();
        let port = TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap()
            .port();
        // The address and, since tests send wrong keys on purpose, no rate
        // limit on failed logins; both are documented settings.
        let config = home.path().join(".config/passalong-server/config.toml");
        let text = std::fs::read_to_string(&config)
            .unwrap()
            .lines()
            .map(|line| match line.split_once(" = ") {
                Some(("address", _)) => format!("address = \"127.0.0.1:{port}\""),
                Some(("auth_failures_per_minute", _)) => "auth_failures_per_minute = 0".to_owned(),
                _ => line.to_owned(),
            })
            .collect::<Vec<_>>()
            .join("\n");
        std::fs::write(&config, text).unwrap();
        run(&bin, home.path(), &["workspace", "create", WORKSPACE]);
        let log = std::fs::File::create(home.path().join("serve.log")).unwrap();
        let child = command(&bin, home.path())
            .arg("serve")
            .stdout(Stdio::null())
            .stderr(log)
            .spawn()
            .expect("passalong-server serve starts");
        let mut server = Self {
            home,
            bin,
            child,
            url: format!("https://127.0.0.1:{port}"),
            pin,
            key: String::new(),
        };
        server.wait_listening(port);
        server.key = server.create_key(&["--never"]);
        server
    }

    /// A new key of [`WORKSPACE`] labelled `test`, with `extra` options
    /// such as `--read-only` or `--expires 7d`. Returns the key itself.
    pub fn create_key(&self, extra: &[&str]) -> String {
        let mut args = vec![
            "key",
            "create",
            "--workspace",
            WORKSPACE,
            "--label",
            "test",
            "--json",
        ];
        args.extend_from_slice(extra);
        let printed = self.run(&args);
        // The key is the one `pal_` word in the JSON.
        printed
            .split(|c: char| c == '"' || c.is_whitespace())
            .find(|word| word.starts_with("pal_"))
            .expect("key create --json prints the key")
            .to_owned()
    }

    /// Runs an operator command, such as `key revoke <id>`, and returns
    /// what it printed.
    pub fn run(&self, args: &[&str]) -> String {
        run(&self.bin, self.home.path(), args)
    }

    /// The config text of a device in `dir` that reaches this server with
    /// its pin (or without one when `pinned` is false) and keeps its API key
    /// in `dir/api.key`.
    pub fn config(&self, dir: &Path, pinned: bool) -> String {
        let pin = if pinned {
            format!("tls_pin = \"{}\"\n", self.pin)
        } else {
            String::new()
        };
        format!(
            "[client]\ndevice_name = \"phone\"\n[server]\nkind = \"https\"\n[server.https]\nurl = \"{}\"\n{pin}api_key_file = \"{}\"\n",
            self.url,
            dir.join("api.key").display()
        )
    }

    /// The server's log so far.
    pub fn log(&self) -> String {
        std::fs::read_to_string(self.home.path().join("serve.log")).unwrap_or_default()
    }

    fn wait_listening(&mut self, port: u16) {
        let deadline = Instant::now() + Duration::from_secs(30);
        while TcpStream::connect(("127.0.0.1", port)).is_err() {
            if let Some(status) = self.child.try_wait().unwrap() {
                panic!("the server stopped ({status}):\n{}", self.log());
            }
            assert!(
                Instant::now() < deadline,
                "the server never listened:\n{}",
                self.log()
            );
            std::thread::sleep(Duration::from_millis(50));
        }
    }
}

impl Drop for TestServer {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// The server with its own home and none of the developer's settings.
fn command(bin: &Path, home: &Path) -> Command {
    let mut command = Command::new(bin);
    command
        .env("HOME", home)
        .env_remove("XDG_CONFIG_HOME")
        .env_remove("XDG_DATA_HOME")
        .env_remove("PASSALONG_SERVER_CONFIG_FILE")
        .env_remove("PASSALONG_SERVER_LOG_LEVEL")
        .stdin(Stdio::null());
    command
}

fn run(bin: &Path, home: &Path, args: &[&str]) -> String {
    let out = command(bin, home).args(args).output().unwrap();
    assert!(
        out.status.success(),
        "passalong-server {args:?} failed: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    // Log lines may precede the result on stdout; every line is kept, the
    // callers look for what they need.
    BufReader::new(out.stdout.as_slice())
        .lines()
        .map_while(Result::ok)
        .collect::<Vec<_>>()
        .join("\n")
}
