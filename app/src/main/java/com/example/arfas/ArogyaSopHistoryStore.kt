package com.example.arfas

import android.content.Context

/** 9 Nov 2026: Ask SOP history on the phone (SharedPreferences), the last [ArogyaSopHistory.MAX] questions. */
object ArogyaSopHistoryStore {
    private const val PREFS = "arogya_sop_history"
    private const val KEY = "items"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(c: Context): List<ArogyaSopHistory.Item> =
        runCatching { ArogyaSopHistory.decode(prefs(c).getString(KEY, null)) }.getOrDefault(emptyList())

    /** Records one answered question (the same question asked again replaces its older entry). */
    fun add(c: Context, msg: SopChatMsg) {
        val r = msg.result ?: return
        if (r.sourceOnly || r.safetyPrompt || r.text.isBlank()) return
        val item = ArogyaSopHistory.Item(
            id = msg.id, question = msg.question, asked = msg.asked, text = r.text, provider = r.provider,
            elapsedMs = r.elapsedMs, online = r.online, quote = r.quote, quoteSource = r.quoteSource, tier = r.tier,
            sources = r.sources, onlineText = r.onlineText
        )
        runCatching { prefs(c).edit().putString(KEY, ArogyaSopHistory.encode(ArogyaSopHistory.add(list(c), item))).apply() }
    }

    fun clear(c: Context) { runCatching { prefs(c).edit().remove(KEY).apply() } }

    /** A saved entry as a chat message (answer text, the guide sentence and its sources). */
    fun toMsg(i: ArogyaSopHistory.Item): SopChatMsg = SopChatMsg(
        i.id, i.question,
        ArogyaAiResult(i.text, i.provider, i.elapsedMs, sources = i.sources, online = i.online, quote = i.quote,
            quoteSource = i.quoteSource, tier = i.tier, onlineText = i.onlineText),
        i.asked
    )
}
