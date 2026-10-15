package dev.pocket.app.data

import android.content.Context
import dev.pocket.app.model.AgentSession
import dev.pocket.app.model.ApprovalRequest
import dev.pocket.app.model.BranchInfo
import dev.pocket.app.model.ChangedFile
import dev.pocket.app.model.CollisionPolicy
import dev.pocket.app.model.ConnectResult
import dev.pocket.app.model.ConnectionKind
import dev.pocket.app.model.ConnectionPhase
import dev.pocket.app.model.ConnectionProfile
import dev.pocket.app.model.Connectivity
import dev.pocket.app.model.DeploymentRecipe
import dev.pocket.app.model.DeploymentRun
import dev.pocket.app.model.DiagnosticLine
import dev.pocket.app.model.DiffBase
import dev.pocket.app.model.EditorBuffer
import dev.pocket.app.model.FileEntry
import dev.pocket.app.model.GitFile
import dev.pocket.app.model.GitOperationResult
import dev.pocket.app.model.GitStatus
import dev.pocket.app.model.ManagedProcess
import dev.pocket.app.model.NewSession
import dev.pocket.app.model.PortMapping
import dev.pocket.app.model.ProcessState
import dev.pocket.app.model.Project
import dev.pocket.app.model.Provider
import dev.pocket.app.model.SaveResult
import dev.pocket.app.model.SessionEvent
import dev.pocket.app.model.SessionState
import dev.pocket.app.model.TerminalSnapshot
import dev.pocket.app.model.TransferDirection
import dev.pocket.app.model.TransferJob
import dev.pocket.app.model.TransferState
import dev.pocket.app.model.TunnelState
import dev.pocket.app.model.WorkspaceChoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.security.MessageDigest
import java.util.UUID

class DemoRepository : PocketRepository {
    private val connections = MutableStateFlow(seedConnections())
    private val activeConnectionId = MutableStateFlow("demo")
    private val projects = MutableStateFlow(seedProjects())
    private val activeProject = MutableStateFlow<String?>("retail")
    private val sessions = MutableStateFlow(seedSessions())
    private val events = MutableStateFlow(seedEvents())
    private val approvals = MutableStateFlow(seedApprovals())
    private val files = MutableStateFlow(seedFiles())
    private val buffers = MutableStateFlow<List<EditorBuffer>>(emptyList())
    private val remoteText = MutableStateFlow(seedRemote())
    private val transfers = MutableStateFlow<List<TransferJob>>(emptyList())
    private val mappings = MutableStateFlow(seedMappings())
    private val processes = MutableStateFlow(seedProcesses())
    private val recipes = MutableStateFlow(seedRecipes())
    private val runs = MutableStateFlow<List<DeploymentRun>>(emptyList())
    private val terminals = MutableStateFlow(mapOf("retail" to TerminalSnapshot("retail", "retail@work:/srv/retail $\n", true)))
    private val filter = MutableStateFlow<SessionState?>(null)
    private val seenKeys = mutableSetOf<String>()
    private var seq = 20L

    override fun connections() = connections
    override fun activeConnection() = combine(connections, activeConnectionId) { list, id -> list.find { it.id == id && it.phase == ConnectionPhase.Connected } }
    override fun projects() = projects
    override fun project(id: String) = projects.map { list -> list.find { it.id == id } }
    override fun activeProjectId() = activeProject
    override fun sessions() = combine(sessions, filter) { list, state ->
        list.filter { !it.archived && (state == null || matches(it.state, state)) }
            .sortedByDescending { it.updatedAt }
    }
    override fun session(id: String) = sessions.map { list -> list.find { it.id == id } }
    override fun events(sessionId: String) = events.map { list -> list.filter { it.sessionId == sessionId } }
    override fun approvals(sessionId: String) = approvals.map { list -> list.filter { it.sessionId == sessionId } }
    override fun changedFiles(sessionId: String): Flow<List<ChangedFile>> = MutableStateFlow(
        if (sessionId == "fix-filters") listOf(ChangedFile("src/app/filters.tsx", "modified", 18, 4)) else emptyList(),
    )
    override fun buffers(projectId: String) = buffers.map { list -> list.filter { it.projectId == projectId } }
    override fun transfers() = transfers
    override fun mappings() = mappings
    override fun processes(projectId: String) = processes.map { list -> list.filter { it.projectId == projectId } }
    override fun recipes(projectId: String) = recipes.map { list -> list.filter { it.projectId == projectId } }
    override fun deployRun(id: String) = runs.map { list -> list.find { it.id == id } }
    override fun terminal(projectId: String) = terminals.map { map -> map[projectId] ?: TerminalSnapshot(projectId, "", false) }
    override fun sessionFilter() = filter

    override suspend fun upsertConnection(profile: ConnectionProfile) {
        connections.update { list -> list.filter { it.id != profile.id } + profile }
    }

    override suspend fun deleteConnection(id: String) {
        connections.update { list -> list.filter { it.id != id } }
    }

    override suspend fun connect(id: String): ConnectResult {
        val profile = connections.value.find { it.id == id }
            ?: return ConnectResult(false, "That connection is gone.", "Add it again.")
        connections.update { list -> list.map { if (it.id == id) it.copy(phase = ConnectionPhase.Connecting, statusLine = "Connecting") else it } }
        delay(350)
        if (profile.kind == ConnectionKind.DirectSsh && profile.host.isBlank()) {
            connections.update { list -> list.map { if (it.id == id) it.copy(phase = ConnectionPhase.Failed, statusLine = "VM unreachable") else it } }
            return ConnectResult(false, "VM unreachable", "Check the host, then Test.")
        }
        connections.update { list ->
            list.map {
                if (it.id == id) it.copy(phase = ConnectionPhase.Connected, statusLine = "Connected", lastConnectedAt = System.currentTimeMillis())
                else it
            }
        }
        activeConnectionId.value = id
        return ConnectResult(true, "Connected")
    }

    override suspend fun disconnect() {
        val id = activeConnectionId.value
        connections.update { list -> list.map { if (it.id == id) it.copy(phase = ConnectionPhase.Disconnected, statusLine = "Disconnected") else it } }
    }

    override suspend fun testConnection(id: String): List<DiagnosticLine> {
        val profile = connections.value.find { it.id == id }
        return listOf(
            DiagnosticLine("Profile", profile != null, profile?.name ?: "Missing"),
            DiagnosticLine("Host", profile?.host?.isNotBlank() == true || profile?.kind == ConnectionKind.Demo, profile?.host ?: "Demo workspace"),
            DiagnosticLine("Companion", true, "Loopback only"),
            DiagnosticLine("Google account", profile?.kind != ConnectionKind.GoogleCloud, if (profile?.kind == ConnectionKind.GoogleCloud) "Sign in from Connections" else "Not used"),
        )
    }

    override suspend fun saveSshSecret(connectionId: String, privateKeyPem: ByteArray?, password: String?) = Unit

    override fun googleAccount() = MutableStateFlow<String?>(null)

    override suspend fun signInGoogle(context: Context) =
        ConnectResult(false, "Demo mode does not sign in to Google")

    override suspend fun acceptHostKey(id: String, fingerprint: String) {
        connections.update { list -> list.map { if (it.id == id) it.copy(hostKeyFingerprint = fingerprint, statusLine = "Connected") else it } }
    }

    override suspend fun upsertProject(project: Project) {
        projects.update { list -> list.filter { it.id != project.id } + project }
    }

    override suspend fun setActiveProject(id: String?) {
        activeProject.value = id
    }

    override suspend fun createSession(draft: NewSession): AgentSession {
        val project = projects.value.find { it.id == draft.projectId }
        val session = AgentSession(
            id = UUID.randomUUID().toString().take(8),
            projectId = draft.projectId,
            provider = draft.provider,
            title = draft.title.ifBlank { "New chat" },
            model = draft.model,
            workspace = draft.workspace,
            workspacePath = if (draft.workspace == WorkspaceChoice.Worktree) "${project?.folder}/.worktrees/${draft.title.lowercase().replace(' ', '-')}" else project?.folder ?: "",
            branch = project?.branch ?: "",
            state = SessionState.Running,
            latestLine = "Session started",
            updatedAt = System.currentTimeMillis(),
        )
        sessions.update { it + session }
        addEvent(session.id, "status", "Started ${draft.provider.name} in ${session.workspacePath}")
        projects.update { list -> list.map { if (it.id == draft.projectId) it.copy(activeChats = it.activeChats + 1) else it } }
        return session
    }

    override suspend fun setSessionControls(sessionId: String, model: String, effort: String, mode: String) {
        sessions.update { list ->
            list.map { if (it.id == sessionId) it.copy(model = model, effort = effort, mode = mode.ifBlank { "agent" }) else it }
        }
        addEvent(sessionId, "status", "Using ${mode.ifBlank { "agent" }}")
    }

    override suspend fun prompt(sessionId: String, text: String, idempotencyKey: String) {
        if (!seenKeys.add(idempotencyKey)) return
        addEvent(sessionId, "user", text)
        sessions.update { list ->
            list.map {
                if (it.id == sessionId) it.copy(state = SessionState.Running, latestLine = text, updatedAt = System.currentTimeMillis()) else it
            }
        }
        delay(250)
        addEvent(sessionId, "output", "Demo reply. On a connected VM this text is the provider CLI, written to its persistent terminal.")
        sessions.update { list ->
            list.map {
                if (it.id == sessionId) it.copy(state = SessionState.AwaitingInput, latestLine = "Waiting for the next prompt", connectivity = Connectivity.Online) else it
            }
        }
    }

    override suspend fun interrupt(sessionId: String) {
        sessions.update { list -> list.map { if (it.id == sessionId) it.copy(state = SessionState.Stopped, latestLine = "Stopped") else it } }
        addEvent(sessionId, "status", "Interrupted")
    }

    override suspend fun resume(sessionId: String) {
        val session = sessions.value.find { it.id == sessionId } ?: return
        if (session.provider != Provider.Terminal && session.providerSessionId.isBlank()) {
            addEvent(sessionId, "status", "Resume needs a provider session id. The terminal is still available.")
            return
        }
        sessions.update { list -> list.map { if (it.id == sessionId) it.copy(state = SessionState.Running, latestLine = "Resumed") else it } }
        addEvent(sessionId, "status", "Resumed")
    }

    override suspend fun close(sessionId: String) {
        sessions.update { list -> list.map { if (it.id == sessionId) it.copy(state = SessionState.Stopped, latestLine = "Closed") else it } }
    }

    override suspend fun renameSession(sessionId: String, title: String) {
        sessions.update { list -> list.map { if (it.id == sessionId) it.copy(title = title) else it } }
    }

    override suspend fun archiveSession(sessionId: String) {
        sessions.update { list -> list.map { if (it.id == sessionId) it.copy(archived = true) else it } }
    }

    override suspend fun respond(approvalId: String, allow: Boolean) {
        approvals.update { list -> list.map { if (it.id == approvalId) it.copy(resolved = true, allowed = allow) else it } }
        val approval = approvals.value.find { it.id == approvalId } ?: return
        addEvent(approval.sessionId, "status", if (allow) "Allowed ${approval.action}" else "Denied ${approval.action}")
    }

    override suspend fun listDir(projectId: String, path: String): List<FileEntry> {
        val root = projects.value.find { it.id == projectId }?.folder ?: return emptyList()
        val dir = if (path.isBlank()) root else path
        return files.value.filter { parentOf(it.path) == dir.trimEnd('/') }
    }

    override suspend fun mkdir(projectId: String, path: String) {
        files.update { it + FileEntry(path.substringAfterLast('/'), path, directory = true, mode = "drwxr-xr-x") }
    }

    override suspend fun renamePath(projectId: String, path: String, newName: String) {
        val dest = parentOf(path) + "/" + newName
        files.update { list -> list.map { if (it.path == path) it.copy(name = newName, path = dest) else it } }
    }

    override suspend fun movePath(projectId: String, path: String, destination: String) {
        files.update { list -> list.map { if (it.path == path) it.copy(path = destination, name = destination.substringAfterLast('/')) else it } }
    }

    override suspend fun copyPath(projectId: String, path: String, destination: String) {
        val source = files.value.find { it.path == path } ?: return
        files.update { it + source.copy(path = destination, name = destination.substringAfterLast('/')) }
    }

    override suspend fun deletePaths(projectId: String, paths: List<String>) {
        files.update { list -> list.filter { it.path !in paths } }
        remoteText.update { it - paths.toSet() }
    }

    override suspend fun openBuffer(projectId: String, path: String): EditorBuffer {
        buffers.value.find { it.projectId == projectId && it.path == path }?.let { return it }
        val text = remoteText.value[path] ?: ""
        val buffer = EditorBuffer(projectId, path, text, fingerprint(text), dirty = false, binary = text.isEmpty() && path.endsWith(".png"))
        buffers.update { it + buffer }
        return buffer
    }

    override suspend fun updateBuffer(projectId: String, path: String, text: String) {
        buffers.update { list ->
            list.map {
                if (it.projectId == projectId && it.path == path) it.copy(text = text, dirty = text != remoteText.value[path]) else it
            }
        }
    }

    override suspend fun saveBuffer(projectId: String, path: String): SaveResult {
        val buffer = buffers.value.find { it.projectId == projectId && it.path == path }
            ?: return SaveResult(false, "Nothing open")
        val disk = remoteText.value[path] ?: ""
        val diskFp = fingerprint(disk)
        if (buffer.fingerprint != diskFp && buffer.fingerprint.isNotBlank()) {
            return SaveResult(false, "The file changed on the workspace.", conflict = true, remoteText = disk, remoteFingerprint = diskFp)
        }
        remoteText.update { it + (path to buffer.text) }
        val fp = fingerprint(buffer.text)
        buffers.update { list -> list.map { if (it.path == path) it.copy(fingerprint = fp, dirty = false) else it } }
        return SaveResult(true, "Saved")
    }

    override suspend fun reloadBuffer(projectId: String, path: String): EditorBuffer {
        val text = remoteText.value[path] ?: ""
        val buffer = EditorBuffer(projectId, path, text, fingerprint(text), dirty = false)
        buffers.update { list -> list.filter { it.path != path } + buffer }
        return buffer
    }

    override suspend fun saveCopy(projectId: String, path: String): SaveResult {
        val buffer = buffers.value.find { it.path == path } ?: return SaveResult(false, "Nothing open")
        val copy = path + ".copy"
        remoteText.update { it + (copy to buffer.text) }
        files.update { list -> list + FileEntry(copy.substringAfterLast('/'), copy, directory = false, bytes = buffer.text.length.toLong(), mode = "-rw-r--r--") }
        return SaveResult(true, "Saved a separate copy")
    }

    override suspend fun enqueueUpload(projectId: String, sourceLabel: String, destinationPath: String, bytes: Long, collision: CollisionPolicy): String {
        val exists = files.value.any { it.path == destinationPath }
        val dest = if (exists && collision == CollisionPolicy.KeepBoth) destinationPath + ".2" else destinationPath
        if (exists && collision == CollisionPolicy.Skip) {
            val job = TransferJob(UUID.randomUUID().toString().take(8), projectId, TransferDirection.Upload, sourceLabel, dest, bytes, bytes, TransferState.Completed, "Skipped")
            transfers.update { it + job }
            return job.id
        }
        val job = TransferJob(UUID.randomUUID().toString().take(8), projectId, TransferDirection.Upload, sourceLabel, dest, bytes, 0, TransferState.Transferring)
        transfers.update { it + job }
        delay(200)
        transfers.update { list -> list.map { if (it.id == job.id) it.copy(bytesDone = bytes, state = TransferState.Completed, message = "Verified") else it } }
        files.update { list -> list.filter { it.path != dest } + FileEntry(dest.substringAfterLast('/'), dest, false, bytes = bytes, mode = "-rw-r--r--") }
        return job.id
    }

    override suspend fun enqueueDownload(projectId: String, path: String, destinationLabel: String): String {
        val bytes = remoteText.value[path]?.length?.toLong() ?: 0L
        val job = TransferJob(UUID.randomUUID().toString().take(8), projectId, TransferDirection.Download, path, destinationLabel, bytes, bytes, TransferState.Completed, "Ready to open")
        transfers.update { it + job }
        return job.id
    }

    override suspend fun cancelTransfer(id: String) {
        transfers.update { list -> list.map { if (it.id == id && it.state == TransferState.Transferring) it.copy(state = TransferState.Cancelled, message = "Cancelled") else it } }
    }

    override suspend fun retryTransfer(id: String) {
        transfers.update { list -> list.map { if (it.id == id) it.copy(state = TransferState.Completed, message = "Retried") else it } }
    }

    override suspend fun gitStatus(projectId: String) = GitStatus(
        branch = projects.value.find { it.id == projectId }?.branch ?: "main",
        files = listOf(
            GitFile("src/app/filters.tsx", "modified"),
            GitFile("src/lib/api.ts", "modified"),
        ),
        ahead = 0,
        behind = 0,
    )

    override suspend fun gitDiff(projectId: String, path: String, base: DiffBase, commit: String): String {
        val target = path.ifBlank { "src/app/filters.tsx" }
        return """
            diff --git a/$target b/$target
            --- a/$target
            +++ b/$target
            @@ -12,4 +12,6 @@
            -  return rows
            +  return rows.filter((row) => row.active)
            +  // base ${base.name} ${commit}
        """.trimIndent()
    }

    override suspend fun branches(projectId: String) = listOf(
        BranchInfo("fix/filters", true),
        BranchInfo("main", false),
    )

    override suspend fun commit(projectId: String, paths: List<String>, message: String, idempotencyKey: String): GitOperationResult {
        if (!seenKeys.add(idempotencyKey)) return GitOperationResult(true, "Already committed")
        if (paths.isEmpty()) return GitOperationResult(false, "Select at least one file. Nothing was staged.")
        if (message.isBlank()) return GitOperationResult(false, "Write a commit message.")
        return GitOperationResult(true, "Committed ${paths.size} file(s). Not pushed.")
    }

    override suspend fun push(projectId: String, idempotencyKey: String): GitOperationResult {
        if (!seenKeys.add("push:$idempotencyKey")) return GitOperationResult(true, "Push already sent")
        return GitOperationResult(true, "Pushed fix/filters")
    }

    override suspend fun pull(projectId: String, idempotencyKey: String): GitOperationResult {
        if (!seenKeys.add("pull:$idempotencyKey")) return GitOperationResult(true, "Pull already sent")
        return GitOperationResult(true, "Already up to date")
    }

    override suspend fun addMapping(projectId: String, label: String, remotePort: Int, phonePort: Int): PortMapping {
        val taken = mappings.value.any { it.phonePort == phonePort && it.tunnelState == TunnelState.Listening }
        val port = if (taken) phonePort + 1 else phonePort
        val mapping = PortMapping(
            id = UUID.randomUUID().toString().take(8),
            projectId = projectId,
            label = label,
            remoteHost = "127.0.0.1",
            remotePort = remotePort,
            phonePort = port,
            processState = ProcessState.Stopped,
            tunnelState = TunnelState.Closed,
            url = "http://127.0.0.1:$port",
        )
        mappings.update { it + mapping }
        return mapping
    }

    override suspend fun startMapping(id: String): PortMapping {
        val current = mappings.value.find { it.id == id } ?: error("Missing mapping")
        val occupied = mappings.value.any { it.id != id && it.phonePort == current.phonePort && it.tunnelState == TunnelState.Listening }
        if (occupied) {
            val shifted = current.copy(phonePort = current.phonePort + 1, tunnelState = TunnelState.Failed, url = "http://127.0.0.1:${current.phonePort + 1}")
            mappings.update { list -> list.map { if (it.id == id) shifted else it } }
            return shifted.copy(tunnelState = TunnelState.Failed)
        }
        val started = current.copy(tunnelState = TunnelState.Listening, processState = ProcessState.Running, url = "http://127.0.0.1:${current.phonePort}")
        mappings.update { list -> list.map { if (it.id == id) started else it } }
        projects.update { list -> list.map { if (it.id == current.projectId) it.copy(previewRunning = true) else it } }
        return started
    }

    override suspend fun stopMapping(id: String) {
        val current = mappings.value.find { it.id == id } ?: return
        mappings.update { list -> list.map { if (it.id == id) it.copy(tunnelState = TunnelState.Closed) else it } }
        projects.update { list -> list.map { if (it.id == current.projectId) it.copy(previewRunning = false) else it } }
    }

    override suspend fun startProcess(projectId: String, label: String, command: String): ManagedProcess {
        val process = ManagedProcess(UUID.randomUUID().toString().take(8), projectId, label, command, ProcessState.Running, "started")
        processes.update { it + process }
        return process
    }

    override suspend fun stopProcess(id: String) {
        processes.update { list -> list.map { if (it.id == id) it.copy(state = ProcessState.Stopped, log = it.log + "\nstopped") else it } }
    }

    override suspend fun saveRecipe(recipe: DeploymentRecipe) {
        recipes.update { list -> list.filter { it.id != recipe.id } + recipe }
    }

    override suspend fun startDeploy(projectId: String, recipeId: String, idempotencyKey: String): DeploymentRun {
        if (runs.value.any { it.projectId == projectId && it.state == ProcessState.Running }) {
            return runs.value.first { it.projectId == projectId && it.state == ProcessState.Running }
        }
        if (seenKeys.contains("deploy:$idempotencyKey")) {
            return runs.value.last()
        }
        seenKeys.add("deploy:$idempotencyKey")
        val recipe = recipes.value.find { it.id == recipeId }
        val run = DeploymentRun(
            id = UUID.randomUUID().toString().take(8),
            recipeId = recipeId,
            projectId = projectId,
            state = ProcessState.Running,
            log = "Deploy ${recipe?.name ?: recipeId}\n$ ${recipe?.command}\n",
            startedAt = System.currentTimeMillis(),
        )
        runs.update { it + run }
        delay(300)
        runs.update { list -> list.map { if (it.id == run.id) it.copy(state = ProcessState.Stopped, log = it.log + "Health check recorded. This demo does not contact a server.\n") else it } }
        return runs.value.find { it.id == run.id } ?: run
    }

    override suspend fun terminalInput(projectId: String, data: String) {
        val current = terminals.value[projectId] ?: TerminalSnapshot(projectId, "", true)
        val next = current.output + data + if (data == "\u0003") "^C\n" else "\n"
        terminals.update { it + (projectId to current.copy(output = next, alive = true)) }
    }

    override suspend fun terminalResize(projectId: String, cols: Int, rows: Int) = Unit

    override suspend fun setSessionFilter(state: SessionState?) {
        filter.value = state
    }

    private fun matches(actual: SessionState, filter: SessionState): Boolean {
        if (filter == SessionState.Running) return actual == SessionState.Running || actual == SessionState.Starting
        if (filter == SessionState.AwaitingInput) return actual == SessionState.AwaitingInput
        if (filter == SessionState.Completed) return actual == SessionState.Completed
        return actual == filter
    }

    private fun addEvent(sessionId: String, type: String, text: String) {
        seq += 1
        events.update { it + SessionEvent(seq, sessionId, type, text, System.currentTimeMillis()) }
    }

    private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/')

    private fun fingerprint(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun seedConnections() = listOf(
        ConnectionProfile(
            id = "demo",
            name = "This phone",
            kind = ConnectionKind.Demo,
            host = "local demo",
            username = "you",
            phase = ConnectionPhase.Connected,
            statusLine = "Connected",
            lastConnectedAt = System.currentTimeMillis(),
        ),
        ConnectionProfile(
            id = "work",
            name = "Work VM",
            kind = ConnectionKind.DirectSsh,
            host = "",
            username = "dev",
            statusLine = "Disconnected",
        ),
    )

    private fun seedProjects() = listOf(
        Project(
            id = "retail",
            connectionId = "demo",
            name = "Retail dashboard",
            folder = "/srv/retail",
            branch = "fix/filters",
            devCommand = "npm run dev -- --port 3002",
            testCommand = "npm test",
            ports = listOf(3002, 8000),
            activeChats = 2,
            previewRunning = false,
        ),
    )

    private fun seedSessions() = listOf(
        AgentSession(
            id = "fix-filters",
            projectId = "retail",
            provider = Provider.Claude,
            title = "Fix filters",
            model = "claude",
            workspace = WorkspaceChoice.Worktree,
            workspacePath = "/srv/retail/.worktrees/fix-filters",
            branch = "fix/filters",
            state = SessionState.AwaitingInput,
            latestLine = "Filter chips ignore the active flag",
            providerSessionId = "demo-session",
            updatedAt = System.currentTimeMillis(),
        ),
        AgentSession(
            id = "api-types",
            projectId = "retail",
            provider = Provider.Terminal,
            title = "API types",
            workspace = WorkspaceChoice.Worktree,
            workspacePath = "/srv/retail/.worktrees/api-types",
            branch = "api-types",
            state = SessionState.Completed,
            latestLine = "Types checked",
            updatedAt = System.currentTimeMillis() - 60_000,
        ),
    )

    private fun seedEvents() = listOf(
        SessionEvent(1, "fix-filters", "user", "The filter row still shows archived products.", System.currentTimeMillis() - 10_000),
        SessionEvent(2, "fix-filters", "output", "I would change src/app/filters.tsx so the list keeps rows with active set.", System.currentTimeMillis() - 9_000),
    )

    private fun seedApprovals() = listOf(
        ApprovalRequest(
            id = "ap-1",
            sessionId = "fix-filters",
            action = "Edit src/app/filters.tsx",
            detail = "Replace the return value so inactive rows are excluded. No other files.",
        ),
    )

    private fun seedFiles() = listOf(
        FileEntry("src", "/srv/retail/src", directory = true, mode = "drwxr-xr-x"),
        FileEntry("app", "/srv/retail/src/app", directory = true, mode = "drwxr-xr-x"),
        FileEntry("lib", "/srv/retail/src/lib", directory = true, mode = "drwxr-xr-x"),
        FileEntry("filters.tsx", "/srv/retail/src/app/filters.tsx", directory = false, bytes = 420, mode = "-rw-r--r--"),
        FileEntry("page.tsx", "/srv/retail/src/app/page.tsx", directory = false, bytes = 300, mode = "-rw-r--r--"),
        FileEntry("api.ts", "/srv/retail/src/lib/api.ts", directory = false, bytes = 180, mode = "-rw-r--r--"),
        FileEntry("README.md", "/srv/retail/README.md", directory = false, bytes = 80, mode = "-rw-r--r--"),
        FileEntry("link", "/srv/retail/link", directory = false, symlink = true, mode = "lrwxr-xr-x"),
    )

    private fun seedRemote() = mapOf(
        "/srv/retail/src/app/filters.tsx" to "export function visible(rows) {\n  return rows\n}\n",
        "/srv/retail/src/app/page.tsx" to "export default function Page() {\n  return <main>Retail</main>\n}\n",
        "/srv/retail/src/lib/api.ts" to "export const apiPort = 8000\n",
        "/srv/retail/README.md" to "# Retail dashboard\n",
    )

    private fun seedMappings() = listOf(
        PortMapping("front", "retail", "Frontend", "127.0.0.1", 3002, 3002, ProcessState.Stopped, TunnelState.Closed, "http://127.0.0.1:3002"),
        PortMapping("api", "retail", "API", "127.0.0.1", 8000, 8000, ProcessState.Stopped, TunnelState.Closed, "http://127.0.0.1:8000"),
    )

    private fun seedProcesses() = listOf(
        ManagedProcess("dev", "retail", "Frontend", "npm run dev -- --port 3002", ProcessState.Stopped, "Not started"),
    )

    private fun seedRecipes() = listOf(
        DeploymentRecipe(
            id = "staging",
            projectId = "retail",
            name = "Staging",
            command = "npm run deploy:staging",
            workingDirectory = "/srv/retail",
            target = "staging",
            healthUrl = "http://127.0.0.1:3002",
        ),
    )
}
