# rustls-platform-verifier's Kotlin component is called from Rust through
# JNI, which a shrinker cannot see.
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
