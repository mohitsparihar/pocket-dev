package dev.pocket.app

import dev.pocket.app.data.remote.IdempotencyTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdempotencyTest {

    @Test
    fun `first use of a key is not seen`() {
        val tracker = IdempotencyTracker()
        assertFalse("First use should return false (not yet seen)", tracker.wasSeen("key-1"))
    }

    @Test
    fun `second use of the same key is seen`() {
        val tracker = IdempotencyTracker()
        tracker.wasSeen("key-1")
        assertTrue("Repeat use of same key should return true", tracker.wasSeen("key-1"))
    }

    @Test
    fun `different keys are independent`() {
        val tracker = IdempotencyTracker()
        tracker.wasSeen("key-a")
        assertFalse("A different key should not be affected", tracker.wasSeen("key-b"))
    }

    @Test
    fun `key seen three times only applies once`() {
        val tracker = IdempotencyTracker()
        var applications = 0
        repeat(3) {
            if (!tracker.wasSeen("key-x")) applications++
        }
        assertTrue("Operation should only apply once across three attempts", applications == 1)
    }

    @Test
    fun `reset clears all seen keys`() {
        val tracker = IdempotencyTracker()
        tracker.wasSeen("key-1")
        tracker.wasSeen("key-2")
        tracker.reset()
        assertFalse("After reset, key-1 should be fresh", tracker.wasSeen("key-1"))
        assertFalse("After reset, key-2 should be fresh", tracker.wasSeen("key-2"))
    }

    @Test
    fun `many distinct keys all start unseen`() {
        val tracker = IdempotencyTracker()
        val keys = (1..100).map { "op-$it" }
        val results = keys.map { tracker.wasSeen(it) }
        assertTrue("All distinct keys should be unseen on first use", results.none { it })
    }

    @Test
    fun `prompt idempotency scenario`() {
        // Simulates: client sends prompt with same key twice (e.g. after network retry).
        val tracker = IdempotencyTracker()
        val idKey = "prompt:session-1:1734000000000"

        var promptsSent = 0
        repeat(2) {
            if (!tracker.wasSeen(idKey)) {
                promptsSent++ // would be actual API call
            }
        }
        assertTrue("Prompt should only be sent once despite two attempts", promptsSent == 1)
    }
}
