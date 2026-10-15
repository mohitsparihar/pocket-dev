package dev.pocket.app.ui.connections

import dev.pocket.app.ui.components.rememberSafeScope
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.model.ConnectResult
import dev.pocket.app.model.ConnectionKind
import dev.pocket.app.model.ConnectionPhase
import dev.pocket.app.model.ConnectionProfile
import dev.pocket.app.model.DiagnosticLine
import dev.pocket.app.service.PocketConnectionService
import dev.pocket.app.ui.components.StatusChip
import dev.pocket.app.ui.components.StatusTone
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(repository: PocketRepository) {
    val scope = rememberSafeScope()
    val context = LocalContext.current
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val profiles by repository.connections().collectAsState(initial = emptyList())
    val activeConnection by repository.activeConnection().collectAsState(initial = null)
    val googleAccount by repository.googleAccount().collectAsState(initial = null)
    var signingIn by remember { mutableStateOf(false) }
    var signInResult by remember { mutableStateOf<ConnectResult?>(null) }

    // Connect result per profile
    val connectResults = remember { mutableStateMapOf<String, ConnectResult>() }
    // Test results per profile
    val testResults = remember { mutableStateMapOf<String, List<DiagnosticLine>>() }
    // Loading state per profile
    val loadingIds = remember { mutableStateMapOf<String, Boolean>() }

    var editProfile by remember { mutableStateOf<ConnectionProfile?>(null) }
    var deleteTarget by remember { mutableStateOf<ConnectionProfile?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            GoogleAccountBar(
                account = googleAccount,
                signingIn = signingIn,
                result = signInResult,
                onSignIn = {
                    scope.launch {
                        signingIn = true
                        signInResult = repository.signInGoogle(context)
                        signingIn = false
                    }
                },
            )
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Link,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No connections — tap + to add a server profile",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    val isActive = activeConnection?.id == profile.id
                    val connectResult = connectResults[profile.id]
                    val tests = testResults[profile.id]
                    val loading = loadingIds[profile.id] == true

                    ProfileCard(
                        profile = profile,
                        isActive = isActive,
                        loading = loading,
                        connectResult = connectResult,
                        testResults = tests,
                        onConnect = {
                            scope.launch {
                                if (Build.VERSION.SDK_INT >= 33 && profile.kind != ConnectionKind.Demo) {
                                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                loadingIds[profile.id] = true
                                val result = repository.connect(profile.id)
                                connectResults[profile.id] = result
                                if (result.ok && profile.kind != ConnectionKind.Demo) {
                                    startConnectionService(context, profile)
                                }
                                loadingIds[profile.id] = false
                            }
                        },
                        onTrustHostKey = { fingerprint ->
                            scope.launch {
                                loadingIds[profile.id] = true
                                repository.acceptHostKey(profile.id, fingerprint)
                                val result = repository.connect(profile.id)
                                connectResults[profile.id] = result
                                if (result.ok && profile.kind != ConnectionKind.Demo) {
                                    startConnectionService(context, profile)
                                }
                                loadingIds[profile.id] = false
                            }
                        },
                        onDisconnect = {
                            scope.launch {
                                repository.disconnect()
                                context.startService(Intent(context, PocketConnectionService::class.java).apply {
                                    action = PocketConnectionService.ACTION_DISCONNECT
                                })
                                connectResults.remove(profile.id)
                            }
                        },
                        onTest = {
                            scope.launch {
                                loadingIds[profile.id] = true
                                val lines = repository.testConnection(profile.id)
                                testResults[profile.id] = lines
                                loadingIds[profile.id] = false
                            }
                        },
                        onEdit = { editProfile = profile },
                        onDelete = { deleteTarget = profile },
                    )
                }
            }
        }
        }

        FloatingActionButton(
            onClick = {
                editProfile = ConnectionProfile(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    kind = ConnectionKind.DirectSsh,
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "Add connection")
        }
    }

    editProfile?.let { profile ->
        EditProfileSheet(
            profile = profile,
            onDismiss = { editProfile = null },
            onSave = { updated, secret ->
                scope.launch {
                    repository.upsertConnection(updated)
                    if (secret != null) {
                        repository.saveSshSecret(updated.id, secret.first, secret.second)
                    }
                    editProfile = null
                }
            },
        )
    }

    deleteTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete connection?") },
            text = { Text("\"${profile.name}\" will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { repository.deleteConnection(profile.id) }
                        deleteTarget = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ConnectionProfile,
    isActive: Boolean,
    loading: Boolean,
    connectResult: ConnectResult?,
    testResults: List<DiagnosticLine>?,
    onConnect: () -> Unit,
    onTrustHostKey: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        profile.name.ifEmpty { "Unnamed" },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (profile.kind == ConnectionKind.GoogleCloud)
                                Icons.Outlined.Cloud
                            else
                                Icons.Outlined.Computer,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when (profile.kind) {
                                ConnectionKind.DirectSsh -> "Direct SSH · ${profile.host}"
                                ConnectionKind.GoogleCloud -> "Google Cloud · ${profile.instance.ifBlank { profile.project }}"
                                ConnectionKind.Demo -> "Demo"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (profile.username.isNotEmpty()) {
                        Text(
                            profile.username,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                StatusChip(
                    label = profile.phase.label(),
                    tone = profile.phase.tone(),
                )
            }

            // Connect result message
            if (connectResult != null && !connectResult.ok) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                connectResult.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        if (connectResult.recovery.isNotEmpty()) {
                            Text(
                                connectResult.recovery,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        val fingerprint = hostKeyFingerprint(connectResult.message, connectResult.recovery)
                        if (fingerprint != null) {
                            Button(
                                onClick = { onTrustHostKey(fingerprint) },
                                modifier = Modifier.height(48.dp),
                            ) {
                                Text("Trust this host key")
                            }
                        }
                    }
                }
            }

            // Google Cloud sign-in notice
            if (profile.kind == ConnectionKind.GoogleCloud) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "Connect starts the VM if it is stopped, opens the IAP tunnel in this app, then SSHes with the imported key. Termux does not need to stay open.",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Test results
            if (testResults != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Diagnostic results",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    testResults.forEach { line ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (line.ok) Icons.Outlined.Check else Icons.Outlined.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (line.ok)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                            )
                            Column {
                                Text(line.name, style = MaterialTheme.typography.bodySmall)
                                if (line.detail.isNotEmpty()) {
                                    Text(
                                        line.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.height(40.dp),
                        enabled = !loading,
                    ) {
                        Icon(Icons.Outlined.WifiOff, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.height(40.dp),
                        enabled = !loading,
                    ) {
                        Icon(Icons.Outlined.Wifi, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(if (loading) "Connecting…" else "Connect")
                    }
                }

                IconButton(
                    onClick = onTest,
                    modifier = Modifier.size(40.dp),
                    enabled = !loading,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Test connection")
                }

                Spacer(Modifier.weight(1f))

                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit profile")
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete profile",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileSheet(
    profile: ConnectionProfile,
    onDismiss: () -> Unit,
    onSave: (ConnectionProfile, Pair<ByteArray?, String?>?) -> Unit,
) {
    var name by remember { mutableStateOf(profile.name) }
    var kind by remember { mutableStateOf(profile.kind) }
    var host by remember { mutableStateOf(profile.host) }
    var portText by remember { mutableStateOf(profile.port.toString()) }
    var username by remember { mutableStateOf(profile.username) }
    var gcProject by remember { mutableStateOf(profile.project) }
    var zone by remember { mutableStateOf(profile.zone) }
    var instance by remember { mutableStateOf(profile.instance) }
    var companionPortText by remember { mutableStateOf(profile.companionPort.toString()) }
    var setupCode by remember { mutableStateOf(profile.setupCode) }
    var password by remember { mutableStateOf("") }
    var keyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var keyLabel by remember { mutableStateOf("No key imported") }
    var error by remember { mutableStateOf("") }
    val context = LocalContext.current
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.isEmpty()) {
            error = "Could not read that key file"
        } else {
            keyBytes = bytes
            keyLabel = "Key ready (${bytes.size} bytes)"
            error = ""
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (profile.name.isEmpty()) "New Connection" else "Edit Connection",
                style = MaterialTheme.typography.titleLarge,
            )

            // Kind selector
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == ConnectionKind.DirectSsh,
                        onClick = { kind = ConnectionKind.DirectSsh },
                        label = { Text("Direct SSH") },
                        leadingIcon = { Icon(Icons.Outlined.Computer, null, modifier = Modifier.size(16.dp)) },
                    )
                    FilterChip(
                        selected = kind == ConnectionKind.GoogleCloud,
                        onClick = { kind = ConnectionKind.GoogleCloud },
                        label = { Text("Google Cloud") },
                        leadingIcon = { Icon(Icons.Outlined.Cloud, null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. Work VPS") },
            )

            when (kind) {
                ConnectionKind.DirectSsh -> {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("e.g. 12.34.56.78 or myserver.com") },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it },
                            label = { Text("SSH port") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            placeholder = { Text("22") },
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password, if the server uses one") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedButton(
                        onClick = { keyPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("Import private key")
                    }
                    Text(keyLabel, style = MaterialTheme.typography.bodySmall)
                }
                ConnectionKind.GoogleCloud -> {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Google Cloud via IAP",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                "Sign in on the Connections tab with the Google account that can start this VM. Pocket Dev starts the instance, opens IAP, and SSHes with the key you import here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = instance,
                        onValueChange = { instance = it },
                        label = { Text("Instance name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = gcProject,
                        onValueChange = { gcProject = it },
                        label = { Text("GCP project ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = zone,
                        onValueChange = { zone = it },
                        label = { Text("Zone") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("asia-south1-b") },
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("SSH username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = { keyPicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text("Import private key")
                    }
                    Text(keyLabel, style = MaterialTheme.typography.bodySmall)
                }
                ConnectionKind.Demo -> {
                    Text(
                        "Demo mode — no real connection. Used for UI development.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = setupCode,
                onValueChange = { setupCode = it },
                label = { Text("Companion setup code") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = companionPortText,
                onValueChange = { companionPortText = it },
                label = { Text("Companion port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { Text("8787") },
            )

            if (error.isNotEmpty()) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    if (name.isBlank()) { error = "Enter a profile name"; return@Button }
                    if (kind == ConnectionKind.DirectSsh && host.isBlank()) {
                        error = "Enter a host address"; return@Button
                    }
                    if (kind == ConnectionKind.GoogleCloud && (instance.isBlank() || gcProject.isBlank() || zone.isBlank() || username.isBlank())) {
                        error = "Enter the instance, project, zone, and SSH username"; return@Button
                    }
                    val port = portText.toIntOrNull() ?: 22
                    val compPort = companionPortText.toIntOrNull() ?: 8787
                    onSave(
                        profile.copy(
                            name = name.trim(),
                            kind = kind,
                            host = if (kind == ConnectionKind.GoogleCloud) instance.trim() else host.trim(),
                            port = if (kind == ConnectionKind.GoogleCloud) 22 else port,
                            username = username.trim(),
                            project = gcProject.trim(),
                            zone = zone.trim(),
                            instance = instance.trim(),
                            companionPort = compPort,
                            setupCode = setupCode.trim(),
                        ),
                        if (keyBytes != null || password.isNotBlank()) keyBytes to password else null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun GoogleAccountBar(
    account: String?,
    signingIn: Boolean,
    result: ConnectResult?,
    onSignIn: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Google account", style = MaterialTheme.typography.labelLarge)
                    Text(
                        account ?: "Not signed in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onSignIn, enabled = !signingIn, modifier = Modifier.height(40.dp)) {
                    Text(if (signingIn) "Waiting…" else if (account == null) "Sign in" else "Switch")
                }
            }
            if (result != null && !result.ok) {
                Text(result.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun startConnectionService(context: android.content.Context, profile: ConnectionProfile) {
    val host = profile.instance.ifBlank { profile.host }
    val intent = Intent(context, PocketConnectionService::class.java).apply {
        action = PocketConnectionService.ACTION_CONNECT
        putExtra(PocketConnectionService.EXTRA_CONNECTION_ID, profile.id)
        putExtra(PocketConnectionService.EXTRA_HOST, host)
    }
    ContextCompat.startForegroundService(context, intent)
}

private fun hostKeyFingerprint(message: String, recovery: String): String? {
    if (!message.startsWith("Unknown host key") && !message.startsWith("Changed host key")) return null
    return Regex("SHA256:[A-Za-z0-9+/=_-]+").find("$message $recovery")?.value
}

private fun ConnectionPhase.label(): String = when (this) {
    ConnectionPhase.Disconnected -> "Disconnected"
    ConnectionPhase.Connecting -> "Connecting"
    ConnectionPhase.Connected -> "Connected"
    ConnectionPhase.Failed -> "Failed"
}

private fun ConnectionPhase.tone(): StatusTone = when (this) {
    ConnectionPhase.Connected -> StatusTone.Ok
    ConnectionPhase.Connecting -> StatusTone.Busy
    ConnectionPhase.Failed -> StatusTone.Bad
    ConnectionPhase.Disconnected -> StatusTone.Wait
}
