package com.example.arfas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Photos read the way a person reads them. With signal and a Groq key, Qwen (the cloud model) looks at
 * the whole photo (handwriting, tables without headings, a name written on two lines, any layout) and writes
 * what is on it as plain text. ArogyaDoot's own readers then turn that text into fields exactly as they do with the
 * phone's ML Kit text, so the same checks and the ASHA's review apply.
 *
 * Order follows the one Settings choice: Cloud first → cloud vision first; Local first → on-phone reading only. No signal / no key / slow → the phone's reading (ML Kit + rules + phone model).
 * Aadhaar numbers are blacked out on the phone before the photo is sent and never come back from the cloud.
 */
object ArogyaPhotoVision {
    private const val TAG = "ArogyaDemo"
    const val LABEL = "Qwen3.8 27B (Groq, online): read the photo"

    // ── Android side ──

    /** Whether to send this photo: Cloud first + a usable cloud text endpoint (signal + key). */
    suspend fun wanted(context: Context, phoneReadingGood: Boolean): Boolean = withContext(Dispatchers.IO) {
        ArogyaCloud.cloudFirst(context) && runCatching { ArogyaCloud.textChoice(context).let { it.first != null && it.second.isNotBlank() } }.getOrDefault(false)
    }

    private fun blackout(bmp: Bitmap, boxes: List<ArogyaTableOcr.Line>): Bitmap {
        if (boxes.isEmpty()) return bmp
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out); val p = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        boxes.forEach { b -> val pad = (b.h / 2).coerceAtLeast(4); c.drawRect((b.left - pad).toFloat(), (b.top - pad).toFloat(), (b.right + pad).toFloat(), (b.bottom + pad).toFloat(), p) }
        return out
    }

    /**
     * The photo's text as the cloud model reads it, or null (no signal, no key, slow, nothing read). [lines] are the phone's
     * ML Kit lines for the same bitmap: they locate the Aadhaar numbers to black out before sending.
     */
    suspend fun transcribe(context: Context, bmp: Bitmap, lines: List<ArogyaTableOcr.Line>, kind: ArogyaPhotoText.Kind): String? = withContext(Dispatchers.IO) {
        val t = System.currentTimeMillis()
        val hidden = if (kind == ArogyaPhotoText.Kind.MEDICINE) emptyList() else ArogyaPhotoText.hiddenBoxes(lines)
        val safe = blackout(bmp, hidden)
        val scale = 1600f / maxOf(safe.width, safe.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(safe, (safe.width * scale).toInt(), (safe.height * scale).toInt(), true) else safe
        val jpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        val (ep, key) = ArogyaCloud.textChoice(context)
        if (ep == null || key.isBlank()) return@withContext null
        val imageUrl = "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val raw = ArogyaCloud.withDeadline(ArogyaVisitAi.CLOUD_DEADLINE_MS, "photo_vision") {
            OpenAiCompatibleClient(ep, { key }, ArogyaVisitAi.CLOUD_DEADLINE_MS.toInt()).complete(ArogyaPhotoText.SYSTEM, ArogyaPhotoText.prompt(kind), 900, listOf(imageUrl))
        }
        val text = ArogyaPhotoText.clean(raw)
        Log.i(TAG, "AROGYA_PHOTO_VISION kind=$kind ok=${text != null} chars=${text?.length ?: 0} aadhaar_hidden=${hidden.size} ms=${System.currentTimeMillis() - t}")
        text
    }
}
