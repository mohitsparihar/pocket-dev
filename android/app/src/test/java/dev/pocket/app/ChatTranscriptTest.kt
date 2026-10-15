package dev.pocket.app

import dev.pocket.app.model.AgentCommand
import dev.pocket.app.model.Provider
import dev.pocket.app.model.SessionEvent
import dev.pocket.app.ui.chats.ChatBlock
import dev.pocket.app.ui.chats.SlashKind
import dev.pocket.app.ui.chats.commandsFor
import dev.pocket.app.ui.chats.matchingCommands
import dev.pocket.app.ui.chats.resolveChoice
import dev.pocket.app.ui.chats.modelChoices
import dev.pocket.app.ui.chats.toolParts
import dev.pocket.app.ui.chats.unavailableCommandReason
import org.junit.Assert.assertNotNull
import dev.pocket.app.ui.chats.transcriptBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTranscriptTest {
    @Test
    fun hidesTerminalChromeAndKeepsTheConversation() {
        val blocks = transcriptBlocks(
            listOf(
                SessionEvent(1, "s", "output", "\u001B[2G", 1),
                SessionEvent(2, "s", "output", "ctrl+g to edit in Vim", 2),
                SessionEvent(3, "s", "output", "●high · /effort", 3),
                SessionEvent(4, "s", "prompt", "hi", 4),
                SessionEvent(5, "s", "thinking", "Look at the filter row.", 5),
                SessionEvent(6, "s", "assistant", "The archived rows are still included.", 6),
                SessionEvent(7, "s", "tool", "Read  filters.tsx", 7),
            )
        )
        assertEquals(ChatBlock.You(4, "hi"), blocks[0])
        assertEquals(ChatBlock.Thinking(5, "Look at the filter row."), blocks[1])
        assertEquals(ChatBlock.Agent(6, "The archived rows are still included."), blocks[2])
        assertEquals(ChatBlock.Tools(7, listOf("Read  filters.tsx")), blocks[3])
        assertEquals(4, blocks.size)
    }

    @Test
    fun groupsConsecutiveToolCallsAndSeparatesErrors() {
        val blocks = transcriptBlocks(
            listOf(
                SessionEvent(1, "s", "tool", "Read  a.ts", 1),
                SessionEvent(2, "s", "tool", "Edit  a.ts", 2),
                SessionEvent(3, "s", "assistant", "Done.", 3),
                SessionEvent(4, "s", "tool", "Bash  npm test", 4),
                SessionEvent(5, "s", "error", "Tests failed", 5),
            )
        )
        assertEquals(ChatBlock.Tools(1, listOf("Read  a.ts", "Edit  a.ts")), blocks[0])
        assertEquals(ChatBlock.Agent(3, "Done."), blocks[1])
        assertEquals(ChatBlock.Tools(4, listOf("Bash  npm test")), blocks[2])
        assertEquals(ChatBlock.Error(5, "Tests failed"), blocks[3])
        assertEquals("Bash" to "npm test", toolParts("Bash  npm test"))
    }

    @Test
    fun slashMenuFiltersClaudeCommands() {
        val matches = matchingCommands(Provider.Claude, "/eff")
        assertEquals(listOf("effort"), matches.map { it.name })
        assertEquals(SlashKind.Effort, matches.first().kind)
        assertTrue(matchingCommands(Provider.Terminal, "/").isEmpty())
    }

    @Test
    fun agentCommandsFromTheVmJoinTheAppCommands() {
        val reported = listOf(
            AgentCommand("compact", "Summarize"),
            AgentCommand("codex:review", "Plugin review", source = "plugin"),
            AgentCommand("model", "TUI only"),
        )
        val commands = commandsFor(Provider.Claude, reported)
        // The app's /model wins over a same-named agent command.
        assertEquals(SlashKind.Model, commands.single { it.name == "model" }.kind)
        assertEquals(SlashKind.Agent, commands.single { it.name == "compact" }.kind)
        // Typing part of a plugin command finds it.
        assertEquals(listOf("codex:review"), matchingCommands(commands, "/revi").map { it.name })
        // Arguments being typed close the menu.
        assertTrue(matchingCommands(commands, "/compact focus on tests").isEmpty())
    }

    @Test
    fun withoutTheVmListClaudeOffersHeadlessBuiltIns() {
        val names = commandsFor(Provider.Claude).map { it.name }
        assertTrue("compact" in names && "init" in names && "help" in names)
        assertTrue(commandsFor(Provider.Cursor).none { it.kind == SlashKind.Agent })
    }

    @Test
    fun statusIsAnAppCommandAndTerminalOnlyCommandsAreExplained() {
        val commands = commandsFor(Provider.Claude, listOf(AgentCommand("compact", "")))
        assertEquals(SlashKind.Status, commands.single { it.name == "status" }.kind)
        // Known terminal-only command: explained even before the VM list loads.
        assertTrue(unavailableCommandReason(Provider.Claude, "/permissions", null)!!.contains("Mode chip"))
        // Unknown command: only blocked once the VM's list is known.
        assertNull(unavailableCommandReason(Provider.Claude, "/frobnicate", null))
        assertNotNull(unavailableCommandReason(Provider.Claude, "/frobnicate", commands))
        // Real commands, arguments and paths go through.
        assertNull(unavailableCommandReason(Provider.Claude, "/compact keep the tests", commands))
        assertNull(unavailableCommandReason(Provider.Claude, "/src/app.ts is broken", commands))
        assertNull(unavailableCommandReason(Provider.Claude, "fix the bug", commands))
    }

    @Test
    fun modelAliasResolves() {
        assertEquals("sonnet", resolveChoice(modelChoices(Provider.Claude), "Sonnet"))
        assertNull(resolveChoice(modelChoices(Provider.Claude), "nope"))
    }
}
