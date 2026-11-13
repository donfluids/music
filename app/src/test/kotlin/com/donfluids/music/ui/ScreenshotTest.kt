package com.donfluids.music.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.donfluids.music.AnalyzerViewModel
import com.donfluids.music.UiState
import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.dsp.BeatThisModel
import com.donfluids.music.dsp.KeyLevelTracker
import com.donfluids.music.dsp.SungNote
import com.donfluids.music.history.SongRecord
import com.donfluids.music.music.ChordLabel
import com.donfluids.music.music.ChordType
import com.donfluids.music.music.ChordSegment
import com.donfluids.music.music.Key
import com.donfluids.music.music.Mode
import com.donfluids.music.music.NoteNaming
import com.donfluids.music.ui.theme.LiveKeyTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the screen in its main states to build/screenshots, for review. Not a pass/fail test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Before fun record() { System.setProperty("roborazzi.test.record", "true") }

    private val notes = List(12) { SungNote(64 + it % 5, it * 0.5, 0.45) }
    private fun chord(root: Int, type: ChordType = ChordType.MAJOR) = ChordLabel(root, type)

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shoot(name: String, state: UiState, dark: Boolean = false) {
        val vm = AnalyzerViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.showStateForScreenshot(state)
        // The beat dots and the tap pulse animate for ever: step the clock by hand instead of
        // waiting for an idle moment that never comes.
        compose.mainClock.autoAdvance = false
        compose.setContent { LiveKeyTheme(dark = dark) { AnalyzerScreen(vm) } }
        compose.mainClock.advanceTimeBy(400)
        // A dialog is a window of its own, so the whole screen is captured when one is open.
        if (state.showSettings) captureScreenRoboImage("build/screenshots/$name.png")
        else compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private val eMajor = Key(4, Mode.MAJOR)
    private val voicesSettled = UiState(
        listening = true, preset = AnalysisPreset.VOICES, tapBpm = 96f, tapAnchorNanos = System.nanoTime(), tapCount = 6,
        analysis = AnalysisResult(
            secondsHeard = 40f, level = 0.5f, key = eMajor, displayKey = eMajor, keyNow = eMajor, keyRangeLow = eMajor, keyRangeHigh = eMajor,
            keyConfidence = 0.8f, displayKeyConfidence = 0.8f, keyHeldSeconds = 20f, pairDecided = true, modeDecided = true,
            tuningCents = 12f, tuningConfidence = 0.6f, sungNotes = notes, keyFromMelody = true,
            currentChord = chord(9), chordSegments = listOf(ChordSegment(chord(4), 30f, 34f), ChordSegment(chord(9), 34f, 36f), ChordSegment(chord(11), 36f, 38f), ChordSegment(chord(9), 38f, 40f)),
        ),
    )

    @Test fun voicesSettledLight() = shoot("1-voices-settled", voicesSettled)

    @Test fun voicesSettledDark() = shoot("5-voices-settled-dark", voicesSettled, dark = true)

    @Test fun voicesTapping() = shoot("6-voices-tapping", voicesSettled.copy(tapBpm = 92f, tapCount = 2))

    @Test fun fixedDoAt442() = shoot("7-voices-do-re-mi-442", voicesSettled.copy(naming = NoteNaming.FIXED_DO, referenceHz = 442, transpose = 0))

    @Test fun settings() = shoot("8-settings", voicesSettled.copy(showSettings = true, referenceHz = 442))

    @Test fun history() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        shoot("9-history", UiState(
            showHistory = true,
            history = listOf(
                SongRecord(now - 600_000, Key(7, Mode.MAJOR), bpm = 72, bpmTapped = true, seconds = 245, presetId = "voices"),
                SongRecord(now - 1_500_000, Key(4, Mode.MINOR), transpose = 2, bpm = 124, meter = "4/4", seconds = 198, presetId = "band"),
                SongRecord(now - day, Key(10, Mode.MAJOR), bpm = 96, meter = "6/8", seconds = 312, presetId = "band"),
                SongRecord(now - day - 900_000, Key(2, Mode.MAJOR), seconds = 180, presetId = "voices"),
            ),
        ))
    }

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun idle() = shoot("0-idle", UiState(preset = AnalysisPreset.VOICES))

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun idleBand() = shoot("0-idle-band", UiState(preset = AnalysisPreset.BAND))

    /** A 360 dp phone, the narrowest common width, with the singing and chords from a real test. */
    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun smallPhone() = shoot("10-small-phone", smallPhoneState)

    private val smallPhoneState: UiState get() {
        val bb = Key(10, Mode.MAJOR)
        return UiState(
            listening = true, preset = AnalysisPreset.VOICES, tapBpm = 108f, tapAnchorNanos = System.nanoTime(), tapCount = 8,
            analysis = AnalysisResult(
                secondsHeard = 60f, level = 0.6f, key = bb, displayKey = bb, keyNow = bb, keyRangeLow = bb, keyRangeHigh = bb,
                keyConfidence = 0.4f, displayKeyConfidence = 0.4f, keyHeldSeconds = 4f,
                tuningCents = 11f, tuningConfidence = 0.5f, singerSpreadCents = 36f, sungNotes = notes,
                chordSegments = listOf(chord(2, ChordType.MAJOR_SEVENTH), chord(5, ChordType.MINOR), chord(2, ChordType.MINOR), chord(5), chord(2, ChordType.MAJOR_SEVENTH), chord(0, ChordType.MINOR), chord(10))
                    .mapIndexed { i, c -> ChordSegment(c, i * 2f, i * 2f + 2f) },
            ),
        )
    }

    /** Tall enough to show the whole page in one picture. */
    @Test @Config(qualifiers = "w360dp-h1700dp-xhdpi")
    fun limits() = shoot("12-what-to-expect", UiState(showLimits = true))

    @Test @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun smallPhoneSettings() = shoot("11-small-phone-settings", voicesSettled.copy(showSettings = true, referenceHz = 442, nashville = true))

    @Test fun voicesWandering() {
        val cs = Key(1, Mode.MAJOR)
        shoot("2-voices-wandering", UiState(
            listening = true, preset = AnalysisPreset.VOICES,
            analysis = AnalysisResult(
                secondsHeard = 70f, level = 0.4f, key = cs, displayKey = cs, keyNow = Key(2, Mode.MAJOR), keyRangeLow = Key(0, Mode.MAJOR), keyRangeHigh = Key(2, Mode.MAJOR),
                keyMove = KeyLevelTracker.Move.RISING, keyConfidence = 0.6f, displayKeyConfidence = 0.6f, keyHeldSeconds = 30f, pairDecided = true, modeDecided = true,
                tuningCents = -30f, tuningConfidence = 0.5f, sungNotes = notes, keyFromMelody = true, currentChord = chord(2),
            ),
        ))
    }

    @Test fun voicesEarly() {
        val f = Key(5, Mode.MAJOR)
        shoot("3-voices-early", UiState(
            listening = true, preset = AnalysisPreset.VOICES,
            analysis = AnalysisResult(
                secondsHeard = 6f, level = 0.5f, key = f, displayKey = f, keyNow = f, keyRangeLow = f, keyRangeHigh = f,
                keyConfidence = 0.3f, displayKeyConfidence = 0.3f, keyHeldSeconds = 2f, pairDecided = false, modeDecided = false,
            ),
        ))
    }

    /** A band in G, with Basic Pitch hearing the notes and Beat This! following the beat (as on the iPhone). */
    private val bandState: UiState get() {
        val g = Key(7, Mode.MAJOR)
        return UiState(
            listening = true, preset = AnalysisPreset.BAND,
            analysis = AnalysisResult(
                secondsHeard = 40f, level = 0.6f, key = g, displayKey = g, keyNow = g, keyRangeLow = g, keyRangeHigh = g,
                keyConfidence = 0.8f, displayKeyConfidence = 0.8f, keyHeldSeconds = 25f, pairDecided = true, modeDecided = true,
                bpm = 120f, tempoStrength = 0.6f, tempoLocked = true, beatPeriodMillis = 500f, lastBeatAtNanos = System.nanoTime(),
                meterLabel = "4/4", beatsPerBar = 4, meterConfidence = 0.7f, currentChord = chord(2),
                tuningConfidence = 0.6f, tuningCents = 4f,
                chroma = listOf(0.3f, 0.05f, 0.9f, 0.1f, 0.4f, 0.2f, 0.15f, 1.0f, 0.05f, 0.5f, 0.05f, 0.6f),
                songKey = g,
                chordSegments = listOf(ChordSegment(chord(7), 30f, 32f), ChordSegment(chord(4, ChordType.MINOR), 32f, 34f), ChordSegment(chord(0), 34f, 36f), ChordSegment(chord(2), 36f, 40f)),
                notesModel = "Basic Pitch", notesMillisPerSecond = 30f,
                beatModel = BeatThisModel.SMALL.title, beatMillisPerSecond = 45f, beatEverySeconds = 1,
            ),
        )
    }

    @Test fun band() = shoot("4-band", bandState)

    /** The Details fold open with the raw timings (as after a long press on its heading), scrolled to the bottom. */
    @Test fun bandDetails() {
        ScreenshotHooks.detailsOpen = true
        try { shoot("13-band-details", bandState) } finally { ScreenshotHooks.detailsOpen = false }
    }
}
