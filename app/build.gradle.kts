plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":tools"))

    compileOnly(platform(libs.spring.boot.bom))
    implementation(platform(libs.ktor.bom))
    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.boot.servlet)
    compileOnly(libs.spring.boot.webmvc)
    compileOnly(libs.jackson.annotations)
    implementation(libs.kotlinx.coroutines.core)
    compileOnly(libs.jakarta.servlet)

    implementation(libs.slack.bolt.servlet)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.webmvc.test)
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
