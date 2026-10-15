package dev.pocket.app.google

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object IapFrames {
    const val TAG_SID = 0x0001
    const val TAG_DATA = 0x0004
    const val TAG_ACK = 0x0007
    const val MAX_DATA = 16384

    fun data(payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(6 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(TAG_DATA.toShort())
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun ack(totalBytesReceived: Long): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(TAG_ACK.toShort())
        buffer.putLong(totalBytesReceived)
        return buffer.array()
    }

    fun parse(message: ByteArray): IapMessage {
        val buffer = ByteBuffer.wrap(message).order(ByteOrder.BIG_ENDIAN)
        if (buffer.remaining() < 2) return IapMessage.Ignored
        val tag = buffer.short.toInt() and 0xFFFF
        return when (tag) {
            TAG_SID -> {
                if (buffer.remaining() < 4) return IapMessage.Ignored
                val length = buffer.int
                if (length < 0 || buffer.remaining() < length) return IapMessage.Ignored
                val sid = ByteArray(length)
                buffer.get(sid)
                IapMessage.Session(sid.decodeToString())
            }
            TAG_DATA -> {
                if (buffer.remaining() < 4) return IapMessage.Ignored
                val length = buffer.int
                if (length < 0 || buffer.remaining() < length) return IapMessage.Ignored
                val payload = ByteArray(length)
                buffer.get(payload)
                IapMessage.Data(payload)
            }
            TAG_ACK -> {
                if (buffer.remaining() < 8) return IapMessage.Ignored
                IapMessage.Ack(buffer.long)
            }
            else -> IapMessage.Ignored
        }
    }
}

internal sealed class IapMessage {
    data class Session(val sid: String) : IapMessage()
    data class Data(val payload: ByteArray) : IapMessage()
    data class Ack(val confirmedBytes: Long) : IapMessage()
    data object Ignored : IapMessage()
}
