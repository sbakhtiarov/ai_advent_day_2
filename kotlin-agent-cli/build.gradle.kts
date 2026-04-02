import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
}

group = "com.aichallenge.day2"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.3.3"
val coroutinesVersion = "1.10.2"
val serializationVersion = "1.9.0"
val datetimeVersion = "0.7.1"
val mcpSdkVersion = "0.8.4"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    val nativeTarget = if (System.getProperty("os.arch") == "aarch64") {
        macosArm64("native")
    } else {
        macosX64("native")
    }

    nativeTarget.binaries {
        executable {
            baseName = "agent-cli"
            entryPoint = "com.aichallenge.day2.agent.main"
        }
    }

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-server-core:$ktorVersion")
                implementation("io.ktor:ktor-server-netty:$ktorVersion")
                implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
                implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                implementation("ch.qos.logback:logback-classic:1.5.18")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-client-mock:$ktorVersion")
                implementation("io.ktor:ktor-server-test-host:$ktorVersion")
            }
        }

        val nativeMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-curl:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-sse:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-client-logging:$ktorVersion")
                implementation("io.modelcontextprotocol:kotlin-sdk-client:$mcpSdkVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")
            }
        }

        val nativeTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-client-mock:$ktorVersion")
            }
        }
    }
}

tasks.register<Jar>("jvmFatJar") {
    group = "build"
    description = "Builds a runnable fat jar for the JVM HTTP server."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.aichallenge.day2.agent.server.HttpServerMainKt"
    }
    dependsOn("jvmJar")
    from(kotlin.targets.getByName("jvm").compilations.getByName("main").output.allOutputs)
    from({
        configurations.getByName("jvmRuntimeClasspath")
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
}
