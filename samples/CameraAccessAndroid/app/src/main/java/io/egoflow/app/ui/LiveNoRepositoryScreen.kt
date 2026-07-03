/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

package io.egoflow.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.egoflow.app.R
import io.egoflow.app.ui.repo.components.RepoEmptyState

/**
 * Live-tab content shown when the signed-in account has no repositories.
 *
 * Streaming always publishes into a repository, so with zero repositories
 * there is nothing to start. Rather than render the start screens with
 * dead buttons, the Live tab swaps in this guidance: repositories are
 * created on the EgoFlow dashboard (web), so the only in-app actions are
 * to re-check after adding one, or jump to the Repositories tab.
 *
 * @param isRefreshing true while a repository re-check is in flight; the
 *   refresh action is disabled and relabeled so a double-tap can't queue
 *   a second load.
 */
@Composable
fun LiveNoRepositoryScreen(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onViewRepositories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RepoEmptyState(
            title = stringResource(R.string.live_no_repo_title),
            detail = stringResource(R.string.live_no_repo_description),
        )

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PillButton(
                label = stringResource(
                    if (isRefreshing) R.string.live_no_repo_refreshing
                    else R.string.live_no_repo_refresh,
                ),
                onClick = onRefresh,
                enabled = !isRefreshing,
            )
            PillButton(
                label = stringResource(R.string.live_no_repo_view),
                onClick = onViewRepositories,
                style = PillButtonStyle.Outlined,
            )
        }
    }
}
