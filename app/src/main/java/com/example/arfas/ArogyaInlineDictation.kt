package com.example.arfas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
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
 * ChatGPT-style inline dictation: tap the mic, speak, and the words appear in the text box.
 * Uses the on-phone engine ([AndroWhisperRecognizer] streaming + [AndroAudioRecorder]); no popup,
 * no cloud, and it NEVER submits anything by itself (submitting while a Gemma decode is being
 * cancelled is what exposed the slot leak — see EMERGENCY_REGRESSION_RCA).
 *
 * Only one dictation runs at a time: starting one cancels any other, so speech can never land in
 * the wrong field. Leaving the screen releases the microphone and Whisper.
 */
class InlineDictationController(private val appContext: Context) {
    enum class State { IDLE, LISTENING, WORKING, ERROR }

    var state by mutableStateOf(State.IDLE); private set
    var errorText by mutableStateOf(""); private set
    /** Live words heard so far in the current session (already cleaned). */
    var partialText by mutableStateOf(""); private set
    val isActive: Boolean get() = state == State.LISTENING || state == State.WORKING

    private var job: Job? = null
    @Volatile private var recorder: AndroAudioRecorder? = null
    @Volatile private var finishRequested = false

    /**
     * Tap toggles: idle → listen; listening/working → stop and transcribe now.
     * [onPartial]/[onFinal] run on the main thread with cleaned text; [voiceChoice] is an [ArogyaLang] VOICE_* value.
     */
    fun toggle(
        scope: CoroutineScope, voiceChoice: String, hindi: Boolean,
        maxSeconds: Int = 60,
        onPartial: (String) -> Unit = {}, onFinal: (String) -> Unit, onError: (String) -> Unit = {}
    ) {
        if (isActive) { stop(); return }
        fun tr(en: String, hi: String) = if (hindi) hi else en
        errorText = ""
        partialText = ""
        active.getAndSet(this)?.let { if (it !== this) it.cancel() }
        finishRequested = false
        state = State.LISTENING
        val recognizer = AndroWhisperRecognizer(appContext)
        suspend fun fail(msg: String) {
            withContext(Dispatchers.Main) { errorText = msg; partialText = ""; state = State.ERROR; onError(msg) }
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
                        val p = ArogyaDictationText.clean(runCatching { session.partial() }.getOrDefault(""))
                        if (p.isNotBlank()) withContext(Dispatchers.Main) { partialText = p; onPartial(p) }
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
                if (!heardSpeech) { fail(tr("No speech heard. Tap the mic and speak.", "कुछ सुनाई नहीं दिया। माइक दबाकर बोलें।")); return@launch }
                withContext(Dispatchers.Main) { state = State.WORKING }
                val t = ArogyaDictationText.clean(withContext(Dispatchers.Default) {
                    if (voiceChoice == ArogyaLang.VOICE_AUTO) session.finish()
                    else session.finishWithLanguage(ArogyaLang.whisperLang(voiceChoice))
                })
                Log.i(TAG, "AROGYA_DICTATION_DONE chars=${t.length}")
                withContext(Dispatchers.Main) {
                    partialText = ""
                    state = State.IDLE
                    if (t.isNotBlank()) onFinal(t)
                    else { val m = tr("No speech recognised.", "कुछ समझ नहीं आया।"); errorText = m; state = State.ERROR; onError(m) }
                }
            } catch (e: CancellationException) {
                state = State.IDLE
                partialText = ""
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AROGYA_DICTATION_EXCEPTION ${e.javaClass.simpleName}")
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

    /** Abandon the session: nothing more is inserted. */
    fun cancel() {
        job?.cancel()
        runCatching { recorder?.stop() }
        recorder = null
        partialText = ""
        if (state == State.LISTENING || state == State.WORKING) state = State.IDLE
    }

    fun release() = cancel()

    /** Surface a microphone-permission denial as an inline error. */
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

/** Runs [onGranted] now if the mic permission is held, else asks first. */
@Composable
private fun rememberMicGate(controller: InlineDictationController, hindi: Boolean, onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onGranted() else controller.permissionDenied(hindi)
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) onGranted()
        else runCatching { ask.launch(Manifest.permission.RECORD_AUDIO) }
    }
}

/** Mic icon for a text field: 🎤 idle, ■ while listening/transcribing (tap = stop). */
@Composable
fun DictationMicButton(
    controller: InlineDictationController, hindi: Boolean, enabled: Boolean = true,
    modifier: Modifier = Modifier, onStart: () -> Unit
) {
    val gate = rememberMicGate(controller, hindi, onStart)
    val listening = controller.isActive
    IconButton(onClick = { if (listening) controller.stop() else gate() }, enabled = enabled, modifier = modifier) {
        Text(
            if (listening) "■" else "🎤",
            color = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** One small line under a field: listening / transcribing / error. Nothing when idle. */
@Composable
fun DictationStatusLine(controller: InlineDictationController, hindi: Boolean, modifier: Modifier = Modifier) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    when {
        controller.state == InlineDictationController.State.LISTENING -> Text(tr("● Listening… tap ■ to stop", "● सुन रहे हैं… रोकने के लिए ■ दबाएँ"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = modifier)
        controller.state == InlineDictationController.State.WORKING -> Text(tr("Writing what you said…", "आपकी बात लिख रहे हैं…"),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = modifier)
        controller.state == InlineDictationController.State.ERROR && controller.errorText.isNotBlank() -> Text(controller.errorText,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = modifier)
    }
}

/**
 * Text-field state that accepts dictation at the cursor. Partials re-apply from the snapshot taken
 * when recording started, so they never duplicate; the final transcript replaces the partial.
 * Typing while listening stops the session (typed words win; text so far stays).
 */
class DictatingField internal constructor(
    val controller: InlineDictationController,
    private val scope: CoroutineScope,
    private val context: Context,
    private val hindi: Boolean,
    private val onValue: (String) -> Unit
) {
    var tfv by mutableStateOf(TextFieldValue(""))
    private var base: TextFieldValue? = null

    private fun apply(dictated: String) {
        val b = base ?: return
        val e = ArogyaDictationText.insertAtCursor(b.text, b.selection.start, b.selection.end, dictated)
        tfv = TextFieldValue(e.text, TextRange(e.selStart))
        onValue(e.text)
    }

    /** Wire to the text field's onValueChange. */
    fun onUserEdit(v: TextFieldValue) {
        if (controller.isActive) { controller.cancel(); base = null }
        tfv = v
        onValue(v.text)
    }

    /** Wire to [DictationMicButton.onStart]. */
    fun start() {
        base = tfv
        controller.toggle(scope, ArogyaLang.voiceLang(context), hindi,
            onPartial = { apply(it) },
            onFinal = { apply(it); base = null },
            onError = { base = null })
    }

    internal fun syncFromParent(value: String) {
        if (value != tfv.text && !controller.isActive) tfv = TextFieldValue(value, TextRange(value.length))
    }
}

@Composable
fun rememberDictatingField(value: String, hindi: Boolean, onValue: (String) -> Unit): DictatingField {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = rememberInlineDictationController()
    val latestOnValue = androidx.compose.runtime.rememberUpdatedState(onValue)
    val field = remember { DictatingField(controller, scope, context.applicationContext, hindi) { latestOnValue.value(it) } }
    // Parent is the source of truth when not dictating: clears after send, record loads and the
    // caller's own filters (digits-only phone, length caps) show exactly as before.
    androidx.compose.runtime.SideEffect { field.syncFromParent(value) }
    return field
}

/**
 * A big "speak" button for record-then-act flows (visit note → AI organise, guided form fill, stock).
 * No popup: the words appear live under the button; when the ASHA taps ■ the final text goes to
 * [onFinal]. Nothing happens if nothing was said.
 */
@Composable
fun InlineSpeakButton(
    label: String, hindi: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier,
    onFinal: (String) -> Unit
) {
    fun tr(en: String, hi: String) = if (hindi) hi else en
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = rememberInlineDictationController()
    val latest = androidx.compose.runtime.rememberUpdatedState(onFinal)
    val gate = rememberMicGate(controller, hindi) {
        controller.toggle(scope, ArogyaLang.voiceLang(context), hindi, onFinal = { latest.value(it) })
    }
    Column(modifier) {
        val listening = controller.isActive
        Button(
            onClick = { if (listening) controller.stop() else gate() },
            enabled = enabled || listening,
            colors = if (listening) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) {
            Text(
                when (controller.state) {
                    InlineDictationController.State.LISTENING -> tr("■  Stop — I'm listening", "■  रोकें — सुन रहे हैं")
                    InlineDictationController.State.WORKING -> tr("Writing what you said…", "लिख रहे हैं…")
                    else -> label
                },
                fontWeight = FontWeight.Bold
            )
        }
        if (controller.partialText.isNotBlank()) Text("“" + controller.partialText + "”",
            style = MaterialTheme.typography.bodyMedium)
        DictationStatusLine(controller, hindi)
    }
}
