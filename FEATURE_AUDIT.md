# Feature Audit — every feature after today's changes (6 Nov 2026)

**What this is:** a source-level check of every feature for the bug classes found in the demo, plus host tests and a full APK build.
**What it is not:** a device test. The local emulator (`Medium_Phone_API_36.1`) never finished booting on this PC: the software-OpenGL driver is missing and the boot snapshot is broken. So **nothing here was run on Android.** Use `PHONE_AUDIT.md` for that.

## Bug classes swept across the whole codebase

| # | Bug class (why it appeared) | How it was found | Status |
|---|---|---|---|
| B1 | Online features gated on `MetaOnline.ready()` (needs the retired Meta key, so they never ran) | `grep MetaOnline.ready` → 7 hits | **Fixed** in visit "Check online", demo online check and the saved-question queue. The 4 remaining hits are retired Meta voice/SAM paths that correctly return "not available" |
| B2 | Calls to the retired Meta API (`MetaOnline.api`: SAM, Muse voice) | grep → 5 hits | All unreachable (`if (true)` / `cloud = false`) or guarded with a fallback |
| B3 | Person/card photos expected to be read online (blocked by the privacy change) | grep `ArogyaPhotoVision.transcribe` | **Fixed:** add person, PDF import, MCP card, demo person/card → Gemma on the phone |
| B4 | Features asking for retired models (DINOv2, SAM, DETR, Llama) | grep `metaVision.has`, `DINOV2`, `LLAMA3B`, `metaStatus` | **Fixed:** kit count, AI table, "✨ AI" chip, self-test, "remember this item". The rest are unreachable helpers |
| B5 | User-visible "Meta" / "Muse Spark" / "Llama" text | grep of quoted strings | **Fixed** everywhere a user can see it, except the **"Built with Muse Code" credit** (4 places), left for your decision |
| B6 | Modal mic popups | grep `ArogyaDictationDialog` | Removed; all mics inline |
| B7 | Camera photos in the public Gallery / Android 7–9 camera | grep `EXTERNAL_CONTENT_URI` | Fixed (private FileProvider) |

## Feature matrix

Legend: **Code** = call path traced in source today · **Host** = host test passes · **Build** = compiles into the APK · **Phone** = still needs a device check.

| Area | Feature | Issues found and fixed today | Evidence | Phone check (PHONE_AUDIT step) |
|---|---|---|---|---|
| Setup | Setup wizard (Gemma, Whisper, guide, sample) | Dead Meta-zip standby removed; Groq key save no longer crashes | Code, Host (SetupPlan 18/18, SetupMatch 14/14), Build | 1.1–1.5 |
| Settings | Redesigned cards, Groq key, answer mode, photos switch | Meta rows removed; "Send photos online" added | Code, Build | 1.2–1.6 |
| Settings | Self-test ("Check the app") | **Was failing on retired Meta models every run**; now checks Gemma, vision, OCR, Groq | Code, Build | run it once |
| Settings | Guide install / switch / check | — | Code, Host (GuideLibrary) | 1.4 |
| Home | Due list, today's plan, online/offline switch | — | Code, Host (DayCheck, Flow) | 3.x |
| Registry | Add person: manual form + mic | Inline mic | Code, Host (AddPerson 24/24), Build | 3.3 |
| Registry | Add person: photo (printed / handwritten) | **Handwriting regression fixed** (Gemma on phone); false "turn on internet" text removed | Code, Host (RegisterTables 26/26), Build | demo W1, 3.x |
| Registry | Add person: PDF | Row-by-row reading, Gemma for scans, page note | Code, Build | 3.14 |
| Registry | Add person: CSV / Excel | `.xlsx` support, separators, encodings, wider picker | Code, Host (XlsxImport 15/15) | 3.12–3.13 |
| Registry | Duplicate / merge on import | Grounding bug fixed (glued digits, partial names) | Host (AddPerson 24/24) | — |
| Registry | Registers, forms, diary, monthly | — | Host (Forms, FormsVerify, Monthly, Registry, Apply…) | — |
| Registry | Backup / restore, exports | Android 7–9 exports now work | Code, Build | 3.17, 4 |
| Visit | Guided form + "Speak visit note" | Inline speak | Code, Host (Guided, FormFill) | 3.5 |
| Visit | Voice notes + AI organise | Inline speak → organise hand-off checked | Code | 3.4 |
| Visit | Add card or photo (OCR draft) | Full-resolution capture, upright, 6 PDF pages | Code, Build | 3.10–3.11 |
| Visit | MCP card read | One OCR pass; Gemma fallback; photo stays on phone | Code, Build | demo W2 |
| Visit | Ask the guide (+ follow-up, more detail) | Inline mic; attachments get a Gemma description | Code, Host (AshaJourney 56/56, GoldMatch) | 3.1–3.2, 3.7 |
| Visit | Check online / save for later | **Both were dead (Meta gate); fixed** | Code, Build | 1.5 + online question |
| Visit | Danger signs | — (fixed rules) | Host (FinishVisit 34/34) | demo W2 |
| Visit | Family explanation, ANM message | — | Host (DemoTour) | demo |
| Visit | Finish visit (extract fields, plan) | — | Host (FinishVisit, Save, Review) | — |
| Visit | Visit PDF / share | Android 7–9 path | Code, Host (DocRender) | 3.17 |
| Stock | Receive / write-off | Expired / near-expiry warning | Code, Host (MedStock 18/18, StockRegister 22/22) | 3.15 |
| Stock | Medicine label scan | Gemma offline fallback, brand/Hindi names, syrup bug, "Read online" offer bug | Code, Host (MedScan 24/24, MedStock) | demo W3 |
| Stock | Drug-kit count | All 16 items; Gemma estimate; labels no longer "Meta" | Code, Host (AiTable 33/33, CountDots) | 3.16, demo W3 |
| Stock | Voice stock | Inline speak | Code, Host (VoiceStock 25/25) | 3.6 |
| Stock | Refill request | — | Host (Refill, StockMap) | demo W3 |
| Learning | Daily practice | — | Host (Practice) | demo W1 |
| Demo | 3 workflows, 15 scenes | 4 demo bugs fixed (online check, person/card re-read, Qwen branch, kit count) | Code, Host (DemoTour 70/70) | §2 |
| AI | Offline Gemma (text) | Slot-leak fix kept | Code | 3.1 |
| AI | Gemma vision (photos, handwriting, count) | New today | Code, Build | 3.7, W1/W2/W3 |
| AI | Groq (Qwen) online | Meta labels removed; image cap 3 | Code, Host (Cloud 16/16) | 1.5, 3.18 |
| Voice | Whisper STT | Rebuilt with NDK 27 / 16 KB | Build, ELF check | 3.2 (**first native load of the new build**) |
| Voice | TTS (read aloud) | — | — | demo narration |
| Assistant | Main chat screen, live camera / AR | "Remember item" message fixed; the Meta vision stack can no longer activate | Code, Build | optional |
| Platform | 16 KB pages | 20/20 libraries aligned | ELF check | 4 |

## Open items

1. **Device testing:** follow `PHONE_AUDIT.md` (the emulator here can't run).
2. **"Built with Muse Code"** appears in the AI table PDF, the demo end screen, the demo summary and the registry about line. It's a credit, not a feature; keep or remove?
3. Unreachable Meta/Llama code (MetaVisionEngine, LlamaCppCore, Meta-zip installer) is still in the source. It's harmless, but could be deleted in a separate clean-up commit.
4. Gradle is set to 2 GB of memory, which makes builds take 25–47 minutes on this PC. Raising `org.gradle.jvmargs` would help; that's a build-config change, so it needs your OK.

## Full host gate (all 57 suites, run without stopping)

**55 suites green + Batch2Test 49/49** (it passes with the project runner; it fails only in the faster runner, because of an `internal` function). **TableOcrTest 74/76**, the same 2 historical failures as the Candidate B baseline. The gate caught one regression from today's catalog change (ApplyTest still expected one Meta counter); the test now asserts that no Meta model is left (22/22).
