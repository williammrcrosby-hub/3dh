package com.example

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.service.wallpaper.WallpaperService
import android.view.GestureDetector
import android.view.MotionEvent
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.math.*
import java.util.Random

class HarmonographWallpaperService : WallpaperService() {

    private data class WallpaperSegment(
        val p1: ProjectedPoint,
        val p2: ProjectedPoint,
        val color: Int,
        val strokeWidth: Float
    )

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(HarmonographSettings::class.java)

    override fun onCreateEngine(): Engine {
        return HarmonographEngine()
    }

    inner class HarmonographEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var settings = HarmonographSettings()
        private var sharedPrefs: SharedPreferences? = null

        private var drawProgress = 0f
        private var isVisible = false
        private val startTime = System.currentTimeMillis()
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        // Camera base yaw & pitch (from interactive drags) and launcher parallax offset
        private var touchBaseYaw = 35f
        private var touchBasePitch = 25f
        private var launcherYawOffset = 0f
        private var lastX = 0f
        private var lastY = 0f

        // Multi-touch tracking
        private var lastTwoFingerTapTime = 0L
        private var fingerDownTime = 0L
        private var isTwoFingersHeld = false

        private val paint = Paint().apply {
            isAntiAlias = true
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val fillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        private val runDrawingRunnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (isVisible) {
                    val maxSteps = settings.drawLengthSteps * settings.drawLengthFactor
                    if (settings.drawSpeedInstant) {
                        drawProgress = maxSteps
                    } else {
                        val dt = 0.016f // step time
                        val stepsPerSec = maxSteps / (settings.drawSpeedMinutes * 60f)
                        drawProgress += stepsPerSec * dt
                        if (drawProgress >= maxSteps) {
                            if (settings.postCompletionAutoReset) {
                                // Wait resets based on factor (e.g. 25% draw time)
                                val postResetDelay = (settings.drawSpeedMinutes * 60f * settings.postCompletionResetTimeFactor * 1000f).toLong().coerceAtLeast(100L)
                                drawProgress = 0f
                                randomizeUnlockedSettings()
                                handler.postDelayed(this, postResetDelay)
                                return
                            } else if (settings.drawLengthLooping) {
                                drawProgress = 0f
                            } else {
                                drawProgress = maxSteps
                            }
                        }
                    }
                    handler.postDelayed(this, 16) // ~60fps Limit
                }
            }
        }

        override fun onCreate(surfaceHolder: android.view.SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            sharedPrefs = getSharedPreferences("harmonograph_prefs", Context.MODE_PRIVATE)
            sharedPrefs?.registerOnSharedPreferenceChangeListener(this)
            loadActiveSettings()
        }

        override fun onDestroy() {
            super.onDestroy()
            sharedPrefs?.unregisterOnSharedPreferenceChangeListener(this)
            handler.removeCallbacks(runDrawingRunnable)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisible = visible
            if (visible) {
                handler.post(runDrawingRunnable)
            } else {
                handler.removeCallbacks(runDrawingRunnable)
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == "active_settings") {
                loadActiveSettings()
            }
        }

        private fun loadActiveSettings() {
            val json = sharedPrefs?.getString("active_settings", null)
            if (json != null) {
                try {
                    val s = adapter.fromJson(json)
                    if (s != null) {
                        settings = s
                        drawProgress = 0f // Restart drawing upon preference update
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        private fun randomizeUnlockedSettings() {
            val r = Random()
            settings = settings.randomizeAll(r)
            saveSettingsToPrefs(settings)
        }

        private fun saveSettingsToPrefs(settings: HarmonographSettings) {
            try {
                val json = adapter.toJson(settings)
                sharedPrefs?.edit()?.putString("active_settings", json)?.apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            if (event == null) return
            val action = event.actionMasked
            val pointerCount = event.pointerCount

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    fingerDownTime = System.currentTimeMillis()
                    isTwoFingersHeld = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (pointerCount == 2) {
                        fingerDownTime = System.currentTimeMillis()
                        isTwoFingersHeld = true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pointerCount == 1 && !isTwoFingersHeld) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        touchBaseYaw += dx * 0.35f
                        touchBasePitch -= dy * 0.35f
                        lastX = event.x
                        lastY = event.y
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (pointerCount == 2 && isTwoFingersHeld) {
                        val pressDuration = System.currentTimeMillis() - fingerDownTime
                        // Two finger double tap reset detection
                        val now = System.currentTimeMillis()
                        if (now - lastTwoFingerTapTime < 350) {
                            // Two-finger Double Tap -> Randomize
                            randomizeUnlockedSettings()
                            drawProgress = 0f
                        } else if (pressDuration > 450) {
                            // Two-finger tap and hold -> Swap perspectives
                            val p = if (settings.cameraPerspective == 1) 2 else 1
                            settings = settings.copy(cameraPerspective = p)
                            saveSettingsToPrefs(settings)
                        }
                        lastTwoFingerTapTime = now
                        isTwoFingersHeld = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isTwoFingersHeld = false
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            // Adjust camera yaw proportionally to launcher swiping for dynamic 3D depth parallax!
            launcherYawOffset = (xOffset - 0.5f) * 60f
            if (isVisible) {
                drawFrame()
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            val canvas = holder.lockCanvas() ?: return
            
            try {
                // Background Paint - Deep Space Aesthetic
                canvas.drawColor(0xFF0F172A.toInt()) // Slate 900
                
                val width = canvas.width.toFloat()
                val height = canvas.height.toFloat()

                // Calculate relative elapsed milliseconds smoothly without losing precision
                val elapsedMs = System.currentTimeMillis() - startTime
                val timeSec = elapsedMs * 0.001f

                // Combine touch drag rotation and launcher offsets
                val baseYaw = touchBaseYaw + launcherYawOffset
                val basePitch = touchBasePitch

                // Yaw and Pitch auto-rotation logic (continuous rotation to match the main app)
                var activeYaw = baseYaw
                var activePitch = basePitch
                if (settings.cameraAutoRotationEnabled) {
                    activeYaw = (baseYaw + timeSec * settings.cameraAutoRotationSpeed * 25f) % 360f
                    activePitch = basePitch + (sin(timeSec * settings.cameraAutoRotationSpeed * 0.5f) * 15f)
                }
                
                // Base drawing paths evaluation
                val rawPaths = HarmonographMath.generatePathPoints(settings, settings.drawLengthSteps)
                
                // Color hue cycle using elapsed elapsedMs
                val timeHueOffset = if (settings.hueShiftingEnabled) {
                    (elapsedMs / 24) % 360
                } else {
                    0L
                }

                val stepsCount = settings.drawLengthSteps
                val cameraTargetIndex = if (settings.cameraPerspective == 2 && drawProgress >= stepsCount.coerceAtLeast(1) - 1f) {
                    val durationMin = if (settings.drawSpeedInstant) 2.0f else settings.drawSpeedMinutes
                    val cycleDurationMs = (durationMin * 60f * 1000f).toLong().coerceAtLeast(1000L)
                    val progressFrac = (elapsedMs % cycleDurationMs).toFloat() / cycleDurationMs
                    (progressFrac * (stepsCount - 1)).coerceIn(0f, (stepsCount - 1).toFloat())
                } else {
                    drawProgress
                }

                // Project and gather line segments across all paths for unified depth sorting
                val segmentsList = mutableListOf<WallpaperSegment>()
                val tipsList = mutableListOf<Pair<ProjectedPoint, Int>>()

                for (pIdx in rawPaths.indices) {
                    val path3D = rawPaths[pIdx]
                    if (path3D.isEmpty()) continue

                    val projPoints = HarmonographMath.project3DTo2D(
                        points = path3D,
                        yaw = activeYaw,
                        pitch = activePitch,
                        perspective = settings.cameraPerspective,
                        currentDrawProgress = drawProgress,
                        screenWidth = width,
                        screenHeight = height,
                        angularLock = settings.isAngularLockEnabled,
                        angularLockAxis = settings.angularLockAxis,
                        referencePoints = rawPaths.firstOrNull(),
                        cameraTargetIndex = cameraTargetIndex,
                        cameraDistance = settings.cameraDistance.current,
                        dynamicCameraZoomEnabled = settings.dynamicCameraZoomEnabled,
                        coasterDirectionFacing = settings.coasterDirectionFacing,
                        animTime = elapsedMs
                    )
                    
                    if (projPoints.isEmpty()) continue
                    
                    // Segment lines gathering
                    for (i in 0 until projPoints.size - 1) {
                        val p1 = projPoints[i]
                        val p2 = projPoints[i + 1]
                        
                        // Compute color styled dynamically
                        val segmentColor = computeDynamicColor(i, projPoints.size, p1, width, height, timeHueOffset)
                        val baseThickness = settings.lineThickness.current
                        val strokeWidth = baseThickness + (0.5f * baseThickness * (p1.depth / 500f).coerceIn(-1f, 1f))
                        
                        segmentsList.add(WallpaperSegment(p1, p2, segmentColor, strokeWidth))
                    }

                    // Store pen tip if enabled
                    if (settings.penTipEnabled && projPoints.isNotEmpty()) {
                        val tip = projPoints.last()
                        val tipColor = if (settings.penTipColorMode == "solid") {
                            settings.penTipColor
                        } else {
                            if (projPoints.size > 1) {
                                computeDynamicColor(
                                    projPoints.size - 2,
                                    projPoints.size,
                                    projPoints[projPoints.size - 2],
                                    width,
                                    height,
                                    timeHueOffset
                                )
                            } else {
                                0xFFFFFFFF.toInt()
                            }
                        }
                        tipsList.add(Pair(tip, tipColor))
                    }
                }

                // Sort all line segments back-to-front (descending by average depth)
                segmentsList.sortByDescending { (it.p1.depth + it.p2.depth) / 2f }

                // Draw depth-sorted segments on the canvas
                for (seg in segmentsList) {
                    paint.color = seg.color
                    paint.strokeWidth = seg.strokeWidth
                    canvas.drawLine(seg.p1.x, seg.p1.y, seg.p2.x, seg.p2.y, paint)
                }

                // Render styled active pen tip markers on top of all segment drawings
                for ((tip, tipColor) in tipsList) {
                    val s = settings.penTipSize
                    fillPaint.color = tipColor
                    
                    when (settings.penTipShape) {
                        "square" -> {
                            canvas.drawRect(tip.x - s, tip.y - s, tip.x + s, tip.y + s, fillPaint)
                        }
                        "diamond" -> {
                            val p = Path().apply {
                                moveTo(tip.x, tip.y - s)
                                lineTo(tip.x + s, tip.y)
                                lineTo(tip.x, tip.y + s)
                                lineTo(tip.x - s, tip.y)
                                close()
                            }
                            canvas.drawPath(p, fillPaint)
                        }
                        "cross" -> {
                            paint.color = tipColor
                            paint.strokeWidth = 3f
                            canvas.drawLine(tip.x - s, tip.y, tip.x + s, tip.y, paint)
                            canvas.drawLine(tip.x, tip.y - s, tip.x, tip.y + s, paint)
                        }
                        "star" -> {
                            val p = Path()
                            val stepRad = Math.PI / 5
                            for (i in 0 until 10) {
                                val angle = i * stepRad
                                val r = if (i % 2 == 0) s else s * 0.4f
                                val px = tip.x + (r * cos(angle - Math.PI / 2f)).toFloat()
                                val py = tip.y + (r * sin(angle - Math.PI / 2f)).toFloat()
                                if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
                            }
                            p.close()
                            canvas.drawPath(p, fillPaint)
                        }
                        else -> { // circle
                            canvas.drawCircle(tip.x, tip.y, s, fillPaint)
                            paint.color = (tipColor and 0x00FFFFFF) or 0x33000000 // 20% alpha glow
                            canvas.drawCircle(tip.x, tip.y, s * 2f, paint)
                        }
                    }
                }

                // Periodic shapes orthogonal details
                val rawShapes = HarmonographMath.generatePeriodicShapes(settings, settings.drawLengthSteps)
                for (shape in rawShapes) {
                    val kIndex = shape.colorIndex
                    if (kIndex > drawProgress.roundToInt()) continue
                    
                    // Project shape center and outer coordinates
                    val stepsCount = settings.drawLengthSteps
                    
                    // Draw outer shapes
                    drawOrthogonalShapeOnCanvas(
                        canvas, shape, activeYaw, activePitch, settings.cameraPerspective,
                        width, height, settings.isAngularLockEnabled, settings.angularLockAxis,
                        timeHueOffset, stepsCount, rawPaths.firstOrNull() ?: emptyList(), cameraTargetIndex,
                        elapsedMs
                    )
                }

            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun computeDynamicColor(
            idx: Int,
            total: Int,
            pt: ProjectedPoint,
            width: Float,
            height: Float,
            hueOffset: Long
        ): Int {
            val sat = settings.saturation.current
            
            return when (settings.styleMode) {
                "solid" -> {
                    // Solid Color with slight opacity
                    adjustSaturationAndHue(settings.solidColor, sat, hueOffset)
                }
                "length" -> {
                    // Length Gradient along path
                    val ratio = idx.toFloat() / total.coerceAtLeast(1)
                    val color = interpolateColor(settings.gradientStartColor, settings.gradientEndColor, ratio)
                    adjustSaturationAndHue(color, sat, hueOffset)
                }
                "center" -> {
                    // True 3D density proximity from origin
                    val maxDist3D = sqrt(
                        settings.ampX.current * settings.ampX.current +
                        settings.ampY.current * settings.ampY.current +
                        settings.ampZ.current * settings.ampZ.current
                    ).coerceAtLeast(10f)
                    val ratio = (pt.dist3D / maxDist3D).coerceIn(0f, 1f)
                    val color = interpolateColor(settings.gradientStartColor, settings.gradientEndColor, ratio)
                    adjustSaturationAndHue(color, sat, hueOffset)
                }
                else -> {
                    // Rainbow Gradient Mode + optional Live hue rotation
                    val baseHue = (idx.toFloat() / total.coerceAtLeast(1)) * 360f
                    val finalHue = (baseHue + hueOffset) % 360f
                    Color.HSVToColor(floatArrayOf(finalHue, sat, 0.95f))
                }
            }
        }

        private fun drawOrthogonalShapeOnCanvas(
            canvas: Canvas,
            shape: CustomShapeData,
            yawVal: Float,
            pitchVal: Float,
            perspective: Int,
            width: Float,
            height: Float,
            angularLock: Boolean,
            angularLockAxis: String,
            hueOffset: Long,
            totalSteps: Int,
            mainPathPoints: List<Point3D> = emptyList(),
            cameraTargetIndex: Float = -1f,
            animTime: Long = 0L
        ) {
            val concentricLevels = shape.concentric
            val baseSize = shape.size
            
            // Build shape geometries in 3D
            val vertices = when (shape.shapeType) {
                "circle" -> 16
                "triangle" -> 3
                else -> 10 // Star
            }

            for (conc in 0 until concentricLevels) {
                // Stacked vs progressive concentric layouts
                val scaleFactor = 1f + conc * 0.5f
                val size = baseSize * scaleFactor
                
                val centerPt3D = if (shape.deployment == "progressive") {
                    // Offset center slightly along direction
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
                // close polygon path
                if (shape3DPoints.isNotEmpty()) {
                    shape3DPoints.add(shape3DPoints[0])
                }
                
                // Project shape points
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
                
                // Obtain center screen pos for color compute
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
                val shapeColor = computeDynamicColor(shape.colorIndex, totalSteps, centerPtScreen, width, height, hueOffset)
                
                if (shape.isSolid) {
                    val polyPath = Path()
                    polyPath.moveTo(projPts[0].x, projPts[0].y)
                    for (ptIdx in 1 until projPts.size) {
                        polyPath.lineTo(projPts[ptIdx].x, projPts[ptIdx].y)
                    }
                    polyPath.close()
                    fillPaint.color = shapeColor
                    fillPaint.alpha = 150 // semi-transparent fills
                    canvas.drawPath(polyPath, fillPaint)
                } else {
                    paint.color = shapeColor
                    paint.strokeWidth = 1.5f
                    for (ptIdx in 0 until projPts.size - 1) {
                        canvas.drawLine(
                            projPts[ptIdx].x, projPts[ptIdx].y,
                            projPts[ptIdx + 1].x, projPts[ptIdx + 1].y,
                            paint
                        )
                    }
                }
            }
        }

        private fun interpolateColor(color1: Int, color2: Int, ratio: Float): Int {
            val a = (Color.alpha(color1) + ratio * (Color.alpha(color2) - Color.alpha(color1))).toInt()
            val r = (Color.red(color1) + ratio * (Color.red(color2) - Color.red(color1))).toInt()
            val g = (Color.green(color1) + ratio * (Color.green(color2) - Color.green(color1))).toInt()
            val b = (Color.blue(color1) + ratio * (Color.blue(color2) - Color.blue(color1))).toInt()
            return Color.argb(a, r, g, b)
        }

        private fun adjustSaturationAndHue(color: Int, sat: Float, hueOffset: Long): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = sat
            if (hueOffset != 0L) {
                hsv[0] = (hsv[0] + hueOffset) % 360f
            }
            // Preserve the original alpha channel if any
            val alpha = Color.alpha(color)
            return Color.HSVToColor(alpha, hsv)
        }
    }
}
