package com.example.arfas

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import com.google.ar.core.Config
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.sceneview.ar.ARSceneView
import com.google.ar.core.Frame
import com.google.ar.core.Session
import kotlinx.coroutines.delay

// ─────────────────────────────────────────────────────────────────────────────
// AR + HUD view
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ARHUDView(
    modifier: Modifier = Modifier,
    isVisionMode: Boolean,
    arGuidanceActive: Boolean,
    onFrame: (Session, Frame) -> Unit
) {
    // FIX #3: Check CAMERA permission before handing control to ARSceneView.
    // ARCore's ACameraManager calls getDeviceIdsForUid on init; if the permission
    // was revoked or not yet granted (e.g. after a crash restart), it fails 5× and
    // causes AppOps "Operation not started" errors on cleanup.
    val context = LocalContext.current
    // ARCore remains the single Live Video camera owner on supported devices so a fresh
    // frame can be captured/localized without camera hand-off races. arGuidanceActive
    // controls marker/guidance UI state in MainActivity; it intentionally does not swap
    // camera backends mid-session. Read it here so that contract is explicit.
    @Suppress("UNUSED_VARIABLE") val guidanceState = arGuidanceActive
    val cameraGranted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    var sceneSize by remember { mutableStateOf(IntSize.Zero) }
    // Three-state backend decision. UNKNOWN_CHECKING is not treated as ready: it gets
    // a short bounded re-check window, then safely falls back to Camera2. A later
    // SceneView session failure also flips this state to FALLBACK.
    var arCoreReadiness by remember(context, isVisionMode) {
        mutableStateOf(if (isVisionMode) ARController.arCoreReadiness() else ArCoreReadiness.FALLBACK)
    }
    LaunchedEffect(isVisionMode, cameraGranted) {
        if (!isVisionMode || !cameraGranted) return@LaunchedEffect
        arCoreReadiness = ARController.arCoreReadiness()
        if (arCoreReadiness == ArCoreReadiness.CHECKING) {
            repeat(6) {
                delay(200L)
                val next = ARController.arCoreReadiness()
                if (next != ArCoreReadiness.CHECKING) {
                    arCoreReadiness = next
                    return@LaunchedEffect
                }
            }
            Log.w("AR_DEBUG_TRACE", "AR_AVAILABILITY_TIMEOUT action=CAMERA2_FALLBACK")
            arCoreReadiness = ArCoreReadiness.FALLBACK
        }
    }

    DisposableEffect(isVisionMode) {
        if (isVisionMode) ARController.beginLiveVideoGeneration()
        onDispose {
            // SceneView/ARCore native objects must never survive the Live Video surface.
            ARController.clearState()
            VisualEngine.stopPeriodicCleanup()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { sceneSize = it; ARController.updateViewport(it.width, it.height) }
    ) {
        if (isVisionMode && cameraGranted && arCoreReadiness == ArCoreReadiness.READY) {
            SideEffect { ARController.useArCoreBackend() }
            // Phase 19: Live Video has exactly one camera owner. ARCore/SceneView is
            // created only while Live Video is enabled; audio-only/idle sessions stay
            // completely AR-free. Plane detection remains enabled internally for
            // hit-testing, but plane rendering is disabled so users never see the
            // white SceneView plane mesh.
            val depthMode = remember(context) {
                // Keep Depth disabled for the Live-assist camera path. The device logs
                // show repeated ARCore motion-stereo/spherical-rectifier failures while
                // Gemma GPU vision is active. Plane/Point/InstantPlacement are sufficient
                // for FixSight anchoring and avoid competing depth work on the GPU.
                Config.DepthMode.DISABLED
            }
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                planeRenderer = false,
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
                depthMode = depthMode,
                instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP,
                focusMode = Config.FocusMode.AUTO,
                sessionConfiguration = { session, config ->
                    // Keep the raw callback aligned with the typed parameters. The
                    // typed parameters are important because SceneView's own defaults
                    // otherwise set Instant Placement to DISABLED.
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    // Do not enable ARCore Depth merely because the device advertises
                    // support. FixSight uses Plane/Point/InstantPlacement for world
                    // anchoring; disabling Depth removes the failing motion-stereo path
                    // observed on-device and reduces contention with local Gemma vision.
                    config.depthMode = Config.DepthMode.DISABLED
                    config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
                    config.focusMode = Config.FocusMode.AUTO
                },
                onSessionFailed = { exception ->
                    Log.e(
                        "AR_DEBUG_TRACE",
                        "AR_SESSION_FAILED action=CAMERA2_FALLBACK ${exception.javaClass.simpleName}:${exception.message}"
                    )
                    arCoreReadiness = ArCoreReadiness.FALLBACK
                },
                onSessionUpdated = { session, frame ->
                    ARController.ensureLiveVideoSessionConfigured(session)
                    onFrame(session, frame)
                }
            )
            // Only FixSight-owned markers are visible. ARCore planes/point-cloud
            // diagnostics are intentionally not rendered.
            AROverlay()
        } else if (isVisionMode && cameraGranted && arCoreReadiness == ArCoreReadiness.CHECKING) {
            // Do not start a second camera owner while ARCore availability is unresolved.
            // The bounded LaunchedEffect above will choose ARCore or Camera2 shortly.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        } else if (isVisionMode && cameraGranted) {
            // P0 compatibility fallback: phones without a usable Google Play Services
            // for AR installation still get Live Video, one-frame-per-turn VLM capture,
            // AI 2D localization, manual arrows/boxes/text/cards and the normal
            // local/cloud reasoning pipeline. Only world-space anchoring is unavailable.
            val fallbackCamera = remember(context) {
                Camera2FrameController(context.applicationContext)
            }
            DisposableEffect(fallbackCamera) {
                ARController.useCamera2Fallback(fallbackCamera)
                onDispose {
                    ARController.clearCamera2Fallback(fallbackCamera)
                    fallbackCamera.close()
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).also { fallbackCamera.attach(it) }
                },
                update = { view -> fallbackCamera.attach(view) }
            )
            AROverlay()
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
            ) {
                Text(
                    text = "2D AR",
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        } else if (isVisionMode) {
            // Permission not yet granted — show blank until launcher re-grants it
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) { OrbVisualizer() }
        }
    }
}

@Composable
fun AROverlay() {
    val markers = VisualEngine.observableMarkers
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val overlayWidthPx = with(density) { maxWidth.toPx() }
        val overlayHeightPx = with(density) { maxHeight.toPx() }

        // Meta Ensemble DIAG Banner
        Surface(
            color = Color(0xE6111822),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, Color(0xFF00E676)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            Text(
                text = "Gemma 3n E2B offline vision + OCR - AR READY",
                color = Color(0xFF00E676),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        // Geometry remains Canvas-backed so the existing BOX / CIRCLE / ARROW
        // rendering path is unchanged. Textual markers are layered separately
        // below; this avoids changing ARCore anchoring or marker projection.
        Canvas(modifier = Modifier.fillMaxSize()) {
            markers.filter { it.visible }.forEach { marker ->
                if (marker.type == ARMarkerType.TEXT_LABEL || marker.type == ARMarkerType.INSTRUCTION_CARD) {
                    return@forEach
                }
                val rect = marker.bbox
                val color = when (marker.color) {
                    ARColor.RED    -> Color.Red
                    ARColor.GREEN  -> Color.Green
                    ARColor.YELLOW -> Color.Yellow
                    ARColor.BLUE   -> Color.Blue
                }
                val left = rect.x * size.width
                val top = rect.y * size.height
                val width = rect.w * size.width
                val height = rect.h * size.height
                val center = androidx.compose.ui.geometry.Offset(left + width / 2f, top + height / 2f)
                when (marker.type) {
                    ARMarkerType.ARROW_POINTER -> {
                        val start = androidx.compose.ui.geometry.Offset(
                            (center.x - width.coerceAtLeast(72.dp.toPx()) * 0.8f).coerceAtLeast(0f),
                            (center.y - height.coerceAtLeast(72.dp.toPx()) * 0.8f).coerceAtLeast(0f)
                        )
                        drawLine(color = color, start = start, end = center, strokeWidth = 4.dp.toPx())
                        drawCircle(color = color, radius = 6.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
                    }
                    ARMarkerType.CIRCLE_HIGHLIGHT -> {
                        drawCircle(
                            color = color,
                            radius = maxOf(width, height) / 2f,
                            center = center,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                    else -> {
                        drawRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(left, top),
                            size = androidx.compose.ui.geometry.Size(width, height),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }
            }
        }

        markers.filter {
            it.visible && (it.type == ARMarkerType.TEXT_LABEL || it.type == ARMarkerType.INSTRUCTION_CARD)
        }.forEach { marker ->
            val accent = when (marker.color) {
                ARColor.RED    -> Color.Red
                ARColor.GREEN  -> Color.Green
                ARColor.YELLOW -> Color.Yellow
                ARColor.BLUE   -> Color.Blue
            }
            val x = with(density) { (marker.bbox.x * overlayWidthPx).toDp() }
            val y = with(density) { (marker.bbox.y * overlayHeightPx).toDp() }
            val label = marker.label.trim().ifBlank {
                if (marker.type == ARMarkerType.INSTRUCTION_CARD) "Instruction" else "Label"
            }

            if (marker.type == ARMarkerType.INSTRUCTION_CARD) {
                Surface(
                    color = Color(0xE6111822),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, accent),
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .widthIn(min = 150.dp, max = 280.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("GUIDANCE", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                Surface(
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = RoundedCornerShape(7.dp),
                    border = BorderStroke(1.dp, accent),
                    modifier = Modifier
                        .offset(x = x, y = y)
                        .widthIn(max = 220.dp)
                ) {
                    Text(
                        label,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun OrbVisualizer() {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = ""
    )
    Box(
        modifier = Modifier.size(200.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF00E5FF), Color(0xFF006064), Color.Transparent)))
                .border(2.dp, Color(0xFF00E5FF), CircleShape)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings — grouped cards: Offline AI · Knowledge base · Online AI · Answers · Check · About
// ArogyaDoot runs Gemma 3n offline and Groq (Qwen) online. The Meta stack (Llama 3.2, DETR,
// CLIP, DINOv2, SAM) and its models zip are retired and no longer offered here.
// ─────────────────────────────────────────────────────────────────────────────

private val SetBg = Color(0xFF14142A)
private val SetCard = Color(0xFF20203A)
private val SetAccent = Color(0xFF80D8FF)
private val SetMuted = Color(0xFFA0A0B8)
private val SetOk = Color(0xFF00E676)
private val SetWarn = Color(0xFFFFD54F)

@Composable
private fun SettingsCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = SetCard, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = SetMuted, fontSize = 12.sp, lineHeight = 16.sp)
            content()
        }
    }
}

/** ✓ / … / ✗ and a one-line status. */
@Composable
private fun SettingsStatus(text: String, ok: Boolean, warn: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (ok) SetOk else if (warn) SetWarn else Color(0xFFFF5252), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color(0xFFE0E0E0), fontSize = 13.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun SettingsSwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = SetMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** "More ▾" disclosure for rarely used controls inside a card. */
@Composable
private fun SettingsMore(label: String, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) "$label ▴" else "$label ▾", color = SetMuted, fontSize = 12.sp)
    }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun ReportBox(text: String) {
    if (text.isNotBlank()) Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF101020), modifier = Modifier.fillMaxWidth()) {
        Text(text.take(4000), color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp))
    }
}

@Composable
fun SettingsDialog(
    modelLoaded: Boolean,
    modelStatusText: String = "",
    modelFileManager: ModelFileManager? = null,
    onModelFilePicked: ((Uri) -> Unit)?,
    /** The phone AI choice changed (Gemma connected/disconnected): load it now. */
    onPhoneEngineChanged: (() -> Unit)? = null,
    onRunSelfTest: (() -> Unit)? = null,
    selfTestText: String = "",
    selfTestRunning: Boolean = false,
    onKbFilePicked: ((Uri) -> Unit)?,
    kbInstalled: Boolean = false,
    kbReady: Boolean = false,
    kbStatusText: String = "Not loaded",
    kbPacks: List<GenericKnowledgePackRepository.InstalledPack> = emptyList(),
    kbSwitching: Boolean = false,
    onUseKbPack: ((String) -> Unit)? = null,
    onRemoveKbPack: ((String) -> Unit)? = null,
    /** One-tap Guide v1 → v2 update when the wanted file is in Downloads. Null = nothing offered. */
    kbGuideUpdate: KbGuideUpdate? = null,
    onInstallGuideUpdate: (() -> Unit)? = null,
    kbCheckText: String = "",
    kbCheckRunning: Boolean = false,
    onCheckKb: (() -> Unit)? = null,
    groqKeyConfigured: Boolean = false,
    groqStatusText: String = "No API key",
    onSaveGroqKey: ((String) -> Unit)? = null,
    onClearGroqKey: (() -> Unit)? = null,
    onTestGroq: (() -> Unit)? = null,
    answerPolicy: AnswerPolicy = AnswerPolicy.LOCAL_FIRST,
    onAnswerPolicyChanged: ((AnswerPolicy) -> Unit)? = null,
    onlineBackup: Boolean = false,
    onOnlineBackupChanged: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var groqKeyInput by remember { mutableStateOf("") }
    var showGroqKey by remember { mutableStateOf(false) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onModelFilePicked?.invoke(uri)
    }
    val kbPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onKbFilePicked?.invoke(uri)
    }

    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SetBg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // ── Header ──
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Done", color = SetAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // ── 1. Offline AI ──
                    val gemmaInstalled = modelFileManager?.gemmaModelFile() != null
                    val gemmaDisconnected = modelFileManager?.isGemmaDisconnected() == true
                    val failed = !modelLoaded && (modelStatusText.contains("fail", true) || modelStatusText.contains("error", true) || modelStatusText.contains("✗"))
                    SettingsCard("Offline AI", "Gemma 3n on this phone answers questions and reads photos. Works without internet.") {
                        SettingsStatus(
                            when {
                                modelLoaded -> "Gemma 3n ready"
                                gemmaDisconnected -> "Gemma is switched off (see More)"
                                failed -> modelStatusText.removePrefix("Local model failed: ").removePrefix("✗ ").take(160)
                                gemmaInstalled -> modelStatusText.ifBlank { "Loading Gemma 3n…" }
                                else -> "Not installed — install the Gemma 3n model in Setup"
                            },
                            ok = modelLoaded, warn = gemmaInstalled && !failed && !gemmaDisconnected
                        )
                        SettingsMore("More") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Use Gemma on this phone", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Switch(checked = !gemmaDisconnected, onCheckedChange = { on ->
                                    modelFileManager?.setGemmaDisconnected(!on)
                                    onPhoneEngineChanged?.invoke()
                                })
                            }
                            OutlinedButton(onClick = { modelPicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                                Text("Install a Gemma .litertlm file from this phone", fontSize = 12.sp)
                            }
                            val (lastSource, lastTime) = modelFileManager?.getLastAnswerSource() ?: (ModelSource.GEMMA_3N_E2B to 0L)
                            if (lastTime > 0L) Text(
                                "Last answer: ${lastSource.detailText} at " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastTime)),
                                color = SetMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // ── 2. Knowledge base ──
                    SettingsCard("ASHA guide (knowledge base)", "Answers are taken from these official documents and show their sources.") {
                        SettingsStatus(kbStatusText, ok = kbInstalled && kbReady, warn = kbInstalled && !kbReady)
                        if (kbGuideUpdate != null && onInstallGuideUpdate != null) {
                            Text("Guide on this phone: ${kbGuideUpdate.activeLabel} — ${kbGuideUpdate.wantedName} is in Downloads",
                                color = SetWarn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Button(onClick = { onInstallGuideUpdate() }, modifier = Modifier.fillMaxWidth()) { Text("Install ${kbGuideUpdate.wantedName}") }
                        }
                        if (kbPacks.size > 1) KnowledgePackList(kbPacks, kbSwitching, onUseKbPack, onRemoveKbPack)
                        OutlinedButton(onClick = { kbPicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (kbInstalled) "Add another guide (.db)" else "Install guide (.db)", fontSize = 13.sp)
                        }
                        if (kbInstalled && onCheckKb != null) SettingsMore("Check this guide") {
                            Text("Samples passages from every document and checks that search finds them, plus data quality. About 1–2 minutes.",
                                color = SetMuted, fontSize = 11.sp, lineHeight = 15.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = { onCheckKb() }, enabled = !kbCheckRunning, modifier = Modifier.weight(1f)) { Text(if (kbCheckRunning) "Checking…" else "Check now") }
                                OutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(kbCheckText)) },
                                    enabled = kbCheckText.isNotBlank() && !kbCheckRunning, modifier = Modifier.weight(1f)) { Text("Copy report") }
                            }
                            ReportBox(kbCheckText)
                        }
                    }

                    // ── 3. Online AI ──
                    SettingsCard("Online AI (optional)", "Groq · Qwen3.8 27B. Needs your Groq API key (gsk_…). The key is stored encrypted on this phone; names and phone numbers are masked before anything is sent.") {
                        SettingsStatus(groqStatusText, ok = groqKeyConfigured && groqStatusText.contains("Connected", true), warn = groqKeyConfigured)
                        OutlinedTextField(
                            value = groqKeyInput,
                            onValueChange = { groqKeyInput = it },
                            label = { Text(if (groqKeyConfigured) "Replace Groq API key" else "Groq API key (gsk_…)") },
                            singleLine = true,
                            visualTransformation = if (showGroqKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { TextButton(onClick = { showGroqKey = !showGroqKey }) { Text(if (showGroqKey) "Hide" else "Show", fontSize = 12.sp) } },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { if (groqKeyInput.isNotBlank()) { onSaveGroqKey?.invoke(groqKeyInput.trim()); groqKeyInput = "" } },
                                enabled = groqKeyInput.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Save") }
                            OutlinedButton(onClick = { onTestGroq?.invoke() }, enabled = groqKeyConfigured, modifier = Modifier.weight(1f)) { Text("Test") }
                        }
                        if (groqKeyConfigured) TextButton(onClick = { groqKeyInput = ""; onClearGroqKey?.invoke() }) {
                            Text("Remove saved key", color = Color(0xFFFF8A80), fontSize = 12.sp)
                        }
                    }

                    // ── 4. How answers are chosen ──
                    SettingsCard("How answers are chosen") {
                        SettingsSwitchRow(
                            "Prefer online answers",
                            if (answerPolicy == AnswerPolicy.CLOUD_FIRST) "On: Groq answers when there is internet and a key; the phone takes over otherwise."
                            else "Off: Gemma on this phone answers; online is used only when needed or asked.",
                            checked = answerPolicy == AnswerPolicy.CLOUD_FIRST
                        ) { on -> onAnswerPolicyChanged?.invoke(if (on) AnswerPolicy.CLOUD_FIRST else AnswerPolicy.LOCAL_FIRST) }
                        SettingsSwitchRow(
                            "Online backup for guide answers",
                            "If the phone's answer fails its checks, Groq answers from the same guide passages (those passages are sent online).",
                            checked = onlineBackup
                        ) { on -> onOnlineBackupChanged?.invoke(on) }
                        Text("Voice input is always transcribed on this phone (Whisper, Hindi and English); nothing is sent online.",
                            color = SetMuted, fontSize = 11.sp, lineHeight = 15.sp)
                    }

                    // ── 5. Check the app ──
                    if (onRunSelfTest != null) SettingsCard("Check the app", "Runs chat, Hindi, guide search and photo reading on this phone and writes one report (2–5 minutes).") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { onRunSelfTest() }, enabled = !selfTestRunning, modifier = Modifier.weight(1f)) { Text(if (selfTestRunning) "Running…" else "Run self-test") }
                            OutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(selfTestText)) },
                                enabled = selfTestText.isNotBlank() && !selfTestRunning, modifier = Modifier.weight(1f)) { Text("Copy report") }
                        }
                        ReportBox(selfTestText)
                    }

                    // ── 6. About ──
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("ArogyaDoot • guidance for ASHA & ANM health workers", color = SetMuted, fontSize = 11.sp)
                        Text("Offline: Gemma 3n · Online (optional): Qwen on Groq · Voice: Whisper on the phone", color = SetMuted, fontSize = 11.sp)
                        Text("Not a diagnosis — always check with your ANM.", color = SetMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun PhoneAiStatusLine(label: String, ok: Boolean, detail: String, warn: Boolean) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (ok) "✓" else if (warn) "…" else "✗",
            color = if (ok) Color(0xFF00E676) else if (warn) Color(0xFFFFD54F) else Color(0xFFFF5252),
            fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Color(0xFFB0B0B0), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun KnowledgePackList(
    packs: List<GenericKnowledgePackRepository.InstalledPack>,
    switching: Boolean,
    onUse: ((String) -> Unit)?,
    onRemove: ((String) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Installed knowledge packs", color = Color(0xFF9E9E9E), fontSize = 11.sp)
        packs.forEach { pack ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(pack.displayName.ifBlank { "Knowledge Base" }, color = Color.White, fontSize = 13.sp, maxLines = 2)
                    Text("${pack.rowCount} chunks", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                }
                if (pack.active) {
                    Text("In use", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    TextButton(enabled = !switching && onUse != null, onClick = { onUse?.invoke(pack.packId) }) {
                        Text(if (switching) "…" else "Use", color = Color(0xFF80D8FF), fontSize = 12.sp)
                    }
                    TextButton(enabled = !switching && onRemove != null, onClick = { onRemove?.invoke(pack.packId) }) {
                        Text("Remove", color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

