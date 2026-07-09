package io.egoflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class PillButtonStyle { Primary, Outlined, Destructive }

@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillButtonStyle = PillButtonStyle.Primary,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(28.dp)
    val enabledBg = when (style) {
        PillButtonStyle.Primary -> MaterialTheme.colorScheme.primary
        PillButtonStyle.Outlined -> MaterialTheme.colorScheme.surface
        PillButtonStyle.Destructive -> MaterialTheme.colorScheme.error
    }
    val enabledFg = when (style) {
        PillButtonStyle.Primary -> MaterialTheme.colorScheme.onPrimary
        PillButtonStyle.Outlined -> MaterialTheme.colorScheme.onSurface
        PillButtonStyle.Destructive -> androidx.compose.ui.graphics.Color.White
    }
    val bg = if (enabled) enabledBg else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (enabled) enabledFg else MaterialTheme.colorScheme.onSurfaceVariant
    val borderStroke = when {
        !enabled -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        style == PillButtonStyle.Outlined -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        else -> null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .let { if (borderStroke != null) it.border(borderStroke, shape) else it }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
