package com.donfluids.piano

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.res.Configuration

@Composable
fun PianoScreen(synth: () -> PianoSynth?, error: String?) {
    var baseOctave by rememberSaveable { mutableStateOf(4) }
    var sustain by rememberSaveable { mutableStateOf(false) }
    var showNames by rememberSaveable { mutableStateOf(true) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val octaves = if (landscape) 2 else 1

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { baseOctave = (baseOctave - 1).coerceAtLeast(1) }, enabled = baseOctave > 1) {
                Text("−", fontSize = 18.sp)
            }
            Text(
                "C$baseOctave – B${baseOctave + octaves - 1}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(onClick = { baseOctave = (baseOctave + 1).coerceAtMost(7 - octaves + 1) }, enabled = baseOctave + octaves - 1 < 7) {
                Text("+", fontSize = 18.sp)
            }
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = sustain,
                onClick = { sustain = !sustain; synth()?.sustain = sustain },
                label = { Text(stringResource(R.string.sustain)) },
            )
            FilterChip(selected = showNames, onClick = { showNames = !showNames }, label = { Text(stringResource(R.string.note_names)) })
        }
        if (error != null) {
            Text(error, color = Color(0xFFF2997F), style = MaterialTheme.typography.bodyMedium)
        }
        Keyboard(
            baseOctave = baseOctave,
            octaves = octaves,
            showNames = showNames,
            synth = synth,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun Keyboard(baseOctave: Int, octaves: Int, showNames: Boolean, synth: () -> PianoSynth?, modifier: Modifier) {
    /** Pointers currently on each MIDI note; a key is lit while this is above zero. */
    val pressed = remember { mutableStateMapOf<Int, Int>() }
    val textSizePx = with(LocalDensity.current) { 12.sp.toPx() }

    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp))) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val layout = remember(baseOctave, octaves, widthPx, heightPx) { KeyboardLayout(baseOctave, octaves, widthPx, heightPx) }

        // Release everything when the layout changes under a finger (octave shift, rotation).
        DisposableEffect(layout) {
            onDispose {
                pressed.keys.toList().forEach { synth()?.noteOff(it) }
                pressed.clear()
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Piano keyboard, ${layout.whiteCount} white keys from ${KeyboardLayout.noteName(layout.baseMidi)}" }
                .pointerInput(layout) {
                    val byPointer = HashMap<PointerId, KeyboardLayout.Key>()
                    fun press(key: KeyboardLayout.Key, y: Float) {
                        // Further down the key is louder, a little like a real key.
                        val velocity = 0.55f + 0.45f * (y / key.height).coerceIn(0f, 1f)
                        synth()?.noteOn(key.midi, velocity)
                        pressed[key.midi] = (pressed[key.midi] ?: 0) + 1
                    }
                    fun release(key: KeyboardLayout.Key) {
                        val n = (pressed[key.midi] ?: 1) - 1
                        if (n <= 0) { pressed.remove(key.midi); synth()?.noteOff(key.midi) } else pressed[key.midi] = n
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
            val whiteDown = Color(0xFFB7E9E0)
            val black = Color(0xFF1B1D20)
            val blackDown = Color(0xFF2F8F82)
            val edge = Color(0xFF6B6F74)
            layout.whiteKeys.forEach { k ->
                drawRoundRect(
                    color = if (pressed.containsKey(k.midi)) whiteDown else white,
                    topLeft = Offset(k.left + 1f, 0f),
                    size = Size(k.width - 2f, k.height),
                    cornerRadius = CornerRadius(6f, 6f),
                )
                drawLine(edge, Offset(k.right, 0f), Offset(k.right, k.height), strokeWidth = 1f)
                if (showNames) {
                    drawContext.canvas.nativeCanvas.drawText(
                        KeyboardLayout.noteName(k.midi),
                        k.left + k.width / 2,
                        k.height - textSizePx,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.rgb(90, 94, 100)
                            textSize = textSizePx
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        },
                    )
                }
            }
            layout.blackKeys.forEach { k ->
                drawRoundRect(
                    color = if (pressed.containsKey(k.midi)) blackDown else black,
                    topLeft = Offset(k.left, 0f),
                    size = Size(k.width, k.height),
                    cornerRadius = CornerRadius(5f, 5f),
                )
                drawRoundRect(edge, Offset(k.left, 0f), Size(k.width, k.height), CornerRadius(5f, 5f), style = Stroke(1f))
            }
        }
    }
}
