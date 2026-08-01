package com.david.openassistant.agent

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentGoalExecutionGateTest {
    @Test
    fun sameGoalWorkersNeverEnterTheCriticalSectionTogether() {
        runBlocking {
            val goalId = UUID.randomUUID().toString()
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val maximumActive = AtomicInteger()

            val first = async {
                AgentGoalExecutionGate.withGoalLock(goalId) {
                    maximumActive.accumulateAndGet(active.incrementAndGet()) { current, candidate ->
                        maxOf(current, candidate)
                    }
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    active.decrementAndGet()
                }
            }
            firstEntered.await()
            val second = async {
                AgentGoalExecutionGate.withGoalLock(goalId) {
                    maximumActive.accumulateAndGet(active.incrementAndGet()) { current, candidate ->
                        maxOf(current, candidate)
                    }
                    active.decrementAndGet()
                }
            }

            assertEquals(1, maximumActive.get())
            releaseFirst.complete(Unit)
            awaitAll(first, second)
            assertEquals(1, maximumActive.get())
            assertEquals(0, active.get())
        }
    }

    @Test
    fun differentGoalsCanProceedIndependently() {
        runBlocking {
            val entered = AtomicInteger()
            val bothEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()

            val workers = listOf("goal-a", "goal-b").map { goalId ->
                async {
                    AgentGoalExecutionGate.withGoalLock(goalId) {
                        if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                        release.await()
                    }
                }
            }

            bothEntered.await()
            assertEquals(2, entered.get())
            release.complete(Unit)
            workers.awaitAll()
        }
    }
}
