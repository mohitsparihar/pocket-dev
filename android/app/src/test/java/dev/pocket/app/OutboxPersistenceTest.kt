package dev.pocket.app

import dev.pocket.app.data.DemoRepository
import dev.pocket.app.data.remote.RemotePocketRepository
import dev.pocket.app.ssh.HostKeyStore
import dev.pocket.app.ssh.SshSessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class OutboxPersistenceTest {
    private fun repository(dir: java.io.File) = RemotePocketRepository(
        sshManager = SshSessionManager(HostKeyStore()),
        delegate = DemoRepository(),
        profileFile = dir.resolve("connections.json"),
    )

    @Test
    fun promptsSavedBeforeTheAppWasKilledComeBackAsWaiting() = runBlocking {
        val dir = Files.createTempDirectory("outbox").toFile()
        val now = System.currentTimeMillis()
        dir.resolve("outbox.json").writeText(
            """[
              {"key":"k1","sessionId":"s1","text":"fix the build","state":"Sending","createdAt":$now},
              {"key":"k2","sessionId":"s1","text":"then run tests","state":"Sent","createdAt":$now},
              {"key":"old","sessionId":"s3","text":"a month old","state":"Waiting","createdAt":1},
              {"key":"k3","sessionId":"s2","text":"other chat","state":"Queued","createdAt":3}
            ]""",
        )

        val restarted = repository(dir)
        val pending = restarted.pendingPrompts("s1").first()
        assertEquals(listOf("k1", "k2"), pending.map { it.key })
        // Whether they reached the VM is unknown, so all retry with their original keys.
        assertEquals(setOf(dev.pocket.app.model.PendingState.Waiting), pending.map { it.state }.toSet())

        // Too old for the VM to still remember its key: the user decides.
        assertEquals(dev.pocket.app.model.PendingState.Failed, restarted.pendingPrompts("s3").first().single().state)

        restarted.discardPending("k1")
        val afterCancel = repository(dir).pendingPrompts("s1").first()
        assertEquals(listOf("k2"), afterCancel.map { it.key })
        assertFalse(dir.resolve("outbox.json").readText().contains("fix the build"))
    }
}
