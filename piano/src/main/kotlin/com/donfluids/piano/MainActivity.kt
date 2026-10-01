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
    private val settings by lazy { Settings(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF5EEAD4),
                    onPrimary = Color(0xFF00332E),
                    primaryContainer = Color(0xFF134E4A),
                    onPrimaryContainer = Color(0xFFCCFBF1),
                    tertiary = Color(0xFFF2997F),
                    background = Color(0xFF1A1816),
                    onBackground = Color(0xFFEDE8DF),
                    surface = Color(0xFF1A1816),
                    onSurface = Color(0xFFEDE8DF),
                    surfaceContainer = Color(0xFF242120),
                    surfaceContainerHigh = Color(0xFF2C2826),
                    surfaceContainerLowest = Color(0xFF121110),
                    surfaceVariant = Color(0xFF34302D),
                    onSurfaceVariant = Color(0xFFA9A299),
                    outline = Color(0xFF5A544E),
                    outlineVariant = Color(0xFF34302D),
                ),
            ) {
                PianoScreen(output = { output }, settings = settings, error = error)
            }
        }
    }

    /** Audio runs only while the activity is in front; nothing plays from the background. */
    override fun onStart() {
        super.onStart()
        error = null
        output = AudioOutput(this) { message -> error = message }.also {
            it.synth.tone = settings.tone
            it.synth.sustain = settings.sustain
            it.synth.volume = settings.volume
            it.reverb.enabled = settings.reverb
            it.metronome.bpm = settings.bpm
            it.metronome.beatsPerBar = settings.beatsPerBar
            it.start()
        }
    }

    override fun onStop() {
        output?.stop()
        output = null
        super.onStop()
    }
}
