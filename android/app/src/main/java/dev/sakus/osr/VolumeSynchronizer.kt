// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

class VolumeSynchronizer(initial: OsrProtocol.VolumeCommand) {
    var current: OsrProtocol.VolumeCommand = initial
        private set

    fun applyParentCommand(command: OsrProtocol.VolumeCommand): Boolean {
        if (current.streamId != 0 && command.streamId != current.streamId) {
            return false
        }

        val newer = command.epoch > current.epoch ||
            (command.epoch == current.epoch && command.sequence > current.sequence)

        if (!newer) return false
        current = command
        return true
    }

    fun applyGainToPcmS16Le(buffer: ByteArray, length: Int) {
        val gain = current.gainPpm.coerceIn(0, 2_000_000)
        val muted = current.muted || gain == 0
        var index = 0
        while (index + 1 < length) {
            if (muted) {
                buffer[index] = 0
                buffer[index + 1] = 0
            } else {
                val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff)).toShort().toInt()
                val scaled = (sample.toLong() * gain.toLong() / 1_000_000L).coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toInt()
                buffer[index] = (scaled and 0xff).toByte()
                buffer[index + 1] = ((scaled ushr 8) and 0xff).toByte()
            }
            index += 2
        }
    }
}
