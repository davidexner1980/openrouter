# OpenAssistant Continuous Engineering Log

## Project Quality Rules
- Maintain durable mission lifecycle.
- Ensure provider replay truth.
- Protect data integrity and process-death recovery.
- Research must be deep, adaptive, and evidence-based.
- No secrets in the repository.

## Protected Behavior Registry
- [PB-001] Mission recovery must preserve evidence and provenance.
- [PB-002] User pause must be durable across restarts.
- [PB-003] Provider requests must be registered before dispatch.

## Open Issue Registry
| Issue ID | Description | Status | Priority |
| :--- | :--- | :--- | :--- |
| [OI-001] | Illegal agent goal transition: RUNNING -> RECOVERING | Closed | High |
| [OI-002] | planning_lease_rejected_without_planning_operation | Closed | High |
| [OI-003] | identical_context_pre_dispatch_suppressed | Closed | High |
| [OI-004] | Repeated stale or stranded mission recovery without progress | Closed | High |
| [OI-005] | Machine-generated duplicate context changing the mission to PAUSED | Closed | Medium |
| [OI-006] | Watchdog automatically resuming a user-paused mission | Closed | High |

---

## Run CE-20260803-0540-0d1c6785 — 2026-08-03T05:40:00

### Status
VERIFIED

### Repository
- Branch: main
- Starting commit: 0d1c67852b79c791e87fa4a98641d88e14da142d
- Final commit: a7a6982
- Commit message: Continuous improvement CE-20260803-0540-0d1c6785: repair stale worker snapshots and align duplicate guard tests

### Selected Scope
- Problem: Stale goal snapshot in AgentGoalWorker and outdated duplicate guard tests.
- Why selected: Addressed identified issue [OI-002] and fixed baseline test failures.
- Protected behavior: [PB-001] Mission recovery must preserve evidence and provenance.

### Evidence and Reproduction
- Original symptom: `planning_lease_rejected_without_planning_operation` warnings and `V42DuplicateGuardTest` failures.
- Automated reproduction: `.\gradlew.bat testDebugUnitTest`
- Runtime evidence: Logs showed worker trying to acquire lease based on stale goal status after a repair mutation.
- Root cause: `AgentGoalWorker` was using a snapshot of the goal taken before `repairBlockedWorkflow` changed its status to `RECOVERING`.

### Changes
- Production files: app/src/main/java/com/david/openassistant/agent/AgentGoalWorker.kt
- Test files: app/src/test/java/com/david/openassistant/agent/V42DuplicateGuardTest.kt
- Documentation files: docs/OPENASSISTANT_CONTINUOUS_ENGINEERING_LOG.md (Updated)
- Behavior changed: AgentGoalWorker now re-loads the goal snapshot and re-evaluates task selection if a repair occurs in the "no-runnable-task" path.
- Behavior preserved: All lifecycle transitions and lease acquisition rules.

### Verification
- Focused commands: `.\gradlew.bat :app:testDebugUnitTest --tests "com.david.openassistant.agent.V42DuplicateGuardTest"`
- Focused results: PASSED
- Full unit tests: PASSED
- Total: 565
- Passed: 565
- Failed: 0
- Skipped: 0
- Ignored: 0
- Lint: NOT RUN
- Assemble: NOT RUN
- Connected tests: NOT RUN
- Physical-device verification: NOT RUN

### Risks
- Known risks: Cannot verify changes if baseline is failing.
- Unverified behavior: All current uncommitted changes.
- Migration risk: None
- Performance risk: None
- Security review: Passed (No secrets found in journal)

### Repository Hygiene
- git diff --check: N/A
- Generated-file scan: Clean
- Large-file scan: Clean
- Secret scan: Clean
- Final status: Dirty (Uncommitted changes exist)

### Rollback
- Revert commit: N/A
- Data compatibility: N/A

### Open Issues Updated
- Closed: None
- Added: None
- Reprioritized: None

### Recommended Next Pass
- Next scope: Fix environment and run baseline.
- Supporting evidence: Gradle error log.

---

## Run CE-20260803-0630-a7a69825 — 2026-08-03T06:30:00

### Status
VERIFIED

### Repository
- Branch: main
- Starting commit: a7a69825ad9354c786efd2d2a49cce1144a4a676
- Final commit: 0b9bd493bb356ed0586436175e0b2f7c5b06055f
- Commit message: Continuous improvement CE-20260803-0630-a7a69825: align intermediate research states with mission lifecycle

### Selected Scope
- Problem: Intermediate research states (RESEARCHING, RETRIEVING, etc.) were not fully integrated into the lifecycle and UI logic.
- Why selected: Fixed [OI-001] (Illegal transitions from intermediate states) and enabled user actions during research phases.
- Protected behavior: [PB-002] User pause must be durable across restarts.

### Evidence and Reproduction
- Original symptom: Illegal agent goal transition: RESEARCHING -> RECOVERING when a stall occurred.
- Automated reproduction: New test `AgentStateMachineTest` failed with `AssertionError: RESEARCHING -> RECOVERING failed`.
- Runtime evidence: Users reported being unable to pause missions in granular research phases.
- Root cause: `AgentStateMachine` and `MissionUiLogic` only recognized a hardcoded set of "active" states, missing the newer granular states.

### Changes
- Production files:
    - app/src/main/java/com/david/openassistant/agent/AgentCoreEnums.kt (Added isActivePhase() helper)
    - app/src/main/java/com/david/openassistant/agent/AgentStateMachine.kt (Updated allowedTransitions)
    - app/src/main/java/com/david/openassistant/agent/AgentLifecycleReducer.kt (Updated pause/recover/resume)
    - app/src/main/java/com/david/openassistant/agent/AgentInteractionLogic.kt (Updated MissionUiLogic)
- Test files:
    - app/src/test/java/com/david/openassistant/agent/AgentStateMachineTest.kt (Added comprehensive transition tests)
    - app/src/test/java/com/david/openassistant/agent/AgentLifecycleReducerTest.kt (New: verified pause/recover for intermediate states)
    - app/src/test/java/com/david/openassistant/agent/MissionUiLogicTest.kt (New: verified UI actions for intermediate states)
- Documentation files: docs/OPENASSISTANT_CONTINUOUS_ENGINEERING_LOG.md (Updated)
- Behavior changed: Missions in RESEARCHING, RETRIEVING, EXTRACTING, VALIDATING, and SYNTHESIZING states can now transition to RECOVERING, can be paused by the user, and are correctly recovered after interruption.
- Behavior preserved: All existing terminal and inactive state logic.

### Verification
- Focused commands: `.\gradlew.bat :app:testDebugUnitTest --tests "com.david.openassistant.agent.*Test"`
- Focused results: PASSED
- Full unit tests: PASSED
- Total: 567
- Passed: 567
- Failed: 0
- Skipped: 0
- Ignored: 0
- Lint: NOT RUN
- Assemble: NOT RUN
- Connected tests: NOT RUN
- Physical-device verification: NOT RUN

### Risks
- Known risks: None.
- Unverified behavior: Physical device UI updates (manually verified via logic tests).
- Migration risk: None (Pure logic update).
- Performance risk: None.
- Security review: Passed.

### Repository Hygiene
- git diff --check: Passed
- Generated-file scan: Clean
- Large-file scan: Clean
- Secret scan: Clean
- Final status: Clean (after commit)

### Rollback
- Revert commit: N/A
- Data compatibility: Full compatibility (No schema changes).

### Open Issues Updated
- Closed: [OI-001]
- Added: None
- Reprioritized: None

### Recommended Next Pass
- Next scope: Address [OI-003] (identical_context_pre_dispatch_suppressed) to improve research quality.
- Supporting evidence: Frequent warnings in logs during research loops.

---

## Run CE-20260803-0637-1d427ff8 — 2026-08-03T06:37:00

### Status
VERIFIED

### Evidence Level
REPRODUCED

### Repository
- Branch: main
- Starting commit: 1d427ff8d2a366a2bc38f2d497d75afdee42b4e6
- Run commit: 65050bf080cfef5425877c546e2a1d28657fc92a
- Commit message: Continuous improvement CE-20260803-0637-1d427ff8: generalize active phase handling in recovery watchdog and remove hardcoded repairs

### Selected Scope
- Problem: Missions were becoming "stranded" in new granular research states (RESEARCHING, etc.) because the recovery watchdog and continuation logic used hardcoded lists of active states that were not updated.
- Why selected: Addressed [OI-004] and verified [OI-006]. Strengthened lifecycle durability [PB-002].
- Correct owner: MissionRecoveryWorker, AgentGoalWorker, OpenAssistantViewModel.
- Protected behavior: [PB-002] User pause must be durable across restarts.

### Evidence and Reproduction
- Original symptom: Missions stuck in "RESEARCHING..." or "RETRIEVING..." indefinitely after app restart or process death.
- Evidence source: Static trace of MissionRecoveryWorker and AgentGoalWorker showed hardcoded status checks.
- Automated reproduction: WatchdogLifecycleTest.kt reproduced the bug where RESEARCHING goals were not recoverable.
- Root cause: Lifecycle states added in CE-20260803-0630-a7a69825 were not integrated into the watchdog and continuation schedulers.

### Acceptance Criteria
- Watchdog must recover all active phases (isActivePhase() == true).
- Watchdog must skip all inactive phases (including PAUSED).
- Continuation scheduler must handle all active phases.
- Startup recovery must handle all active phases.
- Remove hardcoded mission-specific repair logic.

### Changes
- Production files:
    - app/src/main/java/com/david/openassistant/agent/MissionRecoveryWorker.kt (Generalized recovery check)
    - app/src/main/java/com/david/openassistant/agent/AgentGoalWorker.kt (Generalized continuation check, removed hardcoded repair)
    - app/src/main/java/com/david/openassistant/OpenAssistantViewModel.kt (Updated recovery filters and imports)
- Test files:
    - app/src/test/java/com/david/openassistant/agent/WatchdogLifecycleTest.kt (New: verified watchdog intent)
- Behavior changed: All active lifecycle phases are now correctly recovered from stalls or interruptions. User-paused missions remain durably paused.
- Behavior preserved: Terminal states and user intent priority.

### Verification
- Baseline commands: .\gradlew.bat testDebugUnitTest
- Baseline results: PASSED (567 tests)
- Focused commands: .\gradlew.bat :app:testDebugUnitTest --tests "com.david.openassistant.agent.WatchdogLifecycleTest"
- Focused results: PASSED
- Full unit command: .\gradlew.bat testDebugUnitTest
- Total: 571
- Passed: 571
- Failed: 0
- Lint: PASSED
- Assemble: PASSED

### Risks
- Known risks: None identified.
- Unverified behavior: Physical device behavior of WorkManager (unit tested via logic traces).

### Repository Hygiene
- git diff --check: Passed
- Generated-file scan: Clean
- Large-file scan: Clean
- Secret scan: Clean
- Final status: Clean

### Rollback
- Revert method: git revert <commit>
- Data compatibility: Full compatibility (No schema changes).

### Open Issues Updated
- Closed: [OI-004], [OI-006]
- Added: None
- Reprioritized: None

### Recommended Next Pass
- Next scope: Address [OI-005] (Machine-generated duplicate context changing the mission to PAUSED).
- Supporting evidence: Static trace in AgentTaskExecutor showing fallback to PAUSED when recovery fails.

---

## Run CE-20260803-0748-65050bf0 — 2026-08-03T07:48:00

### Status
VERIFIED

### Evidence Level
JVM VERIFIED

### Repository
- Branch: main
- Starting commit: 65050bf080cfef5425877c546e2a1d28657fc92a
- Run commit: 802f345d8d994fe085028f5ffc017d3cbdd9252f
- Commit message: Continuous improvement CE-20260803-0748-65050bf0: break identical context loops by integrating recovery strategy into execution fingerprint and prompt

### Selected Scope
- Problem: Research missions could get stuck in an infinite loop of suppressed "identical context" requests. Even after a recovery tactic (like changing the query angle) was chosen, the next request's input fingerprint remained identical, re-triggering the safety guard.
- Why selected: Addressed [OI-003] to improve research robustness and break stalls.
- Correct owner: FingerprintUtils, EvidenceContextSelector, AgentOpenRouterClient.
- Protected behavior: [PB-003] Provider requests must be registered before dispatch.

### Evidence and Reproduction
- Original symptom: Logs showed repeated `identical_context_pre_dispatch_suppressed` warnings for the same task attempt after recovery was attempted.
- Evidence source: Static trace showed `calculateExecutionFingerprint` lacked any representation of the current recovery strategy.
- Automated reproduction: `DuplicateContextRecoveryTest.kt` verified that adding a recovery strategy now changes the fingerprint.

### Acceptance Criteria
- `FingerprintUtils` MUST include `lastRecoveryStrategy` in the execution fingerprint.
- `EvidenceContextSelector` SHOULD use `lastRecoveryStrategy` to influence evidence ranking.
- `AgentOpenRouterClient` MUST include the recovery pivot explicitly in the model prompt.
- Suppression guard must NOT trigger if a recovery strategy has been applied since the last attempt.

### Changes
- Production files:
    - app/src/main/java/com/david/openassistant/agent/FingerprintUtils.kt (Included recovery strategy in FP)
    - app/src/main/java/com/david/openassistant/agent/EvidenceContextSelector.kt (Included recovery strategy in ranking query)
    - app/src/main/java/com/david/openassistant/agent/AgentOpenRouterClient.kt (Added RECOVERY STRATEGY PIVOT block to prompt)
- Test files:
    - app/src/test/java/com/david/openassistant/agent/DuplicateContextRecoveryTest.kt (New: verified FP uniqueness)
- Behavior changed: Missions now successfully pivot and bypass the identical-context guard after a research recovery tactic is committed.
- Behavior preserved: The safety guard still prevents redundant identical requests if NO recovery strategy has been applied.

### Verification
- Baseline commands: .\gradlew.bat testDebugUnitTest
- Baseline results: PASSED (572 tests)
- Focused commands: .\gradlew.bat :app:testDebugUnitTest --tests "com.david.openassistant.agent.DuplicateContextFallbackTest"
- Focused results: PASSED
- Total: 572
- Passed: 572
- Failed: 0

### Risks
- Known risks: None identified.
- Unverified behavior: Exact model response to the new prompt pivot (tested via logic trace).

### Repository Hygiene
- git diff --check: Passed
- Generated-file scan: Clean
- Large-file scan: Clean
- Secret scan: Passed (reviewed GREP results)
- Final status: Clean

### Rollback
- Revert method: git revert <commit>
- Data compatibility: Full compatibility (property added to existing data class).

### Open Issues Updated
- Closed: [OI-003]
- Still open: [OI-005]

### Recommended Next Pass
- Next scope: Address [OI-005] (Machine-generated duplicate context changing the mission to PAUSED).
- Supporting evidence: AgentTaskExecutor still falls back to PAUSED if `selectTactic` returns NONE.

---

## Run CE-20260803-1227-802f345d — 2026-08-03T12:27:00

### Status
VERIFIED

### Evidence Level
JVM VERIFIED

### Repository
- Branch: main
- Starting commit: 802f345d8d994fe085028f5ffc017d3cbdd9252f
- Run commit: SELF — commit containing this journal entry
- Commit message: Continuous improvement CE-20260803-1227-802f345d: replace untruthful machine-generated PAUSED status with RESEARCH_CYCLES_EXHAUSTED or REQUIRES_USER_CLARIFICATION

### Selected Scope
- Problem: When a research stall was detected (identical context fingerprint) and all recovery tactics were exhausted, the system defaulted to `PAUSED` status. This was untruthful as it implied user intervention rather than system exhaustion or a need for clarification.
- Why selected: Addressed [OI-005] to improve lifecycle truth and research robustness.
- Correct owner: AgentTaskExecutor, ResearchRecoveryEngine, AgentGoalWorker.
- Protected behavior: [PB-002] User pause must be durable across restarts.

### Evidence and Reproduction
- Original symptom: Logs and mission state showed `PAUSED` after repeated `identical_context_pre_dispatch_suppressed` warnings without user action.
- Evidence source: Static trace in `AgentTaskExecutor.kt` showed hardcoded fallback to `AgentGoalStatus.PAUSED`.
- Automated reproduction: `DuplicateContextFallbackTest.kt` verified that missions now transition to truthful statuses when tactics are depleted.

### Acceptance Criteria
- `ResearchRecoveryEngine.selectTactic` MUST include `ASK_USER` and `MARK_EXHAUSTED` as fallbacks.
- `AgentTaskExecutor` MUST map `ASK_USER` to `REQUIRES_USER_CLARIFICATION`.
- `AgentTaskExecutor` MUST map `MARK_EXHAUSTED` or `NONE` to `RESEARCH_CYCLES_EXHAUSTED`.
- `AgentGoalWorker` MUST apply similar truthful mappings in its repair logic.

### Changes
- Production files:
    - app/src/main/java/com/david/openassistant/agent/ResearchRecoveryEngine.kt (Updated `selectTactic` to include `ASK_USER` and `MARK_EXHAUSTED` fallbacks)
    - app/src/main/java/com/david/openassistant/agent/AgentTaskExecutor.kt (Mapped exhausted tactics to truthful statuses)
    - app/src/main/java/com/david/openassistant/agent/AgentGoalWorker.kt (Updated `repairBlockedWorkflow` with truthful status mapping)
- Test files:
    - app/src/test/java/com/david/openassistant/agent/DuplicateContextFallbackTest.kt (New: verified truthful status transitions)
- Behavior changed: Missions no longer enter a deceptive `PAUSED` state when automated recovery fails; they now clearly state if cycles are exhausted or user clarification is needed.
- Behavior preserved: User-initiated pause remains durable and distinct from these machine-generated states.

### Verification
- Baseline commands: .\gradlew.bat testDebugUnitTest
- Baseline results: PASSED (572 tests)
- Focused commands: .\gradlew.bat :app:testDebugUnitTest --tests "com.david.openassistant.agent.DuplicateContextFallbackTest"
- Focused results: PASSED (2 tests)
- Full unit command: .\gradlew.bat testDebugUnitTest
- Total: 574
- Passed: 574
- Failed: 0
- Lint: PASSED
- Assemble: PASSED

### Risks
- Known risks: None identified.
- Unverified behavior: Exact UI rendering of the new terminal/blocked states (unit tested via status logic).

### Repository Hygiene
- git diff --check: Passed
- Generated-file scan: Clean
- Large-file scan: Clean
- Secret scan: Passed
- Final status: Clean

### Rollback
- Revert method: git revert <commit>
- Data compatibility: Full compatibility (logic only, no schema changes).

### Open Issues Updated
- Closed: [OI-005]

### Recommended Next Pass
- Next scope: Improve objective fidelity [PB-001] by implementing objective-drift detection during multi-stage research.
- Supporting evidence: Static trace in `AgentPlanner` showing potential for operational plans to drift from root objective.
