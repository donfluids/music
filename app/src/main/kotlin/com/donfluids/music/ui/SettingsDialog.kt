package com.donfluids.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.donfluids.music.MAX_REFERENCE_HZ
import com.donfluids.music.MIN_REFERENCE_HZ
import com.donfluids.music.R
import com.donfluids.music.music.NoteNaming
import com.donfluids.music.ui.theme.Tabular

/** The tuning reference, note names, chord names or numbers, how notes are heard, and the theme. Each is saved as soon as it changes. */
@Composable
fun SettingsDialog(
    referenceHz: Int,
    naming: NoteNaming,
    nashville: Boolean,
    crepe: Boolean,
    basicPitch: Boolean,
    onReference: (Int) -> Unit,
    onNaming: (NoteNaming) -> Unit,
    onNashville: (Boolean) -> Unit,
    onCrepe: (Boolean) -> Unit,
    onBasicPitch: (Boolean) -> Unit,
    /** Dark, light, or null to follow the system. */
    theme: Boolean?,
    onTheme: (Boolean?) -> Unit,
    onDone: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            // Scrolls on short screens or with large text.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_reference), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepButton(R.drawable.ic_remove, stringResource(R.string.settings_reference_down), enabled = referenceHz > MIN_REFERENCE_HZ) { onReference(referenceHz - 1) }
                    Text(
                        stringResource(R.string.settings_reference_value, referenceHz),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = Tabular),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(96.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    StepButton(R.drawable.ic_add, stringResource(R.string.settings_reference_up), enabled = referenceHz < MAX_REFERENCE_HZ) { onReference(referenceHz + 1) }
                    if (referenceHz != 440) TextButton(onClick = { onReference(440) }) { Text(stringResource(R.string.settings_reference_reset)) }
                }
                Text(stringResource(R.string.settings_reference_help), style = MaterialTheme.typography.bodySmall, color = muted)

                Text(stringResource(R.string.settings_naming), style = MaterialTheme.typography.labelLarge)
                TwoWay(
                    first = stringResource(R.string.naming_letters),
                    second = stringResource(R.string.naming_do_re_mi),
                    secondSelected = naming == NoteNaming.FIXED_DO,
                    onSelect = { onNaming(if (it) NoteNaming.FIXED_DO else NoteNaming.LETTERS) },
                )
                Text(stringResource(R.string.settings_naming_help), style = MaterialTheme.typography.bodySmall, color = muted)

                Text(stringResource(R.string.settings_chords), style = MaterialTheme.typography.labelLarge)
                TwoWay(
                    first = stringResource(R.string.chords_as_names),
                    second = stringResource(R.string.chords_as_numbers),
                    secondSelected = nashville,
                    onSelect = onNashville,
                )
                Text(stringResource(R.string.settings_chords_help), style = MaterialTheme.typography.bodySmall, color = muted)

                Text(stringResource(R.string.settings_notes), style = MaterialTheme.typography.labelLarge)
                TwoWay(
                    first = stringResource(R.string.notes_standard),
                    second = stringResource(R.string.notes_basic_pitch),
                    secondSelected = basicPitch,
                    onSelect = onBasicPitch,
                )
                Text(stringResource(R.string.settings_notes_help), style = MaterialTheme.typography.bodySmall, color = muted)

                Text(stringResource(R.string.settings_pitch), style = MaterialTheme.typography.labelLarge)
                TwoWay(
                    first = stringResource(R.string.pitch_standard),
                    second = stringResource(R.string.pitch_crepe),
                    secondSelected = crepe,
                    onSelect = onCrepe,
                )
                Text(stringResource(R.string.settings_pitch_help), style = MaterialTheme.typography.bodySmall, color = muted)

                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge)
                Choice(
                    listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
                    selected = when (theme) { null -> 0; false -> 1; true -> 2 },
                    onSelect = { onTheme(when (it) { 0 -> null; 1 -> false; else -> true }) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.done)) } },
    )
}

/** Two equal halves. */
@Composable
private fun TwoWay(first: String, second: String, secondSelected: Boolean, onSelect: (Boolean) -> Unit) =
    Choice(listOf(first, second), if (secondSelected) 1 else 0) { onSelect(it == 1) }

/** Equal segments with no tick icon, so every label stays centred and on one line. */
@Composable
private fun Choice(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(40.dp)) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                selected = selected == i,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size, SEGMENT_SHAPE),
                colors = segmentColors(),
                icon = {},
                modifier = Modifier.weight(1f),
            ) {
                Text(label, maxLines = 1, softWrap = false)
            }
        }
    }
}
