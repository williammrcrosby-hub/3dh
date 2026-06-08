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
    val isTip: Boolean = false,
    val dist3D: Float = 0f
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

    @Volatile
    var lastCalculatedFitMultiplier = 1f

    /**
     * Generates standard Harmonograph timeline base point at index/step k
     */
    fun calculatePointAtStep(k: Int, settings: HarmonographSettings, dt: Float = 0.015f): Point3D {
        val t = k * dt
        
        // XYZ Base signals with decay and phase
        val px = Math.toRadians(settings.phaseX.current.toDouble()).toFloat()
        val py = Math.toRadians(settings.phaseY.current.toDouble()).toFloat()
        val pz = Math.toRadians(settings.phaseZ.current.toDouble()).toFloat()
        
        val decayFactorX = if (settings.decayEnabled.current) exp(-settings.decayX.current * t) else 1f
        val decayFactorY = if (settings.decayEnabled.current) exp(-settings.decayY.current * t) else 1f
        val decayFactorZ = if (settings.decayEnabled.current) exp(-settings.decayZ.current * t) else 1f
        
        var xRaw = settings.ampX.current * decayFactorX * sin(settings.activeFreqX * t + px)
        var yRaw = settings.ampY.current * decayFactorY * sin(settings.activeFreqY * t + py)
        val zRaw = if (settings.ampZ.current > 0f) {
            settings.ampZ.current * decayFactorZ * sin(settings.activeFreqZ * t + pz)
        } else {
            0f
        }
        
        // Compute sublayers X', Y', Z' if amplitudes > 0
        
        // Sublayer X'
        if (settings.ampSubX.enabled && settings.ampSubX.current > 0f) {
            val factor = settings.subXFreqFactor.current.toFloat()
            val freqSubX = if (settings.subXFreqIsMultiply.current) settings.activeFreqX * factor else settings.activeFreqX / factor
            val pSubX = Math.toRadians(settings.phaseSubX.current.toDouble()).toFloat()
            xRaw += settings.ampSubX.current * decayFactorX * sin(freqSubX * t + px + pSubX)
        }
        
        // Sublayer Y'
        if (settings.ampSubY.enabled && settings.ampSubY.current > 0f) {
            val factor = settings.subYFreqFactor.current.toFloat()
            val freqSubY = if (settings.subYFreqIsMultiply.current) settings.activeFreqY * factor else settings.activeFreqY / factor
            val pSubY = Math.toRadians(settings.phaseSubY.current.toDouble()).toFloat()
            yRaw += settings.ampSubY.current * decayFactorY * sin(freqSubY * t + py + pSubY)
        }
        
        // Sublayer Z'
        if (settings.ampSubZ.enabled && settings.ampSubZ.current > 0f) {
            val factor = settings.subZFreqFactor.current.toFloat()
            val freqSubZ = if (settings.subZFreqIsMultiply.current) settings.activeFreqZ * factor else settings.activeFreqZ / factor
            val pSubZ = Math.toRadians(settings.phaseSubZ.current.toDouble()).toFloat()
            // Let sublayer Z' influence the depth
            xRaw += settings.ampSubZ.current * decayFactorZ * sin(freqSubZ * t + pz + pSubZ)
        }
        
        return Point3D(xRaw, yRaw, zRaw)
    }

    /**
     * Generates all primary and pen-offset points along the drawing path
     */
    fun generatePathPoints(
        settings: HarmonographSettings,
        maxSteps: Int,
        dtDefault: Float = 0.015f
    ): List<List<Point3D>> {
        // Calculate max active frequency in the system to determine optimal dt
        var maxActiveFreq = maxOf(settings.activeFreqX, settings.activeFreqY, settings.activeFreqZ)
        if (settings.ampSubX.enabled && settings.ampSubX.current > 0f) {
            val factor = settings.subXFreqFactor.current.toFloat()
            val f = if (settings.subXFreqIsMultiply.current) maxActiveFreq * factor else maxActiveFreq / factor
            if (f > maxActiveFreq) maxActiveFreq = f
        }
        if (settings.ampSubY.enabled && settings.ampSubY.current > 0f) {
            val factor = settings.subYFreqFactor.current.toFloat()
            val f = if (settings.subYFreqIsMultiply.current) maxActiveFreq * factor else maxActiveFreq / factor
            if (f > maxActiveFreq) maxActiveFreq = f
        }
        if (settings.ampSubZ.enabled && settings.ampSubZ.current > 0f) {
            val factor = settings.subZFreqFactor.current.toFloat()
            val f = if (settings.subZFreqIsMultiply.current) maxActiveFreq * factor else maxActiveFreq / factor
            if (f > maxActiveFreq) maxActiveFreq = f
        }
        
        // Dynamically compute adaptive dt: for higher frequencies, sample with a much finer steps to preserve smooth curves.
        val dt = minOf(0.015f, 0.45f / maxActiveFreq)
 
        val totalSteps = (maxSteps * settings.drawLengthFactor).roundToInt().coerceIn(100, 15000)
        
        // Initialize lines for pen counts: 1 to 3
        val paths = List(settings.penCount.current) { mutableListOf<Point3D>() }
        
        val fastestBase = maxOf(settings.activeFreqX, settings.activeFreqY, settings.activeFreqZ)
        
        // Stable parallel transport frame tracking to ensure zero jumpy or jagged flips!
        var prevUVec: Point3D? = null
        
        for (k in 0 until totalSteps) {
            val basePt = calculatePointAtStep(k, settings, dt)
            val t = k * dt
            
            if (settings.penCount.current == 1) {
                paths[0].add(basePt)
            } else {
                // We need orthogonal plane to calculate offset vectors
                val nextPt = calculatePointAtStep(k + 1, settings, dt)
                val dir = (nextPt - basePt).normalized()
                
                // Construct stable orthogonal vectors using parallel transport frame projection
                val uVec: Point3D
                val wVec: Point3D
                if (prevUVec == null) {
                    val dotX = abs(dir.x)
                    val dotY = abs(dir.y)
                    val dotZ = abs(dir.z)
                    val helper = when {
                        dotX <= dotY && dotX <= dotZ -> Point3D(1f, 0f, 0f)
                        dotY <= dotX && dotY <= dotZ -> Point3D(0f, 1f, 0f)
                        else -> Point3D(0f, 0f, 1f)
                    }
                    val u = dir.cross(helper).normalized()
                    uVec = u
                    wVec = dir.cross(u).normalized()
                } else {
                    val dot = prevUVec.x * dir.x + prevUVec.y * dir.y + prevUVec.z * dir.z
                    val uProj = prevUVec - dir * dot
                    val len = uProj.length()
                    val u = if (len > 0.001f) {
                        uProj.normalized()
                    } else {
                        val dotX = abs(dir.x)
                        val dotY = abs(dir.y)
                        val dotZ = abs(dir.z)
                        val helper = when {
                            dotX <= dotY && dotX <= dotZ -> Point3D(1f, 0f, 0f)
                            dotY <= dotX && dotY <= dotZ -> Point3D(0f, 1f, 0f)
                            else -> Point3D(0f, 0f, 1f)
                        }
                        dir.cross(helper).normalized()
                    }
                    uVec = u
                    wVec = dir.cross(u).normalized()
                }
                prevUVec = uVec
                
                val rotationAngle = if (settings.penRotationEnabled.current) {
                    val factor = settings.penRotationMultiplier.current.toFloat()
                    val rotSpeed = if (settings.penRotationIsMultiply.current) fastestBase * factor else fastestBase / factor
                    rotSpeed * t
                } else {
                    0f
                }
                
                // Rotation offsets
                val cosAng = cos(rotationAngle)
                val sinAng = sin(rotationAngle)
                
                // Primary offset direction
                val dirOffset = uVec * cosAng + wVec * sinAng
                
                if (settings.penCount.current == 2) {
                    val p1 = basePt + dirOffset * settings.penOffset.current
                    val p2 = basePt - dirOffset * settings.penOffset.current
                    paths[0].add(p1)
                    paths[1].add(p2)
                } else if (settings.penCount.current == 3) {
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
        dtDefault: Float = 0.015f
    ): List<CustomShapeData> {
        if (settings.periodicShape == "none") return emptyList()
        
        // Calculate max active frequency in the system to determine optimal dt
        var maxActiveFreq = maxOf(settings.freqX.current, settings.freqY.current, settings.freqZ.current)
        if (settings.ampSubX.enabled && settings.ampSubX.current > 0f) {
            val factor = settings.subXFreqFactor.current.toFloat()
            val f = if (settings.subXFreqIsMultiply.current) maxActiveFreq * factor else maxActiveFreq / factor
            if (f > maxActiveFreq) maxActiveFreq = f
        }
        if (settings.ampSubY.enabled && settings.ampSubY.current > 0f) {
            val factor = settings.subYFreqFactor.current.toFloat()
            val f = if (settings.subYFreqIsMultiply.current) maxActiveFreq * factor else maxActiveFreq / factor
            if (f > maxActiveFreq) maxActiveFreq = f
        }
        if (settings.ampSubZ.enabled && settings.ampSubZ.current > 0f) {
            val factor = settings.subZFreqFactor.current.toFloat()
            val f = if (settings.subZFreqIsMultiply.current) maxActiveFreq * factor else maxActiveFreq / factor
            if (f > maxActiveFreq) maxActiveFreq = f
        }
        
        // Dynamically compute adaptive dt to match the path point sampling perfectly
        val dt = minOf(0.015f, 0.45f / maxActiveFreq)

        val totalSteps = (maxSteps * settings.drawLengthFactor).roundToInt().coerceIn(100, 15000)
        val shapesList = mutableListOf<CustomShapeData>()
        
        val fastestBase = maxOf(settings.freqX.current, settings.freqY.current, settings.freqZ.current)
        val factor = settings.periodicShapeFreqFactor.current.toFloat()
        val freqShape = if (settings.periodicShapeFreqIsMultiply) fastestBase * factor else fastestBase / factor
        
        // We will plant shapes at peaks of the sine wave of the periodic trigger
        var prevVal = 0f
        var hasTriggeredThisPeak = false
        var lastShapeAnimTime = -10.0f
        
        // Stable parallel transport tracking for shape orientations
        var prevUVec: Point3D? = null
        
        for (k in 0 until totalSteps) {
            val t = k * dt
            val basePt = calculatePointAtStep(k, settings, dt)
            val nextPt = calculatePointAtStep(k + 1, settings, dt)
            val dir = (nextPt - basePt).normalized()
            
            // Stable parallel transport uVec calculation
            val uVec: Point3D
            val wVec: Point3D
            if (prevUVec == null) {
                val dotX = abs(dir.x)
                val dotY = abs(dir.y)
                val dotZ = abs(dir.z)
                val helper = when {
                    dotX <= dotY && dotX <= dotZ -> Point3D(1f, 0f, 0f)
                    dotY <= dotX && dotY <= dotZ -> Point3D(0f, 1f, 0f)
                    else -> Point3D(0f, 0f, 1f)
                }
                val u = dir.cross(helper).normalized()
                uVec = u
                wVec = dir.cross(u).normalized()
            } else {
                val dot = prevUVec.x * dir.x + prevUVec.y * dir.y + prevUVec.z * dir.z
                val uProj = prevUVec - dir * dot
                val u = uProj.normalized()
                uVec = u
                wVec = dir.cross(u).normalized()
            }
            prevUVec = uVec
            
            val currentVal = sin(freqShape * t)
            
            // Check time-based progressive delay if progressive deployment is enabled
            val virtualDurationSec = if (settings.drawSpeedInstant) 120f else settings.drawSpeedMinutes.current * 60f
            val stepAnimTime = (k.toFloat() / totalSteps.coerceAtLeast(1)) * virtualDurationSec
            val timeDelayOk = if (settings.periodicShapeDeployment == "progressive") {
                (stepAnimTime - lastShapeAnimTime) >= settings.periodicProgressiveDelay.current
            } else {
                true
            }

            val isPeakCandidate = currentVal > 0.95f && currentVal < prevVal && prevVal > 0.94f
            val isPeak = isPeakCandidate && !hasTriggeredThisPeak && timeDelayOk
            
            if (currentVal > 0.95f) {
                if (isPeak) {
                    hasTriggeredThisPeak = true
                    lastShapeAnimTime = stepAnimTime
                }
            } else {
                hasTriggeredThisPeak = false
            }
            prevVal = currentVal
            
            if (isPeak) {
                
                val penCount = settings.penCount.current
                if (penCount == 1) {
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
                } else {
                    val rotationAngle = if (settings.penRotationEnabled.current) {
                        val factor = settings.penRotationMultiplier.current.toFloat()
                        val rotSpeed = if (settings.penRotationIsMultiply.current) fastestBase * factor else fastestBase / factor
                        rotSpeed * t
                    } else {
                        0f
                    }
                    
                    // Rotation offsets
                    val cosAng = cos(rotationAngle)
                    val sinAng = sin(rotationAngle)
                    
                    // Primary offset direction
                    val dirOffset = uVec * cosAng + wVec * sinAng

                    if (penCount == 2) {
                        val p1 = basePt + dirOffset * settings.penOffset.current
                        val p2 = basePt - dirOffset * settings.penOffset.current
                        shapesList.add(
                            CustomShapeData(
                                center = p1,
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
                        shapesList.add(
                            CustomShapeData(
                                center = p2,
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
                    } else if (penCount == 3) {
                        val dirOffset2 = uVec * cos(rotationAngle + 2f * PI.toFloat() / 3f) + wVec * sin(rotationAngle + 2f * PI.toFloat() / 3f)
                        val dirOffset3 = uVec * cos(rotationAngle + 4f * PI.toFloat() / 3f) + wVec * sin(rotationAngle + 4f * PI.toFloat() / 3f)
                        val p1 = basePt + dirOffset * settings.penOffset.current
                        val p2 = basePt + dirOffset2 * settings.penOffset.current
                        val p3 = basePt + dirOffset3 * settings.penOffset.current
                        shapesList.add(
                            CustomShapeData(
                                center = p1,
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
                        shapesList.add(
                            CustomShapeData(
                                center = p2,
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
                        shapesList.add(
                            CustomShapeData(
                                center = p3,
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
                }
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
        currentDrawProgress: Float, // floating-point progress to enable micro-pixel smooth trailing path!
        screenWidth: Float,
        screenHeight: Float,
        angularLock: Boolean,
        angularLockAxis: String = "Z",
        referencePoints: List<Point3D>? = null,
        cameraTargetIndex: Float = -1f,
        cameraDistance: Float = 220f,
        dynamicCameraZoomEnabled: Boolean = false,
        coasterDirectionFacing: Boolean = false,
        animTime: Long = 0L,
        coasterDeviationAngle: Float = 25f,
        coasterOrbitSpeed: Float = 1.2f,
        isPrimaryPath: Boolean = false
    ): List<ProjectedPoint> {
        if (points.isEmpty()) return emptyList()
        
        // Find fractional progress index and fractional parts to avoid "jumpy / choppy" front tip steps!
        val progressInt = floor(currentDrawProgress).toInt().coerceIn(0, points.size - 1)
        val progressFrac = (currentDrawProgress - progressInt).coerceIn(0f, 1f)
        
        val activePoints = ArrayList<Point3D>(progressInt + 2)
        for (i in 0..progressInt) {
            activePoints.add(points[i])
        }
        if (progressFrac > 0.001f && progressInt < points.size - 1) {
            val p1 = points[progressInt]
            val p2 = points[progressInt + 1]
            val interpolated = Point3D(
                p1.x + (p2.x - p1.x) * progressFrac,
                p1.y + (p2.y - p1.y) * progressFrac,
                p1.z + (p2.z - p1.z) * progressFrac
            )
            activePoints.add(interpolated)
        }
        
        val maxIndex = activePoints.size - 1
        
        // Focal distance
        val dFocal = 550f 
        
        // Dynamic Lock View Perpendicular to Plane (directly projections bypass rotation triggers)
        if (angularLock && perspective == 1) {
            val dFocalScale = 300f / cameraDistance
            val useDynamicZoom = true // always on dynamic camera mode for full view
            if (useDynamicZoom) {
                var maxAbsX = 0.01f
                var maxAbsY = 0.01f
                
                val rawProj = activePoints.map { pt ->
                    val (projX, projY, depth) = when (angularLockAxis) {
                        "X" -> Triple(pt.y, pt.z, pt.x)
                        "Y" -> Triple(pt.x, pt.z, pt.y)
                        else -> Triple(pt.x, pt.y, pt.z) // "Z"
                    }
                    val scale = dFocal / (dFocal + depth)
                    val rx = projX * scale * dFocalScale
                    val ry = -projY * scale * dFocalScale
                    
                    val absX = abs(rx)
                    val absY = abs(ry)
                    if (absX > maxAbsX) maxAbsX = absX
                    if (absY > maxAbsY) maxAbsY = absY
                    
                    Triple(rx, ry, depth)
                }
                
                val allowedWidth = (screenWidth * 0.9f) / 2f
                val allowedHeight = (screenHeight * 0.9f) / 2f
                val fitMultiplier = if (isPrimaryPath && points.size > 50) {
                    val m = minOf(allowedWidth / maxAbsX, allowedHeight / maxAbsY).coerceIn(0.1f, 15f)
                    lastCalculatedFitMultiplier = m
                    m
                } else {
                    lastCalculatedFitMultiplier
                }
                
                return rawProj.mapIndexed { idx, (rx, ry, depth) ->
                    val u = screenWidth / 2f + rx * fitMultiplier
                    val v = screenHeight / 2f + ry * fitMultiplier
                    val pt = activePoints[idx]
                    ProjectedPoint(
                        x = u,
                        y = v,
                        depth = depth,
                        originalIndex = idx,
                        isTip = (idx == maxIndex),
                        dist3D = pt.length()
                    )
                }
            } else {
                return activePoints.mapIndexed { idx, pt ->
                    val (projX, projY, depth) = when (angularLockAxis) {
                        "X" -> Triple(pt.y, pt.z, pt.x)
                        "Y" -> Triple(pt.x, pt.z, pt.y)
                        else -> Triple(pt.x, pt.y, pt.z) // "Z"
                    }
                    val scale = (dFocal / (dFocal + depth)) * dFocalScale
                    val u = screenWidth / 2f + projX * scale
                    val v = screenHeight / 2f - projY * scale
                    ProjectedPoint(
                        x = u,
                        y = v,
                        depth = depth,
                        originalIndex = idx,
                        isTip = (idx == maxIndex),
                        dist3D = pt.length()
                    )
                }
            }
        }
        
        val yawRad = Math.toRadians(yaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(pitch.toDouble()).toFloat()
        
        return if (perspective == 1) {
            val dFocalScale = 300f / cameraDistance
            val useDynamicZoom = true // always on dynamic camera mode for full view
            if (useDynamicZoom) {
                var maxAbsX = 0.01f
                var maxAbsY = 0.01f
                
                val rawProj = activePoints.map { pt ->
                    val cxX = cos(yawRad)
                    val sxX = sin(yawRad)
                    val cyY = cos(pitchRad)
                    val syY = sin(pitchRad)
                    
                    val xRot1 = pt.x * cxX - pt.y * sxX
                    val yRot1 = pt.x * sxX + pt.y * cxX
                    val zRot1 = pt.z
                    
                    val xRot2 = xRot1
                    val yRot2 = yRot1 * cyY - zRot1 * syY
                    val zRot2 = yRot1 * syY + zRot1 * cyY
                    
                    val scale = dFocal / (dFocal + zRot2)
                    val rx = xRot2 * scale * dFocalScale
                    val ry = -yRot2 * scale * dFocalScale
                    
                    val absX = abs(rx)
                    val absY = abs(ry)
                    if (absX > maxAbsX) maxAbsX = absX
                    if (absY > maxAbsY) maxAbsY = absY
                    
                    Triple(rx, ry, zRot2)
                }
                
                val allowedWidth = (screenWidth * 0.9f) / 2f
                val allowedHeight = (screenHeight * 0.9f) / 2f
                val fitMultiplier = if (isPrimaryPath && points.size > 50) {
                    val m = minOf(allowedWidth / maxAbsX, allowedHeight / maxAbsY).coerceIn(0.1f, 15f)
                    lastCalculatedFitMultiplier = m
                    m
                } else {
                    lastCalculatedFitMultiplier
                }
                
                rawProj.mapIndexed { idx, (rx, ry, depth) ->
                    val u = screenWidth / 2f + rx * fitMultiplier
                    val v = screenHeight / 2f + ry * fitMultiplier
                    val pt = activePoints[idx]
                    ProjectedPoint(
                        x = u,
                        y = v,
                        depth = depth,
                        originalIndex = idx,
                        isTip = (idx == maxIndex),
                        dist3D = pt.length()
                    )
                }
            } else {
                activePoints.mapIndexed { idx, pt ->
                    val cxX = cos(yawRad)
                    val sxX = sin(yawRad)
                    val cyY = cos(pitchRad)
                    val syY = sin(pitchRad)
                    
                    val xRot1 = pt.x * cxX - pt.y * sxX
                    val yRot1 = pt.x * sxX + pt.y * cxX
                    val zRot1 = pt.z
                    
                    val xRot2 = xRot1
                    val yRot2 = yRot1 * cyY - zRot1 * syY
                    val zRot2 = yRot1 * syY + zRot1 * cyY
                    
                    val scale = (dFocal / (dFocal + zRot2)) * dFocalScale
                    val u = screenWidth / 2f + xRot2 * scale
                    val v = screenHeight / 2f - yRot2 * scale
                    ProjectedPoint(
                        x = u,
                        y = v,
                        depth = zRot2,
                        originalIndex = idx,
                        isTip = (idx == maxIndex),
                        dist3D = pt.length()
                    )
                }
            }
        } else {
            // Perspective 2: Roller coaster pen-riding!
            val refPts = referencePoints ?: points
            
            // Smoothly interpolate lookAtTarget based on cameraTargetIndex (floating-point parameter) or currentDrawProgress!
            val targetIdx = if (cameraTargetIndex >= 0f) cameraTargetIndex else currentDrawProgress
            val idxInt = floor(targetIdx).toInt().coerceIn(0, refPts.size - 1)
            val idxFrac = (targetIdx - idxInt).coerceIn(0f, 1f)
            val lookAtTarget = if (idxFrac > 0.001f && idxInt < refPts.size - 1) {
                val p1 = refPts[idxInt]
                val p2 = refPts[idxInt + 1]
                Point3D(
                    p1.x + (p2.x - p1.x) * idxFrac,
                    p1.y + (p2.y - p1.y) * idxFrac,
                    p1.z + (p2.z - p1.z) * idxFrac
                )
            } else {
                refPts[idxInt]
            }
            
            val dist = cameraDistance * 0.45f
            
            val camPos: Point3D
            val sideDir: Point3D
            val upDir: Point3D
            val viewDir: Point3D
            
            if (coasterDirectionFacing) {
                // Smooth helper function to interpolate point along the list path
                fun getInterpolatedPoint(list: List<Point3D>, t: Float): Point3D {
                    val idxInt = floor(t).toInt().coerceIn(0, list.size - 1)
                    val idxFrac = (t - idxInt).coerceIn(0f, 1f)
                    return if (idxFrac > 0.001f && idxInt < list.size - 1) {
                        val p1 = list[idxInt]
                        val p2 = list[idxInt + 1]
                        Point3D(
                            p1.x + (p2.x - p1.x) * idxFrac,
                            p1.y + (p2.y - p1.y) * idxFrac,
                            p1.z + (p2.z - p1.z) * idxFrac
                        )
                    } else {
                        list[idxInt]
                    }
                }

                // Symmetrically smooth tangent vector computed over a wider sliding window
                val windowSize = 25f
                val startT = (targetIdx - windowSize).coerceAtLeast(0f)
                val endT = (targetIdx + windowSize).coerceAtMost((refPts.size - 1).toFloat())
                
                val pStart = getInterpolatedPoint(refPts, startT)
                val pEnd = getInterpolatedPoint(refPts, endT)
                
                val rawDiff = pEnd - pStart
                val T = if (rawDiff.length() > 0.001f) rawDiff.normalized() else Point3D(1f, 0f, 0f)
                
                // Perfect stable Parallel Transport Frame tracked continuously starting from first node to targetIdxInt.
                // This eliminates gimbal-lock flips and hard boundary jumps completely!
                val targetIdxInt = targetIdx.toInt().coerceIn(0, refPts.size - 1)
                var transportU = Point3D(0f, 1f, 0f)
                var transportW = Point3D(0f, 0f, 1f)
                if (refPts.size > 1) {
                    val firstDiff = refPts[1] - refPts[0]
                    val firstT = if (firstDiff.length() > 0.001f) firstDiff.normalized() else Point3D(1f, 0f, 0f)
                    
                    val dotX = abs(firstT.x)
                    val dotY = abs(firstT.y)
                    val dotZ = abs(firstT.z)
                    val firstHelper = when {
                        dotX <= dotY && dotX <= dotZ -> Point3D(1f, 0f, 0f)
                        dotY <= dotX && dotY <= dotZ -> Point3D(0f, 1f, 0f)
                        else -> Point3D(0f, 0f, 1f)
                    }
                    transportU = firstT.cross(firstHelper).normalized()
                    transportW = firstT.cross(transportU).normalized()
                    
                    val stepSize = 1
                    for (i in stepSize..targetIdxInt step stepSize) {
                        val prevPt = refPts[i - stepSize]
                        val currPt = refPts[i]
                        val segmentDiff = currPt - prevPt
                        val segmentT = if (segmentDiff.length() > 0.001f) segmentDiff.normalized() else Point3D(1f, 0f, 0f)
                        
                        val dotVal = transportU.x * segmentT.x + transportU.y * segmentT.y + transportU.z * segmentT.z
                        val uProj = transportU - segmentT * dotVal
                        if (uProj.length() > 0.001f) {
                            transportU = uProj.normalized()
                            transportW = segmentT.cross(transportU).normalized()
                        }
                    }
                }
                
                // Align the transported frame with our smoothed sliding tangent T
                val dotVal = transportU.x * T.x + transportU.y * T.y + transportU.z * T.z
                val uProj = transportU - T * dotVal
                val uVec = if (uProj.length() > 0.001f) uProj.normalized() else transportU
                val wVec = T.cross(uVec).normalized()
                
                // Slow continuous orbital orbit sways around tangent axis with smooth semi-periodic randomization sways
                val orbitDrift = sin(animTime * 0.000083f).toFloat() * 1.2f + cos(animTime * 0.000031f).toFloat() * 0.7f + sin(animTime * 0.00017f).toFloat() * 0.3f
                val swayAngle = (animTime * 0.00005f * coasterOrbitSpeed) * 2f * PI.toFloat() + 
                                  sin(animTime * 0.0002f * coasterOrbitSpeed).toFloat() * 0.15f + orbitDrift
                
                val angleRad = Math.toRadians(coasterDeviationAngle.toDouble()).toFloat() // customizable deviation angle
                val cosAng = cos(angleRad)
                val sinAng = sin(angleRad)
                
                // Position camera in a beautiful offset cone behind the pen tip
                val offsetDir = T * (-cosAng) + (uVec * cos(swayAngle) + wVec * sin(swayAngle)) * sinAng
                camPos = lookAtTarget + offsetDir * dist
                
                viewDir = (lookAtTarget - camPos).normalized()
                sideDir = (uVec * (-sin(swayAngle)) + wVec * cos(swayAngle)).normalized()
                upDir = sideDir.cross(viewDir).normalized()
            } else {
                // Sphere-relative user controlled camera yaw & pitch with smooth semi-periodic sways
                val orbitDriftYaw = sin(animTime * 0.000073f).toFloat() * 18f + cos(animTime * 0.000029f).toFloat() * 10f + sin(animTime * 0.00013f).toFloat() * 4f
                val orbitDriftPitch = cos(animTime * 0.000067f).toFloat() * 12f + sin(animTime * 0.000023f).toFloat() * 7f + cos(animTime * 0.00011f).toFloat() * 3f
                
                val radYaw = Math.toRadians((yaw + orbitDriftYaw).toDouble()).toFloat()
                val radPitch = Math.toRadians((pitch + orbitDriftPitch).toDouble()).toFloat()
                
                val offsetX = dist * cos(radPitch) * sin(radYaw)
                val offsetY = -dist * cos(radPitch) * cos(radYaw)
                val offsetZ = dist * sin(radPitch)
                
                camPos = lookAtTarget + Point3D(offsetX, offsetY, offsetZ)
                
                sideDir = Point3D(cos(radYaw), sin(radYaw), 0f)
                upDir = Point3D(-sin(radPitch) * sin(radYaw), sin(radPitch) * cos(radYaw), cos(radPitch))
                viewDir = Point3D(-cos(radPitch) * sin(radYaw), cos(radPitch) * cos(radYaw), -sin(radPitch))
            }
            
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
                    isTip = (idx == maxIndex && referencePoints == null),
                    dist3D = pt.length()
                )
            }
        }
    }
}
