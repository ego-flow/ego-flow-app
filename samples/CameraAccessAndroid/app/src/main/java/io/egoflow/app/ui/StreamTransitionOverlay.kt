/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 * Modified in this repository for EgoFlow; see THIRD_PARTY_NOTICES.md.
 */

package io.egoflow.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.egoflow.app.R
import io.egoflow.app.wearables.StreamTransition

private val OverlayScrim = Color(0xD9000000) // rgba(0,0,0,0.85)

/**
 * Full-screen loading overlay shown while a stream is spinning up (STARTING) or
 * tearing down (STOPPING). Renders a centered spinner with a status label and
 * swallows touches so the screen underneath can't be interacted with during the
 * transition. Renders nothing for [StreamTransition.NONE].
 *
 * The phase is owned by WearablesViewModel, which also enforces the minimum
 * visible time and clears it once the work completes.
 */
@Composable
fun StreamTransitionOverlay(
    transition: StreamTransition,
    modifier: Modifier = Modifier,
) {
    if (transition == StreamTransition.NONE) return

    val message = when (transition) {
        StreamTransition.STARTING -> stringResource(R.string.stream_transition_starting)
        StreamTransition.STOPPING -> stringResource(R.string.stream_transition_stopping)
        StreamTransition.NONE -> return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OverlayScrim)
            // Consume taps so the underlying screen stays inert while we transition.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}
