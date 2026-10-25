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

// onnxruntime.dll, which the OCR loads, needs the Visual C++ runtime. Windows doesn't always have
// it, so these ship beside it; Microsoft allows that app-local copy. Without them OCR fails on a
// PC that lacks the redistributable, and every run stops on screens it can't read.
val vcRuntimeDlls = listOf("msvcp140.dll", "msvcp140_1.dll", "vcruntime140.dll", "vcruntime140_1.dll")

val publishSidecar by tasks.registering(Exec::class) {
    val out = appResources.map { it.dir("windows-x64/sidecar") }
    inputs.files(fileTree(sidecarProject) { exclude("bin/**", "obj/**") })
    outputs.dir(out)
    doFirst { delete(out) }
    // DebugType=None leaves the .pdb out of the app.
    commandLine(
        "dotnet", "publish", sidecarProject.path, "-c", "Release", "-r", "win-x64",
        "--self-contained", "true", "-p:DebugType=None", "-o", out.get().asFile.path
    )
    doLast {
        val system32 = File(System.getenv("WINDIR") ?: "C:\\Windows", "System32")
        for (name in vcRuntimeDlls) {
            val dll = File(system32, name)
            if (!dll.isFile) {
                throw GradleException("$dll is missing. Install the Microsoft Visual C++ Redistributable (x64) on this PC, then build again.")
            }
            dll.copyTo(out.get().file(name).asFile, overwrite = true)
        }
    }
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
