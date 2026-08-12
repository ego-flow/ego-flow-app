package io.egoflow.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Bluetooth
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Mic
import io.egoflow.app.R

enum class PermissionIntroType {
  CAMERA,
  MICROPHONE,
  BLUETOOTH,
  NOTIFICATIONS,
}

data class PermissionIntroItem(
    val type: PermissionIntroType,
    val required: Boolean,
)

@Composable
fun PermissionIntroScreen(
    permissions: List<PermissionIntroItem>,
    onAllowPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    onContinueWithoutNotifications: (() -> Unit)? = null,
    errorMessage: String? = null,
) {
  Scaffold(
      modifier = modifier.fillMaxSize(),
      containerColor = MaterialTheme.colorScheme.background,
      bottomBar = {
        PermissionIntroActions(
            onAllowPermissions = onAllowPermissions,
            onContinueWithoutNotifications = onContinueWithoutNotifications,
        )
      },
  ) { contentPadding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(contentPadding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Box(
            modifier =
                Modifier.size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
          Icon(
              imageVector = Lucide.CircleCheck,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(32.dp),
          )
        }
        Text(
            text = stringResource(R.string.permission_intro_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.permission_intro_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
      }

      errorMessage?.let {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
          Text(
              text = it,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
              color = MaterialTheme.colorScheme.onErrorContainer,
              style = MaterialTheme.typography.bodyMedium,
          )
        }
      }

      Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        permissions.forEach { permission ->
          PermissionIntroCard(permission = permission)
        }
      }
    }
  }
}

@Composable
private fun PermissionIntroActions(
    onAllowPermissions: () -> Unit,
    onContinueWithoutNotifications: (() -> Unit)?,
) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.background,
      tonalElevation = 3.dp,
  ) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Button(
          onClick = onAllowPermissions,
          modifier = Modifier.fillMaxWidth().height(52.dp),
          shape = RoundedCornerShape(8.dp),
          contentPadding = PaddingValues(horizontal = 18.dp),
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
              ),
      ) {
        Icon(
            imageVector = Lucide.CircleCheck,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.permission_intro_allow),
            fontWeight = FontWeight.SemiBold,
        )
      }

      if (onContinueWithoutNotifications != null) {
        TextButton(
            onClick = onContinueWithoutNotifications,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
          Text(text = stringResource(R.string.permission_intro_continue_without_notifications))
        }
      }
    }
  }
}

@Composable
private fun PermissionIntroCard(permission: PermissionIntroItem) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
      Box(
          modifier =
              Modifier.size(42.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.secondaryContainer),
          contentAlignment = Alignment.Center,
      ) {
        Icon(
            imageVector = permission.type.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
      }

      Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
              text = permission.type.title(),
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.width(10.dp))
          PermissionBadge(required = permission.required)
        }

        Text(
            text = permission.type.description(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun PermissionBadge(required: Boolean) {
  val background =
      if (required) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.secondaryContainer
      }
  val foreground =
      if (required) {
        MaterialTheme.colorScheme.onPrimaryContainer
      } else {
        MaterialTheme.colorScheme.onSecondaryContainer
      }
  val label =
      if (required) {
        R.string.permission_intro_required
      } else {
        R.string.permission_intro_recommended
      }

  Box(
      modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(background).padding(
          horizontal = 10.dp,
          vertical = 4.dp,
      ),
      contentAlignment = Alignment.Center,
  ) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = foreground,
    )
  }
}

private fun PermissionIntroType.icon(): ImageVector =
    when (this) {
      PermissionIntroType.CAMERA -> Lucide.Camera
      PermissionIntroType.MICROPHONE -> Lucide.Mic
      PermissionIntroType.BLUETOOTH -> Lucide.Bluetooth
      PermissionIntroType.NOTIFICATIONS -> Lucide.Bell
    }

@Composable
private fun PermissionIntroType.title(): String =
    stringResource(
        when (this) {
          PermissionIntroType.CAMERA -> R.string.permission_intro_camera_title
          PermissionIntroType.MICROPHONE -> R.string.permission_intro_microphone_title
          PermissionIntroType.BLUETOOTH -> R.string.permission_intro_bluetooth_title
          PermissionIntroType.NOTIFICATIONS -> R.string.permission_intro_notifications_title
        },
    )

@Composable
private fun PermissionIntroType.description(): String =
    stringResource(
        when (this) {
          PermissionIntroType.CAMERA -> R.string.permission_intro_camera_description
          PermissionIntroType.MICROPHONE -> R.string.permission_intro_microphone_description
          PermissionIntroType.BLUETOOTH -> R.string.permission_intro_bluetooth_description
          PermissionIntroType.NOTIFICATIONS -> R.string.permission_intro_notifications_description
        },
    )
