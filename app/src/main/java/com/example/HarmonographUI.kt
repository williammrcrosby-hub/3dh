package com.example

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.scale
import kotlin.math.*

val usableFrequencies = listOf(1f/12f, 1f/8f, 1f/6f, 1f/4f, 1f/3f, 1f/2f, 2f/3f, 3f/4f, 1f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f, 12f)
val usableFrequenciesLabels = listOf("1/12x", "1/8x", "1/6x", "1/4x", "1/3x", "1/2x", "2/3x", "3/4x", "1x", "1.5x", "2x", "3x", "4x", "5x", "6x", "8x", "10x", "12x")

private data class ComposeSegment(
    val p1: ProjectedPoint,
    val p2: ProjectedPoint,
    val color: Color,
    val strokeWidth: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarmonographAppScreen(viewModel: HarmonographViewModel) {
    val context = LocalContext.current
    val settings by viewModel.uiState.collectAsStateWithLifecycle()
    val drawProgress by viewModel.currentDrawProgress.collectAsStateWithLifecycle()
    val isDrawing by viewModel.isDrawing.collectAsStateWithLifecycle()
    val presets by viewModel.savedPresets.collectAsStateWithLifecycle()

    var isPanelExpanded by remember { mutableStateOf(true) }
    var activeTab by remember { mutableStateOf(0) } // 0: Oscillators, 1: Style & Pen, 2: Camera & Setup, 3: Presets

    // Interaction state variables
    var yaw by remember { mutableStateOf(35f) }
    var pitch by remember { mutableStateOf(25f) }
    var scaleFactor by remember { mutableStateOf(1f) }

    // Floating Phase/Oscillation Info overlay toggle
    var showTelemetryOverlay by remember { mutableStateOf(true) }

    // Stateful high-performance animation frame timer
    var animTime by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { time ->
                animTime = time
            }
        }
    }

    // Dynamic rotation angle calculation driven by the animation timer state
    val animatedYaw = if (settings.cameraAutoRotationEnabled) {
        (yaw + animTime * 0.001f * settings.cameraAutoRotationSpeed * 25f) % 360f
    } else {
        yaw
    }

    val animatedPitch = if (settings.cameraAutoRotationEnabled) {
        pitch + (sin(animTime * 0.001f * settings.cameraAutoRotationSpeed * 0.5f) * 15f)
    } else {
        pitch
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)) // Deep space slate backcolor
    ) {
        // Core 3D drawing Canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(settings) {
                    awaitPointerEventScope {
                        var twoFingerDownTime = 0L
                        var twoFingerHoldTriggered = false
                        var lastTwoFingerTapTime = 0L
                        var isTwoFingerTapPossible = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedChanges = event.changes.filter { it.pressed }
                            val numPressed = pressedChanges.size

                            if (numPressed == 1) {
                                // Single-finger dragging: update view angles
                                val change = pressedChanges.first()
                                if (change.previousPressed) {
                                    val dragAmount = change.position - change.previousPosition
                                    yaw += dragAmount.x * 0.25f
                                    pitch -= dragAmount.y * 0.25f
                                    change.consume()
                                }

                                // If transitioned from 2 fingers down to 1 finger down
                                if (twoFingerDownTime > 0L) {
                                    val duration = System.currentTimeMillis() - twoFingerDownTime
                                    if (!twoFingerHoldTriggered && isTwoFingerTapPossible && duration in 40L..500L) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTwoFingerTapTime < 500L) {
                                            viewModel.resetAndRandomize()
                                            Toast.makeText(context, "Variables Reset & Randomized!", Toast.LENGTH_SHORT).show()
                                            lastTwoFingerTapTime = 0L
                                        } else {
                                            lastTwoFingerTapTime = now
                                        }
                                    }
                                    twoFingerDownTime = 0L
                                    isTwoFingerTapPossible = false
                                }
                            } else if (numPressed == 2) {
                                // Exactly two fingers down
                                if (twoFingerDownTime == 0L) {
                                    twoFingerDownTime = System.currentTimeMillis()
                                    twoFingerHoldTriggered = false
                                    isTwoFingerTapPossible = true
                                } else {
                                    val duration = System.currentTimeMillis() - twoFingerDownTime
                                    if (!twoFingerHoldTriggered && duration >= 650L) {
                                        twoFingerHoldTriggered = true
                                        isTwoFingerTapPossible = false
                                        val p = if (settings.cameraPerspective == 1) 2 else 1
                                        viewModel.updateSettings(settings.copy(cameraPerspective = p))
                                        Toast.makeText(context, "Perspective Swapped via 2-finger hold!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                // Consume all changes when multiple pointers are active
                                event.changes.forEach { it.consume() }
                            } else {
                                // 0 or 3+ fingers down
                                if (twoFingerDownTime > 0L) {
                                    val duration = System.currentTimeMillis() - twoFingerDownTime
                                    if (!twoFingerHoldTriggered && isTwoFingerTapPossible && duration in 40L..500L) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTwoFingerTapTime < 500L) {
                                            viewModel.resetAndRandomize()
                                            Toast.makeText(context, "Variables Reset & Randomized!", Toast.LENGTH_SHORT).show()
                                            lastTwoFingerTapTime = 0L
                                        } else {
                                            lastTwoFingerTapTime = now
                                        }
                                    }
                                    twoFingerDownTime = 0L
                                    isTwoFingerTapPossible = false
                                }
                            }
                        }
                    }
                }
        ) {
            val stepsCount = settings.drawLengthSteps
            val paths = remember(settings) {
                HarmonographMath.generatePathPoints(settings, stepsCount)
            }
            val shapes = remember(settings) {
                HarmonographMath.generatePeriodicShapes(settings, stepsCount)
            }
            
            val timeHueOffset = if (settings.hueShiftingEnabled) {
                (animTime / 24) % 360
            } else {
                0L
            }

            Canvas(modifier = Modifier.fillMaxSize().testTag("3d_harmonograph_canvas")) {
                val drawLimit = drawProgress.roundToInt()
                val width = size.width
                val height = size.height

                val cameraTargetIndex = if (settings.cameraPerspective == 2 && drawProgress >= stepsCount.coerceAtLeast(1) - 1f) {
                    val durationMin = if (settings.drawSpeedInstant) 2.0f else settings.drawSpeedMinutes
                    val cycleDurationMs = (durationMin * 60f * 1000f).toLong().coerceAtLeast(1000L)
                    val progressFrac = (animTime % cycleDurationMs).toFloat() / cycleDurationMs
                    val stepsInPath = paths.firstOrNull()?.size ?: stepsCount
                    (progressFrac * (stepsInPath - 1)).coerceIn(0f, (stepsInPath - 1).toFloat())
                } else {
                    drawProgress
                }

                // Project and gather line segments across all paths for unified depth sorting
                val segmentsList = mutableListOf<ComposeSegment>()
                val tipsList = mutableListOf<Pair<ProjectedPoint, Color>>()

                for (pIdx in paths.indices) {
                    val path3D = paths[pIdx]
                    val projPoints = HarmonographMath.project3DTo2D(
                        points = path3D,
                        yaw = animatedYaw,
                        pitch = animatedPitch,
                        perspective = settings.cameraPerspective,
                        currentDrawProgress = drawProgress,
                        screenWidth = width,
                        screenHeight = height,
                        angularLock = settings.isAngularLockEnabled,
                        angularLockAxis = settings.angularLockAxis,
                        referencePoints = paths.firstOrNull(),
                        cameraTargetIndex = cameraTargetIndex,
                        cameraDistance = settings.cameraDistance.current,
                        dynamicCameraZoomEnabled = settings.dynamicCameraZoomEnabled,
                        coasterDirectionFacing = settings.coasterDirectionFacing,
                        animTime = animTime
                    )
                    
                    if (projPoints.isEmpty()) continue

                    // Gather line segments
                    for (i in 0 until projPoints.size - 1) {
                        val p1 = projPoints[i]
                        val p2 = projPoints[i + 1]
                        
                        val segmentColor = computeComposeColor(
                            settings = settings,
                            idx = i,
                            total = projPoints.size,
                            pt = p1,
                            width = width,
                            height = height,
                            hueOffset = timeHueOffset
                        )
                        
                        val baseThickness = settings.lineThickness.current
                        val strokeWidth = baseThickness + (0.5f * baseThickness * (p1.depth / 500f).coerceIn(-1f, 1f))
                        segmentsList.add(ComposeSegment(p1, p2, segmentColor, strokeWidth))
                    }

                    // Store pen tip if enabled
                    if (settings.penTipEnabled && projPoints.isNotEmpty()) {
                        val tip = projPoints.last()
                        val tipColor = if (settings.penTipColorMode == "solid") {
                            Color(settings.penTipColor)
                        } else {
                            if (projPoints.size > 1) {
                                computeComposeColor(
                                    settings = settings,
                                    idx = projPoints.size - 2,
                                    total = projPoints.size,
                                    pt = projPoints[projPoints.size - 2],
                                    width = width,
                                    height = height,
                                    hueOffset = timeHueOffset
                                )
                            } else {
                                Color.White
                            }
                        }
                        tipsList.add(Pair(tip, tipColor))
                    }
                }

                // Sort all line segments back-to-front (descending by average depth)
                segmentsList.sortByDescending { (it.p1.depth + it.p2.depth) / 2f }

                // Draw depth-sorted segments
                for (seg in segmentsList) {
                    drawLine(
                        color = seg.color,
                        start = androidx.compose.ui.geometry.Offset(seg.p1.x, seg.p1.y),
                        end = androidx.compose.ui.geometry.Offset(seg.p2.x, seg.p2.y),
                        strokeWidth = seg.strokeWidth * scaleFactor
                    )
                }

                // Render styled active pen tip markers
                for ((tip, tipColor) in tipsList) {
                    val s = settings.penTipSize * scaleFactor
                    when (settings.penTipShape) {
                        "square" -> {
                            drawRect(
                                color = tipColor,
                                topLeft = androidx.compose.ui.geometry.Offset(tip.x - s, tip.y - s),
                                size = androidx.compose.ui.geometry.Size(s * 2f, s * 2f)
                            )
                        }
                        "diamond" -> {
                            val path = Path().apply {
                                moveTo(tip.x, tip.y - s)
                                lineTo(tip.x + s, tip.y)
                                lineTo(tip.x, tip.y + s)
                                lineTo(tip.x - s, tip.y)
                                close()
                            }
                            drawPath(path = path, color = tipColor)
                        }
                        "cross" -> {
                            drawLine(
                                color = tipColor,
                                start = androidx.compose.ui.geometry.Offset(tip.x - s, tip.y),
                                end = androidx.compose.ui.geometry.Offset(tip.x + s, tip.y),
                                strokeWidth = 3f * scaleFactor
                            )
                            drawLine(
                                color = tipColor,
                                start = androidx.compose.ui.geometry.Offset(tip.x, tip.y - s),
                                end = androidx.compose.ui.geometry.Offset(tip.x, tip.y + s),
                                strokeWidth = 3f * scaleFactor
                            )
                        }
                        "star" -> {
                            val path = Path()
                            for (i in 0 until 10) {
                                val angle = (i * PI / 5).toFloat()
                                val r = if (i % 2 == 0) s else s * 0.4f
                                val px = tip.x + r * cos(angle - PI.toFloat() / 2f)
                                val py = tip.y + r * sin(angle - PI.toFloat() / 2f)
                                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                            }
                            path.close()
                            drawPath(path = path, color = tipColor)
                        }
                        else -> { // circle
                            drawCircle(
                                color = tipColor,
                                radius = s,
                                center = androidx.compose.ui.geometry.Offset(tip.x, tip.y)
                            )
                            drawCircle(
                                color = tipColor.copy(alpha = 0.2f),
                                radius = s * 2f,
                                center = androidx.compose.ui.geometry.Offset(tip.x, tip.y)
                            )
                        }
                    }
                }

                // Orthogonal secondary shapes drawing
                for (shape in shapes) {
                    if (shape.colorIndex > drawLimit) continue
                    drawComposeOrthogonalShape(
                        shape = shape,
                        yawVal = animatedYaw,
                        pitchVal = animatedPitch,
                        perspective = settings.cameraPerspective,
                        width = width,
                        height = height,
                        angularLock = settings.isAngularLockEnabled,
                        angularLockAxis = settings.angularLockAxis,
                        timeHueOffset = timeHueOffset,
                        totalSteps = stepsCount,
                        settings = settings,
                        scaleFactor = scaleFactor,
                        mainPathPoints = paths.firstOrNull() ?: emptyList(),
                        cameraTargetIndex = cameraTargetIndex,
                        animTime = animTime
                    )
                }
            }
        }

        // Floating Telemetry Phase display
        if (showTelemetryOverlay) {
            Box(
                modifier = Modifier
                    .padding(top = 44.dp, start = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xD91E293B)) // Slate 800
                    .padding(12.dp)
                    .align(Alignment.TopStart)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("TELEMETRY", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    TelemetryRow("XYZ Amps", "${settings.ampX.current.roundToInt()}, ${settings.ampY.current.roundToInt()}, ${settings.ampZ.current.roundToInt()}")
                    TelemetryRow("XYZ Freqs", "${"%.2f".format(settings.freqX.current)}x, ${"%.2f".format(settings.freqY.current)}x, ${"%.2f".format(settings.freqZ.current)}x")
                    TelemetryRow("Decays", "${"%.4f".format(settings.decayX.current)}, ${"%.4f".format(settings.decayY.current)}, ${"%.4f".format(settings.decayZ.current)}")
                    TelemetryRow("Phases", "${settings.phaseX.current.roundToInt()}°, ${settings.phaseY.current.roundToInt()}°, ${settings.phaseZ.current.roundToInt()}°")
                    if (settings.ampSubX.current > 0 || settings.ampSubY.current > 0 || settings.ampSubZ.current > 0) {
                        TelemetryRow("SubAmps", "${settings.ampSubX.current.roundToInt()}, ${settings.ampSubY.current.roundToInt()}, ${settings.ampSubZ.current.roundToInt()}")
                    }
                    TelemetryRow("Pen Mode", if (settings.penCount > 1) "${settings.penCount} Pens (${if (settings.penRotationEnabled) "Rotational" else "Parallel"})" else "1 Pen")
                }
            }
        }

        // Upper action buttons (Telemetry toggle, Reset, Install wallpaper, Info help)
        Row(
            modifier = Modifier
                .padding(top = 44.dp, end = 16.dp)
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { showTelemetryOverlay = !showTelemetryOverlay },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xCC1E293B))
            ) {
                Icon(
                    imageVector = if (showTelemetryOverlay) Icons.Default.Info else Icons.Default.Info,
                    contentDescription = "Toggle Telemetry",
                    tint = if (showTelemetryOverlay) Color(0xFF00E5FF) else Color.White
                )
            }
            
            IconButton(
                onClick = {
                    try {
                        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                            putExtra(
                                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(context, HarmonographWallpaperService::class.java)
                            )
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "To set background: Long press Android home screen -> Wallpapers", Toast.LENGTH_LONG).show()
                    }
                },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xCC1E293B))
            ) {
                Icon(
                    imageVector = Icons.Default.Wallpaper,
                    contentDescription = "Install Wallpaper",
                    tint = Color(0xFFFFB300)
                )
            }

            IconButton(
                onClick = { viewModel.resetAndRandomize() },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xCC1E293B)),
                modifier = Modifier.testTag("app_randomize_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Randomize drawing",
                    tint = Color.White
                )
            }
        }

        // Drawing progress controllers
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isPanelExpanded) 340.dp else 40.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xCC0F172A))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val maxSteps = settings.drawLengthSteps * settings.drawLengthFactor
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = { viewModel.togglePlayback() },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Icon(
                        imageVector = if (isDrawing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White
                    )
                }

                Slider(
                    value = drawProgress,
                    onValueChange = { viewModel.jumpToProgress(it) },
                    valueRange = 0f..maxSteps,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF)
                    )
                )

                Text(
                    text = "${(drawProgress / maxSteps * 100).roundToInt()}%",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.End
                )

                IconButton(
                    onClick = { isPanelExpanded = !isPanelExpanded },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Icon(
                        imageVector = if (isPanelExpanded) Icons.Default.Close else Icons.Default.Settings,
                        contentDescription = "Settings Panels",
                        tint = if (isPanelExpanded) Color(0xFFFF4081) else Color.White
                    )
                }
            }
        }

        // Expanded Control Panel Drawer
        AnimatedVisibility(
            visible = isPanelExpanded,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(320.dp)
        ) {
            Surface(
                color = Color(0xFF1E293B), // Slate 800
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxSize().testTag("control_panel_drawer")
            ) {
                Column {
                    TabRow(
                        selectedTabIndex = activeTab,
                        containerColor = Color(0xFF0F172A),
                        contentColor = Color.White
                    ) {
                        Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                            Text("Oscillators", modifier = Modifier.padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                            Text("Style & Pen", modifier = Modifier.padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                            Text("Camera & Setup", modifier = Modifier.padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Tab(selected = activeTab == 3, onClick = { activeTab = 3 }) {
                            Text("Presets", modifier = Modifier.padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(16.dp)
                    ) {
                        when (activeTab) {
                            0 -> OscillatorConfigTab(settings = settings, onUpdate = { viewModel.updateSettings(it) })
                            1 -> StyleAndPenConfigTab(settings = settings, onUpdate = { viewModel.updateSettings(it) })
                            2 -> CameraAndSetupTab(settings = settings, onUpdate = { viewModel.updateSettings(it) })
                            3 -> PresetsTab(presets = presets, activeSettings = settings, viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(0.55f),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Normal)
        Text(value, color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun OscillatorConfigTab(
    settings: HarmonographSettings,
    onUpdate: (HarmonographSettings) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PRIMARY XYZ OSCILLATORS", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 12.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text("Decay Rates", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                Switch(
                    checked = settings.decayEnabled,
                    onCheckedChange = { onUpdate(settings.copy(decayEnabled = it)) },
                    modifier = Modifier.scale(0.7f).testTag("decay_enabled_switch")
                )
            }
        }
        
        // Axis configuration blocks
        item {
            AxisConfigCard("X-Axis Control", settings.ampX, settings.freqX, settings.decayX, settings.phaseX, settings.decayEnabled,
                onAmpChange = { onUpdate(settings.copy(ampX = it)) },
                onFreqChange = { onUpdate(settings.copy(freqX = it)) },
                onDecayChange = { onUpdate(settings.copy(decayX = it)) },
                onPhaseChange = { onUpdate(settings.copy(phaseX = it)) })
        }
        item {
            AxisConfigCard("Y-Axis Control", settings.ampY, settings.freqY, settings.decayY, settings.phaseY, settings.decayEnabled,
                onAmpChange = { onUpdate(settings.copy(ampY = it)) },
                onFreqChange = { onUpdate(settings.copy(freqY = it)) },
                onDecayChange = { onUpdate(settings.copy(decayY = it)) },
                onPhaseChange = { onUpdate(settings.copy(phaseY = it)) })
        }
        item {
            AxisConfigCard("Z-Axis Control (3D Depth)", settings.ampZ, settings.freqZ, settings.decayZ, settings.phaseZ, settings.decayEnabled,
                onAmpChange = { onUpdate(settings.copy(ampZ = it)) },
                onFreqChange = { onUpdate(settings.copy(freqZ = it)) },
                onDecayChange = { onUpdate(settings.copy(decayZ = it)) },
                onPhaseChange = { onUpdate(settings.copy(phaseZ = it)) })
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text("SUBLAYER OSCILLATORS (X' Y' Z') MODULATION", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 12.sp)
        }

        // Sublayer Controls
        item {
            SublayerCard("Sublayer X' (adds to primary X)", settings.ampSubX, settings.phaseSubX, settings.subXFreqFactor, settings.subXFreqIsMultiply,
                onAmpChange = { onUpdate(settings.copy(ampSubX = it)) },
                onPhaseChange = { onUpdate(settings.copy(phaseSubX = it)) },
                onFactorChange = { onUpdate(settings.copy(subXFreqFactor = it)) },
                onIsMultiplyChange = { onUpdate(settings.copy(subXFreqIsMultiply = it)) })
        }
        item {
            SublayerCard("Sublayer Y' (adds to primary Y)", settings.ampSubY, settings.phaseSubY, settings.subYFreqFactor, settings.subYFreqIsMultiply,
                onAmpChange = { onUpdate(settings.copy(ampSubY = it)) },
                onPhaseChange = { onUpdate(settings.copy(phaseSubY = it)) },
                onFactorChange = { onUpdate(settings.copy(subYFreqFactor = it)) },
                onIsMultiplyChange = { onUpdate(settings.copy(subYFreqIsMultiply = it)) })
        }
        item {
            SublayerCard("Sublayer Z' (adds to Z dynamics)", settings.ampSubZ, settings.phaseSubZ, settings.subZFreqFactor, settings.subZFreqIsMultiply,
                onAmpChange = { onUpdate(settings.copy(ampSubZ = it)) },
                onPhaseChange = { onUpdate(settings.copy(phaseSubZ = it)) },
                onFactorChange = { onUpdate(settings.copy(subZFreqFactor = it)) },
                onIsMultiplyChange = { onUpdate(settings.copy(subZFreqIsMultiply = it)) })
        }
    }
}

@Composable
fun AxisConfigCard(
    title: String,
    amp: FloatParameter,
    freq: FloatParameter,
    decay: FloatParameter,
    phase: FloatParameter,
    decayEnabled: Boolean,
    onAmpChange: (FloatParameter) -> Unit,
    onFreqChange: (FloatParameter) -> Unit,
    onDecayChange: (FloatParameter) -> Unit,
    onPhaseChange: (FloatParameter) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                Spacer(modifier = Modifier.weight(1f))
                
                // On/Off Switch
                Switch(
                    checked = amp.enabled,
                    onCheckedChange = { onAmpChange(amp.copy(enabled = it)) },
                    thumbContent = { Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(12.dp)) },
                    modifier = Modifier.scale(0.7f)
                )
            }

            if (amp.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Amplitude
                    ParameterSliderRow("Amplitude", amp.current, amp.rangeMin, amp.rangeMax, 1f,
                        isLocked = amp.locked, onLockToggle = { onAmpChange(amp.copy(locked = it)) },
                        isRangeLocked = amp.rangeLocked, onRangeLockToggle = { onAmpChange(amp.withRangeLocked(it)) },
                        selectedMin = amp.actualSelectedMin, selectedMax = amp.actualSelectedMax,
                        onRangeChange = { min, max -> onAmpChange(amp.withRanges(min, max)) },
                        onValueChange = { onAmpChange(amp.withValue(it)) },
                        onRandomize = { onAmpChange(amp.randomize(java.util.Random())) })

                    // Usable List Frequencies Range Picker - Find nearest elements to represent randomized/imprecise floating-point frequencies correctly!
                    val freqIndex = usableFrequencies.mapIndexed { idx, f -> idx to kotlin.math.abs(f - freq.current) }
                        .minByOrNull { it.second }?.first ?: 0
                    val selectedMinIndex = usableFrequencies.mapIndexed { idx, f -> idx to kotlin.math.abs(f - freq.actualSelectedMin) }
                        .minByOrNull { it.second }?.first ?: 0
                    val selectedMaxIndex = usableFrequencies.mapIndexed { idx, f -> idx to kotlin.math.abs(f - freq.actualSelectedMax) }
                        .minByOrNull { it.second }?.first ?: (usableFrequencies.size - 1)
                    val formattedValueStr = if (kotlin.math.abs(usableFrequencies[freqIndex] - freq.current) > 0.005f) {
                        "${usableFrequenciesLabels[freqIndex]} (${"%.3f".format(freq.current)}x)"
                    } else {
                        usableFrequenciesLabels[freqIndex]
                    }
                    ParameterSliderRow(
                        label = "Frequency",
                        value = freqIndex.toFloat(),
                        minVal = 0f,
                        maxVal = (usableFrequencies.size - 1).toFloat(),
                        stepValue = 1f,
                        valueLabelFallback = formattedValueStr,
                        isLocked = freq.locked, onLockToggle = { onFreqChange(freq.copy(locked = it)) },
                        isRangeLocked = freq.rangeLocked, onRangeLockToggle = { onFreqChange(freq.withRangeLocked(it)) },
                        selectedMin = selectedMinIndex.toFloat(), selectedMax = selectedMaxIndex.toFloat(),
                        onRangeChange = { min, max -> onFreqChange(freq.withRanges(usableFrequencies[min.roundToInt()], usableFrequencies[max.roundToInt()])) },
                        onValueChange = { onFreqChange(freq.withValue(usableFrequencies[it.roundToInt()])) },
                        onRandomize = { onFreqChange(freq.randomize(java.util.Random())) }
                    )

                    // Decay
                    if (decayEnabled) {
                        ParameterSliderRow("Decay Rate", decay.current, decay.rangeMin, decay.rangeMax, 0.0001f, "%.4f",
                            isLocked = decay.locked, onLockToggle = { onDecayChange(decay.copy(locked = it)) },
                            isRangeLocked = decay.rangeLocked, onRangeLockToggle = { onDecayChange(decay.withRangeLocked(it)) },
                            selectedMin = decay.actualSelectedMin, selectedMax = decay.actualSelectedMax,
                            onRangeChange = { min, max -> onDecayChange(decay.withRanges(min, max)) },
                            onValueChange = { onDecayChange(decay.withValue(it)) },
                            onRandomize = { onDecayChange(decay.randomize(java.util.Random())) })
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("Decay Rate", color = Color(0xFF64748B), fontSize = 11.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            Text("Disabled Globally", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Phase Angle degrees
                    ParameterSliderRow("Phase Offset", phase.current, phase.rangeMin, phase.rangeMax, 5f, "%.0f°",
                        isLocked = phase.locked, onLockToggle = { onPhaseChange(phase.copy(locked = it)) },
                        isRangeLocked = phase.rangeLocked, onRangeLockToggle = { onPhaseChange(phase.withRangeLocked(it)) },
                        selectedMin = phase.actualSelectedMin, selectedMax = phase.actualSelectedMax,
                        onRangeChange = { min, max -> onPhaseChange(phase.withRanges(min, max)) },
                        onValueChange = { onPhaseChange(phase.withValue(it)) },
                        onRandomize = { onPhaseChange(phase.randomize(java.util.Random())) })
                }
            }
        }
    }
}

@Composable
fun SublayerCard(
    title: String,
    amp: FloatParameter,
    phase: FloatParameter,
    factor: IntParameter,
    isMultiply: BooleanParameter,
    onAmpChange: (FloatParameter) -> Unit,
    onPhaseChange: (FloatParameter) -> Unit,
    onFactorChange: (IntParameter) -> Unit,
    onIsMultiplyChange: (BooleanParameter) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                Spacer(modifier = Modifier.weight(1f))
                
                // Lock active/enabled state toggle
                IconButton(
                    onClick = { onAmpChange(amp.copy(enabledLocked = !amp.enabledLocked)) },
                    modifier = Modifier.size(24.dp).testTag("lock_" + title.substringBefore(" ").lowercase() + "_enabled")
                ) {
                    Icon(
                        imageVector = if (amp.enabledLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = "Lock sublayer active state",
                        tint = if (amp.enabledLocked) Color(0xFFFF4081) else Color(0xFF64748B),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                
                // On/Off Switch for sub oscillator
                Switch(
                    checked = amp.enabled,
                    onCheckedChange = { onAmpChange(amp.copy(enabled = it)) },
                    thumbContent = { Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(12.dp)) },
                    modifier = Modifier.scale(0.7f)
                )
            }
            
            if (amp.enabled) {
                // Sub Amplitudes
                ParameterSliderRow("Sub-Amplitude", amp.current, amp.rangeMin, amp.rangeMax, 1f,
                    isLocked = amp.locked, onLockToggle = { onAmpChange(amp.copy(locked = it)) },
                    isRangeLocked = amp.rangeLocked, onRangeLockToggle = { onAmpChange(amp.withRangeLocked(it)) },
                    selectedMin = amp.actualSelectedMin, selectedMax = amp.actualSelectedMax,
                    onRangeChange = { min, max -> onAmpChange(amp.withRanges(min, max)) },
                    onValueChange = { onAmpChange(amp.withValue(it)) },
                    onRandomize = { onAmpChange(amp.randomize(java.util.Random())) })
                
                if (amp.current > 0f) {
                    ParameterSliderRow("Phase shift", phase.current, phase.rangeMin, phase.rangeMax, 1f, "%.0f°",
                        isLocked = phase.locked, onLockToggle = { onPhaseChange(phase.copy(locked = it)) },
                        isRangeLocked = phase.rangeLocked, onRangeLockToggle = { onPhaseChange(phase.withRangeLocked(it)) },
                        selectedMin = phase.actualSelectedMin, selectedMax = phase.actualSelectedMax,
                        onRangeChange = { min, max -> onPhaseChange(phase.withRanges(min, max)) },
                        onValueChange = { onPhaseChange(phase.withValue(it)) },
                        onRandomize = { onPhaseChange(phase.randomize(java.util.Random())) })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Base factor: ${factor.current}x", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Divide",
                                color = if (!isMultiply.current) Color(0xFF00E5FF) else Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable(enabled = !isMultiply.locked) { onIsMultiplyChange(isMultiply.copy(current = false)) }
                            )
                            Switch(
                                checked = isMultiply.current,
                                onCheckedChange = { onIsMultiplyChange(isMultiply.copy(current = it)) },
                                modifier = Modifier.scale(0.6f),
                                enabled = !isMultiply.locked
                            )
                            Text(
                                text = "Multiply",
                                color = if (isMultiply.current) Color(0xFF00E5FF) else Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable(enabled = !isMultiply.locked) { onIsMultiplyChange(isMultiply.copy(current = true)) }
                            )
                            
                            IconButton(
                                onClick = { onIsMultiplyChange(isMultiply.copy(locked = !isMultiply.locked)) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = if (isMultiply.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Lock multiplication/division",
                                    tint = if (isMultiply.locked) Color(0xFFFF4081) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    
                    ParameterSliderRow("Sub frequency factor", factor.current.toFloat(), factor.rangeMin.toFloat(), factor.rangeMax.toFloat(), 1f, "%.0fx",
                        isLocked = factor.locked, onLockToggle = { onFactorChange(factor.copy(locked = it)) },
                        isRangeLocked = factor.rangeLocked, onRangeLockToggle = { onFactorChange(factor.withRangeLocked(it)) },
                        selectedMin = factor.actualSelectedMin.toFloat(), selectedMax = factor.actualSelectedMax.toFloat(),
                        onRangeChange = { min, max -> onFactorChange(factor.withRanges(min.roundToInt(), max.roundToInt())) },
                        onValueChange = { onFactorChange(factor.withValue(it.roundToInt())) },
                        onRandomize = { onFactorChange(factor.randomize(java.util.Random())) })
                }
            }
        }
    }
}

@Composable
fun ParameterSliderRow(
    label: String,
    value: Float,
    minVal: Float,
    maxVal: Float,
    stepValue: Float,
    formatString: String = "%.1f",
    valueLabelFallback: String? = null,
    isLocked: Boolean,
    onLockToggle: (Boolean) -> Unit,
    isRangeLocked: Boolean,
    onRangeLockToggle: (Boolean) -> Unit,
    selectedMin: Float,
    selectedMax: Float,
    onRangeChange: (Float, Float) -> Unit,
    onValueChange: (Float) -> Unit,
    onRandomize: () -> Unit
) {
    val activeMin = if (isRangeLocked) selectedMin else minVal
    val activeMax = if (isRangeLocked) selectedMax else maxVal

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = valueLabelFallback ?: String.format(formatString, value),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = value.coerceIn(activeMin, activeMax),
                    onValueChange = onValueChange,
                    valueRange = activeMin..maxOf(activeMin + 0.0001f, activeMax),
                    colors = SliderDefaults.colors(
                        thumbColor = if (isLocked) Color.Red else Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF)
                    ),
                    enabled = !isLocked
                )
            }

            // Lock Toggle Badge Button
            IconButton(
                onClick = { onLockToggle(!isLocked) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Lock value",
                    tint = if (isLocked) Color(0xFFFF4081) else Color(0xFF64748B),
                    modifier = Modifier.size(16.dp)
                )
            }

            // Range Lock Badge Button
            IconButton(
                onClick = { onRangeLockToggle(!isRangeLocked) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Range Lock Value",
                    tint = if (isRangeLocked) Color(0xFF00E5FF) else Color(0xFF64748B),
                    modifier = Modifier.size(16.dp)
                )
            }

            // Parameter Specific Randomizer Button
            IconButton(
                onClick = onRandomize,
                modifier = Modifier.size(24.dp),
                enabled = !isLocked
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Randomize parameter",
                    tint = if (isLocked) Color(0xFF334155) else Color(0xFFFFB300),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        
        if (isRangeLocked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 4.dp)
                    .background(Color(0x3300E5FF), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Randomizer Range Limit:", color = Color(0xFF00E5FF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    
                    val minLabel = if (valueLabelFallback != null) {
                        try {
                            val usableFrequenciesLabels = listOf(
                                "1/12×", "1/11×", "1/10×", "1/9×", "1/8×", "1/7×", "1/6×", "1/5×", "1/4×", "1/3×", "1/2×",
                                "1× (Default)", "2×", "3×", "4×", "5×", "6×", "7×", "8×", "9×", "10×", "11×", "12×"
                            )
                            usableFrequenciesLabels[selectedMin.roundToInt().coerceIn(0, usableFrequenciesLabels.size - 1)]
                        } catch(e: Exception) {
                            String.format(formatString, selectedMin)
                        }
                    } else {
                        String.format(formatString, selectedMin)
                    }
                    val maxLabel = if (valueLabelFallback != null) {
                        try {
                            val usableFrequenciesLabels = listOf(
                                "1/12×", "1/11×", "1/10×", "1/9×", "1/8×", "1/7×", "1/6×", "1/5×", "1/4×", "1/3×", "1/2×",
                                "1× (Default)", "2×", "3×", "4×", "5×", "6×", "7×", "8×", "9×", "10×", "11×", "12×"
                            )
                            usableFrequenciesLabels[selectedMax.roundToInt().coerceIn(0, usableFrequenciesLabels.size - 1)]
                        } catch(e: Exception) {
                            String.format(formatString, selectedMax)
                        }
                    } else {
                        String.format(formatString, selectedMax)
                    }
                    
                    Text(
                        text = "$minLabel to $maxLabel",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                RangeSlider(
                    value = selectedMin..selectedMax,
                    onValueChange = { range ->
                        onRangeChange(range.start, range.endInclusive)
                    },
                    valueRange = minVal..maxVal,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF)
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }
        }
    }
}

@Composable
fun RgbSlidersGroup(
    label: String,
    colorValue: Int,
    onColorChange: (Int) -> Unit
) {
    val r = ((colorValue shr 16) and 0xFF).toFloat()
    val g = ((colorValue shr 8) and 0xFF).toFloat()
    val b = (colorValue and 0xFF).toFloat()
    
    var localR by remember(r) { mutableStateOf(r) }
    var localG by remember(g) { mutableStateOf(g) }
    var localB by remember(b) { mutableStateOf(b) }
    
    val displayColor = Color(colorValue)

    val triggerColorChange = { newR: Float, newG: Float, newB: Float ->
        val combined = 0xFF000000.toInt() or (newR.roundToInt() shl 16) or (newG.roundToInt() shl 8) or newB.roundToInt()
        onColorChange(combined)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x1F00E5FF)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(displayColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "R: ${localR.roundToInt()} G: ${localG.roundToInt()} B: ${localB.roundToInt()}",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Red Slider
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("R", color = Color(0xFFFF5252), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp))
                Slider(
                    value = localR,
                    onValueChange = { newVal ->
                        localR = newVal
                        triggerColorChange(newVal, localG, localB)
                    },
                    valueRange = 0f..255f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFFFF5252), activeTrackColor = Color(0xFFFF5252)),
                    modifier = Modifier.weight(1f).height(18.dp)
                )
            }
            
            // Green Slider
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("G", color = Color(0xFF69F0AE), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp))
                Slider(
                    value = localG,
                    onValueChange = { newVal ->
                        localG = newVal
                        triggerColorChange(localR, newVal, localB)
                    },
                    valueRange = 0f..255f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF69F0AE), activeTrackColor = Color(0xFF69F0AE)),
                    modifier = Modifier.weight(1f).height(18.dp)
                )
            }
            
            // Blue Slider
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("B", color = Color(0xFF448AFF), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(12.dp))
                Slider(
                    value = localB,
                    onValueChange = { newVal ->
                        localB = newVal
                        triggerColorChange(localR, localG, newVal)
                    },
                    valueRange = 0f..255f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF448AFF), activeTrackColor = Color(0xFF448AFF)),
                    modifier = Modifier.weight(1f).height(18.dp)
                )
            }
        }
    }
}

@Composable
fun StyleAndPenConfigTab(
    settings: HarmonographSettings,
    onUpdate: (HarmonographSettings) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("RENDERING STYLE OPTIONS", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 12.sp)
        }

        // Style Mode Selectors
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val modeLabels = listOf("solid", "length", "center", "rainbow")
                for (mode in modeLabels) {
                    val active = (settings.styleMode == mode)
                    Button(
                        onClick = { onUpdate(settings.copy(styleMode = mode)) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (active) Color(0xFF00E5FF) else Color(0xFF0F172A),
                            contentColor = if (active) Color.Black else Color.White
                        ),
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(mode.replaceFirstChar { it.uppercase() }, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Solid Color Picker Swatches & RGB Sliders
        if (settings.styleMode == "solid") {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val swatches = listOf(0xFF00E5FF, 0xFFFF4081, 0xFFFFE082, 0xFF69F0AE, 0xFFD1C4E9, 0xFFFFFFFF)
                        Text("Swatch Select:", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(4.dp))
                        for (sw in swatches) {
                            val act = (settings.solidColor == sw.toInt())
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(sw))
                                    .clickable { onUpdate(settings.copy(solidColor = sw.toInt())) }
                            ) {
                                if (act) {
                                    Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp).align(Alignment.Center))
                                }
                            }
                        }
                    }
                    
                    RgbSlidersGroup(
                        label = "Custom Solid Color Controls",
                        colorValue = settings.solidColor,
                        onColorChange = { onUpdate(settings.copy(solidColor = it)) }
                    )
                }
            }
        }

        // Dual Color Gradient Selectors & RGB Sliders
        if (settings.styleMode == "length" || settings.styleMode == "center") {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Color A Swatches:", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        listOf(0xFF00E5FF, 0xFF69F0AE, 0xFFFFFF8D).forEach { sw ->
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(sw))
                                    .clickable { onUpdate(settings.copy(gradientStartColor = sw.toInt())) }
                            ) {
                                if (settings.gradientStartColor == sw.toInt()) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(12.dp).align(Alignment.Center))
                            }
                        }
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        Text("Color B Swatches:", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        listOf(0xFFFF4081, 0xFFE040FB, 0xFFFF3D00).forEach { sw ->
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color(sw))
                                    .clickable { onUpdate(settings.copy(gradientEndColor = sw.toInt())) }
                            ) {
                                if (settings.gradientEndColor == sw.toInt()) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(12.dp).align(Alignment.Center))
                            }
                        }
                    }
                    
                    RgbSlidersGroup(
                        label = "Gradient Start (Color A)",
                        colorValue = settings.gradientStartColor,
                        onColorChange = { onUpdate(settings.copy(gradientStartColor = it)) }
                    )
                    
                    RgbSlidersGroup(
                        label = "Gradient End (Color B)",
                        colorValue = settings.gradientEndColor,
                        onColorChange = { onUpdate(settings.copy(gradientEndColor = it)) }
                    )
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live Color Hue Shifting", color = Color.White, fontSize = 12.sp)
                Spacer(modifier = Modifier.weight(1f))
                Switch(checked = settings.hueShiftingEnabled, onCheckedChange = { onUpdate(settings.copy(hueShiftingEnabled = it)) }, modifier = Modifier.scale(0.8f))
            }
        }

        item {
            Text("PEN & LINE PARAMETERS", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 12.sp)
        }

        // Multi Pen Controllers
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active Pen Count", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        for (p in 1..3) {
                            val active = (settings.penCount == p)
                            Button(
                                onClick = { onUpdate(settings.copy(penCount = p)) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (active) Color(0xFF00E5FF) else Color(0xFF1E293B),
                                    contentColor = if (active) Color.Black else Color.White
                                ),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.padding(horizontal = 4.dp).size(width = 44.dp, height = 30.dp)
                            ) {
                                Text("$p", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (settings.penCount > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Rotational Pen Offset", color = Color.White, fontSize = 12.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            Switch(checked = settings.penRotationEnabled, onCheckedChange = { onUpdate(settings.copy(penRotationEnabled = it)) }, modifier = Modifier.scale(0.7f))
                        }
                        if (settings.penRotationEnabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Rotation Frequency Modes", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.weight(1f))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Divide", color = if (!settings.penRotationIsMultiply) Color(0xFF00E5FF) else Color.White, fontSize = 11.sp, modifier = Modifier.clickable { onUpdate(settings.copy(penRotationIsMultiply = false)) })
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Switch(checked = settings.penRotationIsMultiply, onCheckedChange = { onUpdate(settings.copy(penRotationIsMultiply = it)) }, modifier = Modifier.scale(0.6f))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Multiply", color = if (settings.penRotationIsMultiply) Color(0xFF00E5FF) else Color.White, fontSize = 11.sp, modifier = Modifier.clickable { onUpdate(settings.copy(penRotationIsMultiply = true)) })
                                }
                            }
                            
                            ParameterSliderRow(
                                label = "Rotation Speed Multiplier",
                                value = settings.penRotationMultiplier.current.toFloat(),
                                minVal = settings.penRotationMultiplier.rangeMin.toFloat(),
                                maxVal = settings.penRotationMultiplier.rangeMax.toFloat(),
                                stepValue = 1f,
                                formatString = "%.0fx",
                                isLocked = settings.penRotationMultiplier.locked,
                                onLockToggle = { onUpdate(settings.copy(penRotationMultiplier = settings.penRotationMultiplier.copy(locked = it))) },
                                isRangeLocked = settings.penRotationMultiplier.rangeLocked,
                                onRangeLockToggle = { onUpdate(settings.copy(penRotationMultiplier = settings.penRotationMultiplier.withRangeLocked(it))) },
                                selectedMin = settings.penRotationMultiplier.actualSelectedMin.toFloat(),
                                selectedMax = settings.penRotationMultiplier.actualSelectedMax.toFloat(),
                                onRangeChange = { min, max -> onUpdate(settings.copy(penRotationMultiplier = settings.penRotationMultiplier.withRanges(min.roundToInt(), max.roundToInt()))) },
                                onValueChange = { onUpdate(settings.copy(penRotationMultiplier = settings.penRotationMultiplier.withValue(it.roundToInt()))) },
                                onRandomize = { onUpdate(settings.copy(penRotationMultiplier = settings.penRotationMultiplier.randomize(java.util.Random()))) }
                            )
                        }
                        
                        ParameterSliderRow(
                            label = "Pen displacement spacing",
                            value = settings.penOffset.current,
                            minVal = settings.penOffset.rangeMin,
                            maxVal = settings.penOffset.rangeMax,
                            stepValue = 1f,
                            isLocked = settings.penOffset.locked,
                            onLockToggle = { onUpdate(settings.copy(penOffset = settings.penOffset.copy(locked = it))) },
                            isRangeLocked = settings.penOffset.rangeLocked,
                            onRangeLockToggle = { onUpdate(settings.copy(penOffset = settings.penOffset.withRangeLocked(it))) },
                            selectedMin = settings.penOffset.actualSelectedMin,
                            selectedMax = settings.penOffset.actualSelectedMax,
                            onRangeChange = { min, max -> onUpdate(settings.copy(penOffset = settings.penOffset.withRanges(min, max))) },
                            onValueChange = { onUpdate(settings.copy(penOffset = settings.penOffset.withValue(it))) },
                            onRandomize = { onUpdate(settings.copy(penOffset = settings.penOffset.randomize(java.util.Random()))) }
                        )
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Line Parameter Controls", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    
                    ParameterSliderRow(
                        label = "Line Thickness",
                        value = settings.lineThickness.current,
                        minVal = settings.lineThickness.rangeMin,
                        maxVal = settings.lineThickness.rangeMax,
                        stepValue = 0.1f,
                        formatString = "%.1f px",
                        isLocked = settings.lineThickness.locked,
                        onLockToggle = { onUpdate(settings.copy(lineThickness = settings.lineThickness.copy(locked = it))) },
                        isRangeLocked = settings.lineThickness.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(lineThickness = settings.lineThickness.withRangeLocked(it))) },
                        selectedMin = settings.lineThickness.actualSelectedMin,
                        selectedMax = settings.lineThickness.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(lineThickness = settings.lineThickness.withRanges(min, max))) },
                        onValueChange = { onUpdate(settings.copy(lineThickness = settings.lineThickness.withValue(it))) },
                        onRandomize = { onUpdate(settings.copy(lineThickness = settings.lineThickness.randomize(java.util.Random()))) }
                    )
                }
            }
        }

        // Orthogonal periodic shapes triggers
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Orthogonal Path Geometric Shapes", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("none", "circle", "triangle", "star").forEach { shape ->
                            val act = (settings.periodicShape == shape)
                            Button(
                                onClick = { onUpdate(settings.copy(periodicShape = shape)) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (act) Color(0xFFFF4081) else Color(0xFF1E293B),
                                    contentColor = Color.White
                                ),
                                modifier = Modifier.weight(1f).height(30.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(shape.replaceFirstChar { it.uppercase() }, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (settings.periodicShape != "none") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Fill Solid Shape", color = Color.White, fontSize = 12.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            Switch(checked = settings.periodicShapeSolid, onCheckedChange = { onUpdate(settings.copy(periodicShapeSolid = it)) }, modifier = Modifier.scale(0.7f))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Spawn rate modes", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Divide", color = if (!settings.periodicShapeFreqIsMultiply) Color(0xFF00E5FF) else Color.White, fontSize = 11.sp, modifier = Modifier.clickable { onUpdate(settings.copy(periodicShapeFreqIsMultiply = false)) })
                                Spacer(modifier = Modifier.width(6.dp))
                                Switch(checked = settings.periodicShapeFreqIsMultiply, onCheckedChange = { onUpdate(settings.copy(periodicShapeFreqIsMultiply = it)) }, modifier = Modifier.scale(0.6f))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Multiply", color = if (settings.periodicShapeFreqIsMultiply) Color(0xFF00E5FF) else Color.White, fontSize = 11.sp, modifier = Modifier.clickable { onUpdate(settings.copy(periodicShapeFreqIsMultiply = true)) })
                            }
                        }
                        
                        ParameterSliderRow(
                            label = "Spawn rate freq multiplier",
                            value = settings.periodicShapeFreqFactor.current.toFloat(),
                            minVal = settings.periodicShapeFreqFactor.rangeMin.toFloat(),
                            maxVal = settings.periodicShapeFreqFactor.rangeMax.toFloat(),
                            stepValue = 1f,
                            formatString = "%.0fx",
                            isLocked = settings.periodicShapeFreqFactor.locked,
                            onLockToggle = { onUpdate(settings.copy(periodicShapeFreqFactor = settings.periodicShapeFreqFactor.copy(locked = it))) },
                            isRangeLocked = settings.periodicShapeFreqFactor.rangeLocked,
                            onRangeLockToggle = { onUpdate(settings.copy(periodicShapeFreqFactor = settings.periodicShapeFreqFactor.withRangeLocked(it))) },
                            selectedMin = settings.periodicShapeFreqFactor.actualSelectedMin.toFloat(),
                            selectedMax = settings.periodicShapeFreqFactor.actualSelectedMax.toFloat(),
                            onRangeChange = { min, max -> onUpdate(settings.copy(periodicShapeFreqFactor = settings.periodicShapeFreqFactor.withRanges(min.roundToInt(), max.roundToInt()))) },
                            onValueChange = { onUpdate(settings.copy(periodicShapeFreqFactor = settings.periodicShapeFreqFactor.withValue(it.roundToInt()))) },
                            onRandomize = { onUpdate(settings.copy(periodicShapeFreqFactor = settings.periodicShapeFreqFactor.randomize(java.util.Random()))) }
                        )

                        ParameterSliderRow(
                            label = "Shape Dimension Size",
                            value = settings.periodicShapeSize.current,
                            minVal = settings.periodicShapeSize.rangeMin,
                            maxVal = settings.periodicShapeSize.rangeMax,
                            stepValue = 1f,
                            isLocked = settings.periodicShapeSize.locked,
                            onLockToggle = { onUpdate(settings.copy(periodicShapeSize = settings.periodicShapeSize.copy(locked = it))) },
                            isRangeLocked = settings.periodicShapeSize.rangeLocked,
                            onRangeLockToggle = { onUpdate(settings.copy(periodicShapeSize = settings.periodicShapeSize.withRangeLocked(it))) },
                            selectedMin = settings.periodicShapeSize.actualSelectedMin,
                            selectedMax = settings.periodicShapeSize.actualSelectedMax,
                            onRangeChange = { min, max -> onUpdate(settings.copy(periodicShapeSize = settings.periodicShapeSize.withRanges(min, max))) },
                            onValueChange = { onUpdate(settings.copy(periodicShapeSize = settings.periodicShapeSize.withValue(it))) },
                            onRandomize = { onUpdate(settings.copy(periodicShapeSize = settings.periodicShapeSize.randomize(java.util.Random()))) }
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Concentric Layers: ${settings.periodicShapeConcentric}x", color = Color.White, fontSize = 12.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            for (l in 1..3) {
                                val act = (settings.periodicShapeConcentric == l)
                                Button(
                                    onClick = { onUpdate(settings.copy(periodicShapeConcentric = l)) },
                                    colors = ButtonDefaults.buttonColors(containerColor = if (act) Color(0xFFFF4081) else Color(0xFF1E293B)),
                                    modifier = Modifier.size(width = 38.dp, height = 28.dp).padding(horizontal = 2.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("$l", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (settings.periodicShapeConcentric > 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Layer Deployment Mode", color = Color.White, fontSize = 12.sp)
                                Spacer(modifier = Modifier.weight(1f))
                                Row {
                                    val isProgressive = settings.periodicShapeDeployment == "progressive"
                                    Button(
                                        onClick = { onUpdate(settings.copy(periodicShapeDeployment = "stacked")) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (!isProgressive) Color(0xFFFF4081) else Color(0xFF1E293B),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.height(28.dp).padding(horizontal = 2.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text("Stacked", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { onUpdate(settings.copy(periodicShapeDeployment = "progressive")) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isProgressive) Color(0xFFFF4081) else Color(0xFF1E293B),
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.height(28.dp).padding(horizontal = 2.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Text("Progressive", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Active Pen Tip Configuration Section
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active Pen Tip Marker", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.penTipEnabled,
                            onCheckedChange = { onUpdate(settings.copy(penTipEnabled = it)) },
                            modifier = Modifier.scale(0.8f).testTag("pen_tip_switch")
                        )
                    }

                    if (settings.penTipEnabled) {
                        Text("Pen Tip Marker Shape", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf("circle", "square", "diamond", "cross", "star").forEach { shape ->
                                val act = (settings.penTipShape == shape)
                                Button(
                                    onClick = { onUpdate(settings.copy(penTipShape = shape)) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (act) Color(0xFF00E5FF) else Color(0xFF1E293B),
                                        contentColor = if (act) Color.Black else Color.White
                                    ),
                                    modifier = Modifier.weight(1f).height(28.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(shape.replaceFirstChar { it.uppercase() }, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Color Behavior", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val matchLine = settings.penTipColorMode == "match_line"
                                Button(
                                    onClick = { onUpdate(settings.copy(penTipColorMode = "match_line")) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (matchLine) Color(0xFF00E5FF) else Color(0xFF1E293B),
                                        contentColor = if (matchLine) Color.Black else Color.White
                                    ),
                                    modifier = Modifier.height(26.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Match Line", fontSize = 10.sp)
                                }
                                Button(
                                    onClick = { onUpdate(settings.copy(penTipColorMode = "solid")) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (!matchLine) Color(0xFF00E5FF) else Color(0xFF1E293B),
                                        contentColor = if (!matchLine) Color.Black else Color.White
                                    ),
                                    modifier = Modifier.height(26.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Custom Color", fontSize = 10.sp)
                                }
                            }
                        }

                        if (settings.penTipColorMode == "solid") {
                            RgbSlidersGroup(
                                label = "Edit Pen Tip Color",
                                colorValue = settings.penTipColor,
                                onColorChange = { onUpdate(settings.copy(penTipColor = it)) }
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text("Marker Size: ${settings.penTipSize.roundToInt()}dp", color = Color.White, fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Slider(
                                value = settings.penTipSize,
                                onValueChange = { onUpdate(settings.copy(penTipSize = it)) },
                                valueRange = 4f..24f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraAndSetupTab(
    settings: HarmonographSettings,
    onUpdate: (HarmonographSettings) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("CAMERA VIEWPORTS & RIDE", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 12.sp)
        }

        // Perspectives selectors
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                val act1 = (settings.cameraPerspective == 1)
                Button(
                    onClick = { onUpdate(settings.copy(cameraPerspective = 1)) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (act1) Color(0xFF00E5FF) else Color(0xFF0F172A), contentColor = if (act1) Color.Black else Color.White),
                    modifier = Modifier.weight(1f).height(42.dp),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Full View", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Optimal overview viewport", fontSize = 8.sp, color = if (act1) Color.DarkGray else Color.LightGray)
                    }
                }

                val act2 = (settings.cameraPerspective == 2)
                Button(
                    onClick = { onUpdate(settings.copy(cameraPerspective = 2)) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (act2) Color(0xFF00E5FF) else Color(0xFF0F172A), contentColor = if (act2) Color.Black else Color.White),
                    modifier = Modifier.weight(1f).height(42.dp),
                    contentPadding = PaddingValues(2.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Roller Coaster", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("Dynamic pen-tip flight", fontSize = 8.sp, color = if (act2) Color.DarkGray else Color.LightGray)
                    }
                }
            }
        }

        // Camera Distance Parameters
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Distance & View Settings", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    
                    ParameterSliderRow(
                        label = "Camera Distance",
                        value = settings.cameraDistance.current,
                        minVal = settings.cameraDistance.rangeMin,
                        maxVal = settings.cameraDistance.rangeMax,
                        stepValue = 2f,
                        formatString = "%.0f px",
                        isLocked = settings.cameraDistance.locked,
                        onLockToggle = { onUpdate(settings.copy(cameraDistance = settings.cameraDistance.copy(locked = it))) },
                        isRangeLocked = settings.cameraDistance.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(cameraDistance = settings.cameraDistance.withRangeLocked(it))) },
                        selectedMin = settings.cameraDistance.actualSelectedMin,
                        selectedMax = settings.cameraDistance.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(cameraDistance = settings.cameraDistance.withRanges(min, max))) },
                        onValueChange = { onUpdate(settings.copy(cameraDistance = settings.cameraDistance.withValue(it))) },
                        onRandomize = { onUpdate(settings.copy(cameraDistance = settings.cameraDistance.randomize(java.util.Random()))) }
                    )


                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto 3D Camera Rotation", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = settings.cameraAutoRotationEnabled, onCheckedChange = { onUpdate(settings.copy(cameraAutoRotationEnabled = it)) }, modifier = Modifier.scale(0.7f))
                    }

                    if (settings.cameraAutoRotationEnabled) {
                        Text("Rotation Speed: ${"%.1f".format(settings.cameraAutoRotationSpeed)}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        Slider(
                            value = settings.cameraAutoRotationSpeed,
                            onValueChange = { onUpdate(settings.copy(cameraAutoRotationSpeed = it)) },
                            valueRange = 0.1f..2.5f
                        )
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Lock View Perpendicular to Plane", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.isAngularLockEnabled,
                        onCheckedChange = { onUpdate(settings.copy(isAngularLockEnabled = it)) },
                        modifier = Modifier.scale(0.8f).testTag("angular_lock_switch")
                    )
                }
                if (settings.isAngularLockEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("X", "Y", "Z").forEach { axis ->
                            val act = (settings.angularLockAxis == axis)
                            Button(
                                onClick = { onUpdate(settings.copy(angularLockAxis = axis)) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (act) Color(0xFF00E5FF) else Color(0xFF1E293B),
                                    contentColor = if (act) Color.Black else Color.White
                                ),
                                modifier = Modifier.weight(1f).height(34.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("$axis Axis Lock", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("DRAW TIMING LIMITS & SPEEDS", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 12.sp)
        }

        // Draw Speed Slider
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Drawspeed completion speed", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Instant draw", color = if (settings.drawSpeedInstant) Color(0xFF00E5FF) else Color.White, fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Switch(checked = settings.drawSpeedInstant, onCheckedChange = { onUpdate(settings.copy(drawSpeedInstant = it)) }, modifier = Modifier.scale(0.6f))
                        }
                    }

                    if (!settings.drawSpeedInstant) {
                        Text("Duration: ${"%.1f".format(settings.drawSpeedMinutes)} minutes", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        Slider(
                            value = settings.drawSpeedMinutes,
                            onValueChange = { onUpdate(settings.copy(drawSpeedMinutes = it)) },
                            valueRange = 1.0f..15.0f
                        )
                    }
                }
            }
        }

        // Dynamic Loop Reset After Completion
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Post Completion Auto Reset", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(checked = settings.postCompletionAutoReset, onCheckedChange = { onUpdate(settings.copy(postCompletionAutoReset = it)) }, modifier = Modifier.scale(0.8f))
                    }

                    if (settings.postCompletionAutoReset) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Wait 25% of draw completion time", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            Switch(
                                checked = settings.postCompletionResetTimeFactor == 0.25f,
                                onCheckedChange = { onUpdate(settings.copy(postCompletionResetTimeFactor = if (it) 0.25f else 0f)) },
                                modifier = Modifier.scale(0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsTab(
    presets: List<HarmonographPreset>,
    activeSettings: HarmonographSettings,
    viewModel: HarmonographViewModel
) {
    val context = LocalContext.current
    var customPresetName by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        Text("SAVED DRAWING PRESETS", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 12.sp)
        
        // Save current preset block
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = customPresetName,
                onValueChange = { customPresetName = it },
                label = { Text("Custom Preset Title", fontSize = 11.sp, color = Color.White) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("preset_name_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color.White,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Button(
                onClick = {
                    if (customPresetName.isNotBlank()) {
                        viewModel.savePreset(customPresetName)
                        customPresetName = ""
                        Toast.makeText(context, "Preset Saved!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Give it a title first!", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                modifier = Modifier.height(48.dp).testTag("save_preset_button")
            ) {
                Text("Save", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // List presets
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(presets) { preset ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).clickable {
                            viewModel.loadPreset(preset)
                            Toast.makeText(context, "Loaded: ${preset.name}", Toast.LENGTH_SHORT).show()
                        }) {
                            Text(preset.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(if (preset.isUserPreset) "My Custom Preset" else "Factory Standard Preset", color = Color(0xFF94A3B8), fontSize = 10.sp)
                        }

                        if (preset.isUserPreset) {
                            IconButton(onClick = { viewModel.deletePreset(preset.id) }) {
                                Icon(Icons.Default.Delete, "Delete preset", tint = Color(0xFFFF4081))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun computeComposeColor(
    settings: HarmonographSettings,
    idx: Int,
    total: Int,
    pt: ProjectedPoint,
    width: Float,
    height: Float,
    hueOffset: Long
): Color {
    val sat = settings.saturation.current
    return when (settings.styleMode) {
        "solid" -> {
            adjustComposeColor(Color(settings.solidColor), sat, hueOffset)
        }
        "length" -> {
            val ratio = idx.toFloat() / total.coerceAtLeast(1)
            val colorVal = interpolateComposeColor(Color(settings.gradientStartColor), Color(settings.gradientEndColor), ratio)
            adjustComposeColor(colorVal, sat, hueOffset)
        }
        "center" -> {
            val maxDist3D = sqrt(
                settings.ampX.current * settings.ampX.current +
                settings.ampY.current * settings.ampY.current +
                settings.ampZ.current * settings.ampZ.current
            ).coerceAtLeast(10f)
            val ratio = (pt.dist3D / maxDist3D).coerceIn(0f, 1f)
            val colorVal = interpolateComposeColor(Color(settings.gradientStartColor), Color(settings.gradientEndColor), ratio)
            adjustComposeColor(colorVal, sat, hueOffset)
        }
        else -> {
            val baseHue = (idx.toFloat() / total.coerceAtLeast(1)) * 360f
            val finalHue = (baseHue + hueOffset) % 360f
            Color.hsv(finalHue, sat, 0.95f)
        }
    }
}

private fun adjustComposeColor(color: Color, sat: Float, hueOffset: Long): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(
        (color.alpha * 255).roundToInt(),
        (color.red * 255).roundToInt(),
        (color.green * 255).roundToInt(),
        (color.blue * 255).roundToInt()
    ), hsv)
    hsv[1] = sat
    if (hueOffset != 0L) {
        hsv[0] = (hsv[0] + hueOffset) % 360f
    }
    val alphaInt = (color.alpha * 255).roundToInt()
    val rawInt = android.graphics.Color.HSVToColor(alphaInt, hsv)
    return Color(rawInt)
}

private fun interpolateComposeColor(c1: Color, c2: Color, ratio: Float): Color {
    val r = c1.red + ratio * (c2.red - c1.red)
    val g = c1.green + ratio * (c2.green - c1.green)
    val b = c1.blue + ratio * (c2.blue - c1.blue)
    val a = c1.alpha + ratio * (c2.alpha - c1.alpha)
    return Color(r, g, b, a)
}

private fun drawComposeOrthogonalShape(
    shape: CustomShapeData,
    yawVal: Float,
    pitchVal: Float,
    perspective: Int,
    width: Float,
    height: Float,
    angularLock: Boolean,
    angularLockAxis: String,
    timeHueOffset: Long,
    totalSteps: Int,
    settings: HarmonographSettings,
    scaleFactor: Float,
    mainPathPoints: List<Point3D> = emptyList(),
    cameraTargetIndex: Float = -1f,
    animTime: Long = 0L
) {
    // Standard DrawScope cannot be accessed outside draw extension function.
}

private fun DrawScope.drawComposeOrthogonalShape(
    shape: CustomShapeData,
    yawVal: Float,
    pitchVal: Float,
    perspective: Int,
    width: Float,
    height: Float,
    angularLock: Boolean,
    angularLockAxis: String,
    timeHueOffset: Long,
    totalSteps: Int,
    settings: HarmonographSettings,
    scaleFactor: Float,
    mainPathPoints: List<Point3D> = emptyList(),
    cameraTargetIndex: Float = -1f,
    animTime: Long = 0L
) {
    val concentricLevels = shape.concentric
    val baseSize = shape.size

    val vertices = when (shape.shapeType) {
        "circle" -> 16
        "triangle" -> 3
        else -> 10 // Star
    }

    for (conc in 0 until concentricLevels) {
        val scaleF = 1f + conc * 0.5f
        val size = baseSize * scaleF
        
        val centerPt3D = if (shape.deployment == "progressive") {
            shape.center + (shape.uVector.cross(shape.wVector) * (conc * size * 0.4f))
        } else {
            shape.center
        }

        val shape3DPoints = mutableListOf<Point3D>()
        for (v in 0 until vertices) {
            val angle = (2f * PI * v / vertices).toFloat()
            val r = if (shape.shapeType == "star" && v % 2 != 0) {
                size / 2.5f
            } else {
                size
            }
            
            val offsetVec = shape.uVector * cos(angle) * r + shape.wVector * sin(angle) * r
            shape3DPoints.add(centerPt3D + offsetVec)
        }
        if (shape3DPoints.isNotEmpty()) {
            shape3DPoints.add(shape3DPoints[0])
        }

        val projPts = HarmonographMath.project3DTo2D(
            points = shape3DPoints,
            yaw = yawVal,
            pitch = pitchVal,
            perspective = perspective,
            currentDrawProgress = shape3DPoints.size.toFloat(),
            screenWidth = width,
            screenHeight = height,
            angularLock = angularLock,
            angularLockAxis = angularLockAxis,
            referencePoints = mainPathPoints.ifEmpty { null },
            cameraTargetIndex = cameraTargetIndex,
            cameraDistance = settings.cameraDistance.current,
            dynamicCameraZoomEnabled = settings.dynamicCameraZoomEnabled,
            coasterDirectionFacing = settings.coasterDirectionFacing,
            animTime = animTime
        )

        if (projPts.size < 2) continue

        val centerProj = HarmonographMath.project3DTo2D(
            points = listOf(centerPt3D),
            yaw = yawVal,
            pitch = pitchVal,
            perspective = perspective,
            currentDrawProgress = 1f,
            screenWidth = width,
            screenHeight = height,
            angularLock = angularLock,
            angularLockAxis = angularLockAxis,
            referencePoints = mainPathPoints.ifEmpty { null },
            cameraTargetIndex = cameraTargetIndex,
            cameraDistance = settings.cameraDistance.current,
            dynamicCameraZoomEnabled = settings.dynamicCameraZoomEnabled,
            coasterDirectionFacing = settings.coasterDirectionFacing,
            animTime = animTime
        )

        val centerPtScreen = centerProj.firstOrNull() ?: continue
        val shapeColor = computeComposeColor(settings, shape.colorIndex, totalSteps, centerPtScreen, width, height, timeHueOffset)

        if (shape.isSolid) {
            val pathCompose = Path()
            pathCompose.moveTo(projPts[0].x, projPts[0].y)
            for (ptIdx in 1 until projPts.size) {
                pathCompose.lineTo(projPts[ptIdx].x, projPts[ptIdx].y)
            }
            pathCompose.close()
            drawPath(
                path = pathCompose,
                color = shapeColor.copy(alpha = 0.55f)
            )
        } else {
            for (pIndex in 0 until projPts.size - 1) {
                drawLine(
                    color = shapeColor,
                    start = androidx.compose.ui.geometry.Offset(projPts[pIndex].x, projPts[pIndex].y),
                    end = androidx.compose.ui.geometry.Offset(projPts[pIndex + 1].x, projPts[pIndex + 1].y),
                    strokeWidth = 1.6f * scaleFactor
                )
            }
        }
    }
}
