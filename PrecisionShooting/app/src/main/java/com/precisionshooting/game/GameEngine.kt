package com.precisionshooting.game

import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

enum class GamePhase { READY, COUNTDOWN, PLAYING, GAME_OVER }
enum class Movement { LINEAR, STRAFE, DRIFT }

data class Target(
    val id: Long,
    val xDp: Float,
    val yDp: Float,
    val radiusDp: Float,
    val vxDpPerSecond: Float,
    val vyDpPerSecond: Float,
    val movement: Movement,
    val ageSeconds: Float = 0f,
    val strafeCenterYDp: Float = yDp,
    val strafeAmplitudeDp: Float = 0f,
    val strafeAngularFrequency: Float = 0f,
    val points: Int = 10
) {
    fun update(deltaSeconds: Float): Target {
        if (deltaSeconds <= 0f || !deltaSeconds.isFinite()) return this
        val newAge = ageSeconds + deltaSeconds
        val newX = xDp + vxDpPerSecond * deltaSeconds
        val newY = when (movement) {
            Movement.STRAFE -> strafeCenterYDp +
                sin(newAge * strafeAngularFrequency) * strafeAmplitudeDp
            Movement.LINEAR, Movement.DRIFT -> yDp + vyDpPerSecond * deltaSeconds
        }
        return copy(xDp = newX, yDp = newY, ageSeconds = newAge)
    }
}

data class HitEffect(
    val xDp: Float,
    val yDp: Float,
    val ageSeconds: Float = 0f
)

data class GameState(
    val phase: GamePhase = GamePhase.READY,
    val score: Int = 0,
    val hits: Int = 0,
    val misses: Int = 0,
    val bestScore: Int = 0,
    val remainingSeconds: Float = 30f,
    val countdownSeconds: Float = 3f,
    val targets: List<Target> = emptyList(),
    val effects: List<HitEffect> = emptyList()
)

data class GameConfig(
    val roundSeconds: Double = 30.0,
    val countdownSeconds: Double = 3.0,
    val maxPhysicsDeltaSeconds: Float = 0.05f,
    val maxTargets: Int = 6,
    val startSpawnIntervalSeconds: Double = 1.15,
    val endSpawnIntervalSeconds: Double = 0.55,
    val minSpeedDpPerSecond: Float = 150f,
    val maxSpeedDpPerSecond: Float = 250f,
    val hudSafeZoneDp: Float = 120f,
    val bottomSafeZoneDp: Float = 50f
)

class GameEngine(
    private val config: GameConfig = GameConfig(),
    private val random: Random = Random.Default
) {
    var state: GameState = GameState(
        remainingSeconds = config.roundSeconds.toFloat(),
        countdownSeconds = config.countdownSeconds.toFloat()
    )
        private set

    private var widthDp = 0f
    private var heightDp = 0f
    private var countdownRemaining = config.countdownSeconds
    private var roundRemaining = config.roundSeconds
    private var spawnAccumulator = 0.0
    private var nextId = 1L

    fun setPlayArea(widthDp: Float, heightDp: Float) {
        this.widthDp = widthDp.coerceAtLeast(0f)
        this.heightDp = heightDp.coerceAtLeast(0f)
    }

    fun setBestScore(value: Int) {
        if (value > state.bestScore) state = state.copy(bestScore = value)
    }

    fun startRound() {
        countdownRemaining = config.countdownSeconds
        roundRemaining = config.roundSeconds
        spawnAccumulator = 0.0
        state = state.copy(
            phase = GamePhase.COUNTDOWN,
            score = 0,
            hits = 0,
            misses = 0,
            remainingSeconds = config.roundSeconds.toFloat(),
            countdownSeconds = config.countdownSeconds.toFloat(),
            targets = emptyList(),
            effects = emptyList()
        )
    }

    fun update(realDeltaSeconds: Float) {
        if (realDeltaSeconds <= 0f || !realDeltaSeconds.isFinite()) return
        val physicsDelta = realDeltaSeconds.coerceAtMost(config.maxPhysicsDeltaSeconds)

        when (state.phase) {
            GamePhase.COUNTDOWN -> {
                countdownRemaining = (countdownRemaining - realDeltaSeconds).coerceAtLeast(0.0)
                if (countdownRemaining <= 0.0) {
                    state = state.copy(phase = GamePhase.PLAYING, countdownSeconds = 0f)
                } else {
                    state = state.copy(countdownSeconds = countdownRemaining.toFloat())
                }
            }
            GamePhase.PLAYING -> updatePlaying(realDeltaSeconds, physicsDelta)
            GamePhase.READY, GamePhase.GAME_OVER -> Unit
        }

        if (state.effects.isNotEmpty()) {
            state = state.copy(
                effects = state.effects
                    .map { it.copy(ageSeconds = it.ageSeconds + realDeltaSeconds) }
                    .filter { it.ageSeconds < 0.22f }
            )
        }
    }

    private fun updatePlaying(realDelta: Float, physicsDelta: Float) {
        roundRemaining = (roundRemaining - realDelta).coerceAtLeast(0.0)
        if (roundRemaining <= 0.0) {
            state = state.copy(
                phase = GamePhase.GAME_OVER,
                remainingSeconds = 0f,
                bestScore = maxOf(state.bestScore, state.score),
                targets = emptyList()
            )
            return
        }

        var targets = state.targets
            .map { it.update(physicsDelta) }
            .filter { isInBounds(it) }

        val progress = (1.0 - roundRemaining / config.roundSeconds).coerceIn(0.0, 1.0)
        val interval = config.startSpawnIntervalSeconds +
            (config.endSpawnIntervalSeconds - config.startSpawnIntervalSeconds) * progress
        spawnAccumulator += realDelta

        while (spawnAccumulator >= interval && targets.size < config.maxTargets) {
            spawnAccumulator -= interval
            spawnTarget(progress.toFloat())?.let { targets = targets + it } ?: break
        }

        state = state.copy(
            remainingSeconds = roundRemaining.toFloat(),
            targets = targets
        )
    }

    fun shoot(xDp: Float, yDp: Float) {
        if (state.phase != GamePhase.PLAYING) return

        val hit = state.targets
            .asReversed()
            .firstOrNull { target ->
                hypot((xDp - target.xDp).toDouble(), (yDp - target.yDp).toDouble()) <= target.radiusDp
            }

        if (hit == null) {
            state = state.copy(misses = state.misses + 1)
        } else {
            val newScore = state.score + hit.points
            state = state.copy(
                score = newScore,
                hits = state.hits + 1,
                bestScore = maxOf(state.bestScore, newScore),
                targets = state.targets.filterNot { it.id == hit.id },
                effects = state.effects + HitEffect(hit.xDp, hit.yDp)
            )
        }
    }

    private fun spawnTarget(progress: Float): Target? {
        if (widthDp <= 0f || heightDp <= 0f) return null

        val radius = when {
            progress > 0.72f && random.nextFloat() > 0.65f -> 22f
            progress > 0.45f && random.nextFloat() > 0.55f -> 30f
            random.nextBoolean() -> 42f
            else -> 56f
        }
        val minY = config.hudSafeZoneDp + radius
        val maxY = heightDp - config.bottomSafeZoneDp - radius
        if (maxY <= minY) return null

        val fromLeft = random.nextBoolean()
        val x = if (fromLeft) -radius else widthDp + radius
        val speedScale = 1f + progress * 0.6f
        val speed = (config.minSpeedDpPerSecond +
            random.nextFloat() * (config.maxSpeedDpPerSecond - config.minSpeedDpPerSecond)) * speedScale
        val vx = if (fromLeft) speed else -speed
        val y = minY + random.nextFloat() * (maxY - minY)
        val movement = when {
            progress > 0.60f && random.nextFloat() > 0.70f -> Movement.STRAFE
            progress > 0.30f && random.nextFloat() > 0.80f -> Movement.DRIFT
            else -> Movement.LINEAR
        }
        val vy = if (movement == Movement.DRIFT) (random.nextFloat() - 0.5f) * 50f * speedScale else 0f
        val points = when {
            radius <= 22f -> 30
            radius <= 30f -> 20
            radius <= 42f -> 15
            else -> 10
        } + if (speedScale > 1.35f) 5 else 0

        return Target(
            id = nextId++,
            xDp = x,
            yDp = y,
            radiusDp = radius,
            vxDpPerSecond = vx,
            vyDpPerSecond = vy,
            movement = movement,
            strafeCenterYDp = y,
            strafeAmplitudeDp = if (movement == Movement.STRAFE) 24f else 0f,
            strafeAngularFrequency = if (movement == Movement.STRAFE) 5f else 0f,
            points = points
        )
    }

    private fun isInBounds(target: Target): Boolean {
        val horizontalMargin = target.radiusDp * 2f
        val verticalMargin = target.radiusDp * 2f
        return target.xDp >= -horizontalMargin &&
            target.xDp <= widthDp + horizontalMargin &&
            target.yDp >= -verticalMargin &&
            target.yDp <= heightDp + verticalMargin
    }
}
