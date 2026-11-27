plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
    sourceSets.test { kotlin.setSrcDirs(listOf(projectDir.resolve("../../test/scheduler-core"))) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
