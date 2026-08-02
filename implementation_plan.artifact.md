# V42.2: Durable Within-Cycle Research Recovery and Material Strategy Pivots

This plan replaces the temporary duplicate-context pause with a durable recovery mechanism for within-cycle tactic pivots.

## User Review Required

> [!IMPORTANT]
> This slice implements **within-cycle tactic pivots only**. Objective-level research-cycle advancement is reserved for V42.3.

## Proposed Changes

### [Component] Core Enums
#### [MODIFY] [AgentCoreEnums.kt](file:///C:/Users/david/Documents/GitHub/openrouter/app/src/main/java/com/david/openassistant/agent/AgentCoreEnums.kt)
- Extend `ExecutionStallDiagnosis` with:
    - `REPEATED_CONTEXT`
    - `DUPLICATE_QUERY_PORTFOLIO`
    - `SOURCE_HOMOGENEITY`
    - `NO_NEW_ACCEPTED_EVIDENCE`
    - `STALE_RESEARCH_STRATEGY`
    - `UNRESOLVED_GAP_STAGNATION`
    - `ENTITY_AMBIGUITY`
    - `TEMPORAL_SCOPE_MISMATCH`
- Extend `EscalationTactic` with:
    - `RESOLVE_ENTITIES`
    - `SHIFT_SOURCE_FAMILY`
    - `FOLLOW_CITATIONS`
    - `DECOMPOSE_UNRESOLVED_GAP`
    - `SEARCH_CONTRADICTING_EVIDENCE`
    - `CHANGE_TEMPORAL_SCOPE`
    - `CHANGE_GEOGRAPHIC_SCOPE`
    - `SEARCH_PRIMARY_RECORDS`
    - `REBUILD_QUERY_PORTFOLIO`
    - `REVISE_OPERATIONAL_OBJECTIVE`

---

### [Component] Models
#### [MODIFY] [AgentResearchModels.kt](file:///C:/Users/david/Documents/GitHub/openrouter/app/src/main/java/com/david/openassistant/agent/AgentResearchModels.kt)
- Add `ResearchRecoveryPlan`, `RecoveryPlanStatus`, and `RecoveryProposal` data classes.
- Ensure all models are serializable to JSON (standard project practice).

#### [MODIFY] [AgentGoalModels.kt](file:///C:/Users/david/Documents/GitHub/openrouter/app/src/main/java/com/david/openassistant/agent/AgentGoalModels.kt)
- Add `recoveryPlans: List<ResearchRecoveryPlan>` and `activeRecoveryPlanId: String?` to `AgentGoal`.

---

### [Component] Persistence
#### [MODIFY] [AgentStore.kt](file:///C:/Users/david/Documents/GitHub/openrouter/app/src/main/java/com/david/openassistant/agent/AgentStore.kt)
- Update JSON serialization/deserialization for `AgentGoal` to include the new recovery fields.
- Implement symmetric encode/decode.

---

### [Component] Logic
#### [NEW] [ResearchRecoveryEngine.kt](file:///C:/Users/david/Documents/GitHub/openrouter/app/src/main/java/com/david/openassistant/agent/ResearchRecoveryEngine.kt)
- Implement a pure deterministic recovery engine:
    - `diagnoseStall()`: Logic to detect new stall types.
    - `selectTactic()`: Logic to pick the next applicable, unused tactic.
    - `generatePlanIdentity()`: SHA-256 derivation from goal/task/context.
    - `validateNovelty()`: Gate to reject cosmetic-only strategy changes.

#### [MODIFY] [AgentTaskExecutor.kt](file:///C:/Users/david/Documents/GitHub/openrouter/app/src/main/java/com/david/openassistant/agent/AgentTaskExecutor.kt)
- Integrate `ResearchRecoveryEngine` into the execution loop.
- Implement the atomic commit of a tactic pivot.
- Authorize exactly one retry with the new strategy.

---

### [Component] Infrastructure Integration
#### [MODIFY] [AgentGoalWorker.kt](file:///C:/Users/david/Documents/GitHub/openrouter/app/src/main/java/com/david/openassistant/agent/AgentGoalWorker.kt)
- Ensure the worker correctly picks up tasks in `RECOVERING` status.

---

## Verification Plan

### Automated Tests
- `ResearchRecoveryEngineTest`: Test stall diagnosis, tactic selection, and novelty validation.
- `AgentStoreRecoveryPersistenceTest`: Verify round-trip serialization of recovery plans.
- `AgentTaskExecutorRecoveryTest`: End-to-end test of recovery flow (diagnose -> prepare -> commit -> retry).
- Run existing V41 and V42.1 tests.

### Manual Verification
- None required; all logic is backend-driven and covered by unit tests.
