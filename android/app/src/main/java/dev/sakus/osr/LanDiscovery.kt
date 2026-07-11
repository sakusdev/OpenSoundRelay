// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import android.os.Build
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object LanDiscovery {
    const val DISCOVERY_PORT = 40_125
    private const val WIRE_VERSION: Byte = 1
    private const val MESSAGE_PROBE: Byte = 1
    private const val MESSAGE_ANNOUNCEMENT: Byte = 2
    private const val HEADER_LEN = 20
    private const val MAX_NAME_BYTES = 96

    const val ROLE_IDLE: Byte = 0
    const val ROLE_SENDER: Byte = 1
    const val ROLE_RECEIVER: Byte = 2
    const val ROLE_DUPLEX: Byte = 3

    const val CAP_AUDIO_RECEIVE = 1 shl 0
    const val CAP_AUDIO_SEND = 1 shl 1
    const val CAP_STREAM_VOLUME = 1 shl 2
    const val CAP_NATIVE_VOLUME = 1 shl 3
    const val CAP_ADAPTIVE_LATENCY = 1 shl 4
    const val CAP_TONE_CONTROLS = 1 shl 5

    data class Device(
        val name: String,
        val address: InetSocketAddress,
        val role: Byte,
        val capabilities: Int,
    )

    private data class Message(
        val type: Byte,
        val role: Byte,
        val capabilities: Int,
        val audioPort: Int,
        val nonce: Long,
        val name: String,
    )

    fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return listOf(manufacturer, model)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "OSR Android" }
    }

    fun scan(
        scannerName: String,
        timeoutMs: Long = 1_400,
        callback: (Result<List<Device>>) -> Unit,
    ) {
        thread(name = "osr-lan-scan") {
            callback(runCatching { scanBlocking(scannerName, timeoutMs) })
        }
    }

    private fun scanBlocking(scannerName: String, timeoutMs: Long): List<Device> {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(0))
            soTimeout = 75
        }
        val nonce = System.nanoTime() xor (System.currentTimeMillis() shl 11)
        val probePayload = encodeMessage(
            Message(
                type = MESSAGE_PROBE,
                role = ROLE_IDLE,
                capabilities = 0,
                audioPort = 0,
                nonce = nonce,
                name = scannerName,
            ),
        )
        val probe = OsrProtocol.encodePacket(OsrProtocol.KIND_HELLO, 1, probePayload)
        val broadcast = InetSocketAddress(InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
        val started = SystemClock.elapsedRealtime()
        var lastProbe = 0L
        val devices = linkedMapOf<String, Device>()
        val buffer = ByteArray(512)

        try {
            while (SystemClock.elapsedRealtime() - started < timeoutMs) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastProbe >= 250) {
                    runCatching { socket.send(DatagramPacket(probe, probe.size, broadcast)) }
                    lastProbe = now
                }

                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val decoded = OsrProtocol.decodePacket(packet.data, packet.length) ?: continue
                if (decoded.kind != OsrProtocol.KIND_HELLO) continue
                val message = decodeMessage(decoded.payload) ?: continue
                if (message.type != MESSAGE_ANNOUNCEMENT || message.nonce != nonce || message.audioPort == 0) {
                    continue
                }
                val address = InetSocketAddress(packet.address, message.audioPort)
                val key = "${address.address.hostAddress}:${address.port}"
                devices[key] = Device(
                    name = message.name.ifBlank { packet.address.hostAddress ?: "OSR device" },
                    address = address,
                    role = message.role,
                    capabilities = message.capabilities,
                )
            }
        } finally {
            socket.close()
        }
        return devices.values.sortedBy { it.name.lowercase() }
    }

    internal fun encodeAnnouncement(
        deviceName: String,
        audioPort: Int,
        nonce: Long,
    ): ByteArray {
        val capabilities = CAP_AUDIO_RECEIVE or CAP_STREAM_VOLUME or CAP_NATIVE_VOLUME or
            CAP_ADAPTIVE_LATENCY or CAP_TONE_CONTROLS
        return encodeMessage(
            Message(
                type = MESSAGE_ANNOUNCEMENT,
                role = ROLE_RECEIVER,
                capabilities = capabilities,
                audioPort = audioPort,
                nonce = nonce,
                name = deviceName,
            ),
        )
    }

    internal fun decodeProbe(payload: ByteArray): Long? {
        val message = decodeMessage(payload) ?: return null
        return message.nonce.takeIf { message.type == MESSAGE_PROBE }
    }

    private fun encodeMessage(message: Message): ByteArray {
        val nameBytes = truncateUtf8(message.name, MAX_NAME_BYTES).toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(HEADER_LEN + nameBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(WIRE_VERSION)
            .put(message.type)
            .put(message.role)
            .put(0.toByte())
            .putInt(message.capabilities)
            .putShort(message.audioPort.coerceIn(0, 65_535).toShort())
            .putShort(nameBytes.size.toShort())
            .putLong(message.nonce)
            .put(nameBytes)
            .array()
    }

    private fun decodeMessage(payload: ByteArray): Message? {
        if (payload.size < HEADER_LEN) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        if (buffer.get() != WIRE_VERSION) return null
        val type = buffer.get()
        val role = buffer.get()
        buffer.get()
        val capabilities = buffer.int
        val audioPort = buffer.short.toInt() and 0xffff
        val nameLength = buffer.short.toInt() and 0xffff
        val nonce = buffer.long
        if (nameLength > MAX_NAME_BYTES || payload.size != HEADER_LEN + nameLength) return null
        val nameBytes = ByteArray(nameLength)
        buffer.get(nameBytes)
        return Message(
            type = type,
            role = role,
            capabilities = capabilities,
            audioPort = audioPort,
            nonce = nonce,
            name = nameBytes.toString(Charsets.UTF_8).trim(),
        )
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val builder = StringBuilder()
        for (character in value) {
            val next = builder.toString() + character
            if (next.toByteArray(Charsets.UTF_8).size > maxBytes) break
            builder.append(character)
        }
        return builder.toString()
    }
}

class LanDiscoveryResponder(
    private val deviceName: String,
    private val audioPort: Int,
    private val status: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = thread(name = "osr-lan-discovery-responder") {
            runLoop()
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        worker?.interrupt()
        worker = null
    }

    private fun runLoop() {
        var packetSequence = 1L
        val localSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(LanDiscovery.DISCOVERY_PORT))
                soTimeout = 250
            }
        } catch (error: Throwable) {
            running.set(false)
            status("LAN discovery unavailable: ${error.message}")
            return
        }
        socket = localSocket
        val buffer = ByteArray(512)

        try {
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    localSocket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (error: SocketException) {
                    if (!running.get()) break
                    throw error
                }
                val decoded = OsrProtocol.decodePacket(packet.data, packet.length) ?: continue
                if (decoded.kind != OsrProtocol.KIND_HELLO) continue
                val nonce = LanDiscovery.decodeProbe(decoded.payload) ?: continue
                val payload = LanDiscovery.encodeAnnouncement(deviceName, audioPort, nonce)
                val response = OsrProtocol.encodePacket(
                    OsrProtocol.KIND_HELLO,
                    packetSequence++,
                    payload,
                )
                localSocket.send(
                    DatagramPacket(response, response.size, packet.address, packet.port),
                )
            }
        } catch (error: Throwable) {
            if (running.get()) status("LAN discovery error: ${error.message}")
        } finally {
            running.set(false)
            localSocket.close()
            socket = null
        }
    }
}
