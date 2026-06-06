package com.example

import kotlin.math.*
import android.graphics.Path as AndroidPath

data class Point3D(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Point3D) = Point3D(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Point3D) = Point3D(x - other.x, y - other.y, z - other.z)
    operator fun times(factor: Float) = Point3D(x * factor, y * factor, z * factor)
    
    fun length(): Float = sqrt(x * x + y * y + z * z)
    
    fun normalized(): Point3D {
        val len = length()
        return if (len > 0.0001f) Point3D(x / len, y / len, z / len) else Point3D(1f, 0f, 0f)
    }
    
    fun cross(o: Point3D) = Point3D(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )
}

data class ProjectedPoint(
    val x: Float,
    val y: Float,
    val depth: Float,
    val originalIndex: Int,
    val isTip: Boolean = false
)

data class CustomShapeData(
    val center: Point3D,
    val shapeType: String,
    val size: Float,
    val isSolid: Boolean,
    val concentric: Int,
    val deployment: String,
    val uVector: Point3D,
    val wVector: Point3D,
    val colorIndex: Int
)

object HarmonographMath {

    /**
     * Generates standard Harmonograph timeline base point at index/step k
     */
    fun calculatePointAtStep(k: Int, settings: HarmonographSettings, dt: Float = 0.015f): Point3D {
        val t = k * dt
        
        // XYZ Base signals with decay and phase
        val px = Math.toRadians(settings.phaseX.current.toDouble()).toFloat()
        val py = Math.toRadians(settings.phaseY.current.toDouble()).toFloat()
        val pz = Math.toRadians(settings.phaseZ.current.toDouble()).toFloat()
        
        val decayFactorX = exp(-settings.decayX.current * t)
        val decayFactorY = exp(-settings.decayY.current * t)
        val decayFactorZ = exp(-settings.decayZ.current * t)
        
        var xRaw = settings.ampX.current * decayFactorX * sin(settings.freqX.current * t + px)
        var yRaw = settings.ampY.current * decayFactorY * sin(settings.freqY.current * t + py)
        val zRaw = if (settings.ampZ.current > 0f) {
            settings.ampZ.current * decayFactorZ * sin(settings.freqZ.current * t + pz)
        } else {
            0f
        }
        
        // Compute sublayers X', Y', Z' if amplitudes > 0
        val fastestBase = maxOf(settings.freqX.current, settings.freqY.current, settings.freqZ.current)
        
        // Sublayer X'
        if (settings.ampSubX.enabled && settings.ampSubX.current > 0f) {
            val factor = settings.subXFreqFactor.current.toFloat()
            val freqSubX = if (settings.subXFreqIsMultiply) fastestBase * factor else fastestBase / factor
            xRaw += settings.ampSubX.current * decayFactorX * sin(freqSubX * t + px + PI.toFloat() / 4f)
        }
        
        // Sublayer Y'
        if (settings.ampSubY.enabled && settings.ampSubY.current > 0f) {
            val factor = settings.subYFreqFactor.current.toFloat()
            val freqSubY = if (settings.subYFreqIsMultiply) fastestBase * factor else fastestBase / factor
            yRaw += settings.ampSubY.current * decayFactorY * sin(freqSubY * t + py + PI.toFloat() / 4f)
        }
        
        // Sublayer Z'
        if (settings.ampSubZ.enabled && settings.ampSubZ.current > 0f) {
            val factor = settings.subZFreqFactor.current.toFloat()
            val freqSubZ = if (settings.subZFreqIsMultiply) fastestBase * factor else fastestBase / factor
            // Let sublayer Z' influence the depth
            xRaw += settings.ampSubZ.current * decayFactorZ * sin(freqSubZ * t + pz + PI.toFloat() / 4f)
        }
        
        return Point3D(xRaw, yRaw, zRaw)
    }

    /**
     * Generates all primary and pen-offset points along the drawing path
     */
    fun generatePathPoints(
        settings: HarmonographSettings,
        maxSteps: Int,
        dt: Float = 0.015f
    ): List<List<Point3D>> {
        val totalSteps = (maxSteps * settings.drawLengthFactor).roundToInt().coerceIn(100, 15000)
        
        // Initialize lines for pen counts: 1 to 3
        val paths = List(settings.penCount) { mutableListOf<Point3D>() }
        
        val fastestBase = maxOf(settings.freqX.current, settings.freqY.current, settings.freqZ.current)
        
        for (k in 0 until totalSteps) {
            val basePt = calculatePointAtStep(k, settings, dt)
            val t = k * dt
            
            if (settings.penCount == 1) {
                paths[0].add(basePt)
            } else {
                // We need orthogonal plane to calculate offset vectors
                val nextPt = calculatePointAtStep(k + 1, settings, dt)
                val dir = (nextPt - basePt).normalized()
                
                // Find a perpendicular vector
                val helper = if (abs(dir.y) < 0.9f) Point3D(0f, 1f, 0f) else Point3D(1f, 0f, 0f)
                val uVec = dir.cross(helper).normalized()
                val wVec = dir.cross(uVec).normalized()
                
                val rotationAngle = if (settings.penRotationEnabled) {
                    val factor = settings.penRotationMultiplier.current.toFloat()
                    val rotSpeed = if (settings.penRotationIsMultiply) fastestBase * factor else fastestBase / factor
                    rotSpeed * t
                } else {
                    0f
                }
                
                // Rotation offsets
                val cosAng = cos(rotationAngle)
                val sinAng = sin(rotationAngle)
                
                // Primary offset direction
                val dirOffset = uVec * cosAng + wVec * sinAng
                
                if (settings.penCount == 2) {
                    val p1 = basePt + dirOffset * settings.penOffset.current
                    val p2 = basePt - dirOffset * settings.penOffset.current
                    paths[0].add(p1)
                    paths[1].add(p2)
                } else if (settings.penCount == 3) {
                    // Triangle placement
                    val dirOffset2 = uVec * cos(rotationAngle + 2f * PI.toFloat() / 3f) + wVec * sin(rotationAngle + 2f * PI.toFloat() / 3f)
                    val dirOffset3 = uVec * cos(rotationAngle + 4f * PI.toFloat() / 3f) + wVec * sin(rotationAngle + 4f * PI.toFloat() / 3f)
                    
                    paths[0].add(basePt + dirOffset * settings.penOffset.current)
                    paths[1].add(basePt + dirOffset2 * settings.penOffset.current)
                    paths[2].add(basePt + dirOffset3 * settings.penOffset.current)
                }
            }
        }
        
        return paths
    }

    /**
     * Generates secondary shapes along the line path
     */
    fun generatePeriodicShapes(
        settings: HarmonographSettings,
        maxSteps: Int,
        dt: Float = 0.015f
    ): List<CustomShapeData> {
        if (settings.periodicShape == "none") return emptyList()
        
        val totalSteps = (maxSteps * settings.drawLengthFactor).roundToInt().coerceIn(100, 15000)
        val shapesList = mutableListOf<CustomShapeData>()
        
        val fastestBase = maxOf(settings.freqX.current, settings.freqY.current, settings.freqZ.current)
        val factor = settings.periodicShapeFreqFactor.current.toFloat()
        val freqShape = if (settings.periodicShapeFreqIsMultiply) fastestBase * factor else fastestBase / factor
        
        // We will plant shapes at peaks of the sine wave of the periodic trigger
        val threshold = 0.08f
        var prevVal = 0f
        
        for (k in 1 until totalSteps step 2) {
            val t = k * dt
            val currentVal = sin(freqShape * t)
            
            // Check for peak threshold (local max)
            if (currentVal > 0.92f && prevVal <= currentVal) {
                // Trigger a shape here
                val basePt = calculatePointAtStep(k, settings, dt)
                val nextPt = calculatePointAtStep(k + 1, settings, dt)
                val dir = (nextPt - basePt).normalized()
                
                val helper = if (abs(dir.y) < 0.9f) Point3D(0f, 1f, 0f) else Point3D(1f, 0f, 0f)
                val uVec = dir.cross(helper).normalized()
                val wVec = dir.cross(uVec).normalized()
                
                shapesList.add(
                    CustomShapeData(
                        center = basePt,
                        shapeType = settings.periodicShape,
                        size = settings.periodicShapeSize.current,
                        isSolid = settings.periodicShapeSolid,
                        concentric = settings.periodicShapeConcentric,
                        deployment = settings.periodicShapeDeployment,
                        uVector = uVec,
                        wVector = wVec,
                        colorIndex = k
                    )
                )
            }
            prevVal = currentVal
        }
        return shapesList
    }

    /**
     * Rotates and projects 3D coordinates into 2D camera coordinates
     */
    fun project3DTo2D(
        points: List<Point3D>,
        yaw: Float, // horizontal rotation degrees
        pitch: Float, // vertical elevation degrees
        perspective: Int, // 1 = Distant screen fill, 2 = Roller coaster follow tip
        currentDrawIndex: Int,
        screenWidth: Float,
        screenHeight: Float,
        angularLock: Boolean,
        angularLockAxis: String = "Z"
    ): List<ProjectedPoint> {
        if (points.isEmpty()) return emptyList()
        
        val maxIndex = minOf(currentDrawIndex, points.size - 1)
        val activePoints = points.subList(0, maxIndex + 1)
        
        // Focal distance
        val dFocal = 550f 
        
        // Dynamic Lock View Perpendicular to Plane (directly projections bypass rotation triggers)
        if (angularLock && perspective == 1) {
            return activePoints.mapIndexed { idx, pt ->
                val (projX, projY, depth) = when (angularLockAxis) {
                    "X" -> Triple(pt.y, pt.z, pt.x)
                    "Y" -> Triple(pt.x, pt.z, pt.y)
                    else -> Triple(pt.x, pt.y, pt.z) // "Z"
                }
                val scale = dFocal / (dFocal + depth)
                val u = screenWidth / 2f + projX * scale
                val v = screenHeight / 2f - projY * scale
                ProjectedPoint(
                    x = u,
                    y = v,
                    depth = depth,
                    originalIndex = idx,
                    isTip = (idx == maxIndex)
                )
            }
        }
        
        val yawRad = Math.toRadians(yaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(pitch.toDouble()).toFloat()
        
        return if (perspective == 1) {
            // Perspective 1: Distant viewing, looking at center (0,0,0)
            activePoints.mapIndexed { idx, pt ->
                // Apply yaw/pitch rot
                val cxX = cos(yawRad)
                val sxX = sin(yawRad)
                val cyY = cos(pitchRad)
                val syY = sin(pitchRad)
                
                // YAW around Z-Axis
                val xRot1 = pt.x * cxX - pt.y * sxX
                val yRot1 = pt.x * sxX + pt.y * cxX
                val zRot1 = pt.z
                
                // PITCH around X-axis
                val xRot2 = xRot1
                val yRot2 = yRot1 * cyY - zRot1 * syY
                val zRot2 = yRot1 * syY + zRot1 * cyY
                
                // Perspective division
                val scale = dFocal / (dFocal + zRot2)
                val u = screenWidth / 2f + xRot2 * scale
                val v = screenHeight / 2f - yRot2 * scale
                ProjectedPoint(
                    x = u,
                    y = v,
                    depth = zRot2,
                    originalIndex = idx,
                    isTip = (idx == maxIndex)
                )
            }
        } else {
            // Perspective 2: Roller coaster pen-riding!
            // Follow lookAtTarget with continuous stable camera based on yaw and pitch
            val tipIndex = maxIndex
            val lookAtTarget = points[tipIndex]
            
            // Stable camera following lookAtTarget using spherical coordinates derived from drag
            val dist = 220f
            val radYaw = Math.toRadians(yaw.toDouble()).toFloat()
            val radPitch = Math.toRadians(pitch.toDouble()).toFloat()
            
            // Spherical camera position relative to moving pen tip
            val offsetX = dist * cos(radPitch) * sin(radYaw)
            val offsetY = -dist * cos(radPitch) * cos(radYaw)
            val offsetZ = dist * sin(radPitch)
            
            val camPos = lookAtTarget + Point3D(offsetX, offsetY, offsetZ)
            
            // Camera viewing direction pointing from camera towards target
            val viewVec = lookAtTarget - camPos
            val viewLen = viewVec.length()
            val viewDir = if (viewLen > 0.05f) {
                Point3D(viewVec.x / viewLen, viewVec.y / viewLen, viewVec.z / viewLen)
            } else {
                Point3D(0f, 1f, 0f)
            }
            
            val helper = if (abs(viewDir.z) > 0.9f) Point3D(0f, 1f, 0f) else Point3D(0f, 0f, 1f)
            val sideDir = viewDir.cross(helper).normalized()
            val upDir = sideDir.cross(viewDir).normalized()
            
            activePoints.mapIndexed { idx, pt ->
                val rel = pt - camPos
                
                // Transform to Camera coordinate system
                val rx = rel.x * sideDir.x + rel.y * sideDir.y + rel.z * sideDir.z
                val ry = rel.x * upDir.x + rel.y * upDir.y + rel.z * upDir.z
                val rz = rel.x * viewDir.x + rel.y * viewDir.y + rel.z * viewDir.z
                
                // Perspective division
                val depth = rz.coerceAtLeast(0.1f)
                val scale = dFocal / depth
                
                val u = screenWidth / 2f + rx * scale * 0.82f
                val v = screenHeight / 2f - ry * scale * 0.82f
                
                ProjectedPoint(
                    x = u,
                    y = v,
                    depth = depth,
                    originalIndex = idx,
                    isTip = (idx == tipIndex)
                )
            }
        }
    }
}
