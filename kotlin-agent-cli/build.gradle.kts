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
val mcpSdkVersion = "0.8.4"

kotlin {
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
            }
        }

        val nativeTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
