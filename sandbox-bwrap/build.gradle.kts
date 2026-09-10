plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> { useJUnitPlatform() }
