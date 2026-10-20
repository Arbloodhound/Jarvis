import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * The release signing key, or null when this machine does not have it.
 *
 * A build without the key still works and is signed with the debug key; it is
 * simply not something to publish. Gradle would otherwise fail on every clone
 * that has no business holding the secret.
 */
val releaseKeystore: Properties? =
    // rootProject, not the app module: file() here would look inside app/.
    rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }
    }

android {
    // The shared gate vectors (src/test/resources/gate_vectors.json) are also read on the
    // device by SharedVectorsDeviceTest: one file, no copy to drift.
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")

    testOptions {
        // ScreenStructure logs which method chose the records, which is the
        // only way to tell on a device whether the template match or the
        // fallback ran. android.util.Log throws in a JVM unit test unless
        // stubs return defaults.
        unitTests.isReturnDefaultValues = true
        // The screen tests (ui/ScreensTest): Compose screens rendered and tapped on the JVM (Robolectric).
        unitTests.isIncludeAndroidResources = true
    }

    namespace = "com.agent.ultra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.agent.ultra"
        minSdk = 26
        targetSdk = 35
        // release-apk.yml passes -PciBuildNumber=<run number>, so every GitHub build is a newer
        // version (17 + N, "3.0.N"; "2.6.N" up to run 34) and an updater such as Obtainium sees it. Local builds: 17.
        val ciBuild = (findProperty("ciBuildNumber") as String?)?.let {
            it.toIntOrNull()?.takeIf { n -> n > 0 } ?: error("ciBuildNumber must be a positive integer, got '$it'")
        }
        versionCode = 17 + (ciBuild ?: 0)
        versionName = if (ciBuild != null) "3.0.$ciBuild" else "3.0.0-native"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // The phone is arm64. `-Pbench` builds for the x86_64 emulator instead (AndroidWorld):
        // same package, same output paths, no on-device model — CMakeLists skips non-arm64 ABIs,
        // and LlmNative reports the library missing rather than dying.
        ndk { abiFilters += listOf(if (project.hasProperty("bench")) "x86_64" else "arm64-v8a") }
    }

    signingConfigs {
        // Same keystore the Expo builds used (android/app/debug.keystore, copied
        // here) — install-over continuity on devices carrying a Build 29/30 install.
        // Kept OUT of git since 2026-09-11 (gitignored; backup in ~/keys): anyone
        // holding it can sign an update over those installs. When it's absent —
        // a fresh clone — builds use the SDK's standard debug key instead.
        if (file("debug.keystore").exists()) {
            create("legacyDebug") {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

        // GitHub's test builds (debug-apk.yml sets JARVIS_TEST_KEYSTORE to ci/test-debug.keystore):
        // one fixed test key, so each test APK installs over the last and keeps the downloaded
        // model. Debug builds only; see ci/README.md for why it is in the repo.
        System.getenv("JARVIS_TEST_KEYSTORE")?.takeIf { file(it).exists() }?.let { path ->
            create("ciTestDebug") {
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

        // The real signing key, for anything that leaves this machine.
        //
        // 2.0.0 and 2.1.0 went out signed with the DEBUG key, whose password is
        // "android" and which ships inside the Android SDK. Anyone at all can
        // sign an APK with it, and Android would accept that stranger's build
        // as a legitimate update to this app, installing straight over it.
        //
        // keystore.properties is gitignored and holds the only copy of the
        // password. When it is absent — a fresh clone, or anyone else's machine
        // — the build falls back to the debug key rather than failing, because
        // a local build is not a release and should not need the secret.
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore!!.getProperty("storeFile"))
                storePassword = releaseKeystore!!.getProperty("storePassword")
                keyAlias = releaseKeystore!!.getProperty("keyAlias")
                keyPassword = releaseKeystore!!.getProperty("keyPassword")
                // v3 carries a proof-of-rotation record, so this key can be
                // replaced later without every install having to be removed.
                // If this key is ever exposed, that is the difference between
                // an update and starting again. v1 is off: it is only needed
                // below API 24 and minSdk here is 26.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // release-apk.yml with test_kit=true: the test build signed with the real key, so the
            // owner downloads the models once and the release later installs over it as an update.
            signingConfig = (if (System.getenv("JARVIS_DEBUG_WITH_RELEASE_KEY") == "1")
                signingConfigs.findByName("release") else null)
                ?: signingConfigs.findByName("legacyDebug")
                ?: signingConfigs.findByName("ciTestDebug")
                ?: signingConfigs.getByName("debug")
        }
        release {
            // No debug-key fallback here. 2.3.0 was published debug-signed
            // because this silently fell back, and a debug-signed APK can be
            // updated by anyone: that key's password ships in the public SDK.
            // Without the release key a release build now fails outright — see
            // the taskGraph check below.
            signingConfig = signingConfigs.getByName(
                if (releaseKeystore != null) "release"
                else if (signingConfigs.findByName("legacyDebug") != null) "legacyDebug"
                else "debug"
            )
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.28.3"
        }
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // openWakeWord acoustic gate (ONNX Runtime). Verify latest version on Maven.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")   // 16KB-aligned natives; WakeGate verified against this API
    // Unpacks the own-voice download (a .tar.bz2: Android has no public bzip2 or tar API).
    // Apache-2.0, pure Java; only its bzip2 and tar readers are used, R8 drops the rest.
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Text in a picture of the screen (ScreenVision): the bundled Latin model, inside the APK, offline.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Tor inside the app (net.EmbeddedTor): Guardian Project's tor-android, BSD-3, the Tor library
    // Orbot is built on; 16 KB-aligned natives. Its classes are Java, so its Kotlin 2.3 stdlib
    // dependency (newer than this project's compiler reads) is left out. 0.4.9.5: later releases need compileSdk 37.
    // Google sign-in for the Gmail / Calendar / Drive / Docs connectors: Google's own Android
    // authorization API (custom-scheme browser redirects are retired for Android OAuth clients).
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("info.guardianproject:tor-android:0.4.9.5") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json on the JVM test classpath (Android ships it; unit tests need it)
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Jarvis's own voice (sherpa-onnx TTS) is a prebuilt library fetched outside Gradle, like the
// llama/whisper static libs. Without it the APK would still build and quietly speak with the
// platform engine only, so an arm64 build stops here instead. `-Pbench` (x86_64) skips it.
val sherpaJni = file("src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so")
val checkSherpaLib by tasks.registering {
    val bench = project.hasProperty("bench")
    doLast {
        if (!bench && !sherpaJni.exists()) throw GradleException(
            "Missing $sherpaJni (Jarvis's own voice). Run: bash ultra-native/ondevice-brain/fetch-sherpa.sh"
        )
    }
}
// Hooked to the JNI-library merge, not preBuild: the JVM unit tests don't need the library.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(checkSherpaLib) }

// A release build without the release key must fail, not quietly produce a
// debug-signed APK that looks publishable. 2.3.0 went out that way: signed
// CN=Android Debug, whose password is inside the public Android SDK, so anyone
// could sign an update over it. Debug builds are unaffected.
gradle.taskGraph.whenReady {
    if (releaseKeystore == null &&
        allTasks.any { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }
    ) {
        throw GradleException(
            "No keystore.properties: this machine does not hold the release key. " +
                "Use assembleDebug, or add keystore.properties before building a release."
        )
    }
}
