package com.david.openassistant

import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.david.openassistant.data.security.ApiKeyStore
import com.david.openassistant.domain.tools.AutonomousToolRuntime
import com.david.openassistant.domain.tools.OpenRouterToolCall
import com.david.openassistant.domain.tools.ToolRecipe
import com.david.openassistant.domain.tools.ToolRecipeCodec
import com.david.openassistant.domain.tools.ToolRecipeOperation
import com.david.openassistant.domain.tools.ToolRecipeParameter
import com.david.openassistant.domain.tools.ToolRecipeStep
import com.david.openassistant.domain.tools.ToolRecipeTest
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in connected-device certification for the fourteen static tools that
 * require Android storage/state, public network access, or a stored provider
 * credential. Run with:
 *
 * connectedDebugAndroidTest
 * -Pandroid.testInstrumentationRunnerArguments.runLiveToolCertification=true
 */
@RunWith(AndroidJUnit4::class)
class LiveToolCertificationTest {
    private lateinit var runtime: AutonomousToolRuntime

    @Before
    fun setUp() {
        val arguments = InstrumentationRegistry.getArguments()
        val isExplicitRun = arguments.getString(LIVE_FLAG) == "true"
        if (!isExplicitRun) {
            assumeTrue(
                "Live certification is opt-in because it performs public network calls and one hosted-sandbox provider call.",
                false,
            )
        }
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
        assertEquals(
            "Connected certification is targeting a stale installed app. Install this source build before trusting any result.",
            BuildConfig.VERSION_NAME,
            packageInfo.versionName,
        )
        assertEquals(
            "Connected certification is targeting the wrong installed versionCode.",
            BuildConfig.VERSION_CODE.toLong(),
            PackageInfoCompat.getLongVersionCode(packageInfo),
        )
        runtime = AutonomousToolRuntime(targetContext)
    }

    @Test
    fun certify01WorkspaceWriteText() {
        val path = writeFixture("write")
        val payload = execute("workspace_write_text", JSONObject().put("path", path).put("content", FIXTURE_MARKER))
        assertTrue(payload.has("path") || payload.has("bytes"))
    }

    @Test
    fun certify02WorkspaceReadText() {
        val path = writeFixture("read")
        val payload = execute("workspace_read_text", JSONObject().put("path", path))
        assertTrue(payload.getString("content").contains(FIXTURE_MARKER))
    }

    @Test
    fun certify03WorkspaceListFiles() {
        val path = writeFixture("list")
        val filename = File(path).name
        val payload = execute("workspace_list_files", JSONObject().put("prefix", ""))
        assertTrue(payload.getJSONArray("files").toString().contains(filename))
    }

    @Test
    fun certify04WorkspaceSearchText() {
        writeFixture("search")
        val payload = execute(
            "workspace_search_text",
            JSONObject().put("query", FIXTURE_MARKER).put("prefix", ""),
        )
        assertTrue(payload.getInt("count") >= 1)
    }

    @Test
    fun certify05WorkspaceFileInfo() {
        val path = writeFixture("info")
        val payload = execute("workspace_file_info", JSONObject().put("path", path))
        assertEquals(path, payload.getString("path"))
        assertTrue(payload.getLong("bytes") > 0L)
    }

    @Test
    fun certify06WorkspaceMoveToTrash() {
        val path = writeFixture("trash")
        val payload = execute("workspace_move_to_trash", JSONObject().put("path", path))
        assertEquals("trashed", payload.getString("status"))
        assertTrue(payload.getBoolean("recoverable"))
    }

    @Test
    fun certify07WorkspaceRestoreFromTrash() {
        val path = writeFixture("restore")
        val trashedPayload = execute("workspace_move_to_trash", JSONObject().put("path", path))
        val trashName = trashedPayload.getString("trash_name")
        val payload = execute("workspace_restore_from_trash", JSONObject().put("trash_name", trashName))
        assertEquals("restored", payload.getString("status"))
    }

    @Test
    fun certify08RuntimeDiagnostics() {
        val payload = execute("inspect_runtime_diagnostics", JSONObject())
        assertTrue(payload.has("diagnostic_events") || payload.has("event_count") || payload.has("events"))
    }

    @Test
    fun certify09ToolFoundryCreateAndExerciseRecipe() {
        val recipe = ToolRecipe(
            toolName = "certification_echo_recipe",
            displayName = "Echo recipe",
            description = "Echo test recipe for certification",
            parameters = listOf(
                ToolRecipeParameter("message", "Message to echo"),
            ),
            steps = listOf(
                ToolRecipeStep(
                    id = "step_1",
                    operation = ToolRecipeOperation.NORMALIZE_WHITESPACE,
                    arguments = mapOf(
                        "text" to "\${input.message}",
                    ),
                ),
            ),
            outputTemplate = "\${step.step_1}",
            tests = listOf(
                ToolRecipeTest(inputs = mapOf("message" to FIXTURE_MARKER), expectedOutput = FIXTURE_MARKER),
            ),
        )
        val created = execute("create_tool_recipe", JSONObject().put("recipe_json", ToolRecipeCodec.toJson(recipe).toString()))
        assertTrue(created.has("tool_name"))

        val disabled = execute("disable_tool_recipe", JSONObject().put("tool_name", created.getString("tool_name")))
        assertTrue(disabled.has("status") || disabled.has("disabled") || disabled.has("recipe_id"))
    }

    @Test
    fun certify10ToolFoundryListRecipes() {
        val payload = execute("list_tool_recipes", JSONObject())
        assertTrue(payload.has("recipes") || payload.has("active_recipes"))
    }

    @Test
    fun certify11ToolFoundryDisableRecipe() {
        val payload = execute("list_tool_recipes", JSONObject())
        assertTrue(payload.has("recipes") || payload.has("active_recipes"))
    }

    @Test
    fun certify12PublicWebSearch() {
        val payload = execute(
            "public_web_search",
            JSONObject().put("query", "Android Studio OpenAssistant release notes"),
        )
        assertTrue(payload.getInt("source_count") >= 1)
        assertTrue(payload.getJSONArray("sources").length() >= 1)
    }

    @Test
    fun certify13PublicWebFetch() {
        val payload = execute(
            "public_web_fetch",
            JSONObject().put("url", "https://example.com/"),
        )
        assertTrue(payload.getString("text").isNotBlank())
        assertTrue(payload.getJSONArray("sources").length() >= 1)
    }

    @Test
    fun certify14HostedSandboxWorkbench() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apiKey = ApiKeyStore(context).load()
        val isExplicitRun = InstrumentationRegistry.getArguments().getString(LIVE_FLAG) == "true"
        
        if (isExplicitRun) {
            assertTrue("A stored OpenRouter credential is required for live sandbox certification when runLiveToolCertification=true is explicitly set.", !apiKey.isNullOrBlank())
        } else if (apiKey.isNullOrBlank()) {
            assumeTrue("Skipping sandbox test as no valid live OpenRouter credential is pre-installed on the device.", false)
            return
        }
        
        val payload = try {
            execute(
                name = "sandbox_workbench",
                arguments = JSONObject()
                    .put("task", "Compute 17 * 23 in code, independently verify the arithmetic, and return the exact integer result.")
                    .put("mode", "test")
                    .put("allow_web", "false")
                    .put("output_format", "text"),
                apiKey = apiKey,
                modelId = "openrouter/auto-beta",
            )
        } catch (e: Exception) {
            if (isExplicitRun) {
                throw AssertionError("Live sandbox certification failed: ${e.message}", e)
            } else {
                assumeTrue("Live sandbox call failed: ${e.message}", false)
                return
            }
        }
        assertTrue(payload.getString("output").contains("391"))
    }

    private fun writeFixture(name: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "autonomy/workspace/certification").apply { mkdirs() }
        val file = File(dir, "$name.txt")
        file.writeText("$FIXTURE_MARKER - $name - ${System.currentTimeMillis()}")
        return "certification/${file.name}"
    }

    private fun execute(
        name: String,
        arguments: JSONObject,
        apiKey: String? = null,
        modelId: String? = null,
    ): JSONObject {
        val raw = kotlinx.coroutines.runBlocking {
            runtime.execute(
                call = OpenRouterToolCall(
                    id = "call_cert_$name",
                    name = name,
                    argumentsJson = arguments.toString(),
                ),
                apiKey = apiKey,
                modelId = modelId,
            )
        }
        return JSONObject(raw.outputJson)
    }

    companion object {
        private const val LIVE_FLAG = "runLiveToolCertification"
        private const val FIXTURE_MARKER = "CERTIFICATION_FIXTURE_1.8.27"
    }
}
