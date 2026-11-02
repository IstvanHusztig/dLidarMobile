package com.dlidar.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.dlidar.mobile.presentation.theme.DLidarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DLidarApplication).container
        setContent {
            // The Surface gives every screen, the debug ones too, the theme's background and text colour.
            DLidarTheme { Surface(Modifier.fillMaxSize()) { AppRoot(container) } }
        }
    }
}
