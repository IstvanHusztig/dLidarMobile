package com.dlidar.mobile

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dlidar.mobile.presentation.theme.DLidarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as DLidarApplication).container
        // Before the first frame, so a theme other than the phone's does not flash its colours.
        applyWindowTheme(container.themeSetting.dark ?: systemDark())
        setContent {
            var chosen by remember { mutableStateOf(container.themeSetting.dark) }
            val dark = chosen ?: isSystemInDarkTheme()
            LaunchedEffect(dark) { applyWindowTheme(dark) }
            // The Surface gives every screen, the debug ones too, the theme's background and text colour.
            DLidarTheme(dark = dark) {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(
                        container,
                        darkTheme = dark,
                        onToggleTheme = {
                            chosen = !dark
                            container.themeSetting.dark = !dark
                        },
                    )
                }
            }
        }
    }

    private fun systemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The window behind Compose and the system bar icons follow the app's theme, not the phone's. */
    private fun applyWindowTheme(dark: Boolean) {
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            // Android 8.0 has no dark navigation bar icons; there the bar gets a dark scrim instead.
            SystemBarStyle.light(Color.TRANSPARENT, DARK_SCRIM)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(ColorDrawable(if (dark) Color.BLACK else Color.WHITE))
    }

    private companion object {
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
