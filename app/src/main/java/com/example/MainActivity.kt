package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.core.configureAppCheck
import com.example.ui.SoraApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must configure App Check before any Firebase AI Logic / ViewModel usage
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            SoraApp()
        }
    }
}
