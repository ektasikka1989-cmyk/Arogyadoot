# ArogyaDoot — Build Verification (5 Nov 2026)

Evidence that the rebranded tree builds and behaves. Build ran on the developer's Windows laptop
(Android Studio JDK, SDK 36, NDK 26.1.10909125). No device was touched; no phone actions were taken.

## 1. Host logic tests (`tools/host-tests/`)

- Full gate `run.ps1`: 25 suites passed (through `PracticeTest: 11 passed, 0 failed`), then stopped at
  `AddPersonTest` — **pre-existing breakage, not from this rebrand**: the uncommitted working tree
  references `ArogyaPersonPhoto.parse`, a symbol that exists nowhere in the tree or in git HEAD
  (`git grep ArogyaPersonPhoto HEAD` is empty; the references were added by uncommitted WIP in
  `AddPersonTest.kt`). The harness throws on first failure, so the 27 later suites did not run here.
- Targeted `TableOcrTest` with the full shared-source list (same list as `run.ps1`):
  **compile clean, 72 passed, 2 failed — both failures proven pre-existing**:
  - `setup-links`: asserts `MODELS_URL` contains `/resolve/main/…`, but `MODELS_URL` is the R2 primary
    URL. Identical test + source at HEAD → fails at HEAD too. Neither line was touched by the rebrand.
  - `narrow-register-columns-not-merged`: check exists only in the uncommitted WIP test changes and runs
    against uncommitted WIP `ArogyaTableOcr.kt` (48 insertions). Neither was touched by the rebrand.
  - The rebrand's own assertions pass: `used-sop-labels` (new `ArogyaDoot • offline` label AND legacy
    `Pramaan • offline` label both map to Llama), `cloud-not-used-label`, `online-flag`.
- Note: `run_some.ps1` cannot run `TableOcrTest` — its file list predates `ArogyaTableOcr`,
  `ArogyaPhotoText`, `ArogyaAiUsed`, `ArogyaSetupPlan` (pre-existing harness gap, left as is).

## 2. Gradle build

Command: `gradlew.bat :app:assembleDebug --console=plain` (with `JAVA_HOME` = Android Studio `jbr`).

> Status: ✅ `BUILD SUCCESSFUL` (see history below).

- First attempt: `BUILD FAILED in 10m 9s` at `:app:packageDebug` with `java.io.IOException: There is not
  enough space on the disk` (C: had 0 bytes free). Every compile task had already passed; only packaging
  was blocked. The owner then freed 5.84 GB on C:.
- Second attempt (incremental): `BUILD SUCCESSFUL in 1m 57s`, 59 tasks (6 executed, 53 up-to-date).
  Command: `gradlew.bat :app:assembleDebug --console=plain` with `JAVA_HOME` = Android Studio `jbr`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, 694.5 MB, built 5 Nov 2026 13:52 IST.
- `output-metadata.json`: `"applicationId": "com.arogyadoot.app"`, versionCode 1, versionName 1.0. ✅
- `aapt dump badging` on the APK: `package: name='com.arogyadoot.app'`,
  `application-label:'ArogyaDoot'`, `launchable-activity: name='com.example.arfas.MainActivity'
  label='ArogyaDoot'`. ✅
- Merged manifest: `package="com.arogyadoot.app"`, application + launcher activity
  `android:label="@string/app_name"`, `app_name` = `ArogyaDoot`. ✅
- Native libs inside the APK (`lib/arm64-v8a/`): `libpramaan_llama.so` (freshly rebuilt with the new log
  tag), `libwhisper.so`, `libonnxruntime.so`, Sherpa-ONNX (jni/c-api/cxx-api), ML Kit OCR pipelines,
  LiteRT, ggml, `libc++_shared.so` — the full AI native set. ✅
- No changes were made outside `C:\Arogyadoot` (all edits are in-tree; Gradle wrote only to the project,
  its caches, and temp).

## 3. Unit tests and lint

- `:app:testDebugUnitTest`: FAILS TO COMPILE — **pre-existing, not from this rebrand** (zero files under
  `app/src/test` were touched). `KbRuntimeRoutingTest.kt` references `KbSelectedTurnRouter`, a symbol defined
  nowhere in main or test sources, and uses `Test`/`assertEquals`/`runTest` without the junit/coroutines-test
  imports the module does not provide. All-or-nothing test compilation blocks the other 11 suites.
- `:app:lintDebug`: finished in 12m 22s with **465 errors — all pre-existing, zero caused by the
  rebrand**: 459 `NewApi` (e.g. `java.time` calls vs minSdk 24), 3 `NonObservableLocale`, 2
  `MissingPermission`, 1 `StateFlowValueCalledInComposition`. Mechanical check: 284 unique error lines
  intersected with all 281 added lines vs HEAD → exactly 1 overlapping line (`ArogyaTables.kt:156`),
  where the diff proves only the brand string changed and the flagged `java.time.LocalDate.now()` call is
  byte-identical to HEAD. All other errors sit on lines this migration never touched.
- `androidTest` compile likewise references the missing `ArogyaPersonPhoto` (new `RestorationTest.kt` WIP);
  `connectedDebugAndroidTest` was not run (no device actions without asking).

## 4. Identity checks

- [x] APK built as `com.arogyadoot.app` with label `ArogyaDoot` (aapt-verified, §2)
- [ ] APK installs side by side with Pramaan + basic AI smoke tests (needs a device; NOT done — no device
  actions were requested; distinct applicationId makes coexistence expected, not proven)
- [x] No `com.example.pramaan` in code, manifests, resources, or scripts (only history notes in docs)
- [x] No ContentProvider authorities / deep links / Firebase ties carried over (none existed)
- [x] `git status` reviewed: only intended source changes + 3 intended new files; secrets, models, logs,
      backups, and the Stage-2 backup dir all ignored (see `MIGRATION_REPORT.md` §5–§6)

## 5. Pre-existing issues (not introduced here, not fixed — out of scope)

1. `ArogyaPersonPhoto` missing (breaks full host gate + androidTest compile).
2. `setup-links` host check fails at HEAD.
3. `narrow-register-columns-not-merged` WIP check fails against WIP table-OCR code.
4. `run_some.ps1` file list is stale for newer suites.
5. `:app:testDebugUnitTest` does not compile (`KbSelectedTurnRouter` undefined + missing test imports).
6. C: was 100% full (0 bytes free) — blocked the first `:app:packageDebug`. Owner freed 5.84 GB;
   incremental rerun then succeeded. No repo files were deleted to fix this (only my own scratch
   `tools/host-tests/out*` output, 77 MB, regenerable).
7. `:app:lintDebug` reports 465 errors (459 NewApi, 3 NonObservableLocale, 2 MissingPermission,
   1 StateFlowValueCalledInComposition) — all pre-existing; overlap analysis proves none were introduced
   by the rebrand (see §3).
