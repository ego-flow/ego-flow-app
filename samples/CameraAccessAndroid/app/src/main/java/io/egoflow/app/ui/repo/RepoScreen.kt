package io.egoflow.app.ui.repo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.egoflow.app.egoflow.RegisteredRepository
import io.egoflow.app.ui.repo.components.ActiveRepoCard
import io.egoflow.app.ui.repo.components.RepoEmptyState
import io.egoflow.app.ui.repo.components.RepoErrorBanner
import io.egoflow.app.ui.repo.components.RepoFilterChips
import io.egoflow.app.ui.repo.components.RepoListCard
import io.egoflow.app.ui.repo.components.RepoSkeletonCard
import io.egoflow.app.ui.repo.components.SectionLabel

@Composable
fun RepoScreen(
    controller: RepoController,
    onGoLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loadState by controller.loadState
    val selectedFilter by controller.selectedFilter
    val activeRepoId by controller.activeRepoId
    var pendingSelectionId by remember { mutableStateOf<String?>(null) }

    // A two-step confirm flow is transient UI state. Reset whenever the
    // underlying list, active repo, or filter changes so a pending card
    // never lingers after the user's attention has moved.
    LaunchedEffect(loadState, selectedFilter, activeRepoId) {
        pendingSelectionId = null
    }

    val dismissInteraction = remember { MutableInteractionSource() }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Empty-space tap dismisses the pending confirm UI. Cards
                // and chips consume their own clicks, so this only fires
                // on background regions of the list.
                .clickable(
                    enabled = pendingSelectionId != null,
                    indication = null,
                    interactionSource = dismissInteraction,
                    onClick = { pendingSelectionId = null },
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val state = loadState) {
                RepoLoadState.Loading -> ScrollableBody { LoadingBody() }
                is RepoLoadState.Loaded -> {
                    if (state.repos.isEmpty()) {
                        CenteredBody { RepoEmptyState() }
                    } else {
                        ScrollableBody {
                            LoadedBody(
                                repos = state.repos,
                                activeRepoId = activeRepoId,
                                selectedFilter = selectedFilter,
                                pendingSelectionId = pendingSelectionId,
                                onSelectRepo = {
                                    controller.selectRepo(it)
                                    pendingSelectionId = null
                                },
                                onPendingCardClick = { pendingSelectionId = it.id },
                                onSelectFilter = { controller.selectedFilter.value = it },
                                onGoLive = onGoLive,
                            )
                        }
                    }
                }
                is RepoLoadState.Error -> {
                    if (state.cached.isEmpty()) {
                        RepoErrorBanner(
                            title = "Couldn't load repositories",
                            detail = state.message,
                            onRetry = { controller.refresh() },
                        )
                        CenteredBody {
                            RepoEmptyState(
                                title = "No cached repositories",
                                detail = "Connect to a network and tap Retry.",
                            )
                        }
                    } else {
                        ScrollableBody {
                            ErrorWithCacheBody(
                                state = state,
                                activeRepoId = activeRepoId,
                                selectedFilter = selectedFilter,
                                pendingSelectionId = pendingSelectionId,
                                onRetry = { controller.refresh() },
                                onSelectRepo = {
                                    controller.selectRepo(it)
                                    pendingSelectionId = null
                                },
                                onPendingCardClick = { pendingSelectionId = it.id },
                                onSelectFilter = { controller.selectedFilter.value = it },
                                onGoLive = onGoLive,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ScrollableBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
private fun ColumnScope.CenteredBody(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ColumnScope.LoadingBody() {
    SectionLabel(text = "ACTIVE")
    RepoSkeletonCard(showCta = true)
    SectionLabel(text = "ALL REPOSITORIES")
    RepoSkeletonCard()
    RepoSkeletonCard()
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ColumnScope.LoadedBody(
    repos: List<RegisteredRepository>,
    activeRepoId: String,
    selectedFilter: RepoFilter,
    pendingSelectionId: String?,
    onSelectRepo: (RegisteredRepository) -> Unit,
    onPendingCardClick: (RegisteredRepository) -> Unit,
    onSelectFilter: (RepoFilter) -> Unit,
    onGoLive: () -> Unit,
) {
    val active = repos.firstOrNull { it.id == activeRepoId }
    val others = repos.filter { it.id != active?.id }
    val visible = others.filter { matchesFilter(it, selectedFilter) }

    if (active != null) {
        SectionLabel(text = "ACTIVE")
        ActiveRepoCard(repo = active, onGoLive = onGoLive)
    }

    SectionLabel(text = "ALL REPOSITORIES", countLabel = visible.size.toString())
    RepoFilterChips(selected = selectedFilter, onSelected = onSelectFilter)

    if (visible.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No repositories in this filter",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        visible.forEach { repo ->
            RepoListCard(
                repo = repo,
                isPending = pendingSelectionId == repo.id,
                onCardClick = { onPendingCardClick(repo) },
                onConfirmSelect = { onSelectRepo(repo) },
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ColumnScope.ErrorWithCacheBody(
    state: RepoLoadState.Error,
    activeRepoId: String,
    selectedFilter: RepoFilter,
    pendingSelectionId: String?,
    onRetry: () -> Unit,
    onSelectRepo: (RegisteredRepository) -> Unit,
    onPendingCardClick: (RegisteredRepository) -> Unit,
    onSelectFilter: (RepoFilter) -> Unit,
    onGoLive: () -> Unit,
) {
    RepoErrorBanner(
        title = "Couldn't load repositories",
        detail = state.message,
        onRetry = onRetry,
    )

    val active = state.cached.firstOrNull { it.id == activeRepoId }
    val others = state.cached.filter { it.id != active?.id }
    val visible = others.filter { matchesFilter(it, selectedFilter) }

    if (active != null) {
        SectionLabel(text = "ACTIVE", trailingChip = "cached")
        ActiveRepoCard(repo = active, onGoLive = onGoLive, isCached = true)
    }
    if (others.isNotEmpty()) {
        SectionLabel(text = "ALL REPOSITORIES", countLabel = visible.size.toString(), trailingChip = "cached")
        RepoFilterChips(selected = selectedFilter, onSelected = onSelectFilter)
        visible.forEach { repo ->
            RepoListCard(
                repo = repo,
                isPending = pendingSelectionId == repo.id,
                onCardClick = { onPendingCardClick(repo) },
                onConfirmSelect = { onSelectRepo(repo) },
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

private fun matchesFilter(repo: RegisteredRepository, filter: RepoFilter): Boolean =
    when (filter) {
        RepoFilter.All -> true
        RepoFilter.Public -> repo.visibility.equals("public", ignoreCase = true)
        RepoFilter.Private -> repo.visibility.equals("private", ignoreCase = true)
    }
