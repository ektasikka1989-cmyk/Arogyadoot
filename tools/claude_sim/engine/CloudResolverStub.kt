package com.example.arfas
/** Test stand-in for the online client: the engine only reads these two fields (streaming started, last word time). */
open class PocketRagCloudResolver { @Volatile var didStream: Boolean = false; @Volatile var lastDeltaMs: Long = 0L }
/** Test stand-in: the engine only uses the runtime's Provider enum (same values as the app). */
class AndroBaselineRuntime { enum class Provider { LOCAL, CLOUD, NONE } }
/** Same shape as the app's AndroConversationStore.AndroChatMessage (used by the session store). */
data class AndroChatMessage(val id: Long, val role: String, val content: String, val timestampMs: Long)
