package dev.pocket.app.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import dev.pocket.app.model.ConnectResult
import dev.pocket.app.model.ConnectionProfile

class SshSessionManager(val hostKeyStore: HostKeyStore) {

    @Volatile private var _session: Session? = null
    private val forwardedPorts = mutableListOf<Int>()

    val isConnected: Boolean get() = _session?.isConnected == true

    fun connect(
        profile: ConnectionProfile,
        credentials: SshCredentials?,
        socketFactory: SocketFactory? = null,
    ): ConnectResult {
        val jsch = JSch()
        val repo = PocketHostKeyRepository(profile.id, hostKeyStore)
        jsch.hostKeyRepository = repo

        when (credentials) {
            is SshCredentials.KeyAuth ->
                jsch.addIdentity(
                    "pocket-${profile.id}",
                    credentials.privateKeyBytes,
                    null,
                    credentials.passphrase,
                )
            is SshCredentials.PasswordAuth -> { /* set after getSession */ }
            null -> { /* JSch will try default identity files at the OS level */ }
        }

        val jschSession = try {
            jsch.getSession(profile.username, profile.host, profile.port)
        } catch (e: JSchException) {
            return ConnectResult(false, "SSH setup failed: ${e.message}", "Check host, port, and username.")
        }

        jschSession.setConfig("StrictHostKeyChecking", "yes")
        // Mobile networks drop TCP silently; without keepalives a dead session
        // still reports isConnected for minutes.
        jschSession.setServerAliveInterval(KEEPALIVE_INTERVAL_MS)
        jschSession.setServerAliveCountMax(KEEPALIVE_MAX_MISSED)
        jschSession.setConfig("PreferredAuthentications", if (credentials is SshCredentials.PasswordAuth) "password" else "publickey,keyboard-interactive,password")

        if (credentials is SshCredentials.PasswordAuth) {
            jschSession.setPassword(credentials.password)
        }
        if (socketFactory != null) {
            jschSession.setSocketFactory(socketFactory)
        }

        // Suppress interactive prompts — we handle everything ourselves
        jschSession.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = (credentials as? SshCredentials.PasswordAuth)?.password
            override fun promptPassword(message: String?) = credentials is SshCredentials.PasswordAuth
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?) = false
            override fun showMessage(message: String?) = Unit
        }

        return try {
            jschSession.connect(if (socketFactory != null) 30_000 else 15_000)
            // JSch called repo.check() during connect; if it returned OK, we proceed
            _session = jschSession
            ConnectResult(true, "Connected to ${profile.host}")
        } catch (e: JSchException) {
            val fp = repo.capturedFingerprint ?: "unknown"
            when (repo.capturedStatus) {
                HostKeyStatus.Changed ->
                    ConnectResult(
                        false,
                        "Changed host key",
                        "The host key for ${profile.host} changed ($fp). Review it in Connections → Diagnostics and call acceptHostKey only if you trust the new key. This may indicate a security issue.",
                    )
                HostKeyStatus.Rejected ->
                    ConnectResult(false, "Key rejected", "")
                HostKeyStatus.Unknown ->
                    ConnectResult(
                        false,
                        "Unknown host key: $fp",
                        "Review the fingerprint, then use Accept Host Key to connect.",
                    )
                null, HostKeyStatus.Accepted ->
                    ConnectResult(false, "SSH connection failed: ${e.message}", "Check credentials and network.")
            }
        }
    }

    fun exec(command: String, timeoutMs: Long = 20_000): String {
        val session = _session ?: return ""
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val stdout = channel.inputStream
        val stderr = channel.errStream
        channel.connect(15_000)
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            while (stdout.available() > 0) out.write(buf, 0, stdout.read(buf))
            while (stderr.available() > 0) err.write(buf, 0, stderr.read(buf))
            if (channel.isClosed) break
            Thread.sleep(50)
        }
        runCatching { channel.disconnect() }
        return out.toString() + err.toString()
    }

    fun disconnect() {
        val s = _session ?: return
        forwardedPorts.clear()
        _session = null
        runCatching { s.disconnect() }
    }

    fun addForward(remoteHost: String, remotePort: Int, preferredPhonePort: Int): Int {
        val s = _session ?: throw IllegalStateException("SSH session not connected")
        val (port, shifted) = PortSelector.findFree(preferredPhonePort)
        // JSch binds the socket; if that throws we propagate
        s.setPortForwardingL("127.0.0.1", port, remoteHost, remotePort)
        forwardedPorts += port
        return port
    }

    fun removeForward(phonePort: Int) {
        val s = _session ?: return
        runCatching { s.delPortForwardingL(phonePort) }
        forwardedPorts -= phonePort
    }

    fun currentSession(): Session? = if (_session?.isConnected == true) _session else null

    private companion object {
        const val KEEPALIVE_INTERVAL_MS = 10_000
        const val KEEPALIVE_MAX_MISSED = 3
    }
}
