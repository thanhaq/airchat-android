package dev.offlinemesh.airchat.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.offlinemesh.airchat.core.AppContainer
import dev.offlinemesh.airchat.core.ChatUiState
import dev.offlinemesh.airchat.core.ChatViewModel
import dev.offlinemesh.airchat.core.DiagnosticsReportFormatter
import dev.offlinemesh.airchat.core.DiagnosticsSnapshot
import dev.offlinemesh.airchat.crypto.IdentityKeySecurity
import dev.offlinemesh.airchat.crypto.SafetyNumber
import dev.offlinemesh.airchat.model.ChatMessage
import dev.offlinemesh.airchat.model.DeliveryState
import dev.offlinemesh.airchat.model.Peer
import dev.offlinemesh.airchat.model.PeerConnectionState
import dev.offlinemesh.airchat.model.PeerTrustState
import dev.offlinemesh.airchat.model.ReceivedFile
import dev.offlinemesh.airchat.service.MeshForegroundService
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AirChatApp(container: AppContainer) {
    val viewModel = remember { ChatViewModel(container.router) }
    DisposableEffect(container) {
        container.startUiSession()
        onDispose { container.stopUiSession() }
    }
    val state by viewModel.uiState.collectAsState()
    val backgroundMeshEnabled by container.backgroundMeshEnabled.collectAsState()
    var confirmWipe by remember { mutableStateOf(false) }
    var filePendingSave by remember { mutableStateOf<ReceivedFile?>(null) }
    var peerPendingTrust by remember { mutableStateOf<Peer?>(null) }
    var diagnosticsReport by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            readPickedFile(context, uri)?.let { file ->
                viewModel.sendFile(
                    fileName = file.name,
                    mimeType = file.mimeType,
                    bytes = file.bytes
                )
            }
        }
    }
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = filePendingSave
        filePendingSave = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            writeReceivedFile(context, uri, file)
        }
    }
    AirChatScreen(
        state = state,
        onMessageChanged = viewModel::updateComposer,
        onChannelChanged = viewModel::updateChannel,
        onSend = viewModel::sendCurrentMessage,
        onRefresh = viewModel::retryDiscovery,
        onConnectPeer = viewModel::connectWifiPeer,
        onSelectDirect = viewModel::selectDirectPeer,
        onTrustPeer = { peer -> peerPendingTrust = peer },
        onForgetPeer = viewModel::forgetPeer,
        onClearDirect = viewModel::clearDirectPeer,
        backgroundMeshEnabled = backgroundMeshEnabled,
        onToggleBackgroundMesh = {
            if (backgroundMeshEnabled) {
                MeshForegroundService.stop(context)
            } else {
                MeshForegroundService.start(context)
            }
        },
        onPanicWipe = { confirmWipe = true },
        onShowDiagnostics = {
            diagnosticsReport = DiagnosticsReportFormatter.format(
                diagnosticsSnapshot(
                    context = context,
                    state = state,
                    backgroundMeshEnabled = backgroundMeshEnabled,
                    identityKeySecurity = container.identityStore.identityKeySecurity
                )
            )
        },
        onPickFile = { filePicker.launch("*/*") },
        onSaveFile = { file ->
            filePendingSave = file
            saveFileLauncher.launch(file.fileName)
        },
        onShareFile = { file ->
            coroutineScope.launch {
                shareReceivedFile(context, file)
            }
        }
    )
    diagnosticsReport?.let { report ->
        AlertDialog(
            onDismissRequest = { diagnosticsReport = null },
            confirmButton = {
                TextButton(onClick = { shareDiagnostics(context, report) }) {
                    Text("Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { diagnosticsReport = null }) {
                    Text("Close")
                }
            },
            title = { Text("Diagnostics") },
            text = {
                Text(
                    text = report,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        )
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        container.panicWipe()
                        viewModel.clearDirectPeer()
                        confirmWipe = false
                    }
                ) {
                    Text("Wipe")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text("Cancel")
                }
            },
            title = { Text("Wipe local data?") },
            text = { Text("Messages, outbox, peer cache, and identity on disk will be removed.") }
        )
    }
    peerPendingTrust?.let { peer ->
        val safetyNumber = peer.publicKey?.let { SafetyNumber.shortCode(state.localPublicKey, it) } ?: "Unavailable"
        AlertDialog(
            onDismissRequest = { peerPendingTrust = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.trustPeer(peer)
                        peerPendingTrust = null
                    }
                ) {
                    Text("Trust")
                }
            },
            dismissButton = {
                TextButton(onClick = { peerPendingTrust = null }) {
                    Text("Cancel")
                }
            },
            title = { Text("Trust ${peer.name}?") },
            text = {
                Text("Compare this safety number out of band before trusting the peer key: $safetyNumber")
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AirChatScreen(
    state: ChatUiState,
    onMessageChanged: (String) -> Unit,
    onChannelChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onConnectPeer: (Peer) -> Unit,
    onSelectDirect: (Peer) -> Unit,
    onTrustPeer: (Peer) -> Unit,
    onForgetPeer: (Peer) -> Unit,
    onClearDirect: () -> Unit,
    backgroundMeshEnabled: Boolean,
    onToggleBackgroundMesh: () -> Unit,
    onPanicWipe: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onPickFile: () -> Unit,
    onSaveFile: (ReceivedFile) -> Unit,
    onShareFile: (ReceivedFile) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AirChat", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${state.nickname} / ${state.localPeerId}",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onShowDiagnostics) {
                        Icon(Icons.Default.Info, contentDescription = "Diagnostics")
                    }
                    IconButton(onClick = onToggleBackgroundMesh) {
                        Icon(
                            imageVector = if (backgroundMeshEnabled) {
                                Icons.Default.NotificationsActive
                            } else {
                                Icons.Default.NotificationsOff
                            },
                            contentDescription = if (backgroundMeshEnabled) {
                                "Stop background mesh"
                            } else {
                                "Keep mesh running in background"
                            }
                        )
                    }
                    IconButton(onClick = onPanicWipe) {
                        Icon(Icons.Default.DeleteForever, contentDescription = "Wipe local data")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh discovery")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            MessageComposer(
                channel = state.channel,
                privateRoomEnabled = state.privateRoomEnabled,
                message = state.composer,
                directPeer = state.directPeer,
                onChannelChanged = onChannelChanged,
                onMessageChanged = onMessageChanged,
                onClearDirect = onClearDirect,
                onPickFile = onPickFile,
                onSend = onSend
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.transportStatuses.forEach { status ->
                    AssistChip(
                        onClick = onRefresh,
                        label = { Text("${status.name}: ${status.state.name}") },
                        leadingIcon = {
                            Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
                if (state.privateRoomEnabled && state.directPeer == null) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Private #${state.channel}") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                }
            }
            PeerStrip(
                peers = state.peers,
                localPublicKey = state.localPublicKey,
                onConnectPeer = onConnectPeer,
                onSelectDirect = onSelectDirect,
                onTrustPeer = onTrustPeer,
                onForgetPeer = onForgetPeer
            )
            FileStrip(
                files = state.receivedFiles,
                onSaveFile = onSaveFile,
                onShareFile = onShareFile
            )
            MessageList(messages = state.messages, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun FileStrip(
    files: List<ReceivedFile>,
    onSaveFile: (ReceivedFile) -> Unit,
    onShareFile: (ReceivedFile) -> Unit
) {
    if (files.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Files",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            files.takeLast(3).asReversed().forEach { file ->
                ListItem(
                    headlineContent = {
                        Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            text = "${formatBytes(file.totalBytes)} / ${file.sha256.take(12)}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { onSaveFile(file) }) {
                                Icon(Icons.Default.SaveAlt, contentDescription = "Save received file")
                            }
                            IconButton(onClick = { onShareFile(file) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share received file")
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PeerStrip(
    peers: List<Peer>,
    localPublicKey: String,
    onConnectPeer: (Peer) -> Unit,
    onSelectDirect: (Peer) -> Unit,
    onTrustPeer: (Peer) -> Unit,
    onForgetPeer: (Peer) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Peers nearby",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (peers.isEmpty()) {
                Text(
                    text = "No local peers yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                peers.take(4).forEach { peer ->
                    PeerRow(
                        peer = peer,
                        localPublicKey = localPublicKey,
                        onConnectPeer = onConnectPeer,
                        onSelectDirect = onSelectDirect,
                        onTrustPeer = onTrustPeer,
                        onForgetPeer = onForgetPeer
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: Peer,
    localPublicKey: String,
    onConnectPeer: (Peer) -> Unit,
    onSelectDirect: (Peer) -> Unit,
    onTrustPeer: (Peer) -> Unit,
    onForgetPeer: (Peer) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(peer.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val safety = peer.publicKey?.let { SafetyNumber.shortCode(localPublicKey, it) }
            Text(
                text = listOfNotNull(
                    peer.transport.name,
                    peer.connectionState.name,
                    trustLabel(peer.trustState),
                    safety?.let { "Safety $it" }
                ).joinToString(" / "),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (peer.trustState) {
                    PeerTrustState.Trusted -> TextButton(onClick = { onForgetPeer(peer) }) {
                        Text("Forget")
                    }

                    PeerTrustState.KeyChanged -> TextButton(onClick = { onTrustPeer(peer) }, enabled = peer.publicKey != null) {
                        Text("Trust")
                    }

                    PeerTrustState.Unknown -> TextButton(onClick = { onTrustPeer(peer) }, enabled = peer.publicKey != null) {
                        Text("Trust")
                    }
                }
                TextButton(
                    onClick = { onSelectDirect(peer) },
                    enabled = peer.publicKey != null && peer.trustState != PeerTrustState.KeyChanged
                ) {
                    Text("DM")
                }
                if (peer.connectionState != PeerConnectionState.Connected) {
                    Button(onClick = { onConnectPeer(peer) }) {
                        Text("Link")
                    }
                }
            }
        }
    )
}

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages.asReversed(), key = { it.id }) { message ->
            MessageBubble(message)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.isLocal) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isLocal) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (message.isLocal) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val shape = RoundedCornerShape(
        topStart = 8.dp,
        topEnd = 8.dp,
        bottomStart = if (message.isLocal) 8.dp else 2.dp,
        bottomEnd = if (message.isLocal) 2.dp else 8.dp
    )
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = shape,
            colors = CardDefaults.elevatedCardColors(containerColor = bubbleColor, contentColor = contentColor),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (message.isLocal) 0.dp else 1.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = remember(message.createdAt) { shortTime(message.createdAt) },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(message.body, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = deliveryLabel(message),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun MessageComposer(
    channel: String,
    privateRoomEnabled: Boolean,
    message: String,
    directPeer: Peer?,
    onChannelChanged: (String) -> Unit,
    onMessageChanged: (String) -> Unit,
    onClearDirect: () -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (directPeer != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (directPeer.trustState == PeerTrustState.KeyChanged) {
                            "Key changed for ${directPeer.name}"
                        } else {
                            "Direct to ${directPeer.name}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = onClearDirect) {
                        Text("Room")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (directPeer == null) {
                    OutlinedTextField(
                        modifier = Modifier.weight(0.36f),
                        value = channel,
                        onValueChange = onChannelChanged,
                        singleLine = true,
                        label = { Text(if (privateRoomEnabled) "Private" else "Channel") },
                        leadingIcon = if (privateRoomEnabled) {
                            {
                                Icon(Icons.Default.Lock, contentDescription = null)
                            }
                        } else {
                            null
                        }
                    )
                }
                OutlinedTextField(
                    modifier = Modifier.weight(if (directPeer == null) 0.64f else 1f),
                    value = message,
                    onValueChange = onMessageChanged,
                    singleLine = true,
                    label = { Text("Message") },
                    leadingIcon = {
                        IconButton(onClick = onPickFile) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Attach file")
                        }
                    },
                    trailingIcon = {
                        IconButton(onClick = onSend, enabled = message.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                )
            }
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            )
        }
    }
}

private fun shortTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun formatBytes(bytes: Int): String {
    return when {
        bytes < 1_024 -> "$bytes B"
        bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
        else -> "${bytes / (1_024 * 1_024)} MB"
    }
}

private fun deliveryLabel(message: ChatMessage): String {
    val base = when (message.state) {
        DeliveryState.Pending -> "pending"
        DeliveryState.Sent -> "sent"
        DeliveryState.Received -> "received"
        DeliveryState.Verified -> "verified"
        DeliveryState.Unverified -> "unverified"
        DeliveryState.Locked -> "locked"
    }
    val visibility = if (message.channel.startsWith("dm:")) "direct" else message.channel
    val hopLabel = if (message.hopCount > 0) " / ${message.hopCount} hops" else ""
    return "$visibility / $base$hopLabel"
}

private fun trustLabel(state: PeerTrustState): String {
    return when (state) {
        PeerTrustState.Unknown -> "Untrusted"
        PeerTrustState.Trusted -> "Trusted"
        PeerTrustState.KeyChanged -> "Key changed"
    }
}

private data class PickedFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray
)

private suspend fun readPickedFile(context: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "airchat-file"
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_PICKED_FILE_BYTES) return@withContext null
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: return@withContext null
    PickedFile(name = name, mimeType = mimeType, bytes = bytes)
}

private suspend fun writeReceivedFile(context: Context, uri: Uri, file: ReceivedFile) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri)?.use { output ->
        output.write(file.bytes)
    }
}

private suspend fun shareReceivedFile(context: Context, file: ReceivedFile) {
    val uri = withContext(Dispatchers.IO) {
        val sharedDir = File(context.cacheDir, SHARED_RECEIVED_DIR).apply { mkdirs() }
        val safeName = file.fileName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "airchat-file" }
        val sharedFile = File(sharedDir, "${file.id.take(12)}-$safeName")
        sharedFile.writeBytes(file.bytes)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            sharedFile
        )
    }
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = file.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, file.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(shareIntent, "Share ${file.fileName}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun diagnosticsSnapshot(
    context: Context,
    state: ChatUiState,
    backgroundMeshEnabled: Boolean,
    identityKeySecurity: IdentityKeySecurity
): DiagnosticsSnapshot {
    return DiagnosticsSnapshot(
        appVersion = appVersion(context),
        protocolVersion = DiagnosticsReportFormatter.PROTOCOL_VERSION,
        device = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim(),
        androidVersion = "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}",
        localPeerId = state.localPeerId,
        nickname = state.nickname,
        identityKeySecurity = identityKeySecurity,
        channel = state.channel,
        privateRoomEnabled = state.privateRoomEnabled,
        directPeerName = state.directPeer?.name,
        backgroundMeshEnabled = backgroundMeshEnabled,
        peerCount = state.peers.size,
        visibleMessageCount = state.messages.size,
        visibleFileCount = state.receivedFiles.size,
        courierQueueSize = state.courierQueueSize,
        transportStatuses = state.transportStatuses
    )
}

private fun appVersion(context: Context): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    return "${packageInfo.versionName ?: "debug"} ($versionCode)"
}

private fun shareDiagnostics(context: Context, report: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "AirChat diagnostics")
        putExtra(Intent.EXTRA_TEXT, report)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(
        Intent.createChooser(shareIntent, "Share diagnostics")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private const val MAX_PICKED_FILE_BYTES = 10 * 1024 * 1024
private const val SHARED_RECEIVED_DIR = "shared-received"
