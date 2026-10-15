package dev.pocket.app.ui.terminal

import dev.pocket.app.ui.components.rememberSafeScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.model.TerminalSnapshot
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(repository: PocketRepository, projectId: String) {
    val scope = rememberSafeScope()
    val clipboard = LocalClipboardManager.current
    val snapshot by repository.terminal(projectId).collectAsState(
        initial = TerminalSnapshot(projectId, "", false),
    )
    var input by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    LaunchedEffect(snapshot.output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    fun send() {
        if (input.isBlank()) return
        val text = input
        input = ""
        scope.launch { repository.terminalInput(projectId, text) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (snapshot.alive) "Session active" else "Session closed",
                style = MaterialTheme.typography.labelSmall,
                color = if (snapshot.alive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(
                "Session lives on the VPS — reconnecting will restore output.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0D1117)),
        ) {
            SelectionContainer {
                Text(
                    text = snapshot.output.ifEmpty { "(no output yet)" },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFFE6EDF3),
                )
            }
        }

        HorizontalDivider()

        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Command") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = ::send, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Send, "Send")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { scope.launch { repository.terminalInput(projectId, "") } },
                        modifier = Modifier.size(width = 72.dp, height = 40.dp),
                    ) {
                        Text("Ctrl+C", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(
                        onClick = {
                            val text = clipboard.getText()?.text
                            if (!text.isNullOrBlank()) {
                                input += text
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Outlined.ContentPaste, "Paste")
                    }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(snapshot.output))
                        },
                        modifier = Modifier.size(width = 64.dp, height = 40.dp),
                    ) {
                        Text("Copy all", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
