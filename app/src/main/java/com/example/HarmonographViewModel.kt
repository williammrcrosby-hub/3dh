package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Random
import androidx.compose.runtime.mutableStateOf

class HarmonographViewModel(application: Application) : AndroidViewModel(application) {

    val gyroYawOffset = mutableStateOf(0f)
    val gyroPitchOffset = mutableStateOf(0f)

    fun addGyroOffsets(yawDelta: Float, pitchDelta: Float) {
        gyroYawOffset.value += yawDelta
        gyroPitchOffset.value += pitchDelta
    }

    private val db = DatabaseProvider.getDatabase(application)
    private val dao = db.dao()
    
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(HarmonographSettings::class.java)

    private val _uiState = MutableStateFlow(HarmonographUiState())
    val uiState: StateFlow<HarmonographUiState> = _uiState.asStateFlow()

    val currentSettings: HarmonographSettings
        get() = _uiState.value.settings

    val currentDrawProgress: StateFlow<Float> = _uiState
        .map { it.drawProgress }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    val isDrawing: StateFlow<Boolean> = _uiState
        .map { it.isDrawing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val currentFps = MutableStateFlow(60f)
    fun updateFps(value: Float) {
        currentFps.value = value
    }

    val savedPresets: StateFlow<List<HarmonographPreset>> = dao.getAllPresets()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var drawingJob: Job? = null
    private val random = Random()

    private val jointFrequencyShifter = JointFrequencyShifter(120f, 300f)

    private val parameterShifters = listOf(
        // Amplitudes (6 to 15 mins)
        ParameterShifter({ it.ampX }, { s, p -> s.copy(ampX = p) }, { true }, 360f, 900f),
        ParameterShifter({ it.ampY }, { s, p -> s.copy(ampY = p) }, { true }, 360f, 900f),
        ParameterShifter({ it.ampZ }, { s, p -> s.copy(ampZ = p) }, { s -> s.ampZ.current > 0f || s.ampZ.actualSelectedMax > 1f }, 360f, 900f),
        ParameterShifter({ it.ampSubX }, { s, p -> s.copy(ampSubX = p) }, { s -> s.ampSubX.enabled && s.ampSubX.current > 0f }, 360f, 900f),
        ParameterShifter({ it.ampSubY }, { s, p -> s.copy(ampSubY = p) }, { s -> s.ampSubY.enabled && s.ampSubY.current > 0f }, 360f, 900f),
        ParameterShifter({ it.ampSubZ }, { s, p -> s.copy(ampSubZ = p) }, { s -> s.ampSubZ.enabled && s.ampSubZ.current > 0f }, 360f, 900f),

        // Phases (9 to 20 mins)
        ParameterShifter({ it.phaseX }, { s, p -> s.copy(phaseX = p) }, { true }, 540f, 1200f),
        ParameterShifter({ it.phaseY }, { s, p -> s.copy(phaseY = p) }, { true }, 540f, 1200f),
        ParameterShifter({ it.phaseZ }, { s, p -> s.copy(phaseZ = p) }, { s -> s.ampZ.current > 0f || s.ampZ.actualSelectedMax > 1f }, 540f, 1200f),
        ParameterShifter({ it.phaseSubX }, { s, p -> s.copy(phaseSubX = p) }, { s -> s.ampSubX.enabled && s.ampSubX.current > 0f }, 540f, 1200f),
        ParameterShifter({ it.phaseSubY }, { s, p -> s.copy(phaseSubY = p) }, { s -> s.ampSubY.enabled && s.ampSubY.current > 0f }, 540f, 1200f),
        ParameterShifter({ it.phaseSubZ }, { s, p -> s.copy(phaseSubZ = p) }, { s -> s.ampSubZ.enabled && s.ampSubZ.current > 0f }, 540f, 1200f)
    )

    fun loadActiveSettingsFromPrefs() {
        val prefs = getApplication<Application>().getSharedPreferences("harmonograph_prefs", android.content.Context.MODE_PRIVATE)
        val savedJson = prefs.getString("active_settings", null)
        if (savedJson != null) {
            try {
                val s = adapter.fromJson(savedJson)?.normalize()
                if (s != null && s != _uiState.value.settings) {
                    _uiState.update { it.copy(settings = s) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    init {
        // Load active settings from prefs on VM creation to ensure all settings persist and translate.
        val prefs = application.getSharedPreferences("harmonograph_prefs", android.content.Context.MODE_PRIVATE)
        val savedJson = prefs.getString("active_settings", null)
        if (savedJson != null) {
            try {
                val loaded = adapter.fromJson(savedJson)?.normalize()
                if (loaded != null) {
                    _uiState.update { it.copy(settings = loaded) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Pre-populate updated default presets on startup, keeping user-created presets intact
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteDefaultPresets()
            insertDefaultPresets()
        }
        startDrawingLoop()
    }

    private fun startDrawingLoop() {
        if (drawingJob != null && drawingJob?.isActive == true) return
        drawingJob = viewModelScope.launch(Dispatchers.Default) {
            var completionTimeMs = 0L
            var lastSaveTime = 0L
            while (true) {
                val prefs = getApplication<android.app.Application>().getSharedPreferences("harmonograph_prefs", android.content.Context.MODE_PRIVATE)
                val isActive = prefs.getBoolean("app_active", false)
                if (!isActive) {
                    delay(250)
                    continue
                }
                delay(16) // ~60fps ticker loop
                
                var shouldReset = false
                var shouldSetCompletionTime = false
                var shouldClearCompletionTime = false
                var updatedSettings: HarmonographSettings? = null
                var finalProgress = 0f
                var isCurrentlyDrawing = true
                
                _uiState.update { current ->
                    var settings = current.settings
                    val isPlay = current.isDrawing
                    
                    if (settings.globalLiveShifting.current && isPlay) {
                        var shiftedSettings = settings
                        val dtShift = 0.016f * settings.globalLiveShiftSpeedMultiplier.current
                        for (shifter in parameterShifters) {
                            shiftedSettings = shifter.update(shiftedSettings, dtShift, random)
                        }
                        shiftedSettings = jointFrequencyShifter.update(shiftedSettings, dtShift, random)
                        if (shiftedSettings != settings) {
                            settings = shiftedSettings
                        }
                    }
                    
                    val progress = current.drawProgress
                    val maxSteps = if (settings.drawSpeedInstant && !settings.instantDrawLengthInfinite.current) {
                        settings.instantDrawLengthLimit.current.toFloat()
                    } else {
                        val baseMax = (settings.drawLengthSteps * settings.drawLengthFactor).toFloat()
                        if (settings.drawLengthLooping && !settings.globalLiveShifting.current) {
                            val paths = HarmonographMath.generatePathPoints(settings, settings.drawLengthSteps)
                            val stepsInPath = paths.firstOrNull()?.size?.toFloat() ?: baseMax
                            if (stepsInPath < settings.drawLengthSteps) {
                                stepsInPath
                            } else {
                                baseMax
                            }
                        } else {
                            baseMax
                        }
                    }
                    var nextProgress = progress
                    var triggerReset = false

                    if (isPlay) {
                        if (settings.drawSpeedInstant) {
                            if (progress < maxSteps) {
                                nextProgress = maxSteps
                            } else {
                                val totalDurationSec = settings.drawSpeedMinutes.current * 60f
                                val stepsPerSec = maxSteps / totalDurationSec
                                val stepsPerFrame = stepsPerSec * 0.016f
                                val nextVal = progress + stepsPerFrame
                                
                                val resetThreshold = maxSteps * (1f + settings.postCompletionResetTimeFactor)
                                if (settings.postCompletionAutoReset && nextVal >= resetThreshold) {
                                    triggerReset = true
                                } else {
                                    nextProgress = nextVal
                                }
                            }
                        } else {
                            val totalDurationSec = settings.drawSpeedMinutes.current * 60f
                            val baseMax = (settings.drawLengthSteps * settings.drawLengthFactor).toFloat()
                            val stepsPerSec = baseMax / totalDurationSec
                            val stepsPerFrame = stepsPerSec * 0.016f
                            val sumProgress = progress + stepsPerFrame
                            val fullMaxSteps = maxSteps
                            val newProgress = if (sumProgress > fullMaxSteps) fullMaxSteps else sumProgress
                            nextProgress = newProgress
                            
                            if (newProgress >= fullMaxSteps) {
                                if (settings.postCompletionAutoReset) {
                                    if (completionTimeMs == 0L) {
                                        shouldSetCompletionTime = true
                                    }
                                    val currentCompletionTime = if (completionTimeMs == 0L) System.currentTimeMillis() else completionTimeMs
                                    val waitDurationMs = ((totalDurationSec * settings.postCompletionResetTimeFactor) * 1000).toLong().coerceAtLeast(100L)
                                    if (System.currentTimeMillis() - currentCompletionTime >= waitDurationMs) {
                                        triggerReset = true
                                        shouldClearCompletionTime = true
                                    }
                                } else if (settings.drawLengthLooping) {
                                    nextProgress = 0f
                                    shouldClearCompletionTime = true
                                } else {
                                    shouldClearCompletionTime = true
                                }
                            } else {
                                shouldClearCompletionTime = true
                            }
                        }
                    }

                    if (triggerReset) {
                        shouldReset = true
                        val allowedKeys = settings.allowedPresets.split(",").filter { it.isNotEmpty() }
                        var baseSettings = settings

                        if (settings.enablePresetRotation && allowedKeys.isNotEmpty()) {
                            val allPresets = savedPresets.value
                            val valid = allPresets.filter { preset ->
                                val key = if (preset.isUserPreset) "u_${preset.id}" else "f_${preset.name}"
                                allowedKeys.contains(key)
                            }
                            if (valid.isNotEmpty()) {
                                val chosen = valid[random.nextInt(valid.size)]
                                try {
                                    val s = adapter.fromJson(chosen.settingsJson)
                                    if (s != null) {
                                        baseSettings = s.copy(
                                            allowedPresets = settings.allowedPresets,
                                            allowedStyleModes = settings.allowedStyleModes,
                                            allowedPerspectives = settings.allowedPerspectives
                                        )
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }

                        val u = baseSettings.randomizeAll(random).normalize()
                        val nextMaxSteps = if (u.drawSpeedInstant && !u.instantDrawLengthInfinite.current) {
                            u.instantDrawLengthLimit.current.toFloat()
                        } else {
                            (u.drawLengthSteps * u.drawLengthFactor).toFloat()
                        }
                        val randomizedProgress = if (u.drawSpeedInstant) nextMaxSteps else 0f
                        
                        updatedSettings = u
                        finalProgress = randomizedProgress
                        isCurrentlyDrawing = true
                        
                        current.copy(
                            settings = u,
                            drawProgress = randomizedProgress,
                            isDrawing = true
                        )
                    } else {
                        updatedSettings = settings
                        finalProgress = nextProgress
                        isCurrentlyDrawing = isPlay
                        
                        current.copy(
                            settings = settings,
                            drawProgress = nextProgress
                        )
                    }
                }

                if (shouldSetCompletionTime && completionTimeMs == 0L) {
                    completionTimeMs = System.currentTimeMillis()
                }
                if (shouldClearCompletionTime) {
                    completionTimeMs = 0L
                }
                if (shouldReset) {
                    gyroYawOffset.value = 0f
                    gyroPitchOffset.value = 0f
                }

                val now = System.currentTimeMillis()
                // If a reset just happened, save immediately to ensure persistent state.
                // Otherwise, throttle preference writes to once every 500ms to avoid blocking background threads.
                if (shouldReset || now - lastSaveTime > 500L) {
                    lastSaveTime = now
                    updatedSettings?.let {
                        saveSettingsToPrefs(it)
                    }
                    saveProgressAndStateToPrefs(finalProgress, isCurrentlyDrawing)
                }
            }
        }
    }

    private fun saveProgressAndStateToPrefs(progress: Float, isDrawing: Boolean) {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("harmonograph_prefs", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("app_active", false)) {
                prefs.edit()
                    .putFloat("draw_progress", progress)
                    .putBoolean("is_drawing", isDrawing)
                    .apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateSettings(newSettings: HarmonographSettings) {
        val norm = newSettings.normalize()
        _uiState.update { 
            it.copy(settings = norm)
        }
        saveSettingsToPrefs(norm)
    }

    fun setDrawingState(isD: Boolean) {
        _uiState.update { 
            it.copy(isDrawing = isD)
        }
    }

    fun togglePlayback() {
        _uiState.update { 
            it.copy(isDrawing = !it.isDrawing)
        }
    }

    fun jumpToProgress(value: Float) {
        val settings = _uiState.value.settings
        val maxSteps = (settings.drawLengthSteps * settings.drawLengthFactor)
        _uiState.update { 
            it.copy(drawProgress = value.coerceIn(0f, maxSteps))
        }
    }

    /**
     * Resets current progress and randomizes unlocked draw settings
     */
    fun resetAndRandomize() {
        gyroYawOffset.value = 0f
        gyroPitchOffset.value = 0f
        
        val current = _uiState.value.settings
        val allowedKeys = current.allowedPresets.split(",").filter { it.isNotEmpty() }
        var baseSettings = current

        if (current.enablePresetRotation && allowedKeys.isNotEmpty()) {
            val allPresets = savedPresets.value
            val valid = allPresets.filter { preset ->
                val key = if (preset.isUserPreset) "u_${preset.id}" else "f_${preset.name}"
                allowedKeys.contains(key)
            }
            if (valid.isNotEmpty()) {
                val chosen = valid[random.nextInt(valid.size)]
                try {
                    val s = adapter.fromJson(chosen.settingsJson)
                    if (s != null) {
                        baseSettings = s.copy(
                            allowedPresets = current.allowedPresets,
                            allowedStyleModes = current.allowedStyleModes,
                            allowedPerspectives = current.allowedPerspectives
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val u = baseSettings.randomizeAll(random).normalize()
        val nextMaxSteps = if (u.drawSpeedInstant && !u.instantDrawLengthInfinite.current) {
            u.instantDrawLengthLimit.current.toFloat()
        } else {
            (u.drawLengthSteps * u.drawLengthFactor).toFloat()
        }
        val nextProgress = if (u.drawSpeedInstant) nextMaxSteps else 0f
        
        _uiState.update { 
            it.copy(
                settings = u,
                drawProgress = nextProgress,
                isDrawing = true
            )
        }
        saveSettingsToPrefs(u)
    }

    private fun saveSettingsToPrefs(settings: HarmonographSettings) {
        try {
            val json = adapter.toJson(settings)
            getApplication<Application>().getSharedPreferences("harmonograph_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("active_settings", json)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Fresh drawing restart
     */
    fun restartDrawing() {
        gyroYawOffset.value = 0f
        gyroPitchOffset.value = 0f
        _uiState.update { 
            it.copy(
                isDrawing = true,
                drawProgress = 0f
            )
        }
    }

    /**
     * Save custom preset to database
     */
    fun savePreset(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = adapter.toJson(_uiState.value.settings) ?: ""
            dao.insertPreset(
                HarmonographPreset(
                    name = name,
                    isUserPreset = true,
                    settingsJson = json
                )
            )
        }
    }

    /**
     * Locks all lockable settings and saves as a snapshot preset
     */
    fun saveSnapshotPreset(customName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshotSettings = _uiState.value.settings.toLockedSnapshotSettings()
            val json = adapter.toJson(snapshotSettings) ?: ""
            val displayName = if (customName.isNotBlank()) customName else "Snapshot #${System.currentTimeMillis() % 10000}"
            dao.insertPreset(
                HarmonographPreset(
                    name = displayName,
                    isUserPreset = true,
                    settingsJson = json
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    /**
     * Load preset into active state
     */
    fun loadPreset(preset: HarmonographPreset) {
        try {
            val settings = adapter.fromJson(preset.settingsJson)
            if (settings != null) {
                // Randomize unlocked parameter values to generate a new variation
                // based on the preset's structural settings and lock constraints
                gyroYawOffset.value = 0f
                gyroPitchOffset.value = 0f
                val randomizedSettings = settings.randomizeAll(random).normalize()
                _uiState.update { 
                    it.copy(
                        settings = randomizedSettings,
                        drawProgress = 0f
                    )
                }
                saveSettingsToPrefs(randomizedSettings)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Delete user preset
     */
    fun deletePreset(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deletePresetById(id)
        }
    }

    /**
     * Rename user preset
     */
    fun renamePreset(id: Int, newName: String, settingsJson: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insertPreset(
                HarmonographPreset(
                    id = id,
                    name = newName,
                    isUserPreset = true,
                    settingsJson = settingsJson
                )
            )
        }
    }

    private suspend fun insertDefaultPresets() {
        val presets = listOf(
            HarmonographPreset(
                name = "Default Settings",
                isUserPreset = false,
                settingsJson = adapter.toJson(HarmonographSettings()) ?: ""
            ),
            HarmonographPreset(
                name = "Classic Spirograph",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(140f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(140f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(0f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(2.0f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(3.0f, rangeMin = 0.1f, rangeMax = 12f),
                        xyzFreqMultiplier = FloatParameter(1.0f, rangeMin = 0.5f, rangeMax = 18.0f, locked = true, selectedMin = 0.5f, selectedMax = 18.0f),
                        decayX = FloatParameter(0.0004f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayY = FloatParameter(0.0004f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayEnabled = BooleanParameter(true, locked = true),
                        lineThickness = FloatParameter(2.5f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.9f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        styleMode = "length",
                        penTipShape = "circle",
                        penTipSize = 7f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "3D Galactic Swirl",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(150f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(150f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(130f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(1.5f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(2.25f, rangeMin = 0.1f, rangeMax = 12f),
                        freqZ = FloatParameter(3.0f, rangeMin = 0.1f, rangeMax = 12f),
                        xyzFreqMultiplier = FloatParameter(1.2f, rangeMin = 0.5f, rangeMax = 18.0f, locked = true, selectedMin = 0.5f, selectedMax = 18.0f),
                        decayX = FloatParameter(0.001f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayY = FloatParameter(0.001f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayZ = FloatParameter(0.001f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayEnabled = BooleanParameter(true, locked = true),
                        lineThickness = FloatParameter(3.0f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.85f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        styleMode = "rainbow",
                        cameraPerspective = 1,
                        cameraAutoRotationEnabled = true,
                        penCount = IntParameter(2, rangeMin = 1, rangeMax = 3),
                        penOffset = FloatParameter(10f, rangeMin = 2f, rangeMax = 30f),
                        penTipShape = "diamond",
                        penTipSize = 8f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Decaying Orbits",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(160f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(160f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(80f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(1.0f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(1.5f, rangeMin = 0.1f, rangeMax = 12f),
                        xyzFreqMultiplier = FloatParameter(1.5f, rangeMin = 0.5f, rangeMax = 18.0f, locked = true, selectedMin = 0.5f, selectedMax = 18.0f),
                        decayX = FloatParameter(0.0035f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayY = FloatParameter(0.0035f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayEnabled = BooleanParameter(true, locked = true),
                        ampSubX = FloatParameter(35f, rangeMin = 0f, rangeMax = 80f),
                        lineThickness = FloatParameter(4.0f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.9f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        styleMode = "center",
                        penTipShape = "diamond",
                        penTipSize = 9f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Hyper Pen Fusion",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(130f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(130f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(90f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(2.0f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(4.0f, rangeMin = 0.1f, rangeMax = 12f),
                        xyzFreqMultiplier = FloatParameter(1.0f, rangeMin = 0.5f, rangeMax = 18.0f, locked = true, selectedMin = 0.5f, selectedMax = 18.0f),
                        penCount = IntParameter(3, rangeMin = 1, rangeMax = 3),
                        penOffset = FloatParameter(18f, rangeMin = 2f, rangeMax = 30f),
                        penRotationEnabled = BooleanParameter(true),
                        penRotationMultiplier = IntParameter(5, rangeMin = 1, rangeMax = 8),
                        styleMode = "rainbow",
                        liveChromaticShiftEnabled = BooleanParameter(true),
                        chromaticShiftSpeed = FloatParameter(0.8f, rangeMin = 0.0f, rangeMax = 1.0f),
                        lineThickness = FloatParameter(1.8f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.95f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "star",
                        penTipSize = 10f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Roller coaster Pen Ride",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(130f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(130f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(95f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(1.33f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(2.0f, rangeMin = 0.1f, rangeMax = 12f),
                        freqZ = FloatParameter(3.0f, rangeMin = 0.1f, rangeMax = 12f),
                        xyzFreqMultiplier = FloatParameter(1.0f, rangeMin = 0.5f, rangeMax = 18.0f, locked = true, selectedMin = 0.5f, selectedMax = 18.0f),
                        styleMode = "rainbow",
                        cameraPerspective = 2,
                        cameraAutoRotationEnabled = false,
                        coasterDirectionFacing = true,
                        coasterDeviationAngle = FloatParameter(25f, rangeMin = 10f, rangeMax = 45f),
                        coasterOrbitSpeed = FloatParameter(0.5f, rangeMin = 0.05f, rangeMax = 1.0f),
                        lineThickness = FloatParameter(2.5f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.88f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "circle",
                        penTipSize = 8f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Chroma Center Spiral",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(120f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(120f, rangeMin = 10f, rangeMax = 250f),
                        styleMode = "center",
                        penCount = IntParameter(2, rangeMin = 1, rangeMax = 3, locked = true),
                        gradientStartHue = FloatParameter(200f, rangeMin = 0f, rangeMax = 360f, locked = true),
                        gradientEndHue = FloatParameter(320f, rangeMin = 0f, rangeMax = 360f, locked = true),
                        penOffset = FloatParameter(14f, rangeMin = 2f, rangeMax = 30f, rangeLocked = true, selectedMin = 10f, selectedMax = 20f),
                        periodicShape = "circle",
                        periodicShapeSize = FloatParameter(8f, rangeMin = 2f, rangeMax = 18f),
                        periodicShapeConcentric = 2,
                        lineThickness = FloatParameter(3.5f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.85f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "square",
                        penTipSize = 10f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Quantum Orbit Wave",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        styleMode = "rainbow",
                        cameraPerspective = 2,
                        cameraAutoRotationEnabled = false,
                        coasterDirectionFacing = true,
                        coasterDeviationAngle = FloatParameter(25f, rangeMin = 10f, rangeMax = 45f, locked = true),
                        coasterOrbitSpeed = FloatParameter(0.7f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penCount = IntParameter(3, rangeMin = 1, rangeMax = 3, locked = true),
                        penRotationEnabled = BooleanParameter(true, locked = true),
                        liveChromaticShiftEnabled = BooleanParameter(true),
                        lineThickness = FloatParameter(1.5f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.9f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "diamond",
                        penTipSize = 9f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Cosmic Star Weaver",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        periodicShape = "star",
                        periodicShapeSize = FloatParameter(10f, rangeMin = 2f, rangeMax = 18f, locked = true),
                        periodicShapeFreqFactor = IntParameter(5, rangeMin = 1, rangeMax = 8, locked = true),
                        periodicShapeConcentric = 2,
                        periodicShapeDeployment = "progressive",
                        styleMode = "spicy",
                        spicyHue = FloatParameter(280f, rangeMin = 0f, rangeMax = 360f),
                        spicyColorRange = FloatParameter(180f, rangeMin = 0f, rangeMax = 360f),
                        penCount = IntParameter(2, rangeMin = 1, rangeMax = 3, locked = true),
                        lineThickness = FloatParameter(2.2f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.92f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "star",
                        penTipSize = 10f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Spicy Neon Swarm",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        styleMode = "spicy",
                        spicyHue = FloatParameter(120f, rangeMin = 0f, rangeMax = 360f, locked = true),
                        spicyColorRange = FloatParameter(180f, rangeMin = 0f, rangeMax = 360f, locked = true),
                        penCount = IntParameter(2, rangeMin = 1, rangeMax = 3, locked = true),
                        penOffset = FloatParameter(12f, rangeMin = 2f, rangeMax = 30f, locked = true),
                        gyroEnabled = true,
                        lineThickness = FloatParameter(3.2f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.85f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "cross",
                        penTipSize = 8f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Gyroscopic Starburst",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        periodicShape = "star",
                        periodicShapeSize = FloatParameter(10f, rangeMin = 2f, rangeMax = 18f, locked = true),
                        gyroEnabled = true,
                        rationalFrequenciesEnabled = BooleanParameter(true),
                        penCount = IntParameter(1, rangeMin = 1, rangeMax = 3, locked = true),
                        dynamicCameraZoomEnabled = true,
                        lineThickness = FloatParameter(2.8f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.9f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "star",
                        penTipSize = 9f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Infinite Chroma Loop",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        styleMode = "rainbow",
                        rainbowHue = FloatParameter(90f, rangeMin = 0f, rangeMax = 360f, locked = true),
                        rainbowColorRange = FloatParameter(270f, rangeMin = 0f, rangeMax = 360f, locked = true),
                        penCount = IntParameter(3, rangeMin = 1, rangeMax = 3, locked = true),
                        penRotationEnabled = BooleanParameter(true, locked = true),
                        penRotationMultiplier = IntParameter(6, rangeMin = 1, rangeMax = 8, locked = true),
                        liveChromaticShiftEnabled = BooleanParameter(true),
                        chromaticShiftSpeed = FloatParameter(0.5f, rangeMin = 0.0f, rangeMax = 1.0f),
                        lineThickness = FloatParameter(2.0f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.93f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "diamond",
                        penTipSize = 8f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Quantum Mono Wave",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(140f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(140f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(100f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(1.5f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(2.5f, rangeMin = 0.1f, rangeMax = 12f),
                        styleMode = "solid", 
                        solidColorHue = FloatParameter(200f, rangeMin = 0f, rangeMax = 360f),
                        monoScaleEnabled = BooleanParameter(true),
                        monoScaleLiveShiftEnabled = BooleanParameter(true),
                        monoScaleLiveShiftSpeed = FloatParameter(0.4f, rangeMin = 0.05f, rangeMax = 1.0f),
                        monoWaveEffectiveRange = FloatParameter(0.0f, rangeMin = -1.0f, rangeMax = 1.0f, locked = true, rangeLocked = true, selectedMin = -0.8f, selectedMax = 0.8f),
                        lineThickness = FloatParameter(3.8f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.85f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "circle",
                        penTipSize = 8f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Zen Lotus Bloom",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        styleMode = "spicy",
                        spicyHue = FloatParameter(10f, rangeMin = 0f, rangeMax = 360f, locked = true),
                        spicyColorRange = FloatParameter(80f, rangeMin = 0f, rangeMax = 360f, locked = true),
                        periodicShape = "triangle",
                        periodicShapeSize = FloatParameter(12f, rangeMin = 2f, rangeMax = 18f, locked = true),
                        periodicShapeConcentric = 3,
                        periodicShapeDeployment = "progressive",
                        periodicProgressiveDelay = FloatParameter(0.5f, rangeMin = 0.25f, rangeMax = 1.5f, locked = true),
                        penCount = IntParameter(2, rangeMin = 1, rangeMax = 3, locked = true),
                        penOffset = FloatParameter(15f, rangeMin = 2f, rangeMax = 30f, locked = true),
                        ampX = FloatParameter(140f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(140f, rangeMin = 10f, rangeMax = 250f),
                        freqX = FloatParameter(2.5f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(3.75f, rangeMin = 0.1f, rangeMax = 12f),
                        lineThickness = FloatParameter(2.4f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.9f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "diamond",
                        penTipSize = 9f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Psychedelic Vortex",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        styleMode = "rainbow",
                        periodicShape = "star",
                        periodicShapeSize = FloatParameter(14f, rangeMin = 2f, rangeMax = 18f, locked = true),
                        periodicShapeConcentric = 2,
                        periodicShapeDeployment = "progressive",
                        penCount = IntParameter(3, rangeMin = 1, rangeMax = 3, locked = true),
                        penRotationEnabled = BooleanParameter(true, locked = true),
                        penRotationMultiplier = IntParameter(4, rangeMin = 1, rangeMax = 8, locked = true),
                        penOffset = FloatParameter(16f, rangeMin = 2f, rangeMax = 30f, locked = true),
                        freqX = FloatParameter(3.0f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(4.5f, rangeMin = 0.1f, rangeMax = 12f),
                        lineThickness = FloatParameter(1.6f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.95f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "star",
                        penTipSize = 10f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Quantum Hyper-Vessel",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(145f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(145f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(110f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(2.0f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(3.0f, rangeMin = 0.1f, rangeMax = 12f),
                        freqZ = FloatParameter(4.0f, rangeMin = 0.1f, rangeMax = 12f),
                        xyzFreqMultiplier = FloatParameter(1.33f, rangeMin = 0.5f, rangeMax = 18.0f, locked = true, selectedMin = 0.5f, selectedMax = 18.0f),
                        decayX = FloatParameter(0.0008f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayY = FloatParameter(0.0008f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayZ = FloatParameter(0.0008f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayEnabled = BooleanParameter(true, locked = true),
                        styleMode = "rainbow",
                        periodicShape = "circle",
                        periodicShapeSize = FloatParameter(10f, rangeMin = 2f, rangeMax = 18f),
                        periodicShapeConcentric = 3,
                        periodicShapeDeployment = "progressive",
                        periodicProgressiveDelay = FloatParameter(0.4f, rangeMin = 0.25f, rangeMax = 1.5f),
                        penCount = IntParameter(2, rangeMin = 1, rangeMax = 3),
                        penOffset = FloatParameter(12f, rangeMin = 2f, rangeMax = 30f),
                        liveChromaticShiftEnabled = BooleanParameter(true),
                        chromaticShiftSpeed = FloatParameter(0.7f, rangeMin = 0.00f, rangeMax = 1.00f),
                        lineThickness = FloatParameter(2.6f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.85f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "circle",
                        penTipSize = 8f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Stardust Constellation",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(150f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(150f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(0f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(3.0f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(5.0f, rangeMin = 0.1f, rangeMax = 12f),
                        xyzFreqMultiplier = FloatParameter(1.0f, rangeMin = 0.5f, rangeMax = 18.0f, locked = true, selectedMin = 0.5f, selectedMax = 18.0f),
                        rationalFrequenciesEnabled = BooleanParameter(true),
                        styleMode = "spicy",
                        spicyHue = FloatParameter(290f, rangeMin = 0f, rangeMax = 360f),
                        spicyColorRange = FloatParameter(90f, rangeMin = 0f, rangeMax = 360f),
                        periodicShape = "star",
                        periodicShapeSize = FloatParameter(8f, rangeMin = 2f, rangeMax = 18f),
                        periodicShapeConcentric = 2,
                        periodicShapeFreqFactor = IntParameter(4, rangeMin = 1, rangeMax = 8),
                        penCount = IntParameter(1, rangeMin = 1, rangeMax = 3),
                        lineThickness = FloatParameter(1.8f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.9f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "star",
                        penTipSize = 10f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Cosmic Kaleidoscope",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(125f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(125f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(80f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(2.5f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(3.75f, rangeMin = 0.1f, rangeMax = 12f),
                        freqZ = FloatParameter(1.25f, rangeMin = 0.1f, rangeMax = 12f),
                        xyzFreqMultiplier = FloatParameter(1.5f, rangeMin = 0.5f, rangeMax = 18.0f, locked = true, selectedMin = 0.5f, selectedMax = 18.0f),
                        styleMode = "rainbow",
                        periodicShape = "triangle",
                        periodicShapeSize = FloatParameter(10f, rangeMin = 2f, rangeMax = 18f),
                        periodicShapeConcentric = 3,
                        penCount = IntParameter(3, rangeMin = 1, rangeMax = 3),
                        penOffset = FloatParameter(15f, rangeMin = 2f, rangeMax = 30f),
                        penRotationEnabled = BooleanParameter(true),
                        penRotationMultiplier = IntParameter(3, rangeMin = 1, rangeMax = 8),
                        monoScaleEnabled = BooleanParameter(true),
                        monoScaleLiveShiftEnabled = BooleanParameter(true),
                        monoScaleLiveShiftSpeed = FloatParameter(0.5f, rangeMin = 0.05f, rangeMax = 1.0f),
                        lineThickness = FloatParameter(2.5f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.85f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "diamond",
                        penTipSize = 9f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Cyber Fusion Core",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(135f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(135f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(115f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(1.5f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(2.25f, rangeMin = 0.1f, rangeMax = 12f),
                        xyzFreqMultiplier = FloatParameter(2.0f, rangeMin = 0.5f, rangeMax = 18.0f, locked = true, selectedMin = 0.5f, selectedMax = 18.0f),
                        styleMode = "rainbow",
                        cameraPerspective = 2,
                        coasterOrbitSpeed = FloatParameter(0.8f, rangeMin = 0.05f, rangeMax = 1.0f),
                        penCount = IntParameter(2, rangeMin = 1, rangeMax = 3),
                        penOffset = FloatParameter(12f, rangeMin = 2f, rangeMax = 30f),
                        liveChromaticShiftEnabled = BooleanParameter(true),
                        chromaticShiftSpeed = FloatParameter(0.9f, rangeMin = 0.00f, rangeMax = 1.00f),
                        lineThickness = FloatParameter(3.0f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.88f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "square",
                        penTipSize = 8f
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Aurora Borealis",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        ampX = FloatParameter(140f, rangeMin = 10f, rangeMax = 250f),
                        ampY = FloatParameter(140f, rangeMin = 10f, rangeMax = 250f),
                        ampZ = FloatParameter(60f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(1.0f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(1.33f, rangeMin = 0.1f, rangeMax = 12f),
                        xyzFreqMultiplier = FloatParameter(1.2f, rangeMin = 0.5f, rangeMax = 18.0f, locked = true, selectedMin = 0.5f, selectedMax = 18.0f),
                        decayX = FloatParameter(0.0003f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayY = FloatParameter(0.0003f, rangeMin = 0.0001f, rangeMax = 0.02f),
                        decayEnabled = BooleanParameter(true, locked = true),
                        styleMode = "solid",
                        solidColorHue = FloatParameter(145f, rangeMin = 0f, rangeMax = 360f), // Soft emerald glow
                        gyroEnabled = true,
                        gyroSensitivity = FloatParameter(0.4f, rangeMin = 0.1f, rangeMax = 2.0f, locked = true),
                        lineThickness = FloatParameter(4.5f, rangeMin = 0.5f, rangeMax = 12f, rangeLocked = true, selectedMin = 1.0f, selectedMax = 5.0f),
                        lineAlpha = FloatParameter(0.5f, rangeMin = 0.05f, rangeMax = 1.0f, locked = true),
                        penTipShape = "circle",
                        penTipSize = 7f
                    )
                ) ?: ""
            )
        )
        for (p in presets) {
            dao.insertPreset(p)
        }
    }
}

object DatabaseProvider {
    private var instance: AppDatabase? = null
    fun getDatabase(context: android.content.Context): AppDatabase {
        return instance ?: synchronized(this) {
            val db = androidx.room.Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "harmonograph_db"
            ).fallbackToDestructiveMigration(true).build()
            instance = db
            db
        }
    }
}

data class HarmonographUiState(
    val settings: HarmonographSettings = HarmonographSettings().normalize(),
    val drawProgress: Float = 0f,
    val isDrawing: Boolean = true
)
