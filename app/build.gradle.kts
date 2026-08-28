plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.majorgym.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.majorgym.app"
        // SecuGen FDx SDK Pro (fingerprint scanner) requires Android 8.1 (API 27)+
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    buildTypes {
        release {
            // Release hardening: shrink + obfuscate the release build (fix
            // "R8/minification is configured appropriately where safe").
            // Keep rules for the bundled SecuGen SDK/Room/etc. live in
            // proguard-rules.pro - both jars in app/libs are used via
            // reflection in places, so they need explicit keeps or R8 will
            // strip classes the native SDK looks up by name at runtime.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No debug-only behavior ships in release: this app has no
            // BuildConfig.DEBUG-gated logging or test backdoors to begin
            // with, and isDebuggable defaults to false for the release
            // build type, which we rely on rather than overriding.
        }
        debug {
            // Unambiguous even though these are Android Gradle Plugin's own
            // defaults - makes clear the debug/release split is intentional,
            // not accidental, per the release-hardening review.
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes.add("META-INF/*")
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    // Detects app foreground/background state reliably for the fingerprint kiosk
    // service (decides whether to just update the on-screen overlay directly, or
    // post a full-screen notification to bring the app forward first).
    implementation("androidx.lifecycle:lifecycle-process:2.8.2")
    // Schedules the daily long-expired-account cleanup check (Feature 4). This is
    // the standard, OS-recommended way to run reliable periodic background work —
    // survives app restarts/reboots without needing a custom alarm/scheduler.
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("io.coil-kt:coil-compose:2.6.0")

    // QR generation (member QR + static gym QR — spec sections 2 and 9)
    implementation("com.google.zxing:core:3.5.3")

    // Video playback for the startup splash screen
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // SecuGen FDx SDK Pro — USB fingerprint scanner (enroll + verify at check-in)
    implementation(files("libs/FDxSDKProFDAndroid.jar"))
    implementation(files("libs/AlCamera.jar"))
}
