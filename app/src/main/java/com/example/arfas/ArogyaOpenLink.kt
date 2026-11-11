package com.example.arfas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * 11 Nov 2026 (AL): one way to open an official guideline link from Ask SOP ("📄 Open PDF", "Open official page").
 * Before, a failure only showed "No browser or PDF viewer is available" and nothing was logged, so a link that
 * did not open could not be diagnosed. Now: the link is opened with a plain view intent in a new task (works from
 * dialogs and sheets); if no app takes it, the link without the "#page=" part is tried (some PDF apps refuse a
 * fragment); if that fails too, the link is copied so it can be pasted into a browser. Every attempt is logged
 * (`AROGYA_OPEN_LINK`).
 */
object ArogyaOpenLink {
    private const val TAG = "ArogyaOpenLink"

    /** The link without its "#…" part (the page jump is a viewer hint, not part of the address). */
    fun withoutFragment(url: String): String = url.substringBefore('#')

    fun open(context: Context, url: String, hindi: Boolean = false): Boolean {
        val candidates = listOf(url, withoutFragment(url)).filter { it.startsWith("http") }.distinct()
        for (u in candidates) {
            val ok = runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrElse { e ->
                Log.w(TAG, "AROGYA_OPEN_LINK failed url=$u ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                false
            }
            if (ok) { Log.i(TAG, "AROGYA_OPEN_LINK ok url=$u"); return true }
        }
        runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("guideline", withoutFragment(url)))
        }
        Log.w(TAG, "AROGYA_OPEN_LINK none url=$url copied=true")
        Toast.makeText(context,
            if (hindi) "लिंक नहीं खुला — लिंक कॉपी कर दिया है, इसे ब्राउज़र में पेस्ट करें।"
            else "Could not open the link — it has been copied; paste it into your browser.",
            Toast.LENGTH_LONG).show()
        return false
    }
}
