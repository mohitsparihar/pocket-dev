package dev.pocket.app.data.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

class CompanionClient(
    private val baseUrl: String,
    @Volatile var token: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Pings detect a dead tunnel quickly; reads have no timeout because the
    // stream can be quiet for a long time while an agent works.
    private val streamHttp = http.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // ── Health ──────────────────────────────────────────────────────────────

    fun health(): Boolean = runCatching {
        val resp = get("/v1/health", auth = false)
        resp.use { it.isSuccessful }
    }.getOrDefault(false)

    fun healthInfo(): RemoteHealth = get("/v1/health", auth = false).use { parseBody(it) }

    // ── Pairing ─────────────────────────────────────────────────────────────

    fun pair(setupCode: String): String {
        val resp = post("/v1/pair", PairRequest(setupCode), auth = false)
        return resp.use { parseBody<PairResponse>(it).token }
    }

    // ── Projects ─────────────────────────────────────────────────────────────

    fun listProjects(): List<RemoteProject> =
        get("/v1/projects").use { parseBody(it) }

    fun createProject(name: String, folder: String): RemoteProject =
        post("/v1/projects", CreateProjectRequest(name, folder)).use { parseBody(it) }

    fun getProject(id: String): RemoteProject =
        get("/v1/projects/$id").use { parseBody(it) }

    fun updateProject(id: String, name: String? = null, folder: String? = null): RemoteProject =
        patch("/v1/projects/$id", UpdateProjectRequest(name, folder)).use { parseBody(it) }

    // ── Sessions ─────────────────────────────────────────────────────────────

    fun listSessions(projectId: String? = null): List<RemoteSession> {
        val path = if (projectId.isNullOrBlank()) "/v1/sessions" else "/v1/sessions?projectId=$projectId"
        return get(path).use { parseBody(it) }
    }

    fun createSession(req: CreateSessionRequest): RemoteSession =
        post("/v1/sessions", req).use { parseBody(it) }

    fun getSession(id: String): RemoteSession =
        get("/v1/sessions/$id").use { parseBody(it) }

    fun sessionStatus(sessionId: String): RemoteSessionStatus =
        get("/v1/sessions/$sessionId/status").use { parseBody(it) }

    fun commands(sessionId: String): RemoteCommandList =
        get("/v1/sessions/$sessionId/commands").use { parseBody(it) }

    fun sessionHistory(id: String, limit: Int): RemoteSessionDetail =
        get("/v1/sessions/$id?limit=$limit").use { parseBody(it) }

    fun prompt(sessionId: String, text: String, idempotencyKey: String) {
        post("/v1/sessions/$sessionId/prompt", PromptRequest(text), idempotencyKey = idempotencyKey)
            .use { checkSuccess(it) }
    }

    fun sessionSettings(sessionId: String, model: String, effort: String, mode: String) {
        post("/v1/sessions/$sessionId/settings", SessionSettingsRequest(model, effort, mode))
            .use { checkSuccess(it) }
    }

    fun interrupt(sessionId: String) {
        postEmpty("/v1/sessions/$sessionId/interrupt").use { checkSuccess(it) }
    }

    fun permission(sessionId: String, approvalId: String, allow: Boolean) {
        post("/v1/sessions/$sessionId/permission", PermissionRequest(approvalId, allow))
            .use { checkSuccess(it) }
    }

    fun resumeSession(sessionId: String) {
        postEmpty("/v1/sessions/$sessionId/resume").use { checkSuccess(it) }
    }

    fun closeSession(sessionId: String) {
        postEmpty("/v1/sessions/$sessionId/close").use { checkSuccess(it) }
    }

    fun patchSession(sessionId: String, title: String? = null, archived: Boolean? = null) {
        patch("/v1/sessions/$sessionId", PatchSessionRequest(title, archived)).use { checkSuccess(it) }
    }

    // ── Events ───────────────────────────────────────────────────────────────

    fun events(after: Long): List<RemoteEvent> =
        get("/v1/events?after=$after").use { parseBody(it) }

    /**
     * Opens the live event stream. The companion first replays everything after
     * [after], then pushes new events as they happen. Frames with seq 0 are
     * live text deltas that are never stored.
     */
    fun openEventStream(
        after: Long,
        onOpen: () -> Unit,
        onEvent: (RemoteEvent) -> Unit,
        onClosed: (Throwable?) -> Unit,
    ): WebSocket {
        val req = Request.Builder()
            .url("${baseUrl.replaceFirst("http", "ws")}/v1/events/stream?after=$after")
            .header("Authorization", "Bearer $token")
            .build()
        return streamHttp.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.decodeFromString<RemoteEvent>(text) }.onSuccess(onEvent)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                onClosed(t)
            }
        })
    }

    // ── Files ────────────────────────────────────────────────────────────────

    fun listFiles(projectId: String, path: String): RemoteFileListing =
        get("/v1/files?projectId=$projectId&path=${encode(path)}").use { parseBody(it) }

    fun fileContent(projectId: String, path: String): RemoteFileContent =
        get("/v1/files/content?projectId=$projectId&path=${encode(path)}").use { parseBody(it) }

    fun saveFile(req: SaveFileRequest, idempotencyKey: String): SaveFileResponse =
        put("/v1/files/content", req, idempotencyKey = idempotencyKey).use { parseBody(it) }

    fun mkdir(projectId: String, path: String) {
        post("/v1/files/mkdir", MkdirRequest(projectId, path)).use { checkSuccess(it) }
    }

    fun renamePath(projectId: String, path: String, newName: String) {
        post("/v1/files/rename", RenameRequest(projectId, path, newName)).use { checkSuccess(it) }
    }

    fun movePath(projectId: String, path: String, destination: String) {
        post("/v1/files/move", MoveRequest(projectId, path, destination)).use { checkSuccess(it) }
    }

    fun copyPath(projectId: String, path: String, destination: String) {
        post("/v1/files/copy", CopyRequest(projectId, path, destination)).use { checkSuccess(it) }
    }

    fun deletePaths(projectId: String, paths: List<String>) {
        post("/v1/files/delete", DeleteRequest(projectId, paths)).use { checkSuccess(it) }
    }

    // ── Git ───────────────────────────────────────────────────────────────────

    fun gitStatus(projectId: String): RemoteGitStatus =
        get("/v1/git/status?projectId=$projectId").use { parseBody(it) }

    fun gitDiff(projectId: String, path: String, base: String, commit: String): String {
        val q = "/v1/git/diff?projectId=$projectId&path=${encode(path)}&base=$base${if (commit.isNotBlank()) "&commit=${encode(commit)}" else ""}"
        return get(q).use { it.body?.string() ?: "" }
    }

    fun branches(projectId: String): List<RemoteBranchInfo> =
        get("/v1/git/branches?projectId=$projectId").use { parseBody(it) }

    fun commit(req: CommitRequest, idempotencyKey: String): RemoteGitResult =
        post("/v1/git/commit", req, idempotencyKey = idempotencyKey).use { parseBody(it) }

    fun push(projectId: String, idempotencyKey: String): RemoteGitResult =
        post("/v1/git/push", PushPullRequest(projectId), idempotencyKey = idempotencyKey).use { parseBody(it) }

    fun pull(projectId: String, idempotencyKey: String): RemoteGitResult =
        post("/v1/git/pull", PushPullRequest(projectId), idempotencyKey = idempotencyKey).use { parseBody(it) }

    // ── Processes ─────────────────────────────────────────────────────────────

    fun listProcesses(projectId: String): List<RemoteProcess> =
        get("/v1/processes?projectId=$projectId").use { parseBody(it) }

    fun startProcess(req: StartProcessRequest): RemoteProcess =
        post("/v1/processes", req).use { parseBody(it) }

    fun stopProcess(id: String) {
        postEmpty("/v1/processes/$id/stop").use { checkSuccess(it) }
    }

    // ── Deploy ────────────────────────────────────────────────────────────────

    fun listRecipes(projectId: String): List<RemoteRecipe> =
        get("/v1/deploy/recipes?projectId=$projectId").use { parseBody(it) }

    fun createRecipe(recipe: RemoteRecipe): RemoteRecipe =
        post("/v1/deploy/recipes", recipe).use { parseBody(it) }

    fun startDeploy(projectId: String, recipeId: String, idempotencyKey: String): RemoteDeployRun =
        post("/v1/deploy/runs", StartDeployRequest(projectId, recipeId), idempotencyKey = idempotencyKey)
            .use { parseBody(it) }

    fun getDeployRun(id: String): RemoteDeployRun =
        get("/v1/deploy/runs/$id").use { parseBody(it) }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun get(path: String, auth: Boolean = true): Response {
        val req = Request.Builder()
            .url("$baseUrl$path")
            .apply { if (auth && token.isNotBlank()) header("Authorization", "Bearer $token") }
            .get()
            .build()
        return execute(req)
    }

    private fun postEmpty(
        path: String,
        auth: Boolean = true,
        idempotencyKey: String? = null,
    ): Response {
        val reqBody = "{}".toRequestBody(mediaJson)
        val req = Request.Builder()
            .url("$baseUrl$path")
            .apply { if (auth && token.isNotBlank()) header("Authorization", "Bearer $token") }
            .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
            .post(reqBody)
            .build()
        return execute(req)
    }

    private inline fun <reified T> post(
        path: String,
        body: T,
        auth: Boolean = true,
        idempotencyKey: String? = null,
    ): Response {
        val bodyStr = json.encodeToString(body)
        val reqBody = bodyStr.toRequestBody(mediaJson)
        val req = Request.Builder()
            .url("$baseUrl$path")
            .apply { if (auth && token.isNotBlank()) header("Authorization", "Bearer $token") }
            .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
            .post(reqBody)
            .build()
        return execute(req)
    }

    private inline fun <reified T> patch(path: String, body: T, auth: Boolean = true): Response {
        val bodyStr = json.encodeToString(body)
        val reqBody = bodyStr.toRequestBody(mediaJson)
        val req = Request.Builder()
            .url("$baseUrl$path")
            .apply { if (auth && token.isNotBlank()) header("Authorization", "Bearer $token") }
            .patch(reqBody)
            .build()
        return execute(req)
    }

    private inline fun <reified T> put(
        path: String,
        body: T,
        auth: Boolean = true,
        idempotencyKey: String? = null,
    ): Response {
        val bodyStr = json.encodeToString(body)
        val reqBody = bodyStr.toRequestBody(mediaJson)
        val req = Request.Builder()
            .url("$baseUrl$path")
            .apply { if (auth && token.isNotBlank()) header("Authorization", "Bearer $token") }
            .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
            .put(reqBody)
            .build()
        return execute(req)
    }

    private fun execute(req: Request): Response {
        val resp = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw CompanionException("NETWORK", e.message ?: "Network error")
        }
        if (!resp.isSuccessful) {
            val errBody = runCatching { resp.body?.string() }.getOrNull()
            val apiErr = runCatching { json.decodeFromString<ApiErrorEnvelope>(errBody ?: "").error }.getOrNull()
            val code = apiErr?.code ?: httpStatusToCode(resp.code)
            val msg = apiErr?.message ?: "HTTP ${resp.code}"
            val recoverable = apiErr?.recoverable ?: false
            resp.close()
            throw CompanionException(code, msg, recoverable)
        }
        return resp
    }

    private fun checkSuccess(resp: Response) {
        if (!resp.isSuccessful) {
            resp.close()
            throw CompanionException("UNKNOWN", "HTTP ${resp.code}")
        }
        resp.close()
    }

    private inline fun <reified T> parseBody(resp: Response): T {
        val bodyStr = resp.body?.string()
            ?: throw CompanionException("NETWORK", "Empty response body")
        return json.decodeFromString(bodyStr)
    }

    private fun httpStatusToCode(status: Int): String = when (status) {
        401 -> "UNAUTHORIZED"
        403 -> "PERMISSION"
        404 -> "NOT_FOUND"
        409 -> "CONFLICT"
        422 -> "VALIDATION"
        423 -> "BUSY"
        501 -> "UNSUPPORTED"
        else -> "UNKNOWN"
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
