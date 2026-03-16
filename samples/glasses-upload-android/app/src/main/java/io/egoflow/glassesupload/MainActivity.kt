package io.egoflow.glassesupload

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.meta.wearable.dat.core.Wearables
import io.egoflow.glassesupload.ui.EgoFlowUploadApp

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()

  private val permissionsLauncher =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val allRequiredGranted =
            requiredPermissions().all { permission -> results[permission] == true || !results.containsKey(permission) }
        if (allRequiredGranted) {
          Wearables.initialize(this)
          viewModel.onPermissionsGranted()
        } else {
          viewModel.onPermissionsDenied()
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      EgoFlowUploadApp(
          viewModel = viewModel,
          onConnectGlasses = { viewModel.startRegistration(this) },
          onDisconnectGlasses = { viewModel.startUnregistration(this) },
          onRequestPermissions = { permissionsLauncher.launch(requiredPermissions()) },
      )
    }
    permissionsLauncher.launch(requiredPermissions())
  }

  private fun requiredPermissions(): Array<String> {
    val permissions =
        mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
        )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissions += Manifest.permission.READ_MEDIA_VIDEO
      permissions += Manifest.permission.POST_NOTIFICATIONS
    } else {
      permissions += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return permissions.toTypedArray()
  }
}
