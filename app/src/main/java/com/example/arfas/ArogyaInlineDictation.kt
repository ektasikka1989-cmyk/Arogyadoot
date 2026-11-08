package com.example.arfas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Claude-style inline dictation: tap the mic, speak, and the words appear in the text box.
 *
 * Fast because the work happens while the ASHA is talking:
 *  - one Whisper model stays loaded for the whole app ([AndroWhisperRecognizer.shared]);
 *  - speech is cut at natural pauses and each phrase is decoded straight away, in order;
 *  - on ✓ only the last phrase is left, so the text is ready about a second later.
 * Language is always auto: Hindi or English per phrase, never Urdu script ([ArogyaSpeechLang]).
 *
 * It NEVER submits anything by itself (submitting while a Gemma decode is being cancelled is
 * what exposed the slot leak — see EMERGENCY_REGRESSION_RCA). Only one dictation runs at a
 * time: starting one cancels any other, so speech can never land in the wrong field.
 */
class InlineDictationController(private val appContext: Context) {
    enum class State { IDLE, LISTENING, WORKING, ERROR }

    var state by mutableStateOf(State.IDLE); private set
    var errorText by mutableStateOf(""); private set
    /** Words heard so far in the current session (finished phrases + the phrase being spoken). */
    var partialText by mutableStateOf(""); private set
    /** Recent voice levels, 0..1, oldest first — drives the waveform. */
    var levels by mutableStateOf(List(WAVE_BARS) { 0f }); private set
    /** Recording time so far. */
    var elapsedMs by mutableStateOf(0L); private set
    val isActive: Boolean get() = state == State.LISTENING || state == State.WORKING

    private var job: Job? = null
    @Volatile private var recorder: AndroAudioRecorder? = null
    @Volatile private var finishRequested = false

    /**
     * Tap toggles: idle → listen; listening → stop and keep the words.
     * [onPartial]/[onFinal] run on the main thread with cleaned text. [voiceChoice] is ignored:
     * the mic always auto-detects Hindi or English (an ASHA may speak either, or Hinglish).
     */
    fun toggle(
        scope: CoroutineScope, voiceChoice: String, hindi: Boolean,
        maxSeconds: Int = 180,
        onPartial: (String) -> Unit = {}, onFinal: (String) -> Unit, onError: (String) -> Unit = {}
    ) {
        if (isActive) { stop(); return }
        fun tr(en: String, hi: String) = if (hindi) hi else en
        errorText = ""
        partialText = ""
        levels = List(WAVE_BARS) { 0f }
        elapsedMs = 0L
        active.getAndSet(this)?.let { if (it !== this) it.cancel() }
        finishRequested = false
        state = State.LISTENING
        val recognizer = AndroWhisperRecognizer.shared(appContext)
        suspend fun fail(msg: String) {
            withContext(Dispatchers.Main) { errorText = msg; partialText = ""; state = State.ERROR; onError(msg) }
            Log.i(TAG, "AROGYA_DICTATION_ERROR $msg")
        }
        job = scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { recognizer.ensureInitialized() }
                if (!ok) { fail(tr("Speech model unavailable. Type instead.", "बोलने वाला मॉडल नहीं है। लिखें।")); return@launch }
                val session = recognizer.newSession(language = "auto", maxSeconds = maxSeconds, streaming = true)
                val audio = AndroAudioRecorder(chunkSamples = CHUNK_SAMPLES)
                recorder = audio
                if (!audio.start()) { fail(tr("Microphone unavailable. Type instead.", "माइक नहीं है। लिखें।")); return@launch }
                Log.i(TAG, "AROGYA_DICTATION_START lang=auto (setting=$voiceChoice ignored)")

                // Finished phrases (in order) + a quick preview of the phrase still being spoken.
                val phrases = ArrayList<String>()
                var preview = ""
                fun publish() {
                    val t = ArogyaDictationText.clean((phrases + preview).joinToString(" "))
                    partialText = t
                    onPartial(t)
                }
                var total = 0                 // samples captured
                var segStart = 0              // start of the phrase being spoken
                var segHasSpeech = false
                var silenceMs = 0L
                var heardSpeech = false
                var pending = 0               // phrases queued or decoding (all on the main thread)
                val queue = Channel<Pair<Int, Int>>(Channel.UNLIMITED)
                val worker = launch {
                    for ((a, b) in queue) {
                        val t = ArogyaDictationText.clean(runCatching { session.decodeRange(a, b) }.getOrDefault(""))
                        if (t.isNotBlank()) phrases += t
                        if (segStart >= b) preview = ""
                        pending--
                        publish()
                    }
                }
                fun cut() {
                    if (total > segStart) { pending++; queue.trySend(segStart to total) }
                    segStart = total
                    segHasSpeech = false
                    silenceMs = 0L
                }
                // Preview of the open phrase, only while no finished phrase is waiting (they come first).
                val previewer = launch {
                    while (isActive && !finishRequested) {
                        delay(PREVIEW_MS)
                        if (pending > 0 || !segHasSpeech) continue
                        val from = maxOf(segStart, total - PREVIEW_MAX_SAMPLES)
                        val start = segStart
                        val p = ArogyaDictationText.clean(runCatching { session.decodeRange(from, total, preview = true) }.getOrDefault(""))
                        if (start == segStart && !finishRequested && p.isNotBlank()) { preview = p; publish() }
                    }
                }
                try {
                    withTimeoutOrNull(maxSeconds * 1000L) {
                        for (chunk in audio.chunks) {
                            if (finishRequested) break
                            session.append(chunk)
                            total += chunk.size
                            val chunkMs = chunk.size * 1000L / AndroAudioRecorder.SAMPLE_RATE
                            elapsedMs += chunkMs
                            var sum = 0f
                            for (x in chunk) sum += x * x
                            val rms = if (chunk.isEmpty()) 0f else sqrt(sum / chunk.size)
                            levels = levels.drop(1) + (sqrt(rms / LEVEL_FULL)).coerceIn(0f, 1f)
                            if (rms > SPEECH_FLOOR) {
                                heardSpeech = true; segHasSpeech = true; silenceMs = 0L
                            } else silenceMs += chunkMs
                            val segMs = (total - segStart) * 1000L / AndroAudioRecorder.SAMPLE_RATE
                            // Cut at a natural pause, or force a cut on very long phrases.
                            if ((segHasSpeech && silenceMs >= PAUSE_MS && segMs >= MIN_PHRASE_MS) || segMs >= MAX_PHRASE_MS) cut()
                            else if (!segHasSpeech && segMs >= MAX_SILENCE_KEEP_MS) { segStart = total; silenceMs = 0L }
                        }
                    }
                } finally {
                    previewer.cancel()
                    audio.stop()
                }
                if (!heardSpeech) {
                    queue.close(); worker.cancel()
                    fail(tr("No speech heard. Tap the mic and speak.", "कुछ सुनाई नहीं दिया। माइक दबाकर बोलें।")); return@launch
                }
                state = State.WORKING
                if (segHasSpeech) cut()
                queue.close()
                worker.join()
                preview = ""
                val t = ArogyaDictationText.clean(phrases.joinToString(" "))
                Log.i(TAG, "AROGYA_DICTATION_DONE phrases=${phrases.size} chars=${t.length} recordedMs=$elapsedMs")
                partialText = ""
                state = State.IDLE
                if (t.isNotBlank()) onFinal(t)
                else { val m = tr("No speech recognised. Try again a little closer.", "कुछ समझ नहीं आया। पास से फिर बोलें।"); errorText = m; state = State.ERROR; onError(m) }
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
                // The shared recognizer stays loaded (instant next start); only this session ends.
                active.compareAndSet(this@InlineDictationController, null)
                if (state == State.LISTENING || state == State.WORKING) state = State.IDLE
            }
        }
    }

    /** ✓ — stop recording and keep the words. */
    fun stop() {
        finishRequested = true
        recorder?.stop()
    }

    /** ✕ — abandon the session: nothing more is inserted. */
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
        const val WAVE_BARS = 32
        private const val CHUNK_SAMPLES = 1600            // 100 ms: smooth waveform
        private const val SPEECH_FLOOR = 0.012f           // RMS above this = voice
        private const val LEVEL_FULL = 0.15f              // RMS shown as a full-height bar
        private const val PAUSE_MS = 600L                 // a pause this long ends a phrase
        private const val MIN_PHRASE_MS = 1200L
        private const val MAX_PHRASE_MS = 15_000L
        private const val MAX_SILENCE_KEEP_MS = 3_000L    // drop long leading silence
        private const val PREVIEW_MS = 900L
        private const val PREVIEW_MAX_SAMPLES = AndroAudioRecorder.SAMPLE_RATE * 8
        private val active = AtomicReference<InlineDictationController?>(null)
    }
}

/** One controller per field; leaving the screen stops the mic. The speech model stays warm. */
@Composable
fun rememberInlineDictationController(): InlineDictationController {
    val context = LocalContext.current
    val c = remember { InlineDictationController(context.applicationContext) }
    DisposableEffect(c) { onDispose { c.release() } }
    // The app loads the shared model at start (MainActivity); this is only a backstop in case
    // that load failed or has not finished — ensureInitialized() returns at once when loaded.
    LaunchedEffect(Unit) {
        delay(1500)
        withContext(Dispatchers.IO) { runCatching { AndroWhisperRecognizer.shared(context).ensureInitialized() } }
    }
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

/** Mic icon for a text field. While recording it becomes a stop icon (= ✓ keep the words). */
@Composable
fun DictationMicButton(
    controller: InlineDictationController, hindi: Boolean, enabled: Boolean = true,
    modifier: Modifier = Modifier, onStart: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val gate = rememberMicGate(controller, hindi) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onStart()
    }
    val listening = controller.isActive
    IconButton(onClick = { if (listening) controller.stop() else gate() }, enabled = enabled, modifier = modifier) {
        Icon(
            if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
            contentDescription = if (listening) (if (hindi) "रोकें" else "Stop") else (if (hindi) "बोलें" else "Speak"),
            tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Under a field: the recording bar while listening (✕ · ● waveform · 0:07 · ✓), a short
 * "finishing" line while the last phrase is written, an error line, or nothing when idle.
 */
@Composable
fun DictationStatusLine(controller: InlineDictationController, hindi: Boolean, modifier: Modifier = Modifier) {
    when {
        controller.state == InlineDictationController.State.LISTENING -> DictationRecordingBar(controller, hindi, modifier)
        controller.state == InlineDictationController.State.WORKING -> DictationFinishingRow(hindi, modifier)
        controller.state == InlineDictationController.State.ERROR && controller.errorText.isNotBlank() -> Text(controller.errorText,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = modifier.padding(top = 4.dp))
    }
}

/** The Claude-style recording bar: ✕ discard · pulsing dot · live waveform · timer · ✓ keep. */
@Composable
fun DictationRecordingBar(controller: InlineDictationController, hindi: Boolean, modifier: Modifier = Modifier) =
    DictationRecordingBarCore(controller, hindi, modifier, tall = false) { controller.cancel() }

@Composable
private fun DictationRecordingBarCore(
    controller: InlineDictationController, hindi: Boolean, modifier: Modifier, tall: Boolean, onDiscard: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val pulse = rememberInfiniteTransition(label = "rec")
    val dotAlpha by pulse.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    val barMax = if (tall) 36.dp else 26.dp
    Row(
        modifier.fillMaxWidth().padding(top = 6.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDiscard() }, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = if (hindi) "रद्द करें" else "Discard",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.padding(start = 2.dp, end = 8.dp).size(10.dp).alpha(dotAlpha).clip(CircleShape)
            .background(MaterialTheme.colorScheme.error))
        Row(Modifier.weight(1f).height(barMax), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            controller.levels.forEach { lvl ->
                val h by animateDpAsState(3.dp + (barMax - 3.dp) * lvl, tween(90), label = "bar")
                Box(Modifier.weight(1f).height(h).clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
        val s = controller.elapsedMs / 1000
        Text("%d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp))
        IconButton(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); controller.stop() },
            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Rounded.Check, contentDescription = if (hindi) "रखें" else "Done",
                tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun DictationFinishingRow(hindi: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(if (hindi) "आख़िरी शब्द लिख रहे हैं…" else "Finishing the last words…",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        val before = tfv
        base = before
        controller.toggle(scope, ArogyaLang.voiceLang(context), hindi,
            onPartial = { apply(it) },
            onFinal = { apply(it); base = null },
            // ✕ or a failed recording puts the field back exactly as it was.
            onError = { restore(before) })
    }

    /** ✕ while recording: drop the dictated words and put the field back as it was. */
    fun discard() {
        val before = base
        controller.cancel()
        if (before != null) restore(before)
    }

    private fun restore(v: TextFieldValue) {
        base = null
        tfv = v
        onValue(v.text)
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

/** Recording bar for a dictating field: ✕ also restores the field's earlier text. */
@Composable
fun DictationStatusLine(field: DictatingField, hindi: Boolean, modifier: Modifier = Modifier) {
    val c = field.controller
    if (c.state == InlineDictationController.State.LISTENING) {
        DictationRecordingBarCore(c, hindi, modifier, tall = false) { field.discard() }
    } else DictationStatusLine(c, hindi, modifier)
}

/**
 * A big "speak" button for record-then-act flows (visit note → AI organise, guided form fill, stock).
 * While recording, the button turns into the recording bar with the words showing live above it;
 * ✓ hands the text to [onFinal], ✕ throws it away. Nothing happens if nothing was said.
 */
@Composable
fun InlineSpeakButton(
    label: String, hindi: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier,
    onFinal: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = rememberInlineDictationController()
    val latest = androidx.compose.runtime.rememberUpdatedState(onFinal)
    val haptic = LocalHapticFeedback.current
    val gate = rememberMicGate(controller, hindi) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        controller.toggle(scope, ArogyaLang.voiceLang(context), hindi, onFinal = { latest.value(it) })
    }
    // Callers still pass "🎤 …" / "● …" labels; the icon replaces the emoji.
    val cleanLabel = label.removePrefix("🎤").removePrefix("●").trim()
    Column(modifier) {
        if (controller.partialText.isNotBlank()) Text(controller.partialText,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 4.dp))
        when (controller.state) {
            InlineDictationController.State.LISTENING -> DictationRecordingBarCore(controller, hindi, Modifier, tall = true) { controller.cancel() }
            InlineDictationController.State.WORKING -> DictationFinishingRow(hindi)
            else -> {
                Button(onClick = { gate() }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(cleanLabel, fontWeight = FontWeight.Bold)
                }
                if (controller.state == InlineDictationController.State.ERROR && controller.errorText.isNotBlank())
                    Text(controller.errorText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
