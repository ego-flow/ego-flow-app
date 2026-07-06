package io.egoflow.app.ui.repo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.egoflow.app.egoflow.EgoFlowBackendClient
import io.egoflow.app.egoflow.RegisteredRepository
import io.egoflow.app.settings.AuthPrefs
import io.egoflow.app.settings.RepoPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RepoFilter { All, Public, Private }

@Immutable
sealed interface RepoLoadState {
    data object Loading : RepoLoadState
    data class Loaded(val repos: List<RegisteredRepository>) : RepoLoadState
    data class Error(val message: String, val cached: List<RegisteredRepository>) : RepoLoadState
}

class RepoController internal constructor(
    private val scope: CoroutineScope,
    private val backendClient: EgoFlowBackendClient,
) {
    val loadState: MutableState<RepoLoadState> = mutableStateOf(RepoLoadState.Loading)
    val selectedFilter: MutableState<RepoFilter> = mutableStateOf(RepoFilter.All)
    val activeRepoId: MutableState<String> = mutableStateOf(RepoPrefs.egoFlowRepositoryId)
    val activeRepoName: MutableState<String> = mutableStateOf(RepoPrefs.egoFlowRepositoryName)

    val canRefresh: Boolean get() = AuthPrefs.hasEgoFlowBackendConfig()

    fun refresh() {
        if (!canRefresh) {
            loadState.value =
                RepoLoadState.Error(
                    message = "Backend not configured. Sign in from Settings.",
                    cached = currentRepos(),
                )
            return
        }
        val previous = currentRepos()
        loadState.value = RepoLoadState.Loading
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { backendClient.listMaintainedRepositories() }
            }
                .onSuccess { repos ->
                    loadState.value = RepoLoadState.Loaded(repos)
                    syncActiveAfterLoad(repos)
                }
                .onFailure { error ->
                    loadState.value =
                        RepoLoadState.Error(
                            message = error.message?.ifBlank { null } ?: "Network unavailable.",
                            cached = previous,
                        )
                }
        }
    }

    fun selectRepo(repo: RegisteredRepository) {
        val displayName = repo.displayName
        activeRepoId.value = repo.id
        activeRepoName.value = displayName
        RepoPrefs.egoFlowRepositoryId = repo.id.trim()
        RepoPrefs.egoFlowRepositoryName = displayName.trim()
    }

    private fun currentRepos(): List<RegisteredRepository> =
        when (val s = loadState.value) {
            is RepoLoadState.Loaded -> s.repos
            is RepoLoadState.Error -> s.cached
            RepoLoadState.Loading -> emptyList()
        }

    private fun syncActiveAfterLoad(repos: List<RegisteredRepository>) {
        if (repos.isEmpty()) return
        val match = repos.firstOrNull { it.id == activeRepoId.value }
        if (match != null) {
            val displayName = match.displayName
            if (activeRepoName.value != displayName) {
                activeRepoName.value = displayName
                RepoPrefs.egoFlowRepositoryName = displayName.trim()
            }
            return
        }
        selectRepo(repos.first())
    }
}

@Composable
fun rememberRepoController(): RepoController {
    val scope = rememberCoroutineScope()
    val client = remember { EgoFlowBackendClient() }
    val controller = remember { RepoController(scope, client) }
    LaunchedEffect(Unit) { controller.refresh() }
    return controller
}
