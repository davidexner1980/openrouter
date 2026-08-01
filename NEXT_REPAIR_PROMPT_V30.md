# OpenAssistant V30 — Refresh Ownership Closure and Buildable Clean-Handoff Certification

## Decision

Execute two strictly separated change sets.

```text
Change Set A:
Refresh worker ownership and runtime-proof closure

Change Set B:
Buildable, manifest-complete, deterministic source handoff
```

Do not begin conversation repair, FREE live routing, mission terminalization, Firebase expansion, or release work.

Change Set B source edits may begin only after Change Set A passes fresh automated gates.

The final clean-handoff certification may not pass until Change Set A also passes the emulator gate.

---

# Change Set A — Refresh ownership closure

## A1. Downgrade unsupported evidence first

Before editing, correct:

```text
CLAIM_EVIDENCE_LEDGER.json
CURRENT_DEFECT_CLUSTER.md
CURRENT_SYSTEM_STATE.md
task/walkthrough records
```

State plainly:

```text
the two test names are now discovered
the shutdown lost-wakeup interleaving is not yet proven
emulator evidence was not supplied
EMULATOR_RUNTIME_PASSED is unsupported
```

Do not erase the earlier overclaim.

---

## A2. Replace workerJob.isActive ownership with explicit mutex-owned state

The current race exists because request admission checks:

```text
workerJob?.isActive
```

while several worker exits do not atomically release ownership and recheck pending work.

Use one authoritative state guarded by one mutex, such as:

```text
workerRunning
pendingLatestRevision
lastProcessedRevision
```

Required request transition:

```text
under mutex:
  update pendingLatestRevision
  if workerRunning:
      record coalesced
      return
  workerRunning = true
  launch exactly one worker
```

Required worker stop transition:

```text
under the same mutex:
  if pendingLatestRevision > lastProcessedRevision:
      continue processing
  else:
      workerRunning = false
      clear worker reference
      return
```

Any request arriving after ownership is released must observe `workerRunning = false` and start a replacement worker.

Any request arriving before ownership is released must update pending work that the current worker rechecks.

Do not use a top-of-loop unprotected `break`.

Do not use a failure-path `break` that leaves a newer pending revision ownerless.

---

## A3. Define failure ownership semantics

For each failure class, specify what happens to:

```text
workerRunning
pendingLatestRevision
failedRevision
lastProcessedRevision
retry state
later requests
```

Required:

```text
CancellationException is rethrown after ownership cleanup.
Permanent failure does not hot-loop.
Transient retry is finite.
A newer revision arriving during retries is not silently lost.
A later explicit request can restart after failure.
No accepted pending revision is left without a documented failed or owned state.
```

Add one durable or inspectable failure snapshot if needed.

Do not pretend a failed revision was processed.

---

## A4. Remove the unchecked stable-read fallback

`AgentStore.loadStableSnapshot()` already returns an atomic snapshot/revision pair under `STORE_LOCK`.

Choose one coherent protocol.

Preferred:

```text
load atomic pair
if pair.revision >= requested target:
    process it
if pair.revision < requested target:
    retry a bounded number of times
after exhaustion:
    return TransientStableReadChurn
```

A newer atomic revision may satisfy an older requested target.

Do not perform several mismatched reads and then accept another result without validating it against the target.

Required tests:

```text
atomic pair at target succeeds
atomic pair newer than target succeeds
pair older than target retries
target never reached returns TransientStableReadChurn
unreached target is not marked processed
```

---

## A5. Propagate cancellation correctly

Do not convert `CancellationException` into an ordinary failure result.

Required:

```text
finally releases worker ownership
activeWorkers returns to zero
CancellationException is rethrown
pending state remains coherent
a later request in a live scope can restart
```

The test must:

```text
block the actual coordinator worker at a controlled suspension point
cancel the owning scope/job
prove cancellation reached the worker
assert cleanup
start a new live scope
prove later revision processes
```

Cancelling a wrapper that only called non-suspending `refresh()` is not sufficient.

---

## A6. Prove the actual shutdown interleaving

Create a deterministic state-machine test that forces:

```text
worker has completed its last revision
worker enters the ownership-release decision
new revision is requested before release completes
ownership transition completes
new revision is processed exactly once
```

Use controlled barriers or test a pure ownership reducer used by production.

The test must not merely request revision 2 while revision 1 is actively delivering.

Required assertions:

```text
lastProcessedRevision = 2
activeWorkers = 0
workerRunning = false
no pending ownerless revision
revision 2 emitted exactly once when content changed
```

---

## A7. Prove failure-shutdown interleaving

Force:

```text
revision 1 fails permanently or exhausts retries
revision 2 arrives while failure cleanup is occurring
```

Define and test whether revision 2:

```text
is processed by the current worker
starts a replacement worker
or is recorded as blocked by the same terminal failure
```

It may not disappear.

---

## A8. Add real listener-driven settlement proof

Use representative SharedPreferences listener behavior and the production store notification path.

Required sequence:

```text
revision 10 contains completed undelivered goal
refresh loads revision 10
delivery writes one message
delivery marks terminalResultDelivered
AgentStore advances to revision 11
KEY_REVISION listener requests revision 11
coordinator applies revision 11
system settles
```

Required assertions:

```text
one terminal message
one delivery transition
no duplicate message
listener called for revision 11
lastRequestedRevision = 11
lastProcessedRevision = 11
activeWorkers = 0
no further refresh without a new revision
```

---

## A9. Add real production action-state proof

The existing local Boolean test is not sufficient.

Create the narrowest production-representative test proving:

```text
background goal status changes
coordinator applies the snapshot through OpenAssistantViewModel's production state path
UI state changes
one real action gate or action method observes the same status
```

Cover at least one:

```text
Pause
Resume
Cancel
```

A pure action-eligibility function is acceptable only if production UI and action execution both use that function.

Do not add a test-only decision path.

---

## A10. Make metrics ownership-coherent

Metrics must reflect the authoritative worker state.

Required fields:

```text
requested
executed
coalesced
skipped
activeWorkers
workerRunning
pendingLatestRevision
lastRequestedRevision
lastProcessedRevision
failures
retryAttempts
retryExhaustions
stableReadRetries
stableReadFailures
uiEmissions
```

Do not allow a handoff where:

```text
workerRunning = false
activeWorkers > 0
```

unless the state is explicitly defined as ownership released but cleanup pending.

Prefer metrics that report logical ownership separately from coroutine cleanup.

---

## A11. Focused tests

The focused suite must contain and discover exact tests equivalent to:

```text
request during shutdown ownership transfer is processed
request during failure shutdown is not lost
CancellationException propagates and worker is restartable
target revision never reached is not processed
callback failure does not commit
listener-driven terminal delivery settles
real action gate uses refreshed ViewModel state
rapid revisions coalesce to latest
```

Inspect JUnit XML and record exact testcase names and SHA-256.

---

## A12. Automated gate

Run fresh:

```text
:app:testDebugUnitTest --tests "*AgentRefreshCoordinatorTest*"
:app:testDebugUnitTest --tests "*AgentStoreStabilityTest*"
:app:testDebugUnitTest
:app:lintDebug
:app:assembleDebug
```

Record:

```text
commands
times
exit codes
JUnit XML
tests discovered/passed/failed/errors/skipped
lint errors
lint warnings
APK path
APK size
APK SHA-256
source manifest SHA-256
```

Do not report “0 lint issues” when warnings exist.

---

## A13. Emulator acceptance

Run:

```text
launch app
idle on Research for two minutes
navigate all five bottom tabs repeatedly
open and close mission detail
trigger a real revision
verify visible state update
exercise listener-driven terminal delivery
verify refresh settles
```

Capture machine-readable:

```text
requested
executed
coalesced
skipped
activeWorkers
workerRunning
pending revision
last requested revision
last processed revision
failures
retry attempts
UI emissions
heap trend
GC frequency
frame delay
ANR/crash
```

Create actual evidence files.

Do not set `EMULATOR_RUNTIME_PASSED` without those files.

---

# Change Set B — Buildable clean handoff

## B1. Use one authoritative app version

The handoff currently says version code 33 while the app uses 53.

Create one source of truth used by:

```text
app defaultConfig
handoff filename
handoff README
evidence identity
```

Do not hardcode a second version.

Expected current identity:

```text
versionName = 1.8.33
versionCode = 53
```

Do not add a leading `v` unless it is only presentation and is clearly separated from `versionName`.

---

## B2. Stage generated metadata outside the repository root

Do not write generated files into the project root.

Use a staging directory such as:

```text
build/handoff/staging/OpenAssistant/
```

Generate inside staging:

```text
HANDOFF_README.md
HANDOFF_EXCLUSIONS.tsv
HANDOFF_MANIFEST_SHA256.txt
```

The task must leave the working source tree unchanged.

Add a test or before/after source-manifest comparison proving that handoff generation does not mutate retained source.

---

## B3. Include the complete Gradle wrapper

The generated ZIP must contain:

```text
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.properties
gradle/wrapper/gradle-wrapper.jar
```

Do not use a global JAR exclusion that removes the wrapper JAR.

Use a separate inclusion specification or a precise exclusion list.

The task must fail if any wrapper file is missing.

---

## B4. Manifest every included regular file

The manifest must include every regular archive file except the manifest itself.

Use:

```text
SHA-256
two spaces
normalized forward-slash relative path
```

Example:

```text
<sha256>  app/src/main/AndroidManifest.xml
```

Requirements:

```text
lexicographically sorted paths
no absolute paths
no Windows backslashes
no duplicate paths
no unmanifested included files
no manifest entries for missing files
```

Verify the manifest against the finished ZIP.

---

## B5. Required-file verification

Fail before publishing the ZIP if any required file is absent:

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
gradle/libs.versions.toml
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/david/openassistant/OpenAssistantViewModel.kt
app/src/main/java/com/david/openassistant/agent/AgentRefreshCoordinator.kt
```

Include `app/google-services.json` only as approved Android client configuration.

---

## B6. Scan the final staged set

Run secret and forbidden-file checks against every staged file that will enter the ZIP.

Allow:

```text
app/google-services.json
synthetic secret fixtures explicitly identified as test-only
```

Reject:

```text
service-account JSON
private keys
keystores
live OpenRouter keys
live bearer tokens
.env
secrets.properties
local.properties
APKs
DEX/classes
nested ZIPs
build caches
```

Do not print secret values.

Record only category and safe relative path.

---

## B7. Real exclusion ledger

Generate category totals:

```text
category
file count
byte count
rule
```

Required categories include:

```text
Gradle caches
Android build output
IDE state
machine-local configuration
compiled packages
historical evidence
nested archives
secret/signing material
```

Do not generate thousands of noisy per-file rows unless requested.

---

## B8. Determinism proof

Use:

```text
sorted archive entries
forward-slash paths
fixed timestamps
fixed permissions where supported
fixed compression configuration
no current time inside staged content
```

Add:

```text
verifyCleanSourceHandoff
```

It must generate two independent ZIPs from the same unchanged source and compare SHA-256.

Required evidence:

```text
first ZIP SHA-256
second ZIP SHA-256
match true/false
file count
maximum path length
manifest verification
required-file verification
secret scan
```

One existing ZIP is not determinism proof.

---

## B9. Keep the package focused

Include:

```text
source
tests
resources
Gradle wrapper and configuration
required tool source
current master/repair prompt
current execution-state records
small current evidence summaries
```

Exclude:

```text
app/build
root build caches
old duplicate prompts
old generated walkthrough copies
raw historical logs
copied APKs
nested handoffs
```

Use a short root folder inside the archive:

```text
OpenAssistant/
```

Maximum internal path:

```text
200 characters
```

---

## B10. User handoff instruction

The task must print clearly:

```text
SEND THIS FILE:
<absolute clean ZIP path>
```

The user should send the generated clean handoff ZIP directly.

Do not instruct the user to ZIP the entire Android Studio project folder again.

---

# Final verification states

Change Set A may reach:

```text
FULL_AUTOMATED_GATE_PASSED
EMULATOR_RUNTIME_PASSED
```

Change Set B may reach:

```text
VERIFIED_CLEAN_HANDOFF
```

Physical-device refresh verification and installed APK identity remain separate.

Do not call the complete application verified.

---

# Required final report

Status:

## Change Set A

Source manifest before:

Files changed:

Worker ownership design:

Shutdown interleaving result:

Failure-shutdown result:

Cancellation propagation:

Stable target-revision behavior:

Listener settlement:

ViewModel/action proof:

Focused testcase names:

Focused test totals:

Full test totals:

Lint errors/warnings:

APK SHA-256:

Emulator evidence paths:

Emulator outcome:

Claim-ledger corrections:

## Change Set B

Version identity:

Staging path:

Working-tree mutation check:

Required files:

Gradle wrapper result:

Manifest entries:

Manifest mismatches:

Unmanifested files:

Secret scan:

Exclusion totals:

First ZIP SHA-256:

Second ZIP SHA-256:

Determinism result:

Final ZIP path:

Final ZIP size:

Maximum internal path:

## Overall

Passed:

Failed:

Blocked:

Rollback:

Next gate:

State every failure plainly.

---

# Immediate instruction

Correct the evidence state first.

Repair Change Set A and prove the actual shutdown transition.

Run fresh automated and emulator gates.

Then repair Change Set B and generate a buildable, manifest-complete, deterministic source handoff.

Do not begin conversation repair until Change Set A reaches `EMULATOR_RUNTIME_PASSED`.

Afterward, send the generated clean ZIP itself.
