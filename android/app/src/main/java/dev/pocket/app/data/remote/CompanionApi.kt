package dev.pocket.app.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val recoverable: Boolean = false,
    val details: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
)

@Serializable
data class ApiErrorEnvelope(val error: ApiError)

class CompanionException(val code: String, message: String, val recoverable: Boolean = false) :
    Exception(message)

@Serializable
data class HealthResponse(val ok: Boolean, val version: String = "")

@Serializable
data class PairRequest(val setupCode: String)

@Serializable
data class PairResponse(val token: String)

@Serializable
data class RemoteProject(
    val id: String,
    val name: String,
    val folder: String,
    val branch: String = "",
    val devCommand: String = "",
    val testCommand: String = "",
    val ports: List<Int> = emptyList(),
)

@Serializable
data class CreateProjectRequest(val name: String, val folder: String)

@Serializable
data class UpdateProjectRequest(val name: String? = null, val folder: String? = null)

@Serializable
data class RemoteSession(
    val id: String,
    val projectId: String,
    val provider: String,
    val title: String = "",
    val model: String = "",
    val effort: String = "",
    val mode: String = "agent",
    val workspace: String = "worktree",
    val workspacePath: String = "",
    val branch: String = "",
    val state: String = "queued",
    val connectivity: String = "online",
    // null until the provider reports its own session id.
    val providerSessionId: String? = null,
    val latestLine: String = "",
    val archived: Boolean = false,
    val updatedAt: Long = 0L,
)

@Serializable
data class RemoteMcpServer(val name: String = "", val status: String = "")

@Serializable
data class RemoteProviderInfo(
    val version: String = "",
    val cwd: String = "",
    val permissionMode: String = "",
    val mcpServers: List<RemoteMcpServer> = emptyList(),
    val plugins: List<String> = emptyList(),
    val tools: Int = 0,
    val skills: Int = 0,
    val agents: Int = 0,
)

@Serializable
data class RemoteUsage(
    val costUsd: Double = 0.0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val turns: Int = 0,
    val contextTokens: Long = 0,
    val contextWindow: Long = 0,
)

@Serializable
data class RemoteSessionStatus(
    val provider: String = "",
    val model: String = "",
    val effort: String = "",
    val mode: String = "agent",
    val state: String = "",
    val workspacePath: String = "",
    val branch: String = "",
    val providerSessionId: String = "",
    val running: Boolean = false,
    val info: RemoteProviderInfo? = null,
    val usage: RemoteUsage? = null,
)

@Serializable
data class RemoteCommand(
    val name: String,
    val description: String = "",
    val argumentHint: String = "",
    val source: String = "built-in",
)

@Serializable
data class RemoteCommandList(
    val provider: String = "",
    val reported: Boolean = false,
    val commands: List<RemoteCommand> = emptyList(),
)

@Serializable
data class RemoteHealth(
    val ok: Boolean = false,
    val latestSeq: Long = 0L,
)

@Serializable
data class RemoteSessionDetail(
    val id: String,
    val events: List<RemoteEvent> = emptyList(),
)

@Serializable
data class CreateSessionRequest(
    val projectId: String,
    val provider: String,
    val title: String,
    val model: String = "",
    val workspace: String = "worktree",
)

@Serializable
data class PromptRequest(val text: String)

@Serializable
data class SessionSettingsRequest(
    val model: String = "",
    val effort: String = "",
    val mode: String = "agent",
)

@Serializable
data class PermissionRequest(val approvalId: String, val allow: Boolean)

@Serializable
data class PatchSessionRequest(val title: String? = null, val archived: Boolean? = null)

@Serializable
data class RemoteEvent(
    val seq: Long,
    val sessionId: String,
    val type: String,
    val payload: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonNull,
    val at: Long = 0L,
)

@Serializable
data class RemoteFileListing(
    val path: String = "",
    val entries: List<RemoteFileEntry> = emptyList(),
)

@Serializable
data class RemoteFileEntry(
    val name: String,
    val path: String = "",
    val type: String = "",
    val directory: Boolean = false,
    val symlink: Boolean = false,
    val size: Long = 0L,
    val bytes: Long = 0L,
    val modifiedAt: Long = 0L,
    val mode: String = "",
)

@Serializable
data class RemoteFileContent(
    val path: String,
    val text: String = "",
    val fingerprint: String = "",
    val binary: Boolean = false,
    val truncated: Boolean = false,
)

@Serializable
data class SaveFileRequest(
    val projectId: String,
    val path: String,
    val text: String,
    val expectedFingerprint: String,
)

@Serializable
data class SaveFileResponse(
    val ok: Boolean = true,
    val fingerprint: String = "",
)

@Serializable
data class MkdirRequest(val projectId: String, val path: String)

@Serializable
data class RenameRequest(val projectId: String, val path: String, val newName: String)

@Serializable
data class MoveRequest(val projectId: String, val path: String, val destination: String)

@Serializable
data class CopyRequest(val projectId: String, val path: String, val destination: String)

@Serializable
data class DeleteRequest(val projectId: String, val paths: List<String>)

@Serializable
data class RemoteGitStatus(
    val branch: String = "",
    val files: List<RemoteGitFile> = emptyList(),
    val ahead: Int = 0,
    val behind: Int = 0,
)

@Serializable
data class RemoteGitFile(val path: String, val status: String, val staged: Boolean = false)

@Serializable
data class RemoteBranchInfo(val name: String, val current: Boolean = false, val remote: Boolean = false)

@Serializable
data class RemoteBranchList(val branches: List<RemoteBranchInfo> = emptyList())

@Serializable
data class RemoteDiff(val diff: String = "")

@Serializable
data class RemoteCommit(
    val sha: String = "",
    val shortSha: String = "",
    val author: String = "",
    val at: Long = 0L,
    val subject: String = "",
)

@Serializable
data class CommitRequest(val projectId: String, val paths: List<String>, val message: String)

@Serializable
data class PushPullRequest(val projectId: String)

@Serializable
data class RemoteGitResult(val ok: Boolean = false, val message: String = "")

@Serializable
data class RemoteProcess(
    val id: String,
    val projectId: String,
    val label: String,
    val command: String,
    val state: String,
    val log: String = "",
)

@Serializable
data class StartProcessRequest(val projectId: String, val label: String, val command: String)

@Serializable
data class RemoteRecipe(
    val id: String = "",
    val projectId: String,
    val name: String,
    val command: String,
    val workingDirectory: String,
    val target: String,
    val healthUrl: String = "",
)

@Serializable
data class StartDeployRequest(val projectId: String, val recipeId: String)

@Serializable
data class RemoteDeployRun(
    val id: String,
    val recipeId: String,
    val projectId: String,
    val state: String,
    val log: String,
    val startedAt: Long,
)

@Serializable
data class RemoteCapabilities(
    val providers: List<String> = emptyList(),
    val pty: Boolean = false,
    val git: Boolean = false,
)
