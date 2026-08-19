plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(project(":core"))

    compileOnly(platform(libs.spring.boot.bom))
    compileOnly(libs.spring.web)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.web)
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
