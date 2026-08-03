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
| [OI-001] | Illegal agent goal transition: RUNNING -> RECOVERING | Open | High |
| [OI-002] | planning_lease_rejected_without_planning_operation | Closed | High |
| [OI-003] | identical_context_pre_dispatch_suppressed | Open | Medium |
| [OI-004] | Repeated stale or stranded mission recovery without progress | Open | High |
| [OI-005] | Machine-generated duplicate context changing the mission to PAUSED | Open | Medium |
| [OI-006] | Watchdog automatically resuming a user-paused mission | Open | High |

---

## Run CE-20260803-0540-0d1c6785 — 2026-08-03T05:40:00

### Status
VERIFIED

### Repository
- Branch: main
- Starting commit: 0d1c67852b79c791e87fa4a98641d88e14da142d
- Final commit: (TBD after push)
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
