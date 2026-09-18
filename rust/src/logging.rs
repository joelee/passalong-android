//! The core's logs, in its syslog-like format, sent to logcat on Android
//! and to standard error elsewhere. Nothing here logs content or secrets:
//! the core's format writes an allow-list of fields only.

use std::io::{self, Write};

use passalong_core::telemetry::{self, LogLevel, ParseLogLevelError, TelemetryError};
use tracing_subscriber::fmt::MakeWriter;

use crate::error::PassalongError;

/// The logcat tag every record carries.
const TAG: &str = "passalong";

/// Installs the process-wide logger at `level`: `error`, `warning`, `info`,
/// `verbose`, or `debug`. Calling it again does nothing, so the app may
/// call it whenever it starts.
#[uniffi::export]
pub fn init_logging(level: String) -> Result<(), PassalongError> {
    let level: LogLevel = level
        .parse()
        .map_err(|err: ParseLogLevelError| PassalongError::Config(err.to_string()))?;
    match telemetry::init(level, LogSink) {
        Ok(()) | Err(TelemetryError::AlreadyInitialised) => Ok(()),
    }
}

/// Hands the subscriber one [`LogLine`] per record.
struct LogSink;

impl<'a> MakeWriter<'a> for LogSink {
    type Writer = LogLine;

    fn make_writer(&'a self) -> Self::Writer {
        LogLine::default()
    }
}

/// Buffers one record and emits it whole when flushed or dropped, so a
/// record is never split across logcat lines.
#[derive(Default)]
struct LogLine {
    buf: Vec<u8>,
}

impl LogLine {
    fn emit(&mut self) {
        if self.buf.is_empty() {
            return;
        }
        let line = String::from_utf8_lossy(&self.buf).trim_end().to_owned();
        self.buf.clear();
        emit(priority_of(&line), &line);
    }
}

impl Write for LogLine {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.buf.extend_from_slice(bytes);
        Ok(bytes.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        self.emit();
        Ok(())
    }
}

impl Drop for LogLine {
    fn drop(&mut self) {
        self.emit();
    }
}

/// Android log priorities, as `android/log.h` numbers them.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Priority {
    Debug = 3,
    Info = 4,
    Warn = 5,
    Error = 6,
}

/// The priority of a record from the syslog severity in its second field:
/// `<timestamp> <severity> <target> ...`.
fn priority_of(line: &str) -> Priority {
    match line.split_whitespace().nth(1) {
        Some("err") => Priority::Error,
        Some("warning") => Priority::Warn,
        Some("debug") => Priority::Debug,
        _ => Priority::Info,
    }
}

#[cfg(target_os = "android")]
#[allow(unsafe_code)]
fn emit(priority: Priority, line: &str) {
    use std::ffi::CString;
    let Ok(tag) = CString::new(TAG) else {
        return;
    };
    // A record may contain NUL only from a corrupt file name; drop such
    // bytes rather than the record.
    let Ok(text) = CString::new(line.replace('\0', "")) else {
        return;
    };
    // SAFETY: `__android_log_write` reads two NUL-terminated strings that
    // live for the duration of the call and returns; it keeps no pointer.
    unsafe {
        android_log_sys::__android_log_write(priority as i32, tag.as_ptr(), text.as_ptr());
    }
}

#[cfg(not(target_os = "android"))]
fn emit(priority: Priority, line: &str) {
    let _ = writeln!(io::stderr(), "{TAG} {priority:?}: {line}");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn priorities_follow_the_syslog_severity_field() {
        let line = |severity: &str| format!("2026-09-12T09:53:11Z {severity} passalong_core x");
        assert_eq!(priority_of(&line("err")), Priority::Error);
        assert_eq!(priority_of(&line("warning")), Priority::Warn);
        assert_eq!(priority_of(&line("info")), Priority::Info);
        assert_eq!(priority_of(&line("notice")), Priority::Info);
        assert_eq!(priority_of(&line("debug")), Priority::Debug);
        assert_eq!(priority_of("garbage"), Priority::Info);
    }

    #[test]
    fn a_line_is_emitted_whole_once() {
        let mut line = LogLine::default();
        line.write_all(b"2026-09-12T09:53:11Z info t hello")
            .unwrap();
        line.flush().unwrap();
        assert!(line.buf.is_empty(), "emitted and cleared");
        line.flush().unwrap();
        drop(line);
    }

    #[test]
    fn init_accepts_every_level_once_and_rejects_unknown_ones() {
        let err = init_logging("loud".into()).unwrap_err();
        assert!(matches!(err, PassalongError::Config(_)), "{err:?}");
        init_logging("debug".into()).unwrap();
        init_logging("info".into()).unwrap();
        tracing::info!(id = "x", "a record for the sink");
    }
}
