package dev.pocket.app.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope

/**
 * A coroutine scope for screen actions. A failed remote call (the tunnel
 * dropped, the VM said no) shows a short message instead of crashing the app.
 */
@Composable
fun rememberSafeScope(): CoroutineScope {
    val context = LocalContext.current.applicationContext
    val handler = remember(context) {
        CoroutineExceptionHandler { _, error ->
            Toast.makeText(context, error.message ?: "Something went wrong", Toast.LENGTH_SHORT).show()
        }
    }
    return rememberCoroutineScope { handler }
}
