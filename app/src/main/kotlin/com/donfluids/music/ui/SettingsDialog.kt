package com.donfluids.music.ui

import androidx.compose.foundation.layout.Arrangement
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

/** The tuning reference and the note-name style. Both are saved as soon as they change. */
@Composable
fun SettingsDialog(
    referenceHz: Int,
    naming: NoteNaming,
    onReference: (Int) -> Unit,
    onNaming: (NoteNaming) -> Unit,
    onDone: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_reference), style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepButton("−", stringResource(R.string.settings_reference_down), enabled = referenceHz > MIN_REFERENCE_HZ) { onReference(referenceHz - 1) }
                    Text(
                        stringResource(R.string.settings_reference_value, referenceHz),
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = Tabular),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(96.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    StepButton("+", stringResource(R.string.settings_reference_up), enabled = referenceHz < MAX_REFERENCE_HZ) { onReference(referenceHz + 1) }
                    if (referenceHz != 440) TextButton(onClick = { onReference(440) }) { Text(stringResource(R.string.settings_reference_reset)) }
                }
                Text(stringResource(R.string.settings_reference_help), style = MaterialTheme.typography.bodySmall, color = muted)

                Text(stringResource(R.string.settings_naming), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(40.dp)) {
                    NoteNaming.entries.forEachIndexed { i, n ->
                        SegmentedButton(
                            selected = n == naming,
                            onClick = { onNaming(n) },
                            shape = SegmentedButtonDefaults.itemShape(i, NoteNaming.entries.size),
                        ) {
                            Text(stringResource(if (n == NoteNaming.FIXED_DO) R.string.naming_do_re_mi else R.string.naming_letters))
                        }
                    }
                }
                Text(stringResource(R.string.settings_naming_help), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.done)) } },
    )
}
