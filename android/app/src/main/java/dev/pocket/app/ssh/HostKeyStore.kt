package dev.pocket.app.ssh

import java.io.File

enum class HostKeyStatus { Unknown, Accepted, Changed, Rejected }

data class HostKeyCheckResult(val status: HostKeyStatus, val fingerprint: String)

class HostKeyStore {
    private val accepted = mutableMapOf<String, String>()
    private var file: File? = null

    fun attach(storage: File) {
        file = storage
        if (!storage.exists()) return
        storage.readLines().forEach { line ->
            val parts = line.split(" ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                accepted[parts[0]] = parts[1]
            }
        }
    }

    fun check(profileId: String, fingerprint: String): HostKeyCheckResult {
        val stored = accepted[profileId] ?: return HostKeyCheckResult(HostKeyStatus.Unknown, fingerprint)
        return if (stored == fingerprint) {
            HostKeyCheckResult(HostKeyStatus.Accepted, fingerprint)
        } else {
            HostKeyCheckResult(HostKeyStatus.Changed, fingerprint)
        }
    }

    fun accept(profileId: String, fingerprint: String) {
        accepted[profileId] = fingerprint
        persist()
    }

    fun reject(profileId: String) {
        accepted.remove(profileId)
        persist()
    }

    fun storedFingerprint(profileId: String): String? = accepted[profileId]

    private fun persist() {
        val target = file ?: return
        target.parentFile?.mkdirs()
        target.writeText(accepted.entries.joinToString("\n") { "${it.key} ${it.value}" })
    }
}
