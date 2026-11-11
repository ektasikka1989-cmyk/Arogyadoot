package com.example.arfas

import org.json.JSONArray
import org.json.JSONObject

/**
 * 9 Nov 2026: Ask SOP history — the last [MAX] questions with their answers, kept on the phone (pure Kotlin
 * codec, host-tested; the SharedPreferences wrapper is ArogyaSopHistoryStore). Ask SOP opens on a clean
 * screen; earlier questions are reopened from the ☰ history panel.
 */
object ArogyaSopHistory {
    const val MAX = 10
    /** Passage text kept per source (the full excerpt can be long). */
    private const val EXCERPT_CHARS = 1_500

    data class Item(
        val id: Long,
        val question: String,
        val asked: String,
        val text: String,
        val provider: String,
        val elapsedMs: Long,
        val online: Boolean,
        val quote: String = "",
        val quoteSource: String = "",
        val tier: Int = 1,
        val sources: List<ArogyaSource> = emptyList(),
        val onlineText: String = ""
    )

    /** The newest last; asking the same question again replaces its older entry; at most [MAX]. */
    fun add(items: List<Item>, item: Item): List<Item> =
        (items.filterNot { it.question.trim().equals(item.question.trim(), ignoreCase = true) } + item).takeLast(MAX)

    fun encode(items: List<Item>): String = JSONArray().apply {
        items.takeLast(MAX).forEach { i ->
            put(JSONObject().apply {
                put("id", i.id); put("q", i.question); put("asked", i.asked); put("text", i.text)
                put("provider", i.provider); put("ms", i.elapsedMs); put("online", i.online)
                put("quote", i.quote); put("quoteSource", i.quoteSource); put("tier", i.tier); put("onlineText", i.onlineText)
                put("sources", JSONArray().apply {
                    i.sources.forEach { s ->
                        put(JSONObject().apply {
                            put("id", s.id); put("title", s.title); put("locator", s.locator)
                            put("excerpt", s.excerpt.take(EXCERPT_CHARS)); put("url", s.url); put("kind", s.kind); put("date", s.date)
                        })
                    }
                })
            })
        }
    }.toString()

    /** Unreadable or missing data gives an empty history (never a crash). */
    fun decode(json: String?): List<Item> = runCatching {
        if (json.isNullOrBlank()) return emptyList()
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { k ->
            val o = a.optJSONObject(k) ?: return@mapNotNull null
            val q = o.optString("q").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val src = o.optJSONArray("sources")
            Item(
                id = o.optLong("id"), question = q, asked = o.optString("asked"), text = o.optString("text"),
                provider = o.optString("provider"), elapsedMs = o.optLong("ms"), online = o.optBoolean("online"),
                quote = o.optString("quote"), quoteSource = o.optString("quoteSource"), tier = o.optInt("tier", 1),
                onlineText = o.optString("onlineText"),
                sources = if (src == null) emptyList() else (0 until src.length()).mapNotNull { j ->
                    val s = src.optJSONObject(j) ?: return@mapNotNull null
                    ArogyaSource(s.optString("id"), s.optString("title"), s.optString("locator"), s.optString("excerpt"), s.optString("url"),
                        kind = s.optString("kind", "guide"), date = s.optString("date"))
                }
            )
        }.takeLast(MAX)
    }.getOrDefault(emptyList())
}
