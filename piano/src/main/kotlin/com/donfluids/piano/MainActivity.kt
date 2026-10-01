package com.donfluids.piano

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    private var output: AudioOutput? = null
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF5EEAD4),
                    onPrimary = Color(0xFF00332E),
                    background = Color(0xFF15171A),
                    onBackground = Color(0xFFECEAE4),
                    surface = Color(0xFF1E2124),
                    onSurface = Color(0xFFECEAE4),
                    surfaceVariant = Color(0xFF2B2F33),
                    onSurfaceVariant = Color(0xFFA8A39B),
                ),
            ) {
                PianoScreen(synth = { output?.synth }, error = error)
            }
        }
    }

    /** Audio runs only while the activity is in front; nothing plays from the background. */
    override fun onStart() {
        super.onStart()
        error = null
        output = AudioOutput(this) { message -> error = message }.also { it.start() }
    }

    override fun onStop() {
        output?.stop()
        output = null
        super.onStop()
    }
}
