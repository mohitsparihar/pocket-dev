package dev.pocket.app.ui.changes

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.model.DiffBase
import dev.pocket.app.model.GitFile
import dev.pocket.app.model.GitStatus
import dev.pocket.app.ui.theme.Danger
import dev.pocket.app.ui.theme.Ok
import kotlinx.coroutines.launch

private data class DiffLineInfo(
    val text: String,
    val filePath: String?,
    val lineNumber: Int,
)

private fun parseDiff(diffText: String): List<DiffLineInfo> {
    var currentFile = ""
    var currentLine = 0
    return diffText.lines().map { line ->
        when {
            line.startsWith("+++ b/") -> {
                currentFile = line.removePrefix("+++ b/")
                DiffLineInfo(line, currentFile, 0)
            }
            line.startsWith("@@ ") -> {
                val m = Regex("""\+(\d+)""").find(line)
                currentLine = m?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                DiffLineInfo(line, null, currentLine)
            }
            line.startsWith("+") && !line.startsWith("+++") -> {
                val n = currentLine
                currentLine++
                DiffLineInfo(line, null, n)
            }
            line.startsWith("-") && !line.startsWith("---") -> DiffLineInfo(line, null, currentLine)
            line.startsWith("diff --git ") -> {
                val parts = line.split(" b/")
                val f = parts.lastOrNull() ?: ""
                DiffLineInfo(line, f, 0)
            }
            else -> {
                if (!line.startsWith("---") && !line.startsWith("+++")) currentLine++
                DiffLineInfo(line, null, currentLine)
            }
        }
    }
}

@Composable
fun ChangesScreen(
    repository: PocketRepository,
    projectId: String,
    onOpenFile: (String, Int) -> Unit,
) {
    val scope = rememberSafeScope()
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var selectedFile by remember { mutableStateOf<GitFile?>(null) }
    var diffBase by remember { mutableStateOf(DiffBase.Worktree) }
    var commitStr by remember { mutableStateOf("") }
    var diffText by remember { mutableStateOf("") }
    var loadingDiff by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        status = repository.gitStatus(projectId)
        val first = status?.files?.firstOrNull()
        selectedFile = first
    }

    LaunchedEffect(selectedFile, diffBase, commitStr) {
        val f = selectedFile ?: return@LaunchedEffect
        loadingDiff = true
        diffText = repository.gitDiff(projectId, f.path, diffBase, commitStr)
        loadingDiff = false
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Info, null, Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Uncommitted files may include both agent and manual edits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Base:", style = MaterialTheme.typography.labelMedium)
            listOf(DiffBase.Worktree to "Worktree", DiffBase.Index to "Index", DiffBase.Commit to "Commit").forEach { (base, label) ->
                FilterChip(
                    selected = diffBase == base,
                    onClick = { diffBase = base },
                    label = { Text(label) },
                )
            }
        }

        if (diffBase == DiffBase.Commit) {
            OutlinedTextField(
                value = commitStr,
                onValueChange = { commitStr = it },
                label = { Text("Commit hash or ref") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        val files = status?.files ?: emptyList()
        val branchLine = status?.let { s ->
            buildString {
                append("Branch: ${s.branch}")
                if (s.ahead > 0) append("  ↑${s.ahead}")
                if (s.behind > 0) append("  ↓${s.behind}")
            }
        }

        branchLine?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        if (files.isEmpty() && status != null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No changed files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(0.35f),
            ) {
                items(files, key = { it.path }) { file ->
                    val isSelected = file == selectedFile
                    val statusColor = when (file.status.lowercase().firstOrNull()) {
                        'm' -> MaterialTheme.colorScheme.primary
                        'a' -> Ok
                        'd' -> Danger
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
                            .clickable {
                                selectedFile = file
                                scope.launch {
                                    loadingDiff = true
                                    diffText = repository.gitDiff(projectId, file.path, diffBase, commitStr)
                                    loadingDiff = false
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            file.status.uppercase().take(1),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Text(file.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    HorizontalDivider()
                }
            }

            HorizontalDivider()

            val diffLines = remember(diffText) { parseDiff(diffText) }

            Column(
                modifier = Modifier.weight(0.65f).fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                if (selectedFile == null) {
                    Spacer(Modifier.height(32.dp))
                    Text("Select a file to view its diff.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                } else if (loadingDiff) {
                    Text("Loading diff…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                } else if (diffText.isBlank()) {
                    Text("No diff available.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                } else {
                    diffLines.forEach { info ->
                        val (bg, fg) = when {
                            info.text.startsWith("+") && !info.text.startsWith("+++") ->
                                Ok.copy(alpha = 0.15f) to Ok
                            info.text.startsWith("-") && !info.text.startsWith("---") ->
                                Danger.copy(alpha = 0.15f) to Danger
                            info.text.startsWith("@@") ->
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) to MaterialTheme.colorScheme.primary
                            info.text.startsWith("diff ") || info.text.startsWith("---") || info.text.startsWith("+++") ->
                                MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
                            else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
                        }
                        val isFileLine = info.filePath != null
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .then(
                                    if (isFileLine) Modifier.clickable { onOpenFile(info.filePath!!, info.lineNumber) }
                                    else Modifier
                                )
                                .padding(horizontal = 12.dp, vertical = 1.dp),
                        ) {
                            Text(
                                info.text,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = fg,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
