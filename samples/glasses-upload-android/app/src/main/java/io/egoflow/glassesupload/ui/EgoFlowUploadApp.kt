package io.egoflow.glassesupload.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.egoflow.glassesupload.MainUiState
import io.egoflow.glassesupload.MainViewModel
import io.egoflow.glassesupload.data.UploadRecordEntity
import io.egoflow.glassesupload.data.UploadStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EgoFlowUploadApp(
    viewModel: MainViewModel,
    onConnectGlasses: () -> Unit,
    onDisconnectGlasses: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsState()
  val snackbarHostState = remember { SnackbarHostState() }
  val listState = rememberLazyListState()

  LaunchedEffect(uiState.message) {
    val message = uiState.message ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(message)
    viewModel.clearMessage()
  }

  LaunchedEffect(listState, uiState.canLoadMore, uiState.history.size) {
    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
        .collect { lastVisible ->
          if (lastVisible != null && lastVisible >= uiState.history.lastIndex - 4) {
            viewModel.loadMoreHistoryIfNeeded()
          }
        }
  }

  MaterialTheme {
    Scaffold(
        topBar = {
          TopAppBar(
              title = { Text("EgoFlow Upload") },
              actions = {
                IconButton(onClick = onRequestPermissions) {
                  Icon(Icons.Outlined.Refresh, contentDescription = "Request permissions")
                }
              },
          )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
      LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 16.dp, top = innerPadding.calculateTopPadding() + 16.dp, end = 16.dp, bottom = 24.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        item {
          ConnectionCard(
              uiState = uiState,
              onConnectGlasses = onConnectGlasses,
              onDisconnectGlasses = onDisconnectGlasses,
          )
        }
        item {
          SettingsCard(
              uiState = uiState,
              onServerBaseUrlChanged = viewModel::updateServerBaseUrl,
              onImportDirectoryChanged = viewModel::updateImportDirectory,
              onDeleteAfterUploadChanged = viewModel::updateDeleteAfterUpload,
              onImportNow = viewModel::importCurrentDirectoryNow,
          )
        }
        item {
          Text(
              text = "History",
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.SemiBold,
          )
        }
        items(uiState.history, key = { it.id }) { record ->
          HistoryRow(record = record, onRetry = { viewModel.retryUpload(record.id) })
        }
      }
    }
  }
}

@Composable
private fun ConnectionCard(
    uiState: MainUiState,
    onConnectGlasses: () -> Unit,
    onDisconnectGlasses: () -> Unit,
) {
  Card {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Link, contentDescription = null)
        Text("Glasses Connection", style = MaterialTheme.typography.titleMedium)
      }
      Text("Registration: ${uiState.connection.registrationLabel}")
      Text("Active device: ${uiState.connection.activeDeviceName ?: uiState.connection.activeDeviceId ?: "None"}")
      Text(if (uiState.connection.hasActiveDevice) "DAT reports an active device." else "No active device detected.")
      if (!uiState.permissionsGranted) {
        Text("Grant Android permissions before DAT registration can proceed.")
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onConnectGlasses, enabled = uiState.permissionsGranted) { Text("Connect My Glasses") }
        Button(onClick = onDisconnectGlasses, enabled = uiState.connection.isRegistered) { Text("Disconnect") }
      }
    }
  }
}

@Composable
private fun SettingsCard(
    uiState: MainUiState,
    onServerBaseUrlChanged: (String) -> Unit,
    onImportDirectoryChanged: (String) -> Unit,
    onDeleteAfterUploadChanged: (Boolean) -> Unit,
    onImportNow: () -> Unit,
) {
  Card {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Settings, contentDescription = null)
        Text("Upload Settings", style = MaterialTheme.typography.titleMedium)
      }
      OutlinedTextField(
          value = uiState.settings.serverBaseUrl,
          onValueChange = onServerBaseUrlChanged,
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Server Base URL") },
          singleLine = true,
      )
      OutlinedTextField(
          value = uiState.settings.importDirectory,
          onValueChange = onImportDirectoryChanged,
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Import Directory") },
          supportingText = { Text(uiState.watcherSummary) },
      )
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text("Delete local file after upload")
          Text(
              "Only imported phone-side files are deleted.",
              style = MaterialTheme.typography.bodySmall,
          )
        }
        Switch(checked = uiState.settings.deleteAfterUpload, onCheckedChange = onDeleteAfterUploadChanged)
      }
      Button(onClick = onImportNow) {
        Icon(Icons.Outlined.CloudUpload, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Import New Files")
      }
    }
  }
}

@Composable
private fun HistoryRow(
    record: UploadRecordEntity,
    onRetry: () -> Unit,
) {
  Card {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(record.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Text("Discovered ${record.discoveredAt.formatTimestamp()} • ${record.durationMs.formatDuration()}")
      AssistChip(onClick = {}, label = { Text(record.status.toUiLabel()) })
      record.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
        Text("Failure reason: $error", color = MaterialTheme.colorScheme.error)
      }
      if (record.status == UploadStatus.FAILED) {
        Button(onClick = onRetry) { Text("Retry Upload") }
      }
    }
  }
}

private fun UploadStatus.toUiLabel(): String =
    when (this) {
      UploadStatus.WAITING -> "Waiting"
      UploadStatus.UPLOADING -> "Uploading"
      UploadStatus.SUCCEEDED -> "Succeeded"
      UploadStatus.FAILED -> "Failed"
    }

private fun Long?.formatDuration(): String {
  if (this == null || this <= 0) {
    return "Unknown duration"
  }
  val totalSeconds = this / 1000
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "%d:%02d".format(minutes, seconds)
}

private fun Long.formatTimestamp(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))
