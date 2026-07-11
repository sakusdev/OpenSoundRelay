// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import java.nio.ByteBuffer
import java.nio.ByteOrder

object OsrProtocol {
    private val magic = byteArrayOf('O'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
    const val PROTOCOL_VERSION: Short = 1
    const val KIND_HELLO: Short = 1
    const val KIND_AUDIO: Short = 2
    const val KIND_VOLUME_COMMAND: Short = 3
    const val KIND_TIME_SYNC: Short = 4
    const val KIND_DEVICE_VOLUME: Short = 5
    const val PACKET_HEADER_LEN = 28
    const val AUDIO_HEADER_LEN = 36
    const val VOLUME_COMMAND_LEN = 40
    const val DEVICE_VOLUME_COMMAND_LEN = 24

    const val CODEC_PCM_S16LE = 1
    const val CODEC_OPUS = 2
    const val SAMPLE_FORMAT_NONE = 0
    const val SAMPLE_FORMAT_S16LE = 1

    data class Packet(
        val kind: Short,
        val flags: Short,
        val sequence: Long,
        val payload: ByteArray,
    )

    data class AudioFrame(
        val streamId: Int,
        val mediaTimeUs: Long,
        val frameSequence: Long,
        val sampleRateHz: Int,
        val channels: Int,
        val codec: Int,
        val sampleFormat: Int,
        val frameDurationUs: Int,
        val payload: ByteArray,
    )

    data class VolumeCommand(
        val streamId: Int,
        val epoch: Long,
        val sequence: Long,
        val gainPpm: Int,
        val muted: Boolean,
        val targetMediaTimeUs: Long,
    )

    data class DeviceVolumeCommand(
        val epoch: Long,
        val sequence: Long,
        val volumePercent: Int,
        val muted: Boolean,
    )

    fun encodePacket(kind: Short, sequence: Long, payload: ByteArray, flags: Short = 0): ByteArray {
        val buffer = ByteBuffer.allocate(PACKET_HEADER_LEN + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(magic)
        buffer.putShort(PROTOCOL_VERSION)
        buffer.putShort(kind)
        buffer.putShort(flags)
        buffer.put(ByteArray(6))
        buffer.putLong(sequence)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun decodePacket(input: ByteArray, length: Int): Packet? {
        if (length < PACKET_HEADER_LEN) return null
        if (input[0] != magic[0] || input[1] != magic[1] || input[2] != magic[2] || input[3] != magic[3]) return null

        val buffer = ByteBuffer.wrap(input, 0, length).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val version = buffer.short
        if (version != PROTOCOL_VERSION) return null

        val kind = buffer.short
        val flags = buffer.short
        buffer.position(16)
        val sequence = buffer.long
        val payloadLen = buffer.int
        if (payloadLen < 0 || payloadLen != length - PACKET_HEADER_LEN) return null

        val payload = input.copyOfRange(PACKET_HEADER_LEN, length)
        return Packet(kind, flags, sequence, payload)
    }

    fun encodeAudioFrame(
        streamId: Int,
        mediaTimeUs: Long,
        frameSequence: Long,
        sampleRateHz: Int,
        channels: Int,
        codec: Int,
        sampleFormat: Int,
        frameDurationUs: Int,
        audioPayload: ByteArray,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(AUDIO_HEADER_LEN + audioPayload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(streamId)
        buffer.putLong(mediaTimeUs)
        buffer.putLong(frameSequence)
        buffer.putInt(sampleRateHz)
        buffer.put(channels.toByte())
        buffer.put(codec.toByte())
        buffer.put(sampleFormat.toByte())
        buffer.put(0.toByte())
        buffer.putInt(frameDurationUs)
        buffer.putInt(audioPayload.size)
        buffer.put(audioPayload)
        return buffer.array()
    }

    fun encodePcmAudioFrame(
        streamId: Int,
        mediaTimeUs: Long,
        frameSequence: Long,
        sampleRateHz: Int,
        channels: Int,
        frameDurationUs: Int,
        pcmPayload: ByteArray,
    ): ByteArray = encodeAudioFrame(
        streamId = streamId,
        mediaTimeUs = mediaTimeUs,
        frameSequence = frameSequence,
        sampleRateHz = sampleRateHz,
        channels = channels,
        codec = CODEC_PCM_S16LE,
        sampleFormat = SAMPLE_FORMAT_S16LE,
        frameDurationUs = frameDurationUs,
        audioPayload = pcmPayload,
    )

    fun decodeAudioFrame(payload: ByteArray): AudioFrame? {
        if (payload.size < AUDIO_HEADER_LEN) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val streamId = buffer.int
        val mediaTimeUs = buffer.long
        val frameSequence = buffer.long
        val sampleRateHz = buffer.int
        val channels = buffer.get().toInt() and 0xff
        val codec = buffer.get().toInt() and 0xff
        val sampleFormat = buffer.get().toInt() and 0xff
        buffer.get()
        val frameDurationUs = buffer.int
        val payloadLen = buffer.int
        if (payloadLen < 0 || payloadLen != payload.size - AUDIO_HEADER_LEN) return null
        val audioPayload = payload.copyOfRange(AUDIO_HEADER_LEN, payload.size)
        return AudioFrame(
            streamId = streamId,
            mediaTimeUs = mediaTimeUs,
            frameSequence = frameSequence,
            sampleRateHz = sampleRateHz,
            channels = channels,
            codec = codec,
            sampleFormat = sampleFormat,
            frameDurationUs = frameDurationUs,
            payload = audioPayload,
        )
    }

    fun encodeVolumeCommand(command: VolumeCommand): ByteArray {
        val buffer = ByteBuffer.allocate(VOLUME_COMMAND_LEN).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(command.streamId)
        buffer.putLong(command.epoch)
        buffer.putLong(command.sequence)
        buffer.putInt(command.gainPpm.coerceIn(0, 2_000_000))
        buffer.put((if (command.muted) 1 else 0).toByte())
        buffer.put(ByteArray(7))
        buffer.putLong(command.targetMediaTimeUs)
        return buffer.array()
    }

    fun decodeVolumeCommand(payload: ByteArray): VolumeCommand? {
        if (payload.size != VOLUME_COMMAND_LEN) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val streamId = buffer.int
        val epoch = buffer.long
        val sequence = buffer.long
        val gainPpm = buffer.int.coerceIn(0, 2_000_000)
        val muted = buffer.get().toInt() != 0
        buffer.position(32)
        val targetMediaTimeUs = buffer.long
        return VolumeCommand(streamId, epoch, sequence, gainPpm, muted, targetMediaTimeUs)
    }

    fun encodeDeviceVolumeCommand(command: DeviceVolumeCommand): ByteArray {
        val buffer = ByteBuffer.allocate(DEVICE_VOLUME_COMMAND_LEN).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(command.epoch)
        buffer.putLong(command.sequence)
        buffer.putShort(command.volumePercent.coerceIn(0, 100).toShort())
        buffer.put((if (command.muted) 1 else 0).toByte())
        buffer.put(ByteArray(5))
        return buffer.array()
    }

    fun decodeDeviceVolumeCommand(payload: ByteArray): DeviceVolumeCommand? {
        if (payload.size != DEVICE_VOLUME_COMMAND_LEN) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val epoch = buffer.long
        val sequence = buffer.long
        val volumePercent = (buffer.short.toInt() and 0xffff).coerceIn(0, 100)
        val muted = buffer.get().toInt() != 0
        return DeviceVolumeCommand(epoch, sequence, volumePercent, muted)
    }
}
