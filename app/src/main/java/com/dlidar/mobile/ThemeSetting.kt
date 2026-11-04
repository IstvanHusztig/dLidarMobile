package com.dlidar.mobile

import android.content.Context
import androidx.core.content.edit

/** The user's choice of a light or dark theme; null follows the phone. Kept on the phone. */
class ThemeSetting(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var dark: Boolean?
        get() = if (prefs.contains(KEY_DARK)) prefs.getBoolean(KEY_DARK, true) else null
        set(value) = prefs.edit { if (value == null) remove(KEY_DARK) else putBoolean(KEY_DARK, value) }

    private companion object {
        const val KEY_DARK = "dark_theme"
    }
}
