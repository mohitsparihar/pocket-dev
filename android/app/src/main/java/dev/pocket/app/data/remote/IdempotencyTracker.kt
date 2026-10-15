package dev.pocket.app.data.remote

class IdempotencyTracker {
    private val seen = mutableSetOf<String>()

    /** Returns true if this key was already seen (caller should skip the operation). */
    fun wasSeen(key: String): Boolean = !seen.add(key)

    fun reset() = seen.clear()
}
