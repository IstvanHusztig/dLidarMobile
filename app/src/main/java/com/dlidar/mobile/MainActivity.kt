package com.dlidar.mobile

import android.graphics.Color
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
        enableEdgeToEdge()
        val container = (application as DLidarApplication).container
        setContent {
            var chosen by remember { mutableStateOf(container.themeSetting.dark) }
            val dark = chosen ?: isSystemInDarkTheme()
            // The system bar icons follow the app's theme, not the phone's.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
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
}
