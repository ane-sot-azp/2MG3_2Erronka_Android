package com.example.osislogin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.osislogin.data.AppDatabase
import com.example.osislogin.util.SessionManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val database = remember { AppDatabase.getDatabase(applicationContext) }
            val sessionManager = remember { SessionManager(applicationContext) }
            val primaryBlue = remember { Color(0xFF1D345C) }
            val secondaryOrange = remember { Color(0xFFF3863A) }
            val colorScheme =
                if (isSystemInDarkTheme()) {
                    darkColorScheme(
                        primary = primaryBlue,
                        secondary = secondaryOrange,
                        tertiary = secondaryOrange
                    )
                } else {
                    lightColorScheme(
                        primary = primaryBlue,
                        secondary = secondaryOrange,
                        tertiary = secondaryOrange
                    )
                }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                            database = database,
                            sessionManager = sessionManager,
                            startDestination = Route.Login.route
                    )
                }
            }
        }
    }
}
