package dev.pocket.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.pocket.app.data.RepositoryFactory
import dev.pocket.app.service.PocketConnectionService
import dev.pocket.app.ui.nav.PocketApp
import dev.pocket.app.ui.theme.PocketTheme

class MainActivity : ComponentActivity() {
    private val openSession = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openSession.value = intent?.getStringExtra(EXTRA_OPEN_SESSION)
        val repository = RepositoryFactory.create(applicationContext)
        PocketConnectionService.resumeIfConnected(applicationContext)
        setContent {
            PocketTheme {
                PocketApp(
                    repository = repository,
                    openSessionId = openSession.value,
                    onSessionOpened = { openSession.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_SESSION)?.let { openSession.value = it }
    }

    override fun onResume() {
        super.onResume()
        PocketConnectionService.appVisible = true
    }

    override fun onPause() {
        PocketConnectionService.appVisible = false
        super.onPause()
    }

    companion object {
        const val EXTRA_OPEN_SESSION = "open_session"
    }
}
