package io.egoflow.app.ui.repo.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Crown
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lock
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Wrench
import io.egoflow.app.egoflow.RegisteredRepository
import io.egoflow.app.ui.repo.displayName

@Composable
fun ActiveRepoCard(
    repo: RegisteredRepository,
    onGoLive: () -> Unit,
    modifier: Modifier = Modifier,
    isCached: Boolean = false,
) {
    val isDark = isSystemInDarkTheme()
    val cardShape = RoundedCornerShape(16.dp)
    val cardBg =
        if (isDark) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    val cardContent = MaterialTheme.colorScheme.onPrimaryContainer
    // Badge fill inside the active card: light → white; dark → surface (deep ink).
    val badgeBg =
        if (isDark) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface
    // Badge text must contrast with badgeBg (surface), not the card container.
    // Using cardContent (onPrimaryContainer) gave near-black text on the dark
    // surface fill in dark mode, making "private"/role labels unreadable.
    val badgeFg = MaterialTheme.colorScheme.onSurface

    val baseModifier =
        modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(cardBg)

    val borderedModifier =
        if (isDark) {
            baseModifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), cardShape)
        } else if (isCached) {
            baseModifier // light cached state in Figma uses a translucent fill, no border
        } else {
            baseModifier
        }

    Column(
        modifier = borderedModifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.Check,
                    contentDescription = "Active repository",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
            Text(
                text = repo.displayName,
                color = cardContent,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onGoLive)
                .padding(PaddingValues(horizontal = 14.dp, vertical = 12.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Go live with this repo",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Box(modifier = Modifier.size(8.dp))
            Icon(
                imageVector = Lucide.ArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
