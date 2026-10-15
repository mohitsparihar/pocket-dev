package dev.pocket.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class Provider { Claude, Cursor, Codex, Terminal }

@Serializable
enum class SessionState {
    Queued, Starting, Running, AwaitingInput, Completed, Failed, Stopped,
}

@Serializable
enum class Connectivity { Online, Reconnecting, Offline }

@Serializable
enum class ConnectionKind { DirectSsh, GoogleCloud, Demo }

@Serializable
enum class ConnectionPhase { Disconnected, Connecting, Connected, Failed }

@Serializable
enum class WorkspaceChoice { Worktree, Shared }

@Serializable
enum class TransferDirection { Upload, Download }

@Serializable
enum class TransferState {
    Queued, Transferring, Disconnected, Completed, Failed, Cancelled,
}

@Serializable
enum class CollisionPolicy { Skip, KeepBoth, Replace }

@Serializable
enum class DiffBase { Worktree, Index, Commit }

@Serializable
enum class ProcessState { Starting, Running, Stopped, Failed }

@Serializable
enum class TunnelState { Closed, Listening, Failed }

@Serializable
data class ConnectionProfile(
    val id: String,
    val name: String,
    val kind: ConnectionKind,
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val project: String = "",
    val zone: String = "",
    val instance: String = "",
    val companionHost: String = "127.0.0.1",
    val companionPort: Int = 8787,
    val setupCode: String = "",
    val phase: ConnectionPhase = ConnectionPhase.Disconnected,
    val statusLine: String = "Disconnected",
    val lastConnectedAt: Long? = null,
    val hostKeyFingerprint: String = "",
)

@Serializable
data class DiagnosticLine(
    val name: String,
    val ok: Boolean,
    val detail: String,
)

@Serializable
data class ConnectResult(
    val ok: Boolean,
    val message: String,
    val recovery: String = "",
)

@Serializable
data class Project(
    val id: String,
    val connectionId: String,
    val name: String,
    val folder: String,
    val branch: String,
    val devCommand: String = "",
    val testCommand: String = "",
    val ports: List<Int> = emptyList(),
    val activeChats: Int = 0,
    val previewRunning: Boolean = false,
)

@Serializable
data class AgentSession(
    val id: String,
    val projectId: String,
    val provider: Provider,
    val title: String,
    val model: String = "",
    val effort: String = "",
    val mode: String = "agent",
    val workspace: WorkspaceChoice = WorkspaceChoice.Worktree,
    val workspacePath: String = "",
    val branch: String = "",
    val state: SessionState = SessionState.Queued,
    val connectivity: Connectivity = Connectivity.Online,
    val providerSessionId: String = "",
    val latestLine: String = "",
    val archived: Boolean = false,
    val updatedAt: Long = 0L,
)

@Serializable
data class SessionEvent(
    val seq: Long,
    val sessionId: String,
    val type: String,
    val text: String,
    val at: Long,
)

/** A slash command the agent itself understands, as reported by the companion. */
data class AgentCommand(
    val name: String,
    val description: String,
    val argumentHint: String = "",
    val source: String = "built-in",
)

/** What /status shows: the chat's settings, the agent's environment, and usage. */
data class SessionStatus(
    val provider: String,
    val model: String,
    val effort: String,
    val mode: String,
    val state: String,
    val workspacePath: String,
    val branch: String,
    val providerSessionId: String,
    val running: Boolean,
    val version: String = "",
    val permissionMode: String = "",
    val mcpServers: List<Pair<String, String>> = emptyList(),
    val plugins: List<String> = emptyList(),
    val tools: Int = 0,
    val skills: Int = 0,
    val agents: Int = 0,
    /** False until the agent has replied once; environment details come from that run. */
    val reported: Boolean = false,
    val costUsd: Double = 0.0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val turns: Int = 0,
    val contextTokens: Long = 0,
    val contextWindow: Long = 0,
)

/** Whether the phone can currently reach the companion. */
enum class LinkState { Offline, Live, Reconnecting }

enum class PendingState { Sending, Waiting, Queued, Sent }

/**
 * A prompt the phone has accepted but the companion has not confirmed yet.
 * It is retried with the same idempotency key, so it runs at most once.
 */
data class PendingPrompt(
    val key: String,
    val sessionId: String,
    val text: String,
    val state: PendingState,
    val createdAt: Long,
)

@Serializable
data class ApprovalRequest(
    val id: String,
    val sessionId: String,
    val action: String,
    val detail: String,
    val resolved: Boolean = false,
    val allowed: Boolean? = null,
)

@Serializable
data class ChangedFile(
    val path: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
)

@Serializable
data class NewSession(
    val projectId: String,
    val provider: Provider,
    val title: String,
    val model: String = "",
    val workspace: WorkspaceChoice = WorkspaceChoice.Worktree,
)

@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val symlink: Boolean = false,
    val bytes: Long = 0L,
    val modifiedAt: Long = 0L,
    val mode: String = "",
)

@Serializable
data class EditorBuffer(
    val projectId: String,
    val path: String,
    val text: String,
    val fingerprint: String,
    val dirty: Boolean,
    val binary: Boolean = false,
)

@Serializable
data class SaveResult(
    val ok: Boolean,
    val message: String,
    val conflict: Boolean = false,
    val remoteText: String = "",
    val remoteFingerprint: String = "",
)

@Serializable
data class TransferJob(
    val id: String,
    val projectId: String,
    val direction: TransferDirection,
    val source: String,
    val destination: String,
    val bytesTotal: Long,
    val bytesDone: Long,
    val state: TransferState,
    val message: String = "",
)

@Serializable
data class GitFile(
    val path: String,
    val status: String,
    val staged: Boolean = false,
)

@Serializable
data class GitStatus(
    val branch: String,
    val files: List<GitFile>,
    val ahead: Int = 0,
    val behind: Int = 0,
)

@Serializable
data class BranchInfo(
    val name: String,
    val current: Boolean,
)

@Serializable
data class GitOperationResult(
    val ok: Boolean,
    val message: String,
)

@Serializable
data class PortMapping(
    val id: String,
    val projectId: String,
    val label: String,
    val remoteHost: String,
    val remotePort: Int,
    val phonePort: Int,
    val processState: ProcessState,
    val tunnelState: TunnelState,
    val url: String,
)

@Serializable
data class ManagedProcess(
    val id: String,
    val projectId: String,
    val label: String,
    val command: String,
    val state: ProcessState,
    val log: String = "",
)

@Serializable
data class DeploymentRecipe(
    val id: String,
    val projectId: String,
    val name: String,
    val command: String,
    val workingDirectory: String,
    val target: String,
    val healthUrl: String = "",
)

@Serializable
data class DeploymentRun(
    val id: String,
    val recipeId: String,
    val projectId: String,
    val state: ProcessState,
    val log: String,
    val startedAt: Long,
)

@Serializable
data class TerminalSnapshot(
    val projectId: String,
    val output: String,
    val alive: Boolean,
)
