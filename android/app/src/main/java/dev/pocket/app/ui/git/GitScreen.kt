package dev.pocket.app.ui.git

import dev.pocket.app.ui.components.rememberSafeScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.model.BranchInfo
import dev.pocket.app.model.GitFile
import dev.pocket.app.model.GitOperationResult
import dev.pocket.app.model.GitStatus
import dev.pocket.app.ui.components.StatusChip
import dev.pocket.app.ui.components.StatusTone
import dev.pocket.app.ui.theme.Danger
import dev.pocket.app.ui.theme.Ok
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun GitScreen(repository: PocketRepository, projectId: String) {
    val scope = rememberSafeScope()
    var status by remember { mutableStateOf<GitStatus?>(null) }
    var branches by remember { mutableStateOf<List<BranchInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var actionLoading by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    var commitMessage by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<GitOperationResult?>(null) }
    var showBranches by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            status = repository.gitStatus(projectId)
            branches = repository.branches(projectId)
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                val s = status
                if (s != null) {
                    Text("Branch: ${s.branch}", style = MaterialTheme.typography.titleSmall)
                    if (s.ahead > 0 || s.behind > 0) {
                        Text("↑${s.ahead} ahead  ↓${s.behind} behind", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("Git", style = MaterialTheme.typography.titleSmall)
                }
            }
            IconButton(onClick = { showBranches = !showBranches }) {
                Icon(
                    if (showBranches) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    "Branches",
                )
            }
        }

        if (showBranches) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.AccountTree, null, Modifier.size(16.dp))
                        Text("Branches", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    branches.forEach { branch ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (branch.current) {
                                Icon(Icons.Outlined.Check, "Current", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Spacer(Modifier.size(16.dp))
                            }
                            Text(branch.name, style = MaterialTheme.typography.bodySmall, color = if (branch.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            HorizontalDivider()
        }

        result?.let { r ->
            Surface(
                color = if (r.ok) Ok.copy(alpha = 0.15f) else Danger.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusChip(if (r.ok) "OK" else "Error", if (r.ok) StatusTone.Ok else StatusTone.Bad)
                    Text(r.message, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val files = status?.files ?: emptyList()
            if (files.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No changed files.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    "Changed files",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(files, key = { it.path }) { file ->
                        GitFileRow(
                            file = file,
                            checked = file.path in selected,
                            onToggle = {
                                if (file.path in selected) selected.remove(file.path)
                                else selected.add(file.path)
                            },
                        )
                    }
                }
            }
        }

        HorizontalDivider()

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = commitMessage,
                onValueChange = { commitMessage = it },
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )

            if (actionLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val key = UUID.randomUUID().toString()
                        scope.launch {
                            actionLoading = true
                            result = repository.commit(projectId, selected.toList(), commitMessage, key)
                            actionLoading = false
                            if (result?.ok == true) {
                                selected.clear()
                                commitMessage = ""
                                status = repository.gitStatus(projectId)
                            }
                        }
                    },
                    enabled = selected.isNotEmpty() && commitMessage.isNotBlank() && !actionLoading,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Text("Commit ${if (selected.isEmpty()) "" else "(${selected.size})"}")
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val key = UUID.randomUUID().toString()
                        scope.launch {
                            actionLoading = true
                            result = repository.pull(projectId, key)
                            actionLoading = false
                            status = repository.gitStatus(projectId)
                        }
                    },
                    enabled = !actionLoading,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Pull") }
                OutlinedButton(
                    onClick = {
                        val key = UUID.randomUUID().toString()
                        scope.launch {
                            actionLoading = true
                            result = repository.push(projectId, key)
                            actionLoading = false
                        }
                    },
                    enabled = !actionLoading,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Push") }
            }
        }
    }
}

@Composable
private fun GitFileRow(file: GitFile, checked: Boolean, onToggle: () -> Unit) {
    val statusColor = when (file.status.lowercase().firstOrNull()) {
        'm' -> MaterialTheme.colorScheme.primary
        'a' -> Ok
        'd' -> Danger
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, modifier = Modifier.size(48.dp))
        Text(
            file.status.uppercase().take(1),
            style = MaterialTheme.typography.labelLarge,
            color = statusColor,
        )
        Column(Modifier.weight(1f)) {
            Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
            Text(file.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    HorizontalDivider(Modifier.padding(start = 60.dp))
}
