package com.donfluids.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.donfluids.music.R

/** What the app can and can't do, in plain words. Back (or the arrow) returns to listening. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitsScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.limits_title), style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.limits_intro), style = MaterialTheme.typography.bodyLarge)

            Heading(stringResource(R.string.limits_wrong_heading))
            Item(R.string.limits_off_pitch_lead, R.string.limits_off_pitch)
            Item(R.string.limits_drift_lead, R.string.limits_drift)
            Item(R.string.limits_key_change_lead, R.string.limits_key_change)
            Item(R.string.limits_major_minor_lead, R.string.limits_major_minor)
            Item(R.string.limits_room_lead, R.string.limits_room)
            Item(R.string.limits_one_key_lead, R.string.limits_one_key)
            Item(R.string.limits_beat_lead, R.string.limits_beat)
            Item(R.string.limits_voices_lead, R.string.limits_voices)

            Heading(stringResource(R.string.limits_notes_heading))
            Note(stringResource(R.string.limits_note_late))
            Note(stringResource(R.string.limits_note_simple))
            Note(stringResource(R.string.limits_note_tuning))
            Note(stringResource(R.string.limits_note_new))

            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.limits_closing), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.limits_privacy), style = MaterialTheme.typography.bodyMedium, color = muted)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp).semantics { heading() },
    )
}

/** A bold lead ("The room is busy.") followed by the explanation, as one paragraph. */
@Composable
private fun Item(lead: Int, body: Int) {
    val leadText = stringResource(lead)
    val bodyText = stringResource(body)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(leadText) }
            append(' ')
            append(bodyText)
        },
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun Note(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
