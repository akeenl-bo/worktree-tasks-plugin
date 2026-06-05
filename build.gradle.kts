import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

kotlin {
    // 2025.3 platform runs on JBR 21; build against a 21 toolchain (foojay auto-provisions it).
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // Target 2025.3 for the reworked (public) terminal API: TerminalToolWindowManager.
        intellijIdea("2025.3")

        // Bundled plugins we integrate with: Git for worktree ops, terminal for launching `claude`.
        bundledPlugins("Git4Idea", "org.jetbrains.plugins.terminal")

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    // Optional step that launches a headless IDE to pre-index settings search; it needs a large
    // heap and isn't worth the flakiness for a plugin this size.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
    }
}

tasks {
    runIde {
        // The sandbox IDE defaults to a small heap (~512 MB), which OOMs while indexing a real
        // project. Give it room. Bump higher if you open very large repos.
        maxHeapSize = "4g"
    }
}
