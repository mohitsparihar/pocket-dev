package dev.pocket.app.google

import com.jcraft.jsch.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP tunnel over the same Cloud IAP WebSocket that `gcloud compute start-iap-tunnel` uses.
 * The destination is the VM's nic0 port, reached as the signed-in Google user.
 */
class IapTunnel(
    private val accessToken: String,
    private val project: String,
    private val zone: String,
    private val instance: String,
    private val port: Int,
) {
    private val closed = AtomicBoolean(false)
    private val opened = CountDownLatch(1)
    private val incoming = ArrayDeque<ByteArray>()
    private val incomingLock = Object()
    private var incomingOffset = 0
    private var received = 0L
    private var acked = 0L
    private var sent = 0L
    private var confirmed = 0L
    private var failure: String? = null
    private var webSocket: WebSocket? = null
    private val sendLock = Object()

    val inputStream: InputStream = IncomingStream()
    val outputStream: OutputStream = OutgoingStream()
    var readTimeoutMs: Int = 0

    fun open(timeoutSeconds: Long = 30) {
        val url = buildString {
            append("wss://tunnel.cloudproxy.app/v4/connect?")
            append("project=").append(encode(project))
            append("&zone=").append(encode(zone))
            append("&instance=").append(encode(instance))
            append("&interface=nic0")
            append("&port=").append(port)
            append("&newWebsocket=True")
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Origin", "bot:iap-tunneler")
            .header("User-Agent", "PocketDev/0.1")
            .header("Sec-WebSocket-Protocol", "relay.tunnel.cloudproxy.app")
            .build()
        val client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        webSocket = client.newWebSocket(request, Listener())
        if (!opened.await(timeoutSeconds, TimeUnit.SECONDS)) {
            close()
            throw IllegalStateException(failure ?: "The IAP tunnel did not open. Check that this account can use IAP TCP forwarding.")
        }
        failure?.let {
            close()
            throw IllegalStateException(it)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { webSocket?.close(1000, "close") }
        opened.countDown()
        synchronized(incomingLock) { incomingLock.notifyAll() }
        synchronized(sendLock) { sendLock.notifyAll() }
    }

    val isClosed: Boolean get() = closed.get()

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun fail(message: String) {
        failure = message
        close()
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            when (val message = IapFrames.parse(bytes.toByteArray())) {
                is IapMessage.Session -> {
                    webSocket.send(IapFrames.ack(0).toByteString())
                    opened.countDown()
                }
                is IapMessage.Data -> {
                    synchronized(incomingLock) {
                        incoming.add(message.payload)
                        received += message.payload.size
                        incomingLock.notifyAll()
                    }
                    acknowledge()
                }
                is IapMessage.Ack -> {
                    synchronized(sendLock) {
                        confirmed = message.confirmedBytes
                        sendLock.notifyAll()
                    }
                }
                IapMessage.Ignored -> Unit
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            failure = text.take(180).ifBlank { "IAP closed the tunnel" }
            close()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (opened.count == 1L) failure = reason.ifBlank { "IAP closed the tunnel ($code)" }
            close()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val http = response?.code?.let { "HTTP $it " }.orEmpty()
            failure = http + (t.message ?: "IAP tunnel failed")
            close()
        }
    }

    private fun acknowledge() {
        val snapshot = synchronized(incomingLock) { received }
        if (snapshot <= acked) return
        val socket = webSocket ?: return
        if (socket.send(IapFrames.ack(snapshot).toByteString())) {
            acked = snapshot
        }
    }

    private fun sendPayload(payload: ByteArray) {
        var offset = 0
        while (offset < payload.size) {
            if (closed.get()) throw java.io.IOException("IAP tunnel closed")
            val end = minOf(payload.size, offset + IapFrames.MAX_DATA)
            val chunk = payload.copyOfRange(offset, end)
            synchronized(sendLock) {
                val deadline = System.currentTimeMillis() + 20_000
                while (sent - confirmed > 512 * 1024 && !closed.get()) {
                    val wait = deadline - System.currentTimeMillis()
                    if (wait <= 0) throw java.io.IOException("IAP tunnel stopped accepting data")
                    sendLock.wait(wait)
                }
            }
            val socket = webSocket ?: throw java.io.IOException("IAP tunnel closed")
            if (!socket.send(IapFrames.data(chunk).toByteString())) {
                throw java.io.IOException("IAP tunnel closed while sending")
            }
            synchronized(sendLock) { sent += chunk.size }
            offset = end
        }
    }

    private inner class IncomingStream : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            synchronized(incomingLock) {
                val deadline = if (readTimeoutMs > 0) System.currentTimeMillis() + readTimeoutMs else Long.MAX_VALUE
                while (incoming.isEmpty() && !closed.get()) {
                    val wait = if (readTimeoutMs > 0) deadline - System.currentTimeMillis() else 0L
                    if (readTimeoutMs > 0 && wait <= 0) throw java.net.SocketTimeoutException("IAP read timed out")
                    incomingLock.wait(if (readTimeoutMs > 0) wait else 0L)
                }
                if (incoming.isEmpty()) return -1
                val chunk = incoming.first()
                val available = chunk.size - incomingOffset
                val count = minOf(len, available)
                System.arraycopy(chunk, incomingOffset, buffer, off, count)
                incomingOffset += count
                if (incomingOffset >= chunk.size) {
                    incoming.removeFirst()
                    incomingOffset = 0
                }
                return count
            }
        }
    }

    private inner class OutgoingStream : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(buffer: ByteArray, off: Int, len: Int) {
            sendPayload(buffer.copyOfRange(off, off + len))
        }
    }
}

class IapSocketFactory(private val tunnel: IapTunnel) : SocketFactory {
    override fun createSocket(host: String, port: Int): Socket = IapSocket(tunnel, host, port)
    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}

private class IapSocket(
    private val tunnel: IapTunnel,
    private val remoteHost: String,
    private val remotePort: Int,
) : Socket() {
    override fun getInputStream(): InputStream = tunnel.inputStream
    override fun getOutputStream(): OutputStream = tunnel.outputStream
    override fun isConnected() = !tunnel.isClosed
    override fun isBound() = true
    override fun isClosed() = tunnel.isClosed
    override fun close() = tunnel.close()
    override fun setTcpNoDelay(on: Boolean) = Unit
    override fun setKeepAlive(on: Boolean) = Unit
    override fun setSoTimeout(timeout: Int) {
        tunnel.readTimeoutMs = timeout
    }
    override fun getSoTimeout() = tunnel.readTimeoutMs
    override fun getInetAddress(): InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    override fun getPort() = remotePort
    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(getInetAddress(), remotePort)
    override fun getLocalSocketAddress(): SocketAddress = InetSocketAddress(getInetAddress(), 0)
    override fun shutdownOutput() = Unit
    override fun shutdownInput() = Unit
}
