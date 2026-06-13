import java.util.Properties
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties()
if (signingPropertiesFile.exists()) {
    signingPropertiesFile.inputStream().use { signingProperties.load(it) }
}

val stableDebugKeystoreFile = rootProject.file("keystore/cts_debug_update_key.jks")
val stableDebugKeystoreB64File = rootProject.file("keystore/cts_debug_update_key.jks.b64")
val stableDebugKeystoreAppFile = rootProject.file("app/keystore/cts_debug_update_key.jks")
val stableDebugKeystoreAppB64File = rootProject.file("app/keystore/cts_debug_update_key.jks.b64")

fun ensureStableDebugKeystore(): File {
    if (stableDebugKeystoreFile.exists()) return stableDebugKeystoreFile
    if (stableDebugKeystoreAppFile.exists()) return stableDebugKeystoreAppFile
    val sourceB64 = when {
        stableDebugKeystoreB64File.exists() -> stableDebugKeystoreB64File
        stableDebugKeystoreAppB64File.exists() -> stableDebugKeystoreAppB64File
        else -> null
    }
    if (sourceB64 != null) {
        stableDebugKeystoreFile.parentFile.mkdirs()
        stableDebugKeystoreFile.writeBytes(Base64.getDecoder().decode(sourceB64.readText().trim()))
        return stableDebugKeystoreFile
    }
    throw GradleException("Stable debug keystore missing. Expected keystore/cts_debug_update_key.jks or keystore/cts_debug_update_key.jks.b64. Use the full project ZIP or commit the keystore folder.")
}

val stableDebugKeystoreResolved = ensureStableDebugKeystore()

android {
    namespace = "com.ksp.cryptobot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ksp.cryptobot"
        minSdk = 26
        targetSdk = 35
        versionCode = 93
        versionName = "3.2.0"
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = stableDebugKeystoreResolved
            storePassword = "ctsdebug"
            keyAlias = "ctsdebug"
            keyPassword = "ctsdebug"
        }
        create("release") {
            if (signingPropertiesFile.exists()) {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (signingPropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("stableDebug")
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.moshi:moshi-adapters:1.15.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
