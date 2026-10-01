package com.donfluids.piano

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.min

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
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val octaves = if (landscape) 2 else 1
    val synth: () -> PianoSynth? = { output()?.synth }

    /** Pitch classes lit by a held chord button. */
    val chordHeld = remember { mutableStateMapOf<Int, List<Int>>() }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { output()?.metronome?.enabled = false } }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Row 1: octave, sustain, names, tone, scale.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { baseOctave = (baseOctave - 1).coerceAtLeast(1); settings.baseOctave = baseOctave }, enabled = baseOctave > 1) { Text("−", fontSize = 18.sp) }
            Text("C$baseOctave–B${baseOctave + octaves - 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { baseOctave = (baseOctave + 1).coerceAtMost(8 - octaves); settings.baseOctave = baseOctave }, enabled = baseOctave + octaves - 1 < 7) { Text("+", fontSize = 18.sp) }
            Spacer(Modifier.width(4.dp))
            FilterChip(selected = sustain, onClick = { sustain = !sustain; settings.sustain = sustain; synth()?.sustain = sustain }, label = { Text(stringResource(R.string.sustain)) })
            FilterChip(selected = showNames, onClick = { showNames = !showNames; settings.showNames = showNames }, label = { Text(stringResource(R.string.note_names)) })
            PianoSynth.Tone.entries.forEach { t ->
                FilterChip(selected = tone == t, onClick = { tone = t; settings.tone = t; synth()?.tone = t }, label = { Text(t.label) })
            }
            FilterChip(
                selected = scale != null,
                onClick = { scaleDialog = true },
                label = { Text(scale?.label ?: stringResource(R.string.scale)) },
            )
            FilterChip(selected = reverb, onClick = { reverb = !reverb; settings.reverb = reverb; output()?.reverb?.enabled = reverb }, label = { Text(stringResource(R.string.reverb)) })
            FilterChip(selected = haptics, onClick = { haptics = !haptics; settings.haptics = haptics }, label = { Text(stringResource(R.string.haptics)) })
            Text(stringResource(R.string.volume), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = volume,
                onValueChange = { volume = it; synth()?.volume = it },
                onValueChangeFinished = { settings.volume = volume },
                modifier = Modifier.width(140.dp).semantics { contentDescription = "Volume" },
            )
        }
        // Row 2: metronome, and the chords of the chosen scale.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetronomeControls(
                on = metronomeOn, bpm = bpm, beatsPerBar = beatsPerBar, output = output,
                onToggle = { metronomeOn = !metronomeOn; output()?.metronome?.enabled = metronomeOn },
                onBpm = { bpm = it.coerceIn(30, 240); settings.bpm = bpm; output()?.metronome?.bpm = bpm },
                onBeats = { beatsPerBar = it; settings.beatsPerBar = it; output()?.metronome?.beatsPerBar = it },
            )
            scale?.let { sc ->
                Spacer(Modifier.width(8.dp))
                sc.chords().forEachIndexed { i, chord ->
                    ChordButton(chord, held = chordHeld.containsKey(i), onPress = {
                        chordHeld[i] = chord.pitchClasses
                        val root = 12 * (baseOctave + 1) + chord.pitchClasses[0] + (if (chord.pitchClasses[0] < sc.tonic) 12 else 0)
                        chord.pitchClasses.forEachIndexed { k, pc -> synth()?.noteOn(root + Math.floorMod(pc - chord.pitchClasses[0], 12) + 0, 0.8f) }
                    }, onRelease = {
                        chordHeld.remove(i)
                        val root = 12 * (baseOctave + 1) + chord.pitchClasses[0] + (if (chord.pitchClasses[0] < sc.tonic) 12 else 0)
                        chord.pitchClasses.forEach { pc -> synth()?.noteOff(root + Math.floorMod(pc - chord.pitchClasses[0], 12)) }
                    })
                }
            }
        }
        if (error != null) Text(error, color = Color(0xFFF2997F), style = MaterialTheme.typography.bodyMedium)
        OctaveStrip(
            baseOctave = baseOctave, octaves = octaves,
            onMove = { baseOctave = it.coerceIn(1, 8 - octaves); settings.baseOctave = baseOctave },
        )
        Keyboard(
            baseOctave = baseOctave, octaves = octaves, showNames = showNames, scale = scale,
            litPitchClasses = chordHeld.values.flatten().toSet(),
            synth = synth,
            haptics = haptics,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    if (scaleDialog) {
        ScaleDialog(current = scale, onPick = { scale = it; settings.scale = it; scaleDialog = false }, onDismiss = { scaleDialog = false })
    }
}

/**
 * The whole C1–B7 range as a thin bar; the visible octaves are highlighted. Drag the window
 * or tap elsewhere to move the keyboard.
 */
@Composable
private fun OctaveStrip(baseOctave: Int, octaves: Int, onMove: (Int) -> Unit) {
    val first = 1
    val total = 7 // octaves 1..7
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(R.string.octave_strip)
    val textSizePx = with(LocalDensity.current) { 10.sp.toPx() }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .semantics { contentDescription = description }
            .pointerInput(octaves) {
                awaitPointerEventScope {
                    var grabOffset = 0f
                    while (true) {
                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                        val perOctave = size.width / total.toFloat()
                        val windowLeft = (baseOctave - first) * perOctave
                        val inWindow = down.position.x in windowLeft..(windowLeft + octaves * perOctave)
                        grabOffset = if (inWindow) down.position.x - windowLeft else octaves * perOctave / 2f
                        var x = down.position.x
                        while (true) {
                            val target = first + Math.round((x - grabOffset) / perOctave)
                            onMove(target)
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            x += c.positionChange().x
                            c.consume()
                        }
                    }
                }
            },
    ) {
        val perOctave = size.width / total
        drawRoundRect(track, Offset.Zero, size, CornerRadius(6f, 6f))
        val left = (baseOctave - first) * perOctave
        drawRoundRect(primary.copy(alpha = 0.35f), Offset(left, 0f), Size(octaves * perOctave, size.height), CornerRadius(6f, 6f))
        drawRoundRect(primary, Offset(left, 0f), Size(octaves * perOctave, size.height), CornerRadius(6f, 6f), style = Stroke(2f))
        val paint = android.graphics.Paint().apply { textSize = textSizePx; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true; color = android.graphics.Color.argb(255, 168, 163, 155) }
        for (o in 0 until total) {
            val x = o * perOctave
            if (o > 0) drawLine(tick, Offset(x, 0f), Offset(x, size.height), 1f)
            drawContext.canvas.nativeCanvas.drawText("C${o + first}", x + perOctave / 2, size.height * 0.72f, paint)
        }
    }
}

@Composable
private fun MetronomeControls(on: Boolean, bpm: Int, beatsPerBar: Int, output: () -> AudioOutput?, onToggle: () -> Unit, onBpm: (Int) -> Unit, onBeats: (Int) -> Unit) {
    // Flash a dot on each click, following the metronome's own clock.
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(on) { while (on) withFrameNanos { frame = it } }
    val m = output()?.metronome
    val beatAge = if (on && m != null && m.beatAtNanos > 0) (frame - m.beatAtNanos) / 1e9 else 1.0
    val flash = if (beatAge in 0.0..0.3) exp(-beatAge * 12).toFloat() else 0f
    val downbeat = m?.beatIndex == 0
    Box(
        Modifier.size(22.dp).graphicsLayer { val s = 1f + 0.5f * flash; scaleX = s; scaleY = s }.clip(CircleShape)
            .background(if (flash > 0.05f) (if (downbeat) Color(0xFFF2997F) else MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.surfaceVariant),
    )
    FilterChip(selected = on, onClick = onToggle, label = { Text(stringResource(if (on) R.string.metronome_stop else R.string.metronome_start)) })
    OutlinedButton(onClick = { onBpm(bpm - 1) }) { Text("−") }
    Text("$bpm ${stringResource(R.string.bpm)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(72.dp))
    OutlinedButton(onClick = { onBpm(bpm + 1) }) { Text("+") }
    listOf(2, 3, 4, 6).forEach { b ->
        FilterChip(selected = beatsPerBar == b, onClick = { onBeats(b) }, label = { Text(if (b == 6) "6/8" else "$b/4") })
    }
}

/** A chord button that sounds and lights its notes while held. */
@Composable
private fun ChordButton(chord: Theory.Chord, held: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(chord) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                        onPress()
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.changes.none { it.pressed }) break
                        }
                        onRelease()
                        down.consume()
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !minor, onClick = { minor = false }, label = { Text(stringResource(R.string.scale_major)) })
                    FilterChip(selected = minor, onClick = { minor = true }, label = { Text(stringResource(R.string.scale_minor)) })
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

@Composable
private fun Keyboard(
    baseOctave: Int, octaves: Int, showNames: Boolean, scale: Theory.Scale?, litPitchClasses: Set<Int>,
    synth: () -> PianoSynth?, haptics: Boolean, modifier: Modifier,
) {
    val view = LocalView.current
    /** Pointers currently on each MIDI note. */
    val pressed = remember { mutableStateMapOf<Int, Int>() }
    /** Press and release times, for the key-travel animation. */
    val pressedAt = remember { mutableStateMapOf<Int, Long>() }
    val releasedAt = remember { mutableStateMapOf<Int, Long>() }
    var frame by remember { mutableLongStateOf(System.nanoTime()) }
    val animating = pressed.isNotEmpty() || releasedAt.isNotEmpty()
    LaunchedEffect(animating) {
        while (animating) {
            withFrameNanos { frame = it }
            releasedAt.entries.filter { frame - it.value > 150_000_000L }.forEach { releasedAt.remove(it.key) }
        }
    }
    val textSizePx = with(LocalDensity.current) { 12.sp.toPx() }
    val inScale = scale?.pitchClasses?.toSet()
    val tonic = scale?.tonic

    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp))) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val layout = remember(baseOctave, octaves, widthPx, heightPx) { KeyboardLayout(baseOctave, octaves, widthPx, heightPx) }

        DisposableEffect(layout) {
            onDispose { pressed.keys.toList().forEach { synth()?.noteOff(it) }; pressed.clear() }
        }

        /** 0 = up, 1 = fully down, with a short travel either way. */
        fun depth(midi: Int): Float {
            val now = frame
            pressedAt[midi]?.let { if (pressed.containsKey(midi)) return min(1f, (now - it) / 40_000_000f) }
            releasedAt[midi]?.let { return (1f - (now - it) / 120_000_000f).coerceIn(0f, 1f) }
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
                        pressedAt[key.midi] = System.nanoTime()
                        releasedAt.remove(key.midi)
                    }
                    fun release(key: KeyboardLayout.Key) {
                        val n = (pressed[key.midi] ?: 1) - 1
                        if (n <= 0) { pressed.remove(key.midi); synth()?.noteOff(key.midi); releasedAt[key.midi] = System.nanoTime() } else pressed[key.midi] = n
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                val id = change.id
                                val current = byPointer[id]
                                if (change.pressed) {
                                    val key = layout.keyAt(change.position.x, change.position.y)
                                    if (key?.midi != current?.midi) {
                                        current?.let { release(it) }
                                        if (key != null) { byPointer[id] = key; press(key, change.position.y) } else byPointer.remove(id)
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
            val white = Color(0xFFF4F1EA)
            val whiteOut = Color(0xFFB9B6AF) // outside the chosen scale
            val whiteDown = Color(0xFFB7E9E0)
            val whiteLit = Color(0xFFDDF5F0)
            val black = Color(0xFF1B1D20)
            val blackDown = Color(0xFF2F8F82)
            val blackLit = Color(0xFF245C55)
            val edge = Color(0xFF6B6F74)
            val accent = Color(0xFF0F766E)
            val shadow = Color(0x33000000)
            val paint = android.graphics.Paint().apply { textSize = textSizePx; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true }

            layout.whiteKeys.forEach { k ->
                val pc = Math.floorMod(k.midi, 12)
                val d = depth(k.midi)
                val lit = pc in litPitchClasses
                val base = when {
                    pressed.containsKey(k.midi) -> whiteDown
                    lit -> whiteLit
                    inScale != null && pc !in inScale -> whiteOut
                    else -> white
                }
                // A pressed key sinks: its lit face shortens and a shadow band appears at the top.
                val top = d * 6f
                drawRoundRect(base, Offset(k.left + 1f, top), Size(k.width - 2f, k.height - top), CornerRadius(6f, 6f))
                if (d > 0f) drawRect(shadow.copy(alpha = 0.2f * d), Offset(k.left + 1f, top), Size(k.width - 2f, 10f * d))
                drawLine(edge, Offset(k.right, 0f), Offset(k.right, k.height), strokeWidth = 1f)
                if (pc == tonic) drawCircle(accent, radius = 5f, center = Offset(k.left + k.width / 2, k.height - textSizePx * 2.4f))
                if (k.midi == 60) { // middle C
                    drawCircle(Color(0xFF5A5E64), radius = 4f, center = Offset(k.left + k.width / 2, k.height - textSizePx * (if (showNames) 3.4f else 1.6f)))
                }
                if (showNames) {
                    paint.color = if (pc == tonic) android.graphics.Color.rgb(15, 118, 110) else android.graphics.Color.rgb(90, 94, 100)
                    drawContext.canvas.nativeCanvas.drawText(KeyboardLayout.noteName(k.midi), k.left + k.width / 2, k.height - textSizePx + top * 0.5f, paint)
                }
            }
            layout.blackKeys.forEach { k ->
                val pc = Math.floorMod(k.midi, 12)
                val d = depth(k.midi)
                val lit = pc in litPitchClasses
                val h = k.height * (1f - 0.06f * d)
                val colour = when {
                    pressed.containsKey(k.midi) -> blackDown
                    lit -> blackLit
                    else -> black
                }
                drawRoundRect(colour, Offset(k.left, 0f), Size(k.width, h), CornerRadius(5f, 5f))
                drawRoundRect(if (inScale != null && pc in inScale) accent else edge, Offset(k.left, 0f), Size(k.width, h), CornerRadius(5f, 5f), style = Stroke(if (inScale != null && pc in inScale) 2f else 1f))
                if (pc == tonic) drawCircle(accent, radius = 4f, center = Offset(k.left + k.width / 2, h - 14f))
            }
        }
    }
}
