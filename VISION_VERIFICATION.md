# Vision Verification — ArogyaDoot

Date: 5 Nov 2026. Status: **wired, not yet demonstrated. No vision claim below is device-proven.**

## What was implemented (code)

1. **Cloud vision via Groq** — `OpenAiCompatibleClient.requestBody()` accepts `images` and emits
   OpenAI-format `image_url` parts (data URLs). `ArogyaPhotoVision.transcribe()` takes the active
   cloud endpoint from `ArogyaCloud.textChoice()` (Groq by default), sends the blacked-out,
   downscaled JPEG + the existing `ArogyaPhotoText` system prompt, and parses with the existing
   cleaners. `wanted()` = Cloud first + usable endpoint/key. Aadhaar blackout, ML Kit geometry,
   row/column mapping and ASHA confirmation are untouched — cloud text flows through the same
   readers and checks as before.
2. **Phone vision unchanged** — ML Kit OCR (Latin + Devanagari), table geometry (`ArogyaTableOcr`),
   MobileSAM + DINOv2 counter, tap-to-count fallback: all preserved.
3. **Honest labels** — `ArogyaPhotoVision.LABEL` = `Qwen3.8 27B (Groq, online): read the photo`;
   demo/stock/add-person screens say Qwen3 while reading.

## What must be proven on device (Stage D/E)

- [ ] Groq `qwen/qwen3.8-27b` accepts `image_url` and returns a genuine photo reading (not an
      error masked as text). If the model is text-only: mark cloud vision unavailable in
      `wanted()` + UI, do not fake it.
- [ ] Image question answering, printed document, Hindi register, ASHA form extraction,
      tabular cell mapping back to correct coordinates; uncertain entries offered for confirmation.
- [ ] Gemma 3n multimodal path (LiteRT image input) as the offline vision engine; latency and
      memory on a 6 GB device.
- [ ] No PII beyond the blacked-out photo leaves the phone; egress log reviewed.

## Deliberately not claimed

ML Kit → text → LLM is **not** presented as vision anywhere; cloud-vision and phone-vision rows in
the AI table show `—` until the proofs above pass. (The AI-table count row keeps its working
phone counter; the speech row keeps Whisper.)
