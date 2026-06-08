package com.example

import androidx.room.*
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.flow.Flow

@JsonClass(generateAdapter = true)
data class FloatParameter(
    val current: Float,
    val locked: Boolean = false,
    val rangeLocked: Boolean = false,
    val rangeMin: Float,
    val rangeMax: Float,
    val selectedMin: Float = -1e9f,
    val selectedMax: Float = 1e9f,
    val enabled: Boolean = true,
    val enabledLocked: Boolean = false
) {
    val actualSelectedMin: Float
        get() {
            val rawMin = if (selectedMin == -1e9f) rangeMin else selectedMin
            val rawMax = if (selectedMax == 1e9f) rangeMax else selectedMax
            return minOf(rawMin, rawMax)
        }
    val actualSelectedMax: Float
        get() {
            val rawMin = if (selectedMin == -1e9f) rangeMin else selectedMin
            val rawMax = if (selectedMax == 1e9f) rangeMax else selectedMax
            return maxOf(rawMin, rawMax)
        }

    fun withValue(v: Float): FloatParameter {
        val valMin = if (rangeLocked) actualSelectedMin else rangeMin
        val valMax = if (rangeLocked) actualSelectedMax else rangeMax
        val safeMin = minOf(valMin, valMax)
        val safeMax = maxOf(valMin, valMax)
        return copy(current = v.coerceIn(safeMin, safeMax))
    }

    fun setValueCompat(v: Float): FloatParameter {
        return copy(current = v.coerceIn(rangeMin, rangeMax))
    }

    fun withRanges(min: Float, max: Float): FloatParameter {
        val minCoerced = min.coerceIn(rangeMin, rangeMax)
        val maxCoerced = max.coerceIn(rangeMin, rangeMax)
        val safeMin = minOf(minCoerced, maxCoerced)
        val safeMax = maxOf(minCoerced, maxCoerced)
        val newCurrent = if (rangeLocked) current.coerceIn(safeMin, safeMax) else current
        return copy(
            selectedMin = minCoerced,
            selectedMax = maxCoerced,
            current = newCurrent
        )
    }

    fun withRangeLocked(locked: Boolean): FloatParameter {
        val newCurrent = if (locked) current.coerceIn(actualSelectedMin, actualSelectedMax) else current
        return copy(rangeLocked = locked, current = newCurrent)
    }

    fun randomize(random: java.util.Random): FloatParameter {
        if (locked) return this
        val valMin = if (rangeLocked) actualSelectedMin else rangeMin
        val valMax = if (rangeLocked) actualSelectedMax else rangeMax
        if (valMax <= valMin) return copy(current = valMin)
        val v = valMin + random.nextFloat() * (valMax - valMin)
        return copy(current = v)
    }
}

@JsonClass(generateAdapter = true)
data class IntParameter(
    val current: Int,
    val locked: Boolean = false,
    val rangeLocked: Boolean = false,
    val rangeMin: Int,
    val rangeMax: Int,
    val selectedMin: Int = Int.MIN_VALUE,
    val selectedMax: Int = Int.MAX_VALUE,
    val enabled: Boolean = true
) {
    val actualSelectedMin: Int
        get() {
            val rawMin = if (selectedMin == Int.MIN_VALUE) rangeMin else selectedMin
            val rawMax = if (selectedMax == Int.MAX_VALUE) rangeMax else selectedMax
            return minOf(rawMin, rawMax)
        }
    val actualSelectedMax: Int
        get() {
            val rawMin = if (selectedMin == Int.MIN_VALUE) rangeMin else selectedMin
            val rawMax = if (selectedMax == Int.MAX_VALUE) rangeMax else selectedMax
            return maxOf(rawMin, rawMax)
        }

    fun withValue(v: Int): IntParameter {
        val valMin = if (rangeLocked) actualSelectedMin else rangeMin
        val valMax = if (rangeLocked) actualSelectedMax else rangeMax
        val safeMin = minOf(valMin, valMax)
        val safeMax = maxOf(valMin, valMax)
        return copy(current = v.coerceIn(safeMin, safeMax))
    }

    fun withRanges(min: Int, max: Int): IntParameter {
        val minCoerced = min.coerceIn(rangeMin, rangeMax)
        val maxCoerced = max.coerceIn(rangeMin, rangeMax)
        val safeMin = minOf(minCoerced, maxCoerced)
        val safeMax = maxOf(minCoerced, maxCoerced)
        val newCurrent = if (rangeLocked) current.coerceIn(safeMin, safeMax) else current
        return copy(
            selectedMin = minCoerced,
            selectedMax = maxCoerced,
            current = newCurrent
        )
    }

    fun withRangeLocked(locked: Boolean): IntParameter {
        val newCurrent = if (locked) current.coerceIn(actualSelectedMin, actualSelectedMax) else current
        return copy(rangeLocked = locked, current = newCurrent)
    }

    fun randomize(random: java.util.Random): IntParameter {
        if (locked) return this
        val valMin = if (rangeLocked) actualSelectedMin else rangeMin
        val valMax = if (rangeLocked) actualSelectedMax else rangeMax
        if (valMax <= valMin) return copy(current = valMin)
        val v = random.nextInt(valMax - valMin + 1) + valMin
        return copy(current = v)
    }
}

@JsonClass(generateAdapter = true)
data class BooleanParameter(
    val current: Boolean,
    val locked: Boolean = false
) {
    fun withValue(value: Boolean): BooleanParameter = copy(current = value)
    fun withLocked(lockedVal: Boolean): BooleanParameter = copy(locked = lockedVal)
    fun randomize(random: java.util.Random): BooleanParameter {
        if (locked) return this
        return copy(current = random.nextBoolean())
    }
}

@JsonClass(generateAdapter = true)
data class HarmonographSettings(
    val ampX: FloatParameter = FloatParameter(120f, rangeMin = 10f, rangeMax = 250f),
    val ampY: FloatParameter = FloatParameter(120f, rangeMin = 10f, rangeMax = 250f),
    val ampZ: FloatParameter = FloatParameter(80f, rangeMin = 0f, rangeMax = 250f),
    
    val freqX: FloatParameter = FloatParameter(1.001f, rangeMin = 1f/12f, rangeMax = 12f),
    val freqY: FloatParameter = FloatParameter(1.503f, rangeMin = 1f/12f, rangeMax = 12f),
    val freqZ: FloatParameter = FloatParameter(2.002f, rangeMin = 1f/12f, rangeMax = 12f),
    
    val decayX: FloatParameter = FloatParameter(0.0015f, rangeMin = 0.0001f, rangeMax = 0.01f),
    val decayY: FloatParameter = FloatParameter(0.0015f, rangeMin = 0.0001f, rangeMax = 0.01f),
    val decayZ: FloatParameter = FloatParameter(0.0015f, rangeMin = 0.0001f, rangeMax = 0.01f),
    
    val phaseX: FloatParameter = FloatParameter(0f, rangeMin = 0f, rangeMax = 360f),
    val phaseY: FloatParameter = FloatParameter(90f, rangeMin = 0f, rangeMax = 360f),
    val phaseZ: FloatParameter = FloatParameter(45f, rangeMin = 0f, rangeMax = 360f),
    
    // Sublayers X' Y' Z' (ampSubZ defaults to 0 to easily showcase clean default curves)
    val ampSubX: FloatParameter = FloatParameter(0f, rangeMin = 0f, rangeMax = 80f),
    val ampSubY: FloatParameter = FloatParameter(0f, rangeMin = 0f, rangeMax = 80f),
    val ampSubZ: FloatParameter = FloatParameter(0f, rangeMin = 0f, rangeMax = 80f),
    
    val phaseSubX: FloatParameter = FloatParameter(45f, rangeMin = 0f, rangeMax = 360f),
    val phaseSubY: FloatParameter = FloatParameter(45f, rangeMin = 0f, rangeMax = 360f),
    val phaseSubZ: FloatParameter = FloatParameter(45f, rangeMin = 0f, rangeMax = 360f),
    
    val subXFreqFactor: IntParameter = IntParameter(2, rangeMin = 1, rangeMax = 8),
    val subXFreqIsMultiply: BooleanParameter = BooleanParameter(true),
    val subYFreqFactor: IntParameter = IntParameter(3, rangeMin = 1, rangeMax = 8),
    val subYFreqIsMultiply: BooleanParameter = BooleanParameter(true),
    val subZFreqFactor: IntParameter = IntParameter(4, rangeMin = 1, rangeMax = 8),
    val subZFreqIsMultiply: BooleanParameter = BooleanParameter(true),
    
    val drawSpeedMinutes: FloatParameter = FloatParameter(2.0f, rangeMin = 1.0f, rangeMax = 15.0f),
    val drawSpeedInstant: Boolean = false,
    val drawLengthSteps: Int = 3000, 
    val drawLengthFactor: Float = 1.0f,
    val drawLengthLooping: Boolean = true,
    
    // Style configurations
    val styleMode: String = "rainbow", // "solid", "length", "center", "rainbow"
    val solidColor: Int = 0xFF00E5FF.toInt(),
    val gradientStartColor: Int = 0xFF00E5FF.toInt(),
    val gradientEndColor: Int = 0xFFFF4081.toInt(),
    
    val solidColorHue: FloatParameter = FloatParameter(180f, rangeMin = 0f, rangeMax = 360f),
    val gradientStartHue: FloatParameter = FloatParameter(180f, rangeMin = 0f, rangeMax = 360f),
    val gradientEndHue: FloatParameter = FloatParameter(330f, rangeMin = 0f, rangeMax = 360f),
    
    val saturation: FloatParameter = FloatParameter(0.9f, rangeMin = 0.1f, rangeMax = 1.0f),
    val hueShiftingEnabled: Boolean = true,
    val hueShiftSpeed: FloatParameter = FloatParameter(15f, rangeMin = 0f, rangeMax = 60f),
    val hueShiftRange: FloatParameter = FloatParameter(360f, rangeMin = 0f, rangeMax = 360f, locked = true, rangeLocked = true, selectedMin = 0f, selectedMax = 360f),
    
    // Extra style coloring parameters
    val rainbowHue: FloatParameter = FloatParameter(0f, rangeMin = 0f, rangeMax = 360f),
    val rainbowColorRange: FloatParameter = FloatParameter(360f, rangeMin = 0f, rangeMax = 360f),
    val spicyHue: FloatParameter = FloatParameter(120f, rangeMin = 0f, rangeMax = 360f),
    val spicyColorRange: FloatParameter = FloatParameter(180f, rangeMin = 0f, rangeMax = 360f),
    val brightness: FloatParameter = FloatParameter(0.95f, rangeMin = 0.1f, rangeMax = 1.0f, locked = true),
    val liveBrightnessShiftEnabled: Boolean = false,
    val brightnessShiftSpeed: FloatParameter = FloatParameter(1.0f, rangeMin = 0.0f, rangeMax = 5.0f),
    
    // Pen setups
    val penCount: IntParameter = IntParameter(1, rangeMin = 1, rangeMax = 3),
    val penOffset: FloatParameter = FloatParameter(12f, rangeMin = 2f, rangeMax = 30f),
    val penRotationEnabled: BooleanParameter = BooleanParameter(false),
    val penRotationMultiplier: IntParameter = IntParameter(2, rangeMin = 1, rangeMax = 8),
    val penRotationIsMultiply: BooleanParameter = BooleanParameter(true),
    
    // Pen tip settings
    val penTipEnabled: Boolean = true,
    val penTipShape: String = "circle", // "circle", "square", "diamond", "cross", "star"
    val penTipColorMode: String = "match_line", // "match_line", "solid"
    val penTipColor: Int = 0xFFFFFFFF.toInt(),
    val penTipSize: Float = 8f,
    
    // Periodic shapes orthogonal to the path line
    val periodicShape: String = "none", // "none", "circle", "triangle", "star"
    val periodicShapeSize: FloatParameter = FloatParameter(6f, rangeMin = 2f, rangeMax = 18f),
    val periodicShapeSolid: Boolean = false,
    val periodicShapeConcentric: Int = 1, // 1, 2, 3 concentric
    val periodicShapeDeployment: String = "stacked", // "stacked" or "progressive"
    val periodicShapeFreqFactor: IntParameter = IntParameter(3, rangeMin = 1, rangeMax = 8),
    val periodicShapeFreqIsMultiply: Boolean = true,
    
    // Camera Setup
    val cameraPerspective: Int = 1, // 1 = Full View, 2 = Roller coaster
    val cameraDistance: FloatParameter = FloatParameter(220f, rangeMin = 80f, rangeMax = 600f),
    val dynamicCameraZoomEnabled: Boolean = false,
    val cameraAngleLock: Boolean = false,
    val cameraAngleLockValue: Float = 0f,
    val cameraAutoRotationEnabled: Boolean = true,
    val cameraAutoRotationRange: Float = 45f,
    val cameraAutoRotationSpeed: Float = 0.3f,
    val isAngularLockEnabled: Boolean = false,
    val angularLockAxis: String = "Z", // "X", "Y", or "Z"
    val gyroEnabled: Boolean = false,
    val gyroSensitivity: FloatParameter = FloatParameter(0.5f, rangeMin = 0.1f, rangeMax = 2.0f),
    
    // Resets
    val postCompletionAutoReset: Boolean = true,
    val postCompletionResetTimeFactor: Float = 0.25f, // 25% of draw completion time or instant
    
    // Additional options
    val decayEnabled: BooleanParameter = BooleanParameter(true),
    val rationalFrequenciesEnabled: Boolean = false,
    val lineThickness: FloatParameter = FloatParameter(2.5f, rangeMin = 0.5f, rangeMax = 12f),
    val coasterDirectionFacing: Boolean = true,
    val coasterDeviationAngle: FloatParameter = FloatParameter(25f, rangeMin = 10f, rangeMax = 45f),
    val coasterOrbitSpeed: FloatParameter = FloatParameter(1.2f, rangeMin = 0.2f, rangeMax = 5.0f)
) {
    fun roundToRational(v: Float): Float {
        val validRationals = listOf(
            1f/12f, 1f/11f, 1f/10f, 1f/9f, 1f/8f, 1f/7f, 1f/6f, 1f/5f, 1f/4f, 1f/3f, 1f/2f,
            1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f
        )
        return validRationals.minByOrNull { kotlin.math.abs(it - v) } ?: v
    }

    val activeFreqX: Float
        get() = if (rationalFrequenciesEnabled) roundToRational(freqX.current) else freqX.current
    val activeFreqY: Float
        get() = if (rationalFrequenciesEnabled) roundToRational(freqY.current) else freqY.current
    val activeFreqZ: Float
        get() = if (rationalFrequenciesEnabled) roundToRational(freqZ.current) else freqZ.current

    fun randomizeAll(random: java.util.Random): HarmonographSettings {
        val randAmpSubX = if (ampSubX.locked) ampSubX else {
            val nextEnabled = if (ampSubX.enabledLocked) ampSubX.enabled else random.nextBoolean()
            if (nextEnabled) {
                val minVal = (if (ampSubX.rangeLocked) ampSubX.actualSelectedMin else ampSubX.rangeMin).coerceAtLeast(15f)
                val maxVal = if (ampSubX.rangeLocked) ampSubX.actualSelectedMax else ampSubX.rangeMax
                val v = minVal + random.nextFloat() * (maxVal - minVal)
                ampSubX.copy(current = v, enabled = true)
            } else {
                ampSubX.copy(current = 0f, enabled = false)
            }
        }
        val randAmpSubY = if (ampSubY.locked) ampSubY else {
            val nextEnabled = if (ampSubY.enabledLocked) ampSubY.enabled else random.nextBoolean()
            if (nextEnabled) {
                val minVal = (if (ampSubY.rangeLocked) ampSubY.actualSelectedMin else ampSubY.rangeMin).coerceAtLeast(15f)
                val maxVal = if (ampSubY.rangeLocked) ampSubY.actualSelectedMax else ampSubY.rangeMax
                val v = minVal + random.nextFloat() * (maxVal - minVal)
                ampSubY.copy(current = v, enabled = true)
            } else {
                ampSubY.copy(current = 0f, enabled = false)
            }
        }
        val randAmpSubZ = if (ampSubZ.locked) ampSubZ else {
            val nextEnabled = if (ampSubZ.enabledLocked) ampSubZ.enabled else random.nextBoolean()
            if (nextEnabled) {
                val minVal = (if (ampSubZ.rangeLocked) ampSubZ.actualSelectedMin else ampSubZ.rangeMin).coerceAtLeast(15f)
                val maxVal = if (ampSubZ.rangeLocked) ampSubZ.actualSelectedMax else ampSubZ.rangeMax
                val v = minVal + random.nextFloat() * (maxVal - minVal)
                ampSubZ.copy(current = v, enabled = true)
            } else {
                ampSubZ.copy(current = 0f, enabled = false)
            }
        }

        val randSat = saturation.randomize(random)
        val randSolidHue = solidColorHue.randomize(random)
        val randGradStartHue = gradientStartHue.randomize(random)
        val randGradEndHue = gradientEndHue.randomize(random)

        val activeSolid = hsvToColorInt(randSolidHue.current, randSat.current)
        val activeGradStart = hsvToColorInt(randGradStartHue.current, randSat.current)
        val activeGradEnd = hsvToColorInt(randGradEndHue.current, randSat.current)

        var randFreqX = freqX.randomize(random)
        var randFreqY = freqY.randomize(random)
        var randFreqZ = freqZ.randomize(random)
        if (rationalFrequenciesEnabled) {
            randFreqX = randFreqX.copy(current = roundToRational(randFreqX.current))
            randFreqY = randFreqY.copy(current = roundToRational(randFreqY.current))
            randFreqZ = randFreqZ.copy(current = roundToRational(randFreqZ.current))
        }

        return copy(
            ampX = ampX.randomize(random),
            ampY = ampY.randomize(random),
            ampZ = ampZ.randomize(random),
            
            freqX = randFreqX,
            freqY = randFreqY,
            freqZ = randFreqZ,
            
            decayX = decayX.randomize(random),
            decayY = decayY.randomize(random),
            decayZ = decayZ.randomize(random),
            
            phaseX = phaseX.randomize(random),
            phaseY = phaseY.randomize(random),
            phaseZ = phaseZ.randomize(random),
            
            phaseSubX = phaseSubX.randomize(random),
            phaseSubY = phaseSubY.randomize(random),
            phaseSubZ = phaseSubZ.randomize(random),
            
            ampSubX = randAmpSubX,
            ampSubY = randAmpSubY,
            ampSubZ = randAmpSubZ,
            
            subXFreqFactor = subXFreqFactor.randomize(random),
            subXFreqIsMultiply = subXFreqIsMultiply.randomize(random),
            subYFreqFactor = subYFreqFactor.randomize(random),
            subYFreqIsMultiply = subYFreqIsMultiply.randomize(random),
            subZFreqFactor = subZFreqFactor.randomize(random),
            subZFreqIsMultiply = subZFreqIsMultiply.randomize(random),
            
            saturation = randSat,
            solidColorHue = randSolidHue,
            gradientStartHue = randGradStartHue,
            gradientEndHue = randGradEndHue,
            solidColor = activeSolid,
            gradientStartColor = activeGradStart,
            gradientEndColor = activeGradEnd,
            
            hueShiftSpeed = hueShiftSpeed.randomize(random),
            hueShiftRange = hueShiftRange.randomize(random),
            
            rainbowHue = rainbowHue.randomize(random),
            rainbowColorRange = rainbowColorRange.randomize(random),
            spicyHue = spicyHue.randomize(random),
            spicyColorRange = spicyColorRange.randomize(random),
            brightness = brightness.randomize(random),
            liveBrightnessShiftEnabled = random.nextBoolean(),
            brightnessShiftSpeed = brightnessShiftSpeed.randomize(random),
            
            penCount = penCount.randomize(random),
            penRotationEnabled = penRotationEnabled.randomize(random),
            penRotationMultiplier = penRotationMultiplier.randomize(random),
            penRotationIsMultiply = penRotationIsMultiply.randomize(random),
            drawSpeedMinutes = drawSpeedMinutes.randomize(random),
            
            penOffset = penOffset.randomize(random),
            periodicShapeSize = periodicShapeSize.randomize(random),
            periodicShapeFreqFactor = periodicShapeFreqFactor.randomize(random),
            lineThickness = lineThickness.randomize(random),
            cameraDistance = cameraDistance.randomize(random),
            coasterDeviationAngle = coasterDeviationAngle.randomize(random),
            coasterOrbitSpeed = coasterOrbitSpeed.randomize(random),
            decayEnabled = decayEnabled.randomize(random),
            gyroSensitivity = gyroSensitivity.randomize(random)
        )
    }

    private fun hsvToColorInt(hue: Float, sat: Float): Int {
        val hsv = floatArrayOf(hue, sat, 1.0f)
        return android.graphics.Color.HSVToColor(hsv)
    }
}

@Entity(tableName = "harmonograph_presets")
data class HarmonographPreset(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val isUserPreset: Boolean = true,
    // Store JSON serialization of settings
    val settingsJson: String
)

@Dao
interface HarmonographDao {
    @Query("SELECT * FROM harmonograph_presets ORDER BY id DESC")
    fun getAllPresets(): Flow<List<HarmonographPreset>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: HarmonographPreset)

    @Query("DELETE FROM harmonograph_presets WHERE id = :id")
    suspend fun deletePresetById(id: Int)

    @Query("DELETE FROM harmonograph_presets WHERE isUserPreset = 0")
    suspend fun deleteDefaultPresets()
}

@Database(entities = [HarmonographPreset::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): HarmonographDao
}
