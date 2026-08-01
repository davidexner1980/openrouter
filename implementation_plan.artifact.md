# Pass 4 Implementation Plan: Location Privacy Containment (Narrow Repair)

Implement a narrow privacy containment to eliminate proactive permission requests and disable direct coordinate access, ensuring zero-provider-call integrity.

## Architecture Feasibility Conclusion
> [!CAUTION]
> **BLOCKED:** Exact-invocation resumption is NOT supported by the current architecture. Tool-call IDs are transient, generated per-request by OpenRouter, and not persisted in a rehydratable format. Resuming an interrupted mission reruns the entire model turn, which generates new tool-call IDs.
>
> Binding human consent to a specific tool-call ID across process restarts or model turns is therefore impossible without major data-model changes.
>
> **Decision:** Implement narrow containment as requested. Defer contextual location support.

## Objective
Remove all location-related proactive prompting and disable direct device-location provider access, replacing it with a structured, recoverable "unavailable" result.

---

## 1. Proposed Changes

### [Component] Android Manifest
#### [MODIFY] [AndroidManifest.xml](file:///D:/my_programs/OpenAssistant_1.8.23/OpenAssistant_1.8.33_V29/OpenAssistant/app/src/main/AndroidManifest.xml)
- **REMOVE** `android.permission.ACCESS_FINE_LOCATION`.
- **REMOVE** `android.permission.ACCESS_COARSE_LOCATION`.
- **INVARIANT:** No location permissions will be requested by the application.

---

### [Component] MainActivity
#### [MODIFY] [MainActivity.kt](file:///D:/my_programs/OpenAssistant_1.8.23/OpenAssistant_1.8.33_V29/OpenAssistant/app/src/main/MainActivity.kt)
- **DELETE** `locationPermissionLauncher` (launcher, callback, and all associated logic).
- **DELETE** the startup `locationPermissionLauncher.launch(...)` call in `onCreate`.
- **DELETE** `onLocationPermissionGranted()` and related ViewModel/state imports.

---

### [Component] Device Location Tool
#### [MODIFY] [DeviceTools.kt](file:///D:/my_programs/OpenAssistant_1.8.23/OpenAssistant_1.8.33_V29/OpenAssistant/app/src/main/java/com/david/openassistant/domain/tools/DeviceTools.kt)
- **REMOVE** `LocationServices`, `FusedLocationProviderClient`, and `Manifest.permission` imports.
- **DISABLE** provider construction and access in `DeviceToolRuntime`.
- **REFACTOR** `getCurrentLocation()` to immediately return:
  ```json
  {
    "status": "unavailable",
    "reason": "Direct device location access is disabled for privacy. Research can continue using a specific city or region name supplied by the user if geographic context is required."
  }
  ```
- **INVARIANT:** No calls to Android location providers will be made. The result is structured and recoverable (the AI model is informed and can ask for a city).

---

### [Component] Privacy and Redaction
#### [MODIFY] [ResearchMonitorReport.kt](file:///D:/my_programs/OpenAssistant_1.8.23/OpenAssistant_1.8.33_V29/OpenAssistant/app/src/main/java/com/david/openassistant/data/diagnostics/ResearchMonitorReport.kt)
- **ADD** `latitude` and `longitude` keys to `MONITOR_SECRET_PATTERNS`.
- **ENHANCE** `redactResearchMonitorText` to redact numeric values associated with these specific keys (e.g., `"latitude": 41.8781` -> `"latitude": "[REDACTED]"`).
- **INVARIANT:** Does not use a broad number-pair regex to avoid corrupting legitimate measurements (e.g., "1.8.33").

---

## 2. Regression Verification Plan

### Automated Regression (Offline)
- **[NEW] `LocationContainmentTest.kt`**:
    - Prove `get_current_location` returns the "unavailable" JSON.
    - Prove provider imports/classes are unreachable/not invoked.
    - Verify `redactResearchMonitorText` redacts `"latitude": 12.3456` but preserves `"version": "1.8.33"` and dates.
- **Existing Tests:** Ensure all 478 unit tests (including `ResearchMonitorReportTest` and `SafeToolsTest`) remain passing.

### Desktop Verification
- **Assemble:** `.\gradlew.bat :app:assembleDebug`
- **Lint:** `.\gradlew.bat :app:lintDebug` (Verify 0 fatal issues).
- **Merged Manifest:** Inspect `app/build/intermediates/merged_manifest/debug/AndroidManifest.xml` to ensure neither permission exists.

### Device Verification (Physical SM-G998U)
- **Installation:** `adb install -r -t` (Retain existing data).
- **Cold Start:** 3 cycles. Verify **ZERO** permission prompts.
- **UI Check:** Main screen and Settings render correctly. Stored missions are preserved.
- **No-Provider Proof:** Search Logcat for `LocationManager` or `FusedLocationProviderClient` during tool execution (if triggers occur).

---

## 3. Rollback Plan

### Reversion Instructions:
1.  **AndroidManifest.xml**: Restore `<uses-permission>` tags for `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`.
2.  **MainActivity.kt**: Restore `locationPermissionLauncher` and the startup `launch()` call.
3.  **DeviceTools.kt**: Restore `LocationServices` imports and the `getCurrentLocation()` implementation using `fusedLocationClient`.
4.  **ResearchMonitorReport.kt**: Remove the new coordinate redaction patterns.

> [!WARNING]
> Rolling back restores overbroad startup permission behavior and raw coordinate persistence.

---

## 4. Evidence Report Correction
The updated `ANDROID_STUDIO_AI_REPORT.md` will:
- Fully redact the device serial (`R5CRC3JK8ME` -> `REDACTED`).
- Remove the API-key suffix (`27a7` -> `REDACTED`).
- Correct the tool source filename to `DeviceTools.kt`.
- Properly categorize Pass 3 claims as `SUPPORTED INFERENCE`.
