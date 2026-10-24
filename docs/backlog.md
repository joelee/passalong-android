# Backlog

Future work not covered by an active plan. Completed items are removed.

## Next milestones

- **Join an encrypted store**: type the store's six words, derive the key
  as `passalong encrypt --join` does, and keep it in `client.key_file`;
  also key import from a QR code shown by another device.
- **Sending**: share text and files into the app (`passalong clipboard`,
  `passalong file`), and a clipboard push; over a passalong-server, the
  three-call upload with `expectedKeyId`.
- **API key import** from a QR code or a link the operator shows, instead
  of pasting; and a *Replace the key* action for an expired one without
  setting up again.
- **Clipboard images**: load a clipboard image onto the clipboard through
  a content URI instead of into Downloads.
- **Instrumented tests for the Compose screens** (the controllers and the
  bridge have them, `just test-android`), and for the SSH flow against
  passalong's Docker OpenSSH server.
- **A `check` screen**: the lines `passalong check` prints, including the
  workspace's usage, after setup.

## Later

- **armeabi-v7a** ABI.
- **Launcher icon and branding.**
- **Release pipeline**: signed APK/AAB, `versionCode` scheme, F-Droid or
  Play.
- **Keyboard shortcuts** on tablets and Chromebooks: the TUI's keys as
  they are.

## Housekeeping

- **Take the core from crates.io again**: `rust/Cargo.toml` pins
  `passalong-core`, `passalong-ssh`, and `passalong-https` to a commit of
  the client's v0.3.0 branch; once v0.3.0 is published, replace the git
  dependencies with `version = "=0.3.0"`.

## Agent suggested next steps

- **Connect only when needed.** As in passalong's own backlog: `choose`
  connects before showing a fresh cache, because the actions need the
  store; connecting on the first action would open the list at once.
- **Retry with backoff** when the connection drops mid-session, instead
  of reporting the failure in the status line and waiting for `R`.
- **Read the list cache written by the CLI** when the same store is used
  on a rooted device or through a shared folder; the identity string is
  already the same.
- **Strip the x86_64 library from release builds** once emulator use is
  no longer needed, halving the APK.
