# Attachment Audit — photo, camera, PDF (offline + online)

**Date:** 6 Nov 2026 · **Commit audited:** `906ae16` (main, after the Meta removal) · **Method:** read-only source review.
Nothing here was run on a device. "Confirmed in code" means the source shows the behaviour; device behaviour still needs testing.

Paths below are relative to `app/src/main/java/com/example/arfas/`.

---

## 1. Where attachments enter the app

| # | Screen / feature | Input | Offline path | Online path | Stored where |
|---|---|---|---|---|---|
| A | **Visit → Add a card or photo** | Camera (`TakePicturePreview`), file picker (image/PDF ≤ 15 MB) | ML Kit OCR → "OCR draft" → ASHA reviews → added to Notes | none | Encrypted (AES-GCM) in visit repo |
| B | **Ask the ASHA guide → ＋ photo / PDF** | Full-res camera (`TakePicture` → MediaStore), file picker | `Phase1AttachmentManager` → ML Kit text / objects / barcodes → text evidence for Gemma + KB | Groq (Qwen) vision with raw photo, up to 3 images | Plaintext copy in `cacheDir/fixsight_attachments` |
| C | **Visit → Read the MCP card with AI** | Full-res camera, file picker | ML Kit + table-row rebuild (`ArogyaTableOcr`) → Gemma text extracts fields | `ArogyaPhotoVision` sends the photo to Groq (Aadhaar boxes blacked out) when Cloud first | Encrypted copy in visit, plus plaintext Phase1 cache |
| D | **Registry → Add person → Photo / PDF / CSV** | Camera, file picker | ML Kit + table OCR → rules + Gemma (`extractPersonFields`) | `ArogyaPhotoVision` (PERSON) when Cloud first | Not kept |
| E | **Registry → patient card read** | Camera, picker | as C | as C | — |
| F | **Stock → Scan medicine label** | Camera, picker | ML Kit → `scanMedicine` rules + Gemma | "Read online" → `ArogyaPhotoVision` (MEDICINE) | Not kept |
| G | **Stock → Count kit by photo** | Camera, picker | Tap-to-count only (DINOv2/SAM retired) | none (SAM 3.1 retired) | Not kept |
| H | **Demo tour** photo steps | Camera, picker | as D / F | as D / F | — |
| I | **Assistant (MainActivity) attach / camera** | `OpenDocument`, `TakePicture` | Same `Phase1AttachmentManager` as B | Same as B | Same as B |
| J | **Live camera / AR** (`Camera2FrameController`) | Live frames | Gemma vision observer (if vision ready) | Groq vision | — |

Supported file types in B and I: images, PDF, DOCX, DOC, PPTX, PPT and plain text. The visit attachment in A accepts only images and PDF.

---

## 2. Gaps — ordered by impact

### P0 — wrong or missing behaviour in the core ASHA flow

**G1. Gemma's vision is never used for photos in the ASHA guide flow (offline).** *Confirmed in code.*
- The local vision branch runs only when `selectedKnowledgeProvider?.isSelected() != true` (`AndroBaselineRuntime.kt:3923`, `:3935`, `:1662`).
- The visit "Ask the ASHA guide" path always turns the KB on before the turn (`ArogyaVisitAi.kt:260`, `:350`).
- On top of that, `buildAttachmentVisionPlan` keeps a photo's pixels only when ML Kit found nothing (`needsVision`, `Phase1TurnAttachment.kt:100`). The class comment still says "The current Gemma model is text-only" (`:96`), which is out of date: Gemma 3n is multimodal and `runLocalVision` exists.
- **Effect:**
  - Offline, a photo of a rash, a wound, a child's eye or a cord stump is understood only through ML Kit's text and coarse object labels.
  - For a picture with no text, the turn ends at the "couldn't inspect the attached image" message (`AndroBaselineRuntime.kt` around `:3482`).
  - The intended offline vision feature does not run in the main workflow.

**G2. The offline fallback for photos is a dead Meta path.** *Confirmed in code.*
- After a cloud failure, or when offline, the code calls `localMetaAttachmentAnswer` (`AndroBaselineRuntime.kt:3479`).
- That path needs the Meta vision stack, which is now permanently off (`META_MODELS_ENABLED=false`), so it always returns null.
- Nothing replaces it with Gemma vision. This is related to G1, but it is a separate place to fix.

**G3. Patient photos go to the cloud without masking names or faces (online).** *Confirmed in code.*
- **SOP-question photos (B/I):** the raw bitmaps go to Groq vision with no blackout at all. With the KB on, the cloud answer also skips the source-claim validator (`AndroBaselineRuntime.kt:3455`, `else true`).
- **Card / register photos (C/D/E):** `ArogyaPhotoVision.transcribe` blacks out only the Aadhaar numbers that ML Kit managed to locate.
  - Names, phone numbers, addresses and faces are sent as they are.
  - Handwritten Aadhaar numbers that OCR missed are also sent.
- This contradicts the in-code comment "Local-only: a card photo is identifiers by design, and names must never reach a cloud prompt" (`ArogyaVisitAi.kt:686`). It also contradicts the Settings and Setup promise that "names and phone numbers are masked before anything is sent".
- *Likely, still to verify:* OCR text extracted from attachments goes into the cloud evidence without the `ArogyaOnlinePolicy.scrub` that the question text gets.

**G4. Camera photos are written to the public Gallery first.** *Confirmed in code.*
- Flows B, C, D, E, F, H and I create the camera file with `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (for example `ArogyaVisitScreen.kt:381`, `ArogyaAddPersonUi.kt:188`, `MainActivity.kt:1264`).
- The temporary photo is deleted after reading, but:
  - Google Photos or other backup apps can upload it before it is deleted;
  - if the app is killed, or reading throws before `deleteTemp`, the patient photo stays in the Gallery;
  - other apps with media access can read it while it exists.
- **Fix direction:** use a `FileProvider` URI in app-private storage.

**G5. The camera fails on Android 7–9 (minSdk 24).** *Confirmed in code.*
- Inserting into `EXTERNAL_CONTENT_URI` on API ≤ 28 needs the `WRITE_EXTERNAL_STORAGE` runtime permission.
- The manifest declares it (`maxSdkVersion=28`), but the app never requests it.
- The insert therefore fails and the user sees "Camera unavailable. Choose a photo instead." on every camera button. The `FileProvider` fix for G4 removes this problem too.

### P1 — quality and reliability

**G6. Visit attachment photos (A) are thumbnails, and are not rotated upright.**
- `TakePicturePreview` returns the camera app's small preview bitmap (`ArogyaVisitScreen.kt:316`). The visit-screen code itself notes, for its other camera button, that the preview "is too small to read a card".
- `ArogyaVisitMedia` decodes with `BitmapFactory` and never calls `ArogyaPhotoGuard.upright`, so sideways phone photos lose most of their OCR. The other flows do correct rotation.
- PDFs in this flow are OCR'd for the **first 3 pages only** (`ArogyaVisitMedia.kt:48`), with no native text extraction.

**G7. Attaching a large PDF can freeze the question box for minutes.**
- Below Android 15 there is no native PDF text, so `preparePdf` renders and OCRs up to **100 pages** while you wait (`MAX_PDF_OCR_PAGES`).
- There is no progress indicator (only `qFileBusy`), no cancel button, and no page or size cap before work starts.
- On a 6 GB phone that already holds Gemma in memory this also risks running out of memory. Rendering bitmaps up to 1,400 px also coexists with the model's memory.

**G8. Plaintext attachment copies are left in the cache.**
- `cacheSelectedSource` copies every picked file, up to 256 MB each, into `cacheDir/fixsight_attachments` without encryption.
- `releaseTurnAttachments(consumed=true)` recycles the bitmaps but **does not delete the cached file**; only `clearPending` and `close` do (`Phase1TurnAttachment.kt:324` vs `:348`).
- Every card read (C) and every answered question (B) therefore leaves a plaintext patient document behind.
- Conversation-scoped copies under the persistent workspace are also unencrypted. Visit attachments, by contrast, are AES-GCM encrypted.

**G9. Images are silently dropped online.** `GroqChatProvider` keeps at most 3 images per request (`:1883`), but attachment planning prepares up to 4 (`MAX_CLOUD_RECOVERY_IMAGES = 4`). The fourth is removed without telling the user or the answer.

**G10. Whether Groq's model can read images is unverified.**
- Every vision request is sent to `qwen/qwen3.8-27b` (`GroqChatProvider.kt:72`); the Meta model names are remapped to it.
- If that model is text-only or not on Groq, every online photo feature fails and falls back to the offline path, which per G1/G2 is weak.
- **Needs one real authenticated test** (key redacted).

**G11. Card reading does the same work three times.** `readCardDetailed` runs:
- the full Phase1 staging: ML Kit text, objects and barcodes;
- a second decode plus table OCR;
- `ArogyaVisitMedia.import`, which runs a third full OCR.

Each pass decodes and OCRs the same photo again: slow on low-end phones.

**G12. Drug-kit counting has no automatic counter any more (G).**
- With the Meta stack retired, `canPhone` is always false (`ArogyaCloudUi.kt:344`, `:377`), so counting is manual tapping only.
- The UI still contains the teach/DINOv2/SAM 3.1 states and their labels.
- Gemma vision (offline) or Qwen (online) could give an estimated count for the ASHA to confirm, but this is not built.

### P2 — wording, edge cases, consistency

**G13. Stale "Muse Spark" / Meta wording in photo features.**
- `ArogyaStockUi.kt:352–355` ("Muse Spark is reading the label…").
- The count engine labels "MobileSAM + DINOv2" and "SAM 3.1 (Meta)".
- The `ArogyaAiUsed` steps, the demo tour, and the "Photo counting" status text in `ArogyaCloud.status`.

**G14. Edge cases with poor handling:**
- **Password-protected PDF:** fails with a generic "Could not read this file".
- **HEIC photos:** decode depends on the platform (API 28+ only).
- **Multi-page register PDFs in Add person:** whether every page is read has not been checked.
- **Visit attachments:** up to 8 per visit, but the user is not told when the limit is reached; the card photo is simply not kept.

**G15. Mixed OCR scripts.** The Devanagari reader is used only for pages that are mostly Hindi (`MLKitProcessor.kt:117`), so mixed Hindi/English cards can lose one script. The handover already lists the known `TableOcrTest` failures (2/76).

**G16. Exports fail on Android 7–9.** `MediaStore.Downloads` (API 29+) is used without a version check by `ArogyaShare.shareVisitPdf`, the registry export, the CSV template and the setup copy. `runCatching` stops a crash, but these features never work on API 24–28.

---

## 3. What does work (confirmed in code)

- Every camera and picker launch calls `ArogyaPhotoGuard.hold` (keeps the app in the foreground). The pending URI and target are saved with `rememberSaveable`, so a camera round-trip survives the activity being recreated.
- Photo rotation is corrected in flows B, C, D, F and G.
- Visit attachments are stored encrypted, limited to 15 MB, never auto-filled, and the OCR is reviewed by a person before it reaches Notes.
- PDFs on Android 15+ use native page text, plus OCR only where it adds new text.
- PDFs ask for a page number instead of guessing when a visual cannot be located.
- Card and person fields are kept only if they appear in the source text (grounding checks), and card extraction itself runs on the phone (`cloud = false`).
- Pending attachments are cleared when a new AI dialog opens (`ArogyaVisitScreen.kt:234`), so files do not carry over between households.

---

## 4. Suggested fix order (one small, separately tested change each)

1. **G4 + G5:** move every camera capture to a `FileProvider` URI in app-private storage. This fixes the Gallery leak and the Android 7–9 camera together.
2. **G3:** policy decision for the owner. Either keep identifiable photos (cards, registers) local-only, or add explicit per-photo consent plus face/name masking. Also apply `scrub` to attachment OCR text sent online. Make the Settings promise match.
3. **G1 + G2:** let a KB-on turn with a photo run Gemma vision first, with its description added to the KB evidence, and replace the dead Meta fallback with Gemma vision. Update the stale comment. Needs on-device memory testing.
4. **G8:** delete cached copies on consumed release; encrypt or avoid the persistent workspace copies.
5. **G6:** visit camera at full resolution, upright correction in `ArogyaVisitMedia`, page count shown for PDFs.
6. **G7:** page and size cap with a progress indicator and Cancel for PDF staging.
7. **G10:** one live Groq vision test; then **G9** (cap planning at 3).
8. **G11, G12, G13, G14, G15, G16** as follow-ups.

## 5. Device tests needed to close this audit

| Test | Proves |
|---|---|
| Offline, KB on: attach a rash photo with no text, ask "what is this?" | G1 / G2 (expect failure today) |
| Online: attach a card photo with "Cloud first", capture the request (proxy) or log image dimensions only | G3 |
| Take a card photo, kill the app before it finishes, open the Gallery | G4 |
| Android 8 or 9 phone/emulator: tap any camera button | G5 |
| 60-page scanned PDF on Android 13 | G7 timing / memory |
| After 5 card reads, list `cache/fixsight_attachments` (run-as) | G8 |
| Groq key set: one photo question | G10 |

---

## 6. Resolution status (6 Nov 2026)

All 16 gaps have a code change. **None is verified on a device yet.** Host tests and `compileDebugKotlin` / `assembleDebug` pass.

| Gap | Commit | What changed | Still needs |
|---|---|---|---|
| G4, G5 | `3a7e62d` | All 7 camera captures go to `cacheDir/camera` through a FileProvider (`ArogyaCameraFiles`); stale captures purged after 6 h | Android 8/9 camera test; capture on a Samsung camera app |
| G3 | `2445619` | Card / register / person photos never go online. New **Send photos online** switch (off by default) gates every Groq image. Attachment text scrubbed of phone, Aadhaar and e-mail before going online. Privacy text corrected | Decide whether the default should stay off |
| G1, G2 | `49e667f` | Gemma 3n describes each attached photo on the phone (`ArogyaLocalVision`), and the description joins the KB-grounded evidence. Dead Meta fallback replaced by Gemma vision (no-KB turns). Card read falls back to Gemma transcription when OCR finds nothing | Rash/wound photo offline; check memory and time on a 6 GB phone |
| G6 | `49e667f` | Visit card photo at full resolution, EXIF rotation, 6 PDF pages OCR'd | Sideways photo test |
| G7 | `49e667f` | Cancel button; PDF OCR capped at 24 pages without native text; cancellation checked per page | 60-page scan on Android 13 |
| G8 | `49e667f` | Cache copy deleted after a turn; previous household's conversation attachments deleted on visit switch | `run-as` cache listing |
| G9, G10 | `49e667f` | Cloud images capped at 3. Groq docs confirm `qwen/qwen3.8-27b` accepts images (3 per request, 20 MB) | One real authenticated request |
| G11 | `feb1137` | Card photo OCR'd once (no staging pass, no OCR on the kept copy) | Card read timing |
| G12 | `feb1137` | Gemma estimates the kit count; saved count is still the ASHA's taps | Accuracy on real kit photos |
| G13 | `feb1137` | Muse/Meta wording fixed; medicine "Read online" offer no longer reappears | — |
| G14 | `feb1137` | Plain messages for password PDF, HEIC, unsupported type, too large, 8-attachment limit | — |
| G15 | `feb1137` | `ArogyaTableOcr.mergeScripts` keeps Hindi lines on mostly-English pages (MergeScriptsTest 6/6) | Mixed Hindi/English card |
| G16 | `feb1137` | `ArogyaDownloads`: Android 7–9 exports go to the app's Download folder and are shared via FileProvider | Export + share on Android 9 |

**Trade-offs to know:**
- With "Send photos online" off (the default), online photo answers are disabled. Offline photo understanding now relies on Gemma vision, which needs `isReadyForVision`.
- Attachment text sent online loses long numbers, including toll-free helpline numbers inside the same evidence.
- Gemma's photo description adds roughly 10–30 s to attaching a photo (estimate, not measured).
