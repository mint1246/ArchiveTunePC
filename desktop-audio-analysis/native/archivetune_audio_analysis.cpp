#include <algorithm>
#include <climits>
#include <cmath>
#include <cstddef>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>
#if defined(_WIN32)
#include <malloc.h>
#endif

#include "essentia/algorithmfactory.h"
#include "essentia/essentia.h"

using essentia::Real;
using essentia::StereoSample;
using essentia::standard::Algorithm;
using essentia::standard::AlgorithmFactory;

#if defined(_WIN32)
#define AT_EXPORT extern "C" __declspec(dllexport)
#else
#define AT_EXPORT extern "C"
#endif

namespace {

constexpr int kOk = 0;
constexpr int kInvalidArgument = -1;
constexpr int kAnalysisFailed = -2;
constexpr int kOutOfMemory = -3;
constexpr int kNotAnalyzed = -4;
constexpr int kBufferTooSmall = -5;

std::once_flag gEssentiaInit;

void ensureEssentiaInitialized() {
    std::call_once(gEssentiaInit, [] { essentia::init(); });
}

struct MonoAnalyzer {
    std::vector<Real> samples;
    std::vector<Real> beats;
    double bpm = 0.0;
    std::string key;
    std::string mode;
    float keyStrength = 0.0f;
    std::string error;
    bool analyzed = false;
};

struct LoudnessAnalyzer {
    std::vector<StereoSample> samples;
    float integratedLufs = 0.0f;
    float loudnessRange = 0.0f;
    std::string error;
    bool analyzed = false;
};

template <typename Handle, typename Fn>
int guarded(Handle* handle, Fn&& fn) {
    if (!handle) return kInvalidArgument;
    try {
        handle->error.clear();
        fn();
        return kOk;
    } catch (const std::bad_alloc&) {
        handle->error = "out of memory";
        return kOutOfMemory;
    } catch (const std::exception& e) {
        handle->error = e.what();
        return kAnalysisFailed;
    } catch (...) {
        handle->error = "unknown native analysis error";
        return kAnalysisFailed;
    }
}

double robustBpmFromBeats(const std::vector<Real>& beats) {
    if (beats.size() < 2) return 0.0;

    std::vector<double> intervals;
    intervals.reserve(beats.size() - 1);
    for (std::size_t i = 1; i < beats.size(); ++i) {
        const double dt = static_cast<double>(beats[i] - beats[i - 1]);
        if (dt >= 0.24 && dt <= 1.5) intervals.push_back(dt);
    }
    if (intervals.empty()) return 0.0;

    // Beat trackers occasionally emit one unusually short/long interval at a
    // phrase boundary. A 10% trimmed mean keeps the aggregate tempo anchored
    // to the dominant grid without the quantisation bias of a pure median.
    std::sort(intervals.begin(), intervals.end());
    const std::size_t trim = intervals.size() >= 10 ? intervals.size() / 10 : 0;
    const auto first = intervals.begin() + static_cast<std::ptrdiff_t>(trim);
    const auto last = intervals.end() - static_cast<std::ptrdiff_t>(trim);
    double total = 0.0;
    for (auto it = first; it != last; ++it) total += *it;
    const auto count = static_cast<std::size_t>(last - first);
    const double meanInterval = count > 0 ? total / static_cast<double>(count) : 0.0;
    return meanInterval > 0.0 ? 60.0 / meanInterval : 0.0;
}

int copyString(const std::string& value, char* output, int capacity) {
    if (!output || capacity <= 0) return kInvalidArgument;
    if (static_cast<std::size_t>(capacity) <= value.size()) return kBufferTooSmall;
    std::memcpy(output, value.c_str(), value.size() + 1);
    return static_cast<int>(value.size());
}

} // namespace

AT_EXPORT int at_audio_analysis_abi_version() {
    return 1;
}

AT_EXPORT void* at_mono_create() {
    try {
        ensureEssentiaInitialized();
        return new MonoAnalyzer();
    } catch (...) {
        return nullptr;
    }
}

AT_EXPORT void at_mono_destroy(void* opaque) {
    delete static_cast<MonoAnalyzer*>(opaque);
}

AT_EXPORT int at_mono_reserve(void* opaque, int sampleCount) {
    auto* handle = static_cast<MonoAnalyzer*>(opaque);
    if (!handle || sampleCount < 0) return kInvalidArgument;
    return guarded(handle, [&] { handle->samples.reserve(static_cast<std::size_t>(sampleCount)); });
}

AT_EXPORT int at_mono_append_f32(void* opaque, const float* samples, int sampleCount) {
    auto* handle = static_cast<MonoAnalyzer*>(opaque);
    if (!handle || !samples || sampleCount < 0) return kInvalidArgument;
    return guarded(handle, [&] {
        handle->samples.insert(handle->samples.end(), samples, samples + sampleCount);
        handle->analyzed = false;
    });
}

AT_EXPORT int at_mono_analyze(void* opaque, int minTempo, int maxTempo, const char* keyProfile) {
    auto* handle = static_cast<MonoAnalyzer*>(opaque);
    if (!handle || minTempo < 40 || maxTempo > 250 || minTempo >= maxTempo) return kInvalidArgument;
    if (handle->samples.empty()) return kInvalidArgument;

    const int status = guarded(handle, [&] {
        const char* profile = (keyProfile && keyProfile[0] != '\0') ? keyProfile : "bgate";

        Algorithm* beatTracker = AlgorithmFactory::create(
            "BeatTrackerDegara",
            "minTempo", minTempo,
            "maxTempo", maxTempo
        );
        std::vector<Real> beats;
        beatTracker->input("signal").set(handle->samples);
        beatTracker->output("ticks").set(beats);
        try {
            beatTracker->compute();
        } catch (...) {
            delete beatTracker;
            throw;
        }
        delete beatTracker;

        Algorithm* keyExtractor = AlgorithmFactory::create("KeyExtractor", "profileType", profile);
        std::string key;
        std::string mode;
        Real strength = 0.0f;
        keyExtractor->input("audio").set(handle->samples);
        keyExtractor->output("key").set(key);
        keyExtractor->output("scale").set(mode);
        keyExtractor->output("strength").set(strength);
        try {
            keyExtractor->compute();
        } catch (...) {
            delete keyExtractor;
            throw;
        }
        delete keyExtractor;

        handle->beats = std::move(beats);
        handle->bpm = robustBpmFromBeats(handle->beats);
        handle->key = std::move(key);
        handle->mode = std::move(mode);
        handle->keyStrength = strength;
        handle->analyzed = true;
    });
    // BeatTrackerDegara and KeyExtractor need the complete signal during compute(), but the
    // decoded PCM is dead as soon as both have finished. Drop the vector capacity immediately
    // instead of keeping one float per source sample alive until the session is destroyed.
    std::vector<Real>().swap(handle->samples);
#if defined(_WIN32)
    // MSVC's CRT can otherwise retain the just-freed multi-megabyte blocks in its process heap,
    // which makes a one-shot analysis look like a permanent working-set increase.
    _heapmin();
#endif
    return status;
}

AT_EXPORT double at_mono_bpm(void* opaque) {
    auto* handle = static_cast<MonoAnalyzer*>(opaque);
    return handle && handle->analyzed ? handle->bpm : 0.0;
}

AT_EXPORT float at_mono_key_strength(void* opaque) {
    auto* handle = static_cast<MonoAnalyzer*>(opaque);
    return handle && handle->analyzed ? handle->keyStrength : 0.0f;
}

AT_EXPORT int at_mono_get_key(void* opaque, char* output, int capacity) {
    auto* handle = static_cast<MonoAnalyzer*>(opaque);
    if (!handle || !handle->analyzed) return kNotAnalyzed;
    return copyString(handle->key, output, capacity);
}

AT_EXPORT int at_mono_get_mode(void* opaque, char* output, int capacity) {
    auto* handle = static_cast<MonoAnalyzer*>(opaque);
    if (!handle || !handle->analyzed) return kNotAnalyzed;
    return copyString(handle->mode, output, capacity);
}

AT_EXPORT int at_mono_beat_count(void* opaque) {
    auto* handle = static_cast<MonoAnalyzer*>(opaque);
    if (!handle || !handle->analyzed) return kNotAnalyzed;
    if (handle->beats.size() > static_cast<std::size_t>(INT_MAX)) return kAnalysisFailed;
    return static_cast<int>(handle->beats.size());
}

AT_EXPORT int at_mono_copy_beats(void* opaque, float* output, int capacity) {
    auto* handle = static_cast<MonoAnalyzer*>(opaque);
    if (!handle || !handle->analyzed || !output || capacity < 0) return kInvalidArgument;
    if (capacity < static_cast<int>(handle->beats.size())) return kBufferTooSmall;
    std::copy(handle->beats.begin(), handle->beats.end(), output);
    return static_cast<int>(handle->beats.size());
}

AT_EXPORT int at_mono_last_error(void* opaque, char* output, int capacity) {
    auto* handle = static_cast<MonoAnalyzer*>(opaque);
    if (!handle) return kInvalidArgument;
    return copyString(handle->error, output, capacity);
}

AT_EXPORT void* at_loudness_create() {
    try {
        ensureEssentiaInitialized();
        return new LoudnessAnalyzer();
    } catch (...) {
        return nullptr;
    }
}

AT_EXPORT void at_loudness_destroy(void* opaque) {
    delete static_cast<LoudnessAnalyzer*>(opaque);
}

AT_EXPORT int at_loudness_reserve(void* opaque, int frameCount) {
    auto* handle = static_cast<LoudnessAnalyzer*>(opaque);
    if (!handle || frameCount < 0) return kInvalidArgument;
    return guarded(handle, [&] { handle->samples.reserve(static_cast<std::size_t>(frameCount)); });
}

AT_EXPORT int at_loudness_append_interleaved_f32(void* opaque, const float* samples, int frameCount) {
    auto* handle = static_cast<LoudnessAnalyzer*>(opaque);
    if (!handle || !samples || frameCount < 0) return kInvalidArgument;
    return guarded(handle, [&] {
        const auto oldSize = handle->samples.size();
        handle->samples.resize(oldSize + static_cast<std::size_t>(frameCount));
        for (int i = 0; i < frameCount; ++i) {
            auto& frame = handle->samples[oldSize + static_cast<std::size_t>(i)];
            frame.first = samples[i * 2];
            frame.second = samples[i * 2 + 1];
        }
        handle->analyzed = false;
    });
}

AT_EXPORT int at_loudness_analyze(void* opaque) {
    auto* handle = static_cast<LoudnessAnalyzer*>(opaque);
    if (!handle || handle->samples.empty()) return kInvalidArgument;
    const int status = guarded(handle, [&] {
        Algorithm* analyzer = AlgorithmFactory::create("LoudnessEBUR128", "sampleRate", 44100.0);
        std::vector<Real> momentary;
        std::vector<Real> shortTerm;
        Real integrated = 0.0f;
        Real range = 0.0f;
        analyzer->input("signal").set(handle->samples);
        analyzer->output("momentaryLoudness").set(momentary);
        analyzer->output("shortTermLoudness").set(shortTerm);
        analyzer->output("integratedLoudness").set(integrated);
        analyzer->output("loudnessRange").set(range);
        try {
            analyzer->compute();
        } catch (...) {
            delete analyzer;
            throw;
        }
        delete analyzer;
        handle->integratedLufs = integrated;
        handle->loudnessRange = range;
        handle->analyzed = true;
    });
    std::vector<StereoSample>().swap(handle->samples);
#if defined(_WIN32)
    _heapmin();
#endif
    return status;
}

AT_EXPORT float at_loudness_integrated_lufs(void* opaque) {
    auto* handle = static_cast<LoudnessAnalyzer*>(opaque);
    return handle && handle->analyzed ? handle->integratedLufs : 0.0f;
}

AT_EXPORT float at_loudness_range_lu(void* opaque) {
    auto* handle = static_cast<LoudnessAnalyzer*>(opaque);
    return handle && handle->analyzed ? handle->loudnessRange : 0.0f;
}

AT_EXPORT int at_loudness_last_error(void* opaque, char* output, int capacity) {
    auto* handle = static_cast<LoudnessAnalyzer*>(opaque);
    if (!handle) return kInvalidArgument;
    return copyString(handle->error, output, capacity);
}
