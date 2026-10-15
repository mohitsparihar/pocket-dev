package dev.pocket.app.ui.preview

import dev.pocket.app.ui.components.rememberSafeScope
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.model.PortMapping
import dev.pocket.app.model.ProcessState
import dev.pocket.app.model.TunnelState
import dev.pocket.app.ui.components.StatusChip
import dev.pocket.app.ui.components.StatusTone
import kotlinx.coroutines.launch

@Composable
fun PreviewScreen(repository: PocketRepository) {
    val scope = rememberSafeScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val mappings by repository.mappings().collectAsState(initial = emptyList())
    val activeProjectId by repository.activeProjectId().collectAsState(initial = null)

    var addLabel by remember { mutableStateOf("") }
    var addRemotePort by remember { mutableStateOf("") }
    var addPhonePort by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }

    // per-mapping tunnel error messages
    val tunnelErrors = remember { mutableStateMapOf<String, String>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (mappings.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Outlined.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "No port mappings — add one below to forward a VM port to your phone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(mappings, key = { it.id }) { mapping ->
                MappingCard(
                    mapping = mapping,
                    tunnelError = tunnelErrors[mapping.id],
                    onStart = {
                        scope.launch {
                            val result = repository.startMapping(mapping.id)
                            if (result.tunnelState == TunnelState.Failed) {
                                tunnelErrors[mapping.id] =
                                    "Port ${mapping.phonePort} is occupied — " +
                                    "offered port: ${result.phonePort}"
                            } else {
                                tunnelErrors.remove(mapping.id)
                            }
                        }
                    },
                    onStop = { scope.launch { repository.stopMapping(mapping.id) } },
                    onCopy = { clipboard.setText(AnnotatedString(mapping.url)) },
                    onOpen = {
                        if (mapping.url.isNotEmpty()) {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(mapping.url))
                            )
                        }
                    },
                )
            }
        }

        item {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text(
                "Add mapping",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        item {
            if (activeProjectId == null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "No active project — open a project first to add mappings",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                AddMappingForm(
                    label = addLabel,
                    remotePort = addRemotePort,
                    phonePort = addPhonePort,
                    error = addError,
                    onLabelChange = { addLabel = it },
                    onRemotePortChange = { addRemotePort = it },
                    onPhonePortChange = { addPhonePort = it },
                    onAdd = {
                        val rp = addRemotePort.toIntOrNull()
                        val pp = addPhonePort.toIntOrNull()
                        when {
                            addLabel.isBlank() -> addError = "Enter a label"
                            rp == null || rp !in 1..65535 -> addError = "Invalid VM port"
                            pp == null || pp !in 1..65535 -> addError = "Invalid phone port"
                            else -> {
                                addError = ""
                                scope.launch {
                                    repository.addMapping(
                                        projectId = activeProjectId!!,
                                        label = addLabel.trim(),
                                        remotePort = rp,
                                        phonePort = pp,
                                    )
                                    addLabel = ""
                                    addRemotePort = ""
                                    addPhonePort = ""
                                }
                            }
                        }
                    },
                )
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun MappingCard(
    mapping: PortMapping,
    tunnelError: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(mapping.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "VM: ${mapping.remoteHost}:${mapping.remotePort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Phone port: ${mapping.phonePort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mapping.url.isNotEmpty()) {
                        Text(
                            mapping.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusChip(
                        label = mapping.processState.label(),
                        tone = mapping.processState.tone(),
                    )
                    StatusChip(
                        label = mapping.tunnelState.label(),
                        tone = mapping.tunnelState.tone(),
                    )
                }
            }

            if (tunnelError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        tunnelError,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isRunning = mapping.processState == ProcessState.Running ||
                    mapping.tunnelState == TunnelState.Listening

                if (isRunning) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.height(40.dp),
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Stop")
                    }
                } else {
                    OutlinedButton(
                        onClick = onStart,
                        modifier = Modifier.height(40.dp),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Start")
                    }
                }

                IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy URL", modifier = Modifier.size(20.dp))
                }

                IconButton(
                    onClick = onOpen,
                    modifier = Modifier.size(40.dp),
                    enabled = mapping.url.isNotEmpty() && isRunning,
                ) {
                    Icon(Icons.Outlined.OpenInBrowser, contentDescription = "Open in browser", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun AddMappingForm(
    label: String,
    remotePort: String,
    phonePort: String,
    error: String,
    onLabelChange: (String) -> Unit,
    onRemotePortChange: (String) -> Unit,
    onPhonePortChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text("Label") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("e.g. Frontend") },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = remotePort,
                onValueChange = onRemotePortChange,
                label = { Text("VM port") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { Text("3000") },
            )
            OutlinedTextField(
                value = phonePort,
                onValueChange = onPhonePortChange,
                label = { Text("Phone port") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { Text("3000") },
            )
        }
        if (error.isNotEmpty()) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onAdd,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Add Mapping")
        }
    }
}

private fun ProcessState.label(): String = when (this) {
    ProcessState.Starting -> "Starting"
    ProcessState.Running -> "Running"
    ProcessState.Stopped -> "Stopped"
    ProcessState.Failed -> "Failed"
}

private fun ProcessState.tone(): StatusTone = when (this) {
    ProcessState.Running -> StatusTone.Busy
    ProcessState.Starting -> StatusTone.Wait
    ProcessState.Stopped -> StatusTone.Bad
    ProcessState.Failed -> StatusTone.Bad
}

private fun TunnelState.label(): String = when (this) {
    TunnelState.Listening -> "Tunnel open"
    TunnelState.Closed -> "Tunnel closed"
    TunnelState.Failed -> "Tunnel failed"
}

private fun TunnelState.tone(): StatusTone = when (this) {
    TunnelState.Listening -> StatusTone.Ok
    TunnelState.Closed -> StatusTone.Wait
    TunnelState.Failed -> StatusTone.Bad
}
