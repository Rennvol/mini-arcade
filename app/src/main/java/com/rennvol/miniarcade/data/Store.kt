package com.rennvol.miniarcade.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "mini_arcade")

object Prefs {
    val TETRIS_HI = intPreferencesKey("tetris_hi")
    val S2048_BEST = intPreferencesKey("s2048_best")
    val MINES_BEST = intPreferencesKey("mines_best_time")
    val SOLITAIRE_WINS = intPreferencesKey("solitaire_wins")
}

fun Context.tetrisHiFlow() = dataStore.data.map { it[Prefs.TETRIS_HI] ?: 0 }
fun Context.best2048Flow() = dataStore.data.map { it[Prefs.S2048_BEST] ?: 0 }
suspend fun Context.updateHighScore(key: Preferences.Key<Int>, value: Int) {
    dataStore.edit { p ->
        val cur = p[key] ?: 0
        if (value > cur) p[key] = value
    }
}
