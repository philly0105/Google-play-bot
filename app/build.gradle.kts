import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// The JDK comes from Build.ps1 (tools/jdk); no toolchain provisioning.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnit()
    // One summary line per run, so the counts can be quoted without opening the reports.
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ suite, result ->
        if (suite.parent == null) {
            println("Tests: ${result.testCount} ran, ${result.successfulTestCount} passed, " +
                "${result.failedTestCount} failed, ${result.skippedTestCount} skipped")
        }
    }))
}

compose.desktop {
    application {
        mainClass = "local.clashfarmer.desktop.MainKt"
        nativeDistributions {
            packageName = "ClashFarmerPC"
            packageVersion = "1.0.0"
        }
    }
}
