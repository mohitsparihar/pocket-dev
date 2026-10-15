package dev.pocket.app.ssh

import java.security.MessageDigest
import java.util.Base64

internal fun computeFingerprint(keyBytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
