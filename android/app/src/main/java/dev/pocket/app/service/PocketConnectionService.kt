package dev.pocket.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.pocket.app.MainActivity
import dev.pocket.app.data.RepositoryFactory
import dev.pocket.app.data.remote.RemotePocketRepository
import dev.pocket.app.model.AgentSession
import dev.pocket.app.model.ConnectionPhase
import dev.pocket.app.model.LinkState
import dev.pocket.app.model.SessionState
import dev.pocket.app.ssh.HostKeyStore
import dev.pocket.app.ssh.SshSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the app process alive while a VM is connected and shows its state.
 * Reconnecting is owned by [RemotePocketRepository.keepAlive]; agents keep
 * running on the VM whether or not this service is alive.
 */
class PocketConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val lastStates = mutableMapOf<String, SessionState>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        showStatus("Starting")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = repository ?: (RepositoryFactory.create(applicationContext) as? RemotePocketRepository)
        if (repo == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                prefs.edit().remove(KEY_CONNECTION_ID).apply()
                scope.launch {
                    repo.disconnect()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val id = intent.getStringExtra(EXTRA_CONNECTION_ID) ?: return START_NOT_STICKY
                prefs.edit().putString(KEY_CONNECTION_ID, id).apply()
                start(repo, id)
            }
            // Restarted by the system after the process was killed.
            null -> {
                val id = prefs.getString(KEY_CONNECTION_ID, null)
                if (id == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                start(repo, id)
            }
        }
        return START_STICKY
    }

    private fun start(repo: RemotePocketRepository, id: String) {
        repo.keepAlive(id)
        watchNetwork(repo)
        watchJob?.cancel()
        watchJob = scope.launch {
            launch {
                combine(repo.connections(), repo.linkState()) { list, link ->
                    val profile = list.find { it.id == id }
                    when {
                        profile == null -> "Disconnected"
                        link == LinkState.Live && profile.phase == ConnectionPhase.Connected -> "Connected · ${profile.name}"
                        else -> "${profile.statusLine} · ${profile.name}"
                    }
                }.distinctUntilChanged().collect { showStatus(it) }
            }
            launch {
                repo.sessionUpdates().collect { sessions -> notifyFinished(sessions) }
            }
        }
    }

    private fun watchNetwork(repo: RemotePocketRepository) {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = repo.nudge()
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    /** Tells you when a chat that was working finishes, while the app is in the background. */
    private fun notifyFinished(sessions: List<AgentSession>) {
        for (session in sessions) {
            val before = lastStates.put(session.id, session.state)
            val wasWorking = before == SessionState.Running || before == SessionState.Starting
            if (!wasWorking || appVisible) continue
            val headline = when (session.state) {
                SessionState.AwaitingInput, SessionState.Completed -> "Finished"
                SessionState.Failed -> "Failed"
                else -> continue
            }
            val title = session.title.ifBlank { "Chat" }
            val notification = NotificationCompat.Builder(this, CHATS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("$headline · $title")
                .setContentText(session.latestLine.removePrefix("You: ").ifBlank { "Open the chat to continue" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(session.latestLine.removePrefix("You: ")))
                .setContentIntent(openApp(session.id))
                .setAutoCancel(true)
                .build()
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(session.id.hashCode(), notification)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        super.onDestroy()
    }

    private fun openApp(sessionId: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra(MainActivity.EXTRA_OPEN_SESSION, sessionId)
        }
        return PendingIntent.getActivity(
            this,
            sessionId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showStatus(text: String) {
        val disconnectIntent = Intent(this, PocketConnectionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPi = PendingIntent.getService(
            this, 0, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Pocket Dev")
            .setContentText(text)
            .setSubText("Agents keep running on the VM")
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPi)
            .build()
        // Android can refuse foreground status (for example when it restarts the
        // service from the background). Stop quietly; agents keep running on the VM.
        runCatching { startForeground(STATUS_NOTIFICATION_ID, notification) }
            .onFailure { stopSelf() }
    }

    private fun ensureChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(STATUS_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(STATUS_CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (manager.getNotificationChannel(CHATS_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHATS_CHANNEL_ID, "Chat updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    companion object {
        const val ACTION_CONNECT = "dev.pocket.app.action.CONNECT"
        const val ACTION_DISCONNECT = "dev.pocket.app.action.DISCONNECT"
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_HOST = "host"

        private const val STATUS_CHANNEL_ID = "pocket-connection"
        private const val CHATS_CHANNEL_ID = "pocket-chats"
        private const val STATUS_NOTIFICATION_ID = 41
        private const val PREFS = "pocket-connection"
        private const val KEY_CONNECTION_ID = "connection_id"

        val sshManager = SshSessionManager(HostKeyStore())

        /** Set by RepositoryFactory before the service is started. */
        @Volatile
        var repository: RemotePocketRepository? = null

        /** True while MainActivity is in the foreground; chat notifications are skipped then. */
        @Volatile
        var appVisible: Boolean = false
    }
}
