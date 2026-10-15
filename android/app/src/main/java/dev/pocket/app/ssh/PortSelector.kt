package dev.pocket.app.ssh

import java.net.InetAddress
import java.net.ServerSocket

object PortSelector {
    private val loopback: InetAddress by lazy { InetAddress.getByName("127.0.0.1") }

    fun findFree(startPort: Int, maxAttempts: Int = 20): Pair<Int, Boolean> {
        for (offset in 0 until maxAttempts) {
            val candidate = startPort + offset
            if (candidate > 65535) break
            try {
                ServerSocket(candidate, 1, loopback).use {
                    return Pair(candidate, offset > 0)
                }
            } catch (_: Exception) {
                // port in use, try next
            }
        }
        throw PortExhaustedException("No free port found near $startPort after $maxAttempts attempts")
    }
}

class PortExhaustedException(message: String) : Exception(message)
