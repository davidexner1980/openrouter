package com.david.openassistant.agent

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentStoreStabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var baseDir: File
    private lateinit var prefs: FakeSharedPreferences

    class FakeSharedPreferences : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        
        override fun getAll(): Map<String, *> = values
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            listener?.let { listeners.add(it) }
        }
        
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            listener?.let { listeners.remove(it) }
        }

        fun notifyListeners(key: String) {
            listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
        }

        class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val tempValues = mutableMapOf<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { tempValues[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { tempValues[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { tempValues[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { tempValues[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { tempValues[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { tempValues[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { tempValues[key] = this } // marker for removal
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
            
            override fun commit(): Boolean {
                val changedKeys = mutableSetOf<String>()
                
                if (clearRequested) {
                    prefs.values.keys.forEach { changedKeys.add(it) }
                    prefs.values.clear()
                    clearRequested = false
                }
                
                tempValues.forEach { (key, value) ->
                    if (value === this) {
                        if (prefs.values.containsKey(key)) {
                            prefs.values.remove(key)
                            changedKeys.add(key)
                        }
                    } else {
                        if (prefs.values[key] != value) {
                            prefs.values[key] = value
                            changedKeys.add(key)
                        }
                    }
                }
                
                tempValues.clear()
                changedKeys.forEach { prefs.notifyListeners(it) }
                return true
            }
            override fun apply() { commit() }
        }
    }

    @Before
    fun setup() {
        baseDir = tempFolder.newFolder("agent_store")
        prefs = FakeSharedPreferences()
        // Use the private constructor via reflection or the public one that takes Context (which we don't have)
        // Actually, let's use the private constructor via reflection
        val constructor = AgentStore::class.java.getDeclaredConstructor(
            android.content.Context::class.java,
            java.io.File::class.java,
            android.content.SharedPreferences::class.java
        )
        constructor.isAccessible = true
        prefs = FakeSharedPreferences()
        // Protocol: Prevent migration churn during stability tests
        prefs.edit().putBoolean("agent_store_migrated_v2", true).commit()
        store = constructor.newInstance(null, baseDir, prefs) as AgentStore
    }

    @Test
    fun `revision increments on upsert`() {
        val initialRevision = store.getLatestRevision()
        assertEquals(0L, initialRevision)
        
        val goal = testGoal("goal-1")
        store.upsertGoal(goal)
        
        val nextRevision = store.getLatestRevision()
        assertTrue("Revision should increment on upsert. Initial: $initialRevision, Current: $nextRevision", nextRevision > initialRevision)
    }

    @Test
    fun `revision increments on update`() {
        val goal = testGoal("goal-1")
        store.upsertGoal(goal)
        val afterUpsertRevision = store.getLatestRevision()
        
        store.updateGoal("goal-1") { it.copy(objective = "updated") }
        
        val afterUpdateRevision = store.getLatestRevision()
        assertTrue("Revision should increment on update. Previous: $afterUpsertRevision, Current: $afterUpdateRevision", afterUpdateRevision > afterUpsertRevision)
    }

    @Test
    fun `read count increments on loadSnapshot`() {
        val initialReadCount = AgentStore.readCount.get()
        store.loadSnapshot()
        assertEquals(initialReadCount + 1, AgentStore.readCount.get())
    }

    @Test
    fun `write count increments on upsert`() {
        val initialWriteCount = AgentStore.writeCount.get()
        store.upsertGoal(testGoal("goal-1"))
        assertEquals(initialWriteCount + 1, AgentStore.writeCount.get())
    }

    @Test
    fun `listener is notified on revision change`() {
        val notifiedKeys = mutableSetOf<String>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) notifiedKeys.add(key)
        }
        store.registerListener(listener)
        
        store.upsertGoal(testGoal("goal-notify"))
        
        assertTrue("Should notify revision. Notified: $notifiedKeys", notifiedKeys.contains(AgentStore.KEY_REVISION))
        
        store.unregisterListener(listener)
        notifiedKeys.clear()
        store.upsertGoal(testGoal("goal-no-notify"))
        assertTrue("Should not notify after unregister", notifiedKeys.isEmpty())
    }

    @Test
    fun `fake shared preferences notifies only on change`() {
        val notifiedKeys = mutableSetOf<String>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) notifiedKeys.add(key)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        // 1. Initial put
        prefs.edit().putString("key1", "val1").commit()
        assertTrue(notifiedKeys.contains("key1"))
        notifiedKeys.clear()

        // 2. Put same value
        prefs.edit().putString("key1", "val1").commit()
        assertTrue("Should not notify for unchanged value", notifiedKeys.isEmpty())

        // 3. Remove existing
        prefs.edit().remove("key1").commit()
        assertTrue("Should notify on remove", notifiedKeys.contains("key1"))
        notifiedKeys.clear()

        // 4. Remove absent
        prefs.edit().remove("key1").commit()
        assertTrue("Should not notify on remove of absent key", notifiedKeys.isEmpty())

        // 5. Clear
        prefs.edit().putString("key2", "val2").commit()
        notifiedKeys.clear()
        prefs.edit().clear().commit()
        assertTrue("Clear should notify removed keys", notifiedKeys.contains("key2"))
    }

    private fun testGoal(id: String) = AgentGoal(
        id = id,
        conversationId = "conv-1",
        userRequest = "test request",
        title = "Test Goal",
        objective = "test objective",
        finalOutputDescription = "test deliverable",
        status = AgentGoalStatus.QUEUED,
        plannerModelId = "model-1",
        executionModelId = "model-1",
        tasks = emptyList()
    )
}
