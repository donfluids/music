package com.donfluids.piano

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.EaseOutCubic
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
    var baseOctave by remember { mutableStateOf(settings.baseOctave) }
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
    val octaves = 2
    val synth: () -> PianoSynth? = { output()?.synth }
    val chordHeld = remember { mutableStateMapOf<Int, List<Int>>() }
    // The audio engine is recreated whenever the activity returns to the front; keep it in step.
    val engine = output()
    LaunchedEffect(engine) { engine?.metronome?.enabled = metronomeOn }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; output()?.metronome?.enabled = false }
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
                baseOctave = baseOctave, octaves = octaves,
                onMove = { baseOctave = it.coerceIn(1, 8 - octaves); settings.baseOctave = baseOctave },
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
            MetronomePill(
                on = metronomeOn, bpm = bpm, beatsPerBar = beatsPerBar, output = output,
                onToggle = { metronomeOn = !metronomeOn; output()?.metronome?.enabled = metronomeOn },
                onTempoTap = { tempoDialog = true },
                onCycleBeats = {
                    beatsPerBar = when (beatsPerBar) { 2 -> 3; 3 -> 4; 4 -> 6; else -> 2 }
                    settings.beatsPerBar = beatsPerBar; output()?.metronome?.beatsPerBar = beatsPerBar
                },
            )
            IconButton(onClick = { settingsSheet = true }) {
                GearIcon(MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)

        // Chord row sits directly above the keys so the eye drops from chord to notes.
        scale?.let { sc ->
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sc.chords().forEachIndexed { i, chord ->
                    val rootMidi = 12 * (baseOctave + 1) + chord.pitchClasses[0] + (if (chord.pitchClasses[0] < sc.tonic) 12 else 0)
                    val notes = chord.pitchClasses.map { pc -> rootMidi + Math.floorMod(pc - chord.pitchClasses[0], 12) }
                    ChordButton(
                        chord, held = chordHeld.containsKey(i),
                        onPress = { chordHeld[i] = chord.pitchClasses; notes.forEach { synth()?.noteOn(it, 0.8f) } },
                        onRelease = { chordHeld.remove(i); notes.forEach { synth()?.noteOff(it) } },
                    )
                }
            }
        }
        Keyboard(
            baseOctave = baseOctave, octaves = octaves, showNames = showNames, scale = scale,
            litPitchClasses = chordHeld.values.flatten().toSet(),
            synth = synth, haptics = haptics,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    if (scaleDialog) ScaleDialog(current = scale, onPick = { scale = it; settings.scale = it; scaleDialog = false }, onDismiss = { scaleDialog = false })
    if (tempoDialog) TempoDialog(bpm = bpm, onBpm = { bpm = it.coerceIn(30, 240); settings.bpm = bpm; output()?.metronome?.bpm = bpm }, onDismiss = { tempoDialog = false })
    if (settingsSheet) {
        ModalBottomSheet(onDismissRequest = { settingsSheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                SettingRow(stringResource(R.string.sustain), sustain) { sustain = it; settings.sustain = it; synth()?.sustain = it }
                SettingRow(stringResource(R.string.note_names), showNames) { showNames = it; settings.showNames = it }
                SettingRow(stringResource(R.string.reverb), reverb) { reverb = it; settings.reverb = it; output()?.reverb?.enabled = it }
                SettingRow(stringResource(R.string.haptics), haptics) { haptics = it; settings.haptics = it }
                Text(stringResource(R.string.volume), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(value = volume, onValueChange = { volume = it; synth()?.volume = it }, onValueChangeFinished = { settings.volume = volume })
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
private fun OctaveStrip(baseOctave: Int, octaves: Int, onMove: (Int) -> Unit, modifier: Modifier = Modifier) {
    val first = 1
    val total = 7
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val description = stringResource(R.string.octave_strip)
    val textSizePx = with(LocalDensity.current) { 10.sp.toPx() }
    Canvas(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = description }
            .pointerInput(octaves) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                        val perOctave = size.width / total.toFloat()
                        val windowLeft = (baseOctave - first) * perOctave
                        val inWindow = down.position.x in windowLeft..(windowLeft + octaves * perOctave)
                        val grabOffset = if (inWindow) down.position.x - windowLeft else octaves * perOctave / 2f
                        var x = down.position.x
                        while (true) {
                            onMove(first + Math.round((x - grabOffset) / perOctave))
                            val c = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            x += c.positionChange().x
                            c.consume()
                        }
                    }
                }
            },
    ) {
        val perOctave = size.width / total
        drawRoundRect(track, Offset.Zero, size, CornerRadius(8f, 8f))
        val left = (baseOctave - first) * perOctave
        drawRoundRect(primary.copy(alpha = 0.22f), Offset(left, 0f), Size(octaves * perOctave, size.height), CornerRadius(8f, 8f))
        drawRoundRect(primary, Offset(left, 0f), Size(octaves * perOctave, size.height), CornerRadius(8f, 8f), style = Stroke(2f))
        val paint = android.graphics.Paint().apply { textSize = textSizePx; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; color = android.graphics.Color.argb(255, 169, 162, 153) }
        for (o in 0 until total) drawContext.canvas.nativeCanvas.drawText("C${o + first}", o * perOctave + perOctave / 2, size.height * 0.68f, paint)
    }
}

/** Dot (tap: start/stop), tempo (tap: dialog) and signature (tap: cycle) in one pill. */
@Composable
private fun MetronomePill(on: Boolean, bpm: Int, beatsPerBar: Int, output: () -> AudioOutput?, onToggle: () -> Unit, onTempoTap: () -> Unit, onCycleBeats: () -> Unit) {
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(on) { while (on) withFrameNanos { frame = it } }
    val m = output()?.metronome
    val periodNanos = 60e9 / bpm
    val beatAge = if (on && m != null && m.beatAtNanos > 0) (frame - m.beatAtNanos).toDouble() else -1.0
    // Breathe between beats so the rhythm is visible, with a brighter hit on the click itself.
    val phase = if (beatAge >= 0) (beatAge / periodNanos).coerceIn(0.0, 1.0) else 0.0
    val breath = if (on) (0.5 + 0.5 * cos(phase * 2 * Math.PI)).toFloat() else 0f
    val hit = if (beatAge in 0.0..3e8) exp(-beatAge / 6e7).toFloat() else 0f
    val downbeat = m?.beatIndex == 0
    val dotColour = when {
        !on -> MaterialTheme.colorScheme.outline
        downbeat -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f + 0.55f * maxOf(breath, hit))
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f + 0.55f * maxOf(breath, hit))
    }
    val toggleDescription = stringResource(R.string.metronome_toggle)
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
            Box(
                Modifier
                    .size(16.dp)
                    .graphicsLayer { val s = 1f + 0.35f * hit; scaleX = s; scaleY = s }
                    .clip(CircleShape)
                    .background(dotColour),
            )
        }
        Text(
            "$bpm",
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTempoTap).minimumInteractiveComponentSize().padding(horizontal = 4.dp),
        )
        Text(
            if (beatsPerBar == 6) "6/8" else "$beatsPerBar/4",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCycleBeats).minimumInteractiveComponentSize().padding(horizontal = 4.dp),
        )
    }
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

@Composable
private fun Keyboard(
    baseOctave: Int, octaves: Int, showNames: Boolean, scale: Theory.Scale?, litPitchClasses: Set<Int>,
    synth: () -> PianoSynth?, haptics: Boolean, modifier: Modifier,
) {
    val view = LocalView.current
    val pressed = remember { mutableStateMapOf<Int, Int>() }
    val pressedAt = remember { mutableStateMapOf<Int, Long>() }
    val releasedAt = remember { mutableStateMapOf<Int, Long>() }
    var frame by remember { mutableLongStateOf(System.nanoTime()) }
    val animating = pressed.isNotEmpty() || releasedAt.isNotEmpty()
    LaunchedEffect(animating) {
        while (animating) {
            withFrameNanos { frame = it }
            releasedAt.entries.filter { frame - it.value > 220_000_000L }.forEach { releasedAt.remove(it.key) }
        }
    }
    val density = LocalDensity.current
    val textSizePx = with(density) { 11.sp.toPx() }
    val dp = with(density) { 1.dp.toPx() }
    val inScale = scale?.pitchClasses?.toSet()
    val tonic = scale?.tonic
    val feltPx = 4f * dp
    val travelPx = 4f * dp

    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(6.dp)) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() } - feltPx
        val layout = remember(baseOctave, octaves, widthPx, heightPx) { KeyboardLayout(baseOctave, octaves, widthPx, heightPx) }

        DisposableEffect(layout) { onDispose { pressed.keys.toList().forEach { synth()?.noteOff(it) }; pressed.clear() } }

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
                .semantics { contentDescription = "Piano keyboard, ${layout.whiteCount} white keys from ${KeyboardLayout.noteName(layout.baseMidi)}" }
                .pointerInput(layout, haptics) {
                    val byPointer = HashMap<PointerId, KeyboardLayout.Key>()
                    fun press(key: KeyboardLayout.Key, y: Float) {
                        val velocity = 0.55f + 0.45f * (y / key.height).coerceIn(0f, 1f)
                        synth()?.noteOn(key.midi, velocity)
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        pressed[key.midi] = (pressed[key.midi] ?: 0) + 1
                        val now = System.nanoTime()
                        pressedAt[key.midi] = now
                        frame = now // the frame clock may be stale after idle; never draw a negative age
                        releasedAt.remove(key.midi)
                    }
                    fun release(key: KeyboardLayout.Key) {
                        val n = (pressed[key.midi] ?: 1) - 1
                        if (n <= 0) { pressed.remove(key.midi); synth()?.noteOff(key.midi); val now = System.nanoTime(); releasedAt[key.midi] = now; frame = now } else pressed[key.midi] = n
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                val id = change.id
                                val current = byPointer[id]
                                if (change.pressed) {
                                    // A touch on the felt strip counts as the top of the key beneath it.
                                    val y = (change.position.y - feltPx).coerceAtLeast(0f)
                                    val key = layout.keyAt(change.position.x, y)
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
            val paint = android.graphics.Paint().apply { textSize = textSizePx; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }
            val radius = 5f * dp
            // The felt strip where the keys disappear under the fallboard.
            drawRect(Felt, Offset(0f, 0f), Size(size.width, feltPx))
            translate(0f, feltPx) {
                layout.whiteKeys.forEach { k ->
                    val pc = Math.floorMod(k.midi, 12)
                    val d = depth(k.midi)
                    val down = pressed.containsKey(k.midi)
                    val lit = pc in litPitchClasses
                    val outOfScale = inScale != null && pc !in inScale
                    val top = d * travelPx
                    val face = keyPath(k.left + 0.6f * dp, top, k.width - 1.2f * dp, k.height - top - 0.5f * dp, radius)
                    val (hi, lo) = when {
                        outOfScale -> IvoryDim to IvoryDim.copy(red = IvoryDim.red * 0.9f, green = IvoryDim.green * 0.9f, blue = IvoryDim.blue * 0.9f)
                        else -> Ivory to IvoryShade
                    }
                    // Light from above: brighter at the top, warmer at the bottom.
                    drawPath(face, Brush.verticalGradient(0f to hi, 1f to lo, startY = top, endY = k.height))
                    // Soft inner shadow on the left edge where the neighbouring key overlaps.
                    drawRect(Brush.horizontalGradient(0f to Color(0x30000000), 1f to Color.Transparent, startX = k.left, endX = k.left + k.width * 0.12f), Offset(k.left, top), Size(k.width * 0.12f, k.height - top))
                    if (d > 0f) drawRect(Brush.verticalGradient(0f to Color(0x55000000), 1f to Color.Transparent, startY = top, endY = top + 6f * dp * d), Offset(k.left, top), Size(k.width, 6f * dp * d))
                    // Press feedback as light: a teal glow rising from the bottom edge.
                    if (down || lit || d > 0f) {
                        val strength = if (down) 1f else if (lit) 0.6f else d
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
                layout.blackKeys.forEach { k ->
                    val pc = Math.floorMod(k.midi, 12)
                    val d = depth(k.midi)
                    val down = pressed.containsKey(k.midi)
                    val lit = pc in litPitchClasses
                    val h = k.height - travelPx * 0.9f * d
                    val body = keyPath(k.left, 0f, k.width, h, 3f * dp)
                    drawPath(body, Brush.verticalGradient(0f to EbonyTop, 0.12f to Ebony, 1f to Color(0xFF0E0D0C)))
                    // Gloss along the top edge, dimmer when pressed.
                    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = (0.35f * (1f - 0.6f * d)).coerceIn(0f, 1f)), 1f to Color.Transparent, startY = 0f, endY = h * 0.14f), Offset(k.left + dp, 0f), Size(k.width - 2f * dp, h * 0.14f))
                    if (down || lit || d > 0f) {
                        val strength = if (down) 1f else if (lit) 0.6f else d
                        drawPath(body, Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 1f to Teal.copy(alpha = 0.75f * strength), startY = 0f, endY = h))
                    }
                    if (inScale != null && pc in inScale) drawCircle(Teal.copy(alpha = if (pc == tonic) 1f else 0.6f), radius = (if (pc == tonic) 4f else 2.5f) * dp, center = Offset(k.left + k.width / 2, h - 8f * dp))
                }
            }
        }
    }
}
