# Backlog

Future work not covered by an active plan. Completed items are removed.

## Next milestones

- **Join an encrypted store**: type the store's six words, derive the key
  as `passalong encrypt --join` does, and keep it in `client.key_file`;
  also key import from a QR code shown by another device.
- **Sending**: share text and files into the app (`passalong clipboard`,
  `passalong file`), and a clipboard push.
- **Clipboard images**: load a clipboard image onto the clipboard through
  a content URI instead of into Downloads.
- **Instrumented tests** for the Compose screens, the sinks, and
  `FfiCore`, against passalong's Docker OpenSSH server.
- **`check` screen**: the four steps `passalong check` prints, after
  setup.

## Later

- **armeabi-v7a** ABI.
- **Launcher icon and branding.**
- **Release pipeline**: signed APK/AAB, `versionCode` scheme, F-Droid or
  Play.
- **Keyboard shortcuts** on tablets and Chromebooks: the TUI's keys as
  they are.

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
