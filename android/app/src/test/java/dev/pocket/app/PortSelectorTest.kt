package dev.pocket.app

import dev.pocket.app.ssh.PortExhaustedException
import dev.pocket.app.ssh.PortSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class PortSelectorTest {

    @Test
    fun `free port returns requested port unchanged`() {
        // Find a port that is actually free, then verify findFree returns it without shift.
        val candidate = findFreePort()
        val (found, shifted) = PortSelector.findFree(candidate)
        assertEquals("Should return the requested port when it is free", candidate, found)
        assertTrue("Should not report shift", !shifted)
    }

    @Test
    fun `occupied port selects next free port`() {
        // Bind a port, then ask for it — findFree should step over it.
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val occupied = socket.localPort
        socket.use {
            val (found, shifted) = PortSelector.findFree(occupied)
            assertNotEquals("Should not return the occupied port", occupied, found)
            assertTrue("Should report that port was shifted", shifted)
        }
    }

    @Test
    fun `selected port is actually bindable`() {
        val seed = findFreePort()
        // Occupy seed so findFree has to step
        val socket = ServerSocket(seed, 1, InetAddress.getByName("127.0.0.1"))
        val nextPort = socket.use {
            val (found, _) = PortSelector.findFree(seed)
            found
        }
        // Verify the returned port can be bound
        ServerSocket(nextPort, 1, InetAddress.getByName("127.0.0.1")).use { }
    }

    @Test
    fun `consecutive occupied ports are all skipped`() {
        // Occupy two consecutive ports; findFree should land on the third.
        val first = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val firstPort = first.localPort
        val second = ServerSocket(firstPort + 1, 1, InetAddress.getByName("127.0.0.1"))
        first.use {
            second.use {
                val (found, _) = PortSelector.findFree(firstPort)
                assertTrue("Should skip both occupied ports", found >= firstPort + 2)
            }
        }
    }

    @Test(expected = PortExhaustedException::class)
    fun `throws when no free port within maxAttempts`() {
        // Bind 3 consecutive ports; ask with maxAttempts=3 — should exhaust.
        val sockets = mutableListOf<ServerSocket>()
        val base: Int
        try {
            val s0 = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            base = s0.localPort
            sockets += s0
            sockets += ServerSocket(base + 1, 1, InetAddress.getByName("127.0.0.1"))
            sockets += ServerSocket(base + 2, 1, InetAddress.getByName("127.0.0.1"))
            PortSelector.findFree(base, maxAttempts = 3)
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private fun findFreePort(): Int =
        ServerSocket(0).use { it.localPort }
}
