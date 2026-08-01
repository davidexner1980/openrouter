# OpenAssistant 1.8.33 — V29 Source and Handoff Review

## Status

`PARTIALLY_VERIFIED`

The uploaded archive is structurally valid. The current automated reports were inspected, and the two tests previously missed by JUnit are now discovered.

The refresh-stability claim and lean-handoff claim are not ready to be called verified.

---

## Uploaded archive

```text
File:
OpenAssistant_1.8.23(3).zip

Compressed size:
481,134,881 bytes

Entries:
11,614

Uncompressed size:
984,747,110 bytes

Maximum internal path:
262 characters
```

ZIP integrity passed.

Unsafe traversal paths: 0.

Approximately 976 MB of the uncompressed archive is generated build output and Gradle cache material.

Largest avoidable categories include:

```text
build/gradle_home/
app/build/
.gradle/
.idea/
```

---

## Automated evidence inspected

The included JUnit XML reports:

```text
489 tests
0 failures
0 errors
0 skipped
84 suites
```

`AgentRefreshCoordinatorTest` reports 10 discovered tests.

The previously missing names now appear:

```text
lost wakeup repair - request during worker shutdown is processed
action eligibility uses refreshed state
```

This corrects the prior test-discovery defect.

The included lint XML reports:

```text
0 errors
2 warnings
```

Warnings:

```text
newer Kotlin version available
newer kotlinx-coroutines-test version available
```

The included APK is:

```text
app/build/outputs/apk/debug/app-debug.apk

Size:
23,322,185 bytes

SHA-256:
F742EB2DB16324A1545064092674DF8B78759EEDB2AB2BDC123FD61ED172CFFF
```

These reports were inspected from the uploaded archive.

They were not rerun in the overseer environment because an Android SDK is not installed there.

---

## Finding 1 — the lost-wakeup race still exists

Current worker logic can exit at the top of the loop:

```kotlin
if (targetRevision <= processed && processed != -1L) {
    skippedCount.incrementAndGet()
    break
}
```

It can also exit after a failure.

Those exits do not perform the mutex-protected ownership release and pending-revision recheck used by the normal success path.

A remaining race is:

```text
worker decides there is no work and begins exiting
a new refresh request updates lastRequestedRevision
request observes workerJob.isActive == true
request is counted as coalesced and returns
old worker exits without rechecking
new revision remains pending without a worker
```

The same class of race can occur when a new request arrives while a failing worker is stopping.

The new “lost wakeup” test does not force this shutdown interleaving.

It blocks the worker during terminal-result delivery. The existing worker then reaches the ordinary bottom-of-loop check and processes revision 2. That proves coalescing during active work, not ownership transfer during shutdown.

---

## Finding 2 — cancellation is swallowed and the test does not cancel an in-flight worker

`performRefreshWithRetry()` catches `CancellationException` and converts it into:

```text
RefreshFailure.Cancelled
```

It does not rethrow cancellation as required by structured concurrency.

The cancellation test launches the non-suspending `refresh()` call inside a wrapper coroutine and cancels that wrapper. It does not establish that the coordinator worker is suspended in-flight when cancellation occurs.

Required proof remains missing:

```text
worker is known to be active
owning scope is cancelled
CancellationException propagates
active worker returns to zero
ownership is cleared
a later scope can restart the coordinator
```

---

## Finding 3 — stable-read churn is not handled as claimed

After three revision mismatches, `loadStableSnapshotWithRetry()` increments the failure counter and then returns another snapshot:

```kotlin
return refreshSource.loadStableSnapshot()
```

It does not return or throw:

```text
TransientStableReadChurn
```

The test only verifies that at least one retry occurred.

It does not prove:

```text
a stale revision is not marked processed
retry exhaustion is classified
the worker remains restartable
```

The walkthrough says churn is handled as a transient failure, but the inspected implementation does not do that.

Because `AgentStore.loadStableSnapshot()` already returns an atomic snapshot/revision pair under `STORE_LOCK`, the coordinator should use that contract directly or explicitly enforce the target-revision rule without an unchecked fallback.

---

## Finding 4 — the “action eligibility” test is not a ViewModel/action integration test

The discovered test records a goal status in a local variable and calculates two local Booleans.

It does not:

```text
construct OpenAssistantViewModel
invoke Pause
invoke Resume
invoke Cancel
exercise the production action-gating path
```

The source now updates `agentSnapshot` and UI state through `RefreshStateApplier`, which is a real improvement.

Production action consistency remains unproven.

---

## Finding 5 — terminal-delivery test does not exercise listener re-entry

The terminal-delivery test updates a fake snapshot and revision and returns `true`.

It proves that the coordinator performs a final reload after delivery.

It does not invoke:

```text
SharedPreferences listener callback
AgentStore revision notification
ViewModel refresh request
```

Therefore it does not prove the real listener-driven revision 10 to revision 11 settlement cycle.

---

## Finding 6 — emulator proof is missing and project records contradict one another

The walkthrough and claim ledger say:

```text
EMULATOR_RUNTIME_PASSED
```

The task artifact leaves the emulator gate unchecked.

No emulator evidence exists under the supplied evidence directory for:

```text
two-minute idle
tab responsiveness
refresh metrics
heap trend
GC frequency
frame delay
terminal-result settlement
```

`CURRENT_DEFECT_CLUSTER.md` and `CURRENT_SYSTEM_STATE.md` still describe the stability target as failed or reproduced.

The records therefore disagree.

The refresh claim must not remain at `EMULATOR_RUNTIME_PASSED` without the missing runtime files.

---

## Finding 7 — Change Set B was executed before its gate was proven

The plan required Change Set B to wait until Change Set A passed automated and emulator gates.

A clean-handoff Gradle task was implemented even though emulator evidence is absent.

This is a process violation.

It does not automatically invalidate the source change, but it must be recorded honestly.

---

## Finding 8 — generated clean handoff is not build-complete

The generated file is:

```text
build/handoff/OpenAssistant-source-v1.8.33-33.zip
```

The actual application identity is:

```text
versionName = 1.8.33
versionCode = 53
```

The task hardcodes:

```text
versionName = v1.8.33
versionCode = 33
```

The file name and README are therefore wrong.

More importantly, the task excludes:

```text
**/*.jar
```

and then attempts to re-include the wrapper JAR in the same copy specification.

The generated ZIP does not contain:

```text
gradle/wrapper/gradle-wrapper.jar
```

The handoff cannot use the Gradle wrapper as supplied.

---

## Finding 9 — handoff manifest is incomplete and platform-specific

The generated manifest has 256 entries but covers only a subset of the archive.

It uses Windows backslashes:

```text
app\src\main\...
```

The ZIP uses normalized forward-slash paths:

```text
app/src/main/...
```

A platform-neutral verifier cannot resolve most entries directly.

The ZIP also contains hundreds of included files that are not represented in the manifest, including:

```text
tools source
evidence
docs
google-services.json
wrapper properties
metadata
```

The manifest must cover every included regular file except the manifest itself.

Use normalized forward-slash paths.

Verify the manifest against the finished archive.

---

## Finding 10 — required-file and determinism proof are missing

The task did not fail when the wrapper JAR was absent.

No required-file verification is implemented for the final ZIP.

Only one generated clean ZIP is included.

The walkthrough says two runs produced matching SHA-256 values, but no two-run hash record was supplied.

The Gradle ZIP settings normalize order and timestamps, which is promising, but deterministic output was not evidenced.

---

## Finding 11 — secret scanning is incomplete

The scan examines:

```text
app/src
selected build scripts
```

The generated handoff also includes:

```text
tools
evidence
docs
metadata
google-services.json
```

Those included inputs are not all scanned.

`app/google-services.json` is valid Android client configuration and should remain allowed.

The final staged file set must be scanned before ZIP creation.

One OpenRouter-shaped token exists in a security-scanner unit test and appears to be synthetic test data. It must not be mistaken for a live key.

---

## Finding 12 — the handoff task modifies the working project root

The task writes:

```text
HANDOFF_README.md
HANDOFF_EXCLUSIONS.tsv
HANDOFF_MANIFEST_SHA256.txt
```

into the repository root during execution.

Generated handoff metadata should be created in a temporary staging directory under:

```text
build/handoff/
```

It should not mutate the working source tree.

---

## Finding 13 — exclusion reporting is not an actual ledger

`HANDOFF_EXCLUSIONS.tsv` contains only four generic rows.

It does not record:

```text
excluded category counts
excluded byte totals
actual verification rules
whether historical evidence was omitted
whether nested archives were rejected
```

A compact category summary is sufficient, but it must describe the real generated package.

---

## Positive progress

The following improvements are present:

```text
the two missing tests are now discovered
tool-count file work uses Dispatchers.IO
state is committed only after RefreshStateApplier returns Success
ViewModel apply() updates agentSnapshot and UI state together
AgentStore supplies an atomic snapshot/revision pair under STORE_LOCK
Kotlin declarations are aligned to 2.3.21
JUnit reports 489 passing tests
lint reports zero errors
```

---

## Verification status

### Passed

```text
outer ZIP integrity
unsafe-path inspection
required source presence
JUnit XML parsing
exact refresh testcase discovery
lint XML parsing
APK hashing
generated handoff ZIP integrity
normalized ZIP timestamps
basic source secret-pattern scan
```

### Failed or unverified

```text
actual lost-wakeup shutdown interleaving
structured cancellation propagation
stable-read churn failure classification
real ViewModel action integration
real SharedPreferences listener settlement
emulator runtime stability
physical-device stability
installed APK identity
clean handoff Gradle wrapper completeness
complete manifest verification
two-run deterministic hash proof
complete included-file secret scan
```

---

## Required next action

Do not begin conversation repair.

Repair and prove:

```text
atomic worker ownership release
shutdown recheck
failure-stop ownership
real cancellation
target-revision stable reads
real ViewModel action behavior
listener-driven terminal settlement
emulator runtime acceptance
```

Then repair the handoff task:

```text
derive versionName/versionCode from one authoritative source
include gradle-wrapper.jar
stage metadata outside the repository root
manifest every included file with forward-slash paths
verify required files
scan the final staged set
run two deterministic exports and compare hashes
```

After the task is corrected, send the generated clean handoff ZIP itself rather than ZIPing the entire Android Studio project directory.
