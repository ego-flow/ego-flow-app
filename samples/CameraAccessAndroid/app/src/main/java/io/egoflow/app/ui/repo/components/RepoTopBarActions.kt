package io.egoflow.app.ui.repo.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RotateCw
import io.egoflow.app.ui.repo.RepoController
import io.egoflow.app.ui.repo.RepoLoadState

@Composable
fun RepoTopBarActions(controller: RepoController) {
    val isLoading = controller.loadState.value is RepoLoadState.Loading
    val rotation = if (isLoading) {
        val transition = rememberInfiniteTransition(label = "refresh-spin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "refresh-spin-angle",
        ).value
    } else {
        0f
    }

    Box(
        modifier = Modifier
            // Material3 TopAppBar gives actions only 4dp of end-padding; add 12dp so the
            // button's right edge lines up with the 16dp body inset used by the search
            // field and cards below.
            .padding(end = 12.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !isLoading) { controller.refresh() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Lucide.RotateCw,
            contentDescription = "Refresh repositories",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation),
        )
    }
}
