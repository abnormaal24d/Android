package com.precisionshooting.game

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.gamePreferences by preferencesDataStore(name = "game_preferences")

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = GameEngine()
    private val preferences = application.applicationContext.gamePreferences
    private val bestScoreKey = intPreferencesKey("best_score")
    private val _state = MutableStateFlow(engine.state)

    val state: StateFlow<GameState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.data.map { it[bestScoreKey] ?: 0 }.collect { persisted ->
                engine.setBestScore(persisted)
                publish()
            }
        }
    }

    fun setPlayArea(widthDp: Float, heightDp: Float) {
        engine.setPlayArea(widthDp, heightDp)
    }

    fun startRound() {
        engine.startRound()
        publish()
    }

    fun update(realDeltaSeconds: Float) {
        val before = engine.state.bestScore
        engine.update(realDeltaSeconds)
        publish()
        persistIfHigher(before)
    }

    fun shoot(xDp: Float, yDp: Float) {
        val before = engine.state.bestScore
        engine.shoot(xDp, yDp)
        publish()
        persistIfHigher(before)
    }

    private fun persistIfHigher(previousBest: Int) {
        val currentBest = engine.state.bestScore
        if (currentBest <= previousBest) return
        viewModelScope.launch {
            preferences.edit { prefs ->
                val persisted = prefs[bestScoreKey] ?: 0
                if (currentBest > persisted) prefs[bestScoreKey] = currentBest
            }
        }
    }

    private fun publish() {
        _state.value = engine.state
    }
}
