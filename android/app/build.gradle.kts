import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionProperties = Properties().apply {
    val versionFile = rootProject.file("version.properties")
    if (versionFile.isFile) {
        versionFile.reader(Charsets.UTF_8).use { reader -> load(reader) }
    }
}

val pebblePileVersionName =
    providers.gradleProperty("pebblepileVersionName").orNull
        ?: providers.environmentVariable("PEBBLEPILE_VERSION_NAME").orNull
        ?: versionProperties.getProperty("versionName", "0.1.0-dev")

val pebblePileVersionCode =
    providers.gradleProperty("pebblepileVersionCode").orNull
        ?: providers.environmentVariable("PEBBLEPILE_VERSION_CODE").orNull
        ?: versionProperties.getProperty("versionCode", "1")

require(Regex("""^[0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z]+)*$""").matches(pebblePileVersionName)) {
    "Invalid PebblePile version name: $pebblePileVersionName"
}
require(pebblePileVersionCode.toIntOrNull()?.let { it > 0 } == true) {
    "PebblePile versionCode must be a positive integer: $pebblePileVersionCode"
}

val releaseKeystorePath = providers.environmentVariable("PEBBLEPILE_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("PEBBLEPILE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("PEBBLEPILE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("PEBBLEPILE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

if (hasReleaseSigning) {
    require(project.file(requireNotNull(releaseKeystorePath)).isFile) {
        "Release keystore does not exist: $releaseKeystorePath"
    }
}

android {
    namespace = "com.pebblepile.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pebblepile.android"
        minSdk = 26
        targetSdk = 35
        versionCode = pebblePileVersionCode.toInt()
        versionName = pebblePileVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = project.file(requireNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

tasks.matching { name == "packageRelease" || name == "bundleRelease" }.configureEach {
    if (!hasReleaseSigning) {
        doFirst {
            throw GradleException(
                "Release signing is not configured. Set PEBBLEPILE_KEYSTORE_PATH, " +
                    "PEBBLEPILE_KEYSTORE_PASSWORD, PEBBLEPILE_KEY_ALIAS and " +
                    "PEBBLEPILE_KEY_PASSWORD.",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.5")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
