package dev.hadapkar.carport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hadapkar.carport.ui.CarPortApp
import dev.hadapkar.carport.ui.MainViewModel
import dev.hadapkar.carport.ui.theme.CarPortTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val expressive by viewModel.expressiveDesign.collectAsStateWithLifecycle()
            CarPortTheme(expressive = expressive) {
                CarPortApp(viewModel)
            }
        }
    }
}
