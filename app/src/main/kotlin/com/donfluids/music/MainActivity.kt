package com.donfluids.music

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.donfluids.music.ui.AnalyzerScreen
import com.donfluids.music.ui.theme.LiveKeyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AnalyzerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiveKeyTheme {
                AnalyzerScreen(viewModel)
            }
        }
    }
}
