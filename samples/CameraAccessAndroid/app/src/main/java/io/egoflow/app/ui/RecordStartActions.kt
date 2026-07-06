package io.egoflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RecordStartActions(
    startGlassesEnabled: Boolean,
    onStartGlasses: () -> Unit,
    onStartPhone: () -> Unit,
    modifier: Modifier = Modifier,
    startGlassesLabel: String = "Start streaming",
    message: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PillButton(
            label = startGlassesLabel,
            onClick = onStartGlasses,
            enabled = startGlassesEnabled,
        )
        PillButton(
            label = "Start on Phone",
            onClick = onStartPhone,
            style = PillButtonStyle.Outlined,
        )
    }
}
