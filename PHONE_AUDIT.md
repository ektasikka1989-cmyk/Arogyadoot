# Phone Audit Script — ArogyaDoot (6 Nov 2026)

Step-by-step checks for the first physical test of today's build. Tick each line **PASS / FAIL / PARTIAL** and note what you saw.
For any FAIL, save `app_log.txt` (or a logcat) and a screenshot. Use only **synthetic** names and data.

**Build:** `app/build/outputs/apk/debug/app-debug.apk` (also copied to `C:\Arogyadoot-recovery-20261106\`).
**Rollback:** `candidate-B-WORKING-full-app-debug.apk` in the same folder (the build that was working this morning).

## 0. Before you start (protect the phone's data)

- [ ] Install with **`adb install -r`** (keeps the 3.6 GB Gemma model, guide and key). **Never uninstall, never "Clear data".**
- [ ] Do **not** run `connectedDebugAndroidTest` (it uninstalls the app).
- [ ] Note the phone model, Android version and RAM: ______
- [ ] Have ready:
  - one **printed** registration slip and one **handwritten** register page (synthetic);
  - an MCP card page with handwritten dates;
  - one medicine strip, plus one strip whose expiry date has passed (or a photo of one);
  - a small `.xlsx` patient list (with a Hindi name and a date column);
  - one PDF;
  - a rash- or wound-like photo with no text in it.

## 1. Launch and setup

| # | Check | Expect |
|---|---|---|
| 1.1 | Open the app (cold start) | No crash, no 16 KB warning dialog |
| 1.2 | Settings ⚙ | One page of cards: Offline AI · ASHA guide · Online AI · How answers are chosen · Check the app · About. **No "Meta" anywhere** |
| 1.3 | Offline AI card | "Gemma 3n ready" (or loading, then ready) |
| 1.4 | ASHA guide card | Guide installed and ready; the pack list is correct |
| 1.5 | Save a Groq key (Settings → Online AI → Save, then Test) | No crash. Status shows "Saved — not tested", then "Connected". On a secure-storage error you see "Could not save…" instead of a crash |
| 1.6 | "Send photos online" switch | Present and **off** by default |

## 2. Demo tour (the main check)

Run each workflow in **English**, then once in **Hindi**. In every scene, also check that the narration reads aloud and that nothing says "Meta", "Muse" or "SAM".

### Workflow 1 — New pregnancy: register and first ANC visit

| Scene | Do | Expect |
|---|---|---|
| plan | Open | Sunita's plan for today |
| addperson | Take a photo of the **printed** slip | Fields read on the phone (name, husband, age, mobile, house, village, LMP). Aadhaar shown masked |
| addperson | Repeat with the **handwritten** page | If fields are missing: **"📱 Read again with Gemma (on this phone)"** button. Pressing it fills more fields, with the label "Gemma 3n (on this phone)". Text says "The photo stays on this phone" |
| form / voice | Play the voice note | The form fills, and each value shows its source words |
| danger | — | Fixed danger rules shown (no AI) |
| sops | — | Guide answer with a source page; a follow-up answer. **With internet + Groq key: an "online check" result appears** (was always skipped before today). Without them: skipped, and it says so |
| explain / saved / mcpcard / practice | — | Family explanation; one save updates the records; MCP card opens with today's changes in yellow; 3 practice questions with sources |
| summary | — | The AI tools table lists Gemma / Qwen / Whisper / ML Kit only. Drug-kit count shows "Gemma … estimate, confirmed by taps" |

### Workflow 2 — Newborn day-7 visit with a danger sign

| Scene | Do | Expect |
|---|---|---|
| card | Photograph the MCP card with handwritten dates | Vaccines and dates read on the phone. If incomplete: the "Read again with Gemma" button works offline |
| voice / danger | — | The danger sign is caught by the rules and an urgent warning shows |
| anm | — | WhatsApp draft with no names; it opens WhatsApp |
| sops / explain / mcpcard | as in Workflow 1 | as in Workflow 1 |

### Workflow 3 — Sick child: medicines and the drug kit

| Scene | Do | Expect |
|---|---|---|
| voice / danger / sops | — | As above; ORS and zinc advice |
| refill | — | Refill request with exact balances; opens WhatsApp |
| medscan | Photograph a medicine strip | Name, batch and expiry read. The kit item is matched (e.g. Electral → ORS) |
| medscan (count) | Photograph the ORS shelf | "Gemma estimates about N…" (or "count by tapping"). The saved count equals your taps |

## 3. Everyday workflow (outside the demo)

| # | Check | Expect |
|---|---|---|
| 3.1 | Ask the guide (home → Ask), offline (airplane mode) | Answer with source; Stop, then ask again → a second answer arrives (Gemma slot-leak fix) |
| 3.2 | **Mic** in the question box | Tap 🎤 → speak → the words appear **in the box** (no popup). Tap ■ to stop. Nothing is sent until you tap ↑ |
| 3.3 | Mic in a patient form field and in visit notes | Words land at the cursor, in that field only |
| 3.4 | "Speak about this visit" | Words show live under the button; on ■ they are added to Notes and AI organise opens |
| 3.5 | Guided visit → "Speak visit note" | Inline, then the form fills |
| 3.6 | Voice stock | Inline speak; the stock change is parsed |
| 3.7 | Ask the guide + attach the **rash photo**, **offline** | Attaching takes ~10–30 s ("Reading the attachment…", with Cancel). The answer uses Gemma's description, grounded in the guide. No "couldn't inspect the attached image" |
| 3.8 | Attach a long scanned PDF | Cancel works; pages beyond 24 are reported as not read |
| 3.9 | Attach a password-protected PDF / HEIC photo | Clear message ("password-protected…", "HEIC…") |
| 3.10 | Visit → Add card or photo → Photograph a card | Full-resolution photo; OCR draft readable; a sideways photo is read upright |
| 3.11 | After any camera photo: open the **Gallery** | **No ArogyaDoot photos in the Gallery** |
| 3.12 | Add person → CSV/Excel → pick the `.xlsx` | Rows imported; the Hindi name is correct; the date is correct; the phone number is whole |
| 3.13 | Add person → pick a semicolon CSV and a UTF-16 "Unicode text" file | Both import; Hindi is not garbled |
| 3.14 | Add person → PDF (register) | People read row by row; "Only the first 6 of N pages…" when the PDF is longer |
| 3.15 | Stock → receive the expired strip | Red "already EXPIRED — do not give"; button says "Save anyway" |
| 3.16 | Stock → count dialog | All 16 kit items selectable (Cotton / bandage at the bottom) |
| 3.17 | Export a visit PDF and share it | PDF saved; the share sheet opens |
| 3.18 | Settings → "Send photos online" ON, online, ask with a photo | Groq may answer about the photo. Turn it OFF again afterwards |

## 4. Optional — 16 KB / older phones

- [ ] On an Android 15+ **16 KB page** emulator image: launch, use the mic, attach a photo → no 16 KB warning, no crash.
- [ ] On an Android 8/9 phone or emulator: any camera button works, and exporting a PDF and sharing it works.

## 5. If something fails

Note the step number, what you expected and what you saw, and attach `app_log.txt`. Do not try fixes on the phone. Roll back with `adb install -r` of the Candidate B APK if the app is unusable.
