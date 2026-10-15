package dev.pocket.app.ui.chats

import dev.pocket.app.ui.components.rememberSafeScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.pocket.app.ui.components.ExtraKeysBar
import dev.pocket.app.ui.components.filterTyped
import dev.pocket.app.ui.components.rememberKeyModifiers
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.model.AgentCommand
import dev.pocket.app.model.AgentSession
import dev.pocket.app.model.ApprovalRequest
import dev.pocket.app.model.ChangedFile
import dev.pocket.app.model.FileEntry
import dev.pocket.app.model.LinkState
import dev.pocket.app.model.NewSession
import dev.pocket.app.model.PendingPrompt
import dev.pocket.app.model.PendingState
import dev.pocket.app.model.Project
import dev.pocket.app.model.Provider
import dev.pocket.app.model.SessionState
import dev.pocket.app.model.SessionStatus
import dev.pocket.app.model.WorkspaceChoice
import dev.pocket.app.ui.theme.Ok
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private val Amber = Color(0xFFFFB74D)

// ──────────────────────── Chat list ────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(repository: PocketRepository, onOpenChat: (String) -> Unit) {
    val scope = rememberSafeScope()
    val sessions by repository.sessions().collectAsState(initial = emptyList())
    val filter by repository.sessionFilter().collectAsState(initial = null)
    val projects by repository.projects().collectAsState(initial = emptyList())
    var showNewSheet by remember { mutableStateOf(false) }
    val now by produceNow()

    val filterOptions = listOf<Pair<SessionState?, String>>(
        null to "All",
        SessionState.Running to "Running",
        SessionState.AwaitingInput to "Your turn",
        SessionState.Completed to "Completed",
    )

    val filtered = sessions.filter { !it.archived }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filterOptions.forEach { (state, label) ->
                    FilterChip(
                        selected = filter == state,
                        onClick = { scope.launch { repository.setSessionFilter(state) } },
                        label = { Text(label) },
                    )
                }
            }

            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Outlined.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (filter == null) "No chats yet" else "No ${filterOptions.first { it.first == filter }.second.lowercase()} chats",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (filter == null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Start one with +. It keeps running on the VM even when your phone is offline.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(filtered, key = { it.id }) { session ->
                        val project = projects.find { it.id == session.projectId }
                        ChatRow(session, project, now, onClick = { onOpenChat(session.id) })
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 68.dp),
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showNewSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "New chat")
        }
    }

    if (showNewSheet) {
        var error by remember { mutableStateOf<String?>(null) }
        NewChatSheet(
            projects = projects,
            error = error,
            onDismiss = { showNewSheet = false },
            onCreate = { draft ->
                scope.launch {
                    runCatching { repository.createSession(draft) }
                        .onSuccess { session ->
                            showNewSheet = false
                            onOpenChat(session.id)
                        }
                        .onFailure { error = it.message ?: "Could not start the chat" }
                }
            },
        )
    }
}

@Composable
private fun produceNow(): androidx.compose.runtime.State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now.longValue = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun ChatRow(session: AgentSession, project: Project?, now: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ProviderAvatar(session.provider, session.state)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title.ifBlank { "New chat" },
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    relativeTime(session.updatedAt, now),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    session.state.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = session.state.color(),
                )
                val context = listOfNotNull(project?.name, session.branch.ifBlank { null }).joinToString(" · ")
                if (context.isNotEmpty()) {
                    Text(
                        "· $context",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (session.latestLine.isNotEmpty()) {
                Text(
                    session.latestLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProviderAvatar(provider: Provider, state: SessionState) {
    Box {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    when (provider) {
                        Provider.Claude -> "Cl"
                        Provider.Cursor -> "Cu"
                        Provider.Codex -> "Cx"
                        Provider.Terminal -> ">_"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state != SessionState.Stopped && state != SessionState.Queued) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .background(MaterialTheme.colorScheme.background, CircleShape)
                    .padding(2.dp)
                    .background(state.color(), CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(
    projects: List<Project>,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (NewSession) -> Unit,
) {
    var selectedProject by remember { mutableStateOf(projects.firstOrNull()) }
    var provider by remember { mutableStateOf(Provider.Claude) }
    var title by remember { mutableStateOf("") }
    var model by remember(provider) { mutableStateOf("") }
    var workspace by remember { mutableStateOf(WorkspaceChoice.Worktree) }
    var creating by remember(error) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("New chat", style = MaterialTheme.typography.titleLarge)

            if (projects.isEmpty()) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        "No projects yet. Add one in the Projects tab first.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                SheetSection("Project") {
                    projects.forEach { p ->
                        FilterChip(selected = selectedProject?.id == p.id, onClick = { selectedProject = p }, label = { Text(p.name) })
                    }
                }
            }

            SheetSection("Agent") {
                Provider.entries.forEach { p ->
                    FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(p.name) })
                }
            }

            if (provider != Provider.Terminal) {
                SheetSection("Model") {
                    FilterChip(selected = model.isBlank(), onClick = { model = "" }, label = { Text("Default") })
                    modelChoices(provider).forEach { choice ->
                        FilterChip(selected = model == choice.id, onClick = { model = choice.id }, label = { Text(choice.label) })
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetSection("Workspace") {
                    FilterChip(
                        selected = workspace == WorkspaceChoice.Worktree,
                        onClick = { workspace = WorkspaceChoice.Worktree },
                        label = { Text("Own branch") },
                    )
                    FilterChip(
                        selected = workspace == WorkspaceChoice.Shared,
                        onClick = { workspace = WorkspaceChoice.Shared },
                        label = { Text("Project folder") },
                    )
                }
                Text(
                    if (workspace == WorkspaceChoice.Worktree)
                        "Works in a separate git worktree, so parallel chats don't overwrite each other."
                    else
                        "Edits the project folder directly, alongside any other chat using it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (optional)") },
                supportingText = { Text("Left empty, the first message names the chat.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    val proj = selectedProject ?: return@Button
                    creating = true
                    onCreate(
                        NewSession(
                            projectId = proj.id,
                            provider = provider,
                            title = title.trim(),
                            model = model,
                            workspace = workspace,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = selectedProject != null && !creating,
            ) {
                Text(if (creating) "Starting…" else "Start chat")
            }
        }
    }
}

@Composable
private fun SheetSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

// ──────────────────────── Chat detail ────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    repository: PocketRepository,
    sessionId: String,
    onBack: () -> Unit,
    onOpenTerminal: (String) -> Unit,
    onOpenChanges: (String) -> Unit,
    onOpenFile: (String, String) -> Unit,
) {
    val scope = rememberSafeScope()
    val session by repository.session(sessionId).collectAsState(initial = null)
    val events by repository.events(sessionId).collectAsState(initial = emptyList())
    val approvals by repository.approvals(sessionId).collectAsState(initial = emptyList())
    val changedFiles by repository.changedFiles(sessionId).collectAsState(initial = emptyList())
    val projects by repository.projects().collectAsState(initial = emptyList())
    val streaming by repository.streamingText(sessionId).collectAsState(initial = "")
    val pending by repository.pendingPrompts(sessionId).collectAsState(initial = emptyList())
    val link by repository.linkState().collectAsState(initial = LinkState.Live)

    // Keeps the caret and selection so the extra-keys row can edit around them.
    var composer by remember { mutableStateOf(TextFieldValue("")) }
    fun setComposer(text: String) {
        composer = TextFieldValue(text, TextRange(text.length))
    }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var contextPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var banner by remember { mutableStateOf<String?>(null) }
    var modelId by remember(session?.id, session?.model) { mutableStateOf(session?.model.orEmpty()) }
    var effortId by remember(session?.id, session?.effort) { mutableStateOf(session?.effort.orEmpty()) }
    var modeId by remember(session?.id, session?.mode) { mutableStateOf(session?.mode?.ifBlank { "agent" } ?: "agent") }

    val provider = session?.provider ?: Provider.Claude
    val working = session?.state == SessionState.Running || session?.state == SessionState.Starting
    val blocks = remember(events) { transcriptBlocks(events) }
    val openApprovals = approvals.filter { !it.resolved }

    // The agent's own commands (built-ins, skills, plugins, custom command
    // files) come from the VM. Claude reports its list after its first run,
    // so refresh when the chat changes state.
    var agentCommands by remember(sessionId) { mutableStateOf<List<AgentCommand>?>(null) }
    LaunchedEffect(sessionId, link == LinkState.Live, session?.state) {
        if (link == LinkState.Live) repository.slashCommands(sessionId)?.let { agentCommands = it }
    }
    val commands = remember(provider, agentCommands) { commandsFor(provider, agentCommands) }
    val suggestions = matchingCommands(commands, composer.text)

    // Full history is fetched when the chat opens and again after a reconnect.
    LaunchedEffect(sessionId, link == LinkState.Live) {
        if (link == LinkState.Live) runCatching { repository.loadHistory(sessionId) }
    }

    fun act(failure: String, block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { banner = it.message ?: failure }
        }
    }

    // Follow new output only while the reader is at the bottom.
    val listState = rememberLazyListState()
    var stick by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        stick = if (dragged) false else !listState.canScrollForward
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { if (!it) stick = true }
    }
    suspend fun scrollToBottom() {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last < 0) return
        listState.scrollToItem(last)
        listState.scrollBy(100_000f)
    }
    LaunchedEffect(blocks.size, pending.size, streaming.length, openApprovals.size, working) {
        if (stick) scrollToBottom()
    }

    fun applyControls(model: String, effort: String, mode: String) {
        modelId = model
        effortId = effort
        modeId = mode
        act("Could not update the chat") { repository.setSessionControls(sessionId, model, effort, mode) }
    }

    fun send(body: String, restore: String) {
        stick = true
        scope.launch {
            // Network trouble keeps the prompt in the outbox; anything else is a real refusal.
            runCatching { repository.prompt(sessionId, body, UUID.randomUUID().toString()) }
                .onFailure {
                    banner = it.message ?: "Could not send"
                    if (composer.text.isBlank()) setComposer(restore)
                }
        }
    }

    fun runAppCommand(command: SlashCommand, arg: String) {
        setComposer("")
        when (command.kind) {
            SlashKind.Model -> if (arg.isBlank()) picker = "model" else {
                // Any model id the CLI accepts works, not only the ones in the picker.
                val id = resolveChoice(modelChoices(provider), arg) ?: arg.takeIf { Regex("^[A-Za-z0-9._:-]+$").matches(it) }
                if (id == null) banner = "Unknown model" else applyControls(id, effortId, modeId)
            }
            SlashKind.Effort -> if (arg.isBlank()) picker = "effort" else {
                val id = resolveChoice(effortChoices(provider), arg)
                if (id == null) banner = "Unknown effort. Try low, medium, high, xhigh or max." else applyControls(modelId, id, modeId)
            }
            SlashKind.Mode -> if (arg.isBlank()) picker = "mode" else {
                val id = resolveChoice(modeChoices(provider), arg)
                if (id == null) banner = "Unknown mode. Try agent, auto, plan or ask." else applyControls(modelId, effortId, id)
            }
            SlashKind.Stop -> act("Could not stop the agent") { repository.interrupt(sessionId) }
            SlashKind.Rename -> if (arg.isBlank()) {
                renameText = session?.title.orEmpty()
                showRenameDialog = true
            } else {
                act("Could not rename") { repository.renameSession(sessionId, arg) }
            }
            SlashKind.Changes -> onOpenChanges(session?.projectId.orEmpty())
            SlashKind.Terminal -> onOpenTerminal(session?.projectId.orEmpty())
            SlashKind.Help -> showHelp = true
            SlashKind.Status -> showStatus = true
            SlashKind.Agent -> Unit
        }
    }

    fun sendComposer() {
        val typed = composer.text.trim()
        banner = null
        val head = slashHead(typed)
        val app = head?.let { name -> commands.find { it.kind != SlashKind.Agent && it.name.equals(name, ignoreCase = true) } }
        when {
            app != null -> runAppCommand(app, typed.substringAfter(' ', "").trim())
            head.equals("commands", ignoreCase = true) -> runAppCommand(SlashCommand("help", "", SlashKind.Help), "")
            // Terminal-only commands are explained instead of sent (the agent would only reply
            // that they aren't available). The text stays so it can be edited.
            unavailableCommandReason(provider, typed, if (agentCommands != null) commands else null) != null -> {
                banner = unavailableCommandReason(provider, typed, if (agentCommands != null) commands else null)
            }
            typed.isNotEmpty() || contextPaths.isNotEmpty() -> {
                val slash = typed.startsWith("/")
                val body = messageWithContext(
                    if (slash) typed else typed.ifBlank { "Look at these files." },
                    if (slash) emptyList() else contextPaths,
                )
                setComposer("")
                if (!slash) contextPaths = emptyList()
                send(body, typed)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ChatHeader(
            session = session,
            onBack = onBack,
            onStop = { act("Could not stop the agent") { repository.interrupt(sessionId) } },
            onResume = { act("Could not resume the chat") { repository.resume(sessionId) } },
            menuOpen = menuOpen,
            onMenu = { menuOpen = it },
            onRename = {
                renameText = session?.title.orEmpty()
                showRenameDialog = true
            },
            onArchive = {
                scope.launch {
                    runCatching { repository.archiveSession(sessionId) }
                        .onSuccess { onBack() }
                        .onFailure { banner = it.message ?: "Could not archive" }
                }
            },
            onTerminal = { onOpenTerminal(session?.projectId.orEmpty()) },
            onChanges = { onOpenChanges(session?.projectId.orEmpty()) },
        )

        AnimatedVisibility(visible = link == LinkState.Reconnecting, enter = fadeIn(), exit = fadeOut()) {
            Surface(color = Amber.copy(alpha = 0.16f), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp), tint = Amber)
                    Text(
                        "Reconnecting. The agent keeps working on the VM, and anything you send goes out when the phone is back online.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (blocks.isEmpty() && pending.isEmpty() && openApprovals.isEmpty() && !working) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 56.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("What should we work on?", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Type / for commands. You can close the app at any time; the work continues on the VM.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                items(blocks, key = { "${it::class.simpleName}-${it.seq}" }) { block ->
                    when (block) {
                        is ChatBlock.You -> YouMessage(block.text)
                        is ChatBlock.Agent -> AgentMessage(block.text)
                        is ChatBlock.Thinking -> ThinkingBlock(block.text)
                        is ChatBlock.Tools -> ToolsGroup(block.calls, live = working && block == blocks.lastOrNull())
                        is ChatBlock.Note -> NoteLine(block.text)
                        is ChatBlock.Error -> ErrorBlock(block.text)
                    }
                }

                items(pending, key = { "pending-${it.key}" }) { prompt ->
                    PendingMessage(
                        prompt,
                        onCancel = { scope.launch { repository.discardPending(prompt.key) } },
                        onResend = { act("Could not resend") { repository.resendPending(prompt.key) } },
                    )
                }

                if (streaming.isNotBlank()) {
                    item(key = "streaming") { AgentMessage(streaming + " ▍") }
                } else if (working) {
                    item(key = "working") { WorkingLine() }
                }

                items(openApprovals, key = { "appr-${it.id}" }) { approval ->
                    ApprovalCard(
                        approval = approval,
                        onAllow = { act("Could not answer") { repository.respond(approval.id, true) } },
                        onDeny = { act("Could not answer") { repository.respond(approval.id, false) } },
                    )
                }

                if (changedFiles.isNotEmpty()) {
                    item(key = "changed-title") {
                        Text(
                            "Changed files",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(changedFiles, key = { "cf-${it.path}" }) { f ->
                        ChangedFileRow(f, onClick = { onOpenFile(session?.projectId.orEmpty(), f.path) })
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = !stick && listState.canScrollForward,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        stick = true
                        scope.launch { scrollToBottom() }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "Jump to latest")
                }
            }
        }

        Composer(
            provider = provider,
            value = composer,
            onValueChange = { composer = it },
            working = working,
            banner = banner,
            suggestions = suggestions,
            contextPaths = contextPaths,
            modeLabel = choiceLabel(modeChoices(provider), modeId, "Agent"),
            modelLabel = choiceLabel(modelChoices(provider), modelId, "Model"),
            effortLabel = choiceLabel(effortChoices(provider), effortId, "Effort"),
            onPick = { picker = it },
            onAddContext = { showContext = true },
            onRemoveContext = { path -> contextPaths = contextPaths - path },
            onSuggestion = { command ->
                // Agent commands go into the composer so arguments can be added before sending.
                if (command.kind == SlashKind.Agent) setComposer("/${command.name} ")
                else runAppCommand(command, "")
            },
            onSend = { sendComposer() },
            onStop = { act("Could not stop the agent") { repository.interrupt(sessionId) } },
        )
    }

    picker?.let { which ->
        val (title, choices, current) = when (which) {
            "model" -> Triple("Model", listOf(AgentChoice("", "Default")) + modelChoices(provider), modelId)
            "effort" -> Triple("Effort", listOf(AgentChoice("", "Default")) + effortChoices(provider), effortId)
            else -> Triple("Mode", modeChoices(provider), modeId)
        }
        ModalBottomSheet(onDismissRequest = { picker = null }) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            choices.forEach { choice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            picker = null
                            when (which) {
                                "model" -> applyControls(choice.id, effortId, modeId)
                                "effort" -> applyControls(modelId, choice.id, modeId)
                                else -> applyControls(modelId, effortId, choice.id)
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                        modeHint(choice.id).takeIf { which == "mode" }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (choice.id.equals(current, ignoreCase = true)) {
                        Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showStatus) {
        StatusSheet(repository, sessionId, onDismiss = { showStatus = false })
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Commands") },
            text = {
                val groups = listOf(
                    "In the app" to commands.filter { it.kind != SlashKind.Agent },
                    "${provider.name} built-in" to commands.filter { it.kind == SlashKind.Agent && it.source == "built-in" },
                    "Skills, plugins and custom" to commands.filter { it.kind == SlashKind.Agent && it.source != "built-in" },
                ).filter { it.second.isNotEmpty() }
                LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    groups.forEach { (title, list) ->
                        item(key = "h-$title") {
                            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                        }
                        items(list, key = { "c-${it.kind}-${it.name}" }) { command ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showHelp = false
                                        if (command.kind == SlashKind.Agent) setComposer("/${command.name} ") else runAppCommand(command, "")
                                    },
                            ) {
                                Text(
                                    "/${command.name}" + if (command.argumentHint.isNotBlank()) " ${command.argumentHint}" else "",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                )
                                if (command.hint.isNotBlank()) {
                                    Text(command.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (provider != Provider.Claude) {
                        item(key = "note") {
                            Text(
                                "${provider.name} runs headless on the VM, where its interactive-only commands don't exist. The app covers those, and your custom command files are expanded for you.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } },
        )
    }

    if (showContext) {
        ContextSheet(
            repository = repository,
            projectId = session?.projectId.orEmpty(),
            root = session?.workspacePath?.ifBlank { projects.find { it.id == session?.projectId }?.folder }.orEmpty(),
            selected = contextPaths,
            onToggle = { path ->
                contextPaths = if (path in contextPaths) contextPaths - path else contextPaths + path
            },
            onDismiss = { showContext = false },
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        act("Could not rename") { repository.renameSession(sessionId, renameText.trim()) }
                        showRenameDialog = false
                    },
                    enabled = renameText.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ChatHeader(
    session: AgentSession?,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onTerminal: () -> Unit,
    onChanges: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    session?.title?.ifBlank { "New chat" } ?: "Chat",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                session?.let { s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(s.state.color(), CircleShape))
                        Text(
                            listOf(s.state.label(), s.provider.name, s.model.ifBlank { null }).filterNotNull().joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (session?.state == SessionState.Stopped || session?.state == SessionState.Failed) {
                IconButton(onClick = onResume) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "Resume")
                }
            }
            Box {
                IconButton(onClick = { onMenu(true) }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
                    if (session?.state == SessionState.Running || session?.state == SessionState.Starting) {
                        DropdownMenuItem(
                            text = { Text("Stop agent") },
                            leadingIcon = { Icon(Icons.Outlined.Stop, null) },
                            onClick = { onMenu(false); onStop() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { onMenu(false); onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("View changes") },
                        leadingIcon = { Icon(Icons.Outlined.CompareArrows, null) },
                        onClick = { onMenu(false); onChanges() },
                    )
                    DropdownMenuItem(
                        text = { Text("Open terminal") },
                        leadingIcon = { Icon(Icons.Outlined.Terminal, null) },
                        onClick = { onMenu(false); onTerminal() },
                    )
                    DropdownMenuItem(
                        text = { Text("Archive") },
                        leadingIcon = { Icon(Icons.Outlined.Archive, null) },
                        onClick = { onMenu(false); onArchive() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    provider: Provider,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    working: Boolean,
    banner: String?,
    suggestions: List<SlashCommand>,
    contextPaths: List<String>,
    modeLabel: String,
    modelLabel: String,
    effortLabel: String,
    onPick: (String) -> Unit,
    onAddContext: () -> Unit,
    onRemoveContext: (String) -> Unit,
    onSuggestion: (SlashCommand) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val agent = provider != Provider.Terminal
    val keys = rememberKeyModifiers()
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().imePadding()) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (suggestions.isNotEmpty()) {
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                LazyColumn(Modifier.heightIn(max = 300.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(suggestions, key = { "${it.kind}-${it.name}" }) { command ->
                        CommandRow(command, onClick = { onSuggestion(command) })
                    }
                }
            }
        }
        banner?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp))
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (contextPaths.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        contextPaths.forEach { path ->
                            FilterChip(
                                selected = true,
                                onClick = { onRemoveContext(path) },
                                label = { Text(path.substringAfterLast('/')) },
                                leadingIcon = { Icon(Icons.Outlined.Description, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
                TextField(
                    value = value,
                    onValueChange = { onValueChange(keys.filterTyped(value, it, clipboard)) },
                    placeholder = { Text(if (agent) "Ask anything, or / for commands" else "Message…") },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                    textStyle = ChatTextStyle,
                    maxLines = 6,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (agent) {
                        IconButton(onClick = onAddContext) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = "Add files as context")
                        }
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ControlChip(modeLabel) { onPick("mode") }
                            ControlChip(modelLabel) { onPick("model") }
                            ControlChip(effortLabel) { onPick("effort") }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.width(6.dp))
                    val canSend = value.text.isNotBlank() || contextPaths.isNotEmpty()
                    if (working && !canSend) {
                        FilledIconButton(
                            onClick = onStop,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = "Stop the agent")
                        }
                    } else {
                        FilledIconButton(onClick = onSend, enabled = canSend) {
                            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
    if (focused) {
        ExtraKeysBar(
            value = value,
            onValueChange = onValueChange,
            modifiers = keys,
            // Like Esc in the agents' own terminals: stop the agent, otherwise put the keyboard away.
            onEsc = { if (working) onStop() else focusManager.clearFocus() },
            markdownKeys = agent,
        )
    }
    }
}

@Composable
private fun CommandRow(command: SlashCommand, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "/${command.name}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (command.argumentHint.isNotBlank()) {
                    Text(
                        " ${command.argumentHint}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (command.hint.isNotBlank()) {
                Text(
                    command.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (command.source != "built-in") {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                Text(
                    command.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ControlChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun YouMessage(text: String, footer: (@Composable () -> Unit)? = null, dim: Boolean = false) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (dim) 0.12f else 0.2f),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = ChatTextStyle,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dim) 0.7f else 1f),
                )
            }
        }
        footer?.invoke()
    }
}

@Composable
private fun PendingMessage(prompt: PendingPrompt, onCancel: () -> Unit, onResend: () -> Unit) {
    YouMessage(
        prompt.text,
        dim = prompt.state != PendingState.Sent,
        footer = {
            val (label, cancellable) = when (prompt.state) {
                PendingState.Sending -> "Sending…" to false
                PendingState.Waiting -> "Waiting for connection, sends automatically" to true
                PendingState.Queued -> "Queued, sends when the agent finishes" to true
                PendingState.Sent -> "Sent" to false
                PendingState.Failed -> "Not delivered" to true
            }
            val failed = prompt.state == PendingState.Failed
            Row(
                Modifier.padding(top = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule,
                    null,
                    Modifier.size(14.dp),
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (failed) {
                    Text(
                        "Resend",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onResend).padding(4.dp),
                    )
                }
                if (cancellable) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onCancel).padding(4.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun AgentMessage(text: String) {
    SelectionContainer {
        MarkdownContent(text, Modifier.fillMaxWidth())
    }
}

@Composable
private fun WorkingLine() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text("Working…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThinkingBlock(text: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Lightbulb, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (open) "Thinking" else text.lineSequence().first().take(80),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolsGroup(calls: List<String>, live: Boolean) {
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (calls.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open = !open }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Build, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${calls.size} steps",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val shown = if (open || calls.size == 1) calls else calls.takeLast(1)
            shown.forEachIndexed { index, call ->
                ToolRow(call, running = live && index == shown.lastIndex)
            }
        }
    }
}

@Composable
private fun ToolRow(call: String, running: Boolean) {
    val (name, detail) = toolParts(call)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (running) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(toolIcon(name), null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(name, style = MaterialTheme.typography.labelLarge)
        if (detail.isNotEmpty()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun toolIcon(name: String): ImageVector = when (name.lowercase()) {
    "read", "view", "notebookread" -> Icons.Outlined.Description
    "edit", "write", "multiedit", "notebookedit", "apply_patch" -> Icons.Outlined.Edit
    "bash", "shell", "command", "exec" -> Icons.Outlined.Terminal
    "grep", "glob", "search", "ls" -> Icons.Outlined.Search
    "webfetch", "websearch", "fetch" -> Icons.Outlined.Language
    else -> Icons.Outlined.Build
}

@Composable
private fun NoteLine(text: String) {
    val done = text.startsWith("Done")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = Ok)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextSheet(
    repository: PocketRepository,
    projectId: String,
    root: String,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember(root) { mutableStateOf(root.ifBlank { "." }) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(path, projectId) {
        if (projectId.isBlank()) {
            error = "This chat is not attached to a project"
            entries = emptyList()
            return@LaunchedEffect
        }
        loading = true
        runCatching { repository.listDir(projectId, path) }
            .onSuccess { found ->
                entries = found.sortedWith(compareBy<FileEntry>({ !it.directory }, { it.name.lowercase() }))
                error = null
            }
            .onFailure { error = it.message ?: "Could not list files" }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Add files as context",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Text(
            path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (path != root && path != "." && path.isNotBlank()) {
            TextButton(onClick = { path = parentPath(path, root) }) { Text("Up") }
        }
        if (loading) {
            Text("Loading…", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(entries, key = { it.path.ifBlank { it.name } }) { entry ->
                val added = entry.path in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.directory) path = entry.path else onToggle(entry.path)
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (entry.directory) "${entry.name}/" else entry.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!entry.directory && added) {
                        Icon(Icons.Outlined.Check, contentDescription = "Added", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        Spacer(Modifier.height(12.dp))
    }
}

private fun parentPath(path: String, root: String): String {
    val parent = path.trimEnd('/').substringBeforeLast('/')
    if (parent.isBlank()) return root.ifBlank { path }
    if (root.isNotBlank() && parent.length < root.length) return root
    return parent
}

@Composable
private fun ApprovalCard(
    approval: ApprovalRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Amber.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, Amber.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, contentDescription = null, tint = Amber, modifier = Modifier.size(18.dp))
                Text("Needs your approval", style = MaterialTheme.typography.labelLarge)
            }
            Text(approval.action, style = ChatTextStyle)
            if (approval.detail.isNotEmpty()) {
                CodeBlock("", approval.detail)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAllow, modifier = Modifier.height(44.dp)) { Text("Allow") }
                OutlinedButton(onClick = onDeny, modifier = Modifier.height(44.dp)) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun ChangedFileRow(file: ChangedFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = file.status.take(1).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = when (file.status.take(1).uppercase()) {
                "A" -> Ok
                "D" -> MaterialTheme.colorScheme.error
                else -> Amber
            },
            modifier = Modifier.width(16.dp),
        )
        Text(
            file.path,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (file.additions > 0 || file.deletions > 0) {
            Text(
                "+${file.additions} −${file.deletions}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

private fun modeHint(id: String): String = when (id) {
    "agent" -> "Edits files as it works"
    "auto" -> "Works through steps without stopping for approval"
    "plan" -> "Proposes a plan and changes nothing"
    "ask" -> "Answers questions with read-only access"
    else -> ""
}

fun relativeTime(at: Long, now: Long): String {
    if (at <= 0L) return ""
    val seconds = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        else -> "${seconds / 86_400}d"
    }
}

private fun SessionState.label(): String = when (this) {
    SessionState.Queued -> "Queued"
    SessionState.Starting -> "Starting"
    SessionState.Running -> "Working"
    SessionState.AwaitingInput -> "Your turn"
    SessionState.Completed -> "Completed"
    SessionState.Failed -> "Failed"
    SessionState.Stopped -> "Stopped"
}

@Composable
private fun SessionState.color(): Color = when (this) {
    SessionState.Running, SessionState.Starting -> MaterialTheme.colorScheme.primary
    SessionState.AwaitingInput -> Amber
    SessionState.Completed -> Ok
    SessionState.Failed -> MaterialTheme.colorScheme.error
    SessionState.Stopped, SessionState.Queued -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusSheet(repository: PocketRepository, sessionId: String, onDismiss: () -> Unit) {
    var status by remember { mutableStateOf<SessionStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) {
        runCatching { repository.sessionStatus(sessionId) }
            .onSuccess { if (it == null) error = "Connect to the VM to see this chat's status." else status = it }
            .onFailure { error = it.message ?: "Could not load the status" }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 620.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text("Status", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            val s = status
            when {
                error != null -> item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                s == null -> item { WorkingLineText("Loading…") }
                else -> {
                    item { StatusSection("Chat") }
                    item { StatusRow("Agent", s.provider.replaceFirstChar { it.uppercase() } + if (s.version.isNotBlank()) " ${s.version}" else "") }
                    item { StatusRow("Model", s.model.ifBlank { "Default" }) }
                    item { StatusRow("Mode", s.mode.replaceFirstChar { it.uppercase() } + if (s.permissionMode.isNotBlank()) " (${s.permissionMode})" else "") }
                    if (s.effort.isNotBlank()) item { StatusRow("Effort", s.effort) }
                    item { StatusRow("State", s.state.replace('_', ' ') + if (s.running) ", process running" else "") }
                    item { StatusRow("Folder", s.workspacePath, mono = true) }
                    if (s.branch.isNotBlank()) item { StatusRow("Branch", s.branch, mono = true) }
                    if (s.providerSessionId.isNotBlank()) item { StatusRow("Session", s.providerSessionId, mono = true) }

                    item { StatusSection("Usage") }
                    if (s.turns == 0) {
                        item { StatusNote("Usage appears after the first reply.") }
                    } else {
                        if (s.costUsd > 0) item { StatusRow("Cost", "$" + String.format(java.util.Locale.US, "%.2f", s.costUsd)) }
                        item { StatusRow("Tokens", "${compactCount(s.inputTokens)} in · ${compactCount(s.outputTokens)} out") }
                        item { StatusRow("Replies", s.turns.toString()) }
                        if (s.contextWindow > 0) {
                            item {
                                val used = (s.contextTokens.toFloat() / s.contextWindow).coerceIn(0f, 1f)
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    StatusRow("Context", "${compactCount(s.contextTokens)} of ${compactCount(s.contextWindow)} (${(used * 100).toInt()}%)")
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { used },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        color = if (used > 0.8f) Amber else MaterialTheme.colorScheme.primary,
                                    )
                                    if (used > 0.8f) StatusNote("Nearly full. /compact frees up space.")
                                }
                            }
                        }
                    }

                    if (s.reported) {
                        item { StatusSection("Environment") }
                        item { StatusRow("Tools", "${s.tools} tools · ${s.skills} skills · ${s.agents} agents") }
                        if (s.plugins.isNotEmpty()) item { StatusRow("Plugins", s.plugins.joinToString(", ")) }
                        item { StatusSection("MCP servers") }
                        if (s.mcpServers.isEmpty()) item { StatusNote("None configured.") }
                        items(s.mcpServers, key = { "mcp-${it.first}" }) { (name, state) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(8.dp).background(
                                        when (state) {
                                            "connected" -> Ok
                                            "failed" -> MaterialTheme.colorScheme.error
                                            else -> Amber
                                        },
                                        CircleShape,
                                    ),
                                )
                                Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 10.dp))
                                Text(state, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item { StatusNote("Server states are from when this chat's agent last started.") }
                    } else if (s.provider == "claude") {
                        item { StatusNote("Version, plugins and MCP servers appear after Claude's first reply.") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkingLineText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun StatusRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                value,
                style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
}

fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}
