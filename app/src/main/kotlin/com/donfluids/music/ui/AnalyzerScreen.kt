package com.donfluids.music.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donfluids.music.AnalyzerViewModel
import com.donfluids.music.R
import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.music.ChordLabel
import com.donfluids.music.music.Key
import com.donfluids.music.music.Notes
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun AnalyzerScreen(viewModel: AnalyzerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val deniedMessage = stringResource(R.string.permission_denied)

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start() else viewModel.reportPermissionDenied(deniedMessage)
    }

    val view = LocalView.current
    DisposableEffect(state.listening) {
        view.keepScreenOn = state.listening
        onDispose { view.keepScreenOn = false }
    }

    // A frame clock so the beat pulse animates between analyser updates.
    var frameNanos by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(state.listening) {
        while (state.listening) {
            withFrameNanos { frameNanos = it }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(
                listening = state.listening,
                level = state.analysis.level,
                onStart = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStop = viewModel::stop,
                onReset = viewModel::reset,
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
            }
            PresetRow(state.preset, viewModel::setPreset)
            KeyCard(state.analysis, state.listening)
            SongCard(state.analysis, state.listening)
            TempoCard(state.analysis, state.listening, state.tempoMultiplier, frameNanos, viewModel::cycleTempoMultiplier)
            MeterCard(state.analysis, state.listening)
            ClipsCard(
                listening = state.listening,
                recording = state.recording,
                recordingSeconds = state.recordingSeconds,
                clipCount = state.clipCount,
                onStart = viewModel::startClip,
                onStop = viewModel::stopClip,
                onShare = viewModel::shareClips,
                onDelete = viewModel::deleteClips,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    state.clipAwaitingLabels?.let {
        LabelDialog(onSave = viewModel::saveLabels, onSkip = viewModel::skipLabels)
    }

    // Hand a freshly zipped set of clips to the share sheet, then forget it.
    LaunchedEffect(state.shareFile) {
        val file = state.shareFile ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "LiveKey clips")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share clips"))
        viewModel.shareConsumed()
    }
}

@Composable
private fun ClipsCard(
    listening: Boolean,
    recording: Boolean,
    recordingSeconds: Int,
    clipCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    SectionCard(stringResource(R.string.clips_heading)) {
        Text(
            stringResource(R.string.clips_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recording) {
                Button(
                    onClick = onStop,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                ) { Text(stringResource(R.string.stop_clip) + "  ${recordingSeconds}s") }
            } else {
                Button(onClick = onStart, enabled = listening) { Text(stringResource(R.string.record_clip)) }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "$clipCount saved",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShare, enabled = clipCount > 0 && !recording) { Text(stringResource(R.string.share_clips)) }
            OutlinedButton(onClick = { confirmDelete = true }, enabled = clipCount > 0 && !recording) { Text(stringResource(R.string.delete_clips)) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_text)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun LabelDialog(onSave: (String, String, String, String) -> Unit, onSkip: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var tempo by remember { mutableStateOf("") }
    var meter by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.label_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(stringResource(R.string.label_key)) }, singleLine = true)
                OutlinedTextField(value = tempo, onValueChange = { tempo = it }, label = { Text(stringResource(R.string.label_tempo)) }, singleLine = true)
                OutlinedTextField(value = meter, onValueChange = { meter = it }, label = { Text(stringResource(R.string.label_meter)) }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text(stringResource(R.string.label_notes)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, tempo, meter, notes) }) { Text(stringResource(R.string.label_save)) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.label_skip)) } },
    )
}

@Composable
private fun Header(listening: Boolean, level: Float, onStart: () -> Unit, onStop: () -> Unit, onReset: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (listening) {
                OutlinedButton(onClick = onReset) { Text(stringResource(R.string.reset)) }
                Button(onClick = onStop) { Text(stringResource(R.string.stop_listening)) }
            } else {
                Button(onClick = onStart) { Text(stringResource(R.string.start_listening)) }
            }
        }
        LinearProgressIndicator(
            progress = { if (listening) level else 0f },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun PresetRow(selected: AnalysisPreset, onSelect: (AnalysisPreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalysisPreset.ALL.forEach { preset ->
                FilterChip(
                    selected = preset == selected,
                    onClick = { onSelect(preset) },
                    label = { Text(preset.title) },
                )
            }
        }
        Text(
            selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 2.sp,
            )
            content()
        }
    }
}

@Composable
private fun Placeholder(listening: Boolean) {
    Text(
        if (listening) stringResource(R.string.listening_placeholder) else stringResource(R.string.idle_placeholder),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConfidenceBar(confidence: Float, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { confidence.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun confidenceWord(c: Float): String = when {
    c >= 0.6f -> "sure"
    c >= 0.35f -> "likely"
    else -> "guessing"
}

@Composable
private fun KeyCard(a: AnalysisResult, listening: Boolean) {
    SectionCard(stringResource(R.string.key_heading)) {
        val key = a.key
        if (key == null) {
            Placeholder(listening)
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                key.name,
                fontSize = 64.sp,
                lineHeight = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(key.mode.label, style = MaterialTheme.typography.titleLarge)
                Text(
                    "relative: ${key.relative.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ConfidenceBar(a.keyConfidence, confidenceWord(a.keyConfidence))
        TuningLine(a)
        ScaleRow(key)
        ChordRow(key)
        ChromaBars(a.chroma, key)
        if (a.keyAlternatives.isNotEmpty()) {
            Text(
                "also possible: " + a.keyAlternatives.joinToString("  ·  ") { it.first.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TuningLine(a: AnalysisResult) {
    if (a.tuningConfidence < 0.2f) return
    val cents = a.tuningCents.roundToInt()
    val text = when {
        abs(cents) < 10 -> "in tune with A = 440 Hz"
        cents > 0 -> "singing about $cents cents sharp of A = 440"
        else -> "singing about ${-cents} cents flat of A = 440"
    }
    val warn = abs(cents) >= 30
    Text(
        if (warn) "$text – halfway between keys, listen before committing" else text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ScaleRow(key: Key) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        key.scaleNames.forEachIndexed { i, name ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (i == 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name,
                    fontWeight = FontWeight.SemiBold,
                    color = if (i == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ChordRow(key: Key) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        key.chords.forEach { chord ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    chord.numeral,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(chord.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ChromaBars(chroma: List<Float>, key: Key) {
    val inScale = key.scale.toSet()
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * 11) / 12
            chroma.forEachIndexed { i, v ->
                val h = size.height * v.coerceIn(0f, 1f)
                drawRoundRect(
                    color = if (i in inScale) primary else muted.copy(alpha = 0.5f),
                    topLeft = androidx.compose.ui.geometry.Offset(i * (barWidth + gap), size.height - h),
                    size = androidx.compose.ui.geometry.Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 12) {
                Text(
                    Notes.neutral(i),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i in inScale) MaterialTheme.colorScheme.onSurface else muted,
                )
            }
        }
    }
}

@Composable
private fun SongCard(a: AnalysisResult, listening: Boolean) {
    SectionCard(stringResource(R.string.song_heading)) {
        val spellingKey = a.songKey ?: a.key
        val segments = a.chordSegments
        if (segments.isEmpty() && a.currentChord == null) {
            Placeholder(listening)
            return@SectionCard
        }
        // Now playing.
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                a.currentChord?.name(spellingKey) ?: "–",
                fontSize = 48.sp,
                lineHeight = 48.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )
            Column(Modifier.padding(bottom = 6.dp)) {
                Text("now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val heard = segments.lastOrNull()?.endSeconds ?: 0f
                Text(
                    "%d:%02d of chords heard".format((heard / 60).toInt(), (heard % 60).toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Timeline of the most recent chords, newest on the right.
        val recent = segments.takeLast(24)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), reverseScrolling = true),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            recent.forEach { seg ->
                val chord = seg.chord ?: return@forEach
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(chord.name(spellingKey), fontWeight = FontWeight.SemiBold)
                    Text(
                        if (spellingKey != null) chord.numeralIn(spellingKey) else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Whole-song key suggestion.
        val songKey = a.songKey
        if (songKey != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Key from the chords: ${songKey.label}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            ConfidenceBar(a.songKeyConfidence, confidenceWord(a.songKeyConfidence))
            val top = a.chordShares.take(6)
            Text(
                top.joinToString("   ") { (chord, share) ->
                    "${chord.name(songKey)} ${chord.numeralIn(songKey)} ${(share * 100).roundToInt()}%"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val notes = buildList {
                if (a.songKeyCadences > 0) add("${a.songKeyCadences} V–I cadence" + if (a.songKeyCadences > 1) "s" else "")
                a.songKeyAlternative?.let { add("or ${it.label}") }
                a.key?.let { if (it != songKey) add("the note-based key card says ${it.label}") }
            }
            if (notes.isNotEmpty()) {
                Text(
                    notes.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (segments.isNotEmpty()) {
            Text(
                "A few more chords and a key will be suggested.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TempoCard(a: AnalysisResult, listening: Boolean, multiplier: Float, frameNanos: Long, onCycleMultiplier: () -> Unit) {
    SectionCard(stringResource(R.string.tempo_heading)) {
        val bpm = a.bpm
        if (bpm == null || a.beatPeriodMillis <= 0f) {
            Placeholder(listening)
            return@SectionCard
        }
        val periodNanos = a.beatPeriodMillis * 1e6 / multiplier
        val beatsElapsed = (frameNanos - a.lastBeatAtNanos) / periodNanos
        val phase = (beatsElapsed - floor(beatsElapsed)).toFloat()
        val beatsPerBar = a.beatsPerBar
        val currentBeat = if (beatsPerBar > 0 && multiplier == 1f) {
            Math.floorMod(a.lastBeatIndexInBar + floor(beatsElapsed).toInt(), beatsPerBar)
        } else -1

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                (bpm * multiplier).roundToInt().toString(),
                fontSize = 64.sp,
                lineHeight = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f)) {
                Text("BPM", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (a.tempoLocked) "steady" else "settling…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCycleMultiplier, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        when (multiplier) { 2f -> "×2 shown"; 0.5f -> "÷2 shown"; else -> "feel it ×2 / ÷2" },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            BeatPulse(phase = phase, downbeat = currentBeat == 0)
        }
        ConfidenceBar(a.tempoStrength * 2f, confidenceWord(a.tempoStrength * 2f))
        if (beatsPerBar > 0 && currentBeat >= 0) {
            BeatDots(beatsPerBar, currentBeat, a.meterCompound)
        }
    }
}

@Composable
private fun BeatPulse(phase: Float, downbeat: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(Modifier.size(72.dp)) {
        val flash = exp(-phase * 5f)
        val radius = size.minDimension / 2 * (0.6f + 0.4f * flash)
        drawCircle(
            color = (if (downbeat) accent else primary).copy(alpha = 0.25f + 0.75f * flash),
            radius = radius,
        )
        drawCircle(color = primary, radius = size.minDimension / 2 * 0.55f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun BeatDots(beatsPerBar: Int, current: Int, compound: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until beatsPerBar) {
            val active = i == current
            Box(
                modifier = Modifier
                    .size(if (active) 36.dp else 28.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            active && i == 0 -> MaterialTheme.colorScheme.tertiary
                            active -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (i + 1).toString(),
                    fontWeight = FontWeight.Bold,
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (compound) {
            Spacer(Modifier.width(4.dp))
            Text("each beat in 3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MeterCard(a: AnalysisResult, listening: Boolean) {
    SectionCard(stringResource(R.string.meter_heading)) {
        val label = a.meterLabel
        if (label == null) {
            Placeholder(listening)
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                label,
                fontSize = 56.sp,
                lineHeight = 56.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(min = 120.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                a.meterScores.entries.sortedByDescending { it.value }.take(4).forEach { (beats, score) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "$beats beats",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(52.dp),
                        )
                        LinearProgressIndicator(
                            progress = { score.coerceIn(0f, 1f) },
                            modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
                            color = if (beats == a.beatsPerBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            trackColor = Color.Transparent,
                        )
                    }
                }
            }
        }
        ConfidenceBar(a.meterConfidence, confidenceWord(a.meterConfidence))
    }
}
