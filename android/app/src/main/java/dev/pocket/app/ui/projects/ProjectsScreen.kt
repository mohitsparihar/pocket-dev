package dev.pocket.app.ui.projects

import dev.pocket.app.ui.components.rememberSafeScope
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.model.ConnectionProfile
import dev.pocket.app.model.NewSession
import dev.pocket.app.model.Project
import dev.pocket.app.model.Provider
import dev.pocket.app.model.WorkspaceChoice
import dev.pocket.app.ui.components.StatusChip
import dev.pocket.app.ui.components.StatusTone
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(repository: PocketRepository, onOpenProject: (String) -> Unit) {
    val scope = rememberSafeScope()
    val projects by repository.projects().collectAsState(initial = emptyList())
    val connections by repository.connections().collectAsState(initial = emptyList())
    var showNewSheet by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        if (projects.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "No projects yet — tap + to add a project",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    val connection = connections.find { it.id == project.connectionId }
                    ProjectCard(project, connection, onClick = { onOpenProject(project.id) })
                }
            }
        }

        FloatingActionButton(
            onClick = { showNewSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "New project")
        }
    }

    if (showNewSheet) {
        NewProjectSheet(
            connections = connections,
            onDismiss = { showNewSheet = false },
            onCreate = { project ->
                scope.launch {
                    repository.upsertProject(project)
                    showNewSheet = false
                    onOpenProject(project.id)
                }
            },
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    connection: ConnectionProfile?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                if (project.previewRunning) {
                    StatusChip("Preview", StatusTone.Ok)
                }
            }
            if (connection != null) {
                Text(
                    connection.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (project.folder.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            project.folder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (project.branch.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.AccountTree,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            project.branch,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (project.activeChats > 0) {
                    Text(
                        "${project.activeChats} active ${if (project.activeChats == 1) "chat" else "chats"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProjectSheet(
    connections: List<ConnectionProfile>,
    onDismiss: () -> Unit,
    onCreate: (Project) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedConnection by remember { mutableStateOf(connections.firstOrNull()) }
    var folder by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("New Project", style = MaterialTheme.typography.titleLarge)

            if (connections.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "No connections — add one in the Connections tab first",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connection", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        connections.forEach { c ->
                            FilterChip(
                                selected = selectedConnection?.id == c.id,
                                onClick = { selectedConnection = c },
                                label = { Text(c.name) },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Project name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                label = { Text("Folder path on VPS (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. ~/projects/my-app") },
            )

            Button(
                onClick = {
                    val conn = selectedConnection ?: return@Button
                    if (name.isBlank()) return@Button
                    onCreate(
                        Project(
                            id = UUID.randomUUID().toString(),
                            connectionId = conn.id,
                            name = name.trim(),
                            folder = folder.trim(),
                            branch = "",
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = selectedConnection != null && name.isNotBlank(),
            ) {
                Text("Create Project")
            }
        }
    }
}

// ──────────────────────── Project detail ────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    repository: PocketRepository,
    projectId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenChanges: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenDeploy: () -> Unit,
) {
    val scope = rememberSafeScope()
    val project by repository.project(projectId).collectAsState(initial = null)

    var folder by remember { mutableStateOf("") }
    var devCommand by remember { mutableStateOf("") }
    var portsText by remember { mutableStateOf("") }
    var testCommand by remember { mutableStateOf("") }
    var saveMessage by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(projectId) {
        repository.setActiveProject(projectId)
    }

    LaunchedEffect(project) {
        if (!initialized && project != null) {
            folder = project!!.folder
            devCommand = project!!.devCommand
            portsText = project!!.ports.joinToString(", ")
            testCommand = project!!.testCommand
            initialized = true
        }
    }

    var showNewChatSheet by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                }
                Text(
                    project?.name ?: "Project",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (project == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Project not found — go back and try again",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Action grid ──
            Text("Actions", style = MaterialTheme.typography.titleSmall)

            val actions = listOf(
                Triple(Icons.Outlined.Chat, "New Chat") { showNewChatSheet = true },
                Triple(Icons.Outlined.Folder, "Files", onOpenFiles),
                Triple(Icons.Outlined.Code, "Editor", onOpenEditor),
                Triple(Icons.Outlined.CompareArrows, "Changes", onOpenChanges),
                Triple(Icons.Outlined.Terminal, "Terminal", onOpenTerminal),
                Triple(Icons.Outlined.Visibility, "Preview", onOpenPreview),
                Triple(Icons.Outlined.AccountTree, "Git", onOpenGit),
                Triple(Icons.Outlined.Launch, "Deploy", onOpenDeploy),
            )

            actions.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pair.forEach { (icon, label, action) ->
                        FilledTonalButton(
                            onClick = action,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(label)
                        }
                    }
                    if (pair.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            HorizontalDivider()

            // ── Setup form ──
            Text("Setup", style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                label = { Text("Folder path on VPS") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. ~/projects/my-app") },
            )

            OutlinedTextField(
                value = devCommand,
                onValueChange = { devCommand = it },
                label = { Text("Dev command") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. npm run dev") },
            )

            OutlinedTextField(
                value = portsText,
                onValueChange = { portsText = it },
                label = { Text("Expected ports (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. 3000, 8000") },
            )

            OutlinedTextField(
                value = testCommand,
                onValueChange = { testCommand = it },
                label = { Text("Test command (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("e.g. npm test") },
            )

            Button(
                onClick = {
                    val ports = portsText
                        .split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                    scope.launch {
                        repository.upsertProject(
                            project!!.copy(
                                folder = folder.trim(),
                                devCommand = devCommand.trim(),
                                ports = ports,
                                testCommand = testCommand.trim(),
                            )
                        )
                        saveMessage = "Saved"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Save project settings")
            }

            if (saveMessage.isNotEmpty()) {
                Text(
                    saveMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showNewChatSheet) {
        val p = project ?: return
        NewChatForProjectSheet(
            project = p,
            onDismiss = { showNewChatSheet = false },
            onCreate = { draft ->
                scope.launch {
                    val session = repository.createSession(draft)
                    showNewChatSheet = false
                    onOpenChat(session.id)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatForProjectSheet(
    project: Project,
    onDismiss: () -> Unit,
    onCreate: (NewSession) -> Unit,
) {
    var provider by remember { mutableStateOf(Provider.Claude) }
    var title by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var workspace by remember { mutableStateOf(WorkspaceChoice.Worktree) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("New Chat in ${project.name}", style = MaterialTheme.typography.titleLarge)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Provider", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Provider.entries.forEach { p ->
                        FilterChip(
                            selected = provider == p,
                            onClick = { provider = p },
                            label = { Text(p.name) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (provider != Provider.Terminal) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Workspace", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = workspace == WorkspaceChoice.Worktree,
                        onClick = { workspace = WorkspaceChoice.Worktree },
                        label = { Text("Separate worktree") },
                    )
                    FilterChip(
                        selected = workspace == WorkspaceChoice.Shared,
                        onClick = { workspace = WorkspaceChoice.Shared },
                        label = { Text("Shared folder") },
                    )
                }
            }

            Button(
                onClick = {
                    if (title.isBlank()) return@Button
                    onCreate(
                        NewSession(
                            projectId = project.id,
                            provider = provider,
                            title = title.trim(),
                            model = model.trim(),
                            workspace = workspace,
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = title.isNotBlank(),
            ) {
                Text("Start Chat")
            }
        }
    }
}
