package com.david.openassistant.handoff

import java.io.File
import java.util.Properties

class ProjectIdentityResolver(val projectRoot: File) {

    data class Identity(
        val applicationId: String?,
        val versionName: String?,
        val versionCode: Int?,
        val gradleWrapper: String?,
        val agpVersion: String?,
        val rootKotlinVersion: String?,
        val catalogKotlinVersion: String?,
        val toolJavaVersion: String?,
        val appJavaVersion: String?,
        val compileSdk: Int?,
        val targetSdk: Int?,
        val minSdk: Int?,
        val composeBom: String?,
        val isGit: Boolean
    )

    fun resolve(): Identity {
        var appVersion: String? = null
        var appCode: Int? = null
        var appId: String? = null
        var gradleW: String? = null
        var agp: String? = null
        var rootKotlinV: String? = null
        var catalogKotlinV: String? = null
        var toolJavaV: String? = System.getProperty("java.version")
        var appJavaV: String? = null
        var compileS: Int? = null
        var targetS: Int? = null
        var minS: Int? = null
        var composeB: String? = null

        // OH-012: Root Kotlin Plugin version
        val rootBuildGradle = File(projectRoot, "build.gradle.kts")
        if (rootBuildGradle.exists()) {
             val content = rootBuildGradle.readText()
             Regex("id\\(\"org.jetbrains.kotlin.plugin.compose\"\\) version \"([^\"]+)\"").find(content)?.let { rootKotlinV = it.groupValues[1] }
             if (rootKotlinV == null) {
                 Regex("id\\(\"org.jetbrains.kotlin.jvm\"\\) version \"([^\"]+)\"").find(content)?.let { rootKotlinV = it.groupValues[1] }
             }
        }

        // Try reading from libs.versions.toml
        val libsFile = File(projectRoot, "gradle/libs.versions.toml")
        if (libsFile.exists()) {
            val content = libsFile.readText()
            Regex("agp = \"([^\"]+)\"").find(content)?.let { agp = it.groupValues[1] }
            Regex("kotlin = \"([^\"]+)\"").find(content)?.let { catalogKotlinV = it.groupValues[1] }
            Regex("compose-bom = \"([^\"]+)\"").find(content)?.let { composeB = it.groupValues[1] }
        }

        // Try reading from app/build.gradle.kts
        val buildGradle = File(projectRoot, "app/build.gradle.kts")
        if (buildGradle.exists()) {
            val content = buildGradle.readText()
            Regex("versionName = \"([^\"]+)\"").find(content)?.let { appVersion = it.groupValues[1] }
            Regex("versionCode = (\\d+)").find(content)?.let { appCode = it.groupValues[1].toInt() }
            Regex("applicationId = \"([^\"]+)\"").find(content)?.let { appId = it.groupValues[1] }
            Regex("compileSdk = (\\d+)").find(content)?.let { compileS = it.groupValues[1].toInt() }
            Regex("targetSdk = (\\d+)").find(content)?.let { targetS = it.groupValues[1].toInt() }
            Regex("minSdk = (\\d+)").find(content)?.let { minS = it.groupValues[1].toInt() }
            
            // OH-012: App Java target
            Regex("sourceCompatibility = JavaVersion.VERSION_(\\d+)").find(content)?.let { appJavaV = it.groupValues[1] }
            if (appJavaV == null) {
                 Regex("targetCompatibility = JavaVersion.VERSION_(\\d+)").find(content)?.let { appJavaV = it.groupValues[1] }
            }
        }

        // Gradle wrapper
        val wrapperProps = File(projectRoot, "gradle/wrapper/gradle-wrapper.properties")
        if (wrapperProps.exists()) {
            val content = wrapperProps.readText()
            Regex("gradle-([\\d\\.]+)-bin").find(content)?.let { gradleW = it.groupValues[1] }
        }

        val isGit = File(projectRoot, ".git").exists() || File(projectRoot, ".git").isFile

        return Identity(appId, appVersion, appCode, gradleW, agp, rootKotlinV, catalogKotlinV, toolJavaV, appJavaV, compileS, targetS, minS, composeB, isGit)
    }
}
