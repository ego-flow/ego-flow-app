package io.egoflow.app.ui.repo.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun RepoSkeletonCard(
    modifier: Modifier = Modifier,
    showCta: Boolean = false,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), cardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonBar(width = 160.dp, height = 16.dp, radius = 8.dp, color = placeholder)
        SkeletonBar(width = 240.dp, height = 12.dp, radius = 6.dp, color = placeholder)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SkeletonBar(width = 70.dp, height = 18.dp, radius = 9.dp, color = placeholder)
            SkeletonBar(width = 60.dp, height = 18.dp, radius = 9.dp, color = placeholder)
            SkeletonBar(width = 50.dp, height = 18.dp, radius = 9.dp, color = placeholder)
        }
        if (showCta) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(placeholder),
            )
        }
    }
}

@Composable
private fun SkeletonBar(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    radius: androidx.compose.ui.unit.Dp,
    color: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(color),
    )
}
