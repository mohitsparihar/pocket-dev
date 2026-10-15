package dev.pocket.app.ui.chats

import dev.pocket.app.model.AgentCommand
import dev.pocket.app.model.Provider
import dev.pocket.app.model.SessionEvent

/**
 * App commands run on the phone (they replace TUI-only commands such as
 * /model that the headless CLIs don't have); [SlashKind.Agent] commands are
 * sent to the agent as a message.
 */
enum class SlashKind { Model, Effort, Mode, Status, Stop, Rename, Changes, Terminal, Help, Agent }

data class SlashCommand(
    val name: String,
    val hint: String,
    val kind: SlashKind,
    val argumentHint: String = "",
    val source: String = "app",
)

data class AgentChoice(val id: String, val label: String)

sealed class ChatBlock {
    abstract val seq: Long

    data class You(override val seq: Long, val text: String) : ChatBlock()
    data class Agent(override val seq: Long, val text: String) : ChatBlock()
    data class Thinking(override val seq: Long, val text: String) : ChatBlock()
    /** Consecutive tool calls, shown as one collapsible group. */
    data class Tools(override val seq: Long, val calls: List<String>) : ChatBlock()
    data class Note(override val seq: Long, val text: String) : ChatBlock()
    data class Error(override val seq: Long, val text: String) : ChatBlock()
}

/** Splits a tool label from the companion ("Read  src/app.ts") into name and detail. */
fun toolParts(call: String): Pair<String, String> {
    val split = call.indexOf("  ")
    return if (split < 0) call.trim() to "" else call.substring(0, split).trim() to call.substring(split).trim()
}

fun appCommands(provider: Provider): List<SlashCommand> {
    if (provider == Provider.Terminal) return emptyList()
    return listOf(
        SlashCommand("model", "Switch model", SlashKind.Model, "[name]"),
        SlashCommand("effort", "Switch reasoning effort", SlashKind.Effort, "[level]"),
        SlashCommand("mode", "Agent, Auto, Plan or Ask", SlashKind.Mode, "[mode]"),
        SlashCommand("status", "Version, model, MCP servers, usage and context", SlashKind.Status),
        SlashCommand("cost", "Tokens and cost for this chat", SlashKind.Status),
        SlashCommand("stop", "Stop the agent", SlashKind.Stop),
        SlashCommand("rename", "Rename this chat", SlashKind.Rename, "[title]"),
        SlashCommand("diff", "Show the working changes", SlashKind.Changes),
        SlashCommand("terminal", "Open a terminal in the project", SlashKind.Terminal),
        SlashCommand("help", "List every command", SlashKind.Help),
    )
}

/**
 * Every command a chat offers: the app's own, then the agent's. [reported]
 * is the list from the VM; without it, the commands known to work headless.
 */
fun commandsFor(provider: Provider, reported: List<AgentCommand>? = null): List<SlashCommand> {
    val app = appCommands(provider)
    val taken = app.map { it.name }.toSet()
    val agent = (reported ?: fallbackAgentCommands(provider))
        .filter { it.name !in taken }
        .map { SlashCommand(it.name, it.description, SlashKind.Agent, it.argumentHint, it.source) }
    return app + agent
}

/**
 * Why a slash command won't be sent, or null when it can be. Commands from the
 * agents' interactive terminals don't exist headless, so they are explained
 * instead of sent. [known] is the full command list when it came from the VM.
 */
fun unavailableCommandReason(provider: Provider, text: String, known: List<SlashCommand>?): String? {
    if (provider == Provider.Terminal) return null
    val name = slashHead(text.trim())?.lowercase() ?: return null
    // "/src/app.ts is broken" is a message, not a command.
    if (!Regex("^[a-z0-9._:-]+$").matches(name)) return null
    if (known?.any { it.name.equals(name, ignoreCase = true) } == true) return null
    val alternative = TERMINAL_ONLY[name]
    // Without the VM's list, only block commands known to be terminal-only.
    if (alternative == null && known == null) return null
    val agent = provider.name
    return buildString {
        append("/$name only works in $agent's interactive terminal, and the app runs $agent headless on the VM.")
        if (!alternative.isNullOrEmpty()) append(" $alternative")
        append(" Type / to see what works here.")
    }
}

// Terminal-only commands, with what to use instead ("" when there is no equivalent).
private val TERMINAL_ONLY = mapOf(
    "config" to "Use the Mode, Model and Effort chips.",
    "permissions" to "Use the Mode chip.",
    "approvals" to "Use the Mode chip.",
    "memory" to "Open CLAUDE.md or AGENTS.md from Files.",
    "resume" to "Open the chat from the chat list.",
    "continue" to "Open the chat from the chat list.",
    "new" to "Start a new chat from the chat list.",
    "exit" to "Use the back button.",
    "quit" to "Use the back button.",
    "login" to "Sign in once in a terminal on the VM.",
    "logout" to "Run it in a terminal on the VM.",
    "doctor" to "Run it in a terminal on the VM.",
    "mcp" to "/status lists the MCP servers.",
    "agents" to "/status shows how many agents are set up.",
    "hooks" to "Edit the settings files from Files.",
    "add-dir" to "Start a chat in that project instead.",
    "export" to "Select the text in the chat to copy it.",
    "vim" to "Use the extra keys above the keyboard.",
    "terminal-setup" to "",
    "ide" to "",
    "theme" to "",
    "statusline" to "",
    "output-style" to "",
    "privacy-settings" to "",
    "release-notes" to "",
    "upgrade" to "",
    "bug" to "",
    "feedback" to "",
    "rewind" to "",
    "todos" to "",
    "plugin" to "",
)

private fun fallbackAgentCommands(provider: Provider): List<AgentCommand> = when (provider) {
    Provider.Claude -> listOf(
        AgentCommand("clear", "Start a fresh conversation"),
        AgentCommand("compact", "Summarize the conversation to free up context"),
        AgentCommand("context", "Show what is using the context window"),
        AgentCommand("init", "Create a CLAUDE.md guide for this project"),
        AgentCommand("review", "Review a pull request"),
        AgentCommand("security-review", "Review pending changes for security issues"),
        AgentCommand("usage", "Show plan usage limits"),
    )
    Provider.Codex -> listOf(
        AgentCommand("review", "Code review of uncommitted changes", "[--base <branch> | --commit <sha>] [instructions]"),
    )
    Provider.Cursor, Provider.Terminal -> emptyList()
}

fun modelChoices(provider: Provider): List<AgentChoice> = when (provider) {
    Provider.Claude -> listOf(
        AgentChoice("haiku", "Haiku"),
        AgentChoice("sonnet", "Sonnet"),
        AgentChoice("opus", "Opus"),
        AgentChoice("fable", "Fable"),
    )
    Provider.Cursor -> listOf(
        AgentChoice("auto", "Auto"),
        AgentChoice("composer-2.5", "Composer 2.5"),
        AgentChoice("gpt-5.6-sol-high", "GPT-5.6"),
        AgentChoice("gpt-5.3-codex-high", "Codex 5.3"),
        AgentChoice("claude-sonnet-5-thinking-high", "Sonnet 5"),
        AgentChoice("claude-opus-5-thinking-high", "Opus 5"),
    )
    Provider.Codex -> listOf(
        AgentChoice("gpt-5.4", "GPT-5.4"),
        AgentChoice("gpt-5.3-codex", "Codex"),
        AgentChoice("o3", "o3"),
    )
    Provider.Terminal -> emptyList()
}

fun modeChoices(provider: Provider): List<AgentChoice> = when (provider) {
    Provider.Terminal -> emptyList()
    else -> listOf(
        AgentChoice("agent", "Agent"),
        AgentChoice("auto", "Auto"),
        AgentChoice("plan", "Plan"),
        AgentChoice("ask", "Ask"),
    )
}

fun effortChoices(provider: Provider): List<AgentChoice> = when (provider) {
    Provider.Terminal -> emptyList()
    else -> listOf(
        AgentChoice("low", "Low"),
        AgentChoice("medium", "Medium"),
        AgentChoice("high", "High"),
        AgentChoice("xhigh", "Extra high"),
        AgentChoice("max", "Max"),
    )
}

fun choiceLabel(choices: List<AgentChoice>, id: String, emptyLabel: String): String {
    if (id.isBlank()) return emptyLabel
    return choices.find { it.id.equals(id, ignoreCase = true) }?.label ?: id
}

fun resolveChoice(choices: List<AgentChoice>, typed: String): String? {
    val query = typed.trim()
    if (query.isEmpty()) return null
    return choices.find { it.id.equals(query, ignoreCase = true) || it.label.equals(query, ignoreCase = true) }?.id
}

/** Commands for the text typed so far: name prefixes first, then any part after a colon, then substrings. */
fun matchingCommands(commands: List<SlashCommand>, text: String): List<SlashCommand> {
    val query = slashHead(text) ?: return emptyList()
    // Once there is a space the user is typing arguments.
    if (text.contains(' ')) return emptyList()
    if (query.isEmpty()) return commands
    fun rank(command: SlashCommand): Int = when {
        command.name.startsWith(query, ignoreCase = true) -> 0
        command.name.split(':', '-').any { it.startsWith(query, ignoreCase = true) } -> 1
        command.name.contains(query, ignoreCase = true) -> 2
        else -> 3
    }
    return commands.map { it to rank(it) }.filter { it.second < 3 }.sortedBy { it.second }.map { it.first }
}

fun matchingCommands(provider: Provider, text: String): List<SlashCommand> =
    matchingCommands(commandsFor(provider), text)

fun slashHead(text: String): String? {
    if (!text.startsWith("/") || text.contains("\n")) return null
    return text.removePrefix("/").substringBefore(" ")
}

fun transcriptBlocks(events: List<SessionEvent>): List<ChatBlock> {
    val blocks = mutableListOf<ChatBlock>()
    val output = StringBuilder()
    var outputSeq = 0L

    fun flush() {
        val text = output.toString().trim()
        output.clear()
        if (text.isNotEmpty()) blocks.add(ChatBlock.Agent(outputSeq, text))
    }

    for (event in events) {
        when (event.type) {
            "output" -> {
                val line = readableLine(event.text) ?: continue
                if (output.isEmpty()) outputSeq = event.seq else output.append("\n")
                output.append(line)
            }
            "thinking" -> {
                flush()
                if (event.text.isNotBlank()) blocks.add(ChatBlock.Thinking(event.seq, event.text.trim()))
            }
            "tool", "command" -> {
                flush()
                val call = event.text.trim()
                if (call.isNotBlank()) {
                    val last = blocks.lastOrNull()
                    if (last is ChatBlock.Tools) blocks[blocks.lastIndex] = last.copy(calls = last.calls + call)
                    else blocks.add(ChatBlock.Tools(event.seq, listOf(call)))
                }
            }
            "prompt", "user" -> {
                flush()
                if (event.text.isNotBlank()) blocks.add(ChatBlock.You(event.seq, event.text.trim()))
            }
            "assistant" -> {
                flush()
                if (event.text.isNotBlank()) blocks.add(ChatBlock.Agent(event.seq, event.text.trim()))
            }
            "status" -> {
                flush()
                val text = event.text.trim()
                if (text.isNotBlank() && !text.startsWith("{")) blocks.add(ChatBlock.Note(event.seq, text))
            }
            "error" -> {
                flush()
                val text = event.text.trim()
                if (text.isNotBlank()) blocks.add(ChatBlock.Error(event.seq, text))
            }
        }
    }
    flush()
    return blocks
}

fun messageWithContext(text: String, paths: List<String>): String {
    if (paths.isEmpty()) return text
    val lines = paths.joinToString("\n") { "- $it" }
    return "Use these files as context:\n$lines\n\n$text"
}

fun readableLine(raw: String): String? {
    val text = raw
        .replace(Regex("\u001B\\].*?(?:\u0007|\u001B\\\\)"), "")
        .replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
        .replace(Regex("\u001B[@-_]"), "")
        .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")
        .trim()
    if (text.isEmpty() || (text.startsWith("{") && text.endsWith("}"))) return null
    val lower = text.lowercase()
    if (
        lower.contains("ctrl+") ||
        lower.contains("to edit in") ||
        lower.contains("/effort") ||
        lower.contains("esc to interrupt")
    ) {
        return null
    }
    if (text.startsWith("/") && text.length <= 4 && !text.contains(" ")) return null
    val letters = text.count { it.isLetterOrDigit() }
    if (letters < 2) return null
    val boxes = text.count { it in BOX }
    if (boxes > 0 && boxes * 3 > text.length) return null
    return text
}

private const val BOX = "─│┌┐└┘├┤┬┴┼╭╮╯╰━┃┏┓┗┛┣┫┳┻╋═║╔╗╚╝╠╣╦╩╬"
