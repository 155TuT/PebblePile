plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    val clientRoot = projectDir.resolve("../../..")
    sourceSets.main { kotlin.setSrcDirs(listOf(clientRoot.resolve("lib/scheduler"))) }
    sourceSets.test { kotlin.setSrcDirs(listOf(clientRoot.resolve("test/scheduler-core"))) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
