plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    compileOnly(platform(libs.spring.boot.bom))
    implementation(platform(libs.ktor.bom))
    api(libs.slack.api.client)

    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.web)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jgit.core)
    implementation(libs.jgit.ssh)

    api(libs.koog.agents)
    implementation(libs.koog.openrouter)
    implementation(libs.mcp.client)
    implementation(libs.mcp.core)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot)
    testImplementation(libs.spring.context)
    testImplementation(libs.spring.test)
    testImplementation(libs.spring.web)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
