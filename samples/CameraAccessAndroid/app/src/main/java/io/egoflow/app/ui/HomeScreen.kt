/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

package io.egoflow.app.ui

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.egoflow.app.wearables.WearablesViewModel

@Composable
fun HomeScreen(
    viewModel: WearablesViewModel,
    repository: RecordRepositorySummary?,
    onOpenRepositories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .padding(bottom = 148.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RecordSettingsPanel(
                repository = repository,
                streamingActive = false,
                onOpenRepositories = onOpenRepositories,
            )
        }

        RecordStartActions(
            startGlassesEnabled = true,
            onStartGlasses = {
                activity?.let { viewModel.startRegistration(it) }
                    ?: Toast.makeText(context, "Activity not available", Toast.LENGTH_SHORT).show()
            },
            onStartPhone = { viewModel.navigateToPhoneMode() },
            modifier = Modifier.align(Alignment.BottomCenter),
            startGlassesLabel = "Connect my glasses",
            message = "You'll continue in the Meta AI app to connect your glasses.",
        )
    }
}
