package io.egoflow.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Video
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.egoflow.app.egoflow.RegisteredRepository
import io.egoflow.app.ui.repo.RepoLoadState
import io.egoflow.app.ui.repo.RepoScreen
import io.egoflow.app.ui.repo.displayName
import io.egoflow.app.ui.repo.components.RepoTopBarActions
import io.egoflow.app.ui.repo.rememberRepoController
import io.egoflow.app.ui.theme.ThemePreference
import io.egoflow.app.wearables.MainTab
import io.egoflow.app.wearables.WearablesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabScreen(
    viewModel: WearablesViewModel,
    onThemeChanged: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab = uiState.selectedTab
    val title = when (selectedTab) {
        MainTab.REPO -> "Repositories"
        MainTab.RECORD -> "Record"
        MainTab.SETTINGS -> "Settings"
    }
    val repoController = rememberRepoController()
    val repoLoadState by repoController.loadState
    val activeRepoId by repoController.activeRepoId
    val activeRepoName by repoController.activeRepoName
    val activeRepository = remember(repoLoadState, activeRepoId, activeRepoName) {
        recordRepositorySummary(repoLoadState, activeRepoId, activeRepoName)
    }
    // Latch the last definitive repository-presence result. Only a positive
    // load (Loaded) is conclusive: a refresh flips loadState back to Loading
    // and a network Error can't prove the account has zero repos, so neither
    // should flash the start UI on/off or wrongly claim "no repository".
    // null = not yet known.
    var hasRepositories by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(repoLoadState) {
        (repoLoadState as? RepoLoadState.Loaded)?.let { hasRepositories = it.repos.isNotEmpty() }
    }
    var showMicDiagnostics by remember { mutableStateOf(false) }

    if (showMicDiagnostics) {
        MicDiagnosticsScreen(onBack = { showMicDiagnostics = false }, modifier = modifier)
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            EgoFlowTopAppBar(
                title = title,
                actions = {
                    if (selectedTab == MainTab.REPO) {
                        RepoTopBarActions(controller = repoController)
                    }
                },
            )
        },
        bottomBar = {
            EgoFlowNavigationBar(
                selectedTab = selectedTab,
                onTabSelected = { viewModel.selectTab(it) },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                MainTab.REPO -> RepoScreen(
                    controller = repoController,
                    onGoLive = { viewModel.selectTab(MainTab.RECORD) },
                )
                MainTab.RECORD ->
                    when {
                        uiState.isStreaming ->
                            StreamScreen(
                                wearablesViewModel = viewModel,
                                isPhoneMode = uiState.isPhoneMode,
                            )
                        // Streaming always publishes into a repository; with none,
                        // there is nothing to start, so guide the user instead of
                        // showing the start screens with dead buttons.
                        hasRepositories == false ->
                            RecordNoRepositoryScreen(
                                isRefreshing = repoLoadState is RepoLoadState.Loading,
                                onRefresh = { repoController.refresh() },
                                onViewRepositories = { viewModel.selectTab(MainTab.REPO) },
                            )
                        uiState.hasActiveDevice ->
                            NonStreamScreen(
                                viewModel = viewModel,
                                repository = activeRepository,
                                onOpenRepositories = { viewModel.selectTab(MainTab.REPO) },
                            )
                        else ->
                            ExtentosConnectionScreen(
                                glasses = viewModel.glasses,
                                onStartPhone = { viewModel.navigateToPhoneMode() },
                            )
                    }
                MainTab.SETTINGS ->
                    SettingsScreen(
                        onLogout = { viewModel.logout() },
                        onThemeChanged = onThemeChanged,
                        onOpenMicDiagnostics = { showMicDiagnostics = true },
                        repositoryDisplayName = activeRepository?.displayName,
                        streamingActive = uiState.isStreaming,
                    )
            }
        }
    }
}

private fun recordRepositorySummary(
    loadState: RepoLoadState,
    activeRepoId: String,
    activeRepoName: String,
): RecordRepositorySummary? {
    val repo =
        when (loadState) {
            is RepoLoadState.Loaded ->
                loadState.repos.firstOrNull { it.id == activeRepoId }
                    ?: if (activeRepoId.isBlank()) loadState.repos.firstOrNull() else null
            is RepoLoadState.Error -> loadState.cached.firstOrNull { it.id == activeRepoId }
            RepoLoadState.Loading -> null
        }
    if (repo != null) {
        return repo.toRecordRepositorySummary(isCached = loadState is RepoLoadState.Error)
    }
    return activeRepoName.trim().takeIf { it.isNotBlank() }?.let {
        RecordRepositorySummary(displayName = it)
    }
}

private fun RegisteredRepository.toRecordRepositorySummary(isCached: Boolean): RecordRepositorySummary =
    RecordRepositorySummary(
        displayName = displayName,
        visibility = visibility,
        role = myRole,
        isCached = isCached,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EgoFlowTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    centerTitle: Boolean = true,
) {
    val titleSlot: @Composable () -> Unit = {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (centerTitle) {
        CenterAlignedTopAppBar(
            title = titleSlot,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = colors,
        )
    } else {
        TopAppBar(
            title = titleSlot,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = colors,
        )
    }
}

@Composable
private fun EgoFlowNavigationBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        NavigationBarItem(
            selected = selectedTab == MainTab.REPO,
            onClick = { onTabSelected(MainTab.REPO) },
            icon = { Icon(Lucide.Folder, contentDescription = "Repo") },
            label = { Text("Repo") },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = selectedTab == MainTab.RECORD,
            onClick = { onTabSelected(MainTab.RECORD) },
            icon = { Icon(Lucide.Video, contentDescription = "Record") },
            label = { Text("Record") },
            colors = itemColors,
        )
        NavigationBarItem(
            selected = selectedTab == MainTab.SETTINGS,
            onClick = { onTabSelected(MainTab.SETTINGS) },
            icon = { Icon(Lucide.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            colors = itemColors,
        )
    }
}
