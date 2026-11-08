plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

dependencies {
    compileOnly(platform(libs.spring.boot.bom))
    api(platform(libs.ktor.bom))
    api(libs.slack.api.client)

    compileOnly(libs.spring.boot)
    compileOnly(libs.spring.context)
    compileOnly(libs.spring.web)
    implementation(libs.kotlin.reflect)
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.snakeyaml)
    implementation(libs.pebble)
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
    testImplementation(libs.mockito.core)
    testRuntimeOnly(libs.junit.platform.launcher)
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesImplementation(libs.spring.context)
    testFixturesImplementation(libs.spring.boot)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> { useJUnitPlatform() }

val jsBridgeDirectory = rootProject.layout.projectDirectory.dir("js-bridge")

val installJsBridge by
    tasks.registering(Exec::class) {
        group = "build setup"
        description = "Install locked JavaScript bridge dependencies for explicit code generation."
        workingDir(jsBridgeDirectory)
        commandLine("pnpm", "install", "--frozen-lockfile")
    }

tasks.register("generateChatUiPrompt") {
    group = "code generation"
    description =
        "Regenerate the checked-in chat UI prompt from json-render's standard chat catalog."
    dependsOn(installJsBridge)

    doLast {
        val prompt =
            providers
                .exec {
                    workingDir(jsBridgeDirectory)
                    commandLine("node", "chat-ui/print-prompt.mjs")
                }
                .standardOutput
                .asText
                .get()
        check(prompt.isNotBlank()) { "Chat UI prompt generator produced empty output." }

        val resource = layout.projectDirectory.file("src/main/resources/prompts/chat-ui.md").asFile
        resource.parentFile.mkdirs()
        resource.writeText(prompt, Charsets.UTF_8)
        logger.lifecycle("Generated {}", resource)
    }
}
