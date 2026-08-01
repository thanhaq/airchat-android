package dev.offlinemesh.airchat

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.offlinemesh.airchat.core.AirChatRuntime
import dev.offlinemesh.airchat.ui.AirChatApp
import dev.offlinemesh.airchat.ui.TrustBackupImportRequest
import dev.offlinemesh.airchat.ui.theme.AirChatTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val container by lazy { AirChatRuntime.get(applicationContext) }
    private val sharedTrustBackupImport = MutableStateFlow<TrustBackupImportRequest?>(null)
    private var sharedTrustBackupImportId = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        container.router.refreshTransports()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(container.requiredPermissions())

        setContent {
            AirChatTheme {
                AirChatApp(
                    container = container,
                    incomingTrustBackup = sharedTrustBackupImport,
                    onTrustBackupImportConsumed = ::consumeTrustBackupImport
                )
            }
        }
        publishTrustBackupImport(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        publishTrustBackupImport(intent)
    }

    private fun publishTrustBackupImport(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        val uri = intent.sharedStreamUri()
        if (text == null && uri == null) return
        sharedTrustBackupImport.value = TrustBackupImportRequest(
            id = ++sharedTrustBackupImportId,
            text = text,
            uri = uri
        )
    }

    private fun consumeTrustBackupImport(id: Long) {
        if (sharedTrustBackupImport.value?.id == id) {
            sharedTrustBackupImport.value = null
        }
    }

    private fun Intent.sharedStreamUri(): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }
}
