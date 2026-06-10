@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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
import androidx.compose.ui.focus.onFocusChanged
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
        var lastTime = 0L
        var frameCount = 0
        var fpsAccumTime = 0L
        while (true) {
            withFrameMillis { time ->
                animTime = time
                if (lastTime > 0L) {
                    val dtFrame = time - lastTime
                    if (dtFrame > 0L) {
                        frameCount++
                        fpsAccumTime += dtFrame
                        if (fpsAccumTime >= 500L) {
                            val measuredFps = (frameCount * 1000f) / fpsAccumTime
                            viewModel.updateFps(measuredFps.coerceAtLeast(1f))
                            frameCount = 0
                            fpsAccumTime = 0L
                        }
                    }
                }
                lastTime = time
            }
        }
    }

    // Dynamic rotation angle calculation driven by the animation timer state
    val animatedYaw = if (settings.cameraAutoRotationEnabled) {
        (yaw + animTime * 0.001f * settings.cameraAutoRotationSpeed * 25f + viewModel.gyroYawOffset.value) % 360f
    } else {
        yaw + viewModel.gyroYawOffset.value
    }

    val animatedPitch = if (settings.cameraAutoRotationEnabled) {
        pitch + (sin(animTime * 0.001f * settings.cameraAutoRotationSpeed * 0.5f) * 15f) + viewModel.gyroPitchOffset.value
    } else {
        pitch + viewModel.gyroPitchOffset.value
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
            var completionTimeOfAnim by remember { mutableStateOf<Long?>(null) }
            val stepsCount = settings.drawLengthSteps
            if (drawProgress < stepsCount - 1f) {
                completionTimeOfAnim = null
            } else if (completionTimeOfAnim == null) {
                completionTimeOfAnim = animTime
            }

            val paths = remember(settings) {
                HarmonographMath.generatePathPoints(settings, stepsCount)
            }
            val shapes = remember(settings) {
                HarmonographMath.generatePeriodicShapes(settings, stepsCount)
            }
            
            val timeHueOffset = if (settings.hueShiftingEnabled) {
                (animTime * settings.hueShiftSpeed.current / 360).toLong() % 360
            } else {
                0L
            }

            Canvas(modifier = Modifier.fillMaxSize().testTag("3d_harmonograph_canvas")) {
                val drawLimit = drawProgress.roundToInt()
                var width = size.width
                var height = size.height
                
                val perfResolutionStr = settings.perfResolution
                val targetRes = perfResolutionStr.toIntOrNull() ?: -1
                val isScaled = targetRes > 0 && width.coerceAtLeast(height) > targetRes
                val scaleFactorGlobal = if (isScaled) targetRes.toFloat() / width.coerceAtLeast(height) else 1f
                
                if (isScaled) {
                    width *= scaleFactorGlobal
                    height *= scaleFactorGlobal
                }

                val cameraTargetIndex = if (settings.cameraPerspective == 2 && drawProgress >= stepsCount.coerceAtLeast(1) - 1f) {
                    val durationMin = if (settings.drawSpeedInstant) 18.0f else (settings.drawSpeedMinutes.current * 7.0f).coerceAtLeast(15.0f)
                    val cycleDurationMs = (durationMin * 60f * 1000f).toLong().coerceAtLeast(1000L)
                    val startT = completionTimeOfAnim ?: animTime
                    val completedTime = (animTime - startT).coerceAtLeast(0L)
                    val fraction = (completedTime.toFloat() / cycleDurationMs) % 1.0f
                    val stepsInPath = paths.firstOrNull()?.size ?: stepsCount
                    ((stepsInPath - 1f + (fraction * stepsInPath)) % stepsInPath).coerceIn(0f, (stepsInPath - 1).toFloat())
                } else {
                    drawProgress
                }

                // Precalculate smooth center path as reference tracker for camera perspective
                val centerPath = if (paths.size > 1 && paths.firstOrNull()?.isNotEmpty() == true) {
                    val pSize = paths[0].size
                    List(pSize) { i ->
                        var sx = 0f
                        var sy = 0f
                        var sz = 0f
                        for (pIdx in paths.indices) {
                            val pt = paths[pIdx][i]
                            sx += pt.x
                            sy += pt.y
                            sz += pt.z
                        }
                        com.example.Point3D(sx / paths.size, sy / paths.size, sz / paths.size)
                    }
                } else {
                    paths.firstOrNull() ?: emptyList()
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
                        referencePoints = centerPath.ifEmpty { null },
                        cameraTargetIndex = cameraTargetIndex,
                        cameraDistance = settings.cameraDistance.current,
                        dynamicCameraZoomEnabled = settings.dynamicCameraZoomEnabled,
                        coasterDirectionFacing = settings.coasterDirectionFacing,
                        animTime = animTime,
                        coasterDeviationAngle = settings.coasterDeviationAngle.current,
                        coasterOrbitSpeed = settings.coasterOrbitSpeed.current,
                        isPrimaryPath = (pIdx == 0),
                        tailLengthLimit = if (settings.drawSpeedInstant && !settings.instantDrawLengthInfinite.current) settings.instantDrawLengthLimit.current else -1
                    )
                    
                    if (projPoints.isEmpty()) continue

                    // Gather line segments
                    for (i in 0 until projPoints.size - 1) {
                        val p1 = projPoints[i]
                        val p2 = projPoints[i + 1]
                        
                        if (p1.isBehindCamera || p2.isBehindCamera) {
                            continue
                        }
                        
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

                withTransform({
                    if (isScaled) {
                        scale(1f / scaleFactorGlobal, 1f / scaleFactorGlobal, pivot = androidx.compose.ui.geometry.Offset.Zero)
                    }
                }) {
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
                                val Math_PI = Math.PI.toFloat()
                                for (i in 0 until 10) {
                                    val angle = (i * Math_PI / 5f)
                                    val r = if (i % 2 == 0) s else s * 0.4f
                                    val px = tip.x + r * kotlin.math.cos(angle - Math_PI / 2f)
                                    val py = tip.y + r * kotlin.math.sin(angle - Math_PI / 2f)
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
                    TelemetryRow("XYZ Freqs", "${"%.3f".format(settings.activeFreqX)}x, ${"%.3f".format(settings.activeFreqY)}x, ${"%.3f".format(settings.activeFreqZ)}x")
                    TelemetryRow("Decays", "${"%.4f".format(settings.decayX.current)}, ${"%.4f".format(settings.decayY.current)}, ${"%.4f".format(settings.decayZ.current)}")
                    TelemetryRow("Phases", "${settings.phaseX.current.roundToInt()}°, ${settings.phaseY.current.roundToInt()}°, ${settings.phaseZ.current.roundToInt()}°")
                    if (settings.ampSubX.current > 0 || settings.ampSubY.current > 0 || settings.ampSubZ.current > 0) {
                        TelemetryRow("SubAmps", "${settings.ampSubX.current.roundToInt()}, ${settings.ampSubY.current.roundToInt()}, ${settings.ampSubZ.current.roundToInt()}")
                    }
                    TelemetryRow("Pen Mode", if (settings.penCount.current > 1) "${settings.penCount.current} Pens (${if (settings.penRotationEnabled.current) "Rotational" else "Parallel"})" else "1 Pen")
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

         // Drawing progress controllers (Only shown when settings panel is collapsed)
         if (!isPanelExpanded) {
             Column(
                 modifier = Modifier
                     .align(Alignment.BottomCenter)
                     .padding(bottom = 40.dp)
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
                         onClick = { isPanelExpanded = true },
                         colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1E293B)),
                         modifier = Modifier.testTag("app_settings_button")
                     ) {
                         Icon(
                             imageVector = Icons.Default.Settings,
                             contentDescription = "Settings Panels",
                             tint = Color.White
                         )
                     }
                 }
             }
         }
 
         // Expanded Control Panel Drawer (Full-Screen Sliding Layout)
         AnimatedVisibility(
             visible = isPanelExpanded,
             enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
             exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
             modifier = Modifier
                 .align(Alignment.BottomCenter)
                 .fillMaxWidth()
                 .fillMaxHeight()
         ) {
             Surface(
                 color = Color(0xFF1E293B), // Slate 800
                 tonalElevation = 8.dp,
                 modifier = Modifier
                     .fillMaxSize()
                     .statusBarsPadding()
                     .navigationBarsPadding()
                     .testTag("control_panel_drawer")
             ) {
                 Column {
                     // Full-Screen Close and Title Header Bar
                     Row(
                         modifier = Modifier
                             .fillMaxWidth()
                             .background(Color(0xFF0F172A))
                             .padding(horizontal = 16.dp, vertical = 12.dp),
                         horizontalArrangement = Arrangement.SpaceBetween,
                         verticalAlignment = Alignment.CenterVertically
                     ) {
                         Text(
                             text = "HARMONOGRAPH CONFIGURATION",
                             fontSize = 13.sp,
                             fontWeight = FontWeight.Bold,
                             color = Color(0xFF00E5FF),
                             letterSpacing = 1.sp
                         )
                         IconButton(
                             onClick = { isPanelExpanded = false },
                             colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1E293B))
                         ) {
                             Icon(
                                  imageVector = Icons.Default.Close,
                                  contentDescription = "Close Settings",
                                  tint = Color(0xFFFF4081)
                             )
                         }
                     }
 
                     TabRow(
                         selectedTabIndex = activeTab,
                         containerColor = Color(0xFF0F172A),
                         contentColor = Color.White,
                         indicator = { tabPositions ->
                             TabRowDefaults.SecondaryIndicator(
                                 modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                                 color = Color(0xFF00E5FF)
                             )
                         }
                     ) {
                         Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                             Text("Oscillators", modifier = Modifier.padding(vertical = 14.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                         }
                         Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                             Text("Style & Pen", modifier = Modifier.padding(vertical = 14.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                         }
                         Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                             Text("Camera", modifier = Modifier.padding(vertical = 14.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                         }
                         Tab(selected = activeTab == 3, onClick = { activeTab = 3 }) {
                             Text("Presets", modifier = Modifier.padding(vertical = 14.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                         }
                         Tab(selected = activeTab == 4, onClick = { activeTab = 4 }) {
                             Text("Perf & Quality", modifier = Modifier.padding(vertical = 14.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                            4 -> PerformanceAndQualityTab(settings = settings, onUpdate = { viewModel.updateSettings(it) }, viewModel = viewModel)
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
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PRIMARY XYZ OSCILLATORS", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 11.sp)
                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp)
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable Friction/Decay", color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { onUpdate(settings.copy(decayEnabled = settings.decayEnabled.copy(locked = !settings.decayEnabled.locked))) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (settings.decayEnabled.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Lock Friction/Decay",
                                tint = if (settings.decayEnabled.locked) Color(0xFF00E5FF) else Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Switch(
                            checked = settings.decayEnabled.current,
                            onCheckedChange = { onUpdate(settings.copy(decayEnabled = settings.decayEnabled.withValue(it))) },
                            modifier = Modifier.scale(0.75f).testTag("decay_enabled_switch")
                        )
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Rational Frequencies Only", color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.rationalFrequenciesEnabled,
                            onCheckedChange = { onUpdate(settings.copy(rationalFrequenciesEnabled = it)) },
                            modifier = Modifier.scale(0.75f).testTag("rational_frequencies_switch")
                        )
                    }
                }
            }
        }
        
        // Axis configuration blocks
        item {
            AxisConfigCard("X-Axis Control", settings.ampX, settings.freqX, settings.decayX, settings.phaseX, settings.decayEnabled.current,
                onAmpChange = { onUpdate(settings.copy(ampX = it)) },
                onFreqChange = { onUpdate(settings.copy(freqX = it)) },
                onDecayChange = { onUpdate(settings.copy(decayX = it)) },
                onPhaseChange = { onUpdate(settings.copy(phaseX = it)) })
        }
        item {
            AxisConfigCard("Y-Axis Control", settings.ampY, settings.freqY, settings.decayY, settings.phaseY, settings.decayEnabled.current,
                onAmpChange = { onUpdate(settings.copy(ampY = it)) },
                onFreqChange = { onUpdate(settings.copy(freqY = it)) },
                onDecayChange = { onUpdate(settings.copy(decayY = it)) },
                onPhaseChange = { onUpdate(settings.copy(phaseY = it)) })
        }
        item {
            AxisConfigCard("Z-Axis Control (3D Depth)", settings.ampZ, settings.freqZ, settings.decayZ, settings.phaseZ, settings.decayEnabled.current,
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

                    val freqValueMapper: (Float) -> String = { idx ->
                        val index = idx.roundToInt().coerceIn(0, usableFrequencies.size - 1)
                        usableFrequenciesLabels[index].replace("x", "")
                    }
                    val freqValueParser: (String) -> Float? = { text ->
                        val parsedFloat = parseMathExpression(text)
                        if (parsedFloat != null) {
                            val nearestIdx = usableFrequencies.mapIndexed { idx, f -> idx to kotlin.math.abs(f - parsedFloat) }
                                .minByOrNull { it.second }?.first
                            nearestIdx?.toFloat()
                        } else {
                            null
                        }
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
                        onRandomize = { onFreqChange(freq.randomize(java.util.Random())) },
                        valueMapper = freqValueMapper,
                        valueParser = freqValueParser
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

private fun formatCleanFloat(v: Float): String {
    if (v % 1f == 0f) return v.toInt().toString()
    val s = String.format(java.util.Locale.US, "%.5f", v)
    if (s.contains(".")) {
        return s.dropLastWhile { it == '0' }.dropLastWhile { it == '.' }
    }
    return s
}

private fun parseMathExpression(input: String): Float? {
    val clean = input.replace(" ", "").replace(",", ".")
    if (clean.isBlank()) return null
    clean.toFloatOrNull()?.let { return it }
    
    // Evaluate simple division (e.g., "1/12")
    if (clean.contains("/")) {
        val parts = clean.split("/")
        if (parts.size == 2) {
            val num = parts[0].toFloatOrNull()
            val den = parts[1].toFloatOrNull()
            if (num != null && den != null && den != 0f) {
                return num / den
            }
        }
    }
    if (clean.contains("*")) {
        val parts = clean.split("*")
        if (parts.size == 2) {
            val p1 = parts[0].toFloatOrNull()
            val p2 = parts[1].toFloatOrNull()
            if (p1 != null && p2 != null) return p1 * p2
        }
    }
    if (clean.contains("+")) {
        val parts = clean.split("+")
        if (parts.size == 2) {
            val p1 = parts[0].toFloatOrNull()
            val p2 = parts[1].toFloatOrNull()
            if (p1 != null && p2 != null) return p1 + p2
        }
    }
    if (clean.contains("-")) {
        val parts = clean.split("-")
        if (parts.size == 2) {
            val p1 = parts[0].toFloatOrNull()
            val p2 = parts[1].toFloatOrNull()
            if (p1 != null && p2 != null) return p1 - p2
        }
    }
    return null
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
    onRandomize: () -> Unit,
    valueMapper: ((Float) -> String)? = null,
    valueParser: ((String) -> Float?)? = null
) {
    val activeMin = if (isRangeLocked) minOf(selectedMin, selectedMax) else minVal
    val activeMax = if (isRangeLocked) maxOf(selectedMin, selectedMax) else maxVal

    val defaultMapper: (Float) -> String = { formatCleanFloat(it) }
    val defaultParser: (String) -> Float? = { parseMathExpression(it) }

    val mapper = valueMapper ?: defaultMapper
    val parser = valueParser ?: defaultParser

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

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
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text("Configure Randomizer Range (Same value on both locks to static):", color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var minInput by remember(selectedMin) { mutableStateOf(mapper(selectedMin)) }
                    var maxInput by remember(selectedMax) { mutableStateOf(mapper(selectedMax)) }

                    androidx.compose.foundation.text.BasicTextField(
                        value = minInput,
                        onValueChange = { newVal ->
                            val filtered = newVal.filter { it.isDigit() || it in "./*+- " || it == ',' }.replace(",", ".")
                            minInput = filtered
                            parser(filtered)?.let { parsed ->
                                if (parsed >= minVal && parsed <= maxVal) {
                                    onRangeChange(parsed, selectedMax)
                                }
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                val parsed = parser(minInput)
                                if (parsed != null && parsed >= minVal && parsed <= maxVal) {
                                    onRangeChange(parsed, selectedMax)
                                } else {
                                    minInput = mapper(selectedMin)
                                }
                                focusManager.clearFocus()
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0x550F172A), RoundedCornerShape(4.dp))
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    val parsed = parser(minInput)
                                    if (parsed != null && parsed >= minVal && parsed <= maxVal) {
                                        onRangeChange(parsed, selectedMax)
                                    } else {
                                        minInput = mapper(selectedMin)
                                    }
                                }
                            }
                    ) { innerTextField ->
                        Column {
                            Text("Min (Limit: ${mapper(minVal)})", color = Color(0xFF94A3B8), fontSize = 8.sp)
                            innerTextField()
                        }
                    }

                    Text("to", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    androidx.compose.foundation.text.BasicTextField(
                        value = maxInput,
                        onValueChange = { newVal ->
                            val filtered = newVal.filter { it.isDigit() || it in "./*+- " || it == ',' }.replace(",", ".")
                            maxInput = filtered
                            parser(filtered)?.let { parsed ->
                                if (parsed >= minVal && parsed <= maxVal) {
                                    onRangeChange(selectedMin, parsed)
                                }
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                val parsed = parser(maxInput)
                                if (parsed != null && parsed >= minVal && parsed <= maxVal) {
                                    onRangeChange(selectedMin, parsed)
                                } else {
                                    maxInput = mapper(selectedMax)
                                }
                                focusManager.clearFocus()
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0x550F172A), RoundedCornerShape(4.dp))
                            .padding(vertical = 4.dp, horizontal = 8.dp)
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    val parsed = parser(maxInput)
                                    if (parsed != null && parsed >= minVal && parsed <= maxVal) {
                                        onRangeChange(selectedMin, parsed)
                                    } else {
                                        maxInput = mapper(selectedMax)
                                    }
                                }
                            }
                    ) { innerTextField ->
                        Column {
                            Text("Max (Limit: ${mapper(maxVal)})", color = Color(0xFF94A3B8), fontSize = 8.sp)
                            innerTextField()
                        }
                    }
                }
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                val modeLabels = listOf("solid", "length", "center", "rainbow", "spicy")
                for (mode in modeLabels) {
                    val active = (settings.styleMode == mode)
                    val allowedList = settings.allowedStyleModes.split(",").filter { it.isNotEmpty() }
                    val isRot = allowedList.contains(mode)
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (active) Color(0xFF00E5FF) else Color(0xFF0F172A)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .combinedClickable(
                                onClick = { onUpdate(settings.copy(styleMode = mode)) },
                                onLongClick = { onUpdate(settings.toggleAllowedStyleMode(mode)) }
                            ),
                        border = BorderStroke(1.dp, if (isRot) Color(0xFFFF4081) else Color.Transparent)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                Text(
                                    mode.replaceFirstChar { it.uppercase() },
                                    color = if (active) Color.Black else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isRot) {
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("🎲", fontSize = 8.sp, color = if (active) Color.Black else Color(0xFFFF4081))
                                }
                            }
                        }
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
                                    .clickable {
                                        val hsv = FloatArray(3)
                                        android.graphics.Color.colorToHSV(sw.toInt(), hsv)
                                        onUpdate(settings.copy(
                                            solidColor = sw.toInt(),
                                            solidColorHue = settings.solidColorHue.setValueCompat(hsv[0])
                                        ))
                                    }
                            ) {
                                if (act) {
                                    Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp).align(Alignment.Center))
                                }
                            }
                        }
                    }
                    
                    ParameterSliderRow(
                        label = "Solid Color Hue",
                        value = settings.solidColorHue.current,
                        minVal = settings.solidColorHue.rangeMin,
                        maxVal = settings.solidColorHue.rangeMax,
                        stepValue = 1f,
                        formatString = "%.0f°",
                        isLocked = settings.solidColorHue.locked,
                        onLockToggle = { onUpdate(settings.copy(solidColorHue = settings.solidColorHue.copy(locked = it))) },
                        isRangeLocked = settings.solidColorHue.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(solidColorHue = settings.solidColorHue.withRangeLocked(it))) },
                        selectedMin = settings.solidColorHue.actualSelectedMin,
                        selectedMax = settings.solidColorHue.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(solidColorHue = settings.solidColorHue.withRanges(min, max))) },
                        onValueChange = {
                            val hsv = floatArrayOf(it, settings.saturation.current, 1.0f)
                            val activeCol = android.graphics.Color.HSVToColor(hsv)
                            onUpdate(settings.copy(
                                solidColorHue = settings.solidColorHue.withValue(it),
                                solidColor = activeCol
                            ))
                        },
                        onRandomize = {
                            val r = settings.solidColorHue.randomize(java.util.Random())
                            val hsv = floatArrayOf(r.current, settings.saturation.current, 1.0f)
                            val activeCol = android.graphics.Color.HSVToColor(hsv)
                            onUpdate(settings.copy(
                                solidColorHue = r,
                                solidColor = activeCol
                            ))
                        }
                    )
                    
                    RgbSlidersGroup(
                        label = "Custom Solid Color Controls",
                        colorValue = settings.solidColor,
                        onColorChange = {
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(it, hsv)
                            onUpdate(settings.copy(
                                solidColor = it,
                                solidColorHue = settings.solidColorHue.setValueCompat(hsv[0])
                            ))
                        }
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
                                    .clickable {
                                        val hsv = FloatArray(3)
                                        android.graphics.Color.colorToHSV(sw.toInt(), hsv)
                                        onUpdate(settings.copy(
                                            gradientStartColor = sw.toInt(),
                                            gradientStartHue = settings.gradientStartHue.setValueCompat(hsv[0])
                                        ))
                                    }
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
                                    .clickable {
                                        val hsv = FloatArray(3)
                                        android.graphics.Color.colorToHSV(sw.toInt(), hsv)
                                        onUpdate(settings.copy(
                                            gradientEndColor = sw.toInt(),
                                            gradientEndHue = settings.gradientEndHue.setValueCompat(hsv[0])
                                        ))
                                    }
                            ) {
                                if (settings.gradientEndColor == sw.toInt()) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(12.dp).align(Alignment.Center))
                            }
                        }
                    }
                    
                    ParameterSliderRow(
                        label = "Gradient Start Hue (Color A)",
                        value = settings.gradientStartHue.current,
                        minVal = settings.gradientStartHue.rangeMin,
                        maxVal = settings.gradientStartHue.rangeMax,
                        stepValue = 1f,
                        formatString = "%.0f°",
                        isLocked = settings.gradientStartHue.locked,
                        onLockToggle = { onUpdate(settings.copy(gradientStartHue = settings.gradientStartHue.copy(locked = it))) },
                        isRangeLocked = settings.gradientStartHue.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(gradientStartHue = settings.gradientStartHue.withRangeLocked(it))) },
                        selectedMin = settings.gradientStartHue.actualSelectedMin,
                        selectedMax = settings.gradientStartHue.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(gradientStartHue = settings.gradientStartHue.withRanges(min, max))) },
                        onValueChange = {
                            val hsv = floatArrayOf(it, settings.saturation.current, 1.0f)
                            val activeCol = android.graphics.Color.HSVToColor(hsv)
                            onUpdate(settings.copy(
                                gradientStartHue = settings.gradientStartHue.withValue(it),
                                gradientStartColor = activeCol
                            ))
                        },
                        onRandomize = {
                            val r = settings.gradientStartHue.randomize(java.util.Random())
                            val hsv = floatArrayOf(r.current, settings.saturation.current, 1.0f)
                            val activeCol = android.graphics.Color.HSVToColor(hsv)
                            onUpdate(settings.copy(
                                gradientStartHue = r,
                                gradientStartColor = activeCol
                            ))
                        }
                    )
                    
                    ParameterSliderRow(
                        label = "Gradient End Hue (Color B)",
                        value = settings.gradientEndHue.current,
                        minVal = settings.gradientEndHue.rangeMin,
                        maxVal = settings.gradientEndHue.rangeMax,
                        stepValue = 1f,
                        formatString = "%.0f°",
                        isLocked = settings.gradientEndHue.locked,
                        onLockToggle = { onUpdate(settings.copy(gradientEndHue = settings.gradientEndHue.copy(locked = it))) },
                        isRangeLocked = settings.gradientEndHue.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(gradientEndHue = settings.gradientEndHue.withRangeLocked(it))) },
                        selectedMin = settings.gradientEndHue.actualSelectedMin,
                        selectedMax = settings.gradientEndHue.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(gradientEndHue = settings.gradientEndHue.withRanges(min, max))) },
                        onValueChange = {
                            val hsv = floatArrayOf(it, settings.saturation.current, 1.0f)
                            val activeCol = android.graphics.Color.HSVToColor(hsv)
                            onUpdate(settings.copy(
                                gradientEndHue = settings.gradientEndHue.withValue(it),
                                gradientEndColor = activeCol
                            ))
                        },
                        onRandomize = {
                            val r = settings.gradientEndHue.randomize(java.util.Random())
                            val hsv = floatArrayOf(r.current, settings.saturation.current, 1.0f)
                            val activeCol = android.graphics.Color.HSVToColor(hsv)
                            onUpdate(settings.copy(
                                gradientEndHue = r,
                                gradientEndColor = activeCol
                            ))
                        }
                    )
                    
                    RgbSlidersGroup(
                        label = "Gradient Start (Color A)",
                        colorValue = settings.gradientStartColor,
                        onColorChange = {
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(it, hsv)
                            onUpdate(settings.copy(
                                gradientStartColor = it,
                                gradientStartHue = settings.gradientStartHue.setValueCompat(hsv[0])
                            ))
                        }
                    )
                    
                    RgbSlidersGroup(
                        label = "Gradient End (Color B)",
                        colorValue = settings.gradientEndColor,
                        onColorChange = {
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(it, hsv)
                            onUpdate(settings.copy(
                                gradientEndColor = it,
                                gradientEndHue = settings.gradientEndHue.setValueCompat(hsv[0])
                            ))
                        }
                    )
                }
            }
        }

        // Rainbow Style Settings Control Block
        if (settings.styleMode == "rainbow") {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Rainbow Style Settings", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    
                    ParameterSliderRow(
                        label = "Rainbow Starting Hue",
                        value = settings.rainbowHue.current,
                        minVal = settings.rainbowHue.rangeMin,
                        maxVal = settings.rainbowHue.rangeMax,
                        stepValue = 1f,
                        formatString = "%.0f°",
                        isLocked = settings.rainbowHue.locked,
                        onLockToggle = { onUpdate(settings.copy(rainbowHue = settings.rainbowHue.copy(locked = it))) },
                        isRangeLocked = settings.rainbowHue.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(rainbowHue = settings.rainbowHue.withRangeLocked(it))) },
                        selectedMin = settings.rainbowHue.actualSelectedMin,
                        selectedMax = settings.rainbowHue.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(rainbowHue = settings.rainbowHue.withRanges(min, max))) },
                        onValueChange = { onUpdate(settings.copy(rainbowHue = settings.rainbowHue.withValue(it))) },
                        onRandomize = { onUpdate(settings.copy(rainbowHue = settings.rainbowHue.randomize(java.util.Random()))) }
                    )
                    
                    ParameterSliderRow(
                        label = "Rainbow Color Range",
                        value = settings.rainbowColorRange.current,
                        minVal = settings.rainbowColorRange.rangeMin,
                        maxVal = settings.rainbowColorRange.rangeMax,
                        stepValue = 1f,
                        formatString = "%.0f° range",
                        isLocked = settings.rainbowColorRange.locked,
                        onLockToggle = { onUpdate(settings.copy(rainbowColorRange = settings.rainbowColorRange.copy(locked = it))) },
                        isRangeLocked = settings.rainbowColorRange.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(rainbowColorRange = settings.rainbowColorRange.withRangeLocked(it))) },
                        selectedMin = settings.rainbowColorRange.actualSelectedMin,
                        selectedMax = settings.rainbowColorRange.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(rainbowColorRange = settings.rainbowColorRange.withRanges(min, max))) },
                        onValueChange = { onUpdate(settings.copy(rainbowColorRange = settings.rainbowColorRange.withValue(it))) },
                        onRandomize = { onUpdate(settings.copy(rainbowColorRange = settings.rainbowColorRange.randomize(java.util.Random()))) }
                    )
                }
            }
        }

        // Spicy Style Settings Control Block
        if (settings.styleMode == "spicy") {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Spicy Style Settings", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    
                    ParameterSliderRow(
                        label = "Spicy Starting Hue",
                        value = settings.spicyHue.current,
                        minVal = settings.spicyHue.rangeMin,
                        maxVal = settings.spicyHue.rangeMax,
                        stepValue = 1f,
                        formatString = "%.0f°",
                        isLocked = settings.spicyHue.locked,
                        onLockToggle = { onUpdate(settings.copy(spicyHue = settings.spicyHue.copy(locked = it))) },
                        isRangeLocked = settings.spicyHue.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(spicyHue = settings.spicyHue.withRangeLocked(it))) },
                        selectedMin = settings.spicyHue.actualSelectedMin,
                        selectedMax = settings.spicyHue.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(spicyHue = settings.spicyHue.withRanges(min, max))) },
                        onValueChange = { onUpdate(settings.copy(spicyHue = settings.spicyHue.withValue(it))) },
                        onRandomize = { onUpdate(settings.copy(spicyHue = settings.spicyHue.randomize(java.util.Random()))) }
                    )
                    
                    ParameterSliderRow(
                        label = "Spicy Color Range",
                        value = settings.spicyColorRange.current,
                        minVal = settings.spicyColorRange.rangeMin,
                        maxVal = settings.spicyColorRange.rangeMax,
                        stepValue = 1f,
                        formatString = "%.0f° range",
                        isLocked = settings.spicyColorRange.locked,
                        onLockToggle = { onUpdate(settings.copy(spicyColorRange = settings.spicyColorRange.copy(locked = it))) },
                        isRangeLocked = settings.spicyColorRange.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(spicyColorRange = settings.spicyColorRange.withRangeLocked(it))) },
                        selectedMin = settings.spicyColorRange.actualSelectedMin,
                        selectedMax = settings.spicyColorRange.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(spicyColorRange = settings.spicyColorRange.withRanges(min, max))) },
                        onValueChange = { onUpdate(settings.copy(spicyColorRange = settings.spicyColorRange.withValue(it))) },
                        onRandomize = { onUpdate(settings.copy(spicyColorRange = settings.spicyColorRange.randomize(java.util.Random()))) }
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live Color Hue Shifting", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = settings.hueShiftingEnabled, onCheckedChange = { onUpdate(settings.copy(hueShiftingEnabled = it)) }, modifier = Modifier.scale(0.8f))
                }
                if (settings.hueShiftingEnabled) {
                    ParameterSliderRow(
                        label = "Hue Shift Speed",
                        value = settings.hueShiftSpeed.current,
                        minVal = settings.hueShiftSpeed.rangeMin,
                        maxVal = settings.hueShiftSpeed.rangeMax,
                        stepValue = 1f,
                        formatString = "%.0f speed",
                        isLocked = settings.hueShiftSpeed.locked,
                        onLockToggle = { onUpdate(settings.copy(hueShiftSpeed = settings.hueShiftSpeed.copy(locked = it))) },
                        isRangeLocked = settings.hueShiftSpeed.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(hueShiftSpeed = settings.hueShiftSpeed.withRangeLocked(it))) },
                        selectedMin = settings.hueShiftSpeed.actualSelectedMin,
                        selectedMax = settings.hueShiftSpeed.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(hueShiftSpeed = settings.hueShiftSpeed.withRanges(min, max))) },
                        onValueChange = { onUpdate(settings.copy(hueShiftSpeed = settings.hueShiftSpeed.withValue(it))) },
                        onRandomize = { onUpdate(settings.copy(hueShiftSpeed = settings.hueShiftSpeed.randomize(java.util.Random()))) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ParameterSliderRow(
                        label = "Hue Shift Active Range",
                        value = settings.hueShiftRange.current,
                        minVal = settings.hueShiftRange.rangeMin,
                        maxVal = settings.hueShiftRange.rangeMax,
                        stepValue = 5f,
                        formatString = "%.0f° hue limits",
                        isLocked = settings.hueShiftRange.locked,
                        onLockToggle = { onUpdate(settings.copy(hueShiftRange = settings.hueShiftRange.copy(locked = it))) },
                        isRangeLocked = settings.hueShiftRange.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(hueShiftRange = settings.hueShiftRange.withRangeLocked(it))) },
                        selectedMin = settings.hueShiftRange.actualSelectedMin,
                        selectedMax = settings.hueShiftRange.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(hueShiftRange = settings.hueShiftRange.withRanges(min, max))) },
                        onValueChange = { onUpdate(settings.copy(hueShiftRange = settings.hueShiftRange.withValue(it))) },
                        onRandomize = { onUpdate(settings.copy(hueShiftRange = settings.hueShiftRange.randomize(java.util.Random()))) }
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Color Saturation Config", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                
                ParameterSliderRow(
                    label = "Base Saturation",
                    value = settings.saturation.current,
                    minVal = settings.saturation.rangeMin,
                    maxVal = settings.saturation.rangeMax,
                    stepValue = 0.05f,
                    formatString = "%.2f",
                    isLocked = settings.saturation.locked,
                    onLockToggle = { onUpdate(settings.copy(saturation = settings.saturation.copy(locked = it))) },
                    isRangeLocked = settings.saturation.rangeLocked,
                    onRangeLockToggle = { onUpdate(settings.copy(saturation = settings.saturation.withRangeLocked(it))) },
                    selectedMin = settings.saturation.actualSelectedMin,
                    selectedMax = settings.saturation.actualSelectedMax,
                    onRangeChange = { min, max -> onUpdate(settings.copy(saturation = settings.saturation.withRanges(min, max))) },
                    onValueChange = { onUpdate(settings.copy(saturation = settings.saturation.withValue(it))) },
                    onRandomize = { onUpdate(settings.copy(saturation = settings.saturation.randomize(java.util.Random()))) }
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chromatic Shift Config", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                
                ParameterSliderRow(
                    label = "Chromatic Shift",
                    value = settings.chromaticShift.current,
                    minVal = settings.chromaticShift.rangeMin,
                    maxVal = settings.chromaticShift.rangeMax,
                    stepValue = 1f,
                    formatString = "%.0f°",
                    isLocked = settings.chromaticShift.locked,
                    onLockToggle = { onUpdate(settings.copy(chromaticShift = settings.chromaticShift.copy(locked = it))) },
                    isRangeLocked = settings.chromaticShift.rangeLocked,
                    onRangeLockToggle = { onUpdate(settings.copy(chromaticShift = settings.chromaticShift.withRangeLocked(it))) },
                    selectedMin = settings.chromaticShift.actualSelectedMin,
                    selectedMax = settings.chromaticShift.actualSelectedMax,
                    onRangeChange = { min, max -> onUpdate(settings.copy(chromaticShift = settings.chromaticShift.withRanges(min, max))) },
                    onValueChange = { onUpdate(settings.copy(chromaticShift = settings.chromaticShift.withValue(it))) },
                    onRandomize = { onUpdate(settings.copy(chromaticShift = settings.chromaticShift.randomize(java.util.Random()))) }
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live Chromatic Shift", color = Color.White, fontSize = 12.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    
                    IconButton(
                        onClick = { onUpdate(settings.copy(liveChromaticShiftEnabled = settings.liveChromaticShiftEnabled.copy(locked = !settings.liveChromaticShiftEnabled.locked))) }
                    ) {
                        Icon(
                            imageVector = if (settings.liveChromaticShiftEnabled.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Lock chromatic shift",
                            tint = if (settings.liveChromaticShiftEnabled.locked) Color(0xFF00E5FF) else Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Switch(
                        checked = settings.liveChromaticShiftEnabled.current,
                        onCheckedChange = { onUpdate(settings.copy(liveChromaticShiftEnabled = settings.liveChromaticShiftEnabled.withValue(it))) },
                        modifier = Modifier.scale(0.8f)
                    )
                }
                
                if (settings.liveChromaticShiftEnabled.current) {
                    ParameterSliderRow(
                        label = "Chromatic Shift Speed",
                        value = settings.chromaticShiftSpeed.current,
                        minVal = settings.chromaticShiftSpeed.rangeMin,
                        maxVal = settings.chromaticShiftSpeed.rangeMax,
                        stepValue = 0.1f,
                        formatString = "%.1fx",
                        isLocked = settings.chromaticShiftSpeed.locked,
                        onLockToggle = { onUpdate(settings.copy(chromaticShiftSpeed = settings.chromaticShiftSpeed.copy(locked = it))) },
                        isRangeLocked = settings.chromaticShiftSpeed.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(chromaticShiftSpeed = settings.chromaticShiftSpeed.withRangeLocked(it))) },
                        selectedMin = settings.chromaticShiftSpeed.actualSelectedMin,
                        selectedMax = settings.chromaticShiftSpeed.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(chromaticShiftSpeed = settings.chromaticShiftSpeed.withRanges(min, max))) },
                        onValueChange = { onUpdate(settings.copy(chromaticShiftSpeed = settings.chromaticShiftSpeed.withValue(it))) },
                        onRandomize = { onUpdate(settings.copy(chromaticShiftSpeed = settings.chromaticShiftSpeed.randomize(java.util.Random()))) }
                    )
                }
            }
        }

        item {
            Text("PEN & LINE PARAMETERS", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 12.sp)
        }

        // Multi Pen Controllers
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ParameterSliderRow(
                        label = "Active Pen Count",
                        value = settings.penCount.current.toFloat(),
                        minVal = settings.penCount.rangeMin.toFloat(),
                        maxVal = settings.penCount.rangeMax.toFloat(),
                        stepValue = 1f,
                        formatString = "%.0f pens",
                        isLocked = settings.penCount.locked,
                        onLockToggle = { onUpdate(settings.copy(penCount = settings.penCount.copy(locked = it))) },
                        isRangeLocked = settings.penCount.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(penCount = settings.penCount.withRangeLocked(it))) },
                        selectedMin = settings.penCount.actualSelectedMin.toFloat(),
                        selectedMax = settings.penCount.actualSelectedMax.toFloat(),
                        onRangeChange = { min, max -> onUpdate(settings.copy(penCount = settings.penCount.withRanges(min.roundToInt(), max.roundToInt()))) },
                        onValueChange = { onUpdate(settings.copy(penCount = settings.penCount.withValue(it.roundToInt()))) },
                        onRandomize = { onUpdate(settings.copy(penCount = settings.penCount.randomize(java.util.Random()))) }
                    )

                    if (settings.penCount.current > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Rotational Pen Offset", color = Color.White, fontSize = 12.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { onUpdate(settings.copy(penRotationEnabled = settings.penRotationEnabled.copy(locked = !settings.penRotationEnabled.locked))) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (settings.penRotationEnabled.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Lock Rotational Offset",
                                    tint = if (settings.penRotationEnabled.locked) Color(0xFF00E5FF) else Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Switch(
                                checked = settings.penRotationEnabled.current,
                                onCheckedChange = { onUpdate(settings.copy(penRotationEnabled = settings.penRotationEnabled.copy(current = it))) },
                                modifier = Modifier.scale(0.7f),
                                enabled = !settings.penRotationEnabled.locked
                            )
                        }
                        if (settings.penRotationEnabled.current) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Rotation Frequency Modes", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { onUpdate(settings.copy(penRotationIsMultiply = settings.penRotationIsMultiply.copy(locked = !settings.penRotationIsMultiply.locked))) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (settings.penRotationIsMultiply.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                        contentDescription = "Lock Frequency Mode",
                                        tint = if (settings.penRotationIsMultiply.locked) Color(0xFF00E5FF) else Color.Gray,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Divide",
                                        color = if (!settings.penRotationIsMultiply.current) Color(0xFF00E5FF) else Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier.clickable {
                                            if (!settings.penRotationIsMultiply.locked) {
                                                onUpdate(settings.copy(penRotationIsMultiply = settings.penRotationIsMultiply.copy(current = false)))
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Switch(
                                        checked = settings.penRotationIsMultiply.current,
                                        onCheckedChange = { onUpdate(settings.copy(penRotationIsMultiply = settings.penRotationIsMultiply.copy(current = it))) },
                                        modifier = Modifier.scale(0.6f),
                                        enabled = !settings.penRotationIsMultiply.locked
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Multiply",
                                        color = if (settings.penRotationIsMultiply.current) Color(0xFF00E5FF) else Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier.clickable {
                                            if (!settings.penRotationIsMultiply.locked) {
                                                onUpdate(settings.copy(penRotationIsMultiply = settings.penRotationIsMultiply.copy(current = true)))
                                            }
                                        }
                                    )
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

                    Spacer(modifier = Modifier.height(4.dp))

                    ParameterSliderRow(
                        label = "Line Opacity (Alpha)",
                        value = settings.lineAlpha.current,
                        minVal = settings.lineAlpha.rangeMin,
                        maxVal = settings.lineAlpha.rangeMax,
                        stepValue = 0.05f,
                        formatString = "%.2f",
                        isLocked = settings.lineAlpha.locked,
                        onLockToggle = { onUpdate(settings.copy(lineAlpha = settings.lineAlpha.copy(locked = it))) },
                        isRangeLocked = settings.lineAlpha.rangeLocked,
                        onRangeLockToggle = { onUpdate(settings.copy(lineAlpha = settings.lineAlpha.withRangeLocked(it))) },
                        selectedMin = settings.lineAlpha.actualSelectedMin,
                        selectedMax = settings.lineAlpha.actualSelectedMax,
                        onRangeChange = { min, max -> onUpdate(settings.copy(lineAlpha = settings.lineAlpha.withRanges(min, max))) },
                        onValueChange = { onUpdate(settings.copy(lineAlpha = settings.lineAlpha.withValue(it))) },
                        onRandomize = { onUpdate(settings.copy(lineAlpha = settings.lineAlpha.randomize(java.util.Random()))) }
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
                            val isProgressive = settings.periodicShapeDeployment == "progressive"
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Layer Deployment Mode", color = Color.White, fontSize = 12.sp)
                                Spacer(modifier = Modifier.weight(1f))
                                Row {
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
                            if (isProgressive) {
                                Spacer(modifier = Modifier.height(6.dp))
                                ParameterSliderRow(
                                    label = "Progressive Deployment Delay",
                                    value = settings.periodicProgressiveDelay.current,
                                    minVal = settings.periodicProgressiveDelay.rangeMin,
                                    maxVal = settings.periodicProgressiveDelay.rangeMax,
                                    stepValue = 0.05f,
                                    formatString = "%.2fs delay between layers",
                                    isLocked = settings.periodicProgressiveDelay.locked,
                                    onLockToggle = { onUpdate(settings.copy(periodicProgressiveDelay = settings.periodicProgressiveDelay.copy(locked = it))) },
                                    isRangeLocked = settings.periodicProgressiveDelay.rangeLocked,
                                    onRangeLockToggle = { onUpdate(settings.copy(periodicProgressiveDelay = settings.periodicProgressiveDelay.withRangeLocked(it))) },
                                    selectedMin = settings.periodicProgressiveDelay.actualSelectedMin,
                                    selectedMax = settings.periodicProgressiveDelay.actualSelectedMax,
                                    onRangeChange = { min, max -> onUpdate(settings.copy(periodicProgressiveDelay = settings.periodicProgressiveDelay.withRanges(min, max))) },
                                    onValueChange = { onUpdate(settings.copy(periodicProgressiveDelay = settings.periodicProgressiveDelay.withValue(it))) },
                                    onRandomize = { onUpdate(settings.copy(periodicProgressiveDelay = settings.periodicProgressiveDelay.randomize(java.util.Random()))) }
                                )
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
                val isP1Rot = settings.allowedPerspectives.split(",").contains("1")
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (act1) Color(0xFF00E5FF) else Color(0xFF0F172A)),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .combinedClickable(
                            onClick = { onUpdate(settings.copy(cameraPerspective = 1)) },
                            onLongClick = { onUpdate(settings.toggleAllowedPerspective(1)) }
                        ),
                    border = BorderStroke(1.dp, if (isP1Rot) Color(0xFFFF4081) else Color.Transparent)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Full View", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (act1) Color.Black else Color.White)
                            if (isP1Rot) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("🎲", fontSize = 9.sp, color = if (act1) Color.Black else Color(0xFFFF4081))
                            }
                        }
                        Text("Optimal overview viewport", fontSize = 8.sp, color = if (act1) Color.DarkGray else Color.LightGray)
                    }
                }

                val act2 = (settings.cameraPerspective == 2)
                val isP2Rot = settings.allowedPerspectives.split(",").contains("2")
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (act2) Color(0xFF00E5FF) else Color(0xFF0F172A)),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .combinedClickable(
                            onClick = { onUpdate(settings.copy(cameraPerspective = 2)) },
                            onLongClick = { onUpdate(settings.toggleAllowedPerspective(2)) }
                        ),
                    border = BorderStroke(1.dp, if (isP2Rot) Color(0xFFFF4081) else Color.Transparent)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Roller Coaster", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (act2) Color.Black else Color.White)
                            if (isP2Rot) {
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("🎲", fontSize = 9.sp, color = if (act2) Color.Black else Color(0xFFFF4081))
                            }
                        }
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

                    if (settings.cameraPerspective == 2) {
                        Spacer(modifier = Modifier.height(10.dp))
                        androidx.compose.material3.HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Follow Pen Direction (Tangent Coastin')", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.weight(1f))
                            Switch(
                                checked = settings.coasterDirectionFacing,
                                onCheckedChange = { onUpdate(settings.copy(coasterDirectionFacing = it)) },
                                modifier = Modifier.scale(0.7f).testTag("coaster_direction_facing_switch")
                            )
                        }
                        
                        if (settings.coasterDirectionFacing) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            ParameterSliderRow(
                                label = "Look Angle Deviation",
                                value = settings.coasterDeviationAngle.current,
                                minVal = settings.coasterDeviationAngle.rangeMin,
                                maxVal = settings.coasterDeviationAngle.rangeMax,
                                stepValue = 1f,
                                formatString = "%.0f° deviation behind pen",
                                isLocked = settings.coasterDeviationAngle.locked,
                                onLockToggle = { onUpdate(settings.copy(coasterDeviationAngle = settings.coasterDeviationAngle.copy(locked = it))) },
                                isRangeLocked = settings.coasterDeviationAngle.rangeLocked,
                                onRangeLockToggle = { onUpdate(settings.copy(coasterDeviationAngle = settings.coasterDeviationAngle.withRangeLocked(it))) },
                                selectedMin = settings.coasterDeviationAngle.actualSelectedMin,
                                selectedMax = settings.coasterDeviationAngle.actualSelectedMax,
                                onRangeChange = { min, max -> onUpdate(settings.copy(coasterDeviationAngle = settings.coasterDeviationAngle.withRanges(min, max))) },
                                onValueChange = { onUpdate(settings.copy(coasterDeviationAngle = settings.coasterDeviationAngle.withValue(it))) },
                                onRandomize = { onUpdate(settings.copy(coasterDeviationAngle = settings.coasterDeviationAngle.randomize(java.util.Random()))) }
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            ParameterSliderRow(
                                label = "Orbital Orbit & Sway Speed",
                                value = settings.coasterOrbitSpeed.current,
                                minVal = settings.coasterOrbitSpeed.rangeMin,
                                maxVal = settings.coasterOrbitSpeed.rangeMax,
                                stepValue = 0.1f,
                                formatString = "%.1fx orbit speed multiplier",
                                isLocked = settings.coasterOrbitSpeed.locked,
                                onLockToggle = { onUpdate(settings.copy(coasterOrbitSpeed = settings.coasterOrbitSpeed.copy(locked = it))) },
                                isRangeLocked = settings.coasterOrbitSpeed.rangeLocked,
                                onRangeLockToggle = { onUpdate(settings.copy(coasterOrbitSpeed = settings.coasterOrbitSpeed.withRangeLocked(it))) },
                                selectedMin = settings.coasterOrbitSpeed.actualSelectedMin,
                                selectedMax = settings.coasterOrbitSpeed.actualSelectedMax,
                                onRangeChange = { min, max -> onUpdate(settings.copy(coasterOrbitSpeed = settings.coasterOrbitSpeed.withRanges(min, max))) },
                                onValueChange = { onUpdate(settings.copy(coasterOrbitSpeed = settings.coasterOrbitSpeed.withValue(it))) },
                                onRandomize = { onUpdate(settings.copy(coasterOrbitSpeed = settings.coasterOrbitSpeed.randomize(java.util.Random()))) }
                            )
                        }
                    }


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
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Gyroscopic Camera Control", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.gyroEnabled,
                            onCheckedChange = { onUpdate(settings.copy(gyroEnabled = it)) },
                            modifier = Modifier.scale(0.7f).testTag("gyro_enabled_switch")
                        )
                    }

                    if (settings.gyroEnabled) {
                        ParameterSliderRow(
                            label = "Gyro Sensitivity",
                            value = settings.gyroSensitivity.current,
                            minVal = settings.gyroSensitivity.rangeMin,
                            maxVal = settings.gyroSensitivity.rangeMax,
                            stepValue = 0.1f,
                            formatString = "%.1fx",
                            isLocked = settings.gyroSensitivity.locked,
                            onLockToggle = { onUpdate(settings.copy(gyroSensitivity = settings.gyroSensitivity.copy(locked = it))) },
                            isRangeLocked = settings.gyroSensitivity.rangeLocked,
                            onRangeLockToggle = { onUpdate(settings.copy(gyroSensitivity = settings.gyroSensitivity.withRangeLocked(it))) },
                            selectedMin = settings.gyroSensitivity.actualSelectedMin,
                            selectedMax = settings.gyroSensitivity.actualSelectedMax,
                            onRangeChange = { min, max -> onUpdate(settings.copy(gyroSensitivity = settings.gyroSensitivity.withRanges(min, max))) },
                            onValueChange = { onUpdate(settings.copy(gyroSensitivity = settings.gyroSensitivity.withValue(it))) },
                            onRandomize = { onUpdate(settings.copy(gyroSensitivity = settings.gyroSensitivity.randomize(java.util.Random()))) }
                        )
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
                        ParameterSliderRow(
                            label = "Draw Duration (minutes)",
                            value = settings.drawSpeedMinutes.current,
                            minVal = settings.drawSpeedMinutes.rangeMin,
                            maxVal = settings.drawSpeedMinutes.rangeMax,
                            stepValue = 0.5f,
                            formatString = "%.1f min",
                            isLocked = settings.drawSpeedMinutes.locked,
                            onLockToggle = { onUpdate(settings.copy(drawSpeedMinutes = settings.drawSpeedMinutes.copy(locked = it))) },
                            isRangeLocked = settings.drawSpeedMinutes.rangeLocked,
                            onRangeLockToggle = { onUpdate(settings.copy(drawSpeedMinutes = settings.drawSpeedMinutes.withRangeLocked(it))) },
                            selectedMin = settings.drawSpeedMinutes.actualSelectedMin,
                            selectedMax = settings.drawSpeedMinutes.actualSelectedMax,
                            onRangeChange = { min, max -> onUpdate(settings.copy(drawSpeedMinutes = settings.drawSpeedMinutes.withRanges(min, max))) },
                            onValueChange = { onUpdate(settings.copy(drawSpeedMinutes = settings.drawSpeedMinutes.withValue(it))) },
                            onRandomize = { onUpdate(settings.copy(drawSpeedMinutes = settings.drawSpeedMinutes.randomize(java.util.Random()))) }
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
    
    var presetToDelete by remember { mutableStateOf<HarmonographPreset?>(null) }
    var presetToRename by remember { mutableStateOf<HarmonographPreset?>(null) }
    var renameInputVal by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        Text("SAVED DRAWING PRESETS", fontWeight = FontWeight.Bold, color = Color(0xFF00E5FF), fontSize = 12.sp)
        
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AUTOMATIC PRESET ROTATION", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Auto cycle between chosen presets on screen sweeps", color = Color(0xFF94A3B8), fontSize = 10.sp)
                }
                Switch(
                    checked = activeSettings.enablePresetRotation,
                    onCheckedChange = { viewModel.updateSettings(activeSettings.copy(enablePresetRotation = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00E5FF),
                        checkedTrackColor = Color(0x6600E5FF)
                    )
                )
            }
        }
        
        // Save current preset block
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = customPresetName,
                onValueChange = { customPresetName = it },
                label = { Text("Custom Preset Title", fontSize = 11.sp, color = Color.White) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("preset_name_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color.White,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B), contentColor = Color.White),
                    modifier = Modifier.weight(1f).height(44.dp).testTag("save_preset_button")
                ) {
                    Text("Save Standard", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        viewModel.saveSnapshotPreset(customPresetName)
                        customPresetName = ""
                        Toast.makeText(context, "Snapshot Saved (Locked)!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                    modifier = Modifier.weight(1.2f).height(44.dp).testTag("save_snapshot_preset_button")
                ) {
                    Text("📷 Save Snapshot", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // List presets
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(presets) { preset ->
                val presetKey = if (preset.isUserPreset) "u_${preset.id}" else "f_${preset.name}"
                val isPresetRot = activeSettings.allowedPresets.split(",").contains(presetKey)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    modifier = Modifier.fillMaxWidth(),
                    border = if (isPresetRot) BorderStroke(1.dp, Color(0xFFFF4081)) else null
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .combinedClickable(
                                    onClick = {
                                        viewModel.loadPreset(preset)
                                        Toast.makeText(context, "Loaded: ${preset.name}", Toast.LENGTH_SHORT).show()
                                    },
                                    onLongClick = {
                                        viewModel.updateSettings(activeSettings.toggleAllowedPreset(presetKey))
                                        val added = !activeSettings.allowedPresets.split(",").contains(presetKey)
                                        Toast.makeText(context, if (added) "Added to presets rotation!" else "Removed from presets rotation!", Toast.LENGTH_SHORT).show()
                                    }
                                )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(preset.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                if (isPresetRot) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("🎲", fontSize = 10.sp, color = Color(0xFFFF4081))
                                }
                            }
                            Text(
                                if (preset.isUserPreset) "My Custom Preset" else "Factory Standard Preset",
                                color = if (isPresetRot) Color(0xFFFF4081) else Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }

                        if (preset.isUserPreset) {
                            IconButton(onClick = { 
                                presetToRename = preset
                                renameInputVal = preset.name
                            }) {
                                Icon(Icons.Default.Edit, "Rename preset", tint = Color(0xFF00E5FF))
                            }
                            
                            IconButton(onClick = { presetToDelete = preset }) {
                                Icon(Icons.Default.Delete, "Delete preset", tint = Color(0xFFFF4081))
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    if (presetToDelete != null) {
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text("Delete Preset?", color = Color.White) },
            text = { Text("Are you sure you want to permanently delete \"${presetToDelete?.name}\"?", color = Color(0xFF94A3B8)) },
            confirmButton = {
                TextButton(onClick = {
                    presetToDelete?.let {
                        viewModel.deletePreset(it.id)
                    }
                    presetToDelete = null
                }) {
                    Text("Delete", color = Color(0xFFFF4081))
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF0F172A)
        )
    }

    // Rename Dialog
    if (presetToRename != null) {
        AlertDialog(
            onDismissRequest = { presetToRename = null },
            title = { Text("Rename Preset", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a new title for this preset:", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    OutlinedTextField(
                        value = renameInputVal,
                        onValueChange = { renameInputVal = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = presetToRename
                    if (p != null && renameInputVal.isNotBlank()) {
                        viewModel.renamePreset(p.id, renameInputVal, p.settingsJson)
                    }
                    presetToRename = null
                }) {
                    Text("Save", color = Color(0xFF00E5FF))
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToRename = null }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF0F172A)
        )
    }
}

@Composable
fun PerformanceAndQualityTab(
    settings: HarmonographSettings,
    onUpdate: (HarmonographSettings) -> Unit,
    viewModel: HarmonographViewModel
) {
    val liveFpsState = viewModel.currentFps.collectAsStateWithLifecycle()
    
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Real-Time Frame Rate FPS Status Metric Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "REAL-TIME FRAME PERFORMANCE",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Live measuring of native canvas render latency",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                    Text(
                        text = "${liveFpsState.value.roundToInt()} FPS",
                        color = if (liveFpsState.value >= 45f) Color(0xFF00E676) else if (liveFpsState.value >= 25f) Color(0xFFFFB300) else Color(0xFFFF1744),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 1. Resolution Selection (480, 760, Native)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CANVAS DRAW RESOLUTION",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Low resolutions (480 or 760 max size) significantly increase frame rate on older or heavy multi-pen configurations.",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(480, 760, -1).forEach { resOpt ->
                            val isSel = if (resOpt == -1) settings.perfResolution == "native" else settings.perfResolution == resOpt.toString()
                            val label = when (resOpt) {
                                480 -> "480p Max"
                                760 -> "760p Max"
                                else -> "Native Full"
                            }
                            Button(
                                onClick = { onUpdate(settings.copy(perfResolution = if (resOpt == -1) "native" else resOpt.toString())) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSel) Color(0xFF00E5FF) else Color(0xFF1E293B),
                                    contentColor = if (isSel) Color.Black else Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f).height(36.dp)
                            ) {
                                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 2. Velocity-Proportional Sampling & Segment Frequency Proportional to Velocity
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "VELOCITY-ADAPTIVE SAMPLING",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF),
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Dynamically adjusts segment density based on pen velocity to prevent jagged curves while maintaining high frame rate.",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }
                        
                        Switch(
                            checked = settings.perfVelocitySampling,
                            onCheckedChange = { onUpdate(settings.copy(perfVelocitySampling = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0x6600E5FF)
                            )
                        )
                    }
                }
            }
        }

        // 3. Tail Removing at Target FPS Configuration
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                    text = "THROTTLE TAIL TO SAVE FPS",
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E5FF),
                                    fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                    text = "Automatically drop historical tail segments if the physical frame rate drops below target threshold.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = settings.perfRemoveTailEnabled,
                            onCheckedChange = { onUpdate(settings.copy(perfRemoveTailEnabled = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0x6600E5FF)
                            )
                        )
                    }

                    if (settings.perfRemoveTailEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Target Frame Rate Threshold:", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("${settings.perfTargetFps.current} FPS", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = settings.perfTargetFps.current.toFloat(),
                            onValueChange = { onUpdate(settings.copy(perfTargetFps = settings.perfTargetFps.copy(current = it.roundToInt()))) },
                            valueRange = 15f..60f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF)
                            )
                        )
                    }
                }
            }
        }

        // 4. Live Shift Tick Rate slider (Tick Rate of Live Shifts)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "LIVE SHIFT TICK RATE",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Throttling live color / angle cycles to a specific interval reduces frame processing load on low-tier hardware.",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Cycle Calculation Interval:", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(
                            text = if (settings.perfLiveShiftTickRateMs.current <= 0) "Immediate (Full)" else "${settings.perfLiveShiftTickRateMs.current} ms",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = settings.perfLiveShiftTickRateMs.current.toFloat(),
                        onValueChange = { onUpdate(settings.copy(perfLiveShiftTickRateMs = settings.perfLiveShiftTickRateMs.copy(current = it.roundToInt()))) },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF)
                        )
                    )
                }
            }
        }

        // 5. Instant draw: length / Infinite setting
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "INSTANT DRAW WINDOWING",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Limits the historical path drawn during fast preview to a specific sliding window size.",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Infinite Drawing Length", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.instantDrawLengthInfinite.current,
                            onCheckedChange = { isInf ->
                                onUpdate(settings.copy(instantDrawLengthInfinite = settings.instantDrawLengthInfinite.copy(current = isInf)))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0x6600E5FF)
                            )
                        )
                    }
                    if (!settings.instantDrawLengthInfinite.current) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Sliding Segment Window Limit:", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("${settings.instantDrawLengthLimit.current} pts", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = settings.instantDrawLengthLimit.current.toFloat(),
                            onValueChange = { onUpdate(settings.copy(instantDrawLengthLimit = settings.instantDrawLengthLimit.copy(current = it.roundToInt()))) },
                            valueRange = 250f..2500f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF)
                            )
                        )
                    }
                }
            }
        }

        // 6. Live Alpha (transparency) on off toggle, lock, range lock, and speed setting (plus transparency shift)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "LIVE TRANSPARENCY (ALPHA) SHIFT",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF),
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Cyclically shift line segment opacity between the assigned lock parameters.",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = settings.liveAlphaShiftEnabled.current,
                            onCheckedChange = { onUpdate(settings.copy(liveAlphaShiftEnabled = settings.liveAlphaShiftEnabled.copy(current = it))) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0x6600E5FF)
                            )
                        )
                    }

                    if (settings.liveAlphaShiftEnabled.current) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Lock/Freeze Current Opacity", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { onUpdate(settings.copy(liveAlphaShiftSpeed = settings.liveAlphaShiftSpeed.copy(locked = !settings.liveAlphaShiftSpeed.locked))) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (settings.liveAlphaShiftSpeed.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Lock/Freeze",
                                    tint = if (settings.liveAlphaShiftSpeed.locked) Color(0xFF00E5FF) else Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Randomize Alpha Range Limit (RNG)", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { onUpdate(settings.copy(lineAlpha = settings.lineAlpha.copy(rangeLocked = !settings.lineAlpha.rangeLocked))) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (settings.lineAlpha.rangeLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Range Lock Limit",
                                    tint = if (settings.lineAlpha.rangeLocked) Color(0xFF00E5FF) else Color.Gray,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Shift Velocity/Speed:", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text("${settings.liveAlphaShiftSpeed.current} Hz", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = settings.liveAlphaShiftSpeed.current,
                            onValueChange = { onUpdate(settings.copy(liveAlphaShiftSpeed = settings.liveAlphaShiftSpeed.copy(current = it))) },
                            valueRange = 0.1f..5.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF)
                            )
                        )
                    }
                }
            }
        }

        // 7. Monochromatic value scale shift
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MONOCHROMATIC VALUE SHIFT",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E5FF),
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Shifts drawing color to a single tone value range (e.g., Red-pink to pure red to charcoal red).",
                                color = Color(0xFF94A3B8),
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = settings.monoScaleEnabled.current,
                            onCheckedChange = { onUpdate(settings.copy(monoScaleEnabled = settings.monoScaleEnabled.copy(current = it))) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF00E5FF),
                                checkedTrackColor = Color(0x6600E5FF)
                            )
                        )
                    }

                    if (settings.monoScaleEnabled.current) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Static Bias Value (Unchecked Shift):", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text(String.format("%.2f", settings.monoScaleShift.current), color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = settings.monoScaleShift.current,
                            onValueChange = { onUpdate(settings.copy(monoScaleShift = settings.monoScaleShift.copy(current = it))) },
                            valueRange = -1.0f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF)
                            )
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Active Live Value Oscillation", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = settings.monoScaleLiveShiftEnabled.current,
                                onCheckedChange = { onUpdate(settings.copy(monoScaleLiveShiftEnabled = settings.monoScaleLiveShiftEnabled.copy(current = it))) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF00E5FF),
                                    checkedTrackColor = Color(0x6600E5FF)
                                )
                            )
                        }

                        if (settings.monoScaleLiveShiftEnabled.current) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Oscillation Lock Range Limit", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { onUpdate(settings.copy(monoScaleShift = settings.monoScaleShift.copy(rangeLocked = !settings.monoScaleShift.rangeLocked))) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (settings.monoScaleShift.rangeLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                        contentDescription = "Range Lock Limit",
                                        tint = if (settings.monoScaleShift.rangeLocked) Color(0xFF00E5FF) else Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Oscillation Lock Speed", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                IconButton(
                                    onClick = { onUpdate(settings.copy(monoScaleLiveShiftSpeed = settings.monoScaleLiveShiftSpeed.copy(locked = !settings.monoScaleLiveShiftSpeed.locked))) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (settings.monoScaleLiveShiftSpeed.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                        contentDescription = "Lock/Freeze",
                                        tint = if (settings.monoScaleLiveShiftSpeed.locked) Color(0xFF00E5FF) else Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Live Shifting Oscillation Speed:", color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Text("${settings.monoScaleLiveShiftSpeed.current} Hz", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = settings.monoScaleLiveShiftSpeed.current,
                                onValueChange = { onUpdate(settings.copy(monoScaleLiveShiftSpeed = settings.monoScaleLiveShiftSpeed.copy(current = it))) },
                                valueRange = 0.1f..4.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF00E5FF),
                                    activeTrackColor = Color(0xFF00E5FF)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun mapHueIntoRange(hue: Float, minHue: Float, maxHue: Float): Float {
    if (maxHue <= minHue) return minHue
    val range = maxHue - minHue
    if (range >= 360f) return (hue % 360f + 360f) % 360f
    val shifted = hue - minHue
    val wrapped = (shifted % range + range) % range
    return minHue + wrapped
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
    val minHue = settings.hueShiftRange.actualSelectedMin
    val maxHue = settings.hueShiftRange.actualSelectedMax
    
    val csMin = settings.chromaticShift.actualSelectedMin
    val csMax = settings.chromaticShift.actualSelectedMax
    val segmentChromaticShift = if (settings.liveChromaticShiftEnabled.current) {
        val sweepMin = if (settings.chromaticShift.rangeLocked) csMin else 0f
        val sweepMax = if (settings.chromaticShift.rangeLocked) csMax else 90f
        val speed = settings.chromaticShiftSpeed.current
        val cycleRatio = 0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() * speed * 0.002f)
        val liveCS = sweepMin + cycleRatio * (sweepMax - sweepMin)
        liveCS.coerceIn(0f, 180f)
    } else if (settings.chromaticShift.rangeLocked && csMax > csMin) {
        val csRand = java.util.Random(idx.toLong() * 37L + settings.hashCode().toLong())
        csMin + csRand.nextFloat() * (csMax - csMin)
    } else {
        settings.chromaticShift.current
    }

    val alphaMin = settings.lineAlpha.actualSelectedMin
    val alphaMax = settings.lineAlpha.actualSelectedMax
    val segmentAlpha = if (settings.liveAlphaShiftEnabled.current) {
        val sweepMin = if (settings.lineAlpha.rangeLocked) alphaMin else 0.1f
        val sweepMax = if (settings.lineAlpha.rangeLocked) alphaMax else 1.0f
        val speed = settings.liveAlphaShiftSpeed.current
        val cycleRatio = 0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() * speed * 0.002f)
        val liveAlpha = sweepMin + cycleRatio * (sweepMax - sweepMin)
        liveAlpha.coerceIn(0.01f, 1.0f)
    } else if (settings.lineAlpha.rangeLocked && alphaMax > alphaMin) {
        val alphaRand = java.util.Random(idx.toLong() * 97L + settings.hashCode().toLong())
        alphaMin + alphaRand.nextFloat() * (alphaMax - alphaMin)
    } else {
        settings.lineAlpha.current
    }

    val finalColor = when (settings.styleMode) {
        "solid" -> {
            adjustComposeColor(Color(settings.solidColor), sat, hueOffset, minHue, maxHue, segmentChromaticShift, pt, segmentAlpha)
        }
        "length" -> {
            val ratio = idx.toFloat() / total.coerceAtLeast(1)
            val colorVal = interpolateComposeColor(Color(settings.gradientStartColor), Color(settings.gradientEndColor), ratio)
            adjustComposeColor(colorVal, sat, hueOffset, minHue, maxHue, segmentChromaticShift, pt, segmentAlpha)
        }
        "center" -> {
            val maxDist3D = sqrt(
                settings.ampX.current * settings.ampX.current +
                settings.ampY.current * settings.ampY.current +
                settings.ampZ.current * settings.ampZ.current
            ).coerceAtLeast(10f)
            val ratio = (pt.dist3D / maxDist3D).coerceIn(0f, 1f)
            val colorVal = interpolateComposeColor(Color(settings.gradientStartColor), Color(settings.gradientEndColor), ratio)
            adjustComposeColor(colorVal, sat, hueOffset, minHue, maxHue, segmentChromaticShift, pt, segmentAlpha)
        }
        "spicy" -> {
            val seedBase = idx.toLong() * 1109L + settings.hashCode().toLong()
            val segRand = java.util.Random(seedBase)
            
            val baseHue = settings.spicyHue.current
            val hRange = settings.spicyColorRange.current
            
            val rHue1 = if (hRange > 0.1f) (baseHue + segRand.nextFloat() * hRange) % 360f else baseHue
            val finalHueVal = (rHue1 + Math.abs(hueOffset) + segmentChromaticShift * (pt.depth / 120f)) % 360f
            val finalHue = mapHueIntoRange(finalHueVal, minHue, maxHue)
            Color.hsv(finalHue, sat, 0.95f, segmentAlpha)
        }
        else -> {
            val baseHue = (settings.rainbowHue.current + (idx.toFloat() / total.coerceAtLeast(1)) * settings.rainbowColorRange.current) % 360f
            val shiftedHue = (baseHue + Math.abs(hueOffset) + segmentChromaticShift * (pt.depth / 120f)) % 360f
            val finalHue = mapHueIntoRange(shiftedHue, minHue, maxHue)
            Color.hsv(finalHue, sat, 0.95f, segmentAlpha)
        }
    }

    return if (settings.monoScaleEnabled.current) {
        applyMonoScaleShiftToComposeColor(finalColor, settings, idx)
    } else {
        finalColor
    }
}

private fun applyMonoScaleShiftToComposeColor(color: Color, settings: HarmonographSettings, idx: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(
        255,
        (color.red * 255).roundToInt(),
        (color.green * 255).roundToInt(),
        (color.blue * 255).roundToInt()
    ), hsv)
    
    val baseSat = hsv[1]
    
    val shiftVal = if (settings.monoScaleLiveShiftEnabled.current) {
        val msMin = settings.monoScaleShift.actualSelectedMin
        val msMax = settings.monoScaleShift.actualSelectedMax
        val sweepMin = if (settings.monoScaleShift.rangeLocked) msMin else -1.0f
        val sweepMax = if (settings.monoScaleShift.rangeLocked) msMax else 1.0f
        val speed = settings.monoScaleLiveShiftSpeed.current
        val cycleRatio = 0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() * speed * 0.002f)
        sweepMin + cycleRatio * (sweepMax - sweepMin)
    } else if (settings.monoScaleShift.rangeLocked) {
        val msMin = settings.monoScaleShift.actualSelectedMin
        val msMax = settings.monoScaleShift.actualSelectedMax
        val msRand = java.util.Random(idx.toLong() * 1237L + settings.hashCode().toLong())
        msMin + msRand.nextFloat() * (msMax - msMin)
    } else {
        settings.monoScaleShift.current
    }
    
    val shiftCoerced = shiftVal.coerceIn(-1.0f, 1.0f)
    if (shiftCoerced < 0f) {
        val ratio = (shiftCoerced + 1.0f).coerceIn(0f, 1f)
        hsv[1] = (0.15f + ratio * (baseSat - 0.15f)).coerceIn(0.05f, 1.0f)
        hsv[2] = 0.98f
    } else {
        val ratio = (1.0f - shiftCoerced).coerceIn(0f, 1f)
        hsv[1] = baseSat
        hsv[2] = (0.15f + ratio * (0.95f - 0.15f)).coerceIn(0.05f, 1.0f)
    }
    
    val alphaInt = (color.alpha * 255).roundToInt().coerceIn(0, 255)
    return Color(android.graphics.Color.HSVToColor(alphaInt, hsv))
}

private fun adjustComposeColor(color: Color, sat: Float, hueOffset: Long, minHue: Float = 0f, maxHue: Float = 360f, chromaticShiftVal: Float = 0f, pt: ProjectedPoint, alphaVal: Float = 0.85f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.argb(
        255,
        (color.red * 255).roundToInt(),
        (color.green * 255).roundToInt(),
        (color.blue * 255).roundToInt()
    ), hsv)
    hsv[1] = sat
    val baseHue = hsv[0]
    val shiftedHue = (baseHue + Math.abs(hueOffset) + chromaticShiftVal * (pt.depth / 120f)) % 360f
    hsv[0] = mapHueIntoRange(shiftedHue, minHue, maxHue)
    hsv[2] = 0.95f
    val alphaInt = (alphaVal * 255).roundToInt().coerceIn(0, 255)
    return Color(android.graphics.Color.HSVToColor(alphaInt, hsv))
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
            animTime = animTime,
            coasterDeviationAngle = settings.coasterDeviationAngle.current,
            coasterOrbitSpeed = settings.coasterOrbitSpeed.current
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
            animTime = animTime,
            coasterDeviationAngle = settings.coasterDeviationAngle.current,
            coasterOrbitSpeed = settings.coasterOrbitSpeed.current
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
