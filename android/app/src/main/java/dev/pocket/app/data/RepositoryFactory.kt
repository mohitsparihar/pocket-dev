package dev.pocket.app.data

import android.content.Context
import dev.pocket.app.data.remote.RemotePocketRepository
import dev.pocket.app.google.GoogleAuth
import dev.pocket.app.service.PocketConnectionService
import java.io.File

object RepositoryFactory {
    @Volatile
    private var instance: PocketRepository? = null

    fun create(context: Context): PocketRepository {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(context: Context): PocketRepository {
        val ssh = PocketConnectionService.sshManager
        ssh.hostKeyStore.attach(File(context.filesDir, "host-keys.txt"))
        val remote = RemotePocketRepository(
            sshManager = ssh,
            delegate = DemoRepository(),
            keyDirectory = File(context.filesDir, "ssh-keys"),
            profileFile = File(context.filesDir, "connections.json"),
            googleAuth = GoogleAuth(context),
        )
        PocketConnectionService.repository = remote
        return remote
    }
}
