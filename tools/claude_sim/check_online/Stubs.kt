package com.example.arfas
/** Test stand-ins: the runtime's Provider enum, the chat message shape, and a Groq client that records what it is sent. */
class AndroBaselineRuntime { enum class Provider { LOCAL, CLOUD, NONE } }
data class AndroChatMessage(val id: Long, val role: String, val content: String, val timestampMs: Long)
open class GroqChatProvider {
    var lastQuery = ""; var lastMax = 0; var lastHistoryChars = 0
    var reply: String = "Answer: Danger signs include fast breathing [S1]."
    open suspend fun chatGenericKnowledgeText(messages: List<Pair<String, String>>, currentQuery: String, safetyCritical: Boolean = false,
                                              maxOutputTokens: Int = 896, onDelta: ((String) -> Unit)? = null): String {
        lastQuery = currentQuery; lastMax = maxOutputTokens; lastHistoryChars = messages.sumOf { it.second.length }
        onDelta?.invoke(reply); return reply
    }
}
