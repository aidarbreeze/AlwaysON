// Top-level build file. Plugin versions are declared here and applied in
// module files.
plugins {
    // AGP 9.1+ (requires Gradle 9.3.1+): runs on Java 17-26, so the build
    // works with the Java 25 that current Android Studio versions ship.
    // Kotlin compilation is built into AGP 9 (built-in Kotlin) — the old
    // org.jetbrains.kotlin.android plugin must NOT be applied alongside it.
    id("com.android.application") version "9.1.1" apply false
}
