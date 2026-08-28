package com.gimica.mergeblast.config

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class BotConfig private constructor(
    val targetPackage: String = "com.gimica.mergeblast",
    val processIntervalMs: Long = 200,
    val minMoveIntervalMs: Long = 150,
    val enableLookahead: Boolean = true,
    val lookaheadDepth: Int = 3,
    val mergeWeight: Int = 100,
    val chainBonus: Int = 500,
    val spaceWeight: Int = 50,
    val missionWeight: Int = 2000,
    val highValueBonus: Int = 10,
    val cornerBonus: Int = 20,
    val monotonicityWeight: Int = 30,
    val smoothnessWeight: Int = 20,
    val tapDurationMs: Long = 50,
    val swipeDurationMs: Long = 300,
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 100,
    val enableHumanLikeTiming: Boolean = true,
    val enableDebugLogging: Boolean = false
) {
    companion object {
        private const val PREFS_NAME = "bot_config"
        private const val KEY_CONFIG_JSON = "config_json"
        private val gson = Gson()

        fun load(context: Context): BotConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_CONFIG_JSON, "") ?: ""
            return if (json.isNotEmpty()) {
                try {
                    gson.fromJson(json, BotConfig::class.java)
                } catch (e: Exception) {
                    BotConfig()
                }
            } else BotConfig()
        }

        fun save(context: Context, config: BotConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = gson.toJson(config)
            prefs.edit().putString(KEY_CONFIG_JSON, json).apply()
        }

        fun getDefaults(): BotConfig = BotConfig()
    }

    fun copy(
        targetPackage: String = this.targetPackage,
        processIntervalMs: Long = this.processIntervalMs,
        minMoveIntervalMs: Long = this.minMoveIntervalMs,
        enableLookahead: Boolean = this.enableLookahead,
        lookaheadDepth: Int = this.lookaheadDepth,
        mergeWeight: Int = this.mergeWeight,
        chainBonus: Int = this.chainBonus,
        spaceWeight: Int = this.spaceWeight,
        missionWeight: Int = this.missionWeight,
        highValueBonus: Int = this.highValueBonus,
        cornerBonus: Int = this.cornerBonus,
        monotonicityWeight: Int = this.monotonicityWeight,
        smoothnessWeight: Int = this.smoothnessWeight,
        tapDurationMs: Long = this.tapDurationMs,
        swipeDurationMs: Long = this.swipeDurationMs,
        maxRetries: Int = this.maxRetries,
        retryDelayMs: Long = this.retryDelayMs,
        enableHumanLikeTiming: Boolean = this.enableHumanLikeTiming,
        enableDebugLogging: Boolean = this.enableDebugLogging
    ): BotConfig = BotConfig(
        targetPackage = targetPackage,
        processIntervalMs = processIntervalMs,
        minMoveIntervalMs = minMoveIntervalMs,
        enableLookahead = enableLookahead,
        lookaheadDepth = lookaheadDepth,
        mergeWeight = mergeWeight,
        chainBonus = chainBonus,
        spaceWeight = spaceWeight,
        missionWeight = missionWeight,
        highValueBonus = highValueBonus,
        cornerBonus = cornerBonus,
        monotonicityWeight = monotonicityWeight,
        smoothnessWeight = smoothnessWeight,
        tapDurationMs = tapDurationMs,
        swipeDurationMs = swipeDurationMs,
        maxRetries = maxRetries,
        retryDelayMs = retryDelayMs,
        enableHumanLikeTiming = enableHumanLikeTiming,
        enableDebugLogging = enableDebugLogging
    )
}

class GameProfile(
    val packageName: String,
    val displayName: String,
    val tileSelectors: TileSelectors,
    val boardSelectors: BoardSelectors,
    val missionSelectors: MissionSelectors,
    val inputConfig: InputConfig,
    val heuristics: HeuristicWeights
) {
    data class TileSelectors(
        val numberPattern: String = "^\\d+$",
        val contentDescPatterns: List<String> = listOf("(?i)tile.*\\d+", "(?i)cell.*\\d+", "(?i)number.*\\d+"),
        val viewIdKeywords: List<String> = listOf("tile", "cell", "number", "block"),
        val classNames: List<String> = listOf("textview", "button", "imageview", "view"),
        val minTileSize: Int = 20,
        val maxTileSize: Int = 500
    )

    data class BoardSelectors(
        val gridContainerKeywords: List<String> = listOf("grid", "board", "game", "playfield"),
        val scoreKeywords: List<String> = listOf("score", "points"),
        val levelKeywords: List<String> = listOf("level", "stage", "wave"),
        val gridLayoutClasses: List<String> = listOf("gridlayout", "gridview", "recyclerview", "linearlayout")
    )

    data class MissionSelectors(
        val keywords: List<String> = listOf("mission", "objective", "target", "goal", "task"),
        val mergeCountIndex: Int = 0,
        val mergeCurrentIndex: Int = 1,
        val existTargetIndex: Int = 2,
        val existValueIndex: Int = 3
    )

    data class InputConfig(
        val tapDurationMs: Long = 50,
        val swipeDurationMs: Long = 300,
        val enableHumanLikeTiming: Boolean = true,
        val minTapIntervalMs: Long = 50,
        val minSwipeIntervalMs: Long = 100
    )

    data class HeuristicWeights(
        val mergeWeight: Int = 100,
        val chainBonus: Int = 500,
        val spaceWeight: Int = 50,
        val missionWeight: Int = 2000,
        val highValueBonus: Int = 10,
        val cornerBonus: Int = 20,
        val monotonicityWeight: Int = 30,
        val smoothnessWeight: Int = 20,
        val lookaheadDepth: Int = 3
    )

    companion object {
        private val profiles = mutableMapOf<String, GameProfile>()

        fun register(profile: GameProfile) {
            profiles[profile.packageName] = profile
        }

        fun get(packageName: String): GameProfile? = profiles[packageName]

        fun getOrDefault(packageName: String): GameProfile {
            return profiles[packageName] ?: DEFAULT_PROFILE
        }

        val DEFAULT_PROFILE = GameProfile(
            packageName = "default",
            displayName = "Default Profile",
            tileSelectors = TileSelectors(),
            boardSelectors = BoardSelectors(),
            missionSelectors = MissionSelectors(),
            inputConfig = InputConfig(),
            heuristics = HeuristicWeights()
        )

        init {
            register(GameProfile(
                packageName = "com.gimica.mergeblast",
                displayName = "Merge Blast",
                tileSelectors = TileSelectors(
                    viewIdKeywords = listOf("tile", "cell", "number", "block", "piece"),
                    classNames = listOf("textview", "button", "imageview", "view", "framelayout")
                ),
                boardSelectors = BoardSelectors(
                    gridContainerKeywords = listOf("grid", "board", "game", "playfield", "container"),
                    scoreKeywords = listOf("score", "points", "scorevalue"),
                    levelKeywords = listOf("level", "stage", "wave", "levelnum")
                ),
                missionSelectors = MissionSelectors(
                    keywords = listOf("mission", "objective", "target", "goal", "task", "quest")
                ),
                inputConfig = InputConfig(
                    tapDurationMs = 50,
                    swipeDurationMs = 300
                ),
                heuristics = HeuristicWeights(
                    mergeWeight = 100,
                    chainBonus = 500,
                    spaceWeight = 50,
                    missionWeight = 2000,
                    lookaheadDepth = 3
                )
            ))
        }
    }
}