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
    val enabled: Boolean = true
) {
    val actualSelectedMin: Float
        get() = if (selectedMin == -1e9f) rangeMin else selectedMin
    val actualSelectedMax: Float
        get() = if (selectedMax == 1e9f) rangeMax else selectedMax

    fun withValue(v: Float): FloatParameter {
        val valMin = if (rangeLocked) actualSelectedMin else rangeMin
        val valMax = if (rangeLocked) actualSelectedMax else rangeMax
        return copy(current = v.coerceIn(valMin, valMax))
    }

    fun withRanges(min: Float, max: Float): FloatParameter {
        val minCoerced = min.coerceIn(rangeMin, rangeMax)
        val maxCoerced = max.coerceIn(rangeMin, rangeMax)
        val newCurrent = if (rangeLocked) current.coerceIn(minCoerced, maxCoerced) else current
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
        get() = if (selectedMin == Int.MIN_VALUE) rangeMin else selectedMin
    val actualSelectedMax: Int
        get() = if (selectedMax == Int.MAX_VALUE) rangeMax else selectedMax

    fun withValue(v: Int): IntParameter {
        val valMin = if (rangeLocked) actualSelectedMin else rangeMin
        val valMax = if (rangeLocked) actualSelectedMax else rangeMax
        return copy(current = v.coerceIn(valMin, valMax))
    }

    fun withRanges(min: Int, max: Int): IntParameter {
        val minCoerced = min.coerceIn(rangeMin, rangeMax)
        val maxCoerced = max.coerceIn(rangeMin, rangeMax)
        val newCurrent = if (rangeLocked) current.coerceIn(minCoerced, maxCoerced) else current
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
    
    val subXFreqFactor: IntParameter = IntParameter(2, rangeMin = 1, rangeMax = 8),
    val subXFreqIsMultiply: Boolean = true,
    val subYFreqFactor: IntParameter = IntParameter(3, rangeMin = 1, rangeMax = 8),
    val subYFreqIsMultiply: Boolean = true,
    val subZFreqFactor: IntParameter = IntParameter(4, rangeMin = 1, rangeMax = 8),
    val subZFreqIsMultiply: Boolean = true,
    
    val drawSpeedMinutes: Float = 2.0f, // 1 to 15 minutes, or instant
    val drawSpeedInstant: Boolean = false,
    val drawLengthSteps: Int = 3000, 
    val drawLengthFactor: Float = 1.0f,
    val drawLengthLooping: Boolean = true,
    
    // Style configurations
    val styleMode: String = "rainbow", // "solid", "length", "center", "rainbow"
    val solidColor: Int = 0xFF00E5FF.toInt(),
    val gradientStartColor: Int = 0xFF00E5FF.toInt(),
    val gradientEndColor: Int = 0xFFFF4081.toInt(),
    val saturation: FloatParameter = FloatParameter(0.9f, rangeMin = 0.1f, rangeMax = 1.0f),
    val hueShiftingEnabled: Boolean = true,
    
    // Pen setups
    val penCount: Int = 1, // 1, 2, or 3
    val penOffset: FloatParameter = FloatParameter(12f, rangeMin = 2f, rangeMax = 30f),
    val penRotationEnabled: Boolean = false,
    val penRotationMultiplier: IntParameter = IntParameter(2, rangeMin = 1, rangeMax = 8),
    val penRotationIsMultiply: Boolean = true,
    
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
    
    // Resets
    val postCompletionAutoReset: Boolean = false,
    val postCompletionResetTimeFactor: Float = 0.25f, // 25% of draw completion time or instant

    // Additional options
    val decayEnabled: Boolean = true,
    val lineThickness: FloatParameter = FloatParameter(2.5f, rangeMin = 0.5f, rangeMax = 12f)
) {
    fun randomizeAll(random: java.util.Random): HarmonographSettings {
        return copy(
            ampX = ampX.randomize(random),
            ampY = ampY.randomize(random),
            ampZ = ampZ.randomize(random),
            
            freqX = freqX.randomize(random),
            freqY = freqY.randomize(random),
            freqZ = freqZ.randomize(random),
            
            decayX = decayX.randomize(random),
            decayY = decayY.randomize(random),
            decayZ = decayZ.randomize(random),
            
            phaseX = phaseX.randomize(random),
            phaseY = phaseY.randomize(random),
            phaseZ = phaseZ.randomize(random),
            
            ampSubX = ampSubX.randomize(random),
            ampSubY = ampSubY.randomize(random),
            ampSubZ = ampSubZ.randomize(random),
            
            subXFreqFactor = subXFreqFactor.randomize(random),
            subYFreqFactor = subYFreqFactor.randomize(random),
            subZFreqFactor = subZFreqFactor.randomize(random),
            
            saturation = saturation.randomize(random),
            penRotationMultiplier = penRotationMultiplier.randomize(random),
            penOffset = penOffset.randomize(random),
            periodicShapeSize = periodicShapeSize.randomize(random),
            periodicShapeFreqFactor = periodicShapeFreqFactor.randomize(random),
            lineThickness = lineThickness.randomize(random),
            cameraDistance = cameraDistance.randomize(random)
        )
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
}

@Database(entities = [HarmonographPreset::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): HarmonographDao
}
