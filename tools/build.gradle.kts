plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.serialization")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.6"))
    api("com.slack.api:slack-api-client:1.49.0")

    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.3.20")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.vladsch.flexmark:flexmark-html2md-converter:0.64.8")

    implementation("ai.koog:koog-agents:1.0.0")
    implementation("ai.koog:agents-mcp:1.0.0-beta-preview7")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.8.1")
    implementation("io.modelcontextprotocol:kotlin-sdk-core:0.8.1")

    testImplementation("org.springframework:spring-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
