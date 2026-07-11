// SPDX-License-Identifier: MPL-2.0

package dev.sakus.osr

enum class AudioQualityProfile(
    val id: String,
    val displayName: String,
    val description: String,
    val sampleRateHz: Int,
    val requestedChannels: Int,
    val frameDurationUs: Int,
) {
    Voice(
        id = "voice",
        displayName = "Voice",
        description = "24 kHz · mono · 20 ms",
        sampleRateHz = 24_000,
        requestedChannels = 1,
        frameDurationUs = 20_000,
    ),
    Balanced(
        id = "balanced",
        displayName = "Balanced",
        description = "48 kHz · mono · 10 ms",
        sampleRateHz = 48_000,
        requestedChannels = 1,
        frameDurationUs = 10_000,
    ),
    Music(
        id = "music",
        displayName = "Music",
        description = "48 kHz · stereo · 5 ms",
        sampleRateHz = 48_000,
        requestedChannels = 2,
        frameDurationUs = 5_000,
    ),
    ;

    companion object {
        fun fromId(value: String?): AudioQualityProfile {
            return entries.firstOrNull { it.id == value } ?: Balanced
        }
    }
}

enum class LatencyMode(
    val id: String,
    val displayName: String,
    val baseLatencyMs: Int,
    val minFrames: Int,
    val maxFrames: Int,
) {
    Low(
        id = "low",
        displayName = "Low",
        baseLatencyMs = 15,
        minFrames = 1,
        maxFrames = 8,
    ),
    Auto(
        id = "auto",
        displayName = "Auto",
        baseLatencyMs = 30,
        minFrames = 2,
        maxFrames = 16,
    ),
    Stable(
        id = "stable",
        displayName = "Stable",
        baseLatencyMs = 60,
        minFrames = 4,
        maxFrames = 24,
    ),
    ;

    companion object {
        fun fromId(value: String?): LatencyMode {
            return entries.firstOrNull { it.id == value } ?: Auto
        }
    }
}

data class ReceiverStats(
    val source: String = "—",
    val format: String = "Waiting for audio",
    val jitterMs: Double = 0.0,
    val bufferMs: Int = 0,
    val bufferedFrames: Int = 0,
    val lostFrames: Long = 0,
    val lateFrames: Long = 0,
)
