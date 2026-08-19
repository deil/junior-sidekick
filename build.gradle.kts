plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "com.github.uncomplexco.sidekick"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
