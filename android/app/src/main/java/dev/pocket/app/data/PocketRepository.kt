package dev.pocket.app.data

import dev.pocket.app.model.AgentCommand
import dev.pocket.app.model.AgentSession
import dev.pocket.app.model.ApprovalRequest
import dev.pocket.app.model.BranchInfo
import dev.pocket.app.model.ChangedFile
import dev.pocket.app.model.CollisionPolicy
import dev.pocket.app.model.ConnectResult
import dev.pocket.app.model.ConnectionProfile
import dev.pocket.app.model.DeploymentRecipe
import dev.pocket.app.model.DeploymentRun
import dev.pocket.app.model.DiagnosticLine
import dev.pocket.app.model.DiffBase
import dev.pocket.app.model.EditorBuffer
import dev.pocket.app.model.FileEntry
import dev.pocket.app.model.GitOperationResult
import dev.pocket.app.model.GitCommit
import dev.pocket.app.model.GitStatus
import dev.pocket.app.model.LinkState
import dev.pocket.app.model.PendingPrompt
import dev.pocket.app.model.ManagedProcess
import dev.pocket.app.model.NewSession
import dev.pocket.app.model.PortMapping
import dev.pocket.app.model.Project
import dev.pocket.app.model.SaveResult
import dev.pocket.app.model.SessionEvent
import dev.pocket.app.model.SessionState
import dev.pocket.app.model.SessionStatus
import dev.pocket.app.model.TerminalSnapshot
import dev.pocket.app.model.TransferJob
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface PocketRepository {
    fun connections(): Flow<List<ConnectionProfile>>
    fun activeConnection(): Flow<ConnectionProfile?>
    suspend fun upsertConnection(profile: ConnectionProfile)
    suspend fun deleteConnection(id: String)
    suspend fun connect(id: String): ConnectResult
    suspend fun disconnect()
    suspend fun testConnection(id: String): List<DiagnosticLine>
    suspend fun acceptHostKey(id: String, fingerprint: String)
    suspend fun saveSshSecret(connectionId: String, privateKeyPem: ByteArray?, password: String?)
    fun googleAccount(): Flow<String?>
    suspend fun signInGoogle(context: Context): ConnectResult

    fun projects(): Flow<List<Project>>
    fun project(id: String): Flow<Project?>
    fun activeProjectId(): Flow<String?>
    suspend fun upsertProject(project: Project)
    suspend fun setActiveProject(id: String?)

    fun sessions(): Flow<List<AgentSession>>
    fun session(id: String): Flow<AgentSession?>
    fun events(sessionId: String): Flow<List<SessionEvent>>

    /** Text the agent is writing right now, before the full message is stored. */
    fun streamingText(sessionId: String): Flow<String> = flowOf("")

    /** Prompts that are on their way to the VM. */
    fun pendingPrompts(sessionId: String): Flow<List<PendingPrompt>> = flowOf(emptyList())

    fun linkState(): Flow<LinkState> = flowOf(LinkState.Live)

    /** Loads the stored history of a chat when it is opened. */
    suspend fun loadHistory(sessionId: String) {}
    suspend fun discardPending(key: String) {}
    suspend fun resendPending(key: String) {}

    /** Slash commands the chat's agent supports on the VM, or null when unknown (offline, demo). */
    suspend fun slashCommands(sessionId: String): List<AgentCommand>? = null

    /** Details for /status, or null when the VM can't be reached. */
    suspend fun sessionStatus(sessionId: String): SessionStatus? = null
    fun approvals(sessionId: String): Flow<List<ApprovalRequest>>
    fun changedFiles(sessionId: String): Flow<List<ChangedFile>>
    suspend fun createSession(draft: NewSession): AgentSession
    suspend fun prompt(sessionId: String, text: String, idempotencyKey: String)
    suspend fun setSessionControls(sessionId: String, model: String, effort: String, mode: String)
    suspend fun interrupt(sessionId: String)
    suspend fun resume(sessionId: String)
    suspend fun close(sessionId: String)
    suspend fun renameSession(sessionId: String, title: String)
    suspend fun archiveSession(sessionId: String)
    suspend fun respond(approvalId: String, allow: Boolean)

    suspend fun listDir(projectId: String, path: String): List<FileEntry>
    suspend fun mkdir(projectId: String, path: String)
    suspend fun renamePath(projectId: String, path: String, newName: String)
    suspend fun movePath(projectId: String, path: String, destination: String)
    suspend fun copyPath(projectId: String, path: String, destination: String)
    suspend fun deletePaths(projectId: String, paths: List<String>)

    fun buffers(projectId: String): Flow<List<EditorBuffer>>
    suspend fun openBuffer(projectId: String, path: String): EditorBuffer
    suspend fun updateBuffer(projectId: String, path: String, text: String)
    suspend fun saveBuffer(projectId: String, path: String): SaveResult
    suspend fun reloadBuffer(projectId: String, path: String): EditorBuffer
    suspend fun saveCopy(projectId: String, path: String): SaveResult

    fun transfers(): Flow<List<TransferJob>>
    suspend fun enqueueUpload(
        projectId: String,
        sourceLabel: String,
        destinationPath: String,
        bytes: Long,
        collision: CollisionPolicy,
    ): String
    suspend fun enqueueDownload(projectId: String, path: String, destinationLabel: String): String
    suspend fun cancelTransfer(id: String)
    suspend fun retryTransfer(id: String)

    suspend fun gitStatus(projectId: String): GitStatus
    suspend fun gitDiff(projectId: String, path: String, base: DiffBase, commit: String): String
    suspend fun branches(projectId: String): List<BranchInfo>
    suspend fun gitLog(projectId: String): List<GitCommit> = emptyList()
    suspend fun commit(projectId: String, paths: List<String>, message: String, idempotencyKey: String): GitOperationResult
    suspend fun push(projectId: String, idempotencyKey: String): GitOperationResult
    suspend fun pull(projectId: String, idempotencyKey: String): GitOperationResult

    fun mappings(): Flow<List<PortMapping>>
    suspend fun addMapping(projectId: String, label: String, remotePort: Int, phonePort: Int): PortMapping
    suspend fun startMapping(id: String): PortMapping
    suspend fun stopMapping(id: String)

    fun processes(projectId: String): Flow<List<ManagedProcess>>
    suspend fun startProcess(projectId: String, label: String, command: String): ManagedProcess
    suspend fun stopProcess(id: String)

    fun recipes(projectId: String): Flow<List<DeploymentRecipe>>
    suspend fun saveRecipe(recipe: DeploymentRecipe)
    suspend fun startDeploy(projectId: String, recipeId: String, idempotencyKey: String): DeploymentRun
    fun deployRun(id: String): Flow<DeploymentRun?>

    fun terminal(projectId: String): Flow<TerminalSnapshot>
    suspend fun terminalInput(projectId: String, data: String)
    suspend fun terminalResize(projectId: String, cols: Int, rows: Int)

    fun sessionFilter(): Flow<SessionState?>
    suspend fun setSessionFilter(state: SessionState?)
}
