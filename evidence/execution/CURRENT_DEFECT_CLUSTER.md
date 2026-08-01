# Current Defect Cluster: Runtime Stability — V25 Corrected

## Cluster identity
- **Cluster ID:** `CLUS-P1-001`
- **Priority:** `P0`
- **Current state:** `REPRODUCED`
- **Target claim:** The application remains responsive and refresh behavior is stable.
- **Current truth:** The target claim is currently `FAILED`. Prior claim of stability was unsupported.

## Requirements addressed
- `REQ-P1`: Application responsiveness and refresh-loop stability.
- `REQ-Section-118`: Monotonic revision and single-flight refresh invariant.
- `REQ-Section-119`: Root-cause repair rather than symptom masking.
- `REQ-Section-131`: Focused acceptance before another deep mission.

## Reported reproduction
1. Launch the application on the emulator.
2. Observe repeated GC and snapshot-refresh activity while idle.
3. Bottom-navigation taps become delayed.
4. UI responsiveness degrades.
5. **V30 Discovery**: Worker ownership check `workerJob?.isActive` contains a race during shutdown interleaving.

## Evidence status
The project references `Logcat-GC-Thrashing.txt`, but that log was not included in this upload. The defect is therefore locally reproduced but not independently rechecked in this handoff.

## Suspected source owners — not yet proven
- `OpenAssistantViewModel.kt`
- `AgentStore.kt`
- SharedPreferences or store-change listeners
- snapshot refresh scheduling
- mission/conversation reconciliation
- UI-state delivery

## Required root-cause statement
Identify:
```text
authoritative state
write origin
notification source
refresh trigger
read path
equivalent write-back, if any
why another notification occurs
why the cycle does not terminate
```

## Required measurements before editing
```text
store read count
store write count
listener callback count
refresh requested count
refresh executed count
refresh coalesced count
refresh skipped count
UI-state emission count
active refresh-job count
store revision
snapshot fingerprint
main-thread frame delay
heap trend
GC frequency
```

## Production invariants
```text
one authoritative monotonic store revision
one causal change notification
single-flight refresh
latest revision wins
unchanged snapshot does not emit new UI state
read-only refresh performs no equivalent write
listener registers once
listener unregisters once
no recursive refresh from its own delivery
```

## Forbidden final fixes
Do not use only:
```text
arbitrary delay
cooldown
permanent listener disable
ignored callbacks without revision tracking
suppressed GC logs
```
A debounce may remain only as secondary protection after the causal loop is fixed.

## Expected minimal change scope
```text
OpenAssistantViewModel.kt
AgentStore.kt
direct refresh/listener tests
runtime diagnostic counters
```
Unexpected edits outside this cluster require explanation or rollback.

## Focused regression tests
```text
one new store revision causes one refresh
identical revision causes no refresh
identical snapshot causes no UI emission
newer revision supersedes stale work
listener registers once
listener unregisters once
read-only refresh causes no store write
self-originated derived metadata does not trigger full reload
rapid updates coalesce to the newest revision
refresh cancellation preserves durable state
```

## Runtime acceptance
The cluster passes only when:
```text
app remains responsive while idle for at least two minutes
all bottom-navigation tabs respond normally
opening a long mission does not start an endless refresh cycle
sending a short chat message does not create a refresh storm
active refresh jobs remain bounded
heap trend stabilizes
GC frequency returns to a reasonable steady state
```

## Required evidence
```text
evidence/runtime-v25/refresh-loop-before.json
evidence/runtime-v25/refresh-loop-after.json
evidence/runtime-v25/gc-before.txt
evidence/runtime-v25/gc-after.txt
evidence/runtime-v25/focused-test-summary.json
evidence/runtime-v25/runtime-responsiveness.md
```

## Stop condition
Do not proceed to the conversation defect cluster until this cluster is `EMULATOR_RUNTIME_PASSED`, or honestly marked `BLOCKED`, `FAILED`, or `NEEDS_HUMAN_REVIEW`.
