package io.egoflow.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.extentos.glasses.core.ExtentosGlasses
import com.extentos.glasses.ui.ConnectionPageConfig
import com.extentos.glasses.ui.ExtentosConnectionPage
import com.extentos.glasses.ui.SectionVisibility

@Composable
fun ExtentosConnectionScreen(
    glasses: ExtentosGlasses,
    onStartPhone: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize()) {
    ExtentosConnectionPage(
        glasses = glasses,
        config =
            ConnectionPageConfig(
                sections =
                    SectionVisibility(
                        capabilities = true,
                        toggles = false,
                        voiceCommands = false,
                    )
            ),
        modifier = Modifier.weight(1f).fillMaxWidth(),
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
          modifier = Modifier.navigationBarsPadding().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
            text = "Need to stream without glasses?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onStartPhone, modifier = Modifier.fillMaxWidth()) {
          Text("Use phone camera")
        }
      }
    }
  }
}
