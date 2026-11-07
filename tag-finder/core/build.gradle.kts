import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}

// Monte-Carlo accuracy report: ./gradlew -PcoreOnly :core:simulate
tasks.register<JavaExec>("simulate") {
    group = "verification"
    description = "Runs the synthetic-room accuracy benchmark."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("tagfinder.core.SimulateKt")
}
