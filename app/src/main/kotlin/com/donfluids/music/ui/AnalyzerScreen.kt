package com.donfluids.music.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.donfluids.music.music.Key
import com.donfluids.music.music.Notes
import com.donfluids.music.ui.theme.Tabular
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/** What the hero row shows: one key, chosen from the chord-based and note-based estimates. */
private class Answer(val key: Key?, val keyConfidence: Float, val keyFromChords: Boolean, val otherKey: Key?)

private fun answer(a: AnalysisResult): Answer {
    val songKey = a.songKey
    return if (songKey != null && a.songKeyConfidence >= 0.4f) {
        Answer(songKey, a.songKeyConfidence, true, a.key?.takeIf { it != songKey })
    } else {
        Answer(a.key, a.keyConfidence, false, songKey?.takeIf { it != a.key })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(viewModel: AnalyzerViewModel, dark: Boolean) {
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

    // Frame clock so the beat animates between analyser updates.
    var frameNanos by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(state.listening) {
        while (state.listening) withFrameNanos { frameNanos = it }
    }

    val a = state.analysis
    val ans = answer(a)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    PresetMenu(state.preset, viewModel::setPreset)
                    IconButton(onClick = { viewModel.setDarkTheme(!dark) }) {
                        Text(if (dark) "☀" else "☾", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OverflowMenu(
                        listening = state.listening,
                        recording = state.recording,
                        clipCount = state.clipCount,
                        onRecord = viewModel::startClip,
                        onStopClip = viewModel::stopClip,
                        onShare = viewModel::shareClips,
                        onDelete = viewModel::deleteClips,
                    )
                },
            )
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.listening) {
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(stringResource(R.string.new_song), style = MaterialTheme.typography.titleMedium)
                    }
                    FilledTonalButton(onClick = viewModel::stop, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text(stringResource(R.string.stop), style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    Button(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) viewModel.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.weight(1f).height(56.dp),
                    ) { Text(stringResource(R.string.listen), style = MaterialTheme.typography.titleMedium) }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StatusLine(state.listening, a, state.recording, state.recordingSeconds, state.error, viewModel::stopClip)
            HeroRow(ans, a, state.tempoMultiplier)
            HintLines(ans, a)
            BeatRow(a, state.tempoMultiplier, frameNanos)
            ans.key?.let { ChordsInKey(it, a) }
            if (a.chordSegments.isNotEmpty() || a.currentChord != null) ChordTimeline(a, ans.key)
            Details(a, ans, state.tempoMultiplier, viewModel::setTempoMultiplier)
            Spacer(Modifier.height(8.dp))
        }
    }

    state.clipAwaitingLabels?.let { LabelDialog(onSave = viewModel::saveLabels, onSkip = viewModel::skipLabels) }

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

// ---------- top bar menus ----------

@Composable
private fun PresetMenu(selected: AnalysisPreset, onSelect: (AnalysisPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("${selected.title} ▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AnalysisPreset.ALL.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(preset.title, fontWeight = if (preset == selected) FontWeight.SemiBold else FontWeight.Normal)
                            Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { open = false; onSelect(preset) },
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    listening: Boolean,
    recording: Boolean,
    clipCount: Int,
    onRecord: () -> Unit,
    onStopClip: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Text("⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (recording) {
                DropdownMenuItem(text = { Text(stringResource(R.string.stop_clip)) }, onClick = { open = false; onStopClip() })
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.record_clip)) },
                    enabled = listening,
                    onClick = { open = false; onRecord() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share_clips) + if (clipCount > 0) " ($clipCount)" else "") },
                enabled = clipCount > 0 && !recording,
                onClick = { open = false; onShare() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete_clips)) },
                enabled = clipCount > 0 && !recording,
                onClick = { open = false; confirmDelete = true },
            )
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

// ---------- main content ----------

@Composable
private fun StatusLine(listening: Boolean, a: AnalysisResult, recording: Boolean, recordingSeconds: Int, error: String?, onStopClip: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (listening) {
            // A dot that breathes with the input level.
            Box(
                Modifier
                    .size(8.dp)
                    .scale(0.7f + 0.6f * a.level)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            val s = a.secondsHeard.toInt()
            Text(
                "${stringResource(R.string.listening)} · %d:%02d".format(s / 60, s % 60),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = Tabular),
                color = muted,
            )
        } else {
            Text(error ?: stringResource(R.string.idle_hint), style = MaterialTheme.typography.bodyMedium, color = if (error != null) MaterialTheme.colorScheme.tertiary else muted)
        }
        Spacer(Modifier.weight(1f))
        if (recording) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f))
                    .clickable(onClick = onStopClip)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                Text("REC ${recordingSeconds}s", style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = Tabular), color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun HeroRow(ans: Answer, a: AnalysisResult, multiplier: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
        HeroValue(
            value = ans.key?.name ?: "–",
            sub = ans.key?.mode?.label,
            label = stringResource(R.string.label_key),
            confidence = if (ans.key != null) ans.keyConfidence else 0f,
            modifier = Modifier.weight(1.2f),
        )
        HeroValue(
            value = a.bpm?.let { (it * multiplier).roundToInt().toString() } ?: "–",
            sub = if (a.bpm != null && !a.tempoLocked) "settling" else null,
            label = stringResource(R.string.label_bpm),
            confidence = (a.tempoStrength * 2f).coerceIn(0f, 1f),
            modifier = Modifier.weight(1f),
        )
        HeroValue(
            value = a.meterLabel ?: "–",
            sub = if (a.meterCompound) "in 3s" else null,
            label = stringResource(R.string.label_time),
            confidence = a.meterConfidence,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A large value, a thin underline whose length shows confidence, and a label line. */
@Composable
private fun HeroValue(value: String, sub: String?, label: String, confidence: Float, modifier: Modifier = Modifier) {
    val known = value != "–"
    val settled = confidence >= 0.5f
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = Tabular),
            color = if (known) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(
                Modifier
                    .fillMaxWidth(confidence.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(if (settled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (sub != null) "$label · $sub" else label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun HintLines(ans: Answer, a: AnalysisResult) {
    val lines = buildList {
        ans.otherKey?.let { add(if (ans.keyFromChords) "the notes suggest ${it.label}" else "the chords suggest ${it.label}") }
        val cents = a.tuningCents.roundToInt()
        if (a.tuningConfidence >= 0.2f && abs(cents) >= 25) {
            add("singing ${abs(cents)}¢ ${if (cents > 0) "sharp" else "flat"}, between two keys")
        }
    }
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** One dot per beat in the bar; the current one is filled and pulses, the downbeat in coral. */
@Composable
private fun BeatRow(a: AnalysisResult, multiplier: Float, frameNanos: Long) {
    val bpm = a.bpm ?: return
    if (a.beatPeriodMillis <= 0f || bpm <= 0f) return
    val periodNanos = a.beatPeriodMillis * 1e6 / multiplier
    val beatsElapsed = (frameNanos - a.lastBeatAtNanos) / periodNanos
    val phase = (beatsElapsed - floor(beatsElapsed)).toFloat()
    val beatsPerBar = if (a.beatsPerBar > 0 && multiplier == 1f) a.beatsPerBar else 1
    val current = if (beatsPerBar > 1) Math.floorMod(a.lastBeatIndexInBar + floor(beatsElapsed).toInt(), beatsPerBar) else 0
    val flash = exp(-phase * 6f)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until beatsPerBar) {
            val active = i == current
            val colour = when {
                active && i == 0 && beatsPerBar > 1 -> MaterialTheme.colorScheme.tertiary
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                Modifier
                    .size(if (beatsPerBar > 4) 22.dp else 28.dp)
                    .scale(if (active) 1f + 0.3f * flash else 1f)
                    .clip(CircleShape)
                    .background(colour),
                contentAlignment = Alignment.Center,
            ) {
                if (beatsPerBar > 1) {
                    Text(
                        (i + 1).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChordsInKey(key: Key, a: AnalysisResult) {
    val now = a.currentChord
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        key.chords.forEachIndexed { i, chord ->
            val playing = now != null && now.root == key.scale[i] && (now.minor == (chord.quality == com.donfluids.music.music.ChordQuality.MINOR))
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            playing -> MaterialTheme.colorScheme.primaryContainer
                            i == 0 -> MaterialTheme.colorScheme.surfaceVariant
                            else -> Color.Transparent
                        },
                    )
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    chord.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (playing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                Text(chord.numeral, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChordTimeline(a: AnalysisResult, key: Key?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            a.currentChord?.name(key) ?: "–",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(48.dp),
        )
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState(), reverseScrolling = true),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            a.chordSegments.takeLast(30).forEach { seg ->
                val chord = seg.chord ?: return@forEach
                Text(
                    chord.name(key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

// ---------- details fold ----------

@Composable
private fun Details(a: AnalysisResult, ans: Answer, multiplier: Float, onMultiplier: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.details), style = MaterialTheme.typography.labelMedium, color = muted)
            Spacer(Modifier.weight(1f))
            Text(if (open) "▴" else "▾", color = muted)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                DetailRow("Tempo felt as") {
                    SingleChoiceSegmentedButtonRow {
                        listOf(0.5f to "÷2", 1f to "as heard", 2f to "×2").forEachIndexed { i, (m, label) ->
                            SegmentedButton(
                                selected = multiplier == m,
                                onClick = { onMultiplier(m) },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }
                DetailRow("Tuning") {
                    val cents = a.tuningCents.roundToInt()
                    Text(
                        when {
                            a.tuningConfidence < 0.2f -> "–"
                            abs(cents) < 10 -> "in tune with A = 440 Hz"
                            else -> "${abs(cents)}¢ ${if (cents > 0) "sharp" else "flat"} of A = 440 Hz"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                DetailRow("Key from notes") {
                    Text(a.key?.let { "${it.label} · ${pct(a.keyConfidence)}" } ?: "–", style = MaterialTheme.typography.bodyMedium)
                    if (a.keyAlternatives.isNotEmpty()) {
                        Text("also " + a.keyAlternatives.joinToString(", ") { it.first.label }, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
                DetailRow("Key from chords") {
                    val k = a.songKey
                    Text(k?.let { "${it.label} · ${pct(a.songKeyConfidence)}" } ?: "–", style = MaterialTheme.typography.bodyMedium)
                    if (k != null) {
                        val parts = buildList {
                            if (a.songKeyCadences > 0) add("${a.songKeyCadences} V–I cadence${if (a.songKeyCadences > 1) "s" else ""}")
                            a.songKeyAlternative?.let { add("or ${it.label}") }
                        }
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
                        Text(
                            a.chordShares.take(6).joinToString("  ") { (c, share) -> "${c.name(k)} ${c.numeralIn(k)} ${(share * 100).roundToInt()}%" },
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }
                DetailRow("Notes heard") { ChromaBars(a.chroma, ans.key) }
                if (a.meterScores.isNotEmpty()) {
                    DetailRow("Bar length") {
                        a.meterScores.entries.sortedByDescending { it.value }.take(4).forEach { (beats, score) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("$beats beats", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.width(56.dp))
                                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(score.coerceIn(0f, 1f))
                                            .height(4.dp)
                                            .background(if (beats == a.beatsPerBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

@Composable
private fun DetailRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun ChromaBars(chroma: List<Float>, key: Key?) {
    val inScale = key?.scale?.toSet() ?: emptySet()
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    Column {
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * 11) / 12
            chroma.forEachIndexed { i, v ->
                val h = size.height * v.coerceIn(0f, 1f)
                drawRoundRect(
                    color = if (i in inScale) primary else muted,
                    topLeft = Offset(i * (barWidth + gap), size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(3.dp.toPx()),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- dialogs ----------

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
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(stringResource(R.string.label_key_field)) }, singleLine = true)
                OutlinedTextField(value = tempo, onValueChange = { tempo = it }, label = { Text(stringResource(R.string.label_tempo)) }, singleLine = true)
                OutlinedTextField(value = meter, onValueChange = { meter = it }, label = { Text(stringResource(R.string.label_meter)) }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text(stringResource(R.string.label_notes)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, tempo, meter, notes) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.skip)) } },
    )
}
