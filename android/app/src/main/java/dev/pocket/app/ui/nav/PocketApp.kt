package dev.pocket.app.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.pocket.app.data.PocketRepository
import dev.pocket.app.ui.changes.ChangesScreen
import dev.pocket.app.ui.chats.ChatDetailScreen
import dev.pocket.app.ui.chats.ChatsScreen
import dev.pocket.app.ui.components.StatusChip
import dev.pocket.app.ui.components.StatusTone
import dev.pocket.app.ui.connections.ConnectionsScreen
import dev.pocket.app.ui.deploy.DeployScreen
import dev.pocket.app.ui.editor.EditorScreen
import dev.pocket.app.ui.files.FileManagerScreen
import dev.pocket.app.ui.git.GitScreen
import dev.pocket.app.ui.preview.PreviewScreen
import dev.pocket.app.ui.projects.ProjectDetailScreen
import dev.pocket.app.ui.projects.ProjectsScreen
import dev.pocket.app.ui.terminal.TerminalScreen
import dev.pocket.app.model.ConnectionPhase

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketApp(
    repository: PocketRepository,
    openSessionId: String? = null,
    onSessionOpened: () -> Unit = {},
) {
    val nav = rememberNavController()
    // Tapping a "chat finished" notification lands in that chat.
    LaunchedEffect(openSessionId) {
        val id = openSessionId ?: return@LaunchedEffect
        nav.navigate(Routes.chat(id)) { launchSingleTop = true }
        onSessionOpened()
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tabs = listOf(
        Tab(Routes.Chats, "Chats", Icons.Outlined.Chat),
        Tab(Routes.Projects, "Projects", Icons.Outlined.Folder),
        Tab(Routes.Preview, "Preview", Icons.Outlined.Visibility),
        Tab(Routes.Connections, "Connections", Icons.Outlined.Link),
    )
    val showTabs = route in tabs.map { it.route }
    val connection by repository.activeConnection().collectAsState(initial = null)
    val projects by repository.projects().collectAsState(initial = emptyList())
    val activeId by repository.activeProjectId().collectAsState(initial = null)
    val projectName = projects.find { it.id == activeId }?.name ?: "No project"
    val status = connection?.statusLine ?: "Disconnected"
    val tone = when (connection?.phase) {
        ConnectionPhase.Connected -> StatusTone.Ok
        ConnectionPhase.Connecting -> StatusTone.Busy
        ConnectionPhase.Failed -> StatusTone.Bad
        else -> StatusTone.Wait
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(projectName, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                },
                actions = {
                    StatusChip(status, tone, Modifier.padding(end = 12.dp))
                },
            )
        },
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(Routes.Chats) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.Chats,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Chats) {
                ChatsScreen(repository, onOpenChat = { nav.navigate(Routes.chat(it)) })
            }
            composable(
                Routes.Chat,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("sessionId").orEmpty()
                ChatDetailScreen(
                    repository = repository,
                    sessionId = id,
                    onBack = { nav.popBackStack() },
                    // A blank project id makes a route like project//changes, which doesn't exist and would crash.
                    onOpenTerminal = { if (it.isNotBlank()) nav.navigate(Routes.terminal(it)) },
                    onOpenChanges = { if (it.isNotBlank()) nav.navigate(Routes.changes(it)) },
                    onOpenFile = { projectId, path -> if (projectId.isNotBlank()) nav.navigate(Routes.editor(projectId, path)) },
                )
            }
            composable(Routes.Projects) {
                ProjectsScreen(repository, onOpenProject = { nav.navigate(Routes.project(it)) })
            }
            composable(
                Routes.Project,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("projectId").orEmpty()
                ProjectDetailScreen(
                    repository = repository,
                    projectId = id,
                    onBack = { nav.popBackStack() },
                    onOpenChat = { nav.navigate(Routes.chat(it)) },
                    onOpenFiles = { nav.navigate(Routes.files(id)) },
                    onOpenEditor = { nav.navigate(Routes.editor(id, "")) },
                    onOpenChanges = { nav.navigate(Routes.changes(id)) },
                    onOpenTerminal = { nav.navigate(Routes.terminal(id)) },
                    onOpenPreview = { nav.navigate(Routes.Preview) },
                    onOpenGit = { nav.navigate(Routes.git(id)) },
                    onOpenDeploy = { nav.navigate(Routes.deploy(id)) },
                )
            }
            composable(Routes.Preview) { PreviewScreen(repository) }
            composable(Routes.Connections) { ConnectionsScreen(repository) }
            composable(
                Routes.Files,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("projectId").orEmpty()
                FileManagerScreen(repository, id) { path -> nav.navigate(Routes.editor(id, path)) }
            }
            composable(
                Routes.Editor,
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("path") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("projectId").orEmpty()
                val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
                EditorScreen(repository, id, path) { nav.popBackStack() }
            }
            composable(
                Routes.Changes,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("projectId").orEmpty()
                ChangesScreen(repository, id) { path, _ -> nav.navigate(Routes.editor(id, path)) }
            }
            composable(
                Routes.Terminal,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            ) { entry ->
                TerminalScreen(repository, entry.arguments?.getString("projectId").orEmpty())
            }
            composable(
                Routes.Git,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            ) { entry ->
                GitScreen(repository, entry.arguments?.getString("projectId").orEmpty())
            }
            composable(
                Routes.Deploy,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            ) { entry ->
                DeployScreen(repository, entry.arguments?.getString("projectId").orEmpty())
            }
        }
    }
}
