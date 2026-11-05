package com.example.arfas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ChatGPT-style inline dictation: one reusable controller for every microphone text field.
 * Reuses the working on-phone engine ([AndroWhisperRecognizer] streaming + [AndroAudioRecorder]);
 * no popup, no cloud, never auto-submits. Partials re-apply from the snapshot taken when recording
 * starts, so they can never duplicate; the final transcript replaces the partial.
 *
 * Lifecycle policy: starting one controller stops any other field's session (else speech could land
 * in the wrong field); disposing the field releases the mic + Whisper. Typing while listening is
 * the caller's signal to stop (see [InlineMicField] wiring notes at call sites).
 */
class InlineDictationController(private val appContext: Context) {
    enum class State { IDLE, LISTENING, WORKING, ERROR }

    var state by mutableStateOf(State.IDLE); private set
    var errorText by mutableStateOf(""); private set
    val isActive: Boolean get() = state == State.LISTENING || state == State.WORKING

    private var job: Job? = null
    @Volatile private var recorder: AndroAudioRecorder? = null
    @Volatile private var finishRequested = false

    /**
     * Tap toggles: idle → listen; listening/working → stop and transcribe now.
     * [onPartial]/[onFinal] run on the main thread; [voiceChoice] is an [ArogyaLang] VOICE_* value.
     */
    fun toggle(
        scope: CoroutineScope, voiceChoice: String, hindi: Boolean,
        maxSeconds: Int = 60,
        onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit = {}
    ) {
        if (isActive) { stop(); return }
        fun tr(en: String, hi: String) = if (hindi) hi else en
        errorText = ""
        active.getAndSet(this)?.let { if (it !== this) it.cancel() }
        finishRequested = false
        state = State.LISTENING
        val recognizer = AndroWhisperRecognizer(appContext)
        suspend fun fail(msg: String) {
            withContext(Dispatchers.Main) { errorText = msg; state = State.ERROR; onError(msg) }
            Log.i(TAG, "AROGYA_DICTATION_ERROR $msg")
        }
        job = scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { recognizer.ensureInitialized() }
                if (!ok) { fail(tr("Speech model unavailable. Type instead.", "बोलने वाला मॉडल नहीं है। लिखें।")); return@launch }
                val session = recognizer.newSession(
                    language = ArogyaLang.whisperLang(voiceChoice), maxSeconds = maxSeconds, streaming = true)
                val audio = AndroAudioRecorder()
                recorder = audio
                if (!audio.start()) { fail(tr("Microphone unavailable. Type instead.", "माइक नहीं है। लिखें।")); return@launch }
                Log.i(TAG, "AROGYA_DICTATION_START lang=$voiceChoice")
                var heardSpeech = false
                val poller = launch(Dispatchers.Default) {
                    while (isActive && !finishRequested) {
                        delay(PARTIAL_MS)
                        val p = runCatching { session.partial() }.getOrDefault("").trim()
                        if (p.isNotBlank()) withContext(Dispatchers.Main) { onPartial(p) }
                    }
                }
                try {
                    withTimeoutOrNull(maxSeconds * 1000L) {
                        for (chunk in audio.chunks) {
                            if (finishRequested) break
                            session.append(chunk)
                            if (!heardSpeech && chunk.any { abs(it) > SPEECH_FLOOR }) heardSpeech = true
                        }
                    }
                } finally {
                    runCatching { poller.cancelAndJoin() }
                    audio.stop()
                }
                if (!heardSpeech) { fail(tr("No speech heard.", "कुछ सुनाई नहीं दिया।")); return@launch }
                state = State.WORKING
                val t = withContext(Dispatchers.Default) {
                    if (voiceChoice == ArogyaLang.VOICE_AUTO) session.finish()
                    else session.finishWithLanguage(ArogyaLang.whisperLang(voiceChoice))
                }.trim()
                Log.i(TAG, "AROGYA_DICTATION_DONE chars=${t.length}")
                withContext(Dispatchers.Main) {
                    state = State.IDLE
                    if (t.isNotBlank()) onFinal(t)
                    else { val m = tr("No speech recognised.", "कुछ समझ नहीं आया।"); errorText = m; state = State.ERROR; onError(m) }
                }
            } catch (e: CancellationException) {
                state = State.IDLE
                throw e
            } catch (e: Exception) {
                fail(tr("Voice unavailable. You can type instead.", "आवाज़ नहीं चली। लिखें।"))
            } finally {
                runCatching { recorder?.stop() }
                recorder = null
                withContext(NonCancellable + Dispatchers.IO) { runCatching { recognizer.release() } }
                active.compareAndSet(this@InlineDictationController, null)
                if (state == State.LISTENING || state == State.WORKING) state = State.IDLE
            }
        }
    }

    /** Stop recording and transcribe what was heard. */
    fun stop() {
        finishRequested = true
        recorder?.stop()
    }

    /** Abandon the session: nothing is inserted. */
    fun cancel() {
        job?.cancel()
        runCatching { recorder?.stop() }
        recorder = null
        if (state == State.LISTENING || state == State.WORKING) state = State.IDLE
    }

    fun release() = cancel()

    /** Surface a microphone-permission denial as a field error. */
    fun permissionDenied(hindi: Boolean) {
        errorText = if (hindi) "माइक की अनुमति नहीं मिली — सेटिंग में दें।" else "Microphone permission denied — allow it in Settings."
        state = State.ERROR
    }

    companion object {
        private const val TAG = "ArogyaDemo"
        private const val PARTIAL_MS = 1200L
        private const val SPEECH_FLOOR = 0.02f
        private val active = AtomicReference<InlineDictationController?>(null)
    }
}

/** One controller per field; leaving the screen releases the mic + Whisper. */
@Composable
fun rememberInlineDictationController(): InlineDictationController {
    val context = LocalContext.current
    val c = remember { InlineDictationController(context.applicationContext) }
    DisposableEffect(c) { onDispose { c.release() } }
    return c
}

/**
 * Mic button with its own permission ask: idle 🎤, listening/working ■ (tap = stop), error ⓘ.
 * Call [controller.toggle] from [onStart] after this button grants permission.
 */
@Composable
fun DictationMicButton(
    controller: InlineDictationController, hindi: Boolean, enabled: Boolean = true,
    modifier: Modifier = Modifier, onStart: () -> Unit
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStart() else controller.permissionDenied(hindi)
    }
    val listening = controller.isActive
    IconButton(
        onClick = {
            if (listening) { controller.stop(); return@IconButton }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) onStart()
            else runCatching { ask.launch(Manifest.permission.RECORD_AUDIO) }
        },
        enabled = enabled, modifier = modifier
    ) {
        Text(
            if (listening) "■" else "🎤",
            color = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
