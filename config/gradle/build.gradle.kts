plugins {
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
}

layout.buildDirectory.set(layout.projectDirectory.dir("../../build"))
subprojects {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("native/$name"))
}
