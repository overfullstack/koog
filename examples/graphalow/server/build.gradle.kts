import io.ktor.plugin.features.DockerImageRegistry.Companion.googleContainerRegistry

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(ktorLibs.plugins.ktor)
}

application {
    mainClass.set("org.salesforce.swara.SchedulerAgentAppKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

tasks.register<JavaExec>("runA2AServers") {
    description = "Run the A2A mesh servers (Scheduler/Appointment Booking)"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.salesforce.swara.a2a.A2AServerLauncherKt")
}

group = "org.salesforce.demo"
version = "1.0.0"

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.addAll("-Xcontext-sensitive-resolution", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    api(platform(libs.http4k.bom))
    api(libs.bundles.http4k)
    implementation(fileTree(mapOf("dir" to "jar", "include" to listOf("*.jar"))))
    implementation("dev.zacsweers.moshix:moshi-adapters:0.34.1")
    implementation("com.squareup.moshi:moshi-adapters:1.15.2")
    implementation("org.graalvm.js:js-language:25.0.1")

    // Okio for I/O operations (used by ReVoman) - using same versions as graphalow
    implementation("com.squareup.okio:okio-jvm:3.16.4")
    
    // Kotlinx datetime (may be used by ReVoman for date/time handling)
    implementation("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.7.1-0.6.x-compat")
    
    // Kotlin Faker for generating fake data (used by ReVoman for dynamic variables)
    implementation("io.github.serpro69:kotlin-faker:1.16.0")
    
    // Underscore for utility functions (may be used by ReVoman)
    implementation("com.github.javadev:underscore:1.119")
    
    // Spring Beans (may be used by ReVoman for dependency injection/bean management)
    implementation("org.springframework:spring-beans:7.0.2")
    
    // PPrint for pretty printing (may be used by ReVoman for debugging/formatting)
    implementation("io.exoquery:pprint-kotlin:3.0.0")
    
    // JetBrains Annotations (may be used by ReVoman)
    compileOnly("org.jetbrains:annotations:26.0.2-1")
    
    // Kotlin Logging bundle (may be used by ReVoman)
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.14")
    implementation("org.apache.logging.log4j:log4j-api:2.25.3")
    implementation("org.apache.logging.log4j:log4j-core:2.25.3")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.3")
    
    // Immutables (may be used by ReVoman for code generation)
    // Note: These are typically used with kapt, but adding as compileOnly for annotations
    compileOnly("org.immutables:value-annotations:2.12.0")
    api(libs.java.vavr)
    api(libs.kotlin.vavr)
    api(libs.arrow.core)
    implementation(libs.ktor.server.websockets)
    implementation(libs.logback)
    
    implementation(libs.koog.agents)
    implementation(libs.koog.ktor)
    implementation(libs.a2a.server)
    implementation(libs.a2a.client)
    implementation(libs.a2a.transport.server.jsonrpc.http)
    implementation(libs.a2a.transport.client.jsonrpc.http)
    implementation(libs.agents.features.a2a.server)
    implementation(libs.agents.features.a2a.client)
    implementation(libs.agents.features.opentelemetry)
    implementation(libs.agents.features.memory)

    implementation(libs.tool.schema)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)
    testImplementation(ktorLibs.server.testHost)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.server.test.host.jvm)
}

ktor {
    docker {
        localImageName = "graphalow"
        imageTag = project.version.toString()
        externalRegistry =
            googleContainerRegistry(
                projectName = provider { "Graphalow" },
                appName = providers.environmentVariable("GCLOUD_APPNAME"),
                username = providers.environmentVariable("GCLOUD_USERNAME"),
                password = providers.environmentVariable("GCLOUD_REGISTRY_PASSWORD"),
            )
    }
    fatJar {
        allowZip64 = true
        archiveFileName.set("graphalow.jar")
    }
}
