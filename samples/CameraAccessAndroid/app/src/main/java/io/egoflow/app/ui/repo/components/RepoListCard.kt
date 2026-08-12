package io.egoflow.app.ui.repo.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Crown
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import io.egoflow.app.egoflow.RegisteredRepository
import io.egoflow.app.ui.repo.displayName

@Composable
fun RepoListCard(
    repo: RegisteredRepository,
    isPending: Boolean,
    onCardClick: () -> Unit,
    onConfirmSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(16.dp)
    val badgeBg = MaterialTheme.colorScheme.surfaceVariant
    val badgeFg = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor =
        if (isPending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val borderWidth = if (isPending) 2.dp else 1.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(borderWidth, borderColor), cardShape)
            .clickable(onClick = onCardClick)
            .padding(PaddingValues(horizontal = 14.dp, vertical = 12.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = repo.displayName,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Icon(
                imageVector = Lucide.EllipsisVertical,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RepoBadge(
                label = repo.visibility.lowercase(),
                background = badgeBg,
                contentColor = badgeFg,
                icon = if (repo.visibility.equals("public", ignoreCase = true)) Lucide.Globe else Lucide.Lock,
            )
            RepoBadge(
                label = repo.myRole.lowercase(),
                background = badgeBg,
                contentColor = badgeFg,
                icon = if (repo.myRole.equals("owner", ignoreCase = true)) Lucide.Crown else Lucide.Wrench,
            )
        }

        if (isPending) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onConfirmSelect)
                    .padding(PaddingValues(horizontal = 14.dp, vertical = 10.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Select this repository",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}
