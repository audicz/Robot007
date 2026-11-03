package cz.robotcockpit.app.brain

import kotlin.math.*

data class ScanMatchResult(
    val pose: RobotPose,
    val score: Double,
    val accepted: Boolean,
    val testedPoints: Int
)

/**
 * Lightweight correlative scan matcher against the existing occupancy grid.
 * It searches a bounded window around wheel odometry. It is a local pose
 * correction stage, not loop closure or a substitute for a full graph SLAM.
 */
class ScanMatcher(
    private val map: RobotMap,
    private val maxTranslationMeters: Float = 0.25f,
    private val translationStepMeters: Float = 0.05f,
    private val maxRotationRadians: Float = Math.toRadians(12.0).toFloat(),
    private val rotationStepRadians: Float = Math.toRadians(2.0).toFloat()
) {
    fun match(scan: FloatArray, predicted: RobotPose, minRange: Float = 0.15f,
              maxRange: Float = 8f, minimumPoints: Int = 24): ScanMatchResult {
        val points = ArrayList<Pair<Float, Float>>()
        for (i in scan.indices step 2) {
            val r = scan[i]
            if (!r.isFinite() || r < minRange || r > maxRange) continue
            val a = Math.toRadians(i.toDouble()).toFloat()
            points.add(cos(a) * r to sin(a) * r)
        }
        if (points.size < minimumPoints) return ScanMatchResult(predicted, 0.0, false, points.size)

        var best = predicted
        var bestScore = Double.NEGATIVE_INFINITY
        var bestHits = 0
        var candidateCount = 0
        var dx = -maxTranslationMeters
        while (dx <= maxTranslationMeters + 1e-5f) {
            var dy = -maxTranslationMeters
            while (dy <= maxTranslationMeters + 1e-5f) {
                var da = -maxRotationRadians
                while (da <= maxRotationRadians + 1e-5f) {
                    val candidate = RobotPose(predicted.x + dx, predicted.y + dy, wrap(predicted.yaw + da))
                    var score = 0.0
                    var hits = 0
                    for ((px, py) in points) {
                        val wx = candidate.x + cos(candidate.yaw) * px - sin(candidate.yaw) * py
                        val wy = candidate.y + sin(candidate.yaw) * px + cos(candidate.yaw) * py
                        val gx = (map.width / 2 + wx / map.resolution).roundToInt()
                        val gy = (map.height / 2 - wy / map.resolution).roundToInt()
                        // Reward occupied endpoints and penalize unknown/out-of-map cells.
                        val cell = map.value(gx, gy)
                        score += when {
                            cell > 3 -> 1.0 + cell / 40.0
                            cell == 0 -> -0.15
                            else -> -0.35
                        }
                        if (cell > 3) hits++
                    }
                    // Small regularizer keeps the solution close to odometry when scores tie.
                    val motionPenalty = (abs(dx) + abs(dy)) * 0.25 +
                        abs(da) * 0.10
                    score = score / points.size - motionPenalty
                    if (score > bestScore) {
                        bestScore = score
                        best = candidate
                        bestHits = hits
                    }
                    candidateCount++
                    da += rotationStepRadians
                }
                dy += translationStepMeters
            }
            dx += translationStepMeters
        }
        val ratio = bestHits.toDouble() / points.size
        val accepted = candidateCount > 0 && ratio >= 0.08 && bestScore > -0.25
        return ScanMatchResult(if (accepted) best else predicted, bestScore, accepted, points.size)
    }

    private fun wrap(a: Float): Float {
        var v = a
        val pi = Math.PI.toFloat()
        val twoPi = (2.0 * Math.PI).toFloat()
        while (v > pi) v -= twoPi
        while (v < -pi) v += twoPi
        return v
    }
}
