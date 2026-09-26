package com.clipgenius.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.clipgenius.ai.ui.navigation.ClipGeniusNavHost
import com.clipgenius.ai.ui.theme.ClipGeniusTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClipGeniusTheme {
                val navController = rememberNavController()
                ClipGeniusNavHost(navController = navController)
            }
        }
    }
}
