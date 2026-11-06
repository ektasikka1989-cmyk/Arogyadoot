# CANDIDATE B RESTORATION REPORT — ArogyaDoot

Date: 6 Nov 2026. Approval: conditional approval to restore Candidate B
(historical baseline + slot-leak fix only). No device changes, no push.

## 1. What was done

- Verified recovery folder (`C:\Arogyadoot-recovery-20261106`, manifest
  re-hashed, 0 mismatches) and active repo (`59e0418`, clean).
- Preserved Candidate A (`candidate-A.patch`, 79,949 bytes) and Candidate B
  (`candidate-B.patch`, 81,732 bytes) as byte-exact diffs, plus both APKs,
  the baseline core-file copy, and all prior materials.
- Applied `candidate-B.patch` to the active repo (`git apply`, clean).
- Proved the active tree byte-identical to the validated isolated worktree
  on all 20 changed files (SHA-256 per file, 20/20 match).

## 2. Candidate B APK (prepared, NOT installed)

- Path: `C:\Arogyadoot\app\build\outputs\apk\debug\app-debug.apk`
- Size: 733,888,953 bytes. SHA-256:
  `69E9378D8437278285506FB084566CB00276CCA766E6763F99831EFFE381534F`
- Build: `gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL in 5m27s.
- Native libs: 21/21 incl. `liblitertlm_jni.so`, `libwhisper.so`,
  `libpramaan_llama.so`, `libggml*.so`, `libonnxruntime.so`, MLKit ×2,
  sherpa ×3 — the exact previous set, nothing obsolete added.
- Runtime assets: 444 entries incl. guide v2 (24.5 MB), reranker (118 MB),
  semantic (118 MB), voice encoder (261 MB), Whisper (148 MB).
- Missing dependencies: none. (The 98 MB worktree APK only lacked the
  git-ignored ONNX/Whisper assets, which correctly do not transfer to fresh
  checkouts and were never deleted from this repo.)

## 3. Configuration verified

App ID `com.arogyadoot.app`; Gemma file/URL/size constants;
`litertlm-android:0.16.1`; Groq `qwen/qwen3.8-27b`; bundled guide asset path;
RAG/KB, ASHA workflow, voice and OCR code paths all present and compiling.

## 4. Host tests

Full `run.ps1`: **25 suites green, 0 failures** across 855 log lines, then
stopped at the pre-existing `AddPersonTest` compile blocker (stale reference
to `ArogyaPersonPhoto`, proven absent at `59e0418` — same blocker as the
baseline worktree run). Targeted rerun (logged): SetupPlan 18, SetupMatch 14,
Scrub 9, FormsVerify 48, FormFill 17 — all pass; TableOcrTest 2 fails
byte-identical to `59e0418` (pre-existing, unrelated).
Full log: recovery folder `host_full_active.log`.

## 5. Required confirmations

1. Active source matches approved Candidate B: YES (20/20 file hashes match
   the validated worktree; `git status` shows exactly the 20-file set).
2. Historical baseline preserved: YES (worktree intact; `candidate-A.patch`;
   baseline core-file copy; freeze bundle).
3. Only the slot-leak correction added: YES (B−A = the +7/−3 core patch,
   hunks inspected; nothing else).
4. All required native dependencies packaged: YES (§2).
5. No additional troubleshooting changes: YES (16K flags, keystore hardening,
   inline dictation + rewire, dead branches all absent; verified by diff).
6. No GitHub push: YES (no commit, push, tag, or history write performed).
7. No device data changed: YES (no install/uninstall/clear; phone untouched
   — it was already disconnected during validation).

## 6. Known defects carried (pre-existing, out of scope)

- `RestorationTest`/`V1CloudTest` (androidTest) and `AddPersonTest` (host)
  reference long-removed photo APIs — broken since before the window.
- TableOcrTest 2 failing checks — identical at `59e0418`.
- Post-cancel first-decode settle question — needs the on-device recovery
  test after install (procedure in reconstruction notes; test stub builds).

## 7. Next step (awaiting separate approval)

Install `app-debug.apk` with `adb install -r` (data preserved), re-run setup
checks, then run the `am instrument` cancellation-recovery test (never
`connectedDebugAndroidTest` — it uninstalls the app and wipes the model).
