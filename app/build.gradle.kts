import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dsh.mobile"
    compileSdk = 34
    // AGP 8.5 defaults to build-tools 34.0.0, which is not installed on this
    // machine (36.x is). Pin explicitly so the build resolves locally.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.dsh.mobile"
        minSdk = 29
        // MUST stay below 29.
        //
        // Android removed the implicit `execute_no_trans` permission for apps
        // targeting API 29+. Such an app cannot execve() ANY binary inside its
        // own writable storage. Verified exhaustively on an Android 16 device -
        // exec is denied in filesDir, getExternalFilesDir() and cacheDir alike:
        //   ".../dsh-runtime/proot: Permission denied"
        // while the identical binary runs fine under `run-as`, because that is
        // a different SELinux domain. This makes the entire PRoot runtime
        // impossible at targetSdk 34.
        //
        // Termux, the canonical Android terminal, uses targetSdk 28 for exactly
        // this reason. Trade-off: this APK must be sideloaded (it is published
        // on GitHub Releases, not the Play Store, which mandates 34+).
        targetSdk = (project.findProperty("dshTargetSdk") as String?)?.toInt() ?: 28

        // Paired with the targetSdk note above: this APK is sideloaded from
        // GitHub Releases, never the Play Store. Play mandates 34+, which would
        // re-break execve() of the PRoot binary and make the runtime unusable.
        lint {
            disable += "ExpiredTargetSdkVersion"
        }
        versionCode = 1
        versionName = "1.0.0"
    }


    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
}

// -- Release signing -------------------------------------------------------
// Signing material is intentionally NOT in the repository. Point this at your
// own keystore (see README) or drop a `keystore/signing.properties` next to
// the module. Without it the release build is simply unsigned rather than
// failing, so forks can still produce a debuggable APK.
val signingPropsFile = rootProject.file("keystore/signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) signingPropsFile.inputStream().use { load(it) }
}

android {
    signingConfigs {
        if (signingPropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file("keystore/${signingProps.getProperty("storeFile")}")
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
