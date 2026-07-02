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

    private sealed class WPInstruction {
        abstract val depth: Float

        data class Line(
            override val depth: Float,
            val p1: ProjectedPoint,
            val p2: ProjectedPoint,
            val color1: Int,
            val color2: Int,
            val strokeWidth: Float
        ) : WPInstruction()

        data class PathFill(
            override val depth: Float,
            val path: Path,
            val color: Int,
            val alpha: Int
        ) : WPInstruction()
    }

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
               this.rationalFrequenciesEnabled.current == other.rationalFrequenciesEnabled.current &&
               this.rationalFrequenciesEnabled.locked == other.rationalFrequenciesEnabled.locked
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
        private val randomShift = java.util.Random()
        private val parameterShifters = listOf(
            // Amplitudes (6 to 15 mins)
            ParameterShifter({ it.ampX }, { s, p -> s.copy(ampX = p) }, { true }, 360f, 900f),
            ParameterShifter({ it.ampY }, { s, p -> s.copy(ampY = p) }, { true }, 360f, 900f),
            ParameterShifter({ it.ampZ }, { s, p -> s.copy(ampZ = p) }, { s -> s.ampZ.current > 0f || s.ampZ.actualSelectedMax > 1f }, 360f, 900f),
            ParameterShifter({ it.ampSubX }, { s, p -> s.copy(ampSubX = p) }, { s -> s.ampSubX.enabled && s.ampSubX.current > 0f }, 360f, 900f),
            ParameterShifter({ it.ampSubY }, { s, p -> s.copy(ampSubY = p) }, { s -> s.ampSubY.enabled && s.ampSubY.current > 0f }, 360f, 900f),
            ParameterShifter({ it.ampSubZ }, { s, p -> s.copy(ampSubZ = p) }, { s -> s.ampSubZ.enabled && s.ampSubZ.current > 0f }, 360f, 900f),

            // Frequencies (30 to 75 mins - extremely slow, prevents chaotic phase-velocity buildup over large t)
            ParameterShifter({ it.freqX }, { s, p -> s.copy(freqX = p) }, { true }, 1800f, 4500f),
            ParameterShifter({ it.freqY }, { s, p -> s.copy(freqY = p) }, { true }, 1800f, 4500f),
            ParameterShifter({ it.freqZ }, { s, p -> s.copy(freqZ = p) }, { s -> s.ampZ.current > 0f || s.ampZ.actualSelectedMax > 1f }, 1800f, 4500f),

            // Phases (9 to 20 mins)
            ParameterShifter({ it.phaseX }, { s, p -> s.copy(phaseX = p) }, { true }, 540f, 1200f),
            ParameterShifter({ it.phaseY }, { s, p -> s.copy(phaseY = p) }, { true }, 540f, 1200f),
            ParameterShifter({ it.phaseZ }, { s, p -> s.copy(phaseZ = p) }, { s -> s.ampZ.current > 0f || s.ampZ.actualSelectedMax > 1f }, 540f, 1200f),
            ParameterShifter({ it.phaseSubX }, { s, p -> s.copy(phaseSubX = p) }, { s -> s.ampSubX.enabled && s.ampSubX.current > 0f }, 540f, 1200f),
            ParameterShifter({ it.phaseSubY }, { s, p -> s.copy(phaseSubY = p) }, { s -> s.ampSubY.enabled && s.ampSubY.current > 0f }, 540f, 1200f),
            ParameterShifter({ it.phaseSubZ }, { s, p -> s.copy(phaseSubZ = p) }, { s -> s.ampSubZ.enabled && s.ampSubZ.current > 0f }, 540f, 1200f)
        )

        @Volatile private var drawProgress = 0f
        @Volatile private var animTime = 0L
        @Volatile private var isVisible = false
        private var completionTimeOfAnim: Long? = null
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var handlerThread: android.os.HandlerThread? = null
        private var backgroundHandler: android.os.Handler? = null

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

                    if (isPlay && settings.globalLiveShifting.current) {
                        var shiftedSettings = settings
                        for (shifter in parameterShifters) {
                            shiftedSettings = shifter.update(shiftedSettings, 0.016f, randomShift)
                        }
                        settings = shiftedSettings
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
                                    backgroundHandler?.postDelayed(this, postResetDelay)
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
                                    backgroundHandler?.postDelayed(this, postResetDelay)
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

                    backgroundHandler?.postDelayed(this, 16) // ~60fps Limit
                }
            }
        }

        override fun onCreate(surfaceHolder: android.view.SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            
            val thread = android.os.HandlerThread("HarmonographRenderThread")
            thread.start()
            handlerThread = thread
            backgroundHandler = android.os.Handler(thread.looper)

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
            
            backgroundHandler?.removeCallbacks(runDrawingRunnable)
            handlerThread?.quitSafely()
            handlerThread = null
            backgroundHandler = null
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisible = visible
            backgroundHandler?.removeCallbacks(runDrawingRunnable)
            if (visible) {
                backgroundHandler?.post(runDrawingRunnable)
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
                    val s = adapter.fromJson(json)?.normalize()
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
            settings = settings.randomizeAll(r).normalize()
            wallpaperDynamicTailLimit = -1
            saveSettingsToPrefs(settings)
        }

        private fun saveSettingsToPrefs(settings: HarmonographSettings) {
            try {
                val normalizedSettings = settings.normalize()
                val json = adapter.toJson(normalizedSettings)
                sharedPrefs?.edit()?.putString("active_settings", json)?.apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private fun saveSnapshotOnWallpaper() {
            val context = this@HarmonographWallpaperService
            val db = DatabaseProvider.getDatabase(context)
            val dao = db.dao()
            
            val locked = settings.lockAllLockable().normalize()
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
                    mainHandler.post {
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
            val canvas = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                try {
                    holder.lockHardwareCanvas()
                } catch (e: Exception) {
                    holder.lockCanvas()
                }
            } else {
                holder.lockCanvas()
            } ?: return
            
            val nowMs = System.currentTimeMillis()
            if (wallpaperLastFpsTime == 0L) {
                wallpaperLastFpsTime = nowMs
            } else {
                wallpaperFrameCount++
                val elapsedMsFps = nowMs - wallpaperLastFpsTime
                if (elapsedMsFps >= 1000L) {
                    wallpaperCurrentFps = (wallpaperFrameCount * 1000f) / elapsedMsFps
                    wallpaperFrameCount = 0
                    wallpaperLastFpsTime = nowMs
                    
                    if (settings.perfRemoveTailEnabled && !settings.instantDrawLengthInfinite.current) {
                        val targetFpsVal = settings.perfTargetFps.current
                        if (wallpaperCurrentFps < targetFpsVal) {
                            val currentLimit = if (wallpaperDynamicTailLimit == -1) settings.instantDrawLengthLimit.current else wallpaperDynamicTailLimit
                            val diff = targetFpsVal - wallpaperCurrentFps
                            val drop = if (diff > 10f) 200 else 100
                            wallpaperDynamicTailLimit = (currentLimit - drop).coerceAtLeast(200)
                        } else if (wallpaperDynamicTailLimit != -1) {
                            if (wallpaperCurrentFps >= targetFpsVal + 10f) {
                                val nextLimit = wallpaperDynamicTailLimit + 250
                                if (nextLimit >= settings.instantDrawLengthLimit.current) {
                                    wallpaperDynamicTailLimit = -1
                                } else {
                                    wallpaperDynamicTailLimit = nextLimit
                                }
                            } else if (wallpaperCurrentFps >= targetFpsVal + 5f) {
                                val nextLimit = wallpaperDynamicTailLimit + 100
                                if (nextLimit >= settings.instantDrawLengthLimit.current) {
                                    wallpaperDynamicTailLimit = -1
                                } else {
                                    wallpaperDynamicTailLimit = nextLimit
                                }
                            }
                        }
                    }
                }
            }

            var width = canvas.width.toFloat()
            var height = canvas.height.toFloat()
            
            val perfResolutionStr = settings.perfResolution
            val targetRes = perfResolutionStr.toIntOrNull() ?: -1
            val isScaled = targetRes > 0 && width.coerceAtLeast(height) > targetRes
            val scaleFactorGlobal = if (isScaled) targetRes.toFloat() / width.coerceAtLeast(height) else 1f

            try {
                // Background Paint - Deep Space Aesthetic
                canvas.drawColor(android.graphics.Color.BLACK) // Completely black
                
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
                    val speedMult = settings.cameraAutoRotationSpeed * 10f
                    val theta = speedMult * (
                        (-kotlin.math.cos(0.07f * timeSec) + 1f) / 0.07f +
                        1.2f * kotlin.math.sin(0.03f * timeSec) / 0.03f -
                        0.8f * (kotlin.math.cos(0.013f * timeSec) - 1f) / 0.013f
                    )
                    val pitchOffset = 12f * (kotlin.math.sin(0.05f * timeSec) + 0.5f * (kotlin.math.cos(0.022f * timeSec) - 1f))
                    activeYaw = (baseYaw + theta) % 360f
                    activePitch = basePitch + pitchOffset
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
                val settingsHash = settings.getStableHash()
                
                // Color hue cycle using elapsed elapsedMs
                val timeHueOffset = if (settings.hueShiftingEnabled.current) {
                    (elapsedMs * settings.hueShiftSpeed.current / 360).toLong() % 360
                } else {
                    0L
                }

                val stepsCount = settings.drawLengthSteps
                val stepsInPath = rawPaths.firstOrNull()?.size ?: stepsCount
                val isClosedLoop = rawPaths.firstOrNull()?.let { it.size > 1 && it.first() == it.last() } == true
                if (drawProgress < stepsInPath - 1f) {
                    completionTimeOfAnim = null
                } else if (completionTimeOfAnim == null) {
                    completionTimeOfAnim = elapsedMs
                }

                val cameraTargetIndex = if (settings.cameraPerspective == 2 && drawProgress >= stepsInPath - 1f) {
                    val durationMin = if (settings.drawSpeedInstant) 18.0f else (settings.drawSpeedMinutes.current * 7.0f).coerceAtLeast(15.0f)
                    val cycleDurationMs = (durationMin * 60f * 1000f).toLong().coerceAtLeast(1000L)
                    val startT = completionTimeOfAnim ?: elapsedMs
                    val completedTime = (elapsedMs - startT).coerceAtLeast(0L)
                    val fraction = (completedTime.toFloat() / cycleDurationMs) % 1.0f
                    ((stepsInPath - 1f + (fraction * stepsInPath)) % stepsInPath).coerceIn(0f, (stepsInPath - 1).toFloat())
                } else {
                    drawProgress
                }

                // Project and gather line segments across all paths for unified depth sorting
                val drawList = mutableListOf<WPInstruction>()
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
                        tailLengthLimit = if (settings.instantDrawLengthInfinite.current) {
                            -1
                        } else if (settings.perfRemoveTailEnabled) {
                            if (wallpaperDynamicTailLimit == -1) {
                                settings.instantDrawLengthLimit.current
                            } else {
                                wallpaperDynamicTailLimit
                            }
                        } else {
                            settings.instantDrawLengthLimit.current
                        },
                        globalLiveShifting = settings.globalLiveShifting.current
                    )
                    
                    if (projPoints.isEmpty()) continue
                    
                    // Segment lines gathering with sub-pixel lines merging optimization to save execution and drawing cycles
                    if (projPoints.isNotEmpty()) {
                        var lastAddedP = projPoints.first()
                        for (i in 1 until projPoints.size) {
                            val p2 = projPoints[i]
                            if (p2.isBehindCamera) {
                                lastAddedP = p2
                                continue
                            }
                            if (lastAddedP.isBehindCamera) {
                                lastAddedP = p2
                                continue
                            }
                            
                            val dx = p2.x - lastAddedP.x
                            val dy = p2.y - lastAddedP.y
                            if (i < projPoints.size - 1 && (dx * dx + dy * dy) < 2.25f) { // 1.5 pixels squared threshold
                                continue
                            }
                            
                            // Compute color styled dynamically once per segment
                            val segmentColor = computeDynamicColor(lastAddedP.originalIndex, stepsInPath, lastAddedP, width, height, timeHueOffset, settingsHash, isClosedLoop)
                            val baseThickness = settings.lineThickness.current
                            val strokeWidth = baseThickness + (0.5f * baseThickness * (lastAddedP.depth / 500f).coerceIn(-1f, 1f))
                            
                            drawList.add(WPInstruction.Line((lastAddedP.depth + p2.depth) / 2f, lastAddedP, p2, segmentColor, segmentColor, strokeWidth))
                            lastAddedP = p2
                        }
                    }

                    // Store pen tip if enabled
                    if (settings.penTipEnabled && projPoints.isNotEmpty()) {
                        val tip = projPoints.last()
                        val tipColor = if (settings.penTipColorMode == "solid") {
                            settings.penTipColor
                        } else {
                            computeDynamicColor(
                                tip.originalIndex,
                                stepsInPath,
                                tip,
                                width,
                                height,
                                timeHueOffset,
                                settingsHash,
                                isClosedLoop
                            )
                        }
                        tipsList.add(Pair(tip, tipColor))
                    }
                }

                // Periodic shapes orthogonal details
                val wallpaperTailLimitVal = if (settings.instantDrawLengthInfinite.current) {
                    -1
                } else if (settings.perfRemoveTailEnabled) {
                    if (wallpaperDynamicTailLimit == -1) {
                        settings.instantDrawLengthLimit.current
                    } else {
                        wallpaperDynamicTailLimit
                    }
                } else {
                    settings.instantDrawLengthLimit.current
                }
                val wallpaperShapesStartIdx = if (wallpaperTailLimitVal > 0 && drawProgress > wallpaperTailLimitVal) {
                    drawProgress.toInt() - wallpaperTailLimitVal
                } else {
                    0
                }

                for (shape in rawShapes) {
                    val kIndex = shape.colorIndex
                    if (kIndex > drawProgress.roundToInt() || kIndex < wallpaperShapesStartIdx) continue
                    
                    // Add secondary shapes to the unified WP drawList
                    addOrthogonalShapeToDrawList(
                        shape = shape,
                        yawVal = activeYaw,
                        pitchVal = activePitch,
                        perspective = settings.cameraPerspective,
                        width = width,
                        height = height,
                        angularLock = settings.isAngularLockEnabled,
                        angularLockAxis = settings.angularLockAxis,
                        hueOffset = timeHueOffset,
                        totalSteps = stepsInPath,
                        centerPathPoints = centerPath,
                        mainPathPoints = rawPaths.getOrNull(shape.penIndex) ?: rawPaths.firstOrNull() ?: emptyList(),
                        cameraTargetIndex = cameraTargetIndex,
                        animTime = elapsedMs,
                        drawProgress = drawProgress,
                        drawList = drawList,
                        settingsHash = settingsHash,
                        isClosedLoop = isClosedLoop
                    )
                }

                // Sort all draw list items back-to-front (descending by average depth)
                drawList.sortByDescending { it.depth }

                // Draw depth-sorted unified list on the canvas
                for (inst in drawList) {
                    when (inst) {
                        is WPInstruction.Line -> {
                            paint.strokeWidth = inst.strokeWidth
                            if (inst.color1 == inst.color2) {
                                paint.shader = null
                                paint.color = inst.color1
                            } else {
                                paint.shader = android.graphics.LinearGradient(
                                    inst.p1.x, inst.p1.y, inst.p2.x, inst.p2.y,
                                    inst.color1, inst.color2,
                                    android.graphics.Shader.TileMode.CLAMP
                                )
                            }
                            canvas.drawLine(inst.p1.x, inst.p1.y, inst.p2.x, inst.p2.y, paint)
                        }
                        is WPInstruction.PathFill -> {
                            fillPaint.color = inst.color
                            fillPaint.alpha = inst.alpha
                            canvas.drawPath(inst.path, fillPaint)
                        }
                    }
                }
                paint.shader = null

                // Render styled active pen tip markers on top of all drawings
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

                // Render togglable tech-overlay HUD for real-time wallpaper FPS counters
                if (settings.perfWallpaperShowFps) {
                    val fpsPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE
                        textSize = 34f
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    }
                    val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xAA0F172A.toInt() // Slate 900 custom transparency overlay
                        style = android.graphics.Paint.Style.FILL
                    }
                    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFF00E5FF.toInt() // Cyber Cyan Border
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = 3f
                    }
                    val fpsText = "WP: ${wallpaperCurrentFps.toInt()} FPS"
                    val left = 48f
                    val top = 120f
                    val right = left + fpsPaint.measureText(fpsText) + 32f
                    val bottom = top + 64f
                    
                    val rect = android.graphics.RectF(left, top, right, bottom)
                    canvas.drawRoundRect(rect, 32f, 32f, bgPaint)
                    canvas.drawRoundRect(rect, 32f, 32f, borderPaint)
                    canvas.drawText(fpsText, left + 16f, top + 44f, fpsPaint)
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

        private fun getDeterministicRandomFloat(index: Int, salt: Long, settingsHash: Long): Float {
            var h = index.toLong() * 312251L + salt * 17971L + settingsHash * 4371427L
            h = h xor (h ushr 21)
            h = h xor (h shl 35)
            h = h xor (h ushr 4)
            h = h * 2685821657736338717L
            return (java.lang.Math.abs(h) % 1000000L).toFloat() / 1000000f
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
            hueOffset: Long,
            settingsHash: Long,
            isClosedLoop: Boolean = false
        ): Int {
            val sat = settings.saturation.current
            val minHue = settings.hueShiftRange.actualSelectedMin
            val maxHue = settings.hueShiftRange.actualSelectedMax
            
            // Prevent float precision loss of System.currentTimeMillis() by using Double for progress calculation
            val timeSecDouble = System.currentTimeMillis().toDouble() / 1000.0
            
            val csMin = settings.chromaticShift.actualSelectedMin
            val csMax = settings.chromaticShift.actualSelectedMax
            val segmentChromaticShift = if (settings.liveChromaticShiftEnabled.current) {
                val sweepMin = if (settings.chromaticShift.rangeLocked) csMin else 0f
                val sweepMax = if (settings.chromaticShift.rangeLocked) csMax else 90f
                val speed = settings.chromaticShiftSpeed.current
                val progress = ((speed.toDouble() * timeSecDouble) % 1.0).toFloat()
                val cycleRatio = if (progress < 0.5f) progress * 2f else (1.0f - progress) * 2f
                val liveCS = sweepMin + cycleRatio * (sweepMax - sweepMin)
                liveCS.coerceIn(0f, 180f)
            } else if (settings.chromaticShift.rangeLocked && csMax > csMin) {
                val csRandVal = getDeterministicRandomFloat(idx, 37L, settingsHash)
                csMin + csRandVal * (csMax - csMin)
            } else {
                settings.chromaticShift.current
            }

            val alphaMin = settings.lineAlpha.actualSelectedMin
            val alphaMax = settings.lineAlpha.actualSelectedMax
            val segmentAlpha = if (settings.liveAlphaShiftEnabled.current) {
                val sweepMin = if (settings.lineAlpha.rangeLocked) alphaMin else 0.1f
                val sweepMax = if (settings.lineAlpha.rangeLocked) alphaMax else 1.0f
                val speed = settings.liveAlphaShiftSpeed.current
                val progress = ((speed.toDouble() * timeSecDouble) % 1.0).toFloat()
                val cycleRatio = if (progress < 0.5f) progress * 2f else (1.0f - progress) * 2f
                val liveAlpha = sweepMin + cycleRatio * (sweepMax - sweepMin)
                liveAlpha.coerceIn(0.01f, 1.0f)
            } else if (settings.lineAlpha.rangeLocked && alphaMax > alphaMin) {
                val alphaRandVal = getDeterministicRandomFloat(idx, 97L, settingsHash)
                alphaMin + alphaRandVal * (alphaMax - alphaMin)
            } else {
                settings.lineAlpha.current
            }
            
            val finalColor = when (settings.styleMode) {
                "solid" -> {
                    adjustSaturationAndHue(settings.solidColor, sat, hueOffset, minHue, maxHue, segmentChromaticShift, pt, segmentAlpha)
                }
                "length" -> {
                    var ratio = idx.toFloat() / total.coerceAtLeast(1)
                    if (isClosedLoop) {
                        ratio = if (ratio < 0.5f) ratio * 2f else (1.0f - ratio) * 2f
                    }
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
                    val seed1 = (settingsHash % 7919L).toFloat() / 7919f
                    val seed2 = ((settingsHash / 7919L) % 65537L).toFloat() / 65537f
                    val seed3 = ((settingsHash / 524287L) % 100003L).toFloat() / 100003f
                    
                    val effectiveIdx = if (isClosedLoop) {
                        val ratio = idx.toFloat() / total.coerceAtLeast(1)
                        val symRatio = if (ratio < 0.5f) ratio * 2f else (1.0f - ratio) * 2f
                        (symRatio * (total / 2f)).toInt()
                    } else {
                        idx
                    }
                    
                    val theta1 = effectiveIdx.toFloat() * 0.0051f + seed1 * 100f
                    val theta2 = effectiveIdx.toFloat() * 0.0139f + seed2 * 200f
                    val theta3 = effectiveIdx.toFloat() * 0.0383f + seed3 * 300f
                    
                    val rHueVal = 0.5f + 0.3f * kotlin.math.sin(theta1) + 0.15f * kotlin.math.cos(theta2) + 0.05f * kotlin.math.sin(theta3)
                    
                    val baseHue = settings.spicyHue.current
                    val hRange = settings.spicyColorRange.current
                    
                    val rHue1 = if (hRange > 0.1f) (baseHue + rHueVal * hRange) % 360f else baseHue
                    val finalHueVal = (rHue1 + Math.abs(hueOffset) + segmentChromaticShift * (pt.depth / 120f)) % 360f
                    val finalHue = mapHueIntoRange(finalHueVal, minHue, maxHue)
                    val alpha = (255 * segmentAlpha).toInt().coerceIn(0, 255)
                    Color.HSVToColor(alpha, floatArrayOf(finalHue, sat, 0.95f))
                }
                else -> {
                    var ratio = idx.toFloat() / total.coerceAtLeast(1)
                    if (isClosedLoop) {
                        ratio = if (ratio < 0.5f) ratio * 2f else (1.0f - ratio) * 2f
                    }
                    val baseHue = (settings.rainbowHue.current + ratio * settings.rainbowColorRange.current) % 360f
                    val shiftedHue = (baseHue + Math.abs(hueOffset) + segmentChromaticShift * (pt.depth / 120f)) % 360f
                    val finalHue = mapHueIntoRange(shiftedHue, minHue, maxHue)
                    val alpha = (255 * segmentAlpha).toInt().coerceIn(0, 255)
                    Color.HSVToColor(alpha, floatArrayOf(finalHue, sat, 0.95f))
                }
            }

            return if (settings.monoScaleEnabled.current) {
                applyMonoScaleShiftToColorInt(finalColor, settings, idx, settingsHash)
            } else {
                finalColor
            }
        }

        private fun applyMonoScaleShiftToColorInt(colorInt: Int, settings: HarmonographSettings, idx: Int, settingsHash: Long): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(colorInt, hsv)
            val baseSat = hsv[1]
            val shiftVal = if (settings.monoScaleLiveShiftEnabled.current) {
                val sweepMin: Float
                val sweepMax: Float
                if (settings.monoWaveEffectiveRange.rangeLocked) {
                    sweepMin = settings.monoWaveEffectiveRange.actualSelectedMin
                    sweepMax = settings.monoWaveEffectiveRange.actualSelectedMax
                } else {
                    val halfRange = kotlin.math.abs(settings.monoWaveEffectiveRange.current)
                    sweepMin = -halfRange
                    sweepMax = halfRange
                }
                val speed = settings.monoScaleLiveShiftSpeed.current / 12f // extremely calming pace
                val timeSecDouble = System.currentTimeMillis().toDouble() / 1000.0
                val baseWavelength = 1200f // wide, smooth waves
                val randomness = settings.monoWaveRandomness.current
                
                // We want randomness to vary/randomize the wave duration (both spatial wavelength and temporal period).
                // Let's use a slow, continuous modulation wave for this duration variation.
                // The speed of this modulation is extremely slow to keep it calming.
                val modTime = timeSecDouble * 0.15
                val waveModulator = kotlin.math.sin(modTime * 2.0 * kotlin.math.PI) * 0.4f + 
                                    kotlin.math.cos(modTime * 1.3 * kotlin.math.PI) * 0.2f
                                    
                // Modulate wavelength by waveModulator scaled by randomness
                // When randomness is 0, wavelength is exactly baseWavelength
                // When randomness is 1, wavelength varies slowly between 0.4 * baseWavelength and 1.6 * baseWavelength
                val modulatedWavelength = baseWavelength * (1.0f + waveModulator.toFloat() * randomness * 0.6f)
                
                // Let's also introduce a secondary, slow propagating wave that blends in with randomness.
                // This adds organic complexity ("randomized duration of waves") without any high-frequency flicker.
                val mainWavePhase = (idx.toFloat() / modulatedWavelength) - (timeSecDouble.toFloat() * speed * 2f * kotlin.math.PI.toFloat())
                
                // Secondary wave has a slightly different wavelength and speed (e.g. golden ratio)
                val secondaryWavelength = modulatedWavelength * 1.618f
                val secondarySpeed = speed * 0.618f
                val secondaryWavePhase = (idx.toFloat() / secondaryWavelength) - (timeSecDouble.toFloat() * secondarySpeed * 2f * kotlin.math.PI.toFloat()) + 1.25f
                
                // Blend the main wave and secondary wave based on randomness
                // If randomness is 0, we have a pure simple sine wave
                // If randomness is 1, we blend in the secondary wave to create organic wave-packet variations
                val combinedPhase = if (randomness > 0f) {
                    mainWavePhase * (1.0f - randomness * 0.3f) + secondaryWavePhase * (randomness * 0.3f)
                } else {
                    mainWavePhase
                }
                
                val waveBase = kotlin.math.sin(combinedPhase)
                
                val cycleRatio = 0.5f + 0.5f * waveBase
                sweepMin + cycleRatio * (sweepMax - sweepMin)
            } else if (settings.monoScaleShift.rangeLocked) {
                val msMin = settings.monoScaleShift.actualSelectedMin
                val msMax = settings.monoScaleShift.actualSelectedMax
                
                val seed1 = (settingsHash % 7919L).toFloat() / 7919f
                val seed2 = ((settingsHash / 7919L) % 65537L).toFloat() / 65537f
                val seed3 = ((settingsHash / 524287L) % 100003L).toFloat() / 100003f
                
                val theta1 = idx.toFloat() * 0.0051f + seed1 * 100f
                val theta2 = idx.toFloat() * 0.0139f + seed2 * 200f
                val theta3 = idx.toFloat() * 0.0383f + seed3 * 300f
                
                val msRandVal = 0.5f + 0.3f * kotlin.math.sin(theta1) + 0.15f * kotlin.math.cos(theta2) + 0.05f * kotlin.math.sin(theta3)
                msMin + msRandVal * (msMax - msMin)
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

        private fun addOrthogonalShapeToDrawList(
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
            centerPathPoints: List<Point3D> = emptyList(),
            mainPathPoints: List<Point3D> = emptyList(),
            cameraTargetIndex: Float = -1f,
            animTime: Long = 0L,
            drawProgress: Float,
            drawList: MutableList<WPInstruction>,
            settingsHash: Long,
            isClosedLoop: Boolean = false
        ) {
            val concentricLevels = shape.concentric
            val baseSize = shape.size
            
            val virtualDurationSec = if (settings.drawSpeedInstant) 120f else settings.drawSpeedMinutes.current * 60f
            val stepsPerSec = totalSteps.toFloat() / virtualDurationSec.coerceAtLeast(1f)
            val delayInSteps = settings.periodicProgressiveDelay.current * stepsPerSec

            for (conc in 0 until concentricLevels) {
                val requiredProgress = shape.colorIndex + conc * delayInSteps
                if (drawProgress < requiredProgress) continue

                // Stacked vs progressive concentric layouts (outermost largest first)
                val scaleFactor = 1f + (concentricLevels - 1 - conc) * 0.5f
                val size = baseSize * scaleFactor
                
                val targetStep = (shape.colorIndex + conc * delayInSteps).roundToInt()
                val baseCenter = if (mainPathPoints.isNotEmpty()) {
                    val idxCoerced = targetStep.coerceIn(mainPathPoints.indices)
                    mainPathPoints[idxCoerced]
                } else {
                    shape.center
                }

                val centerPt3D = baseCenter
                
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
                    referencePoints = centerPathPoints.ifEmpty { null },
                    cameraTargetIndex = cameraTargetIndex,
                    cameraDistance = settings.cameraDistance.current,
                    dynamicCameraZoomEnabled = settings.dynamicCameraZoomEnabled,
                    coasterDirectionFacing = settings.coasterDirectionFacing,
                    animTime = animTime,
                    coasterDeviationAngle = settings.coasterDeviationAngle.current,
                    coasterOrbitSpeed = settings.coasterOrbitSpeed.current,
                    globalLiveShifting = settings.globalLiveShifting.current
                )
                
                val centerPtScreen = centerProj.firstOrNull() ?: continue
                val shapeColor = computeDynamicColor(shape.colorIndex, totalSteps, centerPtScreen, width, height, hueOffset, settingsHash, isClosedLoop)
                
                if (shape.shapeType == "cross") {
                    val p1 = centerPt3D + shape.uVector * size
                    val p2 = centerPt3D - shape.uVector * size
                    val p3 = centerPt3D + shape.wVector * size
                    val p4 = centerPt3D - shape.wVector * size

                    val projPts = HarmonographMath.project3DTo2D(
                        points = listOf(p1, p2, p3, p4),
                        yaw = yawVal,
                        pitch = pitchVal,
                        perspective = perspective,
                        currentDrawProgress = 4f,
                        screenWidth = width,
                        screenHeight = height,
                        angularLock = angularLock,
                        angularLockAxis = angularLockAxis,
                        referencePoints = centerPathPoints.ifEmpty { null },
                        cameraTargetIndex = cameraTargetIndex,
                        cameraDistance = settings.cameraDistance.current,
                        dynamicCameraZoomEnabled = settings.dynamicCameraZoomEnabled,
                        coasterDirectionFacing = settings.coasterDirectionFacing,
                        animTime = animTime,
                        coasterDeviationAngle = settings.coasterDeviationAngle.current,
                        coasterOrbitSpeed = settings.coasterOrbitSpeed.current,
                        globalLiveShifting = settings.globalLiveShifting.current
                    )

                    if (projPts.size == 4) {
                        val pr1 = projPts[0]
                        val pr2 = projPts[1]
                        val pr3 = projPts[2]
                        val pr4 = projPts[3]
                        
                        val avgDepth1 = (pr1.depth + pr2.depth) / 2f
                        val avgDepth2 = (pr3.depth + pr4.depth) / 2f

                        drawList.add(WPInstruction.Line(avgDepth1, pr1, pr2, shapeColor, shapeColor, 1.5f))
                        drawList.add(WPInstruction.Line(avgDepth2, pr3, pr4, shapeColor, shapeColor, 1.5f))
                    }
                } else {
                    val vertices = when (shape.shapeType) {
                        "triangle" -> 3
                        "square" -> 4
                        "diamond" -> 4
                        "star" -> 10
                        else -> 16 // "circle"
                    }

                    val shape3DPoints = mutableListOf<Point3D>()
                    for (v in 0 until vertices) {
                        val baseAngle = (2f * PI * v / vertices).toFloat()
                        val angle = if (shape.shapeType == "square") baseAngle + (PI / 4f).toFloat() else baseAngle
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
                        referencePoints = centerPathPoints.ifEmpty { null },
                        cameraTargetIndex = cameraTargetIndex,
                        cameraDistance = settings.cameraDistance.current,
                        dynamicCameraZoomEnabled = settings.dynamicCameraZoomEnabled,
                        coasterDirectionFacing = settings.coasterDirectionFacing,
                        animTime = animTime,
                        coasterDeviationAngle = settings.coasterDeviationAngle.current,
                        coasterOrbitSpeed = settings.coasterOrbitSpeed.current,
                        globalLiveShifting = settings.globalLiveShifting.current
                    )

                    if (projPts.size >= 2) {
                        if (shape.isSolid) {
                            val polyPath = Path()
                            polyPath.moveTo(projPts[0].x, projPts[0].y)
                            for (ptIdx in 1 until projPts.size) {
                                polyPath.lineTo(projPts[ptIdx].x, projPts[ptIdx].y)
                            }
                            polyPath.close()
                            val avgDepth = projPts.map { it.depth }.average().toFloat()
                            drawList.add(WPInstruction.PathFill(avgDepth, polyPath, shapeColor, 150))
                        } else {
                            for (ptIdx in 0 until projPts.size - 1) {
                                val p1 = projPts[ptIdx]
                                val p2 = projPts[ptIdx + 1]
                                val avgDepth = (p1.depth + p2.depth) / 2f
                                drawList.add(WPInstruction.Line(avgDepth, p1, p2, shapeColor, shapeColor, 1.5f))
                            }
                        }
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
