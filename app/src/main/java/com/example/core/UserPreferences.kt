package com.example.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.SoraThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.soraDataStore: DataStore<Preferences> by preferencesDataStore(name = "sora_prefs")

data class SoraSettings(
    val themeMode: SoraThemeMode = SoraThemeMode.DARK,
    val pomodoroWorkMinutes: Int = 25,
    val pomodoroShortBreakMinutes: Int = 5,
    val pomodoroLongBreakMinutes: Int = 15,
    val ambientFocusSound: Boolean = false
)

class UserPreferences(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val POMODORO_WORK = intPreferencesKey("pomodoro_work")
        val POMODORO_SHORT = intPreferencesKey("pomodoro_short")
        val POMODORO_LONG = intPreferencesKey("pomodoro_long")
        val AMBIENT_SOUND = booleanPreferencesKey("ambient_sound")
    }

    val settings: Flow<SoraSettings> = context.soraDataStore.data.map { prefs ->
        val modeName = prefs[Keys.THEME_MODE] ?: SoraThemeMode.DARK.name
        val mode = SoraThemeMode.entries.firstOrNull { it.name == modeName } ?: SoraThemeMode.DARK
        SoraSettings(
            themeMode = mode,
            pomodoroWorkMinutes = prefs[Keys.POMODORO_WORK] ?: 25,
            pomodoroShortBreakMinutes = prefs[Keys.POMODORO_SHORT] ?: 5,
            pomodoroLongBreakMinutes = prefs[Keys.POMODORO_LONG] ?: 15,
            ambientFocusSound = prefs[Keys.AMBIENT_SOUND] ?: false
        )
    }

    suspend fun setThemeMode(mode: SoraThemeMode) {
        context.soraDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setPomodoroDurations(work: Int, shortBreak: Int, longBreak: Int) {
        context.soraDataStore.edit {
            it[Keys.POMODORO_WORK] = work.coerceIn(5, 120)
            it[Keys.POMODORO_SHORT] = shortBreak.coerceIn(1, 30)
            it[Keys.POMODORO_LONG] = longBreak.coerceIn(5, 60)
        }
    }

    suspend fun setAmbientSound(enabled: Boolean) {
        context.soraDataStore.edit { it[Keys.AMBIENT_SOUND] = enabled }
    }
}
