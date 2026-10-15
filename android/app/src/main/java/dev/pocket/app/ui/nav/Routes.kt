package dev.pocket.app.ui.nav

object Routes {
    const val Chats = "chats"
    const val Projects = "projects"
    const val Preview = "preview"
    const val Connections = "connections"
    const val Chat = "chat/{sessionId}"
    const val Project = "project/{projectId}"
    const val Files = "project/{projectId}/files"
    const val Editor = "project/{projectId}/editor?path={path}"
    const val Changes = "project/{projectId}/changes"
    const val Terminal = "project/{projectId}/terminal"
    const val Git = "project/{projectId}/git"
    const val Deploy = "project/{projectId}/deploy"

    fun chat(sessionId: String) = "chat/$sessionId"
    fun project(projectId: String) = "project/$projectId"
    fun files(projectId: String) = "project/$projectId/files"
    fun editor(projectId: String, path: String) = "project/$projectId/editor?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
    fun changes(projectId: String) = "project/$projectId/changes"
    fun terminal(projectId: String) = "project/$projectId/terminal"
    fun git(projectId: String) = "project/$projectId/git"
    fun deploy(projectId: String) = "project/$projectId/deploy"
}
