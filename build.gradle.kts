plugins {
    base
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.spring") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("org.springframework.boot") version "4.0.7" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.github.uncomplexco.sidekick"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
