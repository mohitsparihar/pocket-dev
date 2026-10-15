package dev.pocket.app.ui.components

import dev.pocket.app.ui.theme.Ok
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StatusChip(label: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val icon = when (tone) {
        StatusTone.Ok -> Icons.Outlined.CheckCircle
        StatusTone.Busy -> Icons.Outlined.Sync
        StatusTone.Wait -> Icons.Outlined.HourglassEmpty
        StatusTone.Bad -> Icons.Outlined.ErrorOutline
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = when (tone) {
                    StatusTone.Ok -> Ok
                    StatusTone.Busy -> MaterialTheme.colorScheme.primary
                    StatusTone.Wait -> MaterialTheme.colorScheme.onSurfaceVariant
                    StatusTone.Bad -> MaterialTheme.colorScheme.error
                },
            )
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

enum class StatusTone { Ok, Busy, Wait, Bad }
