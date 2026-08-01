# Next Verification Gate — V30

## Target: EMULATOR_RUNTIME_PASSED

The stability defect cluster must pass the following sequence of gates:

### 1. CODE_CHANGED
- Replace `workerJob?.isActive` with mutex-guarded state machine.
- Implement structured failure ownership.
- Implement stable target-revision protocol.

### 2. FOCUSED_TEST_PASSED
- Run `AgentRefreshCoordinatorTest` and `AgentStoreStabilityTest`.
- Prove deterministic shutdown and failure interleaving.
- Prove listener-driven terminal settlement.
- Prove real ViewModel action path.

### 3. FULL_AUTOMATED_GATE_PASSED
- `clean`
- `:app:testDebugUnitTest`
- `:app:lintDebug`
- `:app:assembleDebug`

### 4. EMULATOR_RUNTIME_PASSED
- 2-minute idle stability on Research tab.
- Responsive bottom-tab navigation.
- Verified terminal delivery settlement.
- Verified Pause/Resume/Cancel state transition.
- Captured metrics (coalesced, skipped, active workers, heap trend, etc.).
