// SPDX-License-Identifier: MPL-2.0

#include <aaudio/AAudio.h>
#include <jni.h>
#include <opus/opus.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

namespace {

constexpr int32_t kMinBitrateBps = 8'000;
constexpr int32_t kMaxBitrateBps = 512'000;

struct LivePlayer {
    AAudioStream *stream = nullptr;
    std::vector<int16_t> ring;
    std::atomic<uint64_t> read_index{0};
    std::atomic<uint64_t> write_index{0};
    std::atomic<uint64_t> underruns{0};
    std::atomic<uint64_t> dropped_input_frames{0};
    std::atomic<bool> received_audio{false};
    int32_t frames_per_burst = 0;
    int32_t sample_rate = 0;

    ~LivePlayer() {
        if (stream != nullptr) {
            AAudioStream_requestStop(stream);
            AAudioStream_close(stream);
            stream = nullptr;
        }
    }
};

struct OpusEncoderHandle {
    OpusEncoder *encoder = nullptr;
    ~OpusEncoderHandle() {
        if (encoder != nullptr) opus_encoder_destroy(encoder);
    }
};

struct OpusDecoderHandle {
    OpusDecoder *decoder = nullptr;
    ~OpusDecoderHandle() {
        if (decoder != nullptr) opus_decoder_destroy(decoder);
    }
};

template <typename T>
T *from_handle(jlong handle) {
    return reinterpret_cast<T *>(static_cast<intptr_t>(handle));
}

template <typename T>
jlong to_handle(T *pointer) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(pointer));
}

aaudio_data_callback_result_t player_callback(
    AAudioStream *,
    void *user_data,
    void *audio_data,
    int32_t num_frames
) {
    auto *player = static_cast<LivePlayer *>(user_data);
    auto *output = static_cast<int16_t *>(audio_data);
    const uint64_t read = player->read_index.load(std::memory_order_relaxed);
    const uint64_t write = player->write_index.load(std::memory_order_acquire);
    const uint64_t available = write - read;
    const uint64_t requested = static_cast<uint64_t>(std::max(num_frames, 0));
    const uint64_t copied = std::min(available, requested);
    const uint64_t capacity = player->ring.size();

    for (uint64_t index = 0; index < copied; ++index) {
        output[index] = player->ring[(read + index) % capacity];
    }
    std::fill(output + copied, output + requested, 0);
    player->read_index.store(read + copied, std::memory_order_release);

    if (copied < requested && player->received_audio.load(std::memory_order_acquire)) {
        player->underruns.fetch_add(1, std::memory_order_relaxed);
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

bool open_player_stream(LivePlayer *player, int32_t sample_rate, int32_t channel_count, aaudio_sharing_mode_t sharing) {
    AAudioStreamBuilder *builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) return false;

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, sharing);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(builder, sample_rate);
    AAudioStreamBuilder_setChannelCount(builder, channel_count);
    AAudioStreamBuilder_setDataCallback(builder, player_callback, player);

    AAudioStream *stream = nullptr;
    const aaudio_result_t open_result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (open_result != AAUDIO_OK || stream == nullptr) return false;

    if (AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_I16 ||
        AAudioStream_getChannelCount(stream) != channel_count) {
        AAudioStream_close(stream);
        return false;
    }

    player->stream = stream;
    player->sample_rate = AAudioStream_getSampleRate(stream);
    player->frames_per_burst = std::max(AAudioStream_getFramesPerBurst(stream), 1);
    const int32_t native_buffer_frames = player->frames_per_burst * 2;
    AAudioStream_setBufferSizeInFrames(stream, native_buffer_frames);

    const int32_t ring_frames = std::max(player->frames_per_burst * 3, player->sample_rate / 100);
    player->ring.assign(static_cast<size_t>(ring_frames), 0);

    if (AAudioStream_requestStart(stream) != AAUDIO_OK) {
        AAudioStream_close(stream);
        player->stream = nullptr;
        return false;
    }
    return true;
}

jbyteArray bytes_from_pcm(JNIEnv *env, const int16_t *samples, int32_t sample_count) {
    if (sample_count <= 0) return nullptr;
    const jsize byte_count = static_cast<jsize>(sample_count * static_cast<int32_t>(sizeof(int16_t)));
    jbyteArray output = env->NewByteArray(byte_count);
    if (output == nullptr) return nullptr;
    env->SetByteArrayRegion(output, 0, byte_count, reinterpret_cast<const jbyte *>(samples));
    return output;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_sakus_osr_NativeAudioBridge_createPlayer(
    JNIEnv *, jobject, jint sample_rate, jint channel_count
) {
    if (sample_rate <= 0 || channel_count != 1) return 0;
    auto player = std::make_unique<LivePlayer>();
    if (!open_player_stream(player.get(), sample_rate, channel_count, AAUDIO_SHARING_MODE_EXCLUSIVE) &&
        !open_player_stream(player.get(), sample_rate, channel_count, AAUDIO_SHARING_MODE_SHARED)) {
        return 0;
    }
    return to_handle(player.release());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_sakus_osr_NativeAudioBridge_writePlayer(
    JNIEnv *env, jobject, jlong handle, jbyteArray data, jint offset, jint length
) {
    auto *player = from_handle<LivePlayer>(handle);
    if (player == nullptr || data == nullptr || offset < 0 || length <= 0 || (length % 2) != 0) return 0;
    const jsize array_length = env->GetArrayLength(data);
    if (offset + length > array_length || player->ring.empty()) return 0;

    const uint64_t sample_count = static_cast<uint64_t>(length / 2);
    const uint64_t read = player->read_index.load(std::memory_order_acquire);
    const uint64_t write = player->write_index.load(std::memory_order_relaxed);
    const uint64_t used = write - read;
    const uint64_t capacity = player->ring.size();
    if (sample_count > capacity - std::min(used, capacity)) {
        player->dropped_input_frames.fetch_add(sample_count, std::memory_order_relaxed);
        return 0;
    }

    std::vector<jbyte> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, offset, length, bytes.data());
    if (env->ExceptionCheck()) return 0;

    for (uint64_t index = 0; index < sample_count; ++index) {
        const uint8_t low = static_cast<uint8_t>(bytes[index * 2]);
        const uint8_t high = static_cast<uint8_t>(bytes[index * 2 + 1]);
        player->ring[(write + index) % capacity] = static_cast<int16_t>(
            static_cast<uint16_t>(low) | (static_cast<uint16_t>(high) << 8)
        );
    }
    player->write_index.store(write + sample_count, std::memory_order_release);
    player->received_audio.store(true, std::memory_order_release);
    return static_cast<jint>(sample_count);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_sakus_osr_NativeAudioBridge_playerStats(JNIEnv *env, jobject, jlong handle) {
    auto *player = from_handle<LivePlayer>(handle);
    jlong values[6] = {};
    if (player != nullptr) {
        const uint64_t read = player->read_index.load(std::memory_order_acquire);
        const uint64_t write = player->write_index.load(std::memory_order_acquire);
        values[0] = static_cast<jlong>(player->underruns.load(std::memory_order_relaxed));
        values[1] = static_cast<jlong>(player->dropped_input_frames.load(std::memory_order_relaxed));
        values[2] = static_cast<jlong>(write - read);
        values[3] = static_cast<jlong>(player->ring.size());
        values[4] = static_cast<jlong>(player->frames_per_burst);
        values[5] = static_cast<jlong>(player->sample_rate);
    }
    jlongArray result = env->NewLongArray(6);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_sakus_osr_NativeAudioBridge_closePlayer(JNIEnv *, jobject, jlong handle) {
    delete from_handle<LivePlayer>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_sakus_osr_NativeAudioBridge_createOpusEncoder(
    JNIEnv *, jobject, jint sample_rate, jint channel_count, jint bitrate_bps
) {
    int error = OPUS_OK;
    auto encoder = std::make_unique<OpusEncoderHandle>();
    encoder->encoder = opus_encoder_create(
        sample_rate,
        channel_count,
        OPUS_APPLICATION_RESTRICTED_LOWDELAY,
        &error
    );
    if (error != OPUS_OK || encoder->encoder == nullptr) return 0;

    const int bitrate = std::clamp(static_cast<int>(bitrate_bps), kMinBitrateBps, kMaxBitrateBps);
    if (opus_encoder_ctl(encoder->encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK ||
        opus_encoder_ctl(encoder->encoder, OPUS_SET_VBR(1)) != OPUS_OK ||
        opus_encoder_ctl(encoder->encoder, OPUS_SET_COMPLEXITY(5)) != OPUS_OK ||
        opus_encoder_ctl(encoder->encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_MUSIC)) != OPUS_OK ||
        opus_encoder_ctl(encoder->encoder, OPUS_SET_INBAND_FEC(0)) != OPUS_OK ||
        opus_encoder_ctl(encoder->encoder, OPUS_SET_PACKET_LOSS_PERC(0)) != OPUS_OK) {
        return 0;
    }
    return to_handle(encoder.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_sakus_osr_NativeAudioBridge_setOpusBitrate(
    JNIEnv *, jobject, jlong handle, jint bitrate_bps
) {
    auto *encoder = from_handle<OpusEncoderHandle>(handle);
    if (encoder == nullptr || encoder->encoder == nullptr) return JNI_FALSE;
    const int bitrate = std::clamp(static_cast<int>(bitrate_bps), kMinBitrateBps, kMaxBitrateBps);
    return opus_encoder_ctl(encoder->encoder, OPUS_SET_BITRATE(bitrate)) == OPUS_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_sakus_osr_NativeAudioBridge_encodeOpus(
    JNIEnv *env,
    jobject,
    jlong handle,
    jbyteArray pcm_data,
    jint offset,
    jint length,
    jint frame_samples
) {
    auto *encoder = from_handle<OpusEncoderHandle>(handle);
    if (encoder == nullptr || encoder->encoder == nullptr || pcm_data == nullptr ||
        frame_samples <= 0 || length != frame_samples * 2 || offset < 0) {
        return nullptr;
    }
    if (offset + length > env->GetArrayLength(pcm_data)) return nullptr;

    std::vector<int16_t> pcm(static_cast<size_t>(frame_samples));
    env->GetByteArrayRegion(
        pcm_data,
        offset,
        length,
        reinterpret_cast<jbyte *>(pcm.data())
    );
    if (env->ExceptionCheck()) return nullptr;

    std::vector<unsigned char> packet(1275);
    const int encoded = opus_encode(
        encoder->encoder,
        pcm.data(),
        frame_samples,
        packet.data(),
        static_cast<opus_int32>(packet.size())
    );
    if (encoded <= 0) return nullptr;

    jbyteArray output = env->NewByteArray(encoded);
    if (output != nullptr) {
        env->SetByteArrayRegion(output, 0, encoded, reinterpret_cast<const jbyte *>(packet.data()));
    }
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_sakus_osr_NativeAudioBridge_closeOpusEncoder(JNIEnv *, jobject, jlong handle) {
    delete from_handle<OpusEncoderHandle>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_sakus_osr_NativeAudioBridge_createOpusDecoder(
    JNIEnv *, jobject, jint sample_rate, jint channel_count
) {
    int error = OPUS_OK;
    auto decoder = std::make_unique<OpusDecoderHandle>();
    decoder->decoder = opus_decoder_create(sample_rate, channel_count, &error);
    if (error != OPUS_OK || decoder->decoder == nullptr) return 0;
    return to_handle(decoder.release());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_sakus_osr_NativeAudioBridge_decodeOpus(
    JNIEnv *env,
    jobject,
    jlong handle,
    jbyteArray packet_data,
    jint offset,
    jint length,
    jint max_frame_samples
) {
    auto *decoder = from_handle<OpusDecoderHandle>(handle);
    if (decoder == nullptr || decoder->decoder == nullptr || packet_data == nullptr ||
        offset < 0 || length <= 0 || max_frame_samples <= 0) {
        return nullptr;
    }
    if (offset + length > env->GetArrayLength(packet_data)) return nullptr;

    std::vector<unsigned char> packet(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        packet_data,
        offset,
        length,
        reinterpret_cast<jbyte *>(packet.data())
    );
    if (env->ExceptionCheck()) return nullptr;

    std::vector<int16_t> pcm(static_cast<size_t>(max_frame_samples));
    const int decoded = opus_decode(
        decoder->decoder,
        packet.data(),
        length,
        pcm.data(),
        max_frame_samples,
        0
    );
    if (decoded <= 0) return nullptr;
    return bytes_from_pcm(env, pcm.data(), decoded);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_sakus_osr_NativeAudioBridge_closeOpusDecoder(JNIEnv *, jobject, jlong handle) {
    delete from_handle<OpusDecoderHandle>(handle);
}
