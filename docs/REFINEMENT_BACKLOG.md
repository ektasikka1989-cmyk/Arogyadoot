# ArogyaDoot – Refinement backlog (7 Nov 2026)

**Mic work frozen 8 Nov 00:20 (items 0a, 0b):** code written, not yet built or committed. Backup: `C:\Arogyadoot-recovery-20261106\mic-refinement-20261108.patch`. Next step: build, phone check, then commit as one change.

Refinement of existing features only – no new features. Handle one by one.

| # | Item | Owner | Status |
|---|---|---|---|
| 0a | **Mic: Urdu script on Hindi speech.** Language stays auto; auto now chooses only Hindi or English (Urdu counts as Hindi, English only when clearly English), Arabic-script output re-decoded as Hindi. Files: `jni.c` (whisperDetectLanguage), `WhisperLib.kt`, `WhisperContext.kt`, `ArogyaSpeechLang.kt` + `SpeechLangTest`, `AndroWhisperRecognizer.kt`, `ArogyaInlineDictation.kt` (always auto) | Claude | Code done, needs build + phone check |
| 0b | **Mic: speed + look, Claude-style.** One warm shared Whisper; phrases decoded at natural pauses while speaking; live text no longer limited to last 3 s; float buffer instead of boxed list; recording bar ✕ · pulsing dot · live waveform · timer · ✓; Material icons; haptics; ✕ restores the field. Files: `ArogyaInlineDictation.kt`, `AndroWhisperRecognizer.kt`, `ArogyaSopChat.kt`, `ArogyaRegistryUi.kt`, `ArogyaVisitScreen.kt` | Claude | Code done, needs build + phone check |
| 0e | Mic speed/memory, later: 8-bit Whisper (~80 MB vs 148 MB); per-phrase `audio_ctx` behind a switch; detect language once per recording; unload when idle on low-RAM phones (demo phone keeps it loaded from app start – done) | Claude | Open |
| 0d | Mic accuracy: compare base Whisper vs Android offline Hindi engine vs larger Whisper on 15–20 synthetic clips | Team + Claude | Open |
| 0c | Unused 252 MB English-only speech model in `assets/voice/asr` (APK size) | Claude | Open |
| 1 | Offline answers too slow (~37 s first words, 95–124 s total on S25 Ultra). Offline: answer + when-to-refer in 2–3 sentences; rest behind "More detail" (drop the ≥6-sentence rule on phone) | Claude | Open |
| 2 | Phone test of the 6 Nov build with `PHONE_AUDIT.md` (inline mic, Settings, 16 KB, Groq key, UI5/UI6) | Team | Open |
| 3 | Fill the evidence table: re-point the cloud benchmark from the Meta key to `GROQ_API_KEY` (env var on laptop, never in chat) | Team + Claude | Open |
| 4 | Remove leftover Meta/Muse/SAM/Llama strings and the dead Llama/Meta-vision code (one clean-up commit) | Claude | Open |
| 5 | Tidy repo root for evaluators: move patches, logs, backups, old reports to `_archive/` (no code change) | Claude | Open |
| 6 | Clean clone can't rebuild (759 MB git-ignored assets): ship release APK + asset-pack link, state it in README | Team + Claude | Open |
| 7 | Name the Clinical Lead (practising doctor) in README and decks | Team | Open |
