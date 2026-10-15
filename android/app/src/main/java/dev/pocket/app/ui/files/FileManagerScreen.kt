package dev.pocket.app.ui.files

import dev.pocket.app.ui.components.rememberSafeScope
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.model.CollisionPolicy
import dev.pocket.app.model.FileEntry
import dev.pocket.app.model.TransferDirection
import dev.pocket.app.model.TransferJob
import dev.pocket.app.model.TransferState
import dev.pocket.app.ui.components.StatusChip
import dev.pocket.app.ui.components.StatusTone
import kotlinx.coroutines.launch

private data class CollisionState(val uri: Uri, val name: String, val dest: String, val remaining: List<Uri>)
private data class PendingDownload(val path: String, val defaultLabel: String)

@Composable
fun FileManagerScreen(
    repository: PocketRepository,
    projectId: String,
    onOpenFile: (String) -> Unit,
) {
    val scope = rememberSafeScope()
    val project by repository.project(projectId).collectAsState(initial = null)
    val transfers by repository.transfers().collectAsState(initial = emptyList())

    val rootPath = remember(project) { project?.folder ?: "" }
    var currentPath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }

    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<List<String>?>(null) }
    var collision by remember { mutableStateOf<CollisionState?>(null) }
    var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }
    var downloadLabel by remember { mutableStateOf("") }

    LaunchedEffect(rootPath) {
        if (rootPath.isNotBlank() && currentPath.isBlank()) currentPath = rootPath
    }

    suspend fun reload() {
        if (currentPath.isBlank()) return
        loading = true
        loadError = null
        try { entries = repository.listDir(projectId, currentPath) }
        catch (e: Exception) { loadError = e.message }
        finally { loading = false }
    }

    LaunchedEffect(currentPath) { reload() }

    val breadcrumbs: List<Pair<String, String>> = remember(currentPath, rootPath) {
        if (currentPath.isBlank() || rootPath.isBlank()) return@remember emptyList()
        val segs = currentPath.removePrefix(rootPath.trimEnd('/')).split('/').filter { it.isNotBlank() }
        val crumbs = mutableListOf("~" to rootPath.trimEnd('/'))
        var acc = rootPath.trimEnd('/')
        for (s in segs) { acc = "$acc/$s"; crumbs += s to acc }
        crumbs
    }

    val filtered = remember(entries, search) {
        if (search.isBlank()) entries
        else entries.filter { it.name.contains(search, ignoreCase = true) }
    }.sortedWith(compareByDescending<FileEntry> { it.directory }.thenBy { it.name })

    val parentPath = remember(currentPath, rootPath) {
        val t = currentPath.trimEnd('/')
        if (t == rootPath.trimEnd('/')) null else t.substringBeforeLast('/')
    }

    fun processUploads(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val uri = uris.first()
        val name = uri.lastPathSegment?.substringAfterLast(':') ?: "file"
        val dest = "${currentPath.trimEnd('/')}/$name"
        if (entries.any { it.path == dest }) {
            collision = CollisionState(uri, name, dest, uris.drop(1))
        } else {
            scope.launch {
                repository.enqueueUpload(projectId, name, dest, 0L, CollisionPolicy.Replace)
                val rest = uris.drop(1)
                if (rest.isNotEmpty()) processUploads(rest)
                else reload()
            }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        processUploads(uris)
    }

    Column(Modifier.fillMaxSize()) {
        project?.let { p ->
            Text(
                "${p.name}  ${currentPath}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                maxLines = 1,
            )
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            breadcrumbs.forEachIndexed { i, (label, path) ->
                if (i > 0) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                TextButton(
                    onClick = { currentPath = path; selected.clear() },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search in this folder") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (search.isNotBlank()) {
                { IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, "Clear") } }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { parentPath?.let { currentPath = it; selected.clear() } },
                enabled = parentPath != null,
                modifier = Modifier.height(48.dp),
            ) {
                Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Up")
            }
            OutlinedButton(
                onClick = { showCreateFolder = true },
                modifier = Modifier.height(48.dp),
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Folder")
            }
            OutlinedButton(
                onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.height(48.dp),
            ) {
                Icon(Icons.Outlined.Upload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Upload")
            }
            if (selected.isNotEmpty()) {
                OutlinedButton(
                    onClick = { deleteTarget = selected.toList() },
                    modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete (${selected.size})")
                }
            }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        loadError?.let { err ->
            Text("Error: $err", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }

        if (!loading && loadError == null && filtered.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (search.isNotBlank()) "No items match \"$search\"" else "This folder is empty",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(filtered, key = { it.path }) { entry ->
                FileRow(
                    entry = entry,
                    checked = entry.path in selected,
                    onToggle = {
                        if (entry.path in selected) selected.remove(entry.path) else selected.add(entry.path)
                    },
                    onClick = {
                        if (entry.directory) { currentPath = entry.path; selected.clear() }
                        else onOpenFile(entry.path)
                    },
                    onEdit = { onOpenFile(entry.path) },
                    onDownload = {
                        pendingDownload = PendingDownload(entry.path, entry.name)
                        downloadLabel = entry.name
                    },
                )
            }
        }

        val projectTransfers = transfers.filter { it.projectId == projectId }
        if (projectTransfers.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Transfers",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(projectTransfers, key = { it.id }) { job ->
                    TransferRow(
                        job = job,
                        onRetry = { scope.launch { repository.retryTransfer(job.id) } },
                        onCancel = { scope.launch { repository.cancelTransfer(job.id) } },
                    )
                }
            }
        }
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFolderName.isNotBlank()) {
                        val path = "${currentPath.trimEnd('/')}/$newFolderName"
                        scope.launch {
                            repository.mkdir(projectId, path)
                            newFolderName = ""
                            showCreateFolder = false
                            reload()
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolder = false; newFolderName = "" }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { paths ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${paths.size} item(s)?") },
            text = { Text("Files will be removed from the VPS. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            repository.deletePaths(projectId, paths)
                            selected.clear()
                            deleteTarget = null
                            reload()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }

    collision?.let { state ->
        AlertDialog(
            onDismissRequest = { collision = null },
            title = { Text("File exists: ${state.name}") },
            text = { Text("${state.name} already exists in this folder.") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "Skip" to CollisionPolicy.Skip,
                        "Keep both" to CollisionPolicy.KeepBoth,
                        "Replace" to CollisionPolicy.Replace,
                    ).forEach { (label, policy) ->
                        TextButton(onClick = {
                            val current = state
                            collision = null
                            scope.launch {
                                repository.enqueueUpload(projectId, current.name, current.dest, 0L, policy)
                                if (current.remaining.isNotEmpty()) processUploads(current.remaining)
                                else reload()
                            }
                        }) { Text(label) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { collision = null }) { Text("Cancel all") } },
        )
    }

    pendingDownload?.let { dl ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text("Download") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Phone destination label:")
                    OutlinedTextField(
                        value = downloadLabel,
                        onValueChange = { downloadLabel = it },
                        label = { Text("Label") },
                        singleLine = true,
                    )
                    Text(
                        "In demo mode the transfer completes in the queue. On a connected VPS the file would be saved to the location you choose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = dl.path
                    val label = downloadLabel
                    pendingDownload = null
                    scope.launch { repository.enqueueDownload(projectId, target, label) }
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { pendingDownload = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    checked: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDownload: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.size(48.dp))
        Icon(
            imageVector = when {
                entry.directory -> Icons.Outlined.Folder
                entry.symlink -> Icons.Outlined.Link
                else -> Icons.Outlined.InsertDriveFile
            },
            contentDescription = null,
            tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                if (entry.symlink) Text("symlink", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!entry.directory && entry.bytes > 0) {
                    Text(fmtBytes(entry.bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (entry.mode.isNotBlank()) {
                    Text(entry.mode, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.MoreVert, "Actions")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                if (!entry.directory) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        onClick = { showMenu = false; onClick() },
                        leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { showMenu = false; onEdit() },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Download") },
                        onClick = { showMenu = false; onDownload() },
                        leadingIcon = { Icon(Icons.Outlined.Download, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferRow(
    job: TransferJob,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val (chipLabel, tone) = when (job.state) {
        TransferState.Completed -> "Completed" to StatusTone.Ok
        TransferState.Failed -> "Failed" to StatusTone.Bad
        TransferState.Cancelled -> "Cancelled" to StatusTone.Bad
        TransferState.Transferring -> "Transferring" to StatusTone.Busy
        TransferState.Disconnected -> "Disconnected" to StatusTone.Wait
        TransferState.Queued -> "Queued" to StatusTone.Wait
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (job.direction == TransferDirection.Upload) Icons.Outlined.Upload else Icons.Outlined.Download,
            contentDescription = job.direction.name,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text("${job.source} → ${job.destination}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            if (job.bytesTotal > 0) {
                Text("${fmtBytes(job.bytesDone)} / ${fmtBytes(job.bytesTotal)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (job.state == TransferState.Transferring) {
                    LinearProgressIndicator(
                        progress = { job.bytesDone.toFloat() / job.bytesTotal.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
            }
            if (job.message.isNotBlank()) {
                Text(job.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        StatusChip(chipLabel, tone)
        if (job.state == TransferState.Failed || job.state == TransferState.Disconnected) {
            IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Refresh, "Retry", Modifier.size(18.dp))
            }
        }
        if (job.state == TransferState.Transferring || job.state == TransferState.Queued) {
            IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, "Cancel", Modifier.size(18.dp))
            }
        }
    }
}

private fun fmtBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "${b / (1024 * 1024)} MB"
}
