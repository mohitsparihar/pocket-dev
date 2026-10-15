package dev.pocket.app.ui.deploy

import dev.pocket.app.ui.components.rememberSafeScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.model.DeploymentRecipe
import dev.pocket.app.model.DeploymentRun
import dev.pocket.app.model.ProcessState
import dev.pocket.app.ui.components.StatusChip
import dev.pocket.app.ui.components.StatusTone
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun DeployScreen(repository: PocketRepository, projectId: String) {
    val scope = rememberSafeScope()
    val recipes by repository.recipes(projectId).collectAsState(initial = emptyList())
    var selectedRecipe by remember { mutableStateOf<DeploymentRecipe?>(null) }
    var currentRunId by remember { mutableStateOf<String?>(null) }

    val run by androidx.compose.runtime.produceState<DeploymentRun?>(null, currentRunId) {
        currentRunId?.let { id ->
            repository.deployRun(id).collect { value = it }
        }
    }

    val isRunning = run?.state == ProcessState.Running

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Deploy", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (isRunning) {
                    StatusChip("Running", StatusTone.Busy)
                }
            }
        }

        if (recipes.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No deploy recipes configured for this project.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(recipes, key = { it.id }) { recipe ->
                val isSelected = recipe == selectedRecipe
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { selectedRecipe = recipe },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(recipe.name, style = MaterialTheme.typography.titleSmall)
                        RecipeMetaRow("Command", recipe.command)
                        RecipeMetaRow("Directory", recipe.workingDirectory)
                        RecipeMetaRow("Target", recipe.target)
                    }
                }
            }
        }

        selectedRecipe?.let { recipe ->
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val key = UUID.randomUUID().toString()
                            scope.launch {
                                val deployRun = repository.startDeploy(projectId, recipe.id, key)
                                currentRunId = deployRun.id
                            }
                        },
                        enabled = !isRunning,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (isRunning) "Deploying…" else "Deploy ${recipe.name}")
                    }

                    if (isRunning) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }

        run?.let { r ->
            item {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.Terminal, null, Modifier.size(16.dp))
                        Text("Deploy log", style = MaterialTheme.typography.titleSmall)
                        val (chipLabel, tone) = when (r.state) {
                            ProcessState.Running -> "Running" to StatusTone.Busy
                            ProcessState.Stopped -> "Finished" to StatusTone.Ok
                            ProcessState.Failed -> "Failed" to StatusTone.Bad
                            ProcessState.Starting -> "Starting" to StatusTone.Wait
                        }
                        StatusChip(chipLabel, tone)
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        SelectionContainer {
                            Text(
                                r.log,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                                    .height(200.dp)
                                    .verticalScroll(rememberScrollState()),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeMetaRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(width = 72.dp, height = 16.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}
