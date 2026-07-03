package io.egoflow.app.ui.repo.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.egoflow.app.ui.repo.RepoFilter

@Composable
fun RepoFilterChips(
    selected: RepoFilter,
    onSelected: (RepoFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(label = "All", isSelected = selected == RepoFilter.All) { onSelected(RepoFilter.All) }
        FilterChip(label = "Public", isSelected = selected == RepoFilter.Public) { onSelected(RepoFilter.Public) }
        FilterChip(label = "Private", isSelected = selected == RepoFilter.Private) { onSelected(RepoFilter.Private) }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val bg: Color
    val fg: Color
    val borderStroke: BorderStroke?
    if (isSelected) {
        bg = MaterialTheme.colorScheme.primary
        fg = MaterialTheme.colorScheme.onPrimary
        borderStroke = null
    } else {
        bg = MaterialTheme.colorScheme.surface
        fg = MaterialTheme.colorScheme.onSurface
        borderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .let { if (borderStroke != null) it.border(borderStroke, shape) else it }
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
