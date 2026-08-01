import java.util.UUID
import java.time.Instant
import java.security.MessageDigest

plugins {
    kotlin("jvm")
    id("application")
}

application {
    mainClass.set("com.david.openassistant.handoff.HandoffMain")
}

dependencies {
    implementation(libs.json)
    testImplementation(kotlin("test"))
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.david.openassistant.handoff.HandoffMain"
    }
}

tasks.withType<JavaExec> {
    if (project.hasProperty("args")) {
        args = project.property("args").toString().split(" ")
    }
}

// Overseer Tool Test Evidence Capture
val prepareOverseerToolTestEvidence by tasks.registering {
    doLast {
        val executionId = UUID.randomUUID().toString()
        val startTime = Instant.now().toString()
        val buildDir = layout.buildDirectory.get().asFile
        val evidenceDir = File(buildDir, "tool-execution")
        evidenceDir.mkdirs()

        // OH-V12: algorithm_id = path-length-bytes-v1
        fun hashFileDeterministic(file: File, relativePath: String, digest: MessageDigest) {
            if (!file.exists()) return
            digest.update(relativePath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            val bytes = file.readBytes()
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(bytes)
            digest.update(0)
        }

        fun hashDirDeterministic(dir: File, baseDir: File): String {
            if (!dir.exists()) return "missing"
            val d = MessageDigest.getInstance("SHA-256")
            val allowedExt = setOf("kt", "java", "kts", "gradle", "xml", "pro", "properties", "md", "json", "jsonl", "keep", "ps1", "sh", "cmd", "txt")
            dir.walkTopDown().filter { it.isFile && allowedExt.contains(it.extension.lowercase()) }.sortedBy { it.relativeTo(baseDir).path.replace("\\", "/") }.forEach { file ->
                val relPath = file.relativeTo(baseDir).path.replace("\\", "/")
                hashFileDeterministic(file, relPath, d)
            }
            return d.digest().joinToString("") { b: Byte -> String.format("%02x", b) }
        }

        val projectRoot = projectDir.parentFile.parentFile
        val mainHash = hashDirDeterministic(File(projectDir, "src/main"), projectDir)
        val testHash = hashDirDeterministic(File(projectDir, "src/test"), projectDir)
        
        val wiringDigest = MessageDigest.getInstance("SHA-256")
        hashFileDeterministic(File(projectDir, "build.gradle.kts"), "tools/overseer-handoff/build.gradle.kts", wiringDigest)
        hashFileDeterministic(File(projectRoot, "build.gradle.kts"), "build.gradle.kts", wiringDigest)
        hashFileDeterministic(File(projectRoot, "settings.gradle.kts"), "settings.gradle.kts", wiringDigest)
        val rootWiringHash = wiringDigest.digest().joinToString("") { b: Byte -> String.format("%02x", b) }

        val record = """
        {
          "execution_id": "$executionId",
          "started_at_utc": "$startTime",
          "algorithm_id": "path-length-bytes-v1",
          "tool_main_source_sha256": "$mainHash",
          "tool_test_source_sha256": "$testHash",
          "root_task_wiring_sha256": "$rootWiringHash"
        }
        """.trimIndent()
        File(evidenceDir, "execution-start.json").writeText(record)
    }
}

val finalizeOverseerToolTestEvidence by tasks.registering {
    mustRunAfter(tasks.test)
    doLast {
        val buildDir = layout.buildDirectory.get().asFile
        val evidenceDir = File(buildDir, "tool-execution")
        val startFile = File(evidenceDir, "execution-start.json")
        if (!startFile.exists()) return@doLast

        val finishTime = Instant.now().toString()
        
        val testTask = tasks.test.get()
        val outcome = when {
            testTask.state.failure != null -> "FAILED"
            testTask.state.skipped -> "SKIPPED"
            testTask.state.upToDate -> "UP_TO_DATE"
            testTask.state.executed -> "SUCCESS"
            else -> "UNKNOWN"
        }

        fun hashDirDeterministic(dir: File, baseDir: File): String {
            if (!dir.exists()) return "missing"
            val d = MessageDigest.getInstance("SHA-256")
            val allowedExt = setOf("kt", "java", "kts", "gradle", "xml", "pro", "properties", "md", "json", "jsonl", "keep", "ps1", "sh", "cmd", "txt")
            dir.walkTopDown().filter { it.isFile && allowedExt.contains(it.extension.lowercase()) }.sortedBy { it.relativeTo(baseDir).path.replace("\\", "/") }.forEach { file ->
                val relPath = file.relativeTo(baseDir).path.replace("\\", "/")
                digestUpdateFile(d, file, relPath)
            }
            return d.digest().joinToString("") { b: Byte -> String.format("%02x", b) }
        }

        val mainHashAfter = hashDirDeterministic(File(projectDir, "src/main"), projectDir)
        val testHashAfter = hashDirDeterministic(File(projectDir, "src/test"), projectDir)

        // Parse JUnit reports
        val reportDir = File(buildDir, "test-results/test")
        var totalTests = 0
        var totalFailed = 0
        var totalErrors = 0
        var totalSkipped = 0
        val reportHashes = mutableListOf<String>()

        if (reportDir.exists()) {
            reportDir.listFiles { f -> f.extension == "xml" }?.forEach { file ->
                val content = file.readBytes()
                val d = MessageDigest.getInstance("SHA-256")
                val hash = d.digest(content).joinToString("") { b: Byte -> String.format("%02x", b) }
                reportHashes.add("""{"file": "${file.name}", "sha256": "$hash"}""")
                
                val xml = file.readText()
                totalTests += xml.substringAfter("tests=\"", "").substringBefore("\"", "0").toIntOrNull() ?: 0
                totalFailed += xml.substringAfter("failures=\"", "").substringBefore("\"", "0").toIntOrNull() ?: 0
                totalErrors += xml.substringAfter("errors=\"", "").substringBefore("\"", "0").toIntOrNull() ?: 0
                totalSkipped += xml.substringAfter("skipped=\"", "").substringBefore("\"", "0").toIntOrNull() ?: 0
            }
        }

        val startContent = startFile.readText().trim().removePrefix("{").removeSuffix("}")
        val finalRecord = """
        {
          $startContent,
          "finished_at_utc": "$finishTime",
          "task_path": ":tools:overseer-handoff:test",
          "task_outcome": "$outcome",
          "main_hash_after": "$mainHashAfter",
          "test_hash_after": "$testHashAfter",
          "junit_reports": [${reportHashes.joinToString(", ")}],
          "junit_totals": {
            "tests": $totalTests,
            "failed": $totalFailed,
            "errors": $totalErrors,
            "skipped": $totalSkipped
          },
          "invocation_metadata": {
            "gradle_version": "${project.gradle.gradleVersion}",
            "java_runtime_version": "${System.getProperty("java.version")}",
            "invoked_from_ide": ${project.hasProperty("android.injected.invoked.from.ide")},
            "offline": ${project.gradle.startParameter.isOffline},
            "configuration_cache_requested": ${project.gradle.startParameter.isConfigurationCacheRequested}
          }
        }
        """.trimIndent()
        File(evidenceDir, "execution.json").writeText(finalRecord)
    }
}

fun digestUpdateFile(digest: MessageDigest, file: File, relativePath: String) {
    digest.update(relativePath.toByteArray(Charsets.UTF_8))
    digest.update(0)
    val bytes = file.readBytes()
    digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(bytes)
    digest.update(0)
}

tasks.test {
    dependsOn(prepareOverseerToolTestEvidence)
    finalizedBy(finalizeOverseerToolTestEvidence)
}

val verifyOverseerHandoffAcceptance by tasks.registering {
    dependsOn(tasks.test)
    doLast {
        val projectRoot = projectDir.parentFile.parentFile
        val mainClass = "com.david.openassistant.handoff.HandoffMain"
        val classpath = sourceSets.main.get().runtimeClasspath.asPath
        
        val logDir = layout.buildDirectory.get().asFile.resolve("tool-acceptance")
        logDir.mkdirs()
        
        println("Running Overseer Handoff Acceptance Suite via HandoffMain...")
        
        val process = ProcessBuilder("java", "-cp", classpath, mainClass, "acceptance-test", projectRoot.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.to(File(logDir, "stdout.log")))
            .redirectError(ProcessBuilder.Redirect.to(File(logDir, "stderr.log")))
            .start()
        
        val exitCode = process.waitFor()
        
        val summary = """
        {
          "status": "${if (exitCode == 0) "PASSED" else "FAILED"}",
          "exit_code": $exitCode,
          "timestamp": "${Instant.now()}"
        }
        """.trimIndent()
        File(logDir, "summary.json").writeText(summary)
        
        if (exitCode != 0) {
            throw GradleException("Overseer Handoff Acceptance Suite failed with exit code $exitCode. See $logDir/stderr.log")
        }
        println("Acceptance Suite Passed.")
    }
}
