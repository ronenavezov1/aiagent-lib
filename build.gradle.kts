import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    `java-library`
    `maven-publish`
}

group = "com.aiagent"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // suspend מופיע ב-API הציבורי, אז מי שמשתמש בספרייה צריך אותו גם.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // לוגים דרך SLF4J בלבד. הפרויקט שמשתמש בספרייה בוחר מימוש (logback וכו').
    implementation("org.slf4j:slf4j-api:2.0.20")

    implementation("com.anthropic:anthropic-java:2.34.0")
    implementation("com.google.genai:google-genai:1.76.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.20")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
}

// ./gradlew publishToMavenLocal → implementation("com.aiagent:llm-agent:0.1.0")
publishing {
    publications {
        create<MavenPublication>("lib") {
            from(components["java"])
        }
    }
}
