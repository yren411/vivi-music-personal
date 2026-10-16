// SPDX-License-Identifier: AGPL-3.0-or-later
// Tinium JNI adapter, 2026-10-16. DSP source/notices: experiments/bitchord-native-analysis.
#include "tinium/analyzer.h"
#include <jni.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <limits>
#include <stdexcept>

namespace {
static_assert(sizeof(float) == 4 && std::numeric_limits<float>::is_iec559, "IEEE Float32 required");
using namespace tinium::native_analysis;
constexpr const char* version = "fe95bb60-controlled-v1-jni1-evidence1-internal11025";
struct Context {
  JNIEnv* env;
  jobject object; // JNI argument, valid on this calling thread until native method returns.
  jmethodID poll;
  AnalysisStage stage = AnalysisStage::input_validation;
};
bool canceled(void* pointer) {
  auto& c = *static_cast<Context*>(pointer);
  const jint status = c.env->CallIntMethod(c.object, c.poll, static_cast<jint>(c.stage));
  if (c.env->ExceptionCheck()) return true; // Unwind RAII, then preserve the pending Java exception.
  if (status == 2) throw Stop{StopReason::deadline};
  if (status == 3) throw std::runtime_error("CONTROL_FAILED");
  return status != 0;
}
void stage_changed(void* pointer, AnalysisStage stage) { static_cast<Context*>(pointer)->stage = stage; }

jdoubleArray array(JNIEnv* env, const double* values, std::size_t size) {
  auto out = env->NewDoubleArray(static_cast<jsize>(size));
  if (out && size) env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(size), values);
  return out;
}
jobject packet(JNIEnv* env, const Result& r) {
  if (env->ExceptionCheck()) return nullptr;
  // Protocol 1: exact field order also documented/validated in NativeDspPacket.kt.
  const std::array<double, 17> values{
    static_cast<double>(r.input_frames), static_cast<double>(r.input_sample_rate),
    static_cast<double>(r.analysis_frames), static_cast<double>(r.analysis_sample_rate),
    r.duration_seconds, r.analysis_duration_seconds, r.evidence.bpm, r.evidence.interval_seconds,
    r.evidence.first_beat_seconds, r.evidence.native_confidence, r.rms, r.peak,
    r.elapsed_us, r.resample_us, r.dsp_us, static_cast<double>(r.boundary_events_omitted), r.validation_us
  };
  auto type = env->FindClass("com/music/vivi/playback/automix/NativeDspPacket");
  if (!type) return nullptr;
  auto ctor = env->GetMethodID(type, "<init>", "(ILjava/lang/String;[D[D[D)V");
  if (!ctor) return nullptr;
  auto status = env->NewStringUTF(status_name(r.status));
  if (!status) return nullptr;
  auto scalars = array(env, values.data(), values.size());
  if (!scalars) return nullptr;
  auto beats = array(env, r.evidence.beats_seconds.data(), r.evidence.beats_seconds.size());
  if (!beats) return nullptr;
  auto downbeats = array(env, r.evidence.heuristic_downbeats_seconds.data(), r.evidence.heuristic_downbeats_seconds.size());
  if (!downbeats || env->ExceptionCheck()) return nullptr;
  // All locals are bounded (<10) and released by the JNI invocation frame.
  return env->NewObject(type, ctor, 1, status, scalars, beats, downbeats);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_music_vivi_playback_automix_BitChordNativeBindings_implementationVersion(JNIEnv* env, jobject) {
  return env->NewStringUTF(version);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_music_vivi_playback_automix_BitChordNativeBindings_analyze(
    JNIEnv* env, jobject, jfloatArray samples, jint sample_rate, jint channels, jlong origin_us,
    jdouble audible_start, jlong budget_nanos, jobject poller) {
  using namespace tinium::native_analysis;
  Result result;
  try {
    if (!samples || !poller || sample_rate < 8000 || sample_rate > 96000 || channels != 1 ||
        origin_us < 0 || budget_nanos <= 0) return packet(env, result);
    const auto count = env->GetArrayLength(samples);
    if (count <= 0 || static_cast<std::size_t>(count) > max_input_frames ||
        static_cast<double>(count) / sample_rate > max_duration_seconds) return packet(env, result);
    auto type = env->GetObjectClass(poller);
    if (!type) return nullptr;
    auto method = env->GetMethodID(type, "poll", "(I)I");
    if (!method) return nullptr;
    Context context{env, poller, method};
    Control control;
    control.context = &context;
    control.is_canceled = canceled;
    control.stage_changed = stage_changed;
    const auto budget = std::min<jlong>(budget_nanos, 10'000'000'000LL);
    control.deadline = std::chrono::steady_clock::now() + std::chrono::nanoseconds(budget);
    control.checkpoint();
    // One bounded native copy, never GetPrimitiveArrayCritical or a retained Java pointer.
    std::vector<float> pcm(static_cast<std::size_t>(count));
    for (jsize offset = 0; offset < count; offset += 4096) {
      control.checkpoint();
      env->GetFloatArrayRegion(samples, offset, std::min<jsize>(4096, count - offset), pcm.data() + offset);
      if (env->ExceptionCheck()) return nullptr;
    }
    Request request;
    request.pcm = &pcm;
    request.sample_rate = sample_rate;
    request.channels = channels;
    request.window_origin_us = origin_us;
    request.audible_start_seconds = audible_start;
    request.path = Path::resample_11025;
    result = analyze(request, control);
  } catch (const Stop& stop) {
    result.status = stop.reason == StopReason::canceled ? Status::canceled : Status::deadline;
  } catch (const std::bad_alloc&) {
    result.status = Status::allocation_failure;
  } catch (...) {
    result.status = Status::internal_error;
  }
  // Allocation errors while creating the Java packet must not cross JNI as C++ exceptions.
  try { return packet(env, result); }
  catch (...) { return nullptr; } // Kotlin maps a null packet to a controlled technical failure.
}
