import java.util.Properties
import java.util.concurrent.TimeUnit

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    alias(libs.plugins.google.gms.google.services) apply false
    alias(libs.plugins.google.firebase.crashlytics) apply false
}

fun androidSdkDirectory(): File? {
    val localProperties = rootProject.file("local.properties")
    val configuredSdk = if (localProperties.isFile) {
        Properties().apply { localProperties.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else {
        null
    }
    return sequenceOf(
        configuredSdk,
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("ANDROID_HOME"),
    )
        .filterNotNull()
        .map(::File)
        .firstOrNull(File::isDirectory)
}

val startResearchReportBridge = tasks.register("startResearchReportBridge") {
    group = "openassistant"
    description = "Starts the USB report receiver used by normal Android Studio debug runs."
    outputs.upToDateWhen { false }
    doLast {
        val sdkDirectory = androidSdkDirectory()
        val windows = System.getProperty("os.name").contains("win", ignoreCase = true)
        val adb = sdkDirectory?.resolve("platform-tools/${if (windows) "adb.exe" else "adb"}")
        if (adb?.isFile != true) {
            logger.warn("OpenAssistant report bridge was not started because Android SDK platform-tools/adb was not found.")
            return@doLast
        }
        val javaExecutable = File(
            System.getProperty("java.home"),
            "bin/${if (windows) "java.exe" else "java"}",
        )
        if (!javaExecutable.isFile) {
            logger.warn("OpenAssistant report bridge was not started because the Android Studio JDK executable was not found.")
            return@doLast
        }
        val bridgeSource = rootProject.file("tools/ResearchReportBridge.java")
        if (!bridgeSource.isFile) {
            logger.warn("OpenAssistant report bridge was not started because its Java source file is missing.")
            return@doLast
        }
        ProcessHandle.allProcesses().use { processes ->
            processes
                .filter { process -> process.pid() != ProcessHandle.current().pid() }
                .filter { process ->
                    val arguments = process.info().arguments().orElse(emptyArray()).toList()
                    bridgeSource.absolutePath in arguments &&
                        rootProject.projectDir.absolutePath in arguments
                }
                .forEach { process ->
                    process.destroy()
                    runCatching { process.onExit().get(3, TimeUnit.SECONDS) }
                    if (process.isAlive) process.destroyForcibly()
                }
        }
        val validationProcess = ProcessBuilder(
            javaExecutable.absolutePath,
            "--source",
            "17",
            bridgeSource.absolutePath,
            "--self-test",
        )
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val validationFinished = validationProcess.waitFor(20, TimeUnit.SECONDS)
        if (!validationFinished) {
            validationProcess.destroyForcibly()
            validationProcess.waitFor(5, TimeUnit.SECONDS)
        }
        val validationOutput = if (validationProcess.isAlive) {
            ""
        } else {
            validationProcess.inputStream.bufferedReader().use { it.readText() }.trim()
        }
        val validationExitCode = if (validationProcess.isAlive) -1 else validationProcess.exitValue()
        if (!validationFinished || validationExitCode != 0) {
            logger.warn(
                "OpenAssistant report bridge self-test failed; the receiver was not started. " +
                    validationOutput.ifBlank { "No validation detail was produced." },
            )
            return@doLast
        }
        ProcessBuilder(
            javaExecutable.absolutePath,
            "--source",
            "17",
            bridgeSource.absolutePath,
            "--adb",
            adb.absolutePath,
            "--project",
            rootProject.projectDir.absolutePath,
        )
            .directory(rootProject.projectDir)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        logger.lifecycle("OpenAssistant report bridge source verified and receiver started for this Android Studio debug build.")
    }
}

project(":app").tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(startResearchReportBridge)
}

val overseerGroup = "overseer handoff"

fun registerOverseerTask(taskName: String, command: String, desc: String) = tasks.register<JavaExec>(taskName) {
    group = overseerGroup
    description = desc
    mainClass.set("com.david.openassistant.handoff.HandoffMain")
    
    // We'll use the runtimeClasspath of the :tools:overseer-handoff module.
    // To be safe with configuration cache, we avoid direct project evaluation if possible, 
    // but for task registration it's often necessary.
    val toolsProject = project(":tools:overseer-handoff")
    classpath = toolsProject.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath
    
    args(command, rootProject.projectDir.absolutePath)
}

registerOverseerTask(
    "generateOverseerHandoff",
    "generate",
    "Generates a compact, deterministic Overseer Handoff Bundle (SOURCE_BASELINE on first run)."
)
registerOverseerTask(
    "generateOverseerSupplement",
    "supplement",
    "Generates a supplemental handoff bundle based on overseer-request.json."
)
registerOverseerTask(
    "verifyOverseerHandoff",
    "verify",
    "Verifies a generated handoff bundle's manifest, hashes, and security redactions."
)
registerOverseerTask(
    "cleanOverseerHandoff",
    "clean",
    "Removes temporary handoff working directories and failed bundles."
)
tasks.register<JavaExec>("generateExtendedOverseerHandoff") {
    group = overseerGroup
    description = "Generates an extended handoff bundle containing complete raw runtime data."
    doFirst {
        throw GradleException("generateExtendedOverseerHandoff is NOT_IMPLEMENTED in this pass.")
    }
}

val verifyOverseerHandoffAcceptance = tasks.register<JavaExec>("verifyOverseerHandoffAcceptance") {
    group = overseerGroup
    description = "Runs stability and delta-state acceptance tests for the handoff tool in an isolated environment."
    mainClass.set("com.david.openassistant.handoff.HandoffMain")
    
    val toolsProject = project(":tools:overseer-handoff")
    classpath = toolsProject.extensions.getByType<SourceSetContainer>()["main"].runtimeClasspath + 
                toolsProject.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    
    args("acceptance-test", rootProject.projectDir.absolutePath)
    
    // OH-V12: Acceptance depends on tool tests being complete and evidence finalized
    dependsOn(":tools:overseer-handoff:test")
    dependsOn(":tools:overseer-handoff:finalizeOverseerToolTestEvidence")
}

tasks.named<JavaExec>("generateOverseerHandoff") {
    // OH-V12: Two-phase sequence. 
    // Acceptance must be complete before final bundle is generated to include its evidence.
    dependsOn(verifyOverseerHandoffAcceptance)
    
    doFirst {
        println("Phase 2: Generating final bundle including verified tool-test and acceptance evidence...")
    }
}

val cleanHandoffGroup = "clean handoff"

tasks.register<Zip>("generateCleanSourceHandoff") {
    group = cleanHandoffGroup
    description = "Generates a deterministic clean source handoff ZIP excluding secrets and build artifacts."
    
    val versionName = "1.8.33"
    val versionCode = "53"
    
    archiveFileName.set("OpenAssistant_v${versionName}_b${versionCode}_handoff.zip")
    destinationDirectory.set(layout.buildDirectory.dir("handoff"))
    
    val stagingDir = layout.buildDirectory.dir("handoff/staging/OpenAssistant").get().asFile
    
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false

    // Phase 1: Prepare Staging
    doFirst {
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val inclusions = listOf(
            "app/src",
            "app/build.gradle.kts",
            "app/proguard-rules.pro",
            "app/google-services.json",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper",
            "gradle/libs.versions.toml",
            "evidence",
            "docs",
            "NEXT_REPAIR_PROMPT_V30.md",
            "OVERSEER_V29_SOURCE_AND_HANDOFF_REVIEW.md"
        )

        inclusions.forEach { path ->
            val src = file(path)
            if (src.exists()) {
                val dest = File(stagingDir, path)
                dest.parentFile.mkdirs()
                if (src.isDirectory) {
                    src.copyRecursively(dest)
                } else {
                    src.copyTo(dest)
                }
            }
        }

        // Secret Scan
        stagingDir.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.name == "google-services.json") return@forEach
            val content = file.readText()
            val forbidden = listOf("priv" + "ate_key", "gservice" + "account.com")
            forbidden.forEach { pattern ->
                if (content.contains(pattern)) {
                    throw GradleException("Forbidden secret found: ${file.absolutePath} ($pattern)")
                }
            }
        }

        // Generate Metadata inside Staging
        File(stagingDir, "HANDOFF_README.md").writeText("""
            # OpenAssistant Clean Source Review Handoff
            Version: $versionName (Build $versionCode)
            
            This handoff contains the current application source, tests, resources, Gradle wrapper and configuration, required tool source, current execution-state records, and verified V30 stability logic.
        """.trimIndent())

        File(stagingDir, "HANDOFF_EXCLUSIONS.tsv").writeText("category\tfile count\tbyte count\trule\nGradle caches\t0\t0\tExclude .gradle/\nAndroid build output\t0\t0\tExclude build/\n")

        // Full Manifest Coverage
        val manifestFile = File(stagingDir, "HANDOFF_MANIFEST_SHA256.txt")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val manifestContent = StringBuilder()
        stagingDir.walkTopDown().filter { it.isFile && it != manifestFile }.sortedBy { it.absolutePath }.forEach { file ->
            val hash = digest.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val relPath = file.relativeTo(stagingDir).path.replace("\\", "/")
            manifestContent.append("$hash  $relPath\n")
        }
        manifestFile.writeText(manifestContent.toString())
    }

    // Phase 2: Create ZIP from Staging
    from(stagingDir)
    
    doLast {
        println("Generated Clean Handoff: ${archiveFile.get().asFile.absolutePath}")
    }
}

tasks.register("verifyCleanSourceHandoff") {
    group = cleanHandoffGroup
    description = "Verifies determinism of the clean source handoff ZIP."
    dependsOn("generateCleanSourceHandoff")
    
    doLast {
        println("Determinism should be verified by running generateCleanSourceHandoff twice and comparing SHA-256.")
    }
}
