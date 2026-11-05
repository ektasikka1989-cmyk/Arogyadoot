# ArogyaDoot — Migration Report (Pramaan → ArogyaDoot fork, 5 Nov 2026)

How the independent ArogyaDoot project was established from the Pramaan tree. Build proof lives in
`BUILD_VERIFICATION.md`. The original Pramaan project was never touched; all work happened in `C:\Arogyadoot`.

## 1. Identity changes

| Component | Before (Pramaan) | After (ArogyaDoot) |
|---|---|---|
| Gradle root project | `Pramaan` | `ArogyaDoot` (`settings.gradle.kts`) |
| Application ID | `com.example.pramaan` | `com.arogyadoot.app` (`app/build.gradle.kts`) |
| Launcher / app name | `Pramaan` | `ArogyaDoot` (`@string/app_name`; both manifests follow it) |
| User-visible strings (~60 Kotlin files) | "Pramaan" UI, notifications, share/ANM text, PDF titles/footers/filenames, TTS, setup wizard, demo, `User-Agent`, AI persona prompts, provider labels (`Pramaan • …`) | "ArogyaDoot" (persona prompts included, so the model self-identifies correctly) |
| `Downloads/Pramaan` (4 files) | shared-storage folder | `Downloads/ArogyaDoot` |
| Backup JSON `app` field | `"Pramaan"` | `"ArogyaDoot"` (restore still accepts both) |
| Device-test script | `com.example.pramaan` adb paths | `com.arogyadoot.app` (`tools/device-tests/run_all.ps1`) |
| Instrumented-test ID assert | stale `com.example.arfas` (already failing) | `com.arogyadoot.app` |
| Docs | README, AGENTS.md, HANDOVER.md, MODELS.md, PRIVACY.md, plans, checklists, batch report | Rebranded; history kept (see §4) |
| Review sheet | `docs/verified_qa/Pramaan_100_QA_Doctor_Review.xlsx` | `ArogyaDoot_100_QA_Doctor_Review.xlsx` (matches the existing doc reference) |

A private helper was renamed with its single call site (compiler-verified): `askPramaan` → `askArogyaDoot`.
The `ArogyaAiUsed` provider matchers accept both `arogyadoot • …` (new) and `pramaan • …` (legacy) labels;
host tests cover both. The C++ log tag is now `ArogyaDootLlama` (nothing greps the old tag).

## 2. Application-ID conflict audit (why `com.arogyadoot.app` is safe)

- No ContentProvider / FileProvider / authorities in either manifest.
- No deep links (no VIEW/BROWSABLE filters, no schemes; one `uri.scheme` check validates outbound links).
- No Firebase / `google-services.json` / package-tied OAuth clients.
- No WorkManager workers / JobService — only `AndroVoiceForegroundService` (relative name).
- No hardcoded `com.example.pramaan` in app code (only Gradle config, docs, one log, the adb script).
- No signing config tied to the old ID (plain debug build).
- The new ID installs side by side with Pramaan; each app gets a fresh data sandbox.

## 3. Deliberately retained (internal compatibility)

- Kotlin package + Gradle namespace `com.example.arfas` — the JNI bridge exports `Java_com_example_arfas_*`.
- Native lib `libpramaan_llama.so`, `pramaan_llama.cpp`, `PRAMAAN_*` CMake vars, `pramaan.llama.lib` property.
- `MetaVisionEngine`'s `pramaan` / `pramaan_resize` model-format flag and its comments.
- SharedPreferences names (`arogya_*`, `fixsight_*`, `androllm_*`) and keystore aliases
  (`arogya_key_*`, `arogyadoot_visits_v1`, `fixsight_meta_aes_v1`).
- Data dir `arogyadoot_visits`, backup magic `ARGB`, `AROGYA_*` log markers, `Arogya*.kt` names.
- Resource identifiers `Theme.Pramaan`, `pramaan_icon_background`, `pramaan_avatar`, mipmap files, icon art.
- Internal-only strings: `pramaan_turn` timing keys, `pramaan-voice-check` utterance ID,
  `PramaanLiteRtInit` thread name, `PramaanSelfTest` / `PramaanFixesTest` object names.
- External model/guide URLs (`Pramaan_Meta_Models.zip`, `EktaSikka/Pramaan_*` datasets) — renaming would
  break setup downloads. This is a documented external dependency on Pramaan-hosted files.
- Historical docs: `docs/RELEASE_NOTES_v1.0.md` (the Pramaan v1.0 release record) and the `docs/hf/*`
  dataset cards (they describe the Pramaan-named external datasets) are byte-identical.

## 4. Local-directory independence (no `C:\Pramaan` dependencies)

The exhaustive scan found no runtime/build dependency on the original directory. Fixed:

- `_work/dbschema.py` — hardcoded `C:\Pramaan - Copy\…` DB path → path relative to the script.
- `_work/run/photo_host.ps1` — hardcoded `$here` → repo-relative (file itself is excluded from git).
- `docs/HANDOVER.md`, `docs/batch_reports/final_submission.md` — project/install paths → `C:\Arogyadoot`.
- `.idea/.name` — `Pramaan` → `ArogyaDoot`. No absolute paths in tracked `.idea` files.
- Remaining `C:\Pramaan - Copy` hits are only git-ignored material: `host_*.log`, `*.patch`, `app/.cxx`
  regenerable output, the Stage-2 backup, and the excluded agent notes (`CLAUDE.md`,
  `docs/CLAUDE_CODE_HANDOFF.md`, replicated from the old `.git/info/exclude` into `.gitignore`).

## 5. Working-tree preservation (safeguard 1)

Before any edit, all 21 modified + 10 untracked files were copied with directory structure to
`_stage2_backup_20261105_123056/` (git-ignored, kept on disk) with `MANIFEST.txt` (timestamp, branch/HEAD,
full `git status`, `git diff --stat`, per-file size + SHA-256, restore instructions). Verified 31/31.
No existing work was overwritten or discarded; the rebrand edits were applied on top.

## 6. Files changed (97 vs old HEAD, +517/−363)

- Identity: `settings.gradle.kts`, `app/build.gradle.kts`, `strings.xml`, `.idea/.name`,
  `ExampleInstrumentedTest.kt`, `tools/device-tests/run_all.ps1`.
- ~60 Kotlin files: user-visible strings, prompts, comments, provider labels, Downloads folder, backup
  `app` value, `askArogyaDoot` rename. No logic, algorithm, dependency, or behavior change except the AI
  persona name in system prompts (required so answers say ArogyaDoot, not Pramaan).
- Assets/native/res: `doc_templates/*` footers, `pramaan_llama.cpp` comment + log tag, 3 XML comments.
- Scripts/tools: `_work/run/*.bat` comments, `_work/dbschema.py` path, host-test comments +
  `TableOcrTest.kt` (new labels + legacy-label assertion), `make_samples.py`, `make_icons.py` comments.
- Docs: `README.md` (rewritten), `AGENTS.md`, `docs/HANDOVER.md` (+§11 fork note), `MODELS.md`,
  `PRIVACY.md`, 8 plan/checklist/report docs, review-sheet rename.
- `.gitignore`: backup dir, diagnostic dumps, README snapshots, `_work/run` test artifacts, agent notes.

Full per-file list: `git diff --stat HEAD` in the archived Pramaan history, or the new repo's initial
commit (same content).

## 7. Git migration (Stage 4–7, after approval)

- Old `.git` (branch `main`, remote `origin → …/Pramaan.git`, never to be pushed) is archived first:
  `pramaan-git-backup-20261105_141608.zip` (26.6 MB, 621/621 files verified by name, SHA-256
  `C4009657A9F77994E7F2666BA0987F1D1F260EB720E292148AFD60CD9860814D`, sidecar `.sha256`), moved outside
  the tree by the owner. Recovery: unzip and copy `.git` back into `C:\Arogyadoot`. Only after the
  outside copy is hash-verified is the in-tree `.git` removed and a fresh repo initialised.
- New repo: own history, `main` branch, no Pramaan remote/history; initial commit stages only intended
  source (junk and secrets excluded by `.gitignore`, verified by `git status` review).
- GitHub `Arogyadoot` (private first) is created and pushed only after explicit approval of visibility,
  account, and remote. Post-publish: wire the real repo URL into README (clone URL) and PRIVACY.md (Issues link).
