package dev.pocket.app

import dev.pocket.app.model.BranchInfo
import dev.pocket.app.ui.git.filterBranches
import org.junit.Assert.assertEquals
import org.junit.Test

class BranchFilterTest {
    private val branches = listOf(
        BranchInfo("origin/main", current = false, remote = true),
        BranchInfo("fix/crash-123", current = false),
        BranchInfo("main", current = true),
        BranchInfo("origin/fix/crash-123", current = false, remote = true),
        BranchInfo("feature/Login", current = false),
    )

    @Test
    fun currentFirstThenLocalThenRemote() {
        assertEquals(
            listOf("main", "feature/Login", "fix/crash-123", "origin/fix/crash-123", "origin/main"),
            filterBranches(branches, "").map { it.name },
        )
    }

    @Test
    fun searchMatchesEveryWordIgnoringCase() {
        assertEquals(listOf("fix/crash-123", "origin/fix/crash-123"), filterBranches(branches, "fix crash").map { it.name })
        assertEquals(listOf("feature/Login"), filterBranches(branches, "LOGIN").map { it.name })
        assertEquals(listOf("fix/crash-123", "origin/fix/crash-123"), filterBranches(branches, "fix/crash").map { it.name })
        assertEquals(emptyList<String>(), filterBranches(branches, "nope").map { it.name })
    }
}
