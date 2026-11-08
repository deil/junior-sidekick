import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

val openTelemetryAgent by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    openTelemetryAgent(libs.opentelemetry.javaagent)
    implementation(project(":interfaces"))
    implementation(project(":tools-dumphere"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.kotlin.reflect)

    developmentOnly(libs.spring.boot.devtools)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> { useJUnitPlatform() }

val prepareOpenTelemetryAgent by
    tasks.registering(Copy::class) {
        group = "observability"
        description = "Prepare the OpenTelemetry Java agent for local and IDE launches."
        from(openTelemetryAgent)
        into(layout.buildDirectory.dir("otel"))
        rename { "opentelemetry-javaagent.jar" }
    }

tasks.named<BootRun>("bootRun") {
    dependsOn(prepareOpenTelemetryAgent)
    workingDir(rootProject.projectDir)
    jvmArgs(
        "-javaagent:${layout.buildDirectory.file("otel/opentelemetry-javaagent.jar").get().asFile}",
        "-Dotel.javaagent.configuration-file=${rootProject.file("observability/otel.properties")}",
    )
}

dependencyManagement {
    imports {
        mavenBom(
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom:${libs.versions.kotlinx.coroutines.get()}"
        )
    }
}
