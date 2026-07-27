package dev.offlinemesh.airchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.offlinemesh.airchat.core.AirChatRuntime
import dev.offlinemesh.airchat.ui.AirChatApp
import dev.offlinemesh.airchat.ui.theme.AirChatTheme

class MainActivity : ComponentActivity() {
    private val container by lazy { AirChatRuntime.get(applicationContext) }

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
                AirChatApp(container = container)
            }
        }
    }
}
