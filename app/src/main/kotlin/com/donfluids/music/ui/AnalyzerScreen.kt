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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.donfluids.music.AnalyzerViewModel
import com.donfluids.music.R
import com.donfluids.music.UiState
import com.donfluids.music.dsp.AnalysisPreset
import com.donfluids.music.dsp.AnalysisResult
import com.donfluids.music.dsp.KeyLevelTracker
import com.donfluids.music.music.ChordLabel
import com.donfluids.music.music.ChordQuality
import com.donfluids.music.music.Key
import com.donfluids.music.music.Notes
import com.donfluids.music.ui.theme.Tabular
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/** Everything the screen shows, after Hold and transposition have been applied. */
private class Shown(
    /** The key to play in (transposed), or null. */
    val key: Key?,
    val keyConfidence: Float,
    /**
     * How settled the shown key is, 0..1: it fills as the key holds steady, and stops at half
     * while the app is still unsure of the key or of major against minor.
     */
    val keySettled: Float,
    val keyHeld: Boolean,
    /** Null once the singing has shown which is home; otherwise the relative key, transposed. */
    val orRelative: Key?,
    /** The singing does not yet point clearly to one key: what is shown is a best guess. */
    val bestGuess: Boolean,
    /** Where wandering singers are now, transposed; null when they are in the shown key. */
    val now: Key?,
    /** The chord sounding now, transposed. */
    val chord: ChordLabel?,
    val transpose: Int,
    val nashville: Boolean,
) {
    fun chordName(c: ChordLabel): String = if (nashville && key != null) c.nashvilleIn(key) else c.name(key)
}

private fun shown(s: UiState): Shown {
    val a = s.analysis
    val base = s.heldKey ?: a.displayKey
    return Shown(
        key = base?.transposed(s.transpose),
        keyConfidence = a.displayKeyConfidence,
        keySettled = when {
            s.heldKey != null -> 1f
            base == null -> 0f
            else -> (a.keyHeldSeconds / KEY_SETTLE_SECONDS).coerceIn(0f, 1f) * (if (a.pairDecided && a.modeDecided) 1f else 0.5f)
        },
        keyHeld = s.heldKey != null,
        orRelative = if (s.heldKey == null && a.pairDecided && !a.modeDecided) base?.relative?.transposed(s.transpose) else null,
        bestGuess = s.heldKey == null && base != null && !a.pairDecided,
        now = a.keyNow?.takeIf { s.heldKey == null && it != a.displayKey }?.transposed(s.transpose),
        chord = a.currentChord?.transposed(s.transpose),
        transpose = s.transpose,
        nashville = s.nashville,
    )
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

    val a = state.analysis
    val sh = shown(state)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    PresetMenu(state.preset, viewModel::setPreset)
                    ThemeToggle(dark) { viewModel.setDarkTheme(!dark) }
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
            BottomBar(
                listening = state.listening,
                held = sh.keyHeld,
                canHold = sh.key != null,
                onListen = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) viewModel.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStop = viewModel::stop,
                onNewSong = viewModel::reset,
                onHold = viewModel::toggleHold,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            StatusLine(state, viewModel::stopClip)
            KeyAndChordRow(sh)
            // Drums and strums give a pulse to read; singing alone rarely does, so for voices the
            // row shows how far off pitch the singers are and a tempo the player taps in.
            if (state.preset.readsBeat) TempoAndTimeRow(a, state.tempoMultiplier, state.listening, viewModel::setTempoMultiplier)
            else PitchAndTapRow(a, state, viewModel::tapBeat, viewModel::clearTapTempo)
            HintLines(sh, a, showTuning = state.preset.readsBeat)
            sh.key?.let { key ->
                TransposeRow(sh, viewModel::setTranspose, viewModel::setNashville)
                ChordsInKey(key, sh)
            }
            if (a.chordSegments.isNotEmpty() || a.currentChord != null) ChordTimeline(a, sh)
            Details(a, sh, showMeter = state.preset.readsBeat)
            Spacer(Modifier.height(8.dp))
        }
    }

    state.clipAwaitingLabels?.let { LabelDialog(onSave = viewModel::saveLabels, onSkip = viewModel::skipLabels) }
    if (state.clipNoticePending) ClipNoticeDialog(onAccept = viewModel::acceptClipNotice, onDismiss = viewModel::dismissClipNotice)

    val shareTitle = stringResource(R.string.share_clips_title)
    LaunchedEffect(state.shareFile) {
        val file = state.shareFile ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, shareTitle)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, shareTitle))
        viewModel.shareConsumed()
    }
}

// ---------- top and bottom bars ----------

@Composable
private fun PresetMenu(selected: AnalysisPreset, onSelect: (AnalysisPreset) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.preset_menu)
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "$description: ${selected.title}" }) {
            Text("${selected.title} ▾", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
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

/** A drawn sun or moon, so it renders the same on every phone and has a real accessible name. */
@Composable
private fun ThemeToggle(dark: Boolean, onToggle: () -> Unit) {
    val description = stringResource(if (dark) R.string.theme_to_light else R.string.theme_to_dark)
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    val ground = MaterialTheme.colorScheme.background
    IconButton(onClick = onToggle, modifier = Modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.size(20.dp)) {
            val r = size.minDimension / 2
            if (dark) {
                // A sun: switch to light.
                drawCircle(colour, radius = r * 0.55f)
                for (i in 0 until 8) {
                    val angle = i * Math.PI / 4
                    val from = Offset(center.x + (r * 0.75f * Math.cos(angle)).toFloat(), center.y + (r * 0.75f * Math.sin(angle)).toFloat())
                    val to = Offset(center.x + (r * Math.cos(angle)).toFloat(), center.y + (r * Math.sin(angle)).toFloat())
                    drawLine(colour, from, to, strokeWidth = 2.dp.toPx())
                }
            } else {
                // A crescent moon: a disc with a background-coloured disc bitten out of it.
                drawCircle(colour, radius = r * 0.8f)
                drawCircle(ground, radius = r * 0.7f, center = Offset(center.x + r * 0.45f, center.y - r * 0.3f))
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
    val description = stringResource(R.string.more)
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) {
            Canvas(Modifier.size(20.dp)) {
                val r = 2.dp.toPx()
                for (i in -1..1) drawCircle(colour, radius = r, center = Offset(center.x, center.y + i * 6.dp.toPx()))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (recording) {
                DropdownMenuItem(text = { Text(stringResource(R.string.stop_clip)) }, onClick = { open = false; onStopClip() })
            } else {
                DropdownMenuItem(text = { Text(stringResource(R.string.record_clip)) }, enabled = listening, onClick = { open = false; onRecord() })
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

@Composable
private fun BottomBar(
    listening: Boolean,
    held: Boolean,
    canHold: Boolean,
    onListen: () -> Unit,
    onStop: () -> Unit,
    onNewSong: () -> Unit,
    onHold: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (listening) {
            OutlinedButton(onClick = onNewSong, modifier = Modifier.weight(1f).height(56.dp)) {
                Text(stringResource(R.string.new_song), style = MaterialTheme.typography.titleMedium)
            }
            if (held) {
                Button(onClick = onHold, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text(stringResource(R.string.held), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                FilledTonalButton(onClick = onHold, enabled = canHold, modifier = Modifier.weight(1f).height(56.dp)) {
                    Text(stringResource(R.string.hold), style = MaterialTheme.typography.titleMedium)
                }
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(0.8f).height(56.dp)) {
                Text(stringResource(R.string.stop), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Button(onClick = onListen, modifier = Modifier.weight(1f).height(56.dp)) {
                Text(stringResource(R.string.listen), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ---------- main content ----------

@Composable
private fun StatusLine(state: UiState, onStopClip: () -> Unit) {
    val a = state.analysis
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.error != null -> Text(
                state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            state.listening -> {
                val level = a.level
                Box(
                    Modifier
                        .size(8.dp)
                        .graphicsLayer { val s = 0.7f + 0.6f * level; scaleX = s; scaleY = s }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                val s = a.secondsHeard.toInt()
                Text(
                    stringResource(R.string.listening) + " · " + String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = Tabular),
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
            }
            a.key != null || a.bpm != null -> Text(stringResource(R.string.stopped_last_result), style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f))
            else -> Text(stringResource(R.string.idle_hint), style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f))
        }
        if (state.recording) {
            Row(
                Modifier
                    .sizeIn(minHeight = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .clickable(onClick = onStopClip, role = Role.Button)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                Text(
                    stringResource(R.string.rec) + " " + stringResource(R.string.seconds_short, state.recordingSeconds),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = Tabular),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

/** Row 1: the key to play in, and the chord sounding now. Both large; these are what the player reads. */
@Composable
private fun KeyAndChordRow(sh: Shown) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
        HeroValue(
            value = sh.key?.name ?: "–",
            sub = when {
                sh.keyHeld && sh.key != null -> sh.key.mode.label + " · " + stringResource(R.string.sub_held)
                // Singers who have wandered: the big key is the centre, and this says where they are now.
                sh.key != null && sh.now != null -> stringResource(R.string.sub_now, sh.key.mode.label, sh.now.name)
                sh.key != null && sh.bestGuess -> stringResource(R.string.sub_best_guess, sh.key.mode.label)
                sh.key != null && sh.orRelative != null -> stringResource(R.string.sub_or_relative, sh.key.mode.label, sh.orRelative.label)
                sh.key != null -> sh.key.mode.label
                else -> null
            },
            label = stringResource(R.string.label_key),
            confidence = sh.keySettled,
            modifier = Modifier.weight(1f),
        )
        HeroValue(
            value = sh.chord?.let { sh.chordName(it) } ?: "–",
            sub = null,
            label = stringResource(R.string.label_chord),
            confidence = if (sh.chord != null) 1f else 0f,
            accent = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Row 2 for singing: how far off pitch the singers are, and a tempo the player taps in. */
@Composable
private fun PitchAndTapRow(a: AnalysisResult, state: UiState, onTap: () -> Unit, onClear: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        PitchCell(a, Modifier.weight(1f))
        TapCell(state, onTap, onClear, Modifier.weight(1f))
    }
}

/** Cents sharp or flat of the key the singers are in now, as a number and a tuner needle. */
@Composable
private fun PitchCell(a: AnalysisResult, modifier: Modifier) {
    val known = a.sungNotes.size >= 4 || a.tuningConfidence >= 0.2f
    val cents = a.tuningCents.roundToInt()
    val sub = when {
        !known -> null
        abs(cents) < IN_TUNE_CENTS -> stringResource(R.string.sub_in_tune)
        cents > 0 -> stringResource(R.string.sub_sharp)
        else -> stringResource(R.string.sub_flat)
    }
    // Nudged down to sit level with the tap button's padded contents beside it.
    Column(modifier.padding(top = TAP_PAD_V)) {
        HeroValue(
            value = if (known) signedCents(cents) else "–",
            sub = sub,
            label = stringResource(R.string.label_pitch),
            confidence = if (known) 1f else 0f,
            showBar = false,
        )
        Spacer(Modifier.height(8.dp))
        TunerBar(if (known) cents else null)
    }
}

private fun signedCents(c: Int) = when {
    c > 0 -> "+$c¢"
    c < 0 -> "\u2212${-c}¢"
    else -> "0¢"
}

/** A tuner: a track from 50 cents flat to 50 sharp, the in-tune zone shaded, a dot where the singers are. */
@Composable
private fun TunerBar(cents: Int?) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val zone = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val tick = MaterialTheme.colorScheme.outline
    val inTune = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onBackground
    Canvas(Modifier.fillMaxWidth().height(26.dp).clearAndSetSemantics { }) {
        val mid = size.height / 2
        drawRoundRect(track, topLeft = Offset(0f, mid - 3.dp.toPx()), size = Size(size.width, 6.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()))
        val zoneHalf = size.width / 2 * IN_TUNE_CENTS / 50f
        drawRect(zone, topLeft = Offset(size.width / 2 - zoneHalf, 0f), size = Size(2 * zoneHalf, size.height))
        drawLine(tick, Offset(size.width / 2, 2.dp.toPx()), Offset(size.width / 2, size.height - 2.dp.toPx()), strokeWidth = 2.dp.toPx())
        if (cents != null) {
            val x = size.width / 2 + size.width / 2 * (cents.coerceIn(-50, 50) / 50f)
            drawCircle(if (abs(cents) < IN_TUNE_CENTS) inTune else off, radius = 7.dp.toPx(), center = Offset(x, mid))
        }
    }
}

/** Tap on the beat: the tempo shows from the second tap and pulses; a long press clears it. */
@Composable
private fun TapCell(state: UiState, onTap: () -> Unit, onClear: () -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    val bpm = state.tapBpm
    val sub = when {
        bpm == null && state.tapCount == 0 -> stringResource(R.string.sub_tap_the_beat)
        state.tapCount < 4 -> stringResource(R.string.sub_keep_tapping)
        else -> stringResource(R.string.sub_hold_to_clear)
    }
    // A soft button: it must read as something to tap, whether it shows "Tap" or a tempo.
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.tap_action),
                onLongClickLabel = stringResource(R.string.tap_clear),
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTap() },
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onClear() },
            )
            .padding(horizontal = 14.dp, vertical = TAP_PAD_V),
    ) {
        HeroValue(
            value = bpm?.roundToInt()?.toString() ?: stringResource(R.string.tap_value),
            sub = sub,
            label = stringResource(R.string.label_bpm),
            confidence = (state.tapCount / 4f).coerceIn(0f, 1f),
            accent = bpm == null,
            showBar = false,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.height(26.dp), contentAlignment = Alignment.CenterStart) {
            if (bpm != null) PulseDot(bpm, state.tapAnchorNanos)
        }
    }
}

/** One dot that swells on each tapped beat, kept in step with the last tap. */
@Composable
private fun PulseDot(bpm: Float, anchorNanos: Long) {
    val frame = remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(bpm, anchorNanos) { while (true) withFrameNanos { frame.longValue = it } }
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(22.dp)
            .clearAndSetSemantics { }
            .graphicsLayer {
                val period = 60e9 / bpm
                val elapsed = (frame.value - anchorNanos) / period
                val phase = (elapsed - floor(elapsed)).toFloat()
                val s = 1f + 0.35f * exp(-phase * 6f)
                scaleX = s; scaleY = s
                alpha = 0.5f + 0.5f * exp(-phase * 4f)
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawCircle(primary) }
    }
}

/** Row 2: tempo with beat dots (tap the number to feel it double or half), and the time signature. */
@Composable
private fun TempoAndTimeRow(a: AnalysisResult, multiplier: Float, listening: Boolean, onMultiplier: (Float) -> Unit) {
    val tapLabel = stringResource(R.string.tap_tempo_feel)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            val next = when (multiplier) { 1f -> 2f; 2f -> 0.5f; else -> 1f }
            HeroValue(
                value = a.bpm?.let { (it * multiplier).roundToInt().toString() } ?: "–",
                sub = when {
                    multiplier == 2f -> stringResource(R.string.sub_double)
                    multiplier == 0.5f -> stringResource(R.string.sub_half)
                    a.bpm != null && !a.tempoLocked -> stringResource(R.string.sub_settling)
                    else -> null
                },
                label = stringResource(R.string.label_bpm),
                confidence = (a.tempoStrength * 2f).coerceIn(0f, 1f),
                modifier = Modifier.clickable(enabled = a.bpm != null, role = Role.Button, onClickLabel = tapLabel) { onMultiplier(next) },
            )
            if (a.bpm != null) {
                Spacer(Modifier.height(8.dp))
                BeatRow(a, multiplier, listening)
            }
        }
        HeroValue(
            value = a.meterLabel ?: "–",
            sub = if (a.meterCompound) stringResource(R.string.sub_in_threes) else null,
            label = stringResource(R.string.label_time),
            confidence = a.meterConfidence,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A large value, a thin underline whose length shows confidence, and a label line. */
@Composable
private fun HeroValue(
    value: String,
    sub: String?,
    label: String,
    confidence: Float,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    /** The thin confidence line; off where a cell draws its own gauge underneath. */
    showBar: Boolean = true,
) {
    val known = value != "–"
    val settled = confidence >= 0.5f
    val unknown = stringResource(R.string.unknown)
    val description = stringResource(R.string.hero_description, label, if (known) value + (sub?.let { " $it" } ?: "") else unknown, (confidence * 100).roundToInt())
    val colour = when {
        !known -> MaterialTheme.colorScheme.outline
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onBackground
    }
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        // Shrinks for long names such as "Bbmaj7" so nothing runs off the column.
        BasicText(
            value,
            style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = Tabular, color = colour),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 56.sp, stepSize = 2.sp),
            modifier = Modifier.fillMaxWidth().height(60.dp),
        )
        Spacer(Modifier.height(6.dp))
        if (showBar) Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(
                Modifier
                    .fillMaxWidth(confidence.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(if (settled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
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
private fun HintLines(sh: Shown, a: AnalysisResult, showTuning: Boolean = true) {
    val other = if (a.displayKeyFromChords) a.key?.takeIf { it != a.displayKey } else a.songKey?.takeIf { it != a.displayKey }
    val lines = buildList {
        if (!sh.keyHeld && other != null) {
            val name = other.transposed(sh.transpose).label
            add(if (a.displayKeyFromChords) stringResource(R.string.hint_notes_suggest, name) else stringResource(R.string.hint_chords_suggest, name))
        }
        if (sh.key != null && sh.orRelative != null && a.secondsHeard >= 4f) {
            add(stringResource(R.string.hint_major_or_minor, sh.key.label, sh.orRelative.label))
        }
        if (sh.key != null && !sh.keyHeld) {
            val now = (a.keyNow ?: a.displayKey)?.transposed(sh.transpose)
            val low = a.keyRangeLow?.transposed(sh.transpose)
            val high = a.keyRangeHigh?.transposed(sh.transpose)
            when {
                a.keyMove == KeyLevelTracker.Move.SETTLED && sh.now != null -> add(stringResource(R.string.hint_settled, sh.now.label))
                a.keyMove == KeyLevelTracker.Move.RISING && now != null -> add(stringResource(R.string.hint_rising, now.label))
                a.keyMove == KeyLevelTracker.Move.FALLING && now != null -> add(stringResource(R.string.hint_falling, now.label))
                low != null && high != null && low != high -> add(stringResource(R.string.hint_wandering, low.name, high.name, sh.key.label))
            }
        }
        if (sh.key != null && sh.bestGuess && a.secondsHeard >= 4f) {
            val spread = a.singerSpreadCents.roundToInt()
            add(if (spread >= 25) stringResource(R.string.hint_best_guess_scattered, spread) else stringResource(R.string.hint_best_guess))
        }
        // How far the singers sit from the key they are in now. A whole semitone or more of
        // wandering shows as "now" instead.
        val cents = a.tuningCents.roundToInt()
        if (showTuning && a.tuningConfidence >= 0.2f && abs(cents) >= 25) {
            add(if (cents > 0) stringResource(R.string.hint_tuning_sharp, cents) else stringResource(R.string.hint_tuning_flat, -cents))
        }
    }
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * One dot per beat in the bar; the current one is filled and pulses, the downbeat in coral.
 * The frame clock lives here so the pulse never recomposes anything else.
 */
@Composable
private fun BeatRow(a: AnalysisResult, multiplier: Float, listening: Boolean) {
    val frame = remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(listening, a.bpm != null) {
        while (listening && a.bpm != null) withFrameNanos { frame.longValue = it }
    }
    val beatsPerBar = if (a.beatsPerBar > 0 && multiplier == 1f) a.beatsPerBar else 1
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { },
    ) {
        for (i in 0 until beatsPerBar) BeatDot(i, beatsPerBar, a, multiplier, frame)
    }
}

@Composable
private fun BeatDot(index: Int, beatsPerBar: Int, a: AnalysisResult, multiplier: Float, frame: State<Long>) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val idle = MaterialTheme.colorScheme.surfaceVariant
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onTertiary = MaterialTheme.colorScheme.onTertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dotSize = if (beatsPerBar > 4) 22.dp else 26.dp
    Box(
        Modifier
            .size(dotSize)
            .graphicsLayer {
                // Read the frame clock only here, in the draw phase.
                val periodNanos = a.beatPeriodMillis * 1e6 / multiplier
                val elapsed = (frame.value - a.lastBeatAtNanos) / periodNanos
                val phase = (elapsed - floor(elapsed)).toFloat()
                val current = if (beatsPerBar > 1) Math.floorMod(a.lastBeatIndexInBar + floor(elapsed).toInt(), beatsPerBar) else 0
                val s = if (index == current) 1f + 0.3f * exp(-phase * 6f) else 1f
                scaleX = s; scaleY = s
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val periodNanos = a.beatPeriodMillis * 1e6 / multiplier
            val elapsed = (frame.value - a.lastBeatAtNanos) / periodNanos
            val current = if (beatsPerBar > 1) Math.floorMod(a.lastBeatIndexInBar + floor(elapsed).toInt(), beatsPerBar) else 0
            val active = index == current
            drawCircle(if (active && index == 0 && beatsPerBar > 1) tertiary else if (active) primary else idle)
        }
        if (beatsPerBar > 1) {
            // Two copies of the digit, one in each colour; the draw phase decides which shows.
            fun isActive(): Boolean {
                val periodNanos = a.beatPeriodMillis * 1e6 / multiplier
                val elapsed = (frame.value - a.lastBeatAtNanos) / periodNanos
                return index == Math.floorMod(a.lastBeatIndexInBar + floor(elapsed).toInt(), beatsPerBar)
            }
            Text(
                (index + 1).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.graphicsLayer { alpha = if (isActive()) 0f else 1f },
            )
            Text(
                (index + 1).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (index == 0) onTertiary else onPrimary,
                modifier = Modifier.graphicsLayer { alpha = if (isActive()) 1f else 0f },
            )
        }
    }
}

@Composable
private fun TransposeRow(sh: Shown, onTranspose: (Int) -> Unit, onNashville: (Boolean) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val key = sh.key ?: return
    val sounding = key.transposed(-sh.transpose)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.transpose), style = MaterialTheme.typography.labelMedium, color = muted)
        StepButton("−", stringResource(R.string.transpose_down), enabled = sh.transpose > -6) { onTranspose(sh.transpose - 1) }
        Text(
            String.format(Locale.ROOT, "%+d", sh.transpose),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = Tabular),
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Center,
        )
        StepButton("+", stringResource(R.string.transpose_up), enabled = sh.transpose < 6) { onTranspose(sh.transpose + 1) }
        Spacer(Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow(Modifier.height(36.dp)) {
            SegmentedButton(selected = !sh.nashville, onClick = { onNashville(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                Text(stringResource(R.string.letters), style = MaterialTheme.typography.labelMedium)
            }
            SegmentedButton(selected = sh.nashville, onClick = { onNashville(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                Text(stringResource(R.string.numbers), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (sh.transpose != 0) {
        Text(
            stringResource(R.string.transpose_play, sounding.label, key.label, sh.transpose),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

@Composable
private fun StepButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
    }
}

@Composable
private fun ChordsInKey(key: Key, sh: Shown) {
    val now = sh.chord
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        key.chords.forEachIndexed { i, chord ->
            val playing = now != null && now.root == key.scale[i] && now.minor == (chord.quality == ChordQuality.MINOR)
            val name = if (sh.nashville) (i + 1).toString() + chord.quality.suffix else chord.name
            val description = if (playing) stringResource(R.string.chord_playing, name) else name
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
                    .padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) { selected = playing; contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (playing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
                Text(chord.numeral, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChordTimeline(a: AnalysisResult, sh: Shown) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState(), reverseScrolling = true),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        a.chordSegments.takeLast(30).forEach { seg ->
            val chord = seg.chord?.transposed(sh.transpose) ?: return@forEach
            Text(
                sh.chordName(chord),
                style = MaterialTheme.typography.bodyLarge,
                color = muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

// ---------- details fold ----------

@Composable
private fun Details(a: AnalysisResult, sh: Shown, showMeter: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val expanded = stringResource(R.string.details_expanded)
    val collapsed = stringResource(R.string.details_collapsed)
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) expanded else collapsed },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.details), style = MaterialTheme.typography.labelMedium, color = muted)
            Spacer(Modifier.weight(1f))
            Text(if (open) "▴" else "▾", color = muted, modifier = Modifier.clearAndSetSemantics { })
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                DetailRow(stringResource(R.string.tuning)) {
                    val cents = a.tuningCents.roundToInt()
                    Text(
                        when {
                            a.tuningConfidence < 0.2f -> stringResource(R.string.tuning_unknown)
                            abs(cents) < 10 -> stringResource(R.string.tuning_in_tune)
                            cents > 0 -> stringResource(R.string.tuning_sharp, cents)
                            else -> stringResource(R.string.tuning_flat, -cents)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                DetailRow(stringResource(R.string.key_from_notes)) {
                    Text(a.key?.let { "${it.transposed(sh.transpose).label} · ${pct(a.keyConfidence)}" } ?: "–", style = MaterialTheme.typography.bodyMedium)
                    if (a.keyAlternatives.isNotEmpty()) {
                        Text(stringResource(R.string.also, a.keyAlternatives.joinToString(", ") { it.first.transposed(sh.transpose).label }), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
                DetailRow(stringResource(R.string.key_from_chords)) {
                    val k = a.songKey
                    Text(k?.let { "${it.transposed(sh.transpose).label} · ${pct(a.songKeyConfidence)}" } ?: "–", style = MaterialTheme.typography.bodyMedium)
                    if (k != null) {
                        val parts = buildList {
                            if (a.songKeyCadences > 0) add(pluralStringResource(R.plurals.cadences, a.songKeyCadences, a.songKeyCadences))
                            a.songKeyAlternative?.let { add(stringResource(R.string.or_key, it.transposed(sh.transpose).label)) }
                        }
                        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
                        val shownKey = k.transposed(sh.transpose)
                        Text(
                            a.chordShares.take(6).joinToString("  ") { (c, share) -> "${c.transposed(sh.transpose).name(shownKey)} ${c.numeralIn(k)} ${(share * 100).roundToInt()}%" },
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }
                DetailRow(stringResource(R.string.notes_heard)) { ChromaBars(a.chroma, sh.key?.transposed(-sh.transpose)) }
                if (showMeter && a.meterScores.isNotEmpty()) {
                    DetailRow(stringResource(R.string.bar_length)) {
                        a.meterScores.entries.sortedByDescending { it.value }.take(4).forEach { (beats, score) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.beats_n, beats), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.width(64.dp))
                                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(score.coerceIn(0f, 1f))
                                            .height(4.dp)
                                            .background(if (beats == a.beatsPerBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant),
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
    Column(Modifier.clearAndSetSemantics { }) {
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
    var key by rememberSaveable { mutableStateOf("") }
    var tempo by rememberSaveable { mutableStateOf("") }
    var meter by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.label_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(stringResource(R.string.label_key_field)) }, singleLine = true)
                OutlinedTextField(value = tempo, onValueChange = { tempo = it }, label = { Text(stringResource(R.string.label_tempo)) }, singleLine = true)
                OutlinedTextField(value = meter, onValueChange = { meter = it }, label = { Text(stringResource(R.string.label_meter)) }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it.take(500) }, label = { Text(stringResource(R.string.label_notes)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, tempo, meter, notes) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(R.string.skip)) } },
    )
}

@Composable
private fun ClipNoticeDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clip_notice_title)) },
        text = { Text(stringResource(R.string.clip_notice_text)) },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.clip_notice_accept)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Seconds a key must hold before its underline is full. */
private const val KEY_SETTLE_SECONDS = 6f

/** Within this many cents of the key the singers count as in tune. */
private const val IN_TUNE_CENTS = 10

/** Vertical padding inside the tap button, matched by the pitch cell so their numbers line up. */
private val TAP_PAD_V = 6.dp
