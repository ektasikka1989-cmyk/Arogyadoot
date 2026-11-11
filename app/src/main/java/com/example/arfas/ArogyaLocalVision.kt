package com.example.arfas

import android.graphics.Bitmap
import android.util.Log

/**
 * Gemma 3n's own vision on the phone (offline). Used when ML Kit's text and object labels are not
 * enough: a rash, wound or eye photo has no text, and a handwritten card defeats OCR.
 *
 * Gemma describes or transcribes; it never decides. Its output is labelled as an AI reading,
 * goes into the same grounded answer path as OCR text, and the ASHA checks every value.
 */
object ArogyaLocalVision {
    private const val TAG = "ArogyaLocalVision"
    const val LABEL = "Gemma 3n (on this phone): looked at the photo"

    /** Also used for the online (Groq) description of an attached photo, so both read it the same way. */
    const val DESCRIBE_PROMPT =
        "List what is visible in this photo as short factual lines: what it shows (body part, object, document or medicine), " +
            "colour, size and shape, marks, swelling, wounds, rash or discharge, and any readable text. " +
            "Do not diagnose and do not give advice. Write 'unclear' for anything you cannot see well."

    private const val TRANSCRIBE_PROMPT =
        "Copy the writing on this card or register exactly, one row per line, keeping each date or value on the same line as its label. " +
            "Write [unclear] for anything you cannot read. Do not add, guess or explain anything."

    fun ready(lm: LocalModelManager?): Boolean = lm?.isReadyForVision == true

    /**
     * Longest wait for one on-phone photo reading. 11 Nov 2026: raised after review (a slow phone or a dense register
     * page needs time; the limit is only a safety net against a stuck generation, not a speed target).
     */
    fun timeLimitMs(what: String): Long = when {
        what == "count" -> 60_000L
        what == "describe" -> 120_000L
        else -> 180_000L         // transcribe / read_person / read_card / read_medicine
    }

    /** Factual description of a photo for the grounded answer, or null when vision is unavailable or fails. */
    suspend fun describe(lm: LocalModelManager?, bitmap: Bitmap): String? = run(lm, bitmap, DESCRIBE_PROMPT, 480, "describe")

    /**
     * Gemma's estimate of how many [item] are in a kit photo, or null. Only a hint shown to the ASHA:
     * the saved count is always the dots she confirms by tapping.
     */
    suspend fun estimateCount(lm: LocalModelManager?, bitmap: Bitmap, item: String): Int? {
        val prompt = "Count the separate $item items in this photo. Reply with one number only. If you cannot count them, reply 0."
        val out = run(lm, bitmap, prompt, 12, "count") ?: return null
        return Regex("""\d{1,3}""").find(out)?.value?.toIntOrNull()?.takeIf { it in 1..500 }
    }

    /** Transcription of a card/register photo (fallback when OCR read nothing), or null. */
    suspend fun transcribe(lm: LocalModelManager?, bitmap: Bitmap): String? = run(lm, bitmap, TRANSCRIBE_PROMPT, 1600, "transcribe")

    /**
     * The photo's text read by Gemma on the phone with the same prompt the cloud reader used
     * ([ArogyaPhotoText.prompt]), so the existing parsers (people, card rows, medicine) work unchanged.
     * Handwriting is where this matters: ML Kit reads handwritten Hindi badly. Null when unavailable.
     */
    suspend fun readPhoto(lm: LocalModelManager?, bitmap: Bitmap, kind: ArogyaPhotoText.Kind): String? =
        run(lm, bitmap, ArogyaPhotoText.SYSTEM + "\n" + ArogyaPhotoText.prompt(kind), 1600, "read_${kind.name.lowercase()}")
            ?.let { ArogyaPhotoText.clean(it) }

    private suspend fun run(lm: LocalModelManager?, bitmap: Bitmap, prompt: String, maxTokens: Int, what: String): String? {
        if (lm == null || !lm.isReadyForVision || bitmap.isRecycled || bitmap.width <= 0) return null
        val t = System.currentTimeMillis()
        // 11 Nov 2026 (AN): a photo of something else (a wall, a face, a random object) could keep Gemma writing
        // for minutes (greedy decoding loops), so "Add person", card and medicine reading looked stuck. Every
        // reading now has a time limit; on expiry the generation is cancelled and the caller gets "nothing read",
        // which each screen turns into its own "no details found on this photo" message.
        val limitMs = timeLimitMs(what)
        var timedOut = false
        val r = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(limitMs) {
                VisionWorkCoordinator.withVisionWork("arogya_local_vision_$what") {
                    // 8 Nov 2026: greedy (temperature 0) – copying a card or describing a photo must not vary
                    // run to run; the vision default was the chat temperature 0.8.
                    lm.inferVisionImage(bitmap = bitmap, userQueryOnly = prompt, history = emptyList(),
                        maxOutputToken = maxTokens, taskRole = VisionTaskRole.OBSERVER, temperature = 0.0)
                }
            }.also { if (it == null) { timedOut = true; runCatching { lm.requestCancelActiveGeneration("arogya_vision_time_limit") } } }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException && it !is kotlinx.coroutines.TimeoutCancellationException) throw it }.getOrNull()
        val text = r?.takeIf { it.completed }?.text?.trim().orEmpty()
        // A looping transcript ("Name: Name: Name: …", the same phrase over and over) is broken output, not a reading.
        // Only consecutive exact repeats count: a register page legitimately repeats its headings for every person.
        val looping = text.isNotBlank() && KbReplyText.degenerateRepetition(text)?.startsWith("repeat:") == true
        Log.i(TAG, "AROGYA_LOCAL_VISION what=$what ok=${text.isNotBlank() && !looping} chars=${text.length} timedOut=$timedOut looping=$looping ms=${System.currentTimeMillis() - t}")
        if (timedOut || looping) return null
        return text.takeIf { it.length >= (if (what == "count") 1 else 8) }?.take(2_000)
    }
}
