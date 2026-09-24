//! TLS through Android's own trust store.
//!
//! Without a `tls_pin`, `passalong-https` asks the operating system whether
//! it trusts a server's certificate, through `rustls-platform-verifier`. On
//! Android that crate calls into the JVM, so it must be handed the app's
//! `Context` once, before the first connection: `PassalongApp` calls the
//! native method below at start. The Kotlin side of the verifier
//! (`rustls-platform-verifier-android`) is on the app's classpath.
//!
//! This is the one JNI entry point of the bridge; everything else crosses
//! through UniFFI, which has no access to a `JNIEnv`.

use jni::EnvUnowned;
use jni::objects::{JClass, JObject};
use jni::sys::jboolean;

/// `io.github.joelee.passalong.android.NativeTls.init(context)`: hands the
/// verifier the app's context. Returns whether that succeeded; a second call
/// is a no-op that succeeds. Never panics across the boundary: `with_env`
/// catches a panic and it is reported as a failure.
#[unsafe(no_mangle)]
#[allow(unsafe_code)]
pub extern "system" fn Java_io_github_joelee_passalong_android_NativeTls_init<'caller>(
    mut env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    context: JObject<'caller>,
) -> jboolean {
    // SAFETY-adjacent note: the JVM calls this with a live `JNIEnv` for the
    // current frame and a `Context` reference valid for the call; `with_env`
    // attaches them for the closure's duration and catches any panic, so
    // nothing unwinds into the JVM. The only unsafe act is the `no_mangle`
    // export, whose symbol the JVM resolves by this exact name.
    let initialised: bool = env
        .with_env(|env| -> Result<bool, jni::errors::Error> {
            rustls_platform_verifier::android::init_with_env(env, context)?;
            Ok(true)
        })
        .resolve::<jni::errors::LogErrorAndDefault>();
    if initialised {
        tracing::info!("Android TLS verifier initialised");
    } else {
        tracing::error!(
            "Android TLS verifier not initialised: the system trust store is unavailable"
        );
    }
    jboolean::from(initialised)
}
