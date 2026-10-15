package dev.pocket.app.ui.git

import dev.pocket.app.model.BranchInfo

/**
 * Branches matching every word of [query] (so "fix crash" finds
 * fix/crash-123), the current branch first, then local, then remote.
 */
fun filterBranches(branches: List<BranchInfo>, query: String): List<BranchInfo> {
    val words = query.trim().lowercase().split(Regex("[\\s/]+")).filter { it.isNotEmpty() }
    return branches
        .filter { branch -> words.all { branch.name.lowercase().contains(it) } }
        .sortedWith(compareBy<BranchInfo>({ !it.current }, { it.remote }, { it.name.lowercase() }))
}
