package com.david.openassistant.domain

import android.content.Context
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentLifecycleReducer
import com.david.openassistant.domain.model.AgentModelSelector
import com.david.openassistant.agent.AgentRoutingStage
import com.david.openassistant.agent.AgentScheduler
import com.david.openassistant.agent.AgentSnapshot
import com.david.openassistant.agent.AgentStore
import com.david.openassistant.agent.DebugMissionStartHook
import com.david.openassistant.agent.DurableSchedulingState
import com.david.openassistant.agent.MissionStartBoundaryHook
import com.david.openassistant.agent.ResearchDraft
import com.david.openassistant.agent.ResearchDraftStatus
import com.david.openassistant.agent.ResearchMissionStartTelemetry
import com.david.openassistant.agent.ResolvedResearchRequest
import com.david.openassistant.agent.RoutingPolicyProvenance
import com.david.openassistant.agent.SchedulingResult
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.openrouter.OpenRouterKeyInfo
import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.agent.ProviderRequestLedger
import com.david.openassistant.domain.model.ModelProfile
import com.david.openassistant.agent.AgentRefreshSource
import com.david.openassistant.agent.AgentSnapshotWithRevision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class MissionStartResult {
    data class InvalidMissionData(
        val reason: String,
    ) : MissionStartResult()

    data class CreatedAndScheduled(
        val goalId: String,
        val workId: UUID,
        val workInfoState: String,
        val snapshot: AgentSnapshot,
    ) : MissionStartResult()

    data class ReusedActiveMission(
        val goalId: String,
        val existingStatus: AgentGoalStatus,
        val workId: UUID?,
        val snapshot: AgentSnapshot,
    ) : MissionStartResult()

    data class CreatedWaitingForCredential(
        val goalId: String,
        val snapshot: AgentSnapshot,
    ) : MissionStartResult()

    data class RecoveredAndScheduled(
        val goalId: String,
        val workId: UUID,
        val recoveryReason: String,
        val snapshot: AgentSnapshot,
    ) : MissionStartResult()

    data class DraftPersistenceFailed(
        val error: Throwable,
    ) : MissionStartResult()

    data class GoalPersistenceFailed(
        val error: Throwable,
    ) : MissionStartResult()

    data class SchedulingStatePersistenceFailed(
        val goalId: String,
        val error: Throwable,
        val snapshot: AgentSnapshot,
    ) : MissionStartResult()

    data class SchedulingFailed(
        val goalId: String,
        val exception: Throwable,
        val recoverableStateSaved: Boolean,
        val snapshot: AgentSnapshot,
    ) : MissionStartResult()

    data class CleanupFailedAfterScheduled(
        val goalId: String,
        val workId: UUID,
        val error: Throwable,
        val snapshot: AgentSnapshot,
    ) : MissionStartResult()
}

class AgentInteractor internal constructor(
    context: Context?,
    private val agentStore: AgentStore?,
    private val agentScheduler: AgentScheduler?,
    private val boundaryHook: MissionStartBoundaryHook?
) : AgentRefreshSource {
    
    constructor(
        context: Context,
        boundaryHook: MissionStartBoundaryHook = DebugMissionStartHook(context),
    ) : this(
        context = context,
        agentStore = AgentStore(context),
        agentScheduler = AgentScheduler(context),
        boundaryHook = boundaryHook
    )

    override suspend fun loadStableSnapshot(): AgentSnapshotWithRevision = withContext(Dispatchers.IO) {
        agentStore!!.loadStableSnapshot()
    }

    override fun getLatestRevision(): Long {
        return agentStore!!.getLatestRevision()
    }

    suspend fun loadSnapshot(): AgentSnapshot = withContext(Dispatchers.IO) {
        agentStore!!.loadSnapshot()
    }

    fun registerListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        agentStore!!.registerListener(listener)
    }

    fun unregisterListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        agentStore!!.unregisterListener(listener)
    }

    suspend fun selectGoal(goalId: String): AgentSnapshot = withContext(Dispatchers.IO) {
        agentStore!!.selectGoal(goalId)
    }

    suspend fun updateGoal(goalId: String, transform: (AgentGoal) -> AgentGoal) = withContext(Dispatchers.IO) {
        agentStore!!.updateGoal(goalId, transform)
    }

    suspend fun upsertGoal(goal: AgentGoal, select: Boolean): AgentSnapshot = withContext(Dispatchers.IO) {
        agentStore!!.upsertGoal(goal, select)
    }

    suspend fun deleteGoal(goalId: String): AgentSnapshot = withContext(Dispatchers.IO) {
        agentStore!!.deleteGoal(goalId)
    }

    suspend fun savePendingDraft(draft: ResearchDraft?) = withContext(Dispatchers.IO) {
        agentStore!!.savePendingDraft(draft)
    }

    suspend fun loadPendingDraft(): ResearchDraft? = withContext(Dispatchers.IO) {
        agentStore!!.loadPendingDraft()
    }

    fun enqueue(goalId: String, replace: Boolean = false, generation: Int = 0) {
        agentScheduler!!.enqueue(goalId, replace, generation)
    }

    suspend fun enqueueAndConfirm(goalId: String, replace: Boolean = false, generation: Int = 0, activeLease: com.david.openassistant.agent.AgentExecutionLease? = null): SchedulingResult = withContext(Dispatchers.IO) {
        agentScheduler!!.enqueueAndWait(goalId, replace, generation, activeLease)
    }

    fun cancel(goalId: String, generation: Int = 0) {
        agentScheduler!!.cancel(goalId, generation)
    }

    suspend fun cancelAndWait(goalId: String, generation: Int = 0) = withContext(Dispatchers.IO) {
        agentScheduler!!.cancelAndWait(goalId, generation)
    }

    suspend fun finalize(goalId: String) = withContext(Dispatchers.IO) {
        try {
            var generation = 0
            agentStore!!.updateGoal(goalId) { current ->
                generation = current.executionLease?.generation ?: 0
                AgentLifecycleReducer.finalize(current)
            }
            // Signal cancellation to active calls immediately
            agentScheduler!!.cancel(goalId, generation)
            
            // Wait for in-flight requests to settle
            ProviderRequestLedger.waitForSettlement(timeoutMs = 5000L)
            
            // Once settled, transition to FINALIZING if it was CANCELLING
            agentStore.updateGoal(goalId) { current ->
                if (current.status == AgentGoalStatus.CANCELLING) {
                    current.copy(status = AgentGoalStatus.FINALIZING)
                } else {
                    current
                }
            }
            agentScheduler.cancelAndWait(goalId, generation)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    suspend fun startMissionFromBrief(
        draft: ResearchDraft,
        monitor: ResearchMonitor,
        hasCredential: Boolean,
        keyInfo: OpenRouterKeyInfo?,
        models: List<OpenRouterModel>,
        selectedModelId: String?,
        routingProfileName: String,
        automaticStart: Boolean,
        recoveryReason: String? = null,
    ): MissionStartResult = withContext(Dispatchers.IO) {
        val originalUserRequest = draft.originalUserRequest
        val integrityFailure = when {
            draft.id.isBlank() -> "Research submission ID is missing."
            draft.conversationId.isBlank() -> "Research conversation ID is missing."
            originalUserRequest.isBlank() -> "The exact original user request is missing."
            else -> null
        }
        if (integrityFailure != null) {
            return@withContext MissionStartResult.InvalidMissionData(integrityFailure)
        }
        val linkedGoalId = draft.linkedGoalId ?: UUID.randomUUID().toString()
        val startingDraft = draft.copy(
            status = ResearchDraftStatus.STARTING,
            durableSchedulingState = DurableSchedulingState.NOT_SCHEDULED,
            linkedGoalId = linkedGoalId,
            updatedAt = System.currentTimeMillis(),
        )

        // 1. Initial automatic/manual STARTING draft persistence
        val saveDraftResult = try {
            savePendingDraft(startingDraft)
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
        if (saveDraftResult.isFailure) {
            val err = saveDraftResult.exceptionOrNull() ?: IllegalStateException("Failed to save draft")
            return@withContext MissionStartResult.DraftPersistenceFailed(err)
        }

        // 2. Authoritative Telemetry Sequence
        ResearchMissionStartTelemetry.briefCreated(
            monitor = monitor,
            submissionId = startingDraft.id,
            draftId = startingDraft.id,
            linkedGoalId = linkedGoalId,
            conversationId = startingDraft.conversationId,
            automaticStart = automaticStart,
            routingProfile = routingProfileName,
        )

        ResearchMissionStartTelemetry.startRequested(
            monitor = monitor,
            submissionId = startingDraft.id,
            draftId = startingDraft.id,
            linkedGoalId = linkedGoalId,
            automaticStart = automaticStart,
            previousDraftState = draft.status.name,
            targetDraftState = "STARTING",
            routingProfile = routingProfileName,
            conversationId = startingDraft.conversationId,
        )

        // 3. Check existing goal idempotency
        val loadedSnapshot = agentStore!!.loadSnapshot()
        val existingGoal = loadedSnapshot.goals.firstOrNull { candidate ->
            candidate.submissionId == startingDraft.id || candidate.id == linkedGoalId
        }

        if (existingGoal != null) {
            ResearchMissionStartTelemetry.startResolved(monitor, startingDraft.id, existingGoal.id, if (recoveryReason != null) "recovered" else "reused")
            val selectedSnapshot = agentStore.selectGoal(existingGoal.id)

            if (hasCredential && existingGoal.status in setOf(
                    AgentGoalStatus.PLANNING,
                    AgentGoalStatus.QUEUED,
                    AgentGoalStatus.RUNNING,
                    AgentGoalStatus.VERIFYING,
                )
            ) {
                val generation = existingGoal.executionLease?.generation ?: 0
                when (val schedResult = agentScheduler!!.enqueueAndWait(existingGoal.id, replace = false, generation = generation, activeLease = existingGoal.executionLease)) {
                    is SchedulingResult.NewlyEnqueued -> {
                        ResearchMissionStartTelemetry.workerEnqueued(
                            monitor = monitor,
                            goalId = existingGoal.id,
                            submissionId = startingDraft.id,
                            policy = "KEEP",
                            workId = schedResult.workId.toString(),
                            workInfoState = schedResult.state.name,
                        )
                        try {
                            savePendingDraft(null)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            return@withContext MissionStartResult.CleanupFailedAfterScheduled(
                                goalId = existingGoal.id,
                                workId = schedResult.workId,
                                error = error,
                                snapshot = selectedSnapshot,
                            )
                        }
                        return@withContext if (recoveryReason != null) {
                            MissionStartResult.RecoveredAndScheduled(
                                goalId = existingGoal.id,
                                workId = schedResult.workId,
                                recoveryReason = recoveryReason,
                                snapshot = selectedSnapshot,
                            )
                        } else {
                            MissionStartResult.ReusedActiveMission(
                                goalId = existingGoal.id,
                                existingStatus = existingGoal.status,
                                workId = schedResult.workId,
                                snapshot = selectedSnapshot,
                            )
                        }
                    }
                    is SchedulingResult.ReusedActive -> {
                        ResearchMissionStartTelemetry.workerReused(
                            monitor = monitor,
                            goalId = existingGoal.id,
                            submissionId = startingDraft.id,
                            policy = "KEEP",
                            workId = schedResult.workId.toString(),
                            workInfoState = schedResult.state.name,
                        )
                        try {
                            savePendingDraft(null)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            return@withContext MissionStartResult.CleanupFailedAfterScheduled(
                                goalId = existingGoal.id,
                                workId = schedResult.workId,
                                error = error,
                                snapshot = selectedSnapshot,
                            )
                        }
                        return@withContext if (recoveryReason != null) {
                            MissionStartResult.RecoveredAndScheduled(
                                goalId = existingGoal.id,
                                workId = schedResult.workId,
                                recoveryReason = recoveryReason,
                                snapshot = selectedSnapshot,
                            )
                        } else {
                            MissionStartResult.ReusedActiveMission(
                                goalId = existingGoal.id,
                                existingStatus = existingGoal.status,
                                workId = schedResult.workId,
                                snapshot = selectedSnapshot,
                            )
                        }
                    }
                    else -> {
                        val err = IllegalStateException("Scheduling failed for existing goal: $schedResult")
                        ResearchMissionStartTelemetry.workerEnqueueFailed(
                            monitor = monitor,
                            goalId = existingGoal.id,
                            submissionId = startingDraft.id,
                            exceptionType = schedResult::class.java.simpleName,
                            message = schedResult.toString(),
                            policy = "KEEP",
                            type = "reused",
                        )
                        return@withContext MissionStartResult.SchedulingFailed(
                            goalId = existingGoal.id,
                            exception = err,
                            recoverableStateSaved = true,
                            snapshot = selectedSnapshot,
                        )
                    }
                }
            } else {
                try {
                    savePendingDraft(null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withContext MissionStartResult.SchedulingStatePersistenceFailed(
                        goalId = existingGoal.id,
                        error = error,
                        snapshot = selectedSnapshot,
                    )
                }
                return@withContext MissionStartResult.ReusedActiveMission(
                    goalId = existingGoal.id,
                    existingStatus = existingGoal.status,
                    workId = null,
                    snapshot = selectedSnapshot,
                )
            }
        }

        // 4. Debug hook pause boundary
        boundaryHook!!.onBoundaryReached("pre_goal_upsert", startingDraft)

        // 5. Construct and upsert new goal
        val profile = ModelProfile.entries.firstOrNull { it.name == routingProfileName }
        val accountHasNoBalance = keyInfo?.limitRemaining?.let { it <= 0.0 } == true
        val freeOnlyRouting = profile == ModelProfile.FREE || keyInfo?.isFreeTier == true || accountHasNoBalance
        
        val routingProvenance = when {
            profile == ModelProfile.FREE -> RoutingPolicyProvenance.EXPLICIT_USER_SELECTION
            keyInfo?.isFreeTier == true || accountHasNoBalance -> RoutingPolicyProvenance.ACCOUNT_SAFETY_RESTRICTION
            else -> RoutingPolicyProvenance.EXPLICIT_USER_SELECTION
        }

        val modelRoute = AgentModelSelector.choose(
            models = models,
            selectedModelId = selectedModelId,
            freeOnly = freeOnlyRouting,
        )

        val initialGoalStatus = if (hasCredential) AgentGoalStatus.PLANNING else AgentGoalStatus.WAITING_FOR_CREDENTIAL

        val goal = AgentGoal(
            id = linkedGoalId,
            conversationId = startingDraft.conversationId,
            submissionId = startingDraft.id,
            userRequest = originalUserRequest,
            title = startingDraft.title,
            objective = startingDraft.objective,
            finalOutputDescription = startingDraft.desiredDeliverable,
            confirmedConstraints = startingDraft.confirmedConstraints,
            inferredPreferences = startingDraft.inferredPreferences,
            unresolvedQuestions = startingDraft.unresolvedQuestions,
            evidenceRequirements = startingDraft.evidenceRequirements,
            preferredSourceTypes = startingDraft.preferredSourceTypes,
            freshnessRequirement = startingDraft.freshnessRequirement,
            exclusions = startingDraft.exclusions,
            sourceMessageIds = startingDraft.sourceMessageIds,
            status = initialGoalStatus,
            plannerModelId = modelRoute.plannerModelId,
            executionModelId = modelRoute.executionModelId,
            routingStage = if (freeOnlyRouting) AgentRoutingStage.FREE else AgentRoutingStage.AUTO_BETA,
            requestedModelProfileName = profile?.name,
            routingPolicyProvenance = routingProvenance,
            freeOnly = freeOnlyRouting,
            tasks = emptyList(),
            error = if (hasCredential) null else "Waiting for a valid OpenRouter credential.",
            resolvedResearchRequest = startingDraft.resolvedResearchRequest
                ?: ResolvedResearchRequest.createFallbackSingleRequest(originalUserRequest),
        )

        val upsertResult = try {
            agentStore.upsertGoal(goal, select = true)
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
        if (upsertResult.isFailure) {
            val err = upsertResult.exceptionOrNull() ?: IllegalStateException("Failed to upsert goal")
            return@withContext MissionStartResult.GoalPersistenceFailed(err)
        }

        val snapshotAfterUpsert = agentStore.loadSnapshot()
        val createdGoal = snapshotAfterUpsert.goals.firstOrNull { it.id == goal.id }
            ?: return@withContext MissionStartResult.GoalPersistenceFailed(
                IllegalStateException("Goal $linkedGoalId not found after persistence"),
            )

        // 6. Write GOAL_PERSISTED state only after verified goal readback
        val goalPersistedDraft = startingDraft.copy(durableSchedulingState = DurableSchedulingState.GOAL_PERSISTED)
        val statePersistResult = try {
            savePendingDraft(goalPersistedDraft)
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
        if (statePersistResult.isFailure) {
            val err = statePersistResult.exceptionOrNull() ?: IllegalStateException("Failed to persist GOAL_PERSISTED state")
            return@withContext MissionStartResult.SchedulingStatePersistenceFailed(createdGoal.id, err, snapshotAfterUpsert)
        }

        if (!hasCredential) {
            ResearchMissionStartTelemetry.startResolved(monitor, startingDraft.id, createdGoal.id, "waiting_for_credential")
            val clearDraftResult = try {
                savePendingDraft(null)
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
            if (clearDraftResult.isFailure) {
                val err = clearDraftResult.exceptionOrNull() ?: IllegalStateException("Cleanup failed for no credential goal")
                return@withContext MissionStartResult.SchedulingStatePersistenceFailed(createdGoal.id, err, snapshotAfterUpsert)
            }
            return@withContext MissionStartResult.CreatedWaitingForCredential(
                goalId = createdGoal.id,
                snapshot = snapshotAfterUpsert,
            )
        }

        ResearchMissionStartTelemetry.startResolved(monitor, startingDraft.id, createdGoal.id, if (recoveryReason != null) "recovered" else "created")

        // 7. Write SCHEDULING_PENDING prior to WorkManager call
        val schedulingPendingDraft = goalPersistedDraft.copy(durableSchedulingState = DurableSchedulingState.SCHEDULING_PENDING)
        try {
            savePendingDraft(schedulingPendingDraft)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return@withContext MissionStartResult.SchedulingStatePersistenceFailed(
                goalId = createdGoal.id,
                error = error,
                snapshot = snapshotAfterUpsert,
            )
        }

        // 8. Enqueue WorkManager job and handle explicit SchedulingResult branching
        when (val schedResult = agentScheduler!!.enqueueAndWait(createdGoal.id, replace = false, generation = 0)) {
            is SchedulingResult.NewlyEnqueued -> {
                ResearchMissionStartTelemetry.workerEnqueued(
                    monitor = monitor,
                    goalId = createdGoal.id,
                    submissionId = startingDraft.id,
                    policy = "KEEP",
                    workId = schedResult.workId.toString(),
                    workInfoState = schedResult.state.name,
                )
                try {
                    savePendingDraft(schedulingPendingDraft.copy(durableSchedulingState = DurableSchedulingState.SCHEDULED))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withContext MissionStartResult.CleanupFailedAfterScheduled(
                        goalId = createdGoal.id,
                        workId = schedResult.workId,
                        error = error,
                        snapshot = snapshotAfterUpsert,
                    )
                }
                val clearResult = try {
                    savePendingDraft(null)
                    Result.success(Unit)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Result.failure(e)
                }
                if (clearResult.isFailure) {
                    val err = clearResult.exceptionOrNull() ?: IllegalStateException("Cleanup failed after scheduled")
                    return@withContext MissionStartResult.CleanupFailedAfterScheduled(
                        goalId = createdGoal.id,
                        workId = schedResult.workId,
                        error = err,
                        snapshot = snapshotAfterUpsert,
                    )
                }
                return@withContext if (recoveryReason != null) {
                    MissionStartResult.RecoveredAndScheduled(
                        goalId = createdGoal.id,
                        workId = schedResult.workId,
                        recoveryReason = recoveryReason,
                        snapshot = snapshotAfterUpsert,
                    )
                } else {
                    MissionStartResult.CreatedAndScheduled(
                        goalId = createdGoal.id,
                        workId = schedResult.workId,
                        workInfoState = schedResult.state.name,
                        snapshot = snapshotAfterUpsert,
                    )
                }
            }
            is SchedulingResult.ReusedActive -> {
                ResearchMissionStartTelemetry.workerReused(
                    monitor = monitor,
                    goalId = createdGoal.id,
                    submissionId = startingDraft.id,
                    policy = "KEEP",
                    workId = schedResult.workId.toString(),
                    workInfoState = schedResult.state.name,
                )
                try {
                    savePendingDraft(schedulingPendingDraft.copy(durableSchedulingState = DurableSchedulingState.SCHEDULED))
                    savePendingDraft(null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    return@withContext MissionStartResult.CleanupFailedAfterScheduled(
                        goalId = createdGoal.id,
                        workId = schedResult.workId,
                        error = error,
                        snapshot = snapshotAfterUpsert,
                    )
                }
                return@withContext if (recoveryReason != null) {
                    MissionStartResult.RecoveredAndScheduled(
                        goalId = createdGoal.id,
                        workId = schedResult.workId,
                        recoveryReason = recoveryReason,
                        snapshot = snapshotAfterUpsert,
                    )
                } else {
                    MissionStartResult.CreatedAndScheduled(
                        goalId = createdGoal.id,
                        workId = schedResult.workId,
                        workInfoState = schedResult.state.name,
                        snapshot = snapshotAfterUpsert,
                    )
                }
            }
            else -> {
                val err = IllegalStateException("WorkManager scheduling failed: $schedResult")
                val failedDraft = schedulingPendingDraft.copy(durableSchedulingState = DurableSchedulingState.SCHEDULING_FAILED)
                val savedRecoverable = try {
                    savePendingDraft(failedDraft)
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    false
                }

                ResearchMissionStartTelemetry.workerEnqueueFailed(
                    monitor = monitor,
                    goalId = createdGoal.id,
                    submissionId = startingDraft.id,
                    exceptionType = schedResult::class.java.simpleName,
                    message = schedResult.toString(),
                    policy = "KEEP",
                    type = if (recoveryReason != null) "recovery" else "initial",
                )

                return@withContext MissionStartResult.SchedulingFailed(
                    goalId = createdGoal.id,
                    exception = err,
                    recoverableStateSaved = savedRecoverable,
                    snapshot = snapshotAfterUpsert,
                )
            }
        }
    }
}
