// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import java.util.PriorityQueue

class PcmJitterBuffer(
    private val targetFrames: Int = 3,
    private val maxFrames: Int = 16,
) {
    private val frames = PriorityQueue<OsrProtocol.AudioFrame>(compareBy { it.frameSequence })
    private var lastPlayedSequence = 0L

    fun push(frame: OsrProtocol.AudioFrame): List<OsrProtocol.AudioFrame> {
        if (frame.frameSequence <= lastPlayedSequence) {
            return emptyList()
        }

        frames.add(frame)
        while (frames.size > maxFrames) {
            frames.poll()
        }

        val ready = mutableListOf<OsrProtocol.AudioFrame>()
        while (frames.size > targetFrames) {
            val next = frames.poll() ?: break
            if (next.frameSequence <= lastPlayedSequence) continue
            lastPlayedSequence = next.frameSequence
            ready.add(next)
        }
        return ready
    }

    fun clear() {
        frames.clear()
        lastPlayedSequence = 0L
    }
}
