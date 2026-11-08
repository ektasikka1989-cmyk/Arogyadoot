package com.example.arfas

import android.content.Context
import android.util.Log
import io.androllm.core.whisper.WhisperContext
import io.androllm.core.whisper.WhisperCpuConfig
import io.androllm.core.whisper.WhisperParams
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Thin FixSight adapter around AndroLLM's whisper.cpp engine.
 *
 * Model resolution is capability-based, not domain-based. Any valid ggml Whisper
 * model in filesDir/whisper is accepted; an optional bundled model can live at
 * assets/voice/stt/whisper/model.bin. The native context is cached across turns.
 */
class AndroWhisperRecognizer(private val context: Context) {
    private val initMutex = Mutex()
    @Volatile private var whisper: WhisperContext? = null
    @Volatile private var modelLabel: String = ""

    val initialized: Boolean get() = whisper?.isReady == true
    val activeModel: String get() = modelLabel

    suspend fun ensureInitialized(): Boolean {
        whisper?.let { if (it.isReady) return true }
        return initMutex.withLock {
            whisper?.let { if (it.isReady) return@withLock true }
            val file = resolveModelFile()
            try {
                val ctx = when {
                    file != null -> WhisperContext.fromFile(file.absolutePath).also { modelLabel = file.name }
                    assetExists(BUNDLED_MODEL_ASSET) -> WhisperContext.fromAsset(context.assets, BUNDLED_MODEL_ASSET).also {
                        modelLabel = BUNDLED_MODEL_ASSET.substringAfterLast('/')
                    }
                    else -> {
                        Log.w(TAG, "WHISPER_MODEL_MISSING dir=${File(context.filesDir, "whisper").absolutePath}")
                        return@withLock false
                    }
                }
                whisper = ctx
                Log.i(TAG, "WHISPER_READY model=$modelLabel bytes=${file?.length() ?: -1L} threads=${WhisperCpuConfig.preferredThreadCount}")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "WHISPER_INIT_FAILED ${t.javaClass.simpleName}: ${t.message}", t)
                whisper = null
                false
            }
        }
    }

    fun newSession(
        language: String = "auto",
        maxSeconds: Int = 30,
        streaming: Boolean = true,
        translate: Boolean = false,
        numThreads: Int = -1,
        beamSize: Int = 1,
        temperature: Float = 0f
    ): Session {
        val ctx = whisper ?: error("Whisper not initialized")
        return Session(
            context = ctx,
            language = language,
            maxSeconds = maxSeconds,
            streaming = streaming,
            translate = translate,
            numThreads = numThreads,
            beamSize = beamSize,
            temperature = temperature
        )
    }

    suspend fun release() {
        initMutex.withLock {
            runCatching { whisper?.release() }
            whisper = null
            modelLabel = ""
        }
    }

    private fun resolveModelFile(): File? {
        val dir = File(context.filesDir, "whisper")
        if (!dir.exists()) return null
        // Prefer an explicitly selected model. If none is selected, use a deterministic
        // installed model name; never silently switch to the smallest/largest model because
        // that changes recognition quality and latency without the user choosing it.
        val preferred = context.getSharedPreferences("fixsight_voice", Context.MODE_PRIVATE)
            .getString("whisper_model_file", null)
            ?.let { File(dir, it) }
            ?.takeIf { it.isFile && it.length() > 1_000_000 }
        if (preferred != null) return preferred

        val installed = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("bin", true) && it.length() > 1_000_000 }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        // Preserve the conventional model.bin choice when present; otherwise use a
        // deterministic filename order. Model-size changes must be explicit in settings.
        return installed.firstOrNull { it.name.equals("model.bin", ignoreCase = true) }
            ?: installed.firstOrNull()
    }

    private fun assetExists(path: String): Boolean = try {
        context.assets.open(path).use { true }
    } catch (_: Throwable) { false }

    class Session(
        private val context: WhisperContext,
        language: String,
        private val maxSeconds: Int,
        private val streaming: Boolean,
        translate: Boolean,
        numThreads: Int,
        beamSize: Int,
        temperature: Float
    ) {
        private val lock = Any()
        // Plain float buffer (grows as needed). A boxed ArrayList<Float> cost ~16 bytes per
        // sample (≈30 MB for two minutes of speech) and slowed every snapshot.
        private var buf = FloatArray(AndroAudioRecorder.SAMPLE_RATE * 10)
        private var size = 0
        private val cancelled = AtomicBoolean(false)
        @Volatile private var partialRunning: Boolean = false
        /** Auto mode: Hindi or English, decided from the audio (never Urdu script). */
        @Volatile private var autoLanguage: String? = null
        private val params = WhisperParams(
            language = language,
            translate = translate,
            numThreads = if (numThreads < 0) WhisperCpuConfig.preferredThreadCount else numThreads,
            beamSize = beamSize.coerceIn(1, 5),
            temperature = temperature.coerceIn(0f, 1f)
        )

        val sampleCount: Int get() = synchronized(lock) { size }
        val durationMs: Long get() = sampleCount * 1000L / AndroAudioRecorder.SAMPLE_RATE

        fun append(chunk: FloatArray) {
            if (cancelled.get()) return
            synchronized(lock) {
                val maxSamples = maxSeconds * AndroAudioRecorder.SAMPLE_RATE
                val n = minOf((maxSamples - size).coerceAtLeast(0), chunk.size)
                if (n <= 0) return
                if (size + n > buf.size) buf = buf.copyOf(maxOf(size + n, minOf(buf.size * 2, maxSamples)))
                System.arraycopy(chunk, 0, buf, size, n)
                size += n
            }
        }

        private fun copyRange(from: Int, to: Int): FloatArray = synchronized(lock) {
            val a = from.coerceIn(0, size)
            val b = to.coerceIn(a, size)
            buf.copyOfRange(a, b)
        }

        /** Live preview of the last few seconds (used by the guided demo). */
        suspend fun partial(): String {
            if (!streaming || cancelled.get()) return ""
            if (partialRunning) return ""
            val total = sampleCount
            val window = (PARTIAL_WINDOW_MS * AndroAudioRecorder.SAMPLE_RATE / 1000L).toInt()
            val snapshot = copyRange(total - window, total)
            if (snapshot.size < (AndroAudioRecorder.SAMPLE_RATE * PARTIAL_MIN_MS / 1000L).toInt()) return ""
            partialRunning = true
            return try {
                var language = params.language
                if (language == AUTO) {
                    language = autoLanguage ?: run {
                        val heard = copyRange(0, total)
                        if (heard.size < AndroAudioRecorder.SAMPLE_RATE * AUTO_MIN_MS / 1000L) return ""
                        detectAuto(heard, "WHISPER_LANG_PARTIAL").also { autoLanguage = it }
                    }
                }
                decodeGuarded(snapshot, params.copy(language = language, noContext = true), "WHISPER_PARTIAL", preview = true)
            } finally {
                partialRunning = false
            }
        }

        /**
         * Decodes samples [from, to) on their own — one spoken phrase cut at a natural pause.
         * Inline dictation calls this while the ASHA is still talking, so when she taps ✓ only
         * the last phrase is left to decode. [preview] = a quick look at the phrase still being
         * spoken: it never runs language detection twice and never re-decodes.
         */
        suspend fun decodeRange(from: Int, to: Int, preview: Boolean = false): String {
            if (cancelled.get()) return ""
            val trimmed = trimSilence(copyRange(from, to))
            if (trimmed.size < AndroAudioRecorder.SAMPLE_RATE * MIN_DECODE_MS / 1000L) return ""
            var language = params.language
            if (language == AUTO) {
                val longEnough = trimmed.size >= AndroAudioRecorder.SAMPLE_RATE * AUTO_MIN_MS / 1000L
                // Each full phrase gets its own Hindi/English decision (a speaker may switch);
                // previews and very short phrases reuse the last decision.
                language = if (!preview && longEnough) {
                    detectAuto(trimmed, "WHISPER_LANG_SEGMENT").also { autoLanguage = it }
                } else {
                    autoLanguage ?: detectAuto(trimmed, "WHISPER_LANG_FIRST").also { autoLanguage = it }
                }
            }
            return decodeGuarded(trimmed, params.copy(language = language, noContext = true),
                if (preview) "WHISPER_PREVIEW" else "WHISPER_SEGMENT", preview)
        }

        suspend fun finish(): String =
            // Each accepted voice turn must be decoded independently. Conversation
            // continuity is owned by AndroConversationStore/local/cloud history, not
            // by whisper.cpp's cross-call text context. Keeping Whisper context here
            // can bias a fresh PCM buffer toward the previous utterance (observed:
            // a new "fly kite" turn decoded as the prior "make biryani" turn).
            transcribeFull(params.copy(noContext = true), "WHISPER_FINAL")

        /**
         * Re-decodes the exact same captured utterance with an explicit language hint.
         * Used only when the first multilingual/auto decode switches into a script that
         * is outside FixSight's supported Hindi/English/Hinglish conversation profile.
         * The user's text is never rewritten: either this second Whisper hypothesis is
         * accepted verbatim or the turn is rejected and the user is asked to repeat.
         */
        suspend fun finishWithLanguage(language: String): String =
            transcribeFull(params.copy(language = language, noContext = true), "WHISPER_REDECODE")

        private suspend fun transcribeFull(decodeParams: WhisperParams, logTag: String): String {
            if (cancelled.get()) return ""
            val trimmed = trimSilence(copyRange(0, sampleCount))
            if (trimmed.isEmpty()) return ""
            var useParams = decodeParams
            if (decodeParams.language == AUTO) {
                // Final decision uses the whole utterance (more evidence than the partials had).
                val lang = detectAuto(trimmed, "WHISPER_LANG_FINAL")
                autoLanguage = lang
                useParams = decodeParams.copy(language = lang)
            }
            return decodeGuarded(trimmed, useParams, logTag, preview = false)
        }

        /**
         * Decodes [audio]; Hindi speech must come out in Devanagari, never Urdu script.
         * Arabic-script output is decoded again as Hindi (not for previews), and dropped
         * if it is still Arabic.
         */
        private suspend fun decodeGuarded(audio: FloatArray, decodeParams: WhisperParams, logTag: String, preview: Boolean): String {
            val started = System.currentTimeMillis()
            var useParams = decodeParams
            var text = withContext(Dispatchers.Default) { context.transcribe(audio, useParams).text.trim() }
            if (ArogyaSpeechLang.hasArabicScript(text)) {
                if (preview) return ""
                Log.w(TAG, "WHISPER_ARABIC_SCRIPT language=${useParams.language} -> re-decode as Hindi")
                if (useParams.language != ArogyaSpeechLang.HINDI) {
                    useParams = useParams.copy(language = ArogyaSpeechLang.HINDI)
                    text = withContext(Dispatchers.Default) { context.transcribe(audio, useParams).text.trim() }
                }
                if (ArogyaSpeechLang.hasArabicScript(text)) {
                    Log.w(TAG, "WHISPER_ARABIC_SCRIPT_REMAINS dropped")
                    return ""
                }
            }
            if (!preview) Log.i(
                TAG,
                "$logTag language=${useParams.language} " +
                    "duration=${audio.size * 1000L / AndroAudioRecorder.SAMPLE_RATE}ms " +
                    "inference=${System.currentTimeMillis() - started}ms text='${text.take(120)}'"
            )
            return text
        }

        /** Hindi or English for this audio; Urdu counts as Hindi. Falls back to Hindi. */
        private suspend fun detectAuto(audio: FloatArray, logTag: String): String {
            val started = System.currentTimeMillis()
            val line = runCatching { context.detectLanguage(audio, params.numThreads) }.getOrDefault("")
            val lang = ArogyaSpeechLang.choose(ArogyaSpeechLang.parse(line))
            Log.i(TAG, "$logTag $line -> $lang (${System.currentTimeMillis() - started}ms)")
            return lang
        }

        fun cancel() {
            cancelled.set(true)
            synchronized(lock) { size = 0 }
        }

        private fun trimSilence(data: FloatArray): FloatArray {
            val threshold = 0.008f
            var start = 0
            while (start < data.size && kotlin.math.abs(data[start]) < threshold) start++
            var end = data.size
            while (end > start && kotlin.math.abs(data[end - 1]) < threshold) end--
            if (end <= start) return FloatArray(0)
            start = (start - AndroAudioRecorder.SAMPLE_RATE / 12).coerceAtLeast(0)
            end = (end + AndroAudioRecorder.SAMPLE_RATE / 12).coerceAtMost(data.size)
            return data.copyOfRange(start, end)
        }
    }

    companion object {
        private const val TAG = "AndroWhisperSTT"
        private const val PARTIAL_WINDOW_MS = 3000L
        private const val PARTIAL_MIN_MS = 600L
        /** Speech needed before auto mode picks Hindi or English. */
        private const val AUTO_MIN_MS = 1500L
        /** Shorter clips are not worth a decode (a cough, a click). */
        private const val MIN_DECODE_MS = 300L
        private const val AUTO = "auto"
        const val BUNDLED_MODEL_ASSET = "voice/stt/whisper/model.bin"

        @Volatile private var sharedInstance: AndroWhisperRecognizer? = null

        /**
         * One warm recognizer for inline dictation, kept loaded for the life of the app so the
         * mic starts instantly (loading the model on every tap was the main start-up delay).
         * Never release it from a screen.
         */
        fun shared(context: Context): AndroWhisperRecognizer =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: AndroWhisperRecognizer(context.applicationContext).also { sharedInstance = it }
            }
    }
}
