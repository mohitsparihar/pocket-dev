package dev.pocket.app

import dev.pocket.app.data.remote.RemoteBranchList
import dev.pocket.app.data.remote.RemoteCommit
import dev.pocket.app.data.remote.RemoteDiff
import dev.pocket.app.data.remote.RemoteGitResult
import dev.pocket.app.data.remote.RemoteGitStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses captured from a real companion. Decoding them the way
 * CompanionClient does guards against the two sides drifting apart again,
 * which is what made the Changes and Git screens crash.
 */
class GitContractTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun status() {
        val status = json.decodeFromString<RemoteGitStatus>(
            """{"branch":"main","ahead":0,"behind":0,"files":[{"path":"a.txt","status":"modified","staged":true},{"path":"new file.md","status":"untracked","staged":false}]}""",
        )
        assertEquals("main", status.branch)
        assertEquals(listOf("a.txt", "new file.md"), status.files.map { it.path })
    }

    @Test
    fun branchesDiffLogAndResults() {
        val branches = json.decodeFromString<RemoteBranchList>("""{"branches":[{"name":"main","sha":"667c8a5","current":true}],"stderr":""}""")
        assertTrue(branches.branches.single().current)

        val diff = json.decodeFromString<RemoteDiff>("""{"diff":"diff --git a/a.txt b/a.txt\n+c\n","stderr":""}""")
        assertTrue(diff.diff.startsWith("diff --git"))

        val log = json.decodeFromString<List<RemoteCommit>>(
            """[{"sha":"667c8a55145589473a17b98d3cc2f967661b658e","shortSha":"667c8a5","author":"Mohit","at":1792086450000,"subject":"First commit"}]""",
        )
        assertEquals("First commit", log.single().subject)

        val push = json.decodeFromString<RemoteGitResult>("""{"ok":false,"message":"fatal: No configured push destination.","stdout":"","stderr":"fatal"}""")
        assertEquals(false, push.ok)
    }
}
