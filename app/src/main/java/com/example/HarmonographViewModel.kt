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

    private val _uiState = MutableStateFlow(HarmonographSettings())
    val uiState: StateFlow<HarmonographSettings> = _uiState.asStateFlow()

    private val _currentDrawProgress = MutableStateFlow(0f)
    val currentDrawProgress: StateFlow<Float> = _currentDrawProgress.asStateFlow()

    private val _isDrawing = MutableStateFlow(true)
    val isDrawing: StateFlow<Boolean> = _isDrawing.asStateFlow()

    val savedPresets: StateFlow<List<HarmonographPreset>> = dao.getAllPresets()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var drawingJob: Job? = null
    private val random = Random()

    init {
        // Load active settings from prefs on VM creation to ensure all settings persist and translate.
        val prefs = application.getSharedPreferences("harmonograph_prefs", android.content.Context.MODE_PRIVATE)
        val savedJson = prefs.getString("active_settings", null)
        if (savedJson != null) {
            try {
                val loaded = adapter.fromJson(savedJson)
                if (loaded != null) {
                    _uiState.value = loaded
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
        drawingJob?.cancel()
        drawingJob = viewModelScope.launch(Dispatchers.Default) {
            var completionTimeMs = 0L
            while (true) {
                delay(16) // ~60fps ticker loop
                
                val settings = _uiState.value
                val isPlay = _isDrawing.value
                val progress = _currentDrawProgress.value
                val maxSteps = (settings.drawLengthSteps * settings.drawLengthFactor)

                if (isPlay) {
                    if (settings.drawSpeedInstant) {
                        _currentDrawProgress.value = maxSteps
                        if (settings.postCompletionAutoReset) {
                            if (completionTimeMs == 0L) {
                                completionTimeMs = System.currentTimeMillis()
                            } else {
                                val elapsed = System.currentTimeMillis() - completionTimeMs
                                if (elapsed >= 150_000L) { // 2.5 minutes (150 seconds)
                                    resetAndRandomize()
                                    completionTimeMs = 0L
                                }
                            }
                        } else {
                            completionTimeMs = 0L
                        }
                    } else {
                        completionTimeMs = 0L
                        // Compute step increment per frame (~16ms)
                        val totalDurationSec = settings.drawSpeedMinutes.current * 60f
                        val stepsPerSec = maxSteps / totalDurationSec
                        val stepsPerFrame = stepsPerSec * 0.016f
                        
                        val sumProgress = progress + stepsPerFrame
                        val newProgress = if (sumProgress > maxSteps) maxSteps else sumProgress
                        _currentDrawProgress.value = newProgress
                        
                        // Handle auto reset when draw finishes
                        if (newProgress >= maxSteps && settings.postCompletionAutoReset) {
                            val waitSec = totalDurationSec * settings.postCompletionResetTimeFactor
                            delay((waitSec * 1000).toLong().coerceAtLeast(100L))
                            resetAndRandomize()
                        }
                    }
                } else {
                    completionTimeMs = 0L
                }
            }
        }
    }

    fun updateSettings(newSettings: HarmonographSettings) {
        _uiState.value = newSettings
        saveSettingsToPrefs(newSettings)
    }

    fun togglePlayback() {
        _isDrawing.update { !it }
    }

    fun jumpToProgress(value: Float) {
        val settings = _uiState.value
        val maxSteps = (settings.drawLengthSteps * settings.drawLengthFactor)
        _currentDrawProgress.value = value.coerceIn(0f, maxSteps)
    }

    /**
     * Resets current progress and randomizes unlocked draw settings
     */
    fun resetAndRandomize() {
        var updated: HarmonographSettings? = null
        gyroYawOffset.value = 0f
        gyroPitchOffset.value = 0f
        _uiState.update { current ->
            val u = current.randomizeAll(random)
            updated = u
            u
        }
        _currentDrawProgress.value = 0f
        updated?.let { saveSettingsToPrefs(it) }
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
        _currentDrawProgress.value = 0f
    }

    /**
     * Save custom preset to database
     */
    fun savePreset(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val json = adapter.toJson(_uiState.value) ?: ""
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
                val randomizedSettings = settings.randomizeAll(random)
                _uiState.value = randomizedSettings
                _currentDrawProgress.value = 0f
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

    private suspend fun insertDefaultPresets() {
        val presets = listOf(
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
                        decayX = FloatParameter(0.0005f, rangeMin = 0f, rangeMax = 0.01f),
                        decayY = FloatParameter(0.0005f, rangeMin = 0f, rangeMax = 0.01f),
                        styleMode = "length"
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
                        ampZ = FloatParameter(120f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(1.5f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(2.25f, rangeMin = 0.1f, rangeMax = 12f),
                        freqZ = FloatParameter(3.0f, rangeMin = 0.1f, rangeMax = 12f),
                        decayX = FloatParameter(0.001f, rangeMin = 0f, rangeMax = 0.01f),
                        decayY = FloatParameter(0.001f, rangeMin = 0f, rangeMax = 0.01f),
                        decayZ = FloatParameter(0.001f, rangeMin = 0f, rangeMax = 0.01f),
                        styleMode = "rainbow",
                        cameraPerspective = 1,
                        cameraAutoRotationEnabled = true
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
                        decayX = FloatParameter(0.004f, rangeMin = 0f, rangeMax = 0.01f),
                        decayY = FloatParameter(0.004f, rangeMin = 0f, rangeMax = 0.01f),
                        ampSubX = FloatParameter(30f, rangeMin = 0f, rangeMax = 80f),
                        styleMode = "center"
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
                        penCount = IntParameter(3, rangeMin = 1, rangeMax = 3),
                        penOffset = FloatParameter(18f, rangeMin = 2f, rangeMax = 30f),
                        penRotationEnabled = BooleanParameter(true),
                        penRotationMultiplier = IntParameter(5, rangeMin = 1, rangeMax = 8),
                        styleMode = "rainbow"
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
                        ampZ = FloatParameter(90f, rangeMin = 0f, rangeMax = 250f),
                        freqX = FloatParameter(1.33f, rangeMin = 0.1f, rangeMax = 12f),
                        freqY = FloatParameter(2.0f, rangeMin = 0.1f, rangeMax = 12f),
                        freqZ = FloatParameter(3.0f, rangeMin = 0.1f, rangeMax = 12f),
                        styleMode = "rainbow",
                        cameraPerspective = 2,
                        cameraAutoRotationEnabled = false,
                        coasterDirectionFacing = true,
                        coasterDeviationAngle = FloatParameter(25f, rangeMin = 10f, rangeMax = 45f),
                        coasterOrbitSpeed = FloatParameter(1.2f, rangeMin = 0.2f, rangeMax = 5.0f)
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Chroma Center Spiral",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        styleMode = "center",
                        penCount = IntParameter(2, rangeMin = 1, rangeMax = 3, locked = true),
                        gradientStartHue = FloatParameter(200f, rangeMin = 0f, rangeMax = 360f, locked = true),
                        gradientEndHue = FloatParameter(320f, rangeMin = 0f, rangeMax = 360f, locked = true),
                        penOffset = FloatParameter(14f, rangeMin = 2f, rangeMax = 30f, rangeLocked = true, selectedMin = 10f, selectedMax = 20f)
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
                        coasterOrbitSpeed = FloatParameter(1.8f, rangeMin = 0.2f, rangeMax = 5.0f, locked = true),
                        penCount = IntParameter(3, rangeMin = 1, rangeMax = 3, locked = true),
                        penRotationEnabled = BooleanParameter(true, locked = true)
                    )
                ) ?: ""
            ),
            HarmonographPreset(
                name = "Cosmic Star Weaver",
                isUserPreset = false,
                settingsJson = adapter.toJson(
                    HarmonographSettings(
                        periodicShape = "star",
                        periodicShapeSize = FloatParameter(8f, rangeMin = 2f, rangeMax = 18f, locked = true),
                        periodicShapeFreqFactor = IntParameter(4, rangeMin = 1, rangeMax = 8, locked = true),
                        styleMode = "rainbow",
                        penCount = IntParameter(2, rangeMin = 1, rangeMax = 3, locked = true)
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
                        penOffset = FloatParameter(12f, rangeMin = 2f, rangeMax = 30f, locked = true)
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
                        rationalFrequenciesEnabled = true,
                        penCount = IntParameter(1, rangeMin = 1, rangeMax = 3, locked = true)
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
                        penRotationMultiplier = IntParameter(6, rangeMin = 1, rangeMax = 8, locked = true)
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
            ).fallbackToDestructiveMigration().build()
            instance = db
            db
        }
    }
}
