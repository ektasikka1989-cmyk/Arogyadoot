# AI Routing Matrix — ArogyaDoot (post-migration)

Date: 5 Nov 2026. One setting (`AnswerPolicy`: Local first / Cloud first) still governs every AI
action; cloud attempts have deadlines and fall back to the phone with a labelled reason (never a
silent switch). Code-complete; device behaviors marked *(device proof pending)*.

| Feature | Local first | Cloud first | Engine label shown |
|---|---|---|---|
| Ask SOP answer | Gemma 3n + RAG *(proof pending)* | Groq Qwen, else Gemma + reason | `Groq · Qwen3.8 27B • online` / `ArogyaDoot • offline (<file>)` |
| Voice note → form | Gemma, else fixed rules | Groq, else Gemma, else rules | endpoint label / `On-phone model (offline): …` / `Fixed patterns only` |
| Photo / card / label reading | ML Kit + rules + Gemma vision *(proof pending)* | Groq vision attempt, else phone path + reason | `Qwen3.8 27B (Groq, online): read the photo` + phone steps |
| Speech to text | Whisper (only option) | Whisper (only option) | `Whisper (on this phone, offline)` |
| Drug-kit count | Phone counter / tap-to-count | Phone counter / tap-to-count | `MobileSAM + DINOv2 (estimate)` / `Counted by tapping` |
| MCP card fields, ANM msg, explain, practice, organise | Gemma *(proof pending)* | Groq, else Gemma | as Ask SOP |
| Danger signs, schedules, incentives | Fixed rules (never AI) | Fixed rules (never AI) | `⚙` / rules text |
| Read aloud | Android offline voice | Android voice | — |

Key routing code: `ArogyaVisitAi.route()` (try-order + accept checks), `fillForm()`,
`ArogyaCloud.textChoice()` (endpoint + key usability), `ArogyaRouting.order()`,
`ModelFileManager.preferredTextModelFile()` (Gemma-first), `GroqChatProvider` (key-format routing),
`ArogyaPhotoVision.wanted()/transcribe()` (Groq `image_url`).

Retired (kept in code, unreachable): `meta` endpoint preset, `MetaOnline` voice/vision/SAM calls
(`META_ENABLED = false`, fail-closed), Llama AUTO selection (loads only under `LLAMA_ONLY`).
Together AI / Glimmer preset remains selectable with its own key (not a Meta API call).
