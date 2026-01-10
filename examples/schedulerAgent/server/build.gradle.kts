import io.ktor.plugin.features.DockerImageRegistry.Companion.googleContainerRegistry

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(ktorLibs.plugins.ktor)
    id("dev.zacsweers.moshix") version "0.34.1"
    id("com.github.node-gradle.node") version "7.1.0"
}

application {
    mainClass.set("org.jetbrains.demo.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

tasks.register<JavaExec>("runA2AServers") {
    description = "Run the A2A mesh servers (Route Planner, POI Researcher, Plan Composer)"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.jetbrains.demo.agent.a2a.A2AServerLauncherKt")
}

group = "org.jetbrains.demo"
version = "1.0.0"

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.addAll("-Xcontext-sensitive-resolution", "-Xannotation-default-target=param-property")
    }
}

dependencies {
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
    
    // ReVoman for Postman collection execution
    implementation(fileTree(mapOf("dir" to "jar", "include" to listOf("*.jar"))))
    
    // Vavr for Either type support (used by ReVoman) - using same versions as graphalow
    api("io.vavr:vavr:0.11.0") // Updated to match graphalow
    api("io.vavr:vavr-kotlin:0.10.2")
    
    // Arrow for Either type support (used by ReVoman) - using same versions as graphalow
    api("io.arrow-kt:arrow-core:2.2.1.1")
    
    // MoshiX adapters (required by ReVoman) - using api like graphalow example
    api("dev.zacsweers.moshix:moshi-adapters:0.34.1")
    
    // http4k for ReVoman (required for ListAdapter and other format classes)
    api(platform("org.http4k:http4k-bom:6.25.1.0"))
    api("org.http4k:http4k-core") // Core http4k functionality
    api("org.http4k:http4k-format-moshi")
    api("org.http4k:http4k-client-apache") // Required for HTTP requests in ReVoman
    
    // GraalVM JS for ReVoman (required for JavaScript evaluation in Postman collections)
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

    implementation(libs.tool.schema)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.contentNegotiation)
    
    // Database dependencies
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.20.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.0")
    implementation("org.jetbrains.exposed:exposed-core:0.50.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.50.1")
    implementation("org.postgresql:postgresql:42.7.4")
    
    testImplementation(ktorLibs.server.testHost)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.ktor.server.test.host.jvm)
}

moshi {
    enableSealed = true
}

ktor {
    docker {
        localImageName = "ktor-ai-example"
        imageTag = project.version.toString()
        externalRegistry =
            googleContainerRegistry(
                projectName = provider { "Scheduler Agent" },
                appName = providers.environmentVariable("GCLOUD_APPNAME"),
                username = providers.environmentVariable("GCLOUD_USERNAME"),
                password = providers.environmentVariable("GCLOUD_REGISTRY_PASSWORD"),
            )
    }
    fatJar {
        allowZip64 = true
        archiveFileName.set("scheduler-agent.jar")
    }
}
