import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.UUID
import org.gradle.api.tasks.testing.Test

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

    sourceSets.getByName("test").resources.srcDir(rootProject.file("../docs/testing/issue10"))

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

// Git archives keep the version from version.properties. A checkout also identifies
// the source revision in the installable debug APK, including incremental builds.
val checkoutDir = rootProject.projectDir.parentFile
if (checkoutDir.resolve(".git").exists()) {
    fun gitOutput(vararg args: String) = providers.exec {
        workingDir(checkoutDir)
        commandLine("git", *args)
    }.standardOutput.asText.map { it.trim() }

    val revision = gitOutput("rev-parse", "--short=12", "HEAD")
    val changes = gitOutput("--no-optional-locks", "status", "--porcelain", "--untracked-files=normal", "--", "android")
    val debugVersionName = revision.zip(changes) { hash, status ->
        if (status.isEmpty()) "$pebblePileVersionName+$hash" else "$pebblePileVersionName+$hash.dirty"
    }

    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output -> output.versionName.set(debugVersionName) }
    }
}

val cleanDeviceTestApks = tasks.register<Delete>("cleanDeviceTestApks") {
    delete(layout.buildDirectory.dir("outputs/apk/androidTest"))
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn(cleanDeviceTestApks)
}

// When the caller requests both APKs, package the test runner after cleanup.
tasks.matching { it.name == "packageDebugAndroidTest" }.configureEach {
    mustRunAfter(cleanDeviceTestApks)
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.10.1")
}

// Gradle passes project-local test outputs through a JVM argument file. On Windows,
// non-ASCII checkout paths can be decoded with the platform charset before the test
// worker starts, which makes every compiled test class appear to be missing.
if (File.separatorChar == '\\' && !StandardCharsets.US_ASCII.newEncoder().canEncode(rootDir.absolutePath)) {
    val projectRoot = rootDir.toPath().toAbsolutePath().normalize()
    val checkoutId = UUID.nameUUIDFromBytes(projectRoot.toString().toByteArray(Charsets.UTF_8))
    tasks.withType<Test>().configureEach {
        doFirst {
            val mirrorRoot = File(System.getProperty("java.io.tmpdir"), "pebblepile-android-tests/$checkoutId/$name")
            classpath = files(classpath.files.map { entry ->
                val source = entry.toPath().toAbsolutePath().normalize()
                if (!source.startsWith(projectRoot) || !entry.exists()) {
                    entry
                } else {
                    val target = File(mirrorRoot, projectRoot.relativize(source).toString())
                    if (entry.isDirectory) {
                        project.sync { from(entry); into(target) }
                    } else {
                        target.parentFile.mkdirs()
                        entry.copyTo(target, overwrite = true)
                    }
                    target
                }
            })
        }
    }
}
