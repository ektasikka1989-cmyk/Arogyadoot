package com.example.arfas

/**
 * 9 Nov 2026: one shared count of what this phone sent to Groq in the last minute. The free tier allows
 * 8,000 tokens per minute, counted as prompt + max_tokens; every request over it is refused and the feature
 * quietly fell back to the phone (family explanation or "More detail" right after an online answer, long
 * Hindi forms). Requests now ask only for what fits ([cap]) and wait briefly when the minute is used up
 * ([waitMs]). Pure Kotlin, host-tested.
 */
object ArogyaGroqMinute {
    /** Same headroom as GroqChatProvider.REQUEST_TOKEN_BUDGET under the 8,000 limit. */
    const val PER_MINUTE = 7_600
    /** Longest wait before trying anyway (then the phone takes over as before). */
    const val MAX_WAIT_MS = 20_000L
    const val MIN_OUTPUT = 256
    private const val WINDOW_MS = 60_000L
    private val sent = ArrayDeque<Pair<Long, Int>>()

    /** Output tokens that fit one request under the per-minute limit after its prompt. */
    fun cap(output: Int, promptTokens: Int): Int =
        output.coerceAtMost((PER_MINUTE - promptTokens).coerceAtLeast(MIN_OUTPUT)).coerceAtLeast(MIN_OUTPUT)

    /** Milliseconds until [tokens] fit in the last minute's allowance (0 = now). */
    @Synchronized fun waitMs(tokens: Int, now: Long = System.currentTimeMillis()): Long {
        prune(now)
        var free = PER_MINUTE - sent.sumOf { it.second }
        if (tokens <= free) return 0L
        for ((at, n) in sent) {
            free += n
            if (tokens <= free) return (at + WINDOW_MS - now).coerceAtLeast(0L)
        }
        return WINDOW_MS
    }

    @Synchronized fun record(tokens: Int, now: Long = System.currentTimeMillis()) {
        prune(now); if (tokens > 0) sent.addLast(now to tokens)
    }

    @Synchronized fun reset() { sent.clear() }

    private fun prune(now: Long) { while (sent.isNotEmpty() && now - sent.first().first >= WINDOW_MS) sent.removeFirst() }
}
