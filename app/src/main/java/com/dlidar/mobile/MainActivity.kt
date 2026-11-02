package com.dlidar.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dlidar.mobile.presentation.theme.DLidarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DLidarApplication).container
        setContent {
            DLidarTheme { AppRoot(container) }
        }
    }
}
