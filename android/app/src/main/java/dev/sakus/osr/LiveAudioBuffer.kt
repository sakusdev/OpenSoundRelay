// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import java.util.PriorityQueue
import java.util.concurrent.TimeUnit

/**
 * Small, freshness-first audio queue for live playback.
 *
 * The queue intentionally drops old frames when the receiver falls behind. For a live relay,
 * catching up to "now" is more important than replaying every stale packet.
 */
data class LiveAudioBufferStats(
    val bufferedFrames: Int,
    val targetFrames: Int,
    val estimatedLostFrames: Long,
    val lateFrames: Long,
    val droppedStaleFrames: Long,
    val underruns: Long,
    val corrections: Long,
)

class LiveAudioBuffer(
    targetFrames: Int,
    private val adaptive: Boolean,
    private val minFrames: Int,
    private val maxFrames: Int,
    private val stableFramesBeforeShrink: Int = 100,
) {
    private val lock = Object()
    private val frames = PriorityQueue<OsrProtocol.AudioFrame>(compareBy { it.frameSequence })

    private var closed = false
    private var primed = false
    private var currentTargetFrames = targetFrames.coerceIn(minFrames, maxFrames)
    private var lastPlayedSequence = 0L
    private var stableFrames = 0
    private var estimatedLostFrames = 0L
    private var lateFrames = 0L
    private var droppedStaleFrames = 0L
    private var underruns = 0L
    private var corrections = 0L
    private var lastGrowthAtNs = 0L

    fun push(frame: OsrProtocol.AudioFrame): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (frame.frameSequence <= lastPlayedSequence ||
            frames.any { it.frameSequence == frame.frameSequence }
        ) {
            lateFrames++
            return@synchronized false
        }

        frames.add(frame)

        if (frames.size > maxFrames) {
            val keep = currentTargetFrames.coerceAtLeast(1)
            while (frames.size > keep) {
                val dropped = frames.poll() ?: break
                lastPlayedSequence = maxOf(lastPlayedSequence, dropped.frameSequence)
                droppedStaleFrames++
            }
            primed = frames.size >= currentTargetFrames
            corrections++
        }

        if (frames.size >= currentTargetFrames) {
            lock.notifyAll()
        }
        true
    }

    fun take(timeoutMs: Long): OsrProtocol.AudioFrame? = synchronized(lock) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1))

        while (!closed) {
            if (!primed) {
                if (frames.size >= currentTargetFrames) {
                    primed = true
                } else {
                    val remainingNs = deadline - System.nanoTime()
                    if (remainingNs <= 0) return@synchronized null
                    val waitMs = TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1)
                    lock.wait(waitMs)
                    continue
                }
            }

            val next = frames.poll()
            if (next == null) {
                primed = false
                return@synchronized null
            }
            if (next.frameSequence <= lastPlayedSequence) continue

            if (lastPlayedSequence != 0L && next.frameSequence > lastPlayedSequence + 1L) {
                estimatedLostFrames += next.frameSequence - lastPlayedSequence - 1L
            }
            lastPlayedSequence = next.frameSequence
            stableFrames++

            if (adaptive &&
                stableFrames >= stableFramesBeforeShrink &&
                currentTargetFrames > minFrames
            ) {
                currentTargetFrames--
                stableFrames = 0
                corrections++
            }
            return@synchronized next
        }

        null
    }

    fun reportUnderrun() = synchronized(lock) {
        underruns++
        primed = false
        stableFrames = 0

        if (lastPlayedSequence == 0L || !adaptive || currentTargetFrames >= maxFrames) {
            return@synchronized
        }

        val now = System.nanoTime()
        if (now - lastGrowthAtNs >= TimeUnit.MILLISECONDS.toNanos(250)) {
            currentTargetFrames++
            lastGrowthAtNs = now
            corrections++
        }
    }

    fun stats(): LiveAudioBufferStats = synchronized(lock) {
        LiveAudioBufferStats(
            bufferedFrames = frames.size,
            targetFrames = currentTargetFrames,
            estimatedLostFrames = estimatedLostFrames,
            lateFrames = lateFrames,
            droppedStaleFrames = droppedStaleFrames,
            underruns = underruns,
            corrections = corrections,
        )
    }

    fun close() = synchronized(lock) {
        closed = true
        frames.clear()
        lock.notifyAll()
    }
}
