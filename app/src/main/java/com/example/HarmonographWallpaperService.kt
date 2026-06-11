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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HarmonographWallpaperService : WallpaperService() {

    private data class WallpaperSegment(
        val p1: ProjectedPoint,
        val p2: ProjectedPoint,
        val color1: Int,
        val color2: Int,
        val strokeWidth: Float
    )

    private fun HarmonographSettings.isDrawingFormEquivalent(other: HarmonographSettings): Boolean {
        return this.freqX.current == other.freqX.current &&
               this.freqY.current == other.freqY.current &&
               this.freqZ.current == other.freqZ.current &&
               this.ampX.current == other.ampX.current &&
               this.ampY.current == other.ampY.current &&
               this.ampZ.current == other.ampZ.current &&
               this.decayX.current == other.decayX.current &&
               this.decayY.current == other.decayY.current &&
               this.decayZ.current == other.decayZ.current &&
               this.phaseX.current == other.phaseX.current &&
               this.phaseY.current == other.phaseY.current &&
               this.phaseZ.current == other.phaseZ.current &&
               this.decayEnabled.current == other.decayEnabled.current &&
               this.ampSubX.current == other.ampSubX.current &&
               this.ampSubY.current == other.ampSubY.current &&
               this.ampSubZ.current == other.ampSubZ.current &&
               this.penCount.current == other.penCount.current &&
               this.penOffset.current == other.penOffset.current &&
               this.penRotationEnabled.current == other.penRotationEnabled.current &&
               this.drawLengthSteps == other.drawLengthSteps &&
               this.drawLengthFactor == other.drawLengthFactor &&
               this.rationalFrequenciesEnabled == other.rationalFrequenciesEnabled
    }

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(HarmonographSettings::class.java)

    override fun onCreateEngine(): Engine {
        return HarmonographEngine()
    }

    inner class HarmonographEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener, android.hardware.SensorEventListener {

        private var settings = HarmonographSettings()
        private var sharedPrefs: SharedPreferences? = null

        private var drawProgress = 0f
        private var animTime = 0L
        private var isVisible = false
        private var completionTimeOfAnim: Long? = null
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        // Cached math points to eliminate math overhead in drawing loop
        private var cachedSettingsForPoints: HarmonographSettings? = null
        private var cachedPaths: List<List<Point3D>>? = null
        private var cachedShapes: List<CustomShapeData>? = null
        private var cachedCenterPath: List<Point3D>? = null

        // Gyroscope tracking
        private var sensorManager: android.hardware.SensorManager? = null
        private var gyroscope: android.hardware.Sensor? = null
        private var gyroYawOffset = 0f
        private var gyroPitchOffset = 0f
        private var isGyroRegistered = false

        private fun updateGyroRegistration() {
            val shouldRegister = isVisible && settings.gyroEnabled
            if (shouldRegister && !isGyroRegistered) {
                gyroscope?.let {
                    sensorManager?.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_GAME)
                    isGyroRegistered = true
                }
            } else if (!shouldRegister && isGyroRegistered) {
                sensorManager?.unregisterListener(this)
                isGyroRegistered = false
            }
        }

        override fun onSensorChanged(event: android.hardware.SensorEvent?) {
            if (event == null || event.sensor.type != android.hardware.Sensor.TYPE_GYROSCOPE) return
            if (settings.gyroEnabled) {
                val sensitivity = settings.gyroSensitivity.current
                val pyDelta = Math.toDegrees(event.values[0].toDouble()).toFloat() * 0.016f * sensitivity * 2.5f
                val ywDelta = Math.toDegrees(event.values[1].toDouble()).toFloat() * 0.016f * sensitivity * 2.5f
                gyroYawOffset -= ywDelta
                gyroPitchOffset -= pyDelta
            }
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

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
        private var isThreeFingersHeld = false

        private val paint = Paint().apply {
            isAntiAlias = true
            strokeWidth = 2.5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
        }

        private val fillPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        private var lastSaveTime = 0L
        private var wallpaperFrameCount = 0
        private var wallpaperLastFpsTime = 0L
        private var wallpaperCurrentFps = 60f
        private var wallpaperDynamicTailLimit = -1
        private val runDrawingRunnable = object : Runnable {
            override fun run() {
                drawFrame()
                if (isVisible) {
                    val appActive = sharedPrefs?.getBoolean("app_active", false) ?: false
                    val isPlay = if (appActive) {
                        sharedPrefs?.getBoolean("is_drawing", true) ?: true
                    } else {
                        true
                    }

                    if (isPlay) {
                        val maxSteps = settings.drawLengthSteps * settings.drawLengthFactor
                        if (settings.drawSpeedInstant) {
                            if (drawProgress < maxSteps) {
                                drawProgress = maxSteps
                            } else {
                                val dt = 0.016f // step time
                                val stepsPerSec = maxSteps / (settings.drawSpeedMinutes.current * 60f)
                                val nextVal = drawProgress + stepsPerSec * dt
                                
                                val resetThreshold = maxSteps * (1f + settings.postCompletionResetTimeFactor)
                                if (settings.postCompletionAutoReset && nextVal >= resetThreshold) {
                                    val postResetDelay = (settings.drawSpeedMinutes.current * 60f * settings.postCompletionResetTimeFactor * 1000f).toLong().coerceAtLeast(100L)
                                    drawProgress = 0f
                                    randomizeUnlockedSettings()
                                    handler.postDelayed(this, postResetDelay)
                                    saveWallpaperProgressToPrefs()
                                    return
                                } else {
                                    drawProgress = nextVal
                                }
                            }
                        } else {
                            val dt = 0.016f // step time
                            val stepsPerSec = maxSteps / (settings.drawSpeedMinutes.current * 60f)
                            drawProgress += stepsPerSec * dt
                            if (drawProgress >= maxSteps) {
                                if (settings.postCompletionAutoReset) {
                                    val postResetDelay = (settings.drawSpeedMinutes.current * 60f * settings.postCompletionResetTimeFactor * 1000f).toLong().coerceAtLeast(100L)
                                    drawProgress = 0f
                                    randomizeUnlockedSettings()
                                    handler.postDelayed(this, postResetDelay)
                                    saveWallpaperProgressToPrefs()
                                    return
                                } else if (settings.drawLengthLooping) {
                                    drawProgress = 0f
                                } else {
                                    drawProgress = maxSteps
                                }
                            }
                        }
                    }

                    // Increment animTime by 16ms each frames so camera & color rotates continuously when visible
                    animTime += 16L

                    val now = System.currentTimeMillis()
                    if (!appActive && now - lastSaveTime > 200L) {
                        lastSaveTime = now
                        saveWallpaperProgressToPrefs()
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
            
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            gyroscope = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
            
            loadActiveSettings()
        }

        override fun onDestroy() {
            super.onDestroy()
            sharedPrefs?.unregisterOnSharedPreferenceChangeListener(this)
            sensorManager?.unregisterListener(this)
            isGyroRegistered = false
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
            updateGyroRegistration()
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == "active_settings") {
                loadActiveSettings()
            } else if (key == "draw_progress" || key == "is_drawing" || key == "anim_time") {
                loadProgressAndState()
            }
        }

        private fun loadProgressAndState() {
            val appActive = sharedPrefs?.getBoolean("app_active", false) ?: false
            if (appActive) {
                drawProgress = sharedPrefs?.getFloat("draw_progress", drawProgress) ?: drawProgress
                animTime = sharedPrefs?.getLong("anim_time", animTime) ?: animTime
            }
        }

        private fun saveWallpaperProgressToPrefs() {
            try {
                sharedPrefs?.edit()
                    ?.putFloat("draw_progress", drawProgress)
                    ?.putLong("anim_time", animTime)
                    ?.apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun loadActiveSettings() {
            val json = sharedPrefs?.getString("active_settings", null)
            if (json != null) {
                try {
                    val s = adapter.fromJson(json)
                    if (s != null) {
                        val needsReset = !settings.isDrawingFormEquivalent(s)
                        settings = s
                        if (needsReset) {
                            drawProgress = 0f // Restart drawing only if physical form parameters changed
                            wallpaperDynamicTailLimit = -1
                        }
                        if (!settings.gyroEnabled) {
                            gyroYawOffset = 0f
                            gyroPitchOffset = 0f
                        }
                        updateGyroRegistration()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        private fun randomizeUnlockedSettings() {
            val r = Random()
            settings = settings.randomizeAll(r)
            wallpaperDynamicTailLimit = -1
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

        private fun saveSnapshotOnWallpaper() {
            val context = this@HarmonographWallpaperService
            val db = DatabaseProvider.getDatabase(context)
            val dao = db.dao()
            
            val locked = settings.lockAllLockable()
            settings = locked
            saveSettingsToPrefs(locked)
            
            val json = adapter.toJson(locked) ?: ""
            val displayName = "Wallpaper Snapshot #${System.currentTimeMillis() % 10000}"
            
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    dao.insertPreset(
                        HarmonographPreset(
                            name = displayName,
                            isUserPreset = true,
                            settingsJson = json
                        )
                    )
                    handler.post {
                        android.widget.Toast.makeText(context, "Wallpaper Snapshot Saved!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
                    isThreeFingersHeld = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (pointerCount == 2) {
                        fingerDownTime = System.currentTimeMillis()
                        isTwoFingersHeld = true
                        isThreeFingersHeld = false
                        lastX = event.getX(0)
                        lastY = event.getY(0)
                    } else if (pointerCount == 3) {
                        fingerDownTime = System.currentTimeMillis()
                        isThreeFingersHeld = true
                        isTwoFingersHeld = false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pointerCount == 2) {
                        val dx = event.getX(0) - lastX
                        val dy = event.getY(0) - lastY
                        touchBaseYaw += dx * 0.35f
                        touchBasePitch -= dy * 0.35f
                        lastX = event.getX(0)
                        lastY = event.getY(0)
                    } else if (pointerCount == 1 && !isTwoFingersHeld && !isThreeFingersHeld) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        touchBaseYaw += dx * 0.35f
                        touchBasePitch -= dy * 0.35f
                        lastX = event.x
                        lastY = event.y
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (pointerCount == 3 && isThreeFingersHeld) {
                        val pressDuration = System.currentTimeMillis() - fingerDownTime
                        if (pressDuration > 450) {
                            saveSnapshotOnWallpaper()
                        }
                        isThreeFingersHeld = false
                    } else if (pointerCount == 2 && isTwoFingersHeld) {
                        val pressDuration = System.currentTimeMillis() - fingerDownTime
                        val now = System.currentTimeMillis()
                        if (pressDuration > 450) {
                            // Two-finger tap and hold -> Swap perspectives
                            val p = if (settings.cameraPerspective == 1) 2 else 1
                            settings = settings.copy(cameraPerspective = p)
                            saveSettingsToPrefs(settings)
                        } else {
                            if (now - lastTwoFingerTapTime < 350) {
                                // Two-finger Double Tap -> Randomize
                                randomizeUnlockedSettings()
                                drawProgress = 0f
                            }
                        }
                        lastTwoFingerTapTime = now
                        isTwoFingersHeld = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    isTwoFingersHeld = false
                    isThreeFingersHeld = false
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
            
            val nowMs = System.currentTimeMillis()
            if (wallpaperLastFpsTime == 0L) {
                wallpaperLastFpsTime = nowMs
            } else {
                wallpaperFrameCount++
                val elapsedMsFps = nowMs - wallpaperLastFpsTime
                if (elapsedMsFps >= 500L) {
                    wallpaperCurrentFps = (wallpaperFrameCount * 1000f) / elapsedMsFps
                    wallpaperFrameCount = 0
                    wallpaperLastFpsTime = nowMs
                }
            }

            // Dynamic tail control for performance
            if (settings.drawSpeedInstant) {
                if (wallpaperDynamicTailLimit == -1) {
                    wallpaperDynamicTailLimit = -2
                } else if (wallpaperDynamicTailLimit == -2) {
                    wallpaperDynamicTailLimit = settings.instantDrawLengthLimit.current
                } else {
                    if (settings.perfRemoveTailEnabled) {
                        val targetFpsVal = settings.perfTargetFps.current
                        if (wallpaperCurrentFps < targetFpsVal && wallpaperDynamicTailLimit > 200) {
                            wallpaperDynamicTailLimit = (wallpaperDynamicTailLimit - 300).coerceAtLeast(200)
                        } else if (wallpaperCurrentFps > targetFpsVal + 5 && wallpaperDynamicTailLimit < settings.instantDrawLengthLimit.current) {
                            wallpaperDynamicTailLimit = (wallpaperDynamicTailLimit + 100).coerceAtMost(settings.instantDrawLengthLimit.current)
                        }
                    }
                }
            } else {
                wallpaperDynamicTailLimit = -1
            }

            var width = canvas.width.toFloat()
            var height = canvas.height.toFloat()
            
            val perfResolutionStr = settings.perfResolution
            val targetRes = perfResolutionStr.toIntOrNull() ?: -1
            val isScaled = targetRes > 0 && width.coerceAtLeast(height) > targetRes
            val scaleFactorGlobal = if (isScaled) targetRes.toFloat() / width.coerceAtLeast(height) else 1f

            try {
                // Background Paint - Deep Space Aesthetic
                canvas.drawColor(0xFF0F172A.toInt()) // Slate 900
                
                if (isScaled) {
                    canvas.save()
                    canvas.scale(1f / scaleFactorGlobal, 1f / scaleFactorGlobal)
                    width *= scaleFactorGlobal
                    height *= scaleFactorGlobal
                }

                // Calculate relative elapsed milliseconds smoothly using synchronized animTime
                val elapsedMs = animTime
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
                
                // Incorporate gyroscopic offsets if enabled
                if (settings.gyroEnabled) {
                    activeYaw = (activeYaw + gyroYawOffset) % 360f
                    activePitch = (activePitch + gyroPitchOffset)
                }
                
                // Base drawing paths evaluation (cached math to prevent CPU stalling on 15k points)
                if (cachedPaths == null || cachedShapes == null || cachedCenterPath == null || cachedSettingsForPoints != settings) {
                    val rawP = HarmonographMath.generatePathPoints(settings, settings.drawLengthSteps)
                    cachedPaths = rawP
                    cachedShapes = HarmonographMath.generatePeriodicShapes(settings, settings.drawLengthSteps)
                    
                    val centerP = if (rawP.size > 1 && rawP.firstOrNull()?.isNotEmpty() == true) {
                        val pSize = rawP[0].size
                        List(pSize) { i ->
                            var sx = 0f
                            var sy = 0f
                            var sz = 0f
                            for (pIdx in rawP.indices) {
                                val pt = rawP[pIdx][i]
                                sx += pt.x
                                sy += pt.y
                                sz += pt.z
                            }
                            com.example.Point3D(sx / rawP.size, sy / rawP.size, sz / rawP.size)
                        }
                    } else {
                        rawP.firstOrNull() ?: emptyList()
                    }
                    cachedCenterPath = centerP
                    cachedSettingsForPoints = settings
                }

                val rawPaths = cachedPaths!!
                val rawShapes = cachedShapes!!
                val centerPath = cachedCenterPath!!
                
                // Color hue cycle using elapsed elapsedMs
                val timeHueOffset = if (settings.hueShiftingEnabled) {
                    (elapsedMs * settings.hueShiftSpeed.current / 360).toLong() % 360
                } else {
                    0L
                }

                val stepsCount = settings.drawLengthSteps
                if (drawProgress < stepsCount - 1f) {
                    completionTimeOfAnim = null
                } else if (completionTimeOfAnim == null) {
                    completionTimeOfAnim = elapsedMs
                }

                val cameraTargetIndex = if (settings.cameraPerspective == 2 && drawProgress >= stepsCount.coerceAtLeast(1) - 1f) {
                    val durationMin = if (settings.drawSpeedInstant) 18.0f else (settings.drawSpeedMinutes.current * 7.0f).coerceAtLeast(15.0f)
                    val cycleDurationMs = (durationMin * 60f * 1000f).toLong().coerceAtLeast(1000L)
                    val startT = completionTimeOfAnim ?: elapsedMs
                    val completedTime = (elapsedMs - startT).coerceAtLeast(0L)
                    val fraction = (completedTime.toFloat() / cycleDurationMs) % 1.0f
                    val stepsInPath = rawPaths.firstOrNull()?.size ?: stepsCount
                    ((stepsInPath - 1f + (fraction * stepsInPath)) % stepsInPath).coerceIn(0f, (stepsInPath - 1).toFloat())
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
                        referencePoints = centerPath.ifEmpty { null },
                        cameraTargetIndex = cameraTargetIndex,
                        cameraDistance = settings.cameraDistance.current,
                        dynamicCameraZoomEnabled = settings.dynamicCameraZoomEnabled,
                        coasterDirectionFacing = settings.coasterDirectionFacing,
                        animTime = elapsedMs,
                        coasterDeviationAngle = settings.coasterDeviationAngle.current,
                        coasterOrbitSpeed = settings.coasterOrbitSpeed.current,
                        isPrimaryPath = (pIdx == 0),
                        tailLengthLimit = if (settings.drawSpeedInstant && !settings.instantDrawLengthInfinite.current) {
                            if (wallpaperDynamicTailLimit == -1 || wallpaperDynamicTailLimit == -2) -1 else wallpaperDynamicTailLimit
                        } else -1
                    )
                    
                    if (projPoints.isEmpty()) continue
                    
                    // Segment lines gathering
                    for (i in 0 until projPoints.size - 1) {
                        val p1 = projPoints[i]
                        val p2 = projPoints[i + 1]
                        if (p1.isBehindCamera || p2.isBehindCamera) continue
                        
                        // Compute color styled dynamically
                        val segmentColor1 = computeDynamicColor(i, projPoints.size, p1, width, height, timeHueOffset)
                        val segmentColor2 = computeDynamicColor(i + 1, projPoints.size, p2, width, height, timeHueOffset)
                        val baseThickness = settings.lineThickness.current
                        val strokeWidth = baseThickness + (0.5f * baseThickness * (p1.depth / 500f).coerceIn(-1f, 1f))
                        
                        segmentsList.add(WallpaperSegment(p1, p2, segmentColor1, segmentColor2, strokeWidth))
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
                    paint.strokeWidth = seg.strokeWidth
                    if (seg.color1 == seg.color2) {
                        paint.shader = null
                        paint.color = seg.color1
                    } else {
                        paint.shader = android.graphics.LinearGradient(
                            seg.p1.x, seg.p1.y, seg.p2.x, seg.p2.y,
                            seg.color1, seg.color2,
                            android.graphics.Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawLine(seg.p1.x, seg.p1.y, seg.p2.x, seg.p2.y, paint)
                }
                paint.shader = null

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
                val wallpaperTailLimitVal = if (settings.drawSpeedInstant && !settings.instantDrawLengthInfinite.current) {
                    if (wallpaperDynamicTailLimit == -1 || wallpaperDynamicTailLimit == -2) -1 else wallpaperDynamicTailLimit
                } else -1
                val wallpaperShapesStartIdx = if (wallpaperTailLimitVal > 0 && drawProgress > wallpaperTailLimitVal) {
                    drawProgress.toInt() - wallpaperTailLimitVal
                } else {
                    0
                }

                for (shape in rawShapes) {
                    val kIndex = shape.colorIndex
                    if (kIndex > drawProgress.roundToInt() || kIndex < wallpaperShapesStartIdx) continue
                    
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
                if (isScaled) {
                    try {
                        canvas.restore()
                    } catch (e: Exception) {}
                }
                holder.unlockCanvasAndPost(canvas)
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

        private fun computeDynamicColor(
            idx: Int,
            total: Int,
            pt: ProjectedPoint,
            width: Float,
            height: Float,
            hueOffset: Long
        ): Int {
            val sat = settings.saturation.current
            val minHue = settings.hueShiftRange.actualSelectedMin
            val maxHue = settings.hueShiftRange.actualSelectedMax
            
            // Prevent float precision loss of System.currentTimeMillis() by scaling down a modulo value
            val timeSec = (System.currentTimeMillis() % 100000L).toFloat() / 1000f
            
            val csMin = settings.chromaticShift.actualSelectedMin
            val csMax = settings.chromaticShift.actualSelectedMax
            val segmentChromaticShift = if (settings.liveChromaticShiftEnabled.current) {
                val sweepMin = if (settings.chromaticShift.rangeLocked) csMin else 0f
                val sweepMax = if (settings.chromaticShift.rangeLocked) csMax else 90f
                val speed = settings.chromaticShiftSpeed.current
                val cycleRatio = 0.5f + 0.5f * kotlin.math.sin(2f * kotlin.math.PI.toFloat() * speed * timeSec)
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
                val cycleRatio = 0.5f + 0.5f * kotlin.math.sin(2f * kotlin.math.PI.toFloat() * speed * timeSec)
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
                    adjustSaturationAndHue(settings.solidColor, sat, hueOffset, minHue, maxHue, segmentChromaticShift, pt, segmentAlpha)
                }
                "length" -> {
                    val ratio = idx.toFloat() / total.coerceAtLeast(1)
                    val color = interpolateColor(settings.gradientStartColor, settings.gradientEndColor, ratio)
                    adjustSaturationAndHue(color, sat, hueOffset, minHue, maxHue, segmentChromaticShift, pt, segmentAlpha)
                }
                "center" -> {
                    val maxDist3D = sqrt(
                        settings.ampX.current * settings.ampX.current +
                        settings.ampY.current * settings.ampY.current +
                        settings.ampZ.current * settings.ampZ.current
                    ).coerceAtLeast(10f)
                    val ratio = (pt.dist3D / maxDist3D).coerceIn(0f, 1f)
                    val color = interpolateColor(settings.gradientStartColor, settings.gradientEndColor, ratio)
                    adjustSaturationAndHue(color, sat, hueOffset, minHue, maxHue, segmentChromaticShift, pt, segmentAlpha)
                }
                "spicy" -> {
                    val seedBase = idx.toLong() * 1109L + settings.hashCode().toLong()
                    val segRand = java.util.Random(seedBase)
                    
                    val baseHue = settings.spicyHue.current
                    val hRange = settings.spicyColorRange.current
                    
                    val rHue1 = if (hRange > 0.1f) (baseHue + segRand.nextFloat() * hRange) % 360f else baseHue
                    val finalHueVal = (rHue1 + Math.abs(hueOffset) + segmentChromaticShift * (pt.depth / 120f)) % 360f
                    val finalHue = mapHueIntoRange(finalHueVal, minHue, maxHue)
                    val alpha = (255 * segmentAlpha).toInt().coerceIn(0, 255)
                    Color.HSVToColor(alpha, floatArrayOf(finalHue, sat, 0.95f))
                }
                else -> {
                    val baseHue = (settings.rainbowHue.current + (idx.toFloat() / total.coerceAtLeast(1)) * settings.rainbowColorRange.current) % 360f
                    val shiftedHue = (baseHue + Math.abs(hueOffset) + segmentChromaticShift * (pt.depth / 120f)) % 360f
                    val finalHue = mapHueIntoRange(shiftedHue, minHue, maxHue)
                    val alpha = (255 * segmentAlpha).toInt().coerceIn(0, 255)
                    Color.HSVToColor(alpha, floatArrayOf(finalHue, sat, 0.95f))
                }
            }

            return if (settings.monoScaleEnabled.current) {
                applyMonoScaleShiftToColorInt(finalColor, settings, idx)
            } else {
                finalColor
            }
        }

        private fun applyMonoScaleShiftToColorInt(colorInt: Int, settings: HarmonographSettings, idx: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(colorInt, hsv)
            val baseSat = hsv[1]
            val shiftVal = if (settings.monoScaleLiveShiftEnabled.current) {
                val msMin = settings.monoScaleShift.actualSelectedMin
                val msMax = settings.monoScaleShift.actualSelectedMax
                val sweepMin = if (settings.monoScaleShift.rangeLocked) msMin else -1.0f
                val sweepMax = if (settings.monoScaleShift.rangeLocked) msMax else 1.0f
                val speed = settings.monoScaleLiveShiftSpeed.current
                val timeSec = (System.currentTimeMillis() % 100000L).toFloat() / 1000f
                val cycleRatio = 0.5f + 0.5f * kotlin.math.sin(2f * kotlin.math.PI.toFloat() * speed * timeSec)
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
            val alpha = Color.alpha(colorInt)
            return Color.HSVToColor(alpha, hsv)
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
                    shape.center + (shape.uVector.cross(shape.wVector) * (conc * size * settings.periodicProgressiveDelay.current))
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
                    animTime = animTime,
                    coasterDeviationAngle = settings.coasterDeviationAngle.current,
                    coasterOrbitSpeed = settings.coasterOrbitSpeed.current
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
                    animTime = animTime,
                    coasterDeviationAngle = settings.coasterDeviationAngle.current,
                    coasterOrbitSpeed = settings.coasterOrbitSpeed.current
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

        private fun adjustSaturationAndHue(color: Int, sat: Float, hueOffset: Long, minHue: Float, maxHue: Float, chromaticShiftVal: Float, pt: ProjectedPoint, alphaVal: Float = 0.85f): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = sat
            val baseHue = hsv[0]
            val shiftedHue = (baseHue + Math.abs(hueOffset) + chromaticShiftVal * (pt.depth / 120f)) % 360f
            hsv[0] = mapHueIntoRange(shiftedHue, minHue, maxHue)
            hsv[2] = 0.95f
            val alpha = (alphaVal * 255).toInt().coerceIn(0, 255)
            return Color.HSVToColor(alpha, hsv)
        }
    }
}
