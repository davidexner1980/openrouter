package com.david.openassistant.agent

import android.content.Context
import android.util.AtomicFile
import com.david.openassistant.data.openrouter.requireOpenRouterObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

data class NonMissionProviderRecord(
    val exchangeId: String,
    val contextType: String, // "CONVERSATION" or "INFRASTRUCTURE"
    val contextId: String,
    val operation: String,
    val requestedModel: String,
    val outcome: ExchangeOutcome,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val costUsdMicros: Long? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val failureClass: String? = null,
)

/**
 * Bounded atomic store for non-mission provider requests (conversations and infrastructure calls).
 * Ensures usage/cost accounting and terminal outcomes are preserved without mutating mission state.
 */
class ProviderActivityStore(context: Context) {
    private val appContext = context.applicationContext
    private val storeFile = File(appContext.filesDir, STORE_FILE_NAME)
    private val lock = Any()

    fun recordActivity(record: NonMissionProviderRecord) = synchronized(lock) {
        val currentRecords = loadRecordsLocked()
        val updated = (currentRecords + record).takeLast(MAX_RETAINED_RECORDS)
        saveRecordsLocked(updated)
    }

    fun loadRecords(): List<NonMissionProviderRecord> = synchronized(lock) {
        loadRecordsLocked()
    }

    private fun loadRecordsLocked(): List<NonMissionProviderRecord> {
        if (!storeFile.exists()) return emptyList()
        val atomicFile = AtomicFile(storeFile)
        return runCatching {
            val raw = atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val root = requireOpenRouterObject(raw, "Provider activity store")
            val array = root.optJSONArray("records") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(decodeRecord(obj))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveRecordsLocked(records: List<NonMissionProviderRecord>) {
        storeFile.parentFile?.mkdirs()
        val atomicFile = AtomicFile(storeFile)
        var stream: FileOutputStream? = null
        try {
            val json = JSONObject().apply {
                put("version", 1)
                put("records", JSONArray().apply { records.forEach { put(encodeRecord(it)) } })
            }
            stream = atomicFile.startWrite()
            stream.write(json.toString().toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: Throwable) {
            stream?.let(atomicFile::failWrite)
        }
    }

    private fun encodeRecord(rec: NonMissionProviderRecord): JSONObject = JSONObject()
        .put("exchange_id", rec.exchangeId)
        .put("context_type", rec.contextType)
        .put("context_id", rec.contextId)
        .put("operation", rec.operation)
        .put("requested_model", rec.requestedModel)
        .put("outcome", rec.outcome.name)
        .put("prompt_tokens", rec.promptTokens ?: JSONObject.NULL)
        .put("completion_tokens", rec.completionTokens ?: JSONObject.NULL)
        .put("total_tokens", rec.totalTokens ?: JSONObject.NULL)
        .put("cost_usd_micros", rec.costUsdMicros ?: JSONObject.NULL)
        .put("started_at", rec.startedAt)
        .put("finished_at", rec.finishedAt ?: JSONObject.NULL)
        .put("failure_class", rec.failureClass ?: JSONObject.NULL)

    private fun decodeRecord(json: JSONObject): NonMissionProviderRecord = NonMissionProviderRecord(
        exchangeId = json.getString("exchange_id"),
        contextType = json.optString("context_type", "CONVERSATION"),
        contextId = json.optString("context_id", "default"),
        operation = json.optString("operation", "chat"),
        requestedModel = json.optString("requested_model"),
        outcome = runCatching { ExchangeOutcome.valueOf(json.optString("outcome")) }.getOrDefault(ExchangeOutcome.INTERRUPTED_OUTCOME_UNKNOWN),
        promptTokens = if (json.has("prompt_tokens") && !json.isNull("prompt_tokens")) json.optInt("prompt_tokens") else null,
        completionTokens = if (json.has("completion_tokens") && !json.isNull("completion_tokens")) json.optInt("completion_tokens") else null,
        totalTokens = if (json.has("total_tokens") && !json.isNull("total_tokens")) json.optInt("total_tokens") else null,
        costUsdMicros = if (json.has("cost_usd_micros") && !json.isNull("cost_usd_micros")) json.optLong("cost_usd_micros") else null,
        startedAt = json.optLong("started_at"),
        finishedAt = if (json.has("finished_at") && !json.isNull("finished_at")) json.optLong("finished_at") else null,
        failureClass = if (json.has("failure_class") && !json.isNull("failure_class")) json.optString("failure_class") else null,
    )

    companion object {
        private const val STORE_FILE_NAME = "provider_activity_store.json"
        private const val MAX_RETAINED_RECORDS = 200
    }
}
