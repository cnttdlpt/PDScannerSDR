package vn.pdscanner.sdr

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RtlTcpClient(
    private val host: String,
    private val port: Int,
    val sampleRate: Int,
    private val onConnected: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val queue = ArrayBlockingQueue<ByteArray>(96)
    @Volatile var isConnected: Boolean = false
        private set

    @Volatile private var running = false
    private var socket: Socket? = null
    private var readerThread: Thread? = null
    private val outputLock = Any()

    fun start() {
        if (running) return
        running = true
        readerThread = thread(name = "rtl_tcp-reader") {
            var lastError: Exception? = null

            // The Android SDR driver may need a moment after returning RESULT_OK.
            for (attempt in 1..15) {
                if (!running) return@thread
                try {
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.receiveBufferSize = 1024 * 1024
                    s.connect(InetSocketAddress(host, port), 1000)
                    socket = s

                    val input = s.getInputStream()
                    val header = readExactly(input, 12)
                    if (!(header[0].toInt().toChar() == 'R' &&
                                header[1].toInt().toChar() == 'T' &&
                                header[2].toInt().toChar() == 'L' &&
                                header[3].toInt().toChar() == '0')) {
                        throw IllegalStateException("rtl_tcp header không hợp lệ")
                    }

                    isConnected = true

                    // Force our preferred sample rate and automatic tuner gain.
                    sendCommand(0x02, sampleRate.toLong())
                    sendCommand(0x03, 0) // automatic tuner gain mode
                    sendCommand(0x08, 1) // RTL AGC on

                    onConnected()

                    val buffer = ByteArray(32 * 1024)
                    while (running) {
                        val n = input.read(buffer)
                        if (n < 0) throw EOFException("SDR đã ngắt kết nối")
                        if (n == 0) continue

                        val copy = buffer.copyOf(n)
                        if (!queue.offer(copy)) {
                            queue.poll()
                            queue.offer(copy)
                        }
                    }
                    return@thread
                } catch (e: Exception) {
                    lastError = e
                    isConnected = false
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null

                    if (attempt < 15 && running) {
                        Thread.sleep(250)
                    }
                }
            }

            if (running) {
                onError("Không kết nối được rtl_tcp: ${lastError?.message ?: "không rõ lỗi"}")
            }
        }
    }

    fun setFrequency(hz: Long) {
        sendCommand(0x01, hz)
    }

    fun clearBufferedIq() {
        queue.clear()
    }

    /**
     * Reads approximately durationMs of unsigned 8-bit interleaved I/Q.
     * Each complex sample is two bytes: I,Q.
     */
    fun readWindow(durationMs: Int): ByteArray {
        if (!isConnected) throw IllegalStateException("SDR chưa kết nối")

        val expected = ((sampleRate.toLong() * 2L * durationMs) / 1000L)
            .coerceAtLeast(4096L)
            .coerceAtMost(8_000_000L)
            .toInt()

        val out = ByteArrayOutputStream(expected)
        val deadline = System.currentTimeMillis() + maxOf(2500L, durationMs * 6L)

        while (out.size() < expected && System.currentTimeMillis() < deadline) {
            val block = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            val remain = expected - out.size()
            out.write(block, 0, minOf(remain, block.size))
        }

        if (out.size() < expected / 3) {
            throw IllegalStateException("Không nhận đủ mẫu I/Q từ SDR")
        }
        return out.toByteArray()
    }

    fun stop() {
        running = false
        isConnected = false
        queue.clear()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun sendCommand(command: Int, parameter: Long) {
        val s = socket ?: return
        synchronized(outputLock) {
            try {
                val packet = ByteBuffer.allocate(5)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(command.toByte())
                    .putInt((parameter and 0xFFFFFFFFL).toInt())
                    .array()
                s.getOutputStream().write(packet)
                s.getOutputStream().flush()
            } catch (e: Exception) {
                if (running) onError("Lỗi gửi lệnh SDR: ${e.message}")
            }
        }
    }

    private fun readExactly(input: java.io.InputStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(out, offset, size - offset)
            if (n < 0) throw EOFException("Mất kết nối khi đọc rtl_tcp header")
            offset += n
        }
        return out
    }
}
