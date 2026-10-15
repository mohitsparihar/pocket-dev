package dev.pocket.app.ssh

sealed class SshCredentials {
    data class KeyAuth(
        val privateKeyBytes: ByteArray,
        val passphrase: ByteArray? = null,
    ) : SshCredentials()

    data class PasswordAuth(val password: String) : SshCredentials()
}
