import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "worktree-tasks"

pluginManagement {
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
    }
}

plugins {
    // Auto-provisions the JDK 21 toolchain the build needs (only JDK 25 is installed locally).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.16.0"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
