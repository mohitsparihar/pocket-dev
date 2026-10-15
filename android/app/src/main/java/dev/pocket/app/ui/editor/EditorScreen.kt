package dev.pocket.app.ui.editor

import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import dev.pocket.app.ui.components.ExtraKeysBar
import dev.pocket.app.ui.components.filterTyped
import dev.pocket.app.ui.components.rememberKeyModifiers
import dev.pocket.app.ui.components.rememberSafeScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.model.EditorBuffer
import dev.pocket.app.model.SaveResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class ConflictState(val result: SaveResult, val localText: String)
private enum class ConflictReview { None, Showing }

@Composable
fun EditorScreen(
    repository: PocketRepository,
    projectId: String,
    path: String,
    onBack: () -> Unit,
) {
    val scope = rememberSafeScope()
    var activePath by remember { mutableStateOf(path) }

    if (activePath.isBlank()) {
        BufferList(repository, projectId, onSelectBuffer = { activePath = it }, onBack = onBack)
    } else {
        FileEditor(
            repository = repository,
            projectId = projectId,
            path = activePath,
            onBack = onBack,
            onSwitchBuffer = { activePath = it },
        )
    }
}

@Composable
private fun BufferList(
    repository: PocketRepository,
    projectId: String,
    onSelectBuffer: (String) -> Unit,
    onBack: () -> Unit,
) {
    val buffers by repository.buffers(projectId).collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Open files", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (buffers.isEmpty()) {
            Text("No open files. Open a file from the file manager.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            buffers.forEach { buf ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectBuffer(buf.path) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(buf.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                            if (buf.dirty) {
                                Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Text(buf.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FileEditor(
    repository: PocketRepository,
    projectId: String,
    path: String,
    onBack: () -> Unit,
    onSwitchBuffer: (String) -> Unit,
) {
    val scope = rememberSafeScope()
    val buffers by repository.buffers(projectId).collectAsState(initial = emptyList())
    val buffer = buffers.find { it.path == path }

    var tfv by remember(path) { mutableStateOf(TextFieldValue("")) }
    var initialized by remember(path) { mutableStateOf(false) }
    var isBinary by remember(path) { mutableStateOf(false) }

    val undoStack = remember(path) { mutableStateListOf<String>() }
    val keys = rememberKeyModifiers()
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    val redoStack = remember(path) { mutableStateListOf<String>() }
    var lastPushed by remember(path) { mutableStateOf("") }

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var matchIndex by remember { mutableStateOf(0) }

    var conflict by remember { mutableStateOf<ConflictState?>(null) }
    var conflictReview by remember { mutableStateOf(ConflictReview.None) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        val buf = repository.openBuffer(projectId, path)
        isBinary = buf.binary
        tfv = TextFieldValue(buf.text)
        lastPushed = buf.text
        initialized = true
    }

    LaunchedEffect(tfv.text) {
        if (!initialized) return@LaunchedEffect
        delay(600L)
        if (tfv.text != lastPushed) {
            undoStack.add(lastPushed)
            if (undoStack.size > 100) undoStack.removeAt(0)
            redoStack.clear()
            lastPushed = tfv.text
            repository.updateBuffer(projectId, path, tfv.text)
        }
    }

    fun doSave() {
        scope.launch {
            saving = true
            saveMessage = null
            val result = repository.saveBuffer(projectId, path)
            saving = false
            if (result.conflict) {
                conflict = ConflictState(result, tfv.text)
            } else {
                saveMessage = if (result.ok) "Saved" else "Error: ${result.message}"
            }
        }
    }

    fun doSaveAll() {
        scope.launch {
            buffers.forEach { buf ->
                if (buf.dirty) repository.saveBuffer(projectId, buf.path)
            }
            saveMessage = "Saved ${buffers.count { it.dirty }} file(s)"
        }
    }

    fun insertAtCursor(text: String) {
        val sel = tfv.selection
        val s = tfv.text
        val newText = s.substring(0, sel.start) + text + s.substring(sel.end)
        tfv = TextFieldValue(newText, TextRange(sel.start + text.length))
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(tfv.text)
        val prev = undoStack.removeLast()
        tfv = TextFieldValue(prev, TextRange(prev.length.coerceAtMost(tfv.selection.start)))
        lastPushed = prev
        scope.launch { repository.updateBuffer(projectId, path, prev) }
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(tfv.text)
        val next = redoStack.removeLast()
        tfv = TextFieldValue(next, TextRange(next.length.coerceAtMost(tfv.selection.start)))
        lastPushed = next
        scope.launch { repository.updateBuffer(projectId, path, next) }
    }

    fun findMatches(): List<IntRange> {
        if (searchQuery.isBlank()) return emptyList()
        val results = mutableListOf<IntRange>()
        var idx = tfv.text.indexOf(searchQuery, ignoreCase = true)
        while (idx != -1) {
            results += idx until idx + searchQuery.length
            idx = tfv.text.indexOf(searchQuery, idx + 1, ignoreCase = true)
        }
        return results
    }

    fun jumpToMatch(delta: Int) {
        val matches = findMatches()
        if (matches.isEmpty()) return
        matchIndex = ((matchIndex + delta) % matches.size + matches.size) % matches.size
        val r = matches[matchIndex]
        tfv = tfv.copy(selection = TextRange(r.first, r.last + 1))
    }

    val isDirty = buffer?.dirty ?: false
    val monoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp)
    val scrollState = rememberScrollState()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${path.substringAfterLast('/')}${if (isDirty) " •" else ""}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showSearch = !showSearch }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Search, "Find")
            }
            IconButton(onClick = ::undo, enabled = undoStack.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Undo, "Undo")
            }
            IconButton(onClick = ::redo, enabled = redoStack.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Redo, "Redo")
            }
            TextButton(onClick = ::doSave, enabled = !saving) { Text("Save") }
            TextButton(onClick = ::doSaveAll, enabled = !saving && buffers.any { it.dirty }) { Text("Save all") }
        }

        saveMessage?.let { msg ->
            Surface(color = if (msg.startsWith("Error")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { saveMessage = null }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, "Dismiss", Modifier.size(16.dp))
                    }
                }
            }
        }

        if (showSearch) {
            val matches = findMatches()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it; matchIndex = 0 },
                    placeholder = { Text("Find") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(48.dp),
                )
                Text(if (matches.isEmpty()) "0/0" else "${matchIndex + 1}/${matches.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                TextButton(onClick = { jumpToMatch(-1) }, enabled = matches.isNotEmpty()) { Text("Prev") }
                TextButton(onClick = { jumpToMatch(1) }, enabled = matches.isNotEmpty()) { Text("Next") }
                IconButton(onClick = { showSearch = false }) { Icon(Icons.Outlined.Close, "Close") }
            }
        }

        if (!buffers.isEmpty() && buffers.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                buffers.forEach { buf ->
                    FilterChip(
                        selected = buf.path == path,
                        onClick = { onSwitchBuffer(buf.path) },
                        label = {
                            Text(
                                "${buf.path.substringAfterLast('/')}${if (buf.dirty) " •" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
        }

        HorizontalDivider()

        if (isBinary) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text("Binary file — use Download to access this file.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (initialized) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(8.dp),
            ) {
                Column(Modifier.width(40.dp)) {
                    tfv.text.lines().forEachIndexed { i, _ ->
                        Text(
                            "${i + 1}",
                            style = monoStyle.copy(textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        )
                    }
                }
                BasicTextField(
                    value = tfv,
                    onValueChange = { typed ->
                        val new = keys.filterTyped(tfv, typed, clipboard, onUndo = ::undo, onRedo = ::redo)
                        if (new.text != tfv.text || new.selection != tfv.selection) tfv = new
                    },
                    modifier = Modifier.weight(1f).onFocusChanged { editing = it.isFocused },
                    textStyle = monoStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                )
            }
            if (editing) {
                ExtraKeysBar(
                    value = tfv,
                    onValueChange = { tfv = it },
                    modifiers = keys,
                    modifier = Modifier.imePadding(),
                    indentUnit = "    ",
                    onEsc = { focusManager.clearFocus() },
                    onUndo = ::undo,
                    onRedo = ::redo,
                )
            }
        }
    }

    conflict?.let { state ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Save conflict") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The workspace file changed since you opened it. Your edits and the remote version differ.")
                    if (conflictReview == ConflictReview.Showing) {
                        Text("Your version:", style = MaterialTheme.typography.labelMedium)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Text(
                                state.localText,
                                modifier = Modifier.padding(8.dp).fillMaxWidth().height(120.dp).verticalScroll(rememberScrollState()),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("Remote version:", style = MaterialTheme.typography.labelMedium)
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Text(
                                state.result.remoteText,
                                modifier = Modifier.padding(8.dp).fillMaxWidth().height(120.dp).verticalScroll(rememberScrollState()),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            val buf = repository.reloadBuffer(projectId, path)
                            tfv = TextFieldValue(buf.text)
                            lastPushed = buf.text
                            conflict = null
                        }
                    }) { Text("Reload remote") }
                    TextButton(onClick = {
                        conflictReview = if (conflictReview == ConflictReview.Showing) ConflictReview.None else ConflictReview.Showing
                    }) { Text(if (conflictReview == ConflictReview.Showing) "Hide differences" else "Review differences") }
                    TextButton(onClick = {
                        scope.launch {
                            val result = repository.saveCopy(projectId, path)
                            conflict = null
                            saveMessage = if (result.ok) result.message else "Error: ${result.message}"
                        }
                    }) { Text("Save a separate copy") }
                }
            },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text("Cancel") } },
        )
    }
}
