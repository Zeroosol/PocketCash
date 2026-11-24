import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin: models, money rules, ATM cycles and CSV, with no Android code (docs/spec.md sections 2-4).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The app saves its data as one JSON file, so the models are serializable.
    api(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The shared test vectors, also checked by the iPhone app's tests.
val vectors = rootDir.resolve("../spec/vectors")

tasks.test {
    useJUnitPlatform()
    systemProperty("pocketcash.vectors", vectors.canonicalPath)
    inputs.dir(vectors).withPropertyName("vectors").withPathSensitivity(PathSensitivity.RELATIVE)
}
