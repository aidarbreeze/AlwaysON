plugins {
    id("com.android.application")
    // NOTE: no org.jetbrains.kotlin.android — AGP 9 compiles Kotlin via its
    // built-in Kotlin support (that plugin is incompatible with the AGP 9 DSL).
}

android {
    namespace = "com.aidarbreeze.alwayson"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aidarbreeze.alwayson"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Replacement for the removed android.kotlinOptions{} block (AGP 9 built-in
// Kotlin migration): keep the bytecode target aligned with compileOptions.
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // AndroidX Security library for EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Render-verification screenshots (RenderScreenshotsTest, CI only).
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")

    // The app intentionally uses only the Android framework (no external
    // AndroidX / Material dependencies) so the project builds straight out of
    // the box in Android Studio without version-matching issues.
}
