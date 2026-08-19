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
    implementation(project(":core"))
    implementation(project(":sandbox-bwrap"))

    compileOnly(platform(libs.spring.boot.bom))
    implementation(platform(libs.ktor.bom))
    api(libs.slack.api.client)

    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.web)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jsoup)
    implementation(libs.flexmark.html2md)

    implementation(libs.koog.agents)
    implementation(libs.koog.agents.mcp)
    implementation(libs.mcp.client)
    implementation(libs.mcp.core)
    implementation(libs.jgit.core)
    implementation(libs.jgit.ssh)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot)
    testImplementation(libs.spring.context)
    testImplementation(libs.spring.test)
    testImplementation(libs.spring.web)
    testImplementation(libs.kotlin.test.junit5)
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
