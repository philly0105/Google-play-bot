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

// The sidecar (docs/SIDECAR.md) ships as an app resource, so run and the distributable both find it
// in `sidecar/` under the compose.application.resources.dir property. Self-contained, so the PC
// needs no .NET runtime; the OCR models come with the RapidOcrNet package.
val appResources = layout.buildDirectory.dir("appResources")
val sidecarProject = rootProject.file("native/ClashFarmer.Native")

val publishSidecar by tasks.registering(Exec::class) {
    val out = appResources.map { it.dir("windows-x64/sidecar") }
    inputs.files(fileTree(sidecarProject) { exclude("bin/**", "obj/**") })
    outputs.dir(out)
    doFirst { delete(out) }
    commandLine(
        "dotnet", "publish", sidecarProject.path, "-c", "Release", "-r", "win-x64",
        "--self-contained", "true", "-o", out.get().asFile.path
    )
}

compose.desktop {
    application {
        mainClass = "local.clashfarmer.desktop.MainKt"
        nativeDistributions {
            packageName = "ClashFarmerPC"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(appResources)
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(publishSidecar) }
