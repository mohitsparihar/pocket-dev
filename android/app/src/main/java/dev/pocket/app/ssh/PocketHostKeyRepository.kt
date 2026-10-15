package dev.pocket.app.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

internal class PocketHostKeyRepository(
    private val profileId: String,
    private val store: HostKeyStore,
) : HostKeyRepository {

    @Volatile var capturedFingerprint: String? = null
        private set
    @Volatile var capturedStatus: HostKeyStatus? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val fp = computeFingerprint(key)
        capturedFingerprint = fp
        val result = store.check(profileId, fp)
        capturedStatus = result.status
        return when (result.status) {
            HostKeyStatus.Accepted -> HostKeyRepository.OK
            HostKeyStatus.Changed  -> HostKeyRepository.CHANGED
            HostKeyStatus.Unknown,
            HostKeyStatus.Rejected -> HostKeyRepository.NOT_INCLUDED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "pocket-dev-$profileId"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
