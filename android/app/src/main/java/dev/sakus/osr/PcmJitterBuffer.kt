// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import java.util.PriorityQueue

data class JitterBufferStats(
    val bufferedFrames: Int,
    val targetFrames: Int,
    val estimatedLostFrames: Long,
    val lateFrames: Long,
    val corrections: Long,
)

class PcmJitterBuffer(
    targetFrames: Int = 4,
    private val adaptive: Boolean = true,
    private val minFrames: Int = 2,
    private val maxFrames: Int = 14,
) {
    private val frames = PriorityQueue<OsrProtocol.AudioFrame>(compareBy { it.frameSequence })
    private var currentTargetFrames = targetFrames.coerceIn(minFrames, maxFrames)
    private var lastPlayedSequence = 0L
    private var lastReceivedSequence = 0L
    private var stableFrames = 0
    private var estimatedLostFrames = 0L
    private var lateFrames = 0L
    private var corrections = 0L

    fun push(frame: OsrProtocol.AudioFrame): List<OsrProtocol.AudioFrame> {
        if (frame.frameSequence <= lastPlayedSequence ||
            frames.any { it.frameSequence == frame.frameSequence }
        ) {
            lateFrames++
            return emptyList()
        }

        if (lastReceivedSequence != 0L && frame.frameSequence > lastReceivedSequence + 1) {
            estimatedLostFrames += frame.frameSequence - lastReceivedSequence - 1
            stableFrames = 0
            if (adaptive && currentTargetFrames < maxFrames) {
                currentTargetFrames++
                corrections++
            }
        } else {
            stableFrames++
            if (adaptive && stableFrames >= 500 && currentTargetFrames > minFrames) {
                currentTargetFrames--
                stableFrames = 0
                corrections++
            }
        }
        lastReceivedSequence = maxOf(lastReceivedSequence, frame.frameSequence)

        frames.add(frame)
        while (frames.size > maxFrames + 6) {
            frames.poll()
            estimatedLostFrames++
            if (adaptive && currentTargetFrames < maxFrames) {
                currentTargetFrames++
            }
            corrections++
        }

        val ready = mutableListOf<OsrProtocol.AudioFrame>()
        while (frames.size > currentTargetFrames) {
            val next = frames.poll() ?: break
            if (next.frameSequence <= lastPlayedSequence) continue
            lastPlayedSequence = next.frameSequence
            ready.add(next)
        }
        return ready
    }

    fun stats(): JitterBufferStats = JitterBufferStats(
        bufferedFrames = frames.size,
        targetFrames = currentTargetFrames,
        estimatedLostFrames = estimatedLostFrames,
        lateFrames = lateFrames,
        corrections = corrections,
    )

    fun clear() {
        frames.clear()
        lastPlayedSequence = 0L
        lastReceivedSequence = 0L
        stableFrames = 0
    }
}
