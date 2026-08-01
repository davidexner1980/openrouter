# OpenAssistant V27 — Source-Aware Master Repair, Architecture Cleanup, and Runtime Certification Prompt

## Use

Open the real OpenAssistant project in Android Studio and give this complete prompt to the coding agent.

This is an execution contract.

Do not return only a plan.

Do not claim completion from source inspection, checked task boxes, compilation, or unit tests alone.

Inspect the actual current source, repair one bounded defect cluster at a time, verify each cluster with fresh evidence, and report every failure plainly.

---

# 1. Verified handoff identity

The current clean source handoff inspected by the overseer is:

```text
OpenAssistant_1.8.23_CLEAN_SOURCE_HANDOFF.zip
SHA-256:
808fdc72c4b866bfafa102306fe8e213489bfb5a89c27b3da6094e3ed8f3624f
```

The clean package contained approximately:

```text
706 project files
157 app/src/main files
85 app/src/test files
```

Do not assume the local project still matches that ZIP.

Before editing, calculate a new source manifest and record differences.

Known current project facts from the inspected source:

```text
package/applicationId: com.david.openassistant
versionName: 1.8.33
versionCode: 53
compileSdk: 37
targetSdk: 37
minSdk: 26
Java: 17
Kotlin: 2.3.21
AGP: 9.3.1
primary UI: Jetpack Compose
background work: WorkManager
network: OkHttp
modules:
  :app
  :tools:overseer-handoff
```

Treat these as source observations, not runtime proof.

---

# 2. Governing law

Truth beats appearance.

Runtime proof beats documentation.

Fresh command output beats historical summaries.

The installed APK must be cryptographically tied to the verified source.

A model response is not evidence.

A URL is not evidence.

HTTP 200 is not automatically a substantive source.

A successful build is not proof that the app works.

A passing unit test is not proof that the physical-phone workflow works.

A class, enum, button, or screen is not proof that the underlying feature is connected.

Never hide a failure in general success language.

Use these evidence states:

```text
DISCOVERED
CONFIRMED_IN_SOURCE
REPRODUCED
CHANGE_PROPOSED
CODE_CHANGED
COMPILES
FOCUSED_TEST_PASSED
FULL_AUTOMATED_GATE_PASSED
EMULATOR_RUNTIME_PASSED
PHYSICAL_RUNTIME_PASSED
ADVERSARIAL_REVIEW_PASSED
VERIFIED
FAILED
BLOCKED
NEEDS_HUMAN_REVIEW
OBSOLETE
```

Do not skip evidence states.

---

# 3. Product identity

OpenAssistant is a native Android autonomous research assistant.

It must provide:

```text
normal multi-turn pre-research conversation
research-request refinement
one-action Start Deep Research
durable autonomous missions
multi-site web research
source validation
evidence and claim tracking
contradiction analysis
bounded rabbit-hole investigation
honest synthesis
process-death recovery
model/profile routing
standalone report export to Download/OpenAssistant
runtime monitoring and Overseer evidence
```

It is not merely:

```text
a chatbot wrapper
a one-search answer generator
a fake autonomous-agent demonstration
a collection of disconnected screens
```

The user supplies one required credential:

```text
OpenRouter API key
```

Do not add required credentials for other search/model services.

Do not add Tor.

Do not require ADB, email, USB, a batch file, or a computer connection for normal phone use.

---

# 4. Primary interface contract

The bottom navigation must contain:

```text
Research
Missions
Archive
Models
Settings
```

## Research

Must support:

```text
normal conversation before research
multi-turn context
message persistence
assistant responses
Markdown
safe HTTPS links
image attachment where supported
model/profile selection
Send
Start Deep Research
loading and retry states
```

Normal Send must not create a mission.

Start Deep Research must create exactly one durable mission.

## Missions

Must show truthful:

```text
mission status
active worker state
current task
progress
accepted evidence
rejected reads
claims
contradictions
provider/tool activity
cost/tokens
final result
report access
```

Controls must match authoritative state.

Do not show Pause when nothing is active.

## Archive

Must coherently expose retained conversations, completed results, and reports without silently deleting public Downloads files.

## Models

Must display supported OpenRouter models and profiles.

FREE means free-only.

## Settings

Must include:

```text
OpenRouter credential state
routing preferences
storage/cleanup
Deep Research Recorder
Snapshot
Stop & Report
Open
Share
runtime packet
version/build identity
```

---

# 5. Immediate P0 source-specific defect

The inspected source contains a concrete correctness concern in:

```text
app/src/main/java/com/david/openassistant/OpenAssistantViewModel.kt
private fun refreshAgentSnapshot()
```

The current sequence assigns the newly loaded snapshot before capturing the old snapshot:

```kotlin
val loaded = agentInteractor.loadSnapshot()
...
agentSnapshot = loaded
...
.onSuccess { (snapshot, ...) ->
    val oldSnapshot = agentSnapshot
    agentSnapshot = snapshot

    if (snapshot != oldSnapshot || ...) {
        emitUiState()
    }
}
```

This means `oldSnapshot` may already be the newly loaded value.

A real state change can therefore be mistaken for an unchanged snapshot and its UI emission suppressed.

Do not blindly apply a text replacement.

Trace the full ownership path and prove the correct fix.

The correct comparison must preserve:

```text
snapshot before refresh
snapshot loaded from store
snapshot after pending-result delivery
revision before and after delivery writes
UI emission decision
```

The repair must not reintroduce the prior refresh/GC loop.

---

# 6. Related refresh-loop risks requiring inspection

Inspect:

```text
OpenAssistantViewModel.agentPreferenceListener
OpenAssistantViewModel.refreshAgentSnapshot
OpenAssistantViewModel.deliverPendingAgentResults
OpenAssistantViewModel.appendMessageToConversation
AgentStore.getLatestRevision
AgentStore.writeSelectionAndSignalLocked
AgentStore.updateGoal
AgentInteractor.loadSnapshot
AgentInteractor.updateGoal
listener registration and unregister
```

Potential feedback path to prove or reject:

```text
AgentStore write
-> KEY_REVISION listener
-> refreshAgentSnapshot
-> loadSnapshot
-> deliverPendingAgentResults
-> updateGoal(terminalResultDelivered = true)
-> revision write
-> listener
-> another refresh
```

This sequence can be legitimate once.

It must not loop indefinitely.

Required invariant:

```text
one real durable change
-> one revision advance
-> at most one required refresh cycle
-> one pending terminal-result delivery
-> one terminalResultDelivered write
-> final stable snapshot
-> no further refresh without a new revision
```

Do not solve the problem only with:

```text
delay
cooldown
permanent listener disable
log suppression
ignoring all self-originated writes
```

---

# 7. Existing test gap

The inspected test:

```text
app/src/test/java/com/david/openassistant/agent/AgentStoreStabilityTest.kt
```

currently verifies only basic revision/read/write counters.

Its `FakeSharedPreferences` listener registration methods are no-ops:

```kotlin
registerOnSharedPreferenceChangeListener(...) {}
unregisterOnSharedPreferenceChangeListener(...) {}
```

Therefore it does not exercise the real listener feedback cycle.

Do not treat that test as proof of refresh stability.

Add representative tests that actually notify registered listeners when committed keys change.

Prefer extracting a small testable refresh coordinator rather than constructing the entire ViewModel if that produces a cleaner, truthful boundary.

Do not create a fake coordinator used only by tests.

Production must call the tested component.

---

# 8. Phase 0 — source freeze and baseline

Before editing:

```text
record project root
record Git status when available
record source manifest SHA-256
record versionName/versionCode
record toolchain versions
record connected devices and AVDs
record modified files
record relevant existing evidence
```

Create:

```text
evidence/v27/source-before.sha256
evidence/v27/environment.json
evidence/v27/modified-files-before.txt
```

Run fresh:

```text
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

Read actual report files.

Record:

```text
tests discovered
passed
failed
errors
skipped
lint errors
lint warnings
build exit
APK path
APK size
APK SHA-256
```

Do not reuse historical totals.

Do not edit production source if the project cannot compile until the baseline failure is understood.

---

# 9. Phase 1 — reproduce and measure runtime instability

On the emulator:

```text
launch app
idle on Research for two minutes
navigate all bottom tabs
open and close a mission
return to Research
send one short message only if responsive
do not start deep research
```

Measure:

```text
store reads
store writes
KEY_REVISION listener callbacks
refresh requests
refresh executions
refresh coalesced
refresh skipped
active refresh jobs
UI-state emissions
store revision
snapshot fingerprints
heap trend
GC frequency
frame delay
ANR/crash
```

Create:

```text
evidence/v27/runtime/refresh-before.json
evidence/v27/runtime/gc-before.txt
evidence/v27/runtime/responsiveness-before.md
```

If the issue cannot be reproduced, do not make speculative changes.

Report the reproduction as blocked or unverified.

---

# 10. Phase 2 — repair refresh ownership

The final design must provide:

```text
one authoritative AgentStore revision
one causal store-change notification
single-flight refresh
latest revision wins
unchanged revision skipped
unchanged final snapshot suppresses UI emission
real changed snapshot emits UI state
read-only refresh performs no equivalent durable write
listener registers once
listener unregisters once
active refresh jobs remain bounded
```

The UI comparison must use the true pre-refresh snapshot.

A valid conceptual sequence is:

```text
previousSnapshot = agentSnapshot
loadedSnapshot = loadSnapshot()
deliveryResult = deliver pending terminal results using loadedSnapshot
finalSnapshot = reload only if delivery wrote durable state
finalRevision = read revision
compare finalSnapshot with previousSnapshot
assign agentSnapshot = finalSnapshot
emit UI only when meaningful state changed
mark finalRevision processed
```

Do not copy this mechanically if it conflicts with real concurrency.

Prove ordering and races.

Consider that listener callbacks can occur during terminal-result delivery.

The design must not mark a future revision processed before its data has been loaded.

Use explicit local revision variables.

Avoid shadowing `currentStoreRevision`.

---

# 11. Phase 3 — deterministic refresh tests

Required tests:

```text
changed store snapshot emits UI state
unchanged store snapshot does not emit
oldSnapshot is captured before loaded snapshot assignment
one revision triggers one refresh
identical revision triggers no refresh
rapid revisions coalesce to latest
revision created during delivery is loaded before marking processed
terminal result is delivered once
terminalResultDelivered write produces no endless cycle
listener registers once
listener unregisters once
refresh cancellation leaves consistent state
process recreation restores state without loop
corrupt mission remains quarantined
```

The SharedPreferences fake must:

```text
store registered listeners
notify them for changed keys on commit/apply
not notify for unchanged values unless Android behavior requires it
support unregister
```

Use controlled coroutine dispatchers.

Do not use arbitrary sleeps.

---

# 12. Phase 4 — runtime acceptance after repair

Repeat the same emulator scenario.

Acceptance requires:

```text
two-minute idle remains responsive
all tabs respond promptly
mission detail opens and closes
one short chat does not create refresh storm
real store change appears in UI
unchanged refresh does not emit
terminal-result delivery settles
active refresh jobs remain bounded
heap trend stabilizes
GC frequency becomes reasonable
no ANR
no crash
```

Create:

```text
evidence/v27/runtime/refresh-after.json
evidence/v27/runtime/gc-after.txt
evidence/v27/runtime/responsiveness-after.md
```

Report before/after values.

Subjective “looks better” is not enough.

---

# 13. Phase 5 — real pre-research conversation repair

Only begin after runtime stability reaches:

```text
EMULATOR_RUNTIME_PASSED
```

Trace:

```text
Send action
-> persist user message
-> internal safety classification
-> allowed/blocked decision
-> normal chat provider dispatch
-> streaming/parser
-> persist assistant response
-> render assistant response
```

The visible regression:

```text
User Safety: safe
```

must never appear as assistant content.

Do not merely filter it from the UI.

For an allowed request:

```text
classification remains internal
normal provider call occurs
real assistant answer persists
loading clears
no mission is created
```

For a blocked request:

```text
show user-facing safety response
skip normal provider
do not expose classifier JSON or labels
```

Required two-turn test:

```text
Turn 1:
I want to research dark matter, but first help me decide which observational evidence and competing theories should be included.

Turn 2:
Also help me develop an original theory, but keep speculation clearly separated from established science.
```

Before Start Deep Research:

```text
two useful assistant responses
dark-matter context retained
AgentGoal count unchanged
no WorkManager research mission
```

---

# 14. Phase 6 — one-action mission launch

Press Start Deep Research once.

Required:

```text
complete multi-turn request resolved
one stable submission identity
one durable AgentGoal
one scheduling action
no duplicate mission
mission visible in Missions
```

Test:

```text
double tap
process death before goal persist
process death after goal persist
callback replay
credential unavailable
restart
```

Missing credential must produce:

```text
WAITING_FOR_CREDENTIAL
```

without losing mission identity.

---

# 15. Phase 7 — FREE routing

The final HTTP dispatch boundary must enforce:

```text
profile FREE
-> freeOnly true
-> no openrouter/auto-beta
-> no paid direct model
-> no paid fallback
-> no ESCALATE_TO_PAID
```

Record for every provider request:

```text
requested profile
original model
guarded final model
caller
allowed/rejected
operation ID
```

Cover:

```text
chat
planning
schema repair
execution
query generation
gap closure
verification
synthesis
recovery
```

A selector unit test is not runtime proof.

Run one focused small FREE mission.

---

# 16. Phase 8 — mission reconciliation

Repair impossible stranded states such as:

```text
goal QUEUED
zero active workers
no valid lease
research task failed/exhausted
synthesis queued
no future WorkManager execution
```

Reconcile from:

```text
goal state
task state
lease
WorkManager state
no-progress state
available evidence
credential/network state
```

Choose one truthful outcome:

```text
resume
schedule bounded synthesis
WAITING_FOR_NETWORK
WAITING_FOR_CREDENTIAL
REQUIRES_USER_CLARIFICATION
COMPLETED_WITH_QUALIFICATIONS
BLOCKED_WITH_PARTIAL_EVIDENCE
INSUFFICIENT_CURRENT_DATA
CORRUPT_OR_INCOMPLETE_MISSION
```

UI controls must match the result.

---

# 17. Phase 9 — research integrity

Every search must pass one canonical validator.

Reject:

```text
subject drift
prompt prose
generic instruction fragments
weak anchors
duplicate paraphrase strategies
```

Do not repeat the same blocked PDF route.

A fetch becomes evidence only through:

```text
FetchedResponse
-> SourceRead
-> SourceValidation
-> EvidenceCandidate
-> AcceptedEvidence
-> Claim
```

Reject:

```text
challenge pages
semantic 404
login pages
navigation-only fragments
wrong entities
unsupported binaries
```

Do not inject fabricated markers into source text.

Original theories must remain:

```text
ORIGINAL_HYPOTHESIS
speculative
not established science
```

---

# 18. Phase 10 — convergence and cost

Material progress requires at least one:

```text
new accepted authoritative source
new supported claim
resolved criterion
resolved contradiction
closed useful branch
```

These are not progress:

```text
more tokens
same source
same query paraphrase
same unsupported claim
another blocked PDF
equivalent checkpoint
```

Stop exhausted branches and produce honest limited synthesis.

Cost authority must use:

```text
totalCostUsdMicros: Long
```

Ensure exact-once accounting across retries and restart.

---

# 19. Phase 11 — recorder, JSONL, and Downloads

Preserve working behavior:

```text
Snapshot -> Download/OpenAssistant and recorder remains active
Stop & Report -> final report and recorder becomes idle
Open
Share
visible file metadata
hash verification
```

Every runtime-events JSONL physical line must be one valid JSON object.

Required verifier output:

```text
total lines
parsed lines
malformed lines
duplicate event IDs
schema failures
```

Acceptance:

```text
malformed lines = 0
duplicate event IDs = 0
schema failures = 0
```

Separate:

```text
monitor finalized
provider settled
tool settled
mission terminal
packet integrity
```

Do not report one ambiguous FINAL_SETTLED state.

---

# 20. Firebase and Crashlytics review

The inspected project currently applies:

```text
com.google.gms.google-services
com.google.firebase.crashlytics
```

and includes:

```text
implementation(firebase-crashlytics)
app/google-services.json
```

This means Crashlytics infrastructure is already present in source.

Do not assume its privacy and collection behavior is acceptable.

Before keeping it enabled, document:

```text
exact dependency/plugin versions
automatic collection behavior
debug behavior
release behavior
data collected
data excluded
privacy disclosure
user consent requirement
mapping-file behavior
rollback
```

Search the merged manifest and generated resources for effective collection settings.

Crashlytics must never receive:

```text
OpenRouter keys
Authorization headers
raw prompts
conversation bodies
research reports
source excerpts
hidden provider reasoning
private file paths
raw device serials
```

Do not add Analytics implicitly.

If Crashlytics is not yet approved, disable automatic collection or remove the integration through a separate reviewed change set while preserving core app functionality.

Do not make Firebase a runtime requirement for chat, research, storage, or reports.

---

# 21. Professional code structure

After functional gates pass, clean only touched or proven-problem areas.

Required:

```text
one authoritative owner per state
typed states
small cohesive functions
immutable public state
structured concurrency
explicit failures
no business logic in Compose
no blocking I/O on Main
no GlobalScope
no empty catch
no duplicate mutable facts
bounded histories and retries
meaningful names
```

Do not do a repository-wide cosmetic rewrite.

Large source files are a review signal, not automatic permission to split them.

Known large files include:

```text
OpenAssistantViewModel.kt
AgentStore.kt
AgentOpenRouterClient.kt
AgentTaskExecutor.kt
```

Split only when:

```text
ownership becomes clearer
a real test boundary is created
behavior remains compatible
migration is safe
focused tests pass
```

---

# 22. Security and privacy

Audit:

```text
permissions
exported components
FileProvider
intents
network security
backup rules
credential storage
public exports
Firebase
logs
monitor redaction
```

Never expose:

```text
secrets
tokens
credentials
private keys
service-account JSON
signing keystores
private customer data
```

`google-services.json` is client configuration.

Do not confuse it with a Firebase service-account credential.

Do not include service-account JSON or signing material in handoffs.

---

# 23. Full verification gates

After each defect cluster:

```text
focused tests
compile
applicable integration test
runtime smoke test
evidence update
rollback record
```

After all source changes stop:

```text
clean
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
:app:connectedDebugAndroidTest when available
secret scan
dependency/license review
```

Report actual counts.

No unexplained skipped tests.

No weakened tests.

No deleted regression tests.

---

# 24. Exact APK identity

For final certification:

```text
hash source manifest
build APK
hash generated APK
install exact APK
resolve installed APK or split paths
hash installed bytes
compare
record version/package/build
start fresh monitor session
verify first event contains same identity
```

Do not rebuild between hash and install.

A physical launch is only a smoke test until identity matches.

---

# 25. Physical Samsung acceptance

Use the physical Samsung device.

Required:

```text
startup/restoration
all navigation tabs
two-turn pre-research conversation
image picker
Start Deep Research once
mission detail
FREE focused mission
network interruption/recovery
Snapshot disconnected from computer
open snapshot in My Files
Stop & Report
open final report
Share
force close and restore
runtime packet
```

Standalone export must work while disconnected from the computer.

Emulator results do not replace this gate.

---

# 26. Evidence structure

Create real evidence only:

```text
evidence/v27/
  source/
  baseline/
  runtime/
  refresh/
  conversation/
  mission-start/
  routing/
  mission-reconciliation/
  query-integrity/
  sources/
  claims/
  convergence/
  cost/
  recorder/
  runtime-packet/
  downloads/
  security/
  firebase/
  accessibility/
  performance/
  apk-identity/
  physical-device/
  rollback/
  final-review/
```

Every command record must include:

```text
command
start time
finish time
exit code
stdout
stderr
artifact hashes
```

Do not fabricate placeholder evidence.

---

# 27. Claim ledger

Maintain:

```text
evidence/execution/CLAIM_EVIDENCE_LEDGER.json
```

Every claim must include:

```text
claim ID
requirement IDs
state
description
current truth
source files
source hashes
tests
command evidence
runtime evidence
APK identity
next gate
```

Do not use `PARTIALLY_VERIFIED` as a claim-transition state.

Use the explicit evidence state machine.

Overall project status may be partially verified, but each claim must say exactly which gate it reached.

---

# 28. Adversarial review

Before final acceptance, try to disprove:

```text
refresh loop is gone
changed snapshots always reach UI
safety metadata cannot become assistant content
FREE cannot reach paid routing
one Start tap cannot create duplicates
cost cannot double after restart
exhausted work cannot reopen
blocked PDF cannot repeat
malformed JSONL cannot pass
hash mismatch cannot report export success
public cleanup cannot delete reports
hypothesis cannot become established fact
report cannot mutate after hashing
```

Record actual outcomes.

---

# 29. Stop and human-review boundaries

Stop for human review before:

```text
destructive migration
deleting user data
changing credential storage
adding permissions
enabling telemetry
changing FREE to paid-capable behavior
release signing
Play publication
large architectural replacement
```

Continue safe inspection and testing.

Do not ask for approval for ordinary reversible code repair.

---

# 30. Required final report

Status: verified | partially verified | unverified | blocked | needs human review

Summary:

Source identity before:

Source identity after:

Files inspected:

Files changed:

Risk level:

Root causes:

Refresh defect:

Refresh tests:

Runtime responsiveness:

Conversation:

Safety gate:

Mission launch:

FREE routing:

Mission reconciliation:

Query integrity:

Source validation:

Claims:

Convergence:

Cost accounting:

Recorder:

Runtime JSONL:

Downloads:

Firebase/Crashlytics:

Security:

Accessibility:

Performance:

Dependencies:

Commands run:

Tests discovered/passed/failed/errors/skipped:

Lint errors/warnings:

Build:

Generated APK SHA-256:

Installed APK SHA-256:

Emulator scenarios:

Physical-device scenarios:

Passed:

Failed:

Evidence/artifacts:

Rollback plan:

Remaining issues:

Next required gate:

State every failure plainly.

---

# 31. Immediate execution instruction

Begin with the actual current source.

Do not begin another broad rewrite.

First:

```text
1. freeze the source
2. run fresh baseline gates
3. reproduce the refresh/GC problem
4. prove the causal path
5. repair the oldSnapshot/newSnapshot ordering and any verified feedback-loop cause
6. add real listener-cycle and UI-emission tests
7. run full automated gates
8. prove emulator responsiveness
```

Stop after the runtime-stability cluster.

Do not proceed to conversation work until stability reaches:

```text
EMULATOR_RUNTIME_PASSED
```

If it fails, report the failure and preserve the evidence.
