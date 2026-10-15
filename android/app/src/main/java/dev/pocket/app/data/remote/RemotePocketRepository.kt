package dev.pocket.app.data.remote

import android.app.Activity
import android.content.Context
import com.jcraft.jsch.SocketFactory
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.google.ComputeException
import dev.pocket.app.google.ComputeVm
import dev.pocket.app.google.GoogleAuth
import dev.pocket.app.google.IapSocketFactory
import dev.pocket.app.google.IapTunnel
import dev.pocket.app.model.AgentCommand
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
import dev.pocket.app.model.GitCommit
import dev.pocket.app.model.GitStatus
import dev.pocket.app.model.LinkState
import dev.pocket.app.model.PendingPrompt
import dev.pocket.app.model.PendingState
import dev.pocket.app.model.ManagedProcess
import dev.pocket.app.model.NewSession
import dev.pocket.app.model.PortMapping
import dev.pocket.app.model.ProcessState
import dev.pocket.app.model.Project
import dev.pocket.app.model.SaveResult
import dev.pocket.app.model.SessionEvent
import dev.pocket.app.model.SessionState
import dev.pocket.app.model.SessionStatus
import dev.pocket.app.model.TerminalSnapshot
import dev.pocket.app.model.TransferJob
import dev.pocket.app.model.TransferState
import dev.pocket.app.model.TunnelState
import dev.pocket.app.model.WorkspaceChoice
import dev.pocket.app.ssh.SshCredentials
import dev.pocket.app.ssh.SshSessionManager
import dev.pocket.app.model.Provider
import dev.pocket.app.ssh.PortSelector
import dev.pocket.app.model.TransferDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.WebSocket
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class RemotePocketRepository(
    private val sshManager: SshSessionManager,
    private val delegate: PocketRepository,
    private val keyDirectory: File? = null,
    private val profileFile: File? = null,
    private val googleAuth: GoogleAuth? = null,
) : PocketRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Connection state ──────────────────────────────────────────────────────
    private val _connections = MutableStateFlow<List<ConnectionProfile>>(emptyList())
    private val _activeConnectionId = MutableStateFlow<String?>(null)
    private val tokens = mutableMapOf<String, String>()
    private val credentials = mutableMapOf<String, SshCredentials>()

    @Volatile private var client: CompanionClient? = null
    private var pollJob: Job? = null

    // ── Remote state (refreshed by polling) ───────────────────────────────────
    private val _sessions = MutableStateFlow<List<AgentSession>>(emptyList())
    private val _events = MutableStateFlow<Map<String, List<SessionEvent>>>(emptyMap())
    private val _streaming = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _outbox = MutableStateFlow<List<PendingPrompt>>(emptyList())
    private val _link = MutableStateFlow(LinkState.Offline)
    private val _approvals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _activeProject = MutableStateFlow<String?>(null)
    private val _mappings = MutableStateFlow<List<PortMapping>>(emptyList())
    private val _processes = MutableStateFlow<List<ManagedProcess>>(emptyList())
    private val _recipes = MutableStateFlow<List<DeploymentRecipe>>(emptyList())
    private val _runs = MutableStateFlow<List<DeploymentRun>>(emptyList())
    private val _transfers = MutableStateFlow<List<TransferJob>>(emptyList())
    private val _buffers = MutableStateFlow<List<EditorBuffer>>(emptyList())
    private val _terminals = MutableStateFlow<Map<String, TerminalSnapshot>>(emptyMap())
    private val _filter = MutableStateFlow<SessionState?>(null)
    private val eventLock = Any()
    @Volatile private var lastEventSeq = 0L
    private var seqConnectionId: String? = null
    @Volatile private var eventSocket: WebSocket? = null
    @Volatile private var streamOpen = false
    @Volatile private var streamStartedAt = 0L
    @Volatile private var pollFailures = 0
    private val connectMutex = Mutex()
    @Volatile private var desiredConnectionId: String? = null
    private var supervisorJob: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Survives the app being killed: prompts not yet confirmed by the VM, and
    // how far this phone has read the event stream.
    private val outboxFile = profileFile?.resolveSibling("outbox.json")
    private val cursorFile = profileFile?.resolveSibling("event-cursor.json")
    private val outboxLock = Any()
    private var savedCursor: SavedCursor? = null
    private var cursorSavedAt = 0L
    @Volatile private var connectGeneration = 0
    private var activeTunnel: IapTunnel? = null
    private val profileJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // ── Credential helpers ────────────────────────────────────────────────────

    init {
        val saved = loadSavedProfiles()
        if (saved.isNotEmpty()) _connections.value = saved
        loadOutbox()
        savedCursor = runCatching { profileJson.decodeFromString<SavedCursor>(cursorFile!!.readText()) }.getOrNull()
        scope.launch {
            val seededConnections = delegate.connections().first()
            val demos = seededConnections.filter { it.kind == ConnectionKind.Demo }
            _connections.update { current ->
                val real = current.filter { it.kind != ConnectionKind.Demo }
                demos + (real.ifEmpty { saved })
            }
            val seededProjects = delegate.projects().first()
            if (_projects.value.isEmpty()) _projects.value = seededProjects
            if (_activeProject.value == null) _activeProject.value = delegate.activeProjectId().first()
            val seededMappings = delegate.mappings().first()
                if (_mappings.value.isEmpty()) _mappings.value = seededMappings
        }
    }

    private fun loadStoredKey(connectionId: String) {
        if (credentials[connectionId] != null) return
        val file = keyDirectory?.resolve(connectionId) ?: return
        if (file.isFile) credentials[connectionId] = SshCredentials.KeyAuth(file.readBytes())
    }

    fun setCredentials(connectionId: String, creds: SshCredentials) {
        credentials[connectionId] = creds
    }

    override suspend fun saveSshSecret(connectionId: String, privateKeyPem: ByteArray?, password: String?) {
        when {
            privateKeyPem != null -> {
                keyDirectory?.mkdirs()
                keyDirectory?.resolve(connectionId)?.writeBytes(privateKeyPem)
                credentials[connectionId] = SshCredentials.KeyAuth(privateKeyPem)
            }
            !password.isNullOrBlank() -> credentials[connectionId] = SshCredentials.PasswordAuth(password)
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    override fun connections(): Flow<List<ConnectionProfile>> = _connections
    override fun activeConnection(): Flow<ConnectionProfile?> =
        combine(_connections, _activeConnectionId) { list, id ->
            // Stays set while reconnecting so the top bar can say so.
            list.find { it.id == id }
        }

    override fun linkState(): Flow<LinkState> = _link

    /** Unfiltered sessions, for the connection service's notifications. */
    fun sessionUpdates(): StateFlow<List<AgentSession>> = _sessions.asStateFlow()

    override suspend fun upsertConnection(profile: ConnectionProfile) {
        _connections.update { list -> list.filter { it.id != profile.id } + profile }
        persistProfiles()
    }

    override suspend fun deleteConnection(id: String) {
        _connections.update { it.filter { c -> c.id != id } }
        credentials.remove(id)
        tokens.remove(id)
        keyDirectory?.resolve(id)?.delete()
        persistProfiles()
    }

    override fun googleAccount(): Flow<String?> = googleAuth?.account() ?: MutableStateFlow(null)

    override suspend fun signInGoogle(context: Context): ConnectResult {
        val auth = googleAuth ?: return ConnectResult(false, "Google sign-in is unavailable in this build")
        val activity = context as? Activity
            ?: return ConnectResult(false, "Open Pocket Dev and try Sign in again")
        return try {
            val email = auth.signIn(activity)
            ConnectResult(true, "Signed in as $email")
        } catch (e: Exception) {
            ConnectResult(false, e.message ?: "Google sign-in failed", "Use the Google account that can start this VM.")
        }
    }

    override suspend fun connect(id: String): ConnectResult {
        // Taken before waiting for the lock, so a Disconnect tapped meanwhile also cancels this attempt.
        val generation = connectGeneration
        return connectMutex.withLock { connectLocked(id, generation) }
    }

    private suspend fun connectLocked(id: String, generation: Int): ConnectResult = withContext(Dispatchers.IO) {
        val profile = _connections.value.find { it.id == id }
            ?: return@withContext ConnectResult(false, "Connection not found", "Add it again.")
        loadStoredKey(id)

        var socketFactory: SocketFactory? = null
        var endpoint = profile
        if (profile.kind != ConnectionKind.Demo && sshManager.isConnected) {
            sshManager.disconnect()
            closeTunnel()
        }
        if (profile.kind == ConnectionKind.GoogleCloud) {
            updatePhase(id, ConnectionPhase.Connecting, "Checking the VM")
            val ready = try {
                prepareGoogle(profile)
            } catch (e: Exception) {
                closeTunnel()
                val message = e.message ?: "Google connection failed"
                updatePhase(id, ConnectionPhase.Failed, message)
                return@withContext ConnectResult(false, message, recoveryFor(e))
            }
            socketFactory = ready.factory
            endpoint = ready.profile
        }

        if (profile.kind == ConnectionKind.Demo) {
            val result = delegate.connect(id)
            if (result.ok) {
                _activeConnectionId.value = id
                updatePhase(id, ConnectionPhase.Connected, "Connected")
            }
            return@withContext result
        }

        updatePhase(id, ConnectionPhase.Connecting, "Connecting…")

        val sshResult = sshManager.connect(endpoint, credentials[id], socketFactory)
        if (!sshResult.ok) {
            sshManager.disconnect()
            closeTunnel()
            updatePhase(id, ConnectionPhase.Failed, sshResult.message)
            return@withContext sshResult
        }

        val remoteCompanionHost = profile.companionHost.ifBlank { "127.0.0.1" }
        val localCompanionPort = try {
            sshManager.addForward(remoteCompanionHost, profile.companionPort, profile.companionPort)
        } catch (e: Exception) {
            sshManager.disconnect()
            closeTunnel()
            updatePhase(id, ConnectionPhase.Failed, "Companion tunnel failed")
            return@withContext ConnectResult(
                false,
                "Could not tunnel the companion port",
                e.message ?: "The phone could not open a local forward to ${remoteCompanionHost}:${profile.companionPort}.",
            )
        }
        val baseUrl = "http://127.0.0.1:$localCompanionPort"
        val c = CompanionClient(baseUrl, tokens[id] ?: "")

        var healthy = runCatching { c.health() }.getOrDefault(false)
        if (!healthy) {
            updatePhase(id, ConnectionPhase.Connecting, "Starting the companion")
            runCatching { sshManager.exec(companionStartCommand(profile.companionPort)) }
            repeat(8) {
                delay(1_500)
                healthy = runCatching { c.health() }.getOrDefault(false)
                if (healthy) return@repeat
            }
        }
        if (!healthy) {
            sshManager.disconnect()
            closeTunnel()
            updatePhase(id, ConnectionPhase.Failed, "Companion unreachable")
            return@withContext ConnectResult(
                false,
                "Companion is unreachable on the VM at ${remoteCompanionHost}:${profile.companionPort}",
                "SSH is up. Pocket Dev tried to start ~/pocket-dev/node on the VM. If this VM was just created, install the companion there once.",
            )
        }

        // Pair if we don't have a token yet
        if ((tokens[id] ?: "").isBlank() && profile.setupCode.isNotBlank()) {
            val pairResult = runCatching { c.pair(profile.setupCode) }
            if (pairResult.isSuccess) {
                val tok = pairResult.getOrThrow()
                tokens[id] = tok
                c.token = tok
            }
        } else if (tokens[id]?.isNotBlank() == true) {
            c.token = tokens[id]!!
        }

        resetEventCursor(id, c)
        if (generation != connectGeneration) return@withContext abandonConnect(id)
        client = c
        pollFailures = 0
        _activeConnectionId.value = id
        _link.value = LinkState.Live
        updatePhase(id, ConnectionPhase.Connected, "Connected to ${endpoint.host}")
        // Disconnect may have run while the line above was committing; undo if so.
        if (generation != connectGeneration) return@withContext abandonConnect(id)
        startEventStream(c)
        startPolling(profile)
        scope.launch { flushOutbox() }

        ConnectResult(true, "Connected to ${endpoint.host}")
    }

    /** Disconnect was tapped while this attempt was still setting up. */
    private fun abandonConnect(id: String): ConnectResult {
        pollJob?.cancel()
        closeEventStream()
        client = null
        _activeConnectionId.value = null
        _link.value = LinkState.Offline
        sshManager.disconnect()
        closeTunnel()
        updatePhase(id, ConnectionPhase.Disconnected, "Disconnected")
        return ConnectResult(false, "Disconnected")
    }

    private fun prepareGoogle(profile: ConnectionProfile): GoogleReady {
        val auth = googleAuth ?: throw IllegalStateException("Sign in with Google first")
        val instance = profile.instance.ifBlank { profile.host }
        if (profile.project.isBlank() || profile.zone.isBlank() || instance.isBlank()) {
            throw IllegalStateException("Enter the instance name, project, and zone")
        }
        if (profile.username.isBlank()) throw IllegalStateException("Enter the SSH username")
        if (credentials[profile.id] !is SshCredentials.KeyAuth) {
            throw IllegalStateException("Import the SSH private key for this VM")
        }
        val token = auth.accessToken()
        val compute = ComputeVm(token)
        waitUntilRunning(compute, profile, instance)
        updatePhase(profile.id, ConnectionPhase.Connecting, "Opening the IAP tunnel")
        closeTunnel()
        val tunnel = IapTunnel(token, profile.project, profile.zone, instance, 22)
        tunnel.open()
        activeTunnel = tunnel
        return GoogleReady(profile.copy(host = instance, port = 22), IapSocketFactory(tunnel))
    }

    private fun waitUntilRunning(compute: ComputeVm, profile: ConnectionProfile, instance: String) {
        val deadline = System.currentTimeMillis() + 180_000
        var requested = false
        while (System.currentTimeMillis() < deadline) {
            val status = compute.status(profile.project, profile.zone, instance)
            when (status) {
                "RUNNING" -> return
                "TERMINATED", "STOPPED" -> {
                    updatePhase(profile.id, ConnectionPhase.Connecting, "Starting the VM")
                    if (!requested) {
                        runCatching { compute.start(profile.project, profile.zone, instance) }.getOrElse { error ->
                            if (error !is ComputeException || error.status != 409) throw error
                        }
                        requested = true
                    }
                }
                "SUSPENDED" -> {
                    updatePhase(profile.id, ConnectionPhase.Connecting, "Resuming the VM")
                    if (!requested) {
                        runCatching { compute.resume(profile.project, profile.zone, instance) }.getOrElse { error ->
                            if (error !is ComputeException || error.status != 409) throw error
                        }
                        requested = true
                    }
                }
                "STAGING", "PROVISIONING", "REPAIRING", "STOPPING", "SUSPENDING" ->
                    updatePhase(profile.id, ConnectionPhase.Connecting, "VM is $status")
                else -> throw IllegalStateException("VM status is $status")
            }
            Thread.sleep(4_000)
        }
        throw IllegalStateException("The VM did not reach RUNNING")
    }

    private fun companionStartCommand(port: Int): String =
        "bash -lc 'export POCKET_DEV_PORT=$port; setsid nohup \"\$HOME/pocket-dev/node/bin/node\" --experimental-sqlite \"\$HOME/pocket-dev/companion/dist/index.js\" >> \"\$HOME/pocket-dev/companion.log\" 2>&1 < /dev/null & exit 0'"

    private fun recoveryFor(error: Exception): String = when (error) {
        is ComputeException -> when (error.status) {
            401, 403 -> "This Google account cannot start the VM. Sign in with the account that owns the project."
            404 -> "Check the instance name, project, and zone."
            else -> "Google returned ${error.status}."
        }
        else -> when {
            error.message?.startsWith("Sign in") == true -> "Use the Google account that can start this VM."
            error.message?.startsWith("Import the SSH") == true -> "Import the same private key you use for this VM."
            else -> "Check the instance, project, zone, and that IAP TCP forwarding is allowed."
        }
    }

    private fun closeTunnel() {
        activeTunnel?.close()
        activeTunnel = null
    }

    private fun loadSavedProfiles(): List<ConnectionProfile> {
        val file = profileFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            profileJson.decodeFromString<List<ConnectionProfile>>(file.readText()).map {
                it.copy(phase = ConnectionPhase.Disconnected, statusLine = "Disconnected")
            }
        }.getOrDefault(emptyList())
    }

    private fun persistProfiles() {
        val file = profileFile ?: return
        val saved = _connections.value
            .filter { it.kind != ConnectionKind.Demo }
            .map { it.copy(phase = ConnectionPhase.Disconnected, statusLine = "Disconnected") }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(profileJson.encodeToString(saved))
        }
    }

    private data class GoogleReady(val profile: ConnectionProfile, val factory: SocketFactory)

    @kotlinx.serialization.Serializable
    private data class SavedCursor(val connectionId: String, val seq: Long)

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        connectGeneration++
        desiredConnectionId = null
        synchronized(eventLock) { saveCursor(force = true) }
        supervisorJob?.cancel()
        pollJob?.cancel()
        closeEventStream()
        _link.value = LinkState.Offline
        val id = _activeConnectionId.value
        sshManager.disconnect()
        closeTunnel()
        client = null
        _activeConnectionId.value = null
        if (id != null) updatePhase(id, ConnectionPhase.Disconnected, "Disconnected")
        Unit
    }

    override suspend fun testConnection(id: String): List<DiagnosticLine> {
        val profile = _connections.value.find { it.id == id }
            ?: return listOf(DiagnosticLine("Profile", false, "Not found"))
        loadStoredKey(id)

        if (profile.kind == ConnectionKind.GoogleCloud) {
            val account = googleAuth?.email()
            val instance = profile.instance.ifBlank { profile.host }
            return listOf(
                DiagnosticLine("Profile", true, profile.name),
                DiagnosticLine("Google account", account != null, account ?: "Sign in with Google"),
                DiagnosticLine("Instance", instance.isNotBlank() && profile.project.isNotBlank() && profile.zone.isNotBlank(), "$instance · ${profile.zone}"),
                DiagnosticLine("SSH key", credentials[id] is SshCredentials.KeyAuth, if (credentials[id] is SshCredentials.KeyAuth) "Imported" else "Import the private key"),
                DiagnosticLine("IAP", true, "Opens inside the app when you connect"),
            )
        }

        val lines = mutableListOf<DiagnosticLine>()
        lines += DiagnosticLine("Profile", true, profile.name)
        lines += DiagnosticLine("Host", profile.host.isNotBlank(), profile.host.ifBlank { "Not set" })

        val stored = sshManager.hostKeyStore.storedFingerprint(id)
        lines += DiagnosticLine("Host key", stored != null, stored ?: "No accepted key on file — will show fingerprint on first connect")

        val c = client
        if (c != null) {
            val healthy = runCatching { c.health() }.getOrDefault(false)
            lines += DiagnosticLine("Companion health", healthy, if (healthy) "OK" else "Unreachable")
        } else {
            lines += DiagnosticLine("Companion health", false, "Not connected")
        }

        return lines
    }

    override suspend fun acceptHostKey(id: String, fingerprint: String) {
        sshManager.hostKeyStore.accept(id, fingerprint)
        _connections.update { list ->
            list.map { if (it.id == id) it.copy(hostKeyFingerprint = fingerprint) else it }
        }
    }

    // ── Projects ──────────────────────────────────────────────────────────────

    override fun projects(): Flow<List<Project>> = _projects
    override fun project(id: String): Flow<Project?> = _projects.map { it.find { p -> p.id == id } }
    override fun activeProjectId(): Flow<String?> = _activeProject

    override suspend fun upsertProject(project: Project) {
        _projects.update { list -> list.filter { it.id != project.id } + project }
        withClient { c ->
            runCatching { c.updateProject(project.id, project.name, project.folder) }
        }
    }

    override suspend fun setActiveProject(id: String?) {
        _activeProject.value = id
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    override fun sessions(): Flow<List<AgentSession>> =
        combine(_sessions, _filter, delegate.sessions()) { remote, state, demo ->
            if (client == null) demo
            else remote.filter { !it.archived && (state == null || matchesFilter(it.state, state)) }
                .sortedByDescending { it.updatedAt }
        }

    override fun session(id: String): Flow<AgentSession?> =
        combine(_sessions, delegate.session(id)) { remote, demo ->
            if (client == null) demo else remote.find { it.id == id }
        }

    override fun events(sessionId: String): Flow<List<SessionEvent>> =
        combine(_events, delegate.events(sessionId)) { remote, demo ->
            if (client == null) demo else remote[sessionId].orEmpty()
        }

    override fun streamingText(sessionId: String): Flow<String> =
        _streaming.map { it[sessionId].orEmpty() }

    override fun pendingPrompts(sessionId: String): Flow<List<PendingPrompt>> =
        _outbox.map { list -> list.filter { it.sessionId == sessionId } }

    override suspend fun loadHistory(sessionId: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext
        val detail = runCatching { c.sessionHistory(sessionId, HISTORY_LIMIT) }.getOrNull() ?: return@withContext
        // History must not move the cursor: events for other chats between
        // the cursor and this chat's latest event have not arrived yet.
        mergeEvents(detail.events, live = false)
    }

    override suspend fun slashCommands(sessionId: String): List<AgentCommand>? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        runCatching { c.commands(sessionId) }.getOrNull()?.commands?.map {
            AgentCommand(it.name, it.description, it.argumentHint, it.source)
        }
    }

    override suspend fun sessionStatus(sessionId: String): SessionStatus? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        val r = c.sessionStatus(sessionId)
        SessionStatus(
            provider = r.provider,
            model = r.model,
            effort = r.effort,
            mode = r.mode,
            state = r.state,
            workspacePath = r.workspacePath,
            branch = r.branch,
            providerSessionId = r.providerSessionId,
            running = r.running,
            version = r.info?.version.orEmpty(),
            permissionMode = r.info?.permissionMode.orEmpty(),
            mcpServers = r.info?.mcpServers.orEmpty().map { it.name to it.status },
            plugins = r.info?.plugins.orEmpty(),
            tools = r.info?.tools ?: 0,
            skills = r.info?.skills ?: 0,
            agents = r.info?.agents ?: 0,
            reported = r.info != null,
            costUsd = r.usage?.costUsd ?: 0.0,
            inputTokens = r.usage?.inputTokens ?: 0,
            outputTokens = r.usage?.outputTokens ?: 0,
            turns = r.usage?.turns ?: 0,
            contextTokens = r.usage?.contextTokens ?: 0,
            contextWindow = r.usage?.contextWindow ?: 0,
        )
    }

    override suspend fun resendPending(key: String) {
        val old = _outbox.value.find { it.key == key } ?: return
        // A new key: the old one is spent on the VM.
        val fresh = old.copy(key = UUID.randomUUID().toString(), state = PendingState.Sending, createdAt = System.currentTimeMillis())
        updateOutbox { list -> list.map { if (it.key == key) fresh else it } }
        deliver(fresh.key)
    }

    override suspend fun discardPending(key: String) {
        updateOutbox { list -> list.filter { it.key != key } }
    }

    override fun approvals(sessionId: String): Flow<List<ApprovalRequest>> =
        combine(_approvals, delegate.approvals(sessionId)) { remote, demo ->
            if (client == null) demo else remote.filter { it.sessionId == sessionId }
        }

    override fun changedFiles(sessionId: String): Flow<List<ChangedFile>> =
        combine(_events, delegate.changedFiles(sessionId)) { remote, demo ->
            if (client == null) demo
            else remote[sessionId].orEmpty().filter { it.type == "file_change" }
                .map { ChangedFile(it.text, "modified") }
        }

    override suspend fun createSession(draft: NewSession): AgentSession = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.createSession(draft)
        val req = CreateSessionRequest(
            projectId = draft.projectId,
            provider = draft.provider.name.lowercase(),
            title = draft.title,
            model = draft.model,
            workspace = if (draft.workspace == WorkspaceChoice.Shared) "shared" else "worktree",
        )
        val remote = c.createSession(req)
        val session = remote.toModel()
        _sessions.update { it + session }
        session
    }

    override suspend fun setSessionControls(sessionId: String, model: String, effort: String, mode: String) {
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext delegate.setSessionControls(sessionId, model, effort, mode)
            c.sessionSettings(sessionId, model, effort, mode)
            _sessions.update { list ->
                list.map {
                    if (it.id == sessionId) it.copy(model = model, effort = effort, mode = mode.ifBlank { "agent" }) else it
                }
            }
        }
    }

    /**
     * The prompt goes into an outbox first. If the phone is offline, or the
     * request dies on the way, it stays there and is retried with the same
     * idempotency key, so the VM runs it exactly once.
     */
    override suspend fun prompt(sessionId: String, text: String, idempotencyKey: String) {
        if (client == null) return delegate.prompt(sessionId, text, idempotencyKey)
        updateOutbox { list ->
            list.filter { it.key != idempotencyKey } +
                PendingPrompt(idempotencyKey, sessionId, text, PendingState.Sending, System.currentTimeMillis())
        }
        deliver(idempotencyKey)
    }

    private suspend fun deliver(key: String) = withContext(Dispatchers.IO) {
        val pending = _outbox.value.find { it.key == key } ?: return@withContext
        val c = client
        if (c == null || _link.value != LinkState.Live) {
            setPending(key, PendingState.Waiting)
            return@withContext
        }
        try {
            c.prompt(pending.sessionId, pending.text, pending.key)
            setPending(key, PendingState.Sent)
            _sessions.update { list ->
                list.map {
                    if (it.id == pending.sessionId) it.copy(state = SessionState.Running, latestLine = "You: ${pending.text}", updatedAt = System.currentTimeMillis()) else it
                }
            }
        } catch (e: CompanionException) {
            when (e.code) {
                "NETWORK" -> setPending(key, PendingState.Waiting)
                // Cursor and Codex take one prompt at a time; send it when the agent finishes.
                "BUSY" -> setPending(key, PendingState.Queued)
                // The VM recorded it but it never reached the agent; let the user resend.
                "NOT_DELIVERED" -> setPending(key, PendingState.Failed)
                else -> {
                    updateOutbox { list -> list.filter { it.key != key } }
                    throw e
                }
            }
        }
    }

    /** Every outbox change is written to disk before anything is sent. */
    private fun updateOutbox(change: (List<PendingPrompt>) -> List<PendingPrompt>) {
        synchronized(outboxLock) {
            _outbox.update(change)
            val file = outboxFile ?: return
            val json = profileJson.encodeToString(_outbox.value)
            // Write a temp file and swap it in, so a crash mid-write never leaves a half file.
            runCatching {
                val tmp = file.resolveSibling("${file.name}.tmp")
                tmp.writeText(json)
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            }.recoverCatching { file.writeText(json) }
                .onFailure { android.util.Log.w("PocketDev", "Could not save the outbox", it) }
        }
    }

    private fun loadOutbox() {
        val file = outboxFile ?: return
        val saved = runCatching { profileJson.decodeFromString<List<PendingPrompt>>(file.readText()) }.getOrNull() ?: return
        // Whether these reached the VM is unknown; retrying with the same key is safe,
        // as long as the VM still remembers the key. Very old ones need a decision.
        val cutoff = System.currentTimeMillis() - PENDING_MAX_AGE_MS
        _outbox.value = saved.map {
            it.copy(state = if (it.state == PendingState.Failed || it.createdAt < cutoff) PendingState.Failed else PendingState.Waiting)
        }
    }

    private fun setPending(key: String, state: PendingState) {
        updateOutbox { list -> list.map { if (it.key == key) it.copy(state = state) else it } }
    }

    private suspend fun flushOutbox() {
        val now = System.currentTimeMillis()
        // A sent prompt is normally removed when its event arrives; this covers a missed event.
        updateOutbox { list -> list.filter { it.state != PendingState.Sent || now - it.createdAt < SENT_TTL_MS } }
        for (pending in _outbox.value) {
            if (pending.state == PendingState.Waiting || pending.state == PendingState.Queued) {
                runCatching { deliver(pending.key) }
            }
        }
    }

    override suspend fun interrupt(sessionId: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.interrupt(sessionId)
        c.interrupt(sessionId)
        _sessions.update { list ->
            list.map { if (it.id == sessionId) it.copy(state = SessionState.Stopped) else it }
        }
    }

    override suspend fun resume(sessionId: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.resume(sessionId)
        c.resumeSession(sessionId)
        _sessions.update { list ->
            list.map { if (it.id == sessionId) it.copy(state = SessionState.Running) else it }
        }
    }

    override suspend fun close(sessionId: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.close(sessionId)
        c.closeSession(sessionId)
        _sessions.update { list ->
            list.map { if (it.id == sessionId) it.copy(state = SessionState.Stopped) else it }
        }
    }

    override suspend fun renameSession(sessionId: String, title: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.renameSession(sessionId, title)
        c.patchSession(sessionId, title = title)
        _sessions.update { list ->
            list.map { if (it.id == sessionId) it.copy(title = title) else it }
        }
    }

    override suspend fun archiveSession(sessionId: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.archiveSession(sessionId)
        c.patchSession(sessionId, archived = true)
        _sessions.update { list ->
            list.map { if (it.id == sessionId) it.copy(archived = true) else it }
        }
    }

    override suspend fun respond(approvalId: String, allow: Boolean) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.respond(approvalId, allow)
        val approval = _approvals.value.find { it.id == approvalId } ?: return@withContext
        c.permission(approval.sessionId, approvalId, allow)
        _approvals.update { list ->
            list.map { if (it.id == approvalId) it.copy(resolved = true, allowed = allow) else it }
        }
    }

    // ── Files ─────────────────────────────────────────────────────────────────

    override suspend fun listDir(projectId: String, path: String): List<FileEntry> =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext delegate.listDir(projectId, path)
            try {
                val listing = c.listFiles(projectId, path)
                val parent = path.trimEnd('/').ifBlank { listing.path }
                listing.entries.map { it.toModel(parent) }
            } catch (e: CompanionException) {
                if (e.code == "NETWORK") throw companionUnreachable() else throw e
            }
        }

    override suspend fun mkdir(projectId: String, path: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.mkdir(projectId, path)
        c.mkdir(projectId, path)
    }

    override suspend fun renamePath(projectId: String, path: String, newName: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.renamePath(projectId, path, newName)
        c.renamePath(projectId, path, newName)
    }

    override suspend fun movePath(projectId: String, path: String, destination: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.movePath(projectId, path, destination)
        c.movePath(projectId, path, destination)
    }

    override suspend fun copyPath(projectId: String, path: String, destination: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.copyPath(projectId, path, destination)
        c.copyPath(projectId, path, destination)
    }

    override suspend fun deletePaths(projectId: String, paths: List<String>) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.deletePaths(projectId, paths)
        c.deletePaths(projectId, paths)
    }

    // ── Editor buffers ────────────────────────────────────────────────────────

    override fun buffers(projectId: String): Flow<List<EditorBuffer>> =
        combine(_buffers, delegate.buffers(projectId)) { local, demo ->
            if (client == null) demo else local.filter { it.projectId == projectId }
        }

    override suspend fun openBuffer(projectId: String, path: String): EditorBuffer =
        withContext(Dispatchers.IO) {
            _buffers.value.find { it.projectId == projectId && it.path == path }?.let { return@withContext it }
            val c = client ?: return@withContext delegate.openBuffer(projectId, path)
            val content = c.fileContent(projectId, path)
            val buf = EditorBuffer(
                projectId = projectId,
                path = path,
                text = content.text,
                fingerprint = content.fingerprint,
                dirty = false,
                binary = content.binary,
            )
            _buffers.update { it + buf }
            buf
        }

    override suspend fun updateBuffer(projectId: String, path: String, text: String) {
        _buffers.update { list ->
            list.map {
                if (it.projectId == projectId && it.path == path) it.copy(text = text, dirty = true) else it
            }
        }
    }

    override suspend fun saveBuffer(projectId: String, path: String): SaveResult =
        withContext(Dispatchers.IO) {
            val buf = _buffers.value.find { it.projectId == projectId && it.path == path }
                ?: return@withContext SaveResult(false, "File not open")
            val c = client ?: return@withContext delegate.saveBuffer(projectId, path)
            val idKey = "save:$projectId:$path:${buf.fingerprint}"
            val result = runCatching {
                c.saveFile(
                    SaveFileRequest(projectId, path, buf.text, buf.fingerprint),
                    idKey,
                )
            }
            if (result.isFailure) {
                val ex = result.exceptionOrNull()
                if (ex is CompanionException && ex.code == "REMOTE_CHANGED") {
                    val fresh = runCatching { c.fileContent(projectId, path) }.getOrNull()
                    return@withContext SaveResult(
                        false,
                        "The file changed on the remote. ${ ex.message }",
                        conflict = true,
                        remoteText = fresh?.text ?: "",
                        remoteFingerprint = fresh?.fingerprint ?: "",
                    )
                }
                return@withContext SaveResult(false, ex?.message ?: "Save failed")
            }
            val fp = result.getOrNull()?.fingerprint ?: fingerprint(buf.text)
            _buffers.update { list ->
                list.map { if (it.path == path && it.projectId == projectId) it.copy(fingerprint = fp, dirty = false) else it }
            }
            SaveResult(true, "Saved")
        }

    override suspend fun reloadBuffer(projectId: String, path: String): EditorBuffer =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext delegate.reloadBuffer(projectId, path)
            val content = c.fileContent(projectId, path)
            val buf = EditorBuffer(projectId, path, content.text, content.fingerprint, false, content.binary)
            _buffers.update { list -> list.filter { !(it.projectId == projectId && it.path == path) } + buf }
            buf
        }

    override suspend fun saveCopy(projectId: String, path: String): SaveResult =
        withContext(Dispatchers.IO) {
            val buf = _buffers.value.find { it.projectId == projectId && it.path == path }
                ?: return@withContext SaveResult(false, "Nothing open")
            val c = client ?: return@withContext delegate.saveCopy(projectId, path)
            val copyPath = "$path.copy"
            c.saveFile(SaveFileRequest(projectId, copyPath, buf.text, ""), UUID.randomUUID().toString())
            SaveResult(true, "Saved a separate copy as $copyPath")
        }

    // ── Transfers ─────────────────────────────────────────────────────────────
    // Transfers use the companion file API; actual byte streaming would use SFTP
    // over the SSH session (deferred). For now the jobs are tracked client-side.

    override fun transfers(): Flow<List<TransferJob>> = delegate.transfers()

    override suspend fun enqueueUpload(
        projectId: String,
        sourceLabel: String,
        destinationPath: String,
        bytes: Long,
        collision: CollisionPolicy,
    ): String = delegate.enqueueUpload(projectId, sourceLabel, destinationPath, bytes, collision)

    override suspend fun enqueueDownload(projectId: String, path: String, destinationLabel: String): String =
        delegate.enqueueDownload(projectId, path, destinationLabel)

    override suspend fun cancelTransfer(id: String) = delegate.cancelTransfer(id)
    override suspend fun retryTransfer(id: String) = delegate.retryTransfer(id)

    // ── Git ───────────────────────────────────────────────────────────────────

    override suspend fun gitStatus(projectId: String): GitStatus = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.gitStatus(projectId)
        c.gitStatus(projectId).toModel()
    }

    override suspend fun gitDiff(projectId: String, path: String, base: DiffBase, commit: String): String =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext delegate.gitDiff(projectId, path, base, commit)
            c.gitDiff(projectId, path, base.name.lowercase(), commit)
        }

    override suspend fun gitLog(projectId: String): List<GitCommit> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.gitLog(projectId)
        c.gitLog(projectId, 30).map { GitCommit(it.sha, it.shortSha, it.author, it.at, it.subject) }
    }

    override suspend fun branches(projectId: String): List<BranchInfo> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.branches(projectId)
        c.branches(projectId).map { BranchInfo(it.name, it.current, it.remote) }
    }

    override suspend fun commit(
        projectId: String,
        paths: List<String>,
        message: String,
        idempotencyKey: String,
    ): GitOperationResult = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.commit(projectId, paths, message, idempotencyKey)
        val result = c.commit(CommitRequest(projectId, paths, message), idempotencyKey)
        GitOperationResult(result.ok, result.message)
    }

    override suspend fun push(projectId: String, idempotencyKey: String): GitOperationResult =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext delegate.push(projectId, idempotencyKey)
            val result = c.push(projectId, idempotencyKey)
            GitOperationResult(result.ok, result.message)
        }

    override suspend fun pull(projectId: String, idempotencyKey: String): GitOperationResult =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext delegate.pull(projectId, idempotencyKey)
            val result = c.pull(projectId, idempotencyKey)
            GitOperationResult(result.ok, result.message)
        }

    // ── Port mappings ─────────────────────────────────────────────────────────

    override fun mappings(): Flow<List<PortMapping>> = _mappings

    override suspend fun addMapping(projectId: String, label: String, remotePort: Int, phonePort: Int): PortMapping {
        val (actualPort, shifted) = PortSelector.findFree(phonePort)
        val mapping = PortMapping(
            id = UUID.randomUUID().toString().take(8),
            projectId = projectId,
            label = if (shifted) "$label (port $actualPort)" else label,
            remoteHost = "127.0.0.1",
            remotePort = remotePort,
            phonePort = actualPort,
            processState = ProcessState.Stopped,
            tunnelState = TunnelState.Closed,
            url = "http://127.0.0.1:$actualPort",
        )
        _mappings.update { it + mapping }
        return mapping
    }

    override suspend fun startMapping(id: String): PortMapping = withContext(Dispatchers.IO) {
        val mapping = _mappings.value.find { it.id == id }
            ?: return@withContext PortMapping(id, "", "", "127.0.0.1", 0, 0, ProcessState.Failed, TunnelState.Failed, "")

        if (!sshManager.isConnected) {
            val failed = mapping.copy(tunnelState = TunnelState.Failed, processState = ProcessState.Failed)
            _mappings.update { list -> list.map { if (it.id == id) failed else it } }
            return@withContext failed
        }

        return@withContext try {
            val actualPort = sshManager.addForward(mapping.remoteHost, mapping.remotePort, mapping.phonePort)
            val started = mapping.copy(
                phonePort = actualPort,
                tunnelState = TunnelState.Listening,
                processState = ProcessState.Running,
                url = "http://127.0.0.1:$actualPort",
            )
            _mappings.update { list -> list.map { if (it.id == id) started else it } }
            started
        } catch (e: Exception) {
            val failed = mapping.copy(tunnelState = TunnelState.Failed)
            _mappings.update { list -> list.map { if (it.id == id) failed else it } }
            failed
        }
    }

    override suspend fun stopMapping(id: String) = withContext(Dispatchers.IO) {
        val mapping = _mappings.value.find { it.id == id } ?: return@withContext
        sshManager.removeForward(mapping.phonePort)
        _mappings.update { list ->
            list.map { if (it.id == id) it.copy(tunnelState = TunnelState.Closed, processState = ProcessState.Stopped) else it }
        }
    }

    // ── Processes ─────────────────────────────────────────────────────────────

    override fun processes(projectId: String): Flow<List<ManagedProcess>> =
        combine(_processes, delegate.processes(projectId)) { local, demo ->
            if (client == null) demo else local.filter { it.projectId == projectId }
        }

    override suspend fun startProcess(projectId: String, label: String, command: String): ManagedProcess =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext delegate.startProcess(projectId, label, command)
            val remote = c.startProcess(StartProcessRequest(projectId, label, command))
            val proc = remote.toModel()
            _processes.update { it + proc }
            proc
        }

    override suspend fun stopProcess(id: String) = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.stopProcess(id)
        c.stopProcess(id)
        _processes.update { list ->
            list.map { if (it.id == id) it.copy(state = ProcessState.Stopped) else it }
        }
    }

    // ── Deploy ────────────────────────────────────────────────────────────────

    override fun recipes(projectId: String): Flow<List<DeploymentRecipe>> =
        combine(_recipes, delegate.recipes(projectId)) { local, demo ->
            if (client == null && local.none { it.projectId == projectId }) demo
            else local.filter { it.projectId == projectId }
        }

    override suspend fun saveRecipe(recipe: DeploymentRecipe) = withContext(Dispatchers.IO) {
        _recipes.update { list -> list.filter { it.id != recipe.id } + recipe }
        val c = client ?: return@withContext
        runCatching {
            c.createRecipe(recipe.toRemote())
        }
        Unit
    }

    override suspend fun startDeploy(
        projectId: String,
        recipeId: String,
        idempotencyKey: String,
    ): DeploymentRun = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext delegate.startDeploy(projectId, recipeId, idempotencyKey)
        val remote = c.startDeploy(projectId, recipeId, idempotencyKey)
        val run = remote.toModel()
        _runs.update { it + run }
        run
    }

    override fun deployRun(id: String): Flow<DeploymentRun?> =
        combine(_runs, delegate.deployRun(id)) { runs, demo ->
            if (client == null) demo else runs.find { it.id == id }
        }

    // ── Terminal ──────────────────────────────────────────────────────────────
    // Terminal I/O is carried over the companion WebSocket (/v1/pty/:sessionId).
    // Without a WebSocket client dependency we delegate to the companion via polling
    // for now; the snapshot reflects the last event output.

    override fun terminal(projectId: String): Flow<TerminalSnapshot> = delegate.terminal(projectId)

    override suspend fun terminalInput(projectId: String, data: String) {
        // Enqueue to companion PTY channel — forwarded via SSH session channel
        delegate.terminalInput(projectId, data)
    }

    override suspend fun terminalResize(projectId: String, cols: Int, rows: Int) {
        delegate.terminalResize(projectId, cols, rows)
    }

    // ── Session filter ────────────────────────────────────────────────────────

    override fun sessionFilter(): Flow<SessionState?> = _filter
    override suspend fun setSessionFilter(state: SessionState?) {
        _filter.value = state
        delegate.setSessionFilter(state)
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling(profile: ConnectionProfile) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val c = client ?: break
                val ok = runCatching { refreshAll(c, profile) }.isSuccess
                if (ok) {
                    pollFailures = 0
                    _link.value = LinkState.Live
                    val stalled = System.currentTimeMillis() - streamStartedAt > STREAM_OPEN_TIMEOUT_MS
                    if (!streamOpen && stalled) startEventStream(c)
                    flushOutbox()
                } else {
                    pollFailures++
                    if (pollFailures >= MAX_POLL_FAILURES) {
                        _link.value = LinkState.Reconnecting
                        wake.trySend(Unit)
                    }
                }
                delay(if (streamOpen) POLL_INTERVAL_MS else FAST_POLL_INTERVAL_MS)
            }
        }
    }

    // ── Live event stream ─────────────────────────────────────────────────────

    private fun startEventStream(c: CompanionClient) {
        closeEventStream()
        streamStartedAt = System.currentTimeMillis()
        eventSocket = c.openEventStream(
            after = lastEventSeq,
            onOpen = { if (client === c) streamOpen = true },
            onEvent = { event -> if (client === c) ingest(event) },
            onClosed = { if (client === c) streamOpen = false },
        )
    }

    private fun closeEventStream() {
        streamOpen = false
        eventSocket?.cancel()
        eventSocket = null
    }

    /**
     * Starts the stream at the companion's current head the first time we see
     * a VM, instead of downloading its whole history. Chats load their own
     * history when opened.
     */
    private fun resetEventCursor(connectionId: String, c: CompanionClient) {
        val head = runCatching { c.healthInfo().latestSeq }.getOrNull() ?: return
        synchronized(eventLock) {
            if (seqConnectionId == connectionId && head >= lastEventSeq) return
            // After the app was killed, carry on from where it stopped reading so
            // everything that happened meanwhile is replayed.
            val saved = savedCursor
            if (seqConnectionId == null && saved?.connectionId == connectionId && saved.seq <= head) {
                seqConnectionId = connectionId
                lastEventSeq = saved.seq
                return
            }
            seqConnectionId = connectionId
            lastEventSeq = head
            _events.value = emptyMap()
            _streaming.value = emptyMap()
        }
    }

    private fun ingest(event: RemoteEvent) {
        if (event.seq == 0L) {
            if (event.type == "delta") {
                val text = rawEventText(event.payload)
                _streaming.update { it + (event.sessionId to it[event.sessionId].orEmpty() + text) }
            }
            return
        }
        mergeEvents(listOf(event))
    }

    private fun mergeEvents(remote: List<RemoteEvent>, live: Boolean = true) {
        val stored = remote.filter { it.seq > 0 }
        if (stored.isEmpty()) return
        synchronized(eventLock) {
            val fresh = if (live) stored.filter { it.seq > lastEventSeq } else emptyList()
            if (fresh.isNotEmpty()) {
                lastEventSeq = fresh.maxOf { it.seq }
                saveCursor(force = false)
            }
            val mapped = stored.mapNotNull { event ->
                val text = readableEventText(event.payload)
                if (event.type == "output" && text.isBlank()) null
                else SessionEvent(event.seq, event.sessionId, event.type, text, event.at)
            }
            _events.update { map ->
                val next = map.toMutableMap()
                mapped.groupBy { it.sessionId }.forEach { (sessionId, added) ->
                    val merged = (next[sessionId].orEmpty() + added).distinctBy { it.seq }.sortedBy { it.seq }
                    next[sessionId] = merged.takeLast(MAX_EVENTS_PER_SESSION)
                }
                next
            }
            for (event in fresh) applyLiveEvent(event)
        }
    }

    private fun saveCursor(force: Boolean) {
        val id = seqConnectionId ?: return
        val now = System.currentTimeMillis()
        if (!force && now - cursorSavedAt < CURSOR_SAVE_INTERVAL_MS) return
        cursorSavedAt = now
        val cursor = SavedCursor(id, lastEventSeq)
        savedCursor = cursor
        runCatching { cursorFile?.writeText(profileJson.encodeToString(cursor)) }
    }

    private fun applyLiveEvent(event: RemoteEvent) {
        when (event.type) {
            "assistant" -> _streaming.update { it - event.sessionId }
            "prompt" -> {
                val key = ((event.payload as? JsonObject)?.get("key") as? JsonPrimitive)?.contentOrNull
                val text = readableEventText(event.payload)
                updateOutbox { list ->
                    // Older companions don't echo the key; fall back to the text.
                    val match = if (key != null) list.firstOrNull { it.key == key }
                    else list.firstOrNull { it.sessionId == event.sessionId && it.text.trim() == text }
                    if (match == null) list else list - match
                }
            }
            "state_change" -> {
                val state = (event.payload as? JsonObject)?.get("state")?.jsonPrimitive?.contentOrNull ?: return
                val mapped = sessionState(state)
                if (mapped != SessionState.Running && mapped != SessionState.Starting) {
                    _streaming.update { it - event.sessionId }
                }
                _sessions.update { list ->
                    list.map { if (it.id == event.sessionId) it.copy(state = mapped, updatedAt = event.at) else it }
                }
            }
        }
    }

    // ── Reconnect supervisor ──────────────────────────────────────────────────

    /**
     * Keeps [id] connected until [disconnect] is called. Work on the VM never
     * depends on this: the companion runs agents on its own, and the event
     * stream replays whatever happened while the phone was away.
     */
    fun keepAlive(id: String) {
        desiredConnectionId = id
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch { supervise() }
    }

    fun isKeepingAlive(id: String): Boolean = desiredConnectionId == id && supervisorJob?.isActive == true

    /** Called when Android reports a usable network, to retry right away. */
    fun nudge() {
        wake.trySend(Unit)
    }

    private suspend fun supervise() {
        var backoff = BACKOFF_INITIAL_MS
        while (true) {
            val id = desiredConnectionId ?: return
            val healthy = client != null && sshManager.isConnected && pollFailures < MAX_POLL_FAILURES
            if (healthy) {
                backoff = BACKOFF_INITIAL_MS
                withTimeoutOrNull(SUPERVISE_INTERVAL_MS) { wake.receive() }
                continue
            }
            _link.value = LinkState.Reconnecting
            updatePhase(id, ConnectionPhase.Connecting, "Reconnecting…")
            val result = runCatching { connect(id) }
                .getOrElse { ConnectResult(false, it.message ?: "Network error") }
            if (result.ok) {
                backoff = BACKOFF_INITIAL_MS
                continue
            }
            if (desiredConnectionId == null) return
            if (isNonRetryable(result.message)) {
                desiredConnectionId = null
                _link.value = LinkState.Offline
                return
            }
            updatePhase(id, ConnectionPhase.Connecting, "Offline · retrying in ${backoff / 1000}s")
            withTimeoutOrNull(backoff) { wake.receive() }
            backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
        }
    }

    private suspend fun refreshAll(c: CompanionClient, profile: ConnectionProfile) {
        // Projects
        val remoteProjects = c.listProjects()
        _projects.update { existing ->
            remoteProjects.map { rp ->
                val prev = existing.find { it.id == rp.id }
                Project(
                    id = rp.id,
                    connectionId = profile.id,
                    name = rp.name,
                    folder = rp.folder,
                    branch = rp.branch,
                    devCommand = rp.devCommand,
                    testCommand = rp.testCommand,
                    ports = rp.ports,
                    activeChats = prev?.activeChats ?: 0,
                    previewRunning = prev?.previewRunning ?: false,
                )
            }
        }

        // Sessions (for active project)
        val remoteSessions = c.listSessions()
        _sessions.value = remoteSessions.map { it.toModel() }

        // The live stream normally delivers events; poll only while it is down.
        if (!streamOpen) mergeEvents(c.events(lastEventSeq))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updatePhase(id: String, phase: ConnectionPhase, status: String) {
        _connections.update { list ->
            list.map { if (it.id == id) it.copy(phase = phase, statusLine = status, lastConnectedAt = if (phase == ConnectionPhase.Connected) System.currentTimeMillis() else it.lastConnectedAt) else it }
        }
    }

    private suspend fun <T> withClient(block: suspend (CompanionClient) -> T): T? {
        val c = client ?: return null
        return block(c)
    }

    private fun rawEventText(payload: kotlinx.serialization.json.JsonElement): String =
        ((payload as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun readableEventText(payload: kotlinx.serialization.json.JsonElement): String {
        val raw = when (payload) {
            is JsonObject -> {
                val text = payload["text"]
                if (text is JsonPrimitive) text.contentOrNull.orEmpty() else payload.toString()
            }
            is JsonPrimitive -> payload.contentOrNull.orEmpty()
            else -> ""
        }
        return raw
            .replace(Regex("\u001B\\].*?(?:\u0007|\u001B\\\\)"), "")
            .replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
            .replace(Regex("\u001B[@-_]"), "")
            .replace("\r", "")
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")
            .trim()
    }

    private fun companionUnreachable(): CompanionException =
        CompanionException("NETWORK", "Companion is unreachable. Check your SSH connection.", false)

    private fun matchesFilter(actual: SessionState, filter: SessionState): Boolean {
        if (filter == SessionState.Running) return actual == SessionState.Running || actual == SessionState.Starting
        return actual == filter
    }

    private fun fingerprint(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun RemoteSession.toModel() = AgentSession(
        id = id,
        projectId = projectId,
        provider = when (provider.lowercase()) {
            "claude" -> Provider.Claude
            "cursor", "agent" -> Provider.Cursor
            "codex"  -> Provider.Codex
            else     -> Provider.Terminal
        },
        title = title,
        model = model,
        effort = effort,
        mode = mode.ifBlank { "agent" },
        workspace = if (workspace == "shared") WorkspaceChoice.Shared else WorkspaceChoice.Worktree,
        workspacePath = workspacePath,
        branch = branch,
        state = sessionState(state),
        connectivity = when (connectivity.lowercase()) {
            "reconnecting" -> Connectivity.Reconnecting
            "offline"      -> Connectivity.Offline
            else           -> Connectivity.Online
        },
        providerSessionId = providerSessionId.orEmpty(),
        latestLine = latestLine,
        archived = archived,
        updatedAt = updatedAt,
    )

    private fun sessionState(state: String) = when (state.lowercase()) {
        "starting"       -> SessionState.Starting
        "running"        -> SessionState.Running
        "awaiting_input" -> SessionState.AwaitingInput
        "completed"      -> SessionState.Completed
        "failed"         -> SessionState.Failed
        "stopped"        -> SessionState.Stopped
        else             -> SessionState.Queued
    }

    private fun RemoteFileEntry.toModel(parent: String) = FileEntry(
        name = name,
        path = path.ifBlank { if (parent.isBlank() || parent == ".") name else "$parent/$name" },
        directory = directory || type == "directory",
        symlink = symlink || type == "symlink",
        bytes = if (bytes != 0L) bytes else size,
        modifiedAt = modifiedAt,
        mode = mode,
    )

    private fun RemoteGitStatus.toModel() = GitStatus(
        branch = branch,
        // Lists key rows by path, and duplicate keys crash them.
        files = files.distinctBy { it.path }.map { GitFile(it.path, it.status, it.staged) },
        ahead = ahead,
        behind = behind,
    )

    private fun RemoteProcess.toModel() = ManagedProcess(
        id = id,
        projectId = projectId,
        label = label,
        command = command,
        state = when (state.lowercase()) {
            "running"  -> ProcessState.Running
            "stopped"  -> ProcessState.Stopped
            "failed"   -> ProcessState.Failed
            else       -> ProcessState.Starting
        },
        log = log,
    )

    private fun RemoteDeployRun.toModel() = DeploymentRun(
        id = id,
        recipeId = recipeId,
        projectId = projectId,
        state = when (state.lowercase()) {
            "running" -> ProcessState.Running
            "failed"  -> ProcessState.Failed
            else      -> ProcessState.Stopped
        },
        log = log,
        startedAt = startedAt,
    )

    private fun DeploymentRecipe.toRemote() = RemoteRecipe(
        id = id,
        projectId = projectId,
        name = name,
        command = command,
        workingDirectory = workingDirectory,
        target = target,
        healthUrl = healthUrl,
    )

    companion object {
        private const val POLL_INTERVAL_MS = 4_000L
        private const val FAST_POLL_INTERVAL_MS = 2_000L
        private const val MAX_POLL_FAILURES = 3
        private const val SUPERVISE_INTERVAL_MS = 3_000L
        private const val BACKOFF_INITIAL_MS = 2_000L
        private const val BACKOFF_MAX_MS = 30_000L
        private const val SENT_TTL_MS = 20_000L
        private const val STREAM_OPEN_TIMEOUT_MS = 8_000L
        private const val CURSOR_SAVE_INTERVAL_MS = 2_000L
        private const val PENDING_MAX_AGE_MS = 25L * 24 * 3600 * 1000
        private const val HISTORY_LIMIT = 400
        private const val MAX_EVENTS_PER_SESSION = 1_500

        fun isNonRetryable(message: String): Boolean =
            message.startsWith("Unknown host key") ||
                message == "Changed host key" ||
                message == "Key rejected" ||
                message.startsWith("Sign in") ||
                message.startsWith("Import the SSH") ||
                message.startsWith("Enter the")
    }
}
