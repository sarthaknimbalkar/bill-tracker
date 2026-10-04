import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Dedicated key kept in /keystore. Losing it means the app can't be updated without
// uninstalling (which wipes data) — keep a copy of that folder somewhere safe.
val ksProps = Properties().apply {
    rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.local.billtracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.local.billtracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "2.1"    }

    // keystore/ is not in the repo; without it builds fall back to the standard debug key.
    val hasKey = ksProps.getProperty("storeFile") != null
    signingConfigs {
        if (hasKey) create("release") {
            storeFile = file(ksProps.getProperty("storeFile"))
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias")
            keyPassword = ksProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        val signing = if (hasKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
        debug {
            // Same key as release so debug and release builds can update each other without a reinstall.
            signingConfig = signing
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests (android.jar only has stubs)
}
