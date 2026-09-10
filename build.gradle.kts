plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target(files(subprojects.map { it.fileTree("src") { include("**/*.kt") } }))
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }

    kotlinGradle {
        target(
            files(
                listOf(file("build.gradle.kts"), file("settings.gradle.kts")) +
                    subprojects.map { it.file("build.gradle.kts") }
            )
        )
        ktfmt(libs.versions.ktfmt.get()).kotlinlangStyle()
    }
}

allprojects {
    group = "com.github.uncomplexco.sidekick"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "dev.detekt")
}
