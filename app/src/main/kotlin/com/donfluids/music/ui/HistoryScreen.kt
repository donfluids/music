package com.donfluids.music.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.donfluids.music.R
import com.donfluids.music.history.SongRecord
import com.donfluids.music.music.NoteNaming
import com.donfluids.music.ui.theme.Tabular
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

/** The saved songs, grouped by day, newest first. Back (or the arrow) returns to listening. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    songs: List<SongRecord>,
    naming: NoteNaming,
    onClose: () -> Unit,
    onDelete: (Long) -> Unit,
    onRestore: (SongRecord) -> Unit,
    onClear: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undo = stringResource(R.string.undo)
    // One tap deletes, so each delete can be taken back for a few seconds.
    val delete: (SongRecord) -> Unit = { song ->
        onDelete(song.atMillis)
        val message = context.getString(R.string.history_deleted, naming.label(song.key))
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onRestore(song)
        }
    }
    val shareTitle = stringResource(R.string.history_share_title)
    val days = songs.groupBy { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { BackArrow(onClose) },
                title = { Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    TextButton(
                        enabled = songs.isNotEmpty(),
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, shareTitle)
                                .putExtra(Intent.EXTRA_TEXT, historyText(context, songs, naming, zone, today))
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        },
                    ) { Text(stringResource(R.string.history_share)) }
                    TextButton(enabled = songs.isNotEmpty(), onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear)) }
                },
            )
        },
    ) { padding ->
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
                days.forEach { (day, daySongs) ->
                    item(key = "day-$day") {
                        Text(
                            dayLabel(context, day, today),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp).semantics { heading() },
                        )
                    }
                    items(daySongs, key = { it.atMillis }) { song ->
                        SongRow(song, naming, zone, delete)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_text)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SongRow(song: SongRecord, naming: NoteNaming, zone: ZoneId, onDelete: (SongRecord) -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val time = timeOf(song, zone)
    val key = naming.label(song.key)
    val deleteDescription = stringResource(R.string.history_delete_one, key, time)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(key, style = MaterialTheme.typography.titleLarge)
            Text(details(context, song, time), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = Tabular), color = muted)
            playedIn(context, song, naming)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
        }
        IconButton(onClick = { onDelete(song) }) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = deleteDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BackArrow(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
    }
}

private fun timeOf(song: SongRecord, zone: ZoneId): String =
    Instant.ofEpochMilli(song.atMillis).atZone(zone).toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun dayLabel(context: Context, day: LocalDate, today: LocalDate): String = when (day) {
    today -> context.getString(R.string.history_today)
    today.minusDays(1) -> context.getString(R.string.history_yesterday)
    else -> day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
}

/** "10:42 AM, 96 BPM, 4/4, 3 min" */
private fun details(context: Context, song: SongRecord, time: String): String = buildList {
    add(time)
    song.bpm?.let { add(context.getString(if (song.bpmTapped) R.string.history_bpm_tapped else R.string.history_bpm, it)) }
    song.meter?.let { add(it) }
    add(if (song.seconds >= 60) context.getString(R.string.history_minutes, (song.seconds + 30) / 60) else context.getString(R.string.history_seconds, song.seconds))
}.joinToString(", ")

/** "played in A major (+2)" when the player had transposed, else null. */
private fun playedIn(context: Context, song: SongRecord, naming: NoteNaming): String? =
    if (song.transpose == 0) null
    else context.getString(R.string.history_played_in, naming.label(song.key.transposed(song.transpose)), song.transpose)

/** The whole list as plain text, for a message or a note. */
internal fun historyText(context: Context, songs: List<SongRecord>, naming: NoteNaming, zone: ZoneId, today: LocalDate): String =
    buildString {
        append(context.getString(R.string.history_share_title))
        songs.groupBy { Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() }.forEach { (day, daySongs) ->
            append("\n\n").append(dayLabel(context, day, today))
            daySongs.forEach { song ->
                append("\n").append(naming.label(song.key)).append(", ").append(details(context, song, timeOf(song, zone)))
                playedIn(context, song, naming)?.let { append(". ").append(it) }
            }
        }
    }
