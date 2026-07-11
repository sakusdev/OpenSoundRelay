// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil

class PcmJitterBuffer(
    private val mode: LatencyMode = LatencyMode.Auto,
) {
    data class ReadyFrame(
        val frame: OsrProtocol.AudioFrame,
        val missingFramesBefore: Int,
    )

    data class Stats(
        val jitterMs: Double,
        val targetFrames: Int,
        val targetLatencyMs: Int,
        val bufferedFrames: Int,
        val lostFrames: Long,
        val lateFrames: Long,
    )

    private val frames = PriorityQueue<OsrProtocol.AudioFrame>(compareBy { it.frameSequence })
    private val queuedSequences = HashSet<Long>()
    private var lastPlayedSequence = 0L
    private var lastObservedSequence = 0L
    private var lastArrivalUs = 0L
    private var lastMediaTimeUs = 0L
    private var jitterUs = 0.0
    private var targetFrames = mode.minFrames
    private var frameDurationUs = 10_000
    private var shrinkCounter = 0
    private var lostFrames = 0L
    private var lateFrames = 0L

    fun push(
        frame: OsrProtocol.AudioFrame,
        arrivalTimeUs: Long = System.nanoTime() / 1_000L,
    ): List<ReadyFrame> {
        if (frame.frameSequence <= lastPlayedSequence || !queuedSequences.add(frame.frameSequence)) {
            lateFrames++
            return emptyList()
        }

        updateJitter(frame, arrivalTimeUs)
        frames.add(frame)

        val ready = mutableListOf<ReadyFrame>()
        while (frames.size > targetFrames || frames.size > mode.maxFrames * 2) {
            val next = frames.poll() ?: break
            queuedSequences.remove(next.frameSequence)
            if (next.frameSequence <= lastPlayedSequence) {
                lateFrames++
                continue
            }

            val gap = if (lastPlayedSequence == 0L) {
                0L
            } else {
                (next.frameSequence - lastPlayedSequence - 1).coerceAtLeast(0)
            }
            lostFrames += gap
            lastPlayedSequence = next.frameSequence
            ready.add(
                ReadyFrame(
                    frame = next,
                    missingFramesBefore = gap.coerceAtMost(MAX_CONCEALED_FRAMES.toLong()).toInt(),
                ),
            )
        }
        return ready
    }

    fun stats(): Stats {
        return Stats(
            jitterMs = jitterUs / 1_000.0,
            targetFrames = targetFrames,
            targetLatencyMs = (targetFrames * frameDurationUs) / 1_000,
            bufferedFrames = frames.size,
            lostFrames = lostFrames,
            lateFrames = lateFrames,
        )
    }

    fun clear() {
        frames.clear()
        queuedSequences.clear()
        lastPlayedSequence = 0L
        lastObservedSequence = 0L
        lastArrivalUs = 0L
        lastMediaTimeUs = 0L
        jitterUs = 0.0
        targetFrames = mode.minFrames
        frameDurationUs = 10_000
        shrinkCounter = 0
        lostFrames = 0L
        lateFrames = 0L
    }

    private fun updateJitter(frame: OsrProtocol.AudioFrame, arrivalTimeUs: Long) {
        frameDurationUs = frame.frameDurationUs.coerceIn(2_500, 40_000)
        if (frame.frameSequence > lastObservedSequence && lastObservedSequence != 0L) {
            val arrivalDelta = arrivalTimeUs - lastArrivalUs
            val mediaDelta = frame.mediaTimeUs - lastMediaTimeUs
            val variation = abs(arrivalDelta - mediaDelta).toDouble()
            jitterUs += (variation - jitterUs) / 16.0
        }
        if (frame.frameSequence > lastObservedSequence) {
            lastObservedSequence = frame.frameSequence
            lastArrivalUs = arrivalTimeUs
            lastMediaTimeUs = frame.mediaTimeUs
        }

        val desiredLatencyUs = mode.baseLatencyMs * 1_000.0 + jitterUs * 3.0
        val desiredFrames = ceil(desiredLatencyUs / frameDurationUs)
            .toInt()
            .coerceIn(mode.minFrames, mode.maxFrames)
        if (desiredFrames > targetFrames) {
            targetFrames = desiredFrames
            shrinkCounter = 0
        } else if (desiredFrames < targetFrames) {
            shrinkCounter++
            if (shrinkCounter >= SHRINK_AFTER_FRAMES) {
                targetFrames--
                shrinkCounter = 0
            }
        } else {
            shrinkCounter = 0
        }
    }

    companion object {
        private const val MAX_CONCEALED_FRAMES = 8
        private const val SHRINK_AFTER_FRAMES = 200
    }
}
