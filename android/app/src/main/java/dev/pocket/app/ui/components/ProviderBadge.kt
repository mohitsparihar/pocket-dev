package dev.pocket.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocket.app.model.Provider

@Composable
fun ProviderBadge(provider: Provider, modifier: Modifier = Modifier) {
    val label = when (provider) {
        Provider.Claude -> "Claude"
        Provider.Cursor -> "Cursor"
        Provider.Codex -> "Codex"
        Provider.Terminal -> "Terminal"
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
