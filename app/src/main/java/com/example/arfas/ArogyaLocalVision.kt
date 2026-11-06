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

    private const val DESCRIBE_PROMPT =
        "List what is visible in this photo as short factual lines: what it shows (body part, object, document or medicine), " +
            "colour, size and shape, marks, swelling, wounds, rash or discharge, and any readable text. " +
            "Do not diagnose and do not give advice. Write 'unclear' for anything you cannot see well."

    private const val TRANSCRIBE_PROMPT =
        "Copy the writing on this card or register exactly, one row per line, keeping each date or value on the same line as its label. " +
            "Write [unclear] for anything you cannot read. Do not add, guess or explain anything."

    fun ready(lm: LocalModelManager?): Boolean = lm?.isReadyForVision == true

    /** Factual description of a photo for the grounded answer, or null when vision is unavailable or fails. */
    suspend fun describe(lm: LocalModelManager?, bitmap: Bitmap): String? = run(lm, bitmap, DESCRIBE_PROMPT, 320, "describe")

    /** Transcription of a card/register photo (fallback when OCR read nothing), or null. */
    suspend fun transcribe(lm: LocalModelManager?, bitmap: Bitmap): String? = run(lm, bitmap, TRANSCRIBE_PROMPT, 700, "transcribe")

    private suspend fun run(lm: LocalModelManager?, bitmap: Bitmap, prompt: String, maxTokens: Int, what: String): String? {
        if (lm == null || !lm.isReadyForVision || bitmap.isRecycled || bitmap.width <= 0) return null
        val t = System.currentTimeMillis()
        val r = runCatching {
            VisionWorkCoordinator.withVisionWork("arogya_local_vision_$what") {
                lm.inferVisionImage(bitmap = bitmap, userQueryOnly = prompt, history = emptyList(),
                    maxOutputToken = maxTokens, taskRole = VisionTaskRole.OBSERVER)
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        val text = r?.takeIf { it.completed }?.text?.trim().orEmpty()
        Log.i(TAG, "AROGYA_LOCAL_VISION what=$what ok=${text.isNotBlank()} chars=${text.length} ms=${System.currentTimeMillis() - t}")
        return text.takeIf { it.length >= 8 }?.take(2_000)
    }
}
