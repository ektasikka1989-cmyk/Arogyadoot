# Emulator Memory RCA — ArogyaDoot crash after Gemma download

Date: 5 Nov 2026. Status: **hypotheses with code evidence. No logcat/tombstone was available to
this session (emulator is on the user's Google Cloud host), so no root cause is proven.**
Nothing here claims an OOM — the evidence required to decide is listed at the end.

## Reported symptoms

1. Gemma model download completed.
2. App crashed while entering a Groq API key.
3. Setup wizard does not resume when Groq is unconfigured.
4. A 16 KB warning appeared on launch.
5. Mic buttons open a popup (addressed separately in INLINE_STT_IMPLEMENTATION.md).

## Established code facts (no device needed)

**F1 — Gemma loads at app start, in the background.** `MainActivity:457` calls
`FixSightRuntimeGraph.initialiseAsync()` (IO scope) → `ensureLocalModelReady()` loads
`preferredTextModelFile()` (Gemma-first since the migration). Opening Settings or the key field
does NOT trigger any model load; the keyboard does not touch native code. A crash "while entering
a key" therefore coincides with — but is not caused by — key entry, unless it is the keystore
issue in H1.

**F2 — Key save ran uncaught Keystore code on the main thread (prime suspect, now fixed).**
`KeyPage.save()` (was `ArogyaSetupUi.kt:560`) → `FixSightRuntimeGraph.updateGroqKey()` →
`GroqKeyStore.saveKey()` ran `AndroidKeyStore` key generation + AES/GCM cipher with no try/catch,
on the UI thread. `decrypt()` was already wrapped; `saveKey()` was not. On emulators with a
flaky/locked Keystore this throws (`ProviderException`/`KeyStoreException`) and kills the app at
exactly the reported moment (tap Test-and-save / Save). Fix applied this turn: save is caught and
logged in `updateGroqKey()`; both setup and Settings verify the key actually persisted and show
"keystore error" instead of crashing or lying. If the user's crash trace shows
`android.security.*` / `GroqKeyStore.saveKey` / `KeyPage`, this was it.

**F3 — Setup non-resume is by design, unless the app crashes at launch.** `ArogyaSetup.isDone()` is
a persisted flag, set by DonePage "Open ArogyaDoot" and by Skip — even with no key (the key step is
documented optional). Auto-open happens only when `!isDone` (`ArogyaVisitScreen.kt:192`). So "does
not resume" means either (a) done was already marked (banner "Finish setup ›" + ⚙ menu still lead
back), or (b) the app dies at launch before the wizard can show (native/OOM crash loop). Logcat
distinguishes: case (b) shows the process dying right after `RUNTIME_GRAPH_MODEL_LOAD_BEGIN`.

**F4 — 16 KB alignment, measured from the shipped APK (llvm-readobj, NDK 27).**
16 KB-aligned (safe on 16K-page devices): `liblitertlm_jni.so` (the Gemma runtime),
`libonnxruntime.so`, `libsherpa-onnx-jni.so`.
4 KB-only (WILL fail to load on a 16K-page kernel): `libpramaan_llama.so`, `libwhisper.so`,
`libggml*.so`, `libc++_shared.so` (and unchecked others). The launch warning is consistent with a
16K-page emulator image flagging these libs. Consequence: on a 16K-page emulator, any lazy load
of llama.cpp (forced-Llama path) or Whisper (dictation!) dies in the linker — Whisper is loaded by
EVERY mic use (`AndroWhisperRecognizer.ensureInitialized()` → `libwhisper.so`). The Gemma path
itself is 16K-clean.

**F5 — No duplicate model instances in the load path.** `ensureLocalModelReady()` is mutex-guarded
("exactly one load attempt at a time"); reload-after-mismatch unloads first; Whisper recognizers
are per-session with `release()` in `finally` (dialog + new inline controller).

## Hypotheses ranked (to be proven by the evidence below)

- **H1 — Keystore throw on key save (code-proven possible, fix applied).** Confirm: crash stack
  contains `GroqKeyStore`/`AndroidKeyStore` + no native abort. If confirmed, the fix in this build
  resolves it; re-test key save on the emulator.
- **H2 — Gemma native load OOM/abort on a small emulator (unproven).** Confirm: `RUNTIME_GRAPH_MODEL_LOAD_BEGIN file=gemma-…` followed by `OutOfMemoryError`, `SIGABRT` (failed mmap/malloc), or
  ActivityManager low-memory kill with no Java stack. Fix direction (only if proven): load-guard
  with a clear "not enough memory" message + keep the downloaded file (already the behavior —
  failed loads never delete the bundle).
- **H3 — 16K-page linker death (unproven, needs page size).** Confirm: `PAGE_SIZE=16384` on the
  emulator + `UnsatisfiedLinkError`/linker abort naming `libwhisper.so`/`libpramaan_llama.so`.
  Note H3 cannot explain a crash *at key entry* (no native load there) — it would strike at first
  mic use or forced-Llama load instead. Fix direction: rebuild those libs with 16K alignment
  (separate native task; Gemma needs nothing).

## Evidence required from the Cloud emulator (copy-paste)

```
adb shell getprop ro.build.version.release; adb shell getprop ro.product.cpu.abi
adb shell getconf PAGE_SIZE; adb shell cat /proc/meminfo | head -3
adb shell "getprop | grep -i -E 'heap|ram|ubert'"
adb logcat -d -v threadtime '*:W' | grep -E -i 'AndroidRuntime|arogy|gemma|litert|llama|whisper|keystore|groq|lowmemory|kill|fatal|tombstone|sigsegv|sigabrt|oom' | tail -120
adb shell dumpsys meminfo com.arogyadoot.app | head -40
ls /data/tombstones  (as root, if available)
```

Also: emulator AVD `config.ini` (`hw.ramSize`, `abi.type`, `image` = Google APIs vs 16K image?),
and whether "Don't keep activities" / background limits are on. With the crash stack, H1 vs H2 vs
H3 is decidable in minutes: Java `android.security` stack = H1; `RUNTIME_GRAPH_MODEL_LOAD_BEGIN`
with no END + native abort/LMK = H2; linker/`UnsatisfiedLinkError` + 16K pages = H4/H3.

## Changes made this turn (defensive, all reversible)

- `FixSightRuntimeGraph.updateGroqKey()`: keystore failures caught + logged, never crash.
- `KeyPage.save()` returns verified-Boolean; setup shows a keystore-error message instead of a
  false "Saved" (both test-save and save-anyway paths).
- `MainActivity.saveGroqApiKey()`: status shows "Save failed (keystore error)" when verify fails.
- Stale Llama progress strings in `ensureLocalModelReady()` → Gemma/standby words.
- Missed Muse Voice strings in `ArogyaDictationDialog` → retired words.
