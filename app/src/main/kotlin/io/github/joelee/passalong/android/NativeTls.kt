package io.github.joelee.passalong.android

import android.content.Context

/**
 * The one JNI call of the app: hands the bridge the app's [Context], which the Rust side's TLS
 * verifier needs to ask Android's certificate store about a server without a pin. Everything
 * else crosses through UniFFI.
 */
object NativeTls {
    init {
        // JNA loads the same library for the bindings; a second load is a no-op.
        System.loadLibrary("passalong_ffi")
    }

    /** Initialises the verifier once; returns whether it succeeded. Later calls are no-ops. */
    external fun init(context: Context): Boolean
}
