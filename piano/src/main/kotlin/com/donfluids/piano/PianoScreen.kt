package com.donfluids.piano

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donfluids.music.dsp.AnalysisResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min

private val Ivory = Color(0xFFF3EEE2)
private val IvoryShade = Color(0xFFE2DBCB)
private val IvoryDim = Color(0xFFB9B2A4)
private val Ebony = Color(0xFF1C1A18)
private val EbonyTop = Color(0xFF3A3632)
private val Felt = Color(0xFF6E2A2A)
private val Teal = Color(0xFF5EEAD4)
private val TealGlow = Color(0xFF2DD4BF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PianoScreen(output: () -> AudioOutput?, settings: Settings, error: String?) {
    /** Window position in white keys from C1, animatable so the keyboard can snap and glide. */
    val scroll = remember { Animatable(settings.scroll) }
    val scope = rememberCoroutineScope()
    var sustain by remember { mutableStateOf(settings.sustain) }
    var showNames by remember { mutableStateOf(settings.showNames) }
    var tone by remember { mutableStateOf(settings.tone) }
    var scale by remember { mutableStateOf(settings.scale) }
    var bpm by remember { mutableStateOf(settings.bpm) }
    var beatsPerBar by remember { mutableStateOf(settings.beatsPerBar) }
    var metronomeOn by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(settings.volume) }
    var reverb by remember { mutableStateOf(settings.reverb) }
    var haptics by remember { mutableStateOf(settings.haptics) }
    var scaleDialog by remember { mutableStateOf(false) }
    var tempoDialog by remember { mutableStateOf(false) }
    var settingsSheet by remember { mutableStateOf(false) }
    var bandDialog by remember { mutableStateOf(false) }
    var bandOn by remember { mutableStateOf(settings.bandOn) }
    var bandStyle by remember { mutableStateOf(settings.bandStyle) }
    var bandPlaying by remember { mutableStateOf(false) }
    var bandVolume by remember { mutableStateOf(settings.bandVolume) }
    var bandChord by remember { mutableStateOf<BandChord?>(null) }
    val splitMidi = 60
    val context = LocalContext.current
    val listener = remember { SongListener(context.applicationContext) }
    var listening by remember { mutableStateOf(false) }
    var followChords by remember { mutableStateOf(settings.followChords) }
    var autoStart by remember { mutableStateOf(settings.autoStart) }
    var entering by remember { mutableStateOf(false) }
    var matchedStyle by remember { mutableStateOf<Style?>(null) }
    val heard by listener.result.collectAsStateWithLifecycle()
    val micError by listener.error.collectAsStateWithLifecycle()
    val visibleWhite = 14
    val synth: () -> PianoSynth? = { output()?.synth }
    val chordHeld = remember { mutableStateMapOf<Int, List<Int>>() }
    // The audio engine is recreated whenever the activity returns to the front; keep it in step.
    val engine = output()
    LaunchedEffect(engine) {
        engine?.metronome?.enabled = metronomeOn && !bandPlaying
        engine?.band?.let { it.style = bandStyle; it.bpm = bpm; it.volume = bandVolume; it.playing = bandPlaying; bandChord?.let { c -> it.setChord(c) } }
    }
    fun setBandChord(c: BandChord?) {
        if (c == null) return
        bandChord = c
        output()?.band?.setChord(c)
        // Sync start: the first chord starts the band if it is on but idle.
        if (bandOn && !bandPlaying) { bandPlaying = true; output()?.band?.playing = true; output()?.metronome?.enabled = false }
    }
    fun setBandPlaying(on: Boolean) {
        bandPlaying = on
        output()?.band?.playing = on
        output()?.metronome?.enabled = metronomeOn && !on
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; output()?.metronome?.enabled = false; output()?.band?.playing = false; listener.stop() }
    }
    val micDenied = stringResource(R.string.mic_denied)
    var micMessage by remember { mutableStateOf<String?>(null) }
    fun startListening() {
        listener.start(withBand = bandPlaying)
        listening = listener.running
        matchedStyle = null
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else micMessage = micDenied
    }
    fun toggleListening() {
        if (listening) { listener.stop(); listening = false; return }
        micMessage = null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Apply the match once, when the analyser is first sure; chord-following keeps running.
    LaunchedEffect(heard, listening) {
        if (!listening) return@LaunchedEffect
        val m = SongMatcher.match(heard)
        if (m != null && matchedStyle == null) {
            matchedStyle = m.style
            bandOn = true; settings.bandOn = true
            bandStyle = m.style; settings.bandStyle = m.style; output()?.band?.style = m.style
            bpm = m.bpm; settings.bpm = bpm; output()?.band?.bpm = bpm; output()?.metronome?.bpm = bpm
            m.scale?.let { scale = it; settings.scale = it }
            m.chord?.let { bandChord = it; output()?.band?.setChord(it) }
            if (autoStart && !bandPlaying) {
                // Enter on the singers' next downbeat, in their time.
                val at = SongMatcher.nextDownbeatNanos(heard, System.nanoTime())
                output()?.band?.let { b -> if (at != null) b.startAt(at) else b.playing = true }
                output()?.metronome?.enabled = false
                entering = at != null
                bandPlaying = true
            }
        }
        if (entering && output()?.band?.startAtNanos == 0L) entering = false
        if (followChords && bandOn && bandPlaying) {
            // The band follows the chord the analyser hears, by its underlying triad, but only
            // chords that belong to the key; a stray reading is ignored.
            heard.currentChord?.let { c ->
                val q = if (c.minor) BandChord.Quality.MINOR else BandChord.Quality.MAJOR
                val next = BandChord(c.root, q)
                if (next != bandChord && SongMatcher.followable(next, scale)) { bandChord = next; output()?.band?.setChord(next) }
            }
        }
    }


    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // One control row on a lighter surface: octave strip, tone, scale, metronome, settings.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OctaveStrip(
                scroll = scroll.value, visibleWhite = visibleWhite,
                onMove = { target -> scope.launch { scroll.snapTo(target.coerceIn(0f, (KeyboardLayout.WHITE_COUNT - visibleWhite).toFloat())) } },
                onRelease = { scope.launch { scroll.animateTo(Math.round(scroll.value).toFloat(), tween(120)); settings.scroll = scroll.value } },
                modifier = Modifier.weight(1f),
            )
            SingleChoiceSegmentedButtonRow(Modifier.height(36.dp)) {
                PianoSynth.Tone.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tone == t,
                        onClick = { tone = t; settings.tone = t; synth()?.tone = t },
                        shape = SegmentedButtonDefaults.itemShape(i, PianoSynth.Tone.entries.size),
                        icon = {},
                    ) { Text(t.label, style = MaterialTheme.typography.labelMedium) }
                }
            }
            FilterChip(
                selected = scale != null,
                onClick = { scaleDialog = true },
                label = { Text(scale?.label ?: stringResource(R.string.scale)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
            )
            FilterChip(
                selected = listening,
                onClick = { toggleListening() },
                label = {
                    Text(
                        when {
                            !listening -> stringResource(R.string.listen)
                            matchedStyle != null && entering -> stringResource(R.string.listen_starting, matchedStyle!!.name)
                            matchedStyle != null -> "→ ${matchedStyle!!.name}"
                            heard.bpm != null -> "${heard.key?.name ?: "–"} · ${heard.bpm!!.roundToIntSafe()} · ${heard.meterLabel ?: "–"}"
                            else -> stringResource(R.string.listening)
                        },
                    )
                },
                leadingIcon = { EarIcon(if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, if (listening) heard.level else 0f) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
            )
            FilterChip(
                selected = bandOn,
                onClick = { bandDialog = true },
                label = { Text(if (bandOn) bandStyle.name else stringResource(R.string.band)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
            )
            MetronomePill(
                on = if (bandOn) bandPlaying else metronomeOn, bpm = bpm,
                signature = if (bandOn) bandStyle.label else if (beatsPerBar == 6) "6/8" else "$beatsPerBar/4",
                clock = { if (bandOn) output()?.band?.let { b -> b.beatIndex to b.beatAtNanos } else output()?.metronome?.let { m -> m.beatIndex to m.beatAtNanos } },
                isBand = bandOn,
                onToggle = { if (bandOn) setBandPlaying(!bandPlaying) else { metronomeOn = !metronomeOn; output()?.metronome?.enabled = metronomeOn } },
                onTempoTap = { tempoDialog = true },
                onCycleBeats = {
                    if (!bandOn) {
                        beatsPerBar = when (beatsPerBar) { 2 -> 3; 3 -> 4; 4 -> 6; else -> 2 }
                        settings.beatsPerBar = beatsPerBar; output()?.metronome?.beatsPerBar = beatsPerBar
                    }
                },
            )
            IconButton(onClick = { settingsSheet = true }) {
                GearIcon(MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        (error ?: micError ?: micMessage)?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium) }
        if (listening && matchedStyle == null) {
            Text(stringResource(R.string.listen_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Chord row sits directly above the keys so the eye drops from chord to notes.
        if (bandOn || scale != null) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bandOn) {
                    // The chord the band is playing, large enough to read from the keys.
                    Text(
                        bandChord?.name(flats = scale?.let { it.spell(1).endsWith("b") } ?: false) ?: "–",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (bandPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(96.dp),
                    )
                }
                scale?.let { sc ->
                    // Chords are voiced from the lowest C in view.
                    val octaveC = KeyboardLayout.FIRST_MIDI + 12 * (Math.round(scroll.value) / 7)
                    sc.chords().forEachIndexed { i, chord ->
                        val rootMidi = octaveC + chord.pitchClasses[0] + (if (chord.pitchClasses[0] < sc.tonic) 12 else 0)
                        val notes = chord.pitchClasses.map { pc -> rootMidi + Math.floorMod(pc - chord.pitchClasses[0], 12) }
                        ChordButton(
                            chord, held = chordHeld.containsKey(i),
                            onPress = {
                                chordHeld[i] = chord.pitchClasses
                                if (bandOn) setBandChord(BandChord.recognise(notes)) else notes.forEach { synth()?.noteOn(it, 0.8f) }
                            },
                            onRelease = { chordHeld.remove(i); if (!bandOn) notes.forEach { synth()?.noteOff(it) } },
                        )
                    }
                }
            }
        }
        Keyboard(
            scroll = scroll, visibleWhite = visibleWhite, showNames = showNames, scale = scale,
            litPitchClasses = chordHeld.values.flatten().toSet(),
            synth = synth, haptics = haptics,
            onScrollSettled = { settings.scroll = it },
            chordZoneBelow = if (bandOn) splitMidi else null,
            onChordZone = { held -> setBandChord(BandChord.recognise(held)) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
    if (bandDialog) {
        BandDialog(
            on = bandOn, style = bandStyle,
            onPick = { st ->
                bandOn = true; bandStyle = st; settings.bandOn = true; settings.bandStyle = st
                output()?.band?.style = st
                bpm = st.defaultBpm; settings.bpm = bpm; output()?.band?.bpm = bpm; output()?.metronome?.bpm = bpm
                bandDialog = false
            },
            onOff = { bandOn = false; settings.bandOn = false; setBandPlaying(false); bandDialog = false },
            onDismiss = { bandDialog = false },
        )
    }

    if (scaleDialog) ScaleDialog(current = scale, onPick = { scale = it; settings.scale = it; scaleDialog = false }, onDismiss = { scaleDialog = false })
    if (tempoDialog) TempoDialog(bpm = bpm, onBpm = { bpm = it.coerceIn(30, 240); settings.bpm = bpm; output()?.metronome?.bpm = bpm; output()?.band?.bpm = bpm }, onDismiss = { tempoDialog = false })
    if (settingsSheet) {
        ModalBottomSheet(onDismissRequest = { settingsSheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                SettingRow(stringResource(R.string.sustain), sustain) { sustain = it; settings.sustain = it; synth()?.sustain = it }
                SettingRow(stringResource(R.string.note_names), showNames) { showNames = it; settings.showNames = it }
                SettingRow(stringResource(R.string.reverb), reverb) { reverb = it; settings.reverb = it; output()?.reverb?.enabled = it }
                SettingRow(stringResource(R.string.haptics), haptics) { haptics = it; settings.haptics = it }
                SettingRow(stringResource(R.string.auto_start), autoStart) { autoStart = it; settings.autoStart = it }
                SettingRow(stringResource(R.string.follow_chords), followChords) { followChords = it; settings.followChords = it }
                Text(stringResource(R.string.volume), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(value = volume, onValueChange = { volume = it; synth()?.volume = it }, onValueChangeFinished = { settings.volume = volume })
                Text(stringResource(R.string.band_volume), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(value = bandVolume, onValueChange = { bandVolume = it; output()?.band?.volume = it }, onValueChangeFinished = { settings.bandVolume = bandVolume })
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

private fun Float.roundToIntSafe() = Math.round(this)

/** A small ear-like arc that swells with the input level while listening. */
@Composable
private fun EarIcon(colour: Color, level: Float) {
    Canvas(Modifier.size(18.dp)) {
        val r = size.minDimension / 2
        drawCircle(colour, radius = r * (0.35f + 0.25f * level), center = center)
        drawCircle(colour, radius = r * 0.75f, center = center, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun GearIcon(colour: Color) {
    Canvas(Modifier.size(20.dp)) {
        val r = size.minDimension / 2
        drawCircle(colour, radius = r * 0.32f, style = Stroke(2.dp.toPx()))
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            drawLine(colour, Offset(center.x + (r * 0.55f * cos(a)).toFloat(), center.y + (r * 0.55f * Math.sin(a)).toFloat()),
                Offset(center.x + (r * 0.95f * cos(a)).toFloat(), center.y + (r * 0.95f * Math.sin(a)).toFloat()), strokeWidth = 2.5.dp.toPx())
        }
    }
}

/** C1 to B7 as a thin bar; the visible window is highlighted. Drag it or tap to move. */
@Composable
private fun OctaveStrip(scroll: Float, visibleWhite: Int, onMove: (Float) -> Unit, onRelease: () -> Unit, modifier: Modifier = Modifier) {
    val total = KeyboardLayout.WHITE_COUNT.toFloat()
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val description = stringResource(R.string.octave_strip)
    val textSizePx = with(LocalDensity.current) { 10.sp.toPx() }
    Canvas(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = description }
            .pointerInput(visibleWhite) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                        val perWhite = size.width / total
                        val windowLeft = scroll * perWhite
                        val inWindow = down.position.x in windowLeft..(windowLeft + visibleWhite * perWhite)
                        val grabOffset = if (inWindow) down.position.x - windowLeft else visibleWhite * perWhite / 2f
                        var x = down.position.x
                        while (true) {
                            onMove((x - grabOffset) / perWhite)
                            val c = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            x += c.positionChange().x
                            c.consume()
                        }
                        onRelease()
                    }
                }
            },
    ) {
        val perWhite = size.width / total
        drawRoundRect(track, Offset.Zero, size, CornerRadius(8f, 8f))
        val left = scroll * perWhite
        drawRoundRect(primary.copy(alpha = 0.22f), Offset(left, 0f), Size(visibleWhite * perWhite, size.height), CornerRadius(8f, 8f))
        drawRoundRect(primary, Offset(left, 0f), Size(visibleWhite * perWhite, size.height), CornerRadius(8f, 8f), style = Stroke(2f))
        val paint = android.graphics.Paint().apply { textSize = textSizePx; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; color = android.graphics.Color.argb(255, 169, 162, 153) }
        for (o in 0 until KeyboardLayout.OCTAVES) drawContext.canvas.nativeCanvas.drawText("C${o + 1}", (o * 7 + 3.5f) * perWhite, size.height * 0.68f, paint)
    }
}

/** Dot (tap: start/stop), tempo (tap: dialog) and signature (tap: cycle) in one pill. Drives the band when it is on. */
@Composable
private fun MetronomePill(
    on: Boolean, bpm: Int, signature: String, clock: () -> Pair<Int, Long>?, isBand: Boolean,
    onToggle: () -> Unit, onTempoTap: () -> Unit, onCycleBeats: () -> Unit,
) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(on) { while (on) withFrameNanos { frame = it } }
    val (beatIndex, beatAt) = clock() ?: (-1 to 0L)
    val periodNanos = 60e9 / bpm
    val beatAge = if (on && beatAt > 0) (frame - beatAt).toDouble() else -1.0
    // Breathe between beats so the rhythm is visible, with a brighter hit on the click itself.
    val phase = if (beatAge >= 0) (beatAge / periodNanos).coerceIn(0.0, 1.0) else 0.0
    val breath = if (on) (0.5 + 0.5 * cos(phase * 2 * Math.PI)).toFloat() else 0f
    val hit = if (beatAge in 0.0..3e8) exp(-beatAge / 6e7).toFloat() else 0f
    val downbeat = beatIndex == 0
    val dotColour = when {
        !on -> MaterialTheme.colorScheme.outline
        downbeat -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f + 0.55f * maxOf(breath, hit))
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f + 0.55f * maxOf(breath, hit))
    }
    val toggleDescription = stringResource(if (isBand) R.string.band_toggle else R.string.metronome_toggle)
    val primaryColour = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .height(40.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .clickable(onClick = onToggle)
                .semantics { contentDescription = toggleDescription },
            contentAlignment = Alignment.Center,
        ) {
            if (isBand) {
                // Play / stop glyph for the band, so it does not look like the metronome.
                Canvas(Modifier.size(18.dp).graphicsLayer { val s = 1f + 0.25f * hit; scaleX = s; scaleY = s }) {
                    if (on) drawRoundRect(dotColour, Offset(size.width * 0.2f, size.height * 0.2f), Size(size.width * 0.6f, size.height * 0.6f), CornerRadius(3f, 3f))
                    else {
                        val p = Path().apply { moveTo(size.width * 0.25f, size.height * 0.12f); lineTo(size.width * 0.9f, size.height * 0.5f); lineTo(size.width * 0.25f, size.height * 0.88f); close() }
                        drawPath(p, primaryColour)
                    }
                }
            } else {
                Box(
                    Modifier
                        .size(16.dp)
                        .graphicsLayer { val s = 1f + 0.35f * hit; scaleX = s; scaleY = s }
                        .clip(CircleShape)
                        .background(dotColour),
                )
            }
        }
        Text(
            "$bpm",
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTempoTap).minimumInteractiveComponentSize().padding(horizontal = 4.dp),
        )
        Text(
            signature,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !isBand, onClick = onCycleBeats).minimumInteractiveComponentSize().padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun BandDialog(on: Boolean, style: Style, onPick: (Style) -> Unit, onOff: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.band)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.band_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Style.ALL.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { st ->
                            FilterChip(
                                selected = on && st == style,
                                onClick = { onPick(st) },
                                label = { Text("${st.name}  ·  ${st.label} ${st.defaultBpm}") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onOff) { Text(stringResource(R.string.band_off)) } },
    )
}

@Composable
private fun TempoDialog(bpm: Int, onBpm: (Int) -> Unit, onDismiss: () -> Unit) {
    val taps = remember { ArrayList<Long>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tempo)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedButton(onClick = { onBpm(bpm - 1) }) { Text("−", fontSize = 18.sp) }
                    Text("$bpm", fontSize = 40.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"))
                    OutlinedButton(onClick = { onBpm(bpm + 1) }) { Text("+", fontSize = 18.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onBpm(bpm - 5) }) { Text("−5") }
                    OutlinedButton(onClick = {
                        val now = System.nanoTime()
                        if (taps.isNotEmpty() && now - taps.last() > 2_500_000_000L) taps.clear()
                        taps += now
                        if (taps.size >= 2) {
                            val recent = taps.takeLast(5)
                            val mean = (recent.last() - recent.first()).toDouble() / (recent.size - 1)
                            onBpm(Math.round(60e9 / mean).toInt())
                        }
                    }) { Text(stringResource(R.string.tap_tempo)) }
                    OutlinedButton(onClick = { onBpm(bpm + 5) }) { Text("+5") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

/** A chord button that sounds and lights its notes while held. */
@Composable
private fun ChordButton(chord: Theory.Chord, held: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(chord) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                        onPress()
                        while (true) { if (awaitPointerEvent().changes.none { it.pressed }) break }
                        onRelease()
                        down.consume()
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(chord.name, fontWeight = FontWeight.SemiBold, color = if (held) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        Text(chord.numeral, style = MaterialTheme.typography.labelSmall, color = if (held) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScaleDialog(current: Theory.Scale?, onPick: (Theory.Scale?) -> Unit, onDismiss: () -> Unit) {
    var minor by remember { mutableStateOf(current?.minor ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scale)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = !minor, onClick = { minor = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.scale_major)) }
                    SegmentedButton(selected = minor, onClick = { minor = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.scale_minor)) }
                }
                for (row in listOf(0..5, 6..11)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { pc ->
                            val sc = Theory.Scale(pc, minor)
                            FilterChip(selected = current?.tonic == pc && current.minor == minor, onClick = { onPick(sc) }, label = { Text(sc.spell(pc)) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.scale_off)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

/** Rounded at the bottom only, like a real key under the fallboard. */
private fun DrawScope.keyPath(left: Float, top: Float, width: Float, height: Float, radius: Float): Path = Path().apply {
    addRoundRect(RoundRect(left, top, left + width, top + height, CornerRadius.Zero, CornerRadius.Zero, CornerRadius(radius), CornerRadius(radius)))
}

/**
 * The keyboard. Fingers play keys, as many as you like. A drag on the felt bar scrolls, and so
 * do two or more fingers that move sideways together by more than a key's width; until they
 * do, they play. The keyboard snaps to a key edge when a scroll ends.
 */
@Composable
private fun Keyboard(
    scroll: Animatable<Float, *>, visibleWhite: Int, showNames: Boolean, scale: Theory.Scale?, litPitchClasses: Set<Int>,
    synth: () -> PianoSynth?, haptics: Boolean, onScrollSettled: (Float) -> Unit,
    /** With the band on, keys below this MIDI note set the chord instead of sounding. */
    chordZoneBelow: Int?, onChordZone: (Set<Int>) -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val chordHeldKeys = remember { HashSet<Int>() }
    val pressed = remember { mutableStateMapOf<Int, Int>() }
    val pressedAt = remember { mutableStateMapOf<Int, Long>() }
    val releasedAt = remember { mutableStateMapOf<Int, Long>() }
    var frame by remember { mutableLongStateOf(System.nanoTime()) }
    /** Loudness per MIDI note as the synthesiser reports it, so the glow follows the sound. */
    val levels = remember { FloatArray(128) }
    var levelsVersion by remember { mutableStateOf(0) }
    var pressCount by remember { mutableStateOf(0) }
    // One frame loop, started by any press, that runs until nothing moves and nothing sounds.
    LaunchedEffect(pressCount) {
        while (true) {
            withFrameNanos { frame = it }
            releasedAt.entries.filter { frame - it.value > 220_000_000L }.forEach { releasedAt.remove(it.key) }
            val engine = synth()
            engine?.copyLevels(levels)
            levelsVersion++
            val sounding = engine != null && engine.activeVoices > 0
            if (pressed.isEmpty() && releasedAt.isEmpty() && !sounding) break
        }
    }
    val density = LocalDensity.current
    val textSizePx = with(density) { 11.sp.toPx() }
    val dp = with(density) { 1.dp.toPx() }
    val inScale = scale?.pitchClasses?.toSet()
    val tonic = scale?.tonic
    val feltPx = 12f * dp // tall enough to grab as a scroll bar
    val travelPx = 4f * dp

    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(6.dp)) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() } - feltPx
        val layout = remember(visibleWhite, widthPx, heightPx) { KeyboardLayout(visibleWhite, widthPx, heightPx) }

        DisposableEffect(layout) { onDispose { pressed.keys.toList().forEach { synth()?.noteOff(it) }; pressed.clear() } }
        fun releaseAll(byPointer: MutableMap<PointerId, KeyboardLayout.Key>, release: (KeyboardLayout.Key) -> Unit) {
            byPointer.values.toList().forEach { release(it) }
            byPointer.clear()
        }

        /** 0 = up, 1 = down: eased on the way down, a small overshoot on the way back up. */
        fun depth(midi: Int): Float {
            val now = frame
            pressedAt[midi]?.let { if (pressed.containsKey(midi)) return EaseOutCubic.transform(((now - it) / 60_000_000f).coerceIn(0f, 1f)) }
            releasedAt[midi]?.let {
                val t = ((now - it) / 180_000_000f).coerceIn(0f, 1f)
                val eased = 1f - EaseOutCubic.transform(t)
                return eased - 0.25f * kotlin.math.sin(t * Math.PI).toFloat() // dips a dp below rest, then settles
            }
            return 0f
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Piano keyboard, $visibleWhite white keys from ${KeyboardLayout.noteName(layout.leftmostOctaveC(scroll.value))}" }
                .pointerInput(layout, haptics, chordZoneBelow) {
                    val byPointer = HashMap<PointerId, KeyboardLayout.Key>()
                    fun inChordZone(midi: Int) = chordZoneBelow != null && midi < chordZoneBelow
                    val downAt = HashMap<PointerId, Offset>()
                    var scrolling = false
                    val slop = layout.whiteWidth * 0.9f
                    fun press(key: KeyboardLayout.Key, y: Float) {
                        val velocity = 0.55f + 0.45f * (y / key.height).coerceIn(0f, 1f)
                        if (inChordZone(key.midi)) { chordHeldKeys += key.midi; onChordZone(chordHeldKeys.toSet()) } else synth()?.noteOn(key.midi, velocity)
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        pressed[key.midi] = (pressed[key.midi] ?: 0) + 1
                        val now = System.nanoTime()
                        pressedAt[key.midi] = now
                        frame = now // the frame clock may be stale after idle; never draw a negative age
                        releasedAt.remove(key.midi)
                        pressCount++
                    }
                    fun release(key: KeyboardLayout.Key) {
                        val n = (pressed[key.midi] ?: 1) - 1
                        if (n <= 0) {
                            pressed.remove(key.midi)
                            if (inChordZone(key.midi)) chordHeldKeys -= key.midi else synth()?.noteOff(key.midi)
                            val now = System.nanoTime(); releasedAt[key.midi] = now; frame = now
                        } else pressed[key.midi] = n
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.filter { it.pressed }
                            for (c in down) if (c.id !in downAt) downAt[c.id] = c.position
                            event.changes.filter { !it.pressed }.forEach { downAt.remove(it.id) }
                            // Scroll when a finger lands on the felt bar, or when two or more fingers have
                            // all moved sideways the same way by about a key's width. Until then they play.
                            val onFelt = down.size == 1 && downAt[down[0].id]?.let { it.y < feltPx } == true && byPointer.isEmpty()
                            val twoFingerDrag = down.size >= 2 && down.all { c ->
                                val start = downAt[c.id] ?: c.position
                                val dx = c.position.x - start.x
                                kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(c.position.y - start.y)
                            } && down.map { kotlin.math.sign(it.position.x - (downAt[it.id]?.x ?: it.position.x)) }.distinct().size == 1
                            if (!scrolling && (onFelt || twoFingerDrag)) { scrolling = true; releaseAll(byPointer, ::release) }
                            if (scrolling) {
                                if (down.isEmpty()) {
                                    scrolling = false
                                    scope.launch {
                                        scroll.animateTo(Math.round(scroll.value).toFloat().coerceIn(0f, layout.maxScroll), tween(120))
                                        onScrollSettled(scroll.value)
                                    }
                                } else {
                                    val dx = down.map { it.positionChange().x }.average().toFloat()
                                    if (dx != 0f) {
                                        val target = (scroll.value - dx / layout.whiteWidth).coerceIn(0f, layout.maxScroll)
                                        scope.launch { scroll.snapTo(target) }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            for (change in event.changes) {
                                val id = change.id
                                val current = byPointer[id]
                                if (change.pressed) {
                                    val y = (change.position.y - feltPx).coerceAtLeast(0f)
                                    val key = layout.keyAt(change.position.x, y, scroll.value)
                                    if (key?.midi != current?.midi) {
                                        current?.let { release(it) }
                                        if (key != null) { byPointer[id] = key; press(key, y) } else byPointer.remove(id)
                                    }
                                } else if (current != null) {
                                    byPointer.remove(id)
                                    release(current)
                                }
                                change.consume()
                            }
                        }
                    }
                },
        ) {
            @Suppress("UNUSED_EXPRESSION") levelsVersion // read so the canvas redraws when levels change
            val paint = android.graphics.Paint().apply { textSize = textSizePx; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
            val radius = 5f * dp
            // The felt strip where the keys disappear under the fallboard; also the scroll bar.
            drawRect(Felt, Offset(0f, 0f), Size(size.width, feltPx))
            run {
                // A small grip in the middle hints that the bar can be dragged.
                val cx = size.width / 2
                for (i in -1..1) drawRoundRect(Color(0x55FFFFFF), Offset(cx + i * 6f * dp - 1.5f * dp, feltPx * 0.3f), Size(3f * dp, feltPx * 0.4f), CornerRadius(1.5f * dp))
            }
            val scrollPx = layout.scrollPx(scroll.value)
            translate(-scrollPx, feltPx) {
                layout.visible(scroll.value, layout.whiteKeys).forEach { k ->
                    val chordZone = chordZoneBelow != null && k.midi < chordZoneBelow
                    val pc = Math.floorMod(k.midi, 12)
                    val d = depth(k.midi)
                    val down = pressed.containsKey(k.midi)
                    val lit = pc in litPitchClasses
                    val outOfScale = inScale != null && pc !in inScale
                    val top = d * travelPx
                    val face = keyPath(k.left + 0.6f * dp, top, k.width - 1.2f * dp, k.height - top - 0.5f * dp, radius)
                    val (hi, lo) = when {
                        chordZone -> Color(0xFFDCE9E6) to Color(0xFFC6D6D2) // the band's chord zone, faintly teal
                        outOfScale -> IvoryDim to IvoryDim.copy(red = IvoryDim.red * 0.9f, green = IvoryDim.green * 0.9f, blue = IvoryDim.blue * 0.9f)
                        else -> Ivory to IvoryShade
                    }
                    // Light from above: brighter at the top, warmer at the bottom.
                    drawPath(face, Brush.verticalGradient(0f to hi, 1f to lo, startY = top, endY = k.height))
                    // Soft inner shadow on the left edge where the neighbouring key overlaps.
                    drawRect(Brush.horizontalGradient(0f to Color(0x30000000), 1f to Color.Transparent, startX = k.left, endX = k.left + k.width * 0.12f), Offset(k.left, top), Size(k.width * 0.12f, k.height - top))
                    if (d > 0f) drawRect(Brush.verticalGradient(0f to Color(0x55000000), 1f to Color.Transparent, startY = top, endY = top + 6f * dp * d), Offset(k.left, top), Size(k.width, 6f * dp * d))
                    // Press feedback as light: a teal glow rising from the bottom edge.
                    // Glow with the sound: full while the finger is down, then fading with the note itself.
                    val sounding = if (k.midi in levels.indices) levels[k.midi] else 0f
                    val strength = maxOf(if (down) 1f else 0f, if (lit) 0.6f else 0f, sounding * 0.85f)
                    if (strength > 0.02f) {
                        drawPath(face, Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to TealGlow.copy(alpha = 0.55f * strength), startY = top, endY = k.height))
                    }
                    if (inScale != null && !outOfScale) drawCircle(Teal.copy(alpha = if (pc == tonic) 1f else 0.5f), radius = (if (pc == tonic) 4.5f else 3f) * dp, center = Offset(k.left + k.width / 2, k.height - textSizePx * 2.6f))
                    if (k.midi == 60 && inScale == null) drawCircle(Color(0xFF8A847B), radius = 3f * dp, center = Offset(k.left + k.width / 2, k.height - textSizePx * 2.6f))
                    if (showNames || pc == 0) {
                        paint.color = if (pc == tonic) android.graphics.Color.rgb(15, 118, 110) else android.graphics.Color.argb(if (pc == 0) 200 else 140, 90, 86, 80)
                        paint.isFakeBoldText = pc == 0
                        drawContext.canvas.nativeCanvas.drawText(KeyboardLayout.noteName(k.midi), k.left + k.width / 2, k.height - textSizePx * 0.9f + top * 0.5f, paint)
                    }
                }
                // Split line between the chord zone and the playing keys.
                chordZoneBelow?.let { split ->
                    val x = layout.whiteKeys.firstOrNull { it.midi >= split }?.left ?: return@let
                    drawLine(Teal, Offset(x, 0f), Offset(x, layout.height), strokeWidth = 2f * dp)
                }
                layout.visible(scroll.value, layout.blackKeys).forEach { k ->
                    val pc = Math.floorMod(k.midi, 12)
                    val d = depth(k.midi)
                    val down = pressed.containsKey(k.midi)
                    val lit = pc in litPitchClasses
                    val h = k.height - travelPx * 0.9f * d
                    val body = keyPath(k.left, 0f, k.width, h, 3f * dp)
                    drawPath(body, Brush.verticalGradient(0f to EbonyTop, 0.12f to Ebony, 1f to Color(0xFF0E0D0C)))
                    // Gloss along the top edge, dimmer when pressed.
                    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = (0.35f * (1f - 0.6f * d)).coerceIn(0f, 1f)), 1f to Color.Transparent, startY = 0f, endY = h * 0.14f), Offset(k.left + dp, 0f), Size(k.width - 2f * dp, h * 0.14f))
                    val sounding = if (k.midi in levels.indices) levels[k.midi] else 0f
                    val strength = maxOf(if (down) 1f else 0f, if (lit) 0.6f else 0f, sounding * 0.85f)
                    if (strength > 0.02f) {
                        drawPath(body, Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to Teal.copy(alpha = 0.75f * strength), startY = 0f, endY = h))
                    }
                    if (inScale != null && pc in inScale) drawCircle(Teal.copy(alpha = if (pc == tonic) 1f else 0.6f), radius = (if (pc == tonic) 4f else 2.5f) * dp, center = Offset(k.left + k.width / 2, h - 8f * dp))
                }
            }
        }
    }
}
