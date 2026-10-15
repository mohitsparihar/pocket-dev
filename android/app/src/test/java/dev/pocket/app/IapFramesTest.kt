package dev.pocket.app

import dev.pocket.app.google.IapFrames
import dev.pocket.app.google.IapMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IapFramesTest {
    @Test
    fun dataFrameRoundTrips() {
        val frame = IapFrames.data("ssh".encodeToByteArray())
        val parsed = IapFrames.parse(frame)
        assertTrue(parsed is IapMessage.Data)
        assertEquals("ssh", (parsed as IapMessage.Data).payload.decodeToString())
    }

    @Test
    fun ackFrameRoundTrips() {
        val parsed = IapFrames.parse(IapFrames.ack(4096))
        assertTrue(parsed is IapMessage.Ack)
        assertEquals(4096L, (parsed as IapMessage.Ack).confirmedBytes)
    }
}
