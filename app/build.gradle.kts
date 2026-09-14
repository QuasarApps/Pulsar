import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kover)
}

// Release signing: loaded from a gitignored `keystore.properties` at the repo root
// (preferred for local builds) or from environment variables (preferred for CI).
// If neither is present, assembleRelease still works but produces an unsigned APK —
// fine for CI smoke-tests, not uploadable to Play.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey) ?: System.getenv(envKey)

val signingStoreFile = signingValue("storeFile", "PULSAR_KEYSTORE_PATH")
val signingStorePassword = signingValue("storePassword", "PULSAR_KEYSTORE_PASSWORD")
val signingKeyAlias = signingValue("keyAlias", "PULSAR_KEY_ALIAS")
val signingKeyPassword = signingValue("keyPassword", "PULSAR_KEY_PASSWORD")

// Version is overridable from the environment so CI/release automation can stamp a unique,
// monotonically-increasing versionCode (e.g. from the CI run number or a release tag) instead of
// relying on a human to bump a hardcoded literal. Unset -> baseline; but a value that is *set yet
// invalid* fails the build loudly rather than silently shipping/colliding the baseline `1`.
val appVersionCode = System.getenv("PULSAR_VERSION_CODE")?.let { raw ->
    raw.toIntOrNull()?.takeIf { it > 0 }
        ?: error("PULSAR_VERSION_CODE must be a positive integer, but was: '$raw'")
} ?: 1
val appVersionName = System.getenv("PULSAR_VERSION_NAME")?.let { raw ->
    raw.ifBlank { error("PULSAR_VERSION_NAME is set but blank") }
} ?: "1.0.0"

val signingValues = listOf(
    signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword,
)
val hasReleaseSigning = signingValues.all { !it.isNullOrBlank() }
val hasPartialReleaseSigning =
    !hasReleaseSigning && signingValues.any { !it.isNullOrBlank() }

if (hasPartialReleaseSigning) {
    // Surface this loudly — a partial config is almost always a misconfiguration
    // (typo in a property name, missing env var on CI, etc.) and the silent
    // fallback to an unsigned APK only gets caught at upload time.
    logger.warn(
        "Pulsar: partial release signing config detected. " +
            "Some of [storeFile, storePassword, keyAlias, keyPassword] are set " +
            "but not all — :app:assembleRelease will produce an UNSIGNED APK. " +
            "Check keystore.properties or the PULSAR_KEYSTORE_* / PULSAR_KEY_* " +
            "environment variables.",
    )
}

android {
    namespace = "com.quasarapps.pulsar"
    // compileSdk 37 (Android 16 QPR toolchain) is the floor required by the current AndroidX cohort:
    // core(-ktx) 1.19 and lifecycle 2.11 demand 37, activity 1.13 / navigation 2.9.8 demand 36. AGP 9.3
    // (min Gradle 9.5, see the wrapper) supports up to compileSdk 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.quasarapps.pulsar"
        minSdk = 26
        // targetSdk 36 opts into the Android 16 behavior changes (Play requires >= 36 from Aug 2026).
        // The ones that touch this app: predictive back on by default (no onBackPressed/KEYCODE_BACK
        // anywhere — back runs through Navigation Compose, which supports it), edge-to-edge opt-out
        // removed (already edge-to-edge via enableEdgeToEdge + safeDrawing insets), and orientation/
        // aspect-ratio restrictions ignored on sw>=600dp (none declared). Robolectric note: the JVM
        // suite runs at this SDK level by default, so bumping targetSdk moves it to the SDK 36 jar.
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Locale note: the English-baseline instrumented UI tests assert literal English copy and
        // en-US dates, so they must run with the app in English. The CI Gradle Managed Devices
        // (`pixel2api30`/`pixel2api36`, below) are en-US, so CI needs nothing extra. To run the connected suite on
        // a physical device whose system language is NOT English, pin the debug app to English
        // first (Android 13+):
        //   adb shell cmd locale set-app-locales com.quasarapps.pulsar.debug --locales en-US
        // Tests that exercise other locales set their own configuration and are unaffected.
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // The `.debug` applicationId suffix lets a debug build coexist with an installed release
            // build on the same device. The launcher label resolves `${appLabel}` to
            // `@string/app_name` (same as release) so the debug build's name is localized too — a
            // hardcoded "Pulsar (debug)" here would override every translation and show English
            // on non-English devices.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "@string/app_name"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["appLabel"] = "@string/app_name"
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    // composeOptions { kotlinCompilerExtensionVersion } is no longer needed: the Compose compiler
    // is applied as the org.jetbrains.kotlin.plugin.compose Gradle plugin (Kotlin 2.0+).

    testOptions {
        // Zero out device animation scales before instrumentation runs (the AGP-native equivalent
        // of the old emulator-runner's `disable-animations: true`). Keeps the popup/dialog-layer
        // Compose tests — e.g. the DetailScreen dropdown→dialog flow — stable on the emulator.
        animationsDisabled = true

        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }

        // Gradle Managed Devices: AGP provisions/boots/tears down the emulator, so the instrumentation
        // suite runs with the same `./gradlew :app:pixel2api<NN>DebugAndroidTest` command locally and
        // in CI. `aosp-atd` is an Automated Test Device image — headless- and CI-optimised, and matches
        // the app (no Google Play Services dependency). Two API levels, same device profile, so the CI
        // legs differ by platform version only: 30 is the old-platform coverage (minSdk 26 era), 36
        // exercises the Android 16 behavior changes the app opts into via targetSdk 36 (predictive
        // back, edge-to-edge enforcement). Note the android-36 aosp_atd image currently ships under
        // the preview license — CI accepts all SDK licenses up front, and locally a one-time
        // `sdkmanager --licenses` does the same.
        managedDevices {
            localDevices {
                create("pixel2api30") {
                    device = "Pixel 2"
                    apiLevel = 30
                    systemImageSource = "aosp-atd"
                }
                create("pixel2api36") {
                    device = "Pixel 2"
                    apiLevel = 36
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

kotlin {
    // Kotlin compilation target, set via the Kotlin Gradle plugin's compilerOptions DSL — replaces
    // the deprecated android.kotlinOptions block. Pairs with the Java source/target in
    // android.compileOptions above.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

kover {
    reports {
        verify {
            // A regression *floor*, not a quality target. Kover measures the JVM unit/Robolectric
            // suite only — the instrumented (androidTest) suite isn't counted — so the bound is set
            // well below the current ~71% line coverage. That way legitimately adding code that's
            // exercised only by instrumented tests can't trip the gate, while a catastrophic drop
            // (deleting a swathe of tests, adding a large untested module) still fails CI.
            // Enforced via `:app:koverVerify` in the debug CI job.
            rule {
                minBound(60)
            }
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.text.google.fonts)

    // Navigation + lifecycle (multi-counter screens)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // LocalLifecycleOwner + repeatOnLifecycle, to pause the foreground tickers off-screen.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Persistence (milestones + widget bindings)
    implementation(libs.androidx.datastore.preferences)

    // Home-screen widgets (Glance / Compose)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Periodic background refresh for placed widgets.
    implementation(libs.androidx.work.runtime.ktx)

    // Coroutines runtime, pinned explicitly so the app APK's coroutines-core matches the
    // coroutines-test version used by the suites. In the split-APK instrumented tests, coroutines-test
    // resolves its runtime against the *app* APK's core (not the androidTest configuration), so a core
    // older than the test artifact throws NoSuchMethodError (BuildersKt.runBlockingK). Keep this in
    // lockstep with kotlinx-coroutines-test — both use the `coroutines` version ref.
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)

    // Compose UI tests run on the JVM under Robolectric (no emulator needed).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // On-device test infrastructure: ApplicationProvider, ActivityScenario(Rule), runner/rules.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)

    // WorkManager test helpers (TestListenableWorkerBuilder) for the widget refresh worker.
    androidTestImplementation(libs.androidx.work.testing)

    // Coroutine test helpers (runTest, test dispatchers) for repository / view-model tests.
    androidTestImplementation(libs.kotlinx.coroutines.test)

    // Glance widget composable unit testing (runGlanceAppWidgetUnitTest).
    androidTestImplementation(libs.androidx.glance.testing)
    androidTestImplementation(libs.androidx.glance.appwidget.testing)

    // Compose UI tests on a device/emulator. The stub ComponentActivity that createComposeRule()
    // hosts content in comes from the debugImplementation(ui-test-manifest) above, merged into the
    // app-under-test's debug manifest, so it does not need repeating here.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}