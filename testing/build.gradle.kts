import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Test-only module: other modules use it as testImplementation, never in production code.
dependencies {
    api(project(":data:protocol"))
    api(project(":domain"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
}
