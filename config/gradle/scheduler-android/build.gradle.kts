plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pebblepile.scheduler.work"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    val clientRoot = projectDir.resolve("../../..")
    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf(clientRoot.resolve("src/work")))
            manifest.srcFile(clientRoot.resolve("src/work/AndroidManifest.xml"))
        }
        getByName("test").java.setSrcDirs(listOf(clientRoot.resolve("test/scheduler-android")))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    api(project(":scheduler-core"))
    api("androidx.work:work-runtime-ktx:2.9.1")
}
