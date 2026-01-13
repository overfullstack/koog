plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    application
}

group = rootProject.group
version = rootProject.version

application {
    mainClass.set("ai.koog.chat.ui.ChatUIServerKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)
}

tasks.register<JavaExec>("runChatUI") {
    description = "Run the Chat UI server"
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ai.koog.chat.ui.ChatUIServerKt")
}

