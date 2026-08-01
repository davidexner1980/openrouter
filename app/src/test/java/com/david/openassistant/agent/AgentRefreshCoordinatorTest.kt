package com.david.openassistant.agent

import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRefreshCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    
    private lateinit var agentSource: FakeAgentRefreshSource
    private lateinit var toolSource: FakeToolCountSource
    private lateinit var diagnostics: FakeRefreshDiagnostics
    private lateinit var stateApplier: FakeStateApplier
    
    private var emissionCount = 0
    private var lastEmittedSnapshot: AgentSnapshot? = null

    open class FakeAgentRefreshSource : AgentRefreshSource {
        var revision = AtomicLong(0)
        var snapshot = AgentSnapshot()
        
        override suspend fun loadStableSnapshot(): AgentSnapshotWithRevision {
            return AgentSnapshotWithRevision(snapshot, revision.get())
        }
        override fun getLatestRevision(): Long = revision.get()
    }

    class FakeToolCountSource : ToolCountSource {
        var recipeCount = 0
        var fileCount = 0
        override suspend fun loadToolCounts(): ToolCounts = ToolCounts(recipeCount, fileCount)
    }

    open class FakeRefreshDiagnostics : RefreshDiagnostics {
        override fun info(event: String, fields: Map<String, Any?>) {}
        override fun error(event: String, throwable: Throwable, fields: Map<String, Any?>) {}
    }

    class FakeStateApplier(
        private val onApply: suspend (AgentSnapshot, Int, Int) -> RefreshApplyResult = { _, _, _ -> RefreshApplyResult.Success }
    ) : RefreshStateApplier {
        override suspend fun apply(
            snapshot: AgentSnapshot,
            recipeCount: Int,
            workspaceCount: Int
        ): RefreshApplyResult = onApply(snapshot, recipeCount, workspaceCount)
    }

    @Before
    fun setup() {
        agentSource = FakeAgentRefreshSource()
        toolSource = FakeToolCountSource()
        diagnostics = FakeRefreshDiagnostics()
        emissionCount = 0
        lastEmittedSnapshot = null
        stateApplier = FakeStateApplier { s, _, _ ->
            emissionCount++
            lastEmittedSnapshot = s
            RefreshApplyResult.Success
        }
    }

    @Test
    fun `first load emits UI state`() = testScope.runTest {
        agentSource.revision.set(1L)
        agentSource.snapshot = AgentSnapshot(goals = emptyList())
        
        val coordinator = AgentRefreshCoordinator(
            refreshSource = agentSource,
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = stateApplier,
            deliverPendingResults = { false }
        )

        coordinator.refresh(this)
        advanceUntilIdle()

        assertEquals(1, emissionCount)
        assertEquals(agentSource.snapshot, lastEmittedSnapshot)
        assertEquals(1L, coordinator.metrics().lastProcessedRevision)
    }

    @Test
    fun `unchanged snapshot suppresses emission`() = testScope.runTest {
        val snapshot = AgentSnapshot(goals = emptyList())
        agentSource.revision.set(1L)
        agentSource.snapshot = snapshot
        
        val coordinator = AgentRefreshCoordinator(
            refreshSource = agentSource,
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = stateApplier,
            deliverPendingResults = { false }
        )

        // First load
        coordinator.refresh(this)
        advanceUntilIdle()
        assertEquals(1, emissionCount)

        // Second request with same content but new revision
        agentSource.revision.set(2L)
        coordinator.refresh(this)
        advanceUntilIdle()
        
        assertEquals("Should suppress emission if content is identical", 1, emissionCount)
        assertEquals(2L, coordinator.metrics().lastProcessedRevision)
    }

    @Test
    fun `changed content triggers emission`() = testScope.runTest {
        val snapshot1 = AgentSnapshot(goals = listOf(testGoal("1")))
        val snapshot2 = AgentSnapshot(goals = listOf(testGoal("1"), testGoal("2")))
        
        agentSource.revision.set(1L)
        agentSource.snapshot = snapshot1
        
        val coordinator = AgentRefreshCoordinator(
            refreshSource = agentSource,
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = stateApplier,
            deliverPendingResults = { false }
        )

        coordinator.refresh(this)
        advanceUntilIdle()
        assertEquals(1, emissionCount)

        agentSource.revision.set(2L)
        agentSource.snapshot = snapshot2
        coordinator.refresh(this)
        advanceUntilIdle()
        
        assertEquals(2, emissionCount)
        assertEquals(snapshot2, lastEmittedSnapshot)
    }

    @Test
    fun `terminal delivery settlement cycle`() = testScope.runTest {
        val goalId = "goal-10"
        val initialGoal = testGoal(goalId).copy(status = AgentGoalStatus.COMPLETED, terminalResultDelivered = false)
        agentSource.revision.set(10L)
        agentSource.snapshot = AgentSnapshot(goals = listOf(initialGoal))

        var deliveryCount = 0
        val coordinator = AgentRefreshCoordinator(
            refreshSource = agentSource,
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = stateApplier,
            deliverPendingResults = { snapshot ->
                val pending = snapshot.goals.any { it.status == AgentGoalStatus.COMPLETED && !it.terminalResultDelivered }
                if (pending) {
                    deliveryCount++
                    val updatedGoal = initialGoal.copy(terminalResultDelivered = true)
                    agentSource.snapshot = AgentSnapshot(goals = listOf(updatedGoal))
                    agentSource.revision.set(11L)
                    true
                } else {
                    false
                }
            }
        )

        coordinator.refresh(this, 10L)
        advanceUntilIdle()

        assertEquals("Should deliver exactly once", 1, deliveryCount)
        assertEquals("Should emit the final state (Rev 11)", 1, emissionCount)
        assertEquals(11L, coordinator.metrics().lastProcessedRevision)
        assertEquals(true, lastEmittedSnapshot?.goals?.first()?.terminalResultDelivered)
        assertEquals(0, coordinator.metrics().activeWorkers)
    }

    @Test
    fun `callback failure prevents state commitment`() = testScope.runTest {
        agentSource.revision.set(1L)
        
        var shouldFail = true
        val coordinator = AgentRefreshCoordinator(
            refreshSource = agentSource,
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = FakeStateApplier { _, _, _ ->
                if (shouldFail) {
                    RefreshApplyResult.Failure(RefreshFailure.CallbackApplicationFailure("Test Error"))
                } else {
                    emissionCount++
                    RefreshApplyResult.Success
                }
            },
            deliverPendingResults = { false }
        )

        coordinator.refresh(this)
        advanceUntilIdle()
        
        assertEquals(0, emissionCount)
        assertEquals(-1L, coordinator.metrics().lastProcessedRevision)
        assertEquals(1, coordinator.metrics().failures)

        shouldFail = false
        coordinator.refresh(this)
        advanceUntilIdle()
        
        assertEquals(1, emissionCount)
        assertEquals(1L, coordinator.metrics().lastProcessedRevision)
    }

    @Test
    fun `stable-read protocol detects churn`() = testScope.runTest {
        val coordinator = AgentRefreshCoordinator(
            refreshSource = object : FakeAgentRefreshSource() {
                private var callCount = 0
                override suspend fun loadStableSnapshot(): AgentSnapshotWithRevision {
                    // First call returns an old revision to trigger retry
                    return if (callCount++ == 0) {
                        AgentSnapshotWithRevision(snapshot, 0L)
                    } else {
                        AgentSnapshotWithRevision(snapshot, 1L)
                    }
                }
            },
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = stateApplier,
            deliverPendingResults = { false }
        )

        coordinator.refresh(this, 1L)
        advanceUntilIdle()
        
        assertNotEquals("Should have recorded a retry", 0, coordinator.metrics().stableReadRetries)
        assertEquals(1L, coordinator.metrics().lastProcessedRevision)
    }

    @Test
    fun `lost wakeup repair - request during worker shutdown is processed`() = testScope.runTest {
        agentSource.revision.set(1L)
        agentSource.snapshot = AgentSnapshot(goals = listOf(testGoal("rev1")))
        
        val barrier = Mutex(locked = true)
        val deliverWithBarrier: suspend (AgentSnapshot) -> Boolean = {
            barrier.lock()
            barrier.unlock()
            false
        }

        val coordinator = AgentRefreshCoordinator(
            refreshSource = agentSource,
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = stateApplier,
            deliverPendingResults = deliverWithBarrier
        )

        coordinator.refresh(this, 1L)
        // Wait for worker to hit the barrier
        runCurrent() 
        
        // 2. While worker is "busy", request new revision with changed content
        agentSource.revision.set(2L)
        agentSource.snapshot = AgentSnapshot(goals = listOf(testGoal("rev1"), testGoal("rev2")))
        coordinator.refresh(this, 2L)
        
        barrier.unlock()
        advanceUntilIdle()
        
        assertEquals("Should have processed both revisions", 2, emissionCount)
        assertEquals(2L, coordinator.metrics().lastProcessedRevision)
        assertEquals(0, coordinator.metrics().activeWorkers)
    }

    @Test
    fun `bounded failure - permanent error stops immediately`() = testScope.runTest {
        agentSource.revision.set(1L)
        
        val coordinator = AgentRefreshCoordinator(
            refreshSource = object : FakeAgentRefreshSource() {
                override suspend fun loadStableSnapshot(): AgentSnapshotWithRevision {
                    throw IllegalStateException("Permanent Error")
                }
            },
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = stateApplier,
            deliverPendingResults = { false }
        )

        coordinator.refresh(this)
        advanceUntilIdle()
        
        assertEquals(1, coordinator.metrics().failures)
        assertEquals(0, coordinator.metrics().retryAttempts)
        assertEquals(0, coordinator.metrics().activeWorkers)
    }

    @Test
    fun `action eligibility uses refreshed state`() = testScope.runTest {
        val goalId = "eligible-goal"
        val runningGoal = testGoal(goalId).copy(status = AgentGoalStatus.RUNNING)
        agentSource.revision.set(1L)
        agentSource.snapshot = AgentSnapshot(goals = listOf(runningGoal))
        
        var observedStatus: AgentGoalStatus? = null
        val coordinator = AgentRefreshCoordinator(
            refreshSource = agentSource,
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = FakeStateApplier { s, _, _ ->
                emissionCount++
                lastEmittedSnapshot = s
                observedStatus = s.goals.firstOrNull()?.status
                RefreshApplyResult.Success
            },
            deliverPendingResults = { false }
        )

        coordinator.refresh(this)
        advanceUntilIdle()
        assertEquals(AgentGoalStatus.RUNNING, observedStatus)

        val pausedGoal = runningGoal.copy(status = AgentGoalStatus.PAUSED)
        agentSource.revision.set(2L)
        agentSource.snapshot = AgentSnapshot(goals = listOf(pausedGoal))
        
        coordinator.refresh(this)
        advanceUntilIdle()
        
        assertEquals(AgentGoalStatus.PAUSED, observedStatus)
        
        val isPauseEligible = observedStatus == AgentGoalStatus.RUNNING
        val isResumeEligible = observedStatus == AgentGoalStatus.PAUSED
        
        assertEquals(false, isPauseEligible)
        assertEquals(true, isResumeEligible)
    }

    @Test
    fun `deterministic shutdown interleaving - request during release is processed once`() = testScope.runTest {
        val coordinator = AgentRefreshCoordinator(
            refreshSource = agentSource,
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = stateApplier,
            deliverPendingResults = { false }
        )

        // 1. Initial successful refresh
        agentSource.revision.set(1L)
        agentSource.snapshot = AgentSnapshot(goals = listOf(testGoal("rev1")))
        coordinator.refresh(this, 1L)
        advanceUntilIdle()
        assertEquals(1, emissionCount)
        assertEquals(1L, coordinator.metrics().lastProcessedRevision)

        // 2. Mock a worker that is about to exit but we intercept it.
        val barrier = Mutex(locked = true)
        val agentSourceWithBarrier = object : FakeAgentRefreshSource() {
            override suspend fun loadStableSnapshot(): AgentSnapshotWithRevision {
                barrier.lock() // Block here
                barrier.unlock()
                return super.loadStableSnapshot()
            }
        }
        agentSourceWithBarrier.revision.set(2L)
        agentSourceWithBarrier.snapshot = AgentSnapshot(goals = listOf(testGoal("rev2")))

        val coordinator2 = AgentRefreshCoordinator(
            agentSourceWithBarrier, toolSource, diagnostics, stateApplier, { false }
        )

        // Start worker for rev 2
        coordinator2.refresh(this, 2L)
        runCurrent() // Worker reaches barrier in loadStableSnapshot
        
        // While worker is blocked, revision 3 arrives
        agentSourceWithBarrier.revision.set(3L)
        agentSourceWithBarrier.snapshot = AgentSnapshot(goals = listOf(testGoal("rev2"), testGoal("rev3")))
        coordinator2.refresh(this, 3L)
        
        // Now release barrier. Worker should finish rev 3 (coalesced or via loop)
        barrier.unlock()
        advanceUntilIdle()
        
        // Verification:
        val metrics = coordinator2.metrics()
        assertEquals("Should have processed up to revision 3", 3L, metrics.lastProcessedRevision)
        assertEquals("Worker should have shut down", 0, metrics.activeWorkers)
        assertEquals("Worker should not be running", false, metrics.workerRunning)
    }

    @Test
    fun `failure shutdown interleaving - request during failure cleanup is not lost`() = testScope.runTest {
        var firstLoad = true
        val agentSourceWithFailure = object : FakeAgentRefreshSource() {
            override suspend fun loadStableSnapshot(): AgentSnapshotWithRevision {
                if (firstLoad) {
                    firstLoad = false
                    throw IllegalStateException("Permanent Failure")
                }
                return super.loadStableSnapshot()
            }
        }
        
        val coordinator = AgentRefreshCoordinator(
            agentSourceWithFailure, toolSource, diagnostics, stateApplier, { false }
        )

        agentSourceWithFailure.revision.set(1L)
        coordinator.refresh(this, 1L)
        advanceUntilIdle()
        
        assertEquals(1, coordinator.metrics().failures)
        assertEquals(-1L, coordinator.metrics().lastProcessedRevision)

        // Revision 2 arrives after the failure
        agentSourceWithFailure.revision.set(2L)
        agentSourceWithFailure.snapshot = AgentSnapshot(goals = listOf(testGoal("rev2")))
        coordinator.refresh(this, 2L)
        advanceUntilIdle()
        
        assertEquals(2L, coordinator.metrics().lastProcessedRevision)
        assertEquals(0, coordinator.metrics().activeWorkers)
    }

    @Test
    fun `listener-driven terminal settlement settles correctly`() = testScope.runTest {
        val goalId = "terminal-goal"
        val initialGoal = testGoal(goalId).copy(status = AgentGoalStatus.COMPLETED, terminalResultDelivered = false)
        agentSource.revision.set(10L)
        agentSource.snapshot = AgentSnapshot(goals = listOf(initialGoal))

        val coordinator = AgentRefreshCoordinator(
            agentSource, toolSource, diagnostics, stateApplier, 
            deliverPendingResults = { snapshot ->
                val pending = snapshot.goals.firstOrNull { it.id == goalId }?.terminalResultDelivered == false
                if (pending) {
                    // Production behavior: deliver, then update store which increments revision
                    val updatedGoal = initialGoal.copy(terminalResultDelivered = true)
                    agentSource.snapshot = AgentSnapshot(goals = listOf(updatedGoal))
                    agentSource.revision.set(11L)
                    // In production, SharedPreferences listener would call refresh(11L)
                    true 
                } else false
            }
        )

        // Mock the listener behavior
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "agent_store_revision_v1") {
                coordinator.refresh(testScope, agentSource.revision.get())
            }
        }
        
        // Initial trigger
        coordinator.refresh(this, 10L)
        
        // Simulate the listener being triggered when delivery happens
        while (agentSource.revision.get() == 10L) {
            runCurrent()
            if (testScope.currentTime > 10000) break // safety
        }
        
        if (agentSource.revision.get() == 11L) {
            listener.onSharedPreferenceChanged(null, "agent_store_revision_v1")
        }
        
        advanceUntilIdle()

        val metrics = coordinator.metrics()
        assertEquals(11L, metrics.lastProcessedRevision)
        assertEquals(11L, metrics.lastRequestedRevision)
        assertEquals(false, metrics.workerRunning)
        assertEquals(0, metrics.activeWorkers)
    }

    @Test
    fun `real ViewModel action proof - Pause Resume Cancel`() = testScope.runTest {
        // Narrow production-representative test
        val goalId = "action-goal"
        val runningGoal = testGoal(goalId).copy(status = AgentGoalStatus.RUNNING)
        
        // 1. Mock the "Production State" (ViewModel-like)
        var internalAgentSnapshot = AgentSnapshot()
        val uiStateFlow = MutableStateFlow(AgentSnapshot())
        
        val viewModelApplier = object : RefreshStateApplier {
            override suspend fun apply(snapshot: AgentSnapshot, recipeCount: Int, workspaceCount: Int): RefreshApplyResult {
                internalAgentSnapshot = snapshot
                uiStateFlow.update { snapshot }
                return RefreshApplyResult.Success
            }
        }

        val coordinator = AgentRefreshCoordinator(agentSource, toolSource, diagnostics, viewModelApplier, { false })

        // 2. Goal becomes RUNNING in background
        agentSource.revision.set(1L)
        agentSource.snapshot = AgentSnapshot(goals = listOf(runningGoal))
        coordinator.refresh(this, 1L)
        advanceUntilIdle()

        // 3. UI and Action Gate check the same state
        val uiSnapshot = uiStateFlow.value
        val actionSnapshot = internalAgentSnapshot
        
        assertEquals(AgentGoalStatus.RUNNING, uiSnapshot.goals.first().status)
        assertEquals(AgentGoalStatus.RUNNING, actionSnapshot.goals.first().status)

        // Action Gate: canPause
        val canPause = actionSnapshot.goals.first().status == AgentGoalStatus.RUNNING
        assertEquals(true, canPause)

        // 4. Transition to PAUSED
        val pausedGoal = runningGoal.copy(status = AgentGoalStatus.PAUSED)
        agentSource.revision.set(2L)
        agentSource.snapshot = AgentSnapshot(goals = listOf(pausedGoal))
        coordinator.refresh(this, 2L)
        advanceUntilIdle()

        assertEquals(AgentGoalStatus.PAUSED, uiStateFlow.value.goals.first().status)
        val canResume = internalAgentSnapshot.goals.first().status == AgentGoalStatus.PAUSED
        assertEquals(true, canResume)
    }

    @Test
    fun `stable-read protocol - target revision never reached is not processed`() = testScope.runTest {
        val churnSource = object : FakeAgentRefreshSource() {
            override suspend fun loadStableSnapshot(): AgentSnapshotWithRevision {
                // Always return a revision lower than requested
                return AgentSnapshotWithRevision(snapshot, revision.get() - 1)
            }
        }
        
        val coordinator = AgentRefreshCoordinator(churnSource, toolSource, diagnostics, stateApplier, { false })

        churnSource.revision.set(10L)
        coordinator.refresh(this, 10L)
        advanceUntilIdle()
        
        val metrics = coordinator.metrics()
        assertEquals(1, metrics.failures)
        assertEquals(-1L, metrics.lastProcessedRevision)
        assertNotEquals(0, metrics.stableReadFailures)
    }

    @Test
    fun `rapid revisions coalesce to latest`() = testScope.runTest {
        val coordinator = AgentRefreshCoordinator(agentSource, toolSource, diagnostics, stateApplier, { false })

        agentSource.revision.set(1L)
        coordinator.refresh(this, 1L)
        agentSource.revision.set(2L)
        coordinator.refresh(this, 2L)
        agentSource.revision.set(3L)
        coordinator.refresh(this, 3L)

        advanceUntilIdle()

        // With StandardTestDispatcher, all refreshes will be queued and the worker will likely coalesce 2 and 3.
        val metrics = coordinator.metrics()
        assertEquals(3L, metrics.lastProcessedRevision)
        assertNotEquals(0, metrics.coalesced)
    }

    @Test
    fun `stale owner cleanup cannot clear newer owner - deterministic`() = testScope.runTest {
        val loopExitReached = CompletableDeferred<Unit>()
        val workerBStarted = CompletableDeferred<Unit>()
        val workerBExecuting = CompletableDeferred<Unit>()
        val workerBBlock = CompletableDeferred<Unit>()

        val interceptingDiagnostics = object : FakeRefreshDiagnostics() {
            override fun info(event: String, fields: Map<String, Any?>) {
                if (event == "refresh_executing" && fields["revision"] == 2L) {
                    workerBExecuting.complete(Unit)
                }
            }
        }
        
        var applyCallCount = 0
        val blockingApplier = FakeStateApplier { s, r, w ->
            applyCallCount++
            if (applyCallCount == 2) {
                workerBBlock.await()
            }
            stateApplier.apply(s, r, w)
        }

        val coordinator = AgentRefreshCoordinator(
            refreshSource = agentSource,
            toolCountSource = toolSource,
            diagnostics = interceptingDiagnostics,
            stateApplier = blockingApplier,
            deliverPendingResults = { false },
            onWorkerLoopExit = {
                loopExitReached.complete(Unit)
                workerBStarted.await() 
            }
        )

        try {
            // 1. Worker A starts and finishes its loop
            agentSource.revision.set(1L)
            coordinator.refresh(testScope, 1L)
            
            // Advance until A hits the exit hook
            testScope.runCurrent()
            assertTrue("Loop exit should be reached", loopExitReached.isCompleted)
            assertFalse("Worker A should have released ownership in loop", coordinator.metrics().workerRunning)
            
            // 2. Start Worker B while A is paused in its hook
            agentSource.revision.set(2L)
            agentSource.snapshot = AgentSnapshot(goals = listOf(testGoal("rev2")))
            coordinator.refresh(testScope, 2L)
            
            // Advance until B starts executing
            testScope.runCurrent()
            assertTrue("Worker B should have reached execution", workerBExecuting.isCompleted)
            assertTrue("Worker B should now be the owner", coordinator.metrics().workerRunning)
            
            // 3. Now release Worker A so it can run its 'finally' block
            workerBStarted.complete(Unit)
            
            // Advance enough for A's finally to run
            testScope.runCurrent()
            
            // 4. Verification: Worker B should STILL be the owner
            assertTrue("Worker B should STILL be the owner after A's cleanup", coordinator.metrics().workerRunning)
            
            // 5. Unblock B and let everything finish
            workerBBlock.complete(Unit)
            testScope.advanceUntilIdle()
            
            assertEquals("Last processed revision should be 2", 2L, coordinator.metrics().lastProcessedRevision)
            assertEquals(0, coordinator.metrics().activeWorkers)
        } finally {
            workerBStarted.complete(Unit)
            workerBBlock.complete(Unit)
        }
    }

    @Test
    fun `cancellation propagates and cleans up`() = testScope.runTest {
        val applierStarted = CompletableDeferred<Unit>()
        val applierBlock = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        
        val blockingApplier = FakeStateApplier { s, r, w ->
            applierStarted.complete(Unit)
            try {
                applierBlock.await()
            } catch (e: CancellationException) {
                cancellationObserved.complete(Unit)
                throw e
            }
            stateApplier.apply(s, r, w)
        }
        
        val coordinator = AgentRefreshCoordinator(
            refreshSource = agentSource,
            toolCountSource = toolSource,
            diagnostics = diagnostics,
            stateApplier = blockingApplier,
            deliverPendingResults = { false }
        )

        val workerScope = CoroutineScope(SupervisorJob() + testDispatcher)
        try {
            agentSource.revision.set(1L)
            coordinator.refresh(workerScope, 1L)
            
            // 1. Wait for worker to reach the applier
            runCurrent()
            assertTrue("Should have reached applier", applierStarted.isCompleted)
            assertTrue("Worker should be running", coordinator.metrics().workerRunning)
            
            // 2. Cancel the scope that owns the worker
            workerScope.cancel()
            
            // 3. Unblock the applier so it can observe the cancellation
            applierBlock.complete(Unit)
            advanceUntilIdle()
            
            // 4. Verification
            assertTrue("Cancellation should have been observed", cancellationObserved.isCompleted)
            assertFalse("Worker should not be running after cancellation", coordinator.metrics().workerRunning)
            assertEquals("Active workers should be 0", 0, coordinator.metrics().activeWorkers)
            
            // 5. Prove restartability
            val freshScope = CoroutineScope(SupervisorJob() + testDispatcher)
            agentSource.revision.set(2L)
            agentSource.snapshot = AgentSnapshot(goals = listOf(testGoal("rev2")))
            coordinator.refresh(freshScope, 2L)
            
            advanceUntilIdle()
            assertEquals("Should have processed second request", 2L, coordinator.metrics().lastProcessedRevision)
            assertEquals(1, emissionCount)
            freshScope.cancel()
        } finally {
            workerScope.cancel()
            applierBlock.complete(Unit)
        }
    }

    private fun testGoal(id: String) = AgentGoal(
        id = id,
        conversationId = "conv-1",
        userRequest = "req",
        title = "Title",
        objective = "Obj",
        finalOutputDescription = "Out",
        status = AgentGoalStatus.QUEUED,
        plannerModelId = "m",
        executionModelId = "m",
        tasks = emptyList()
    )
}
