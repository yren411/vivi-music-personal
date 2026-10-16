// SPDX-License-Identifier: AGPL-3.0-or-later
#include "tinium/analyzer.h"
#include "controlled_dsp.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <new>

namespace tinium::native_analysis {
namespace {
using Clock = std::chrono::steady_clock;
struct InvalidInput {};
double micros(Clock::time_point from, Clock::time_point to) {
  return std::chrono::duration<double, std::micro>(to - from).count();
}
bool ordered(const std::vector<double>& times, double end) {
  if (times.size() > max_beat_events) return false;
  double previous = -1;
  for (double time : times) {
    if (!std::isfinite(time) || time < 0 || time >= end || time <= previous) return false;
    previous = time;
  }
  return true;
}
bool downbeat_subset(const Evidence& evidence) {
  for (double time : evidence.heuristic_downbeats_seconds) {
    auto found = std::lower_bound(evidence.beats_seconds.begin(), evidence.beats_seconds.end(), time);
    if (found == evidence.beats_seconds.end() || std::abs(*found - time) > 1e-10) return false;
  }
  return true;
}
}

const char* status_name(Status status) noexcept {
  switch (status) {
    case Status::ok: return "OK_UNCALIBRATED_EVIDENCE";
    case Status::no_evidence: return "NO_EVIDENCE";
    case Status::invalid_input: return "INVALID_INPUT";
    case Status::invalid_output: return "INVALID_NATIVE_OUTPUT";
    case Status::canceled: return "CANCELED";
    case Status::deadline: return "DEADLINE";
    case Status::allocation_failure: return "ALLOCATION_FAILURE";
    case Status::internal_error: return "INTERNAL_ERROR";
  }
  return "INTERNAL_ERROR";
}

bool valid_evidence(const Evidence& e, double duration, int rate) noexcept {
  if (!std::isfinite(duration) || duration <= 0 || duration > max_duration_seconds || rate < 8000 || rate > 96000)
    return false;
  if (!std::isfinite(e.bpm) || !std::isfinite(e.native_confidence) || e.native_confidence < 0 || e.native_confidence > 1 ||
      !std::isfinite(e.interval_seconds) || !std::isfinite(e.first_beat_seconds) || e.first_beat_seconds < 0)
    return false;
  if (e.bpm == 0) return e.beats_seconds.empty() && e.heuristic_downbeats_seconds.empty() && e.interval_seconds == 0;
  return e.bpm >= 60 && e.bpm <= 220 && !e.beats_seconds.empty() && e.interval_seconds > 0 && e.first_beat_seconds < duration &&
      std::abs(e.first_beat_seconds - e.beats_seconds.front()) < 1e-9 &&
      std::abs(e.interval_seconds - 60 / e.bpm) < 1e-9 &&
      ordered(e.beats_seconds, duration) && ordered(e.heuristic_downbeats_seconds, duration) && downbeat_subset(e);
}

Result analyze(const Request& request, const Control& caller_control) noexcept {
  Result result;
  const auto began = Clock::now();
  Control control = caller_control;
  control.deadline = std::min(control.deadline, began + std::chrono::seconds(10));
  try {
    control.enter(AnalysisStage::input_validation);
    control.checkpoint();
    if (!request.pcm || request.pcm->empty() || request.channels != 1 || request.sample_rate < 8000 || request.sample_rate > 96000 ||
        request.pcm->size() > max_input_frames || request.window_origin_us < 0 ||
        (request.path != Path::direct && request.path != Path::resample_11025)) throw InvalidInput{};
    const double duration = static_cast<double>(request.pcm->size()) / request.sample_rate;
    const auto duration_us = static_cast<std::int64_t>(std::ceil(duration * 1e6));
    if (duration > max_duration_seconds || !std::isfinite(request.audible_start_seconds) ||
        request.audible_start_seconds < 0 || request.audible_start_seconds >= duration ||
        request.window_origin_us > std::numeric_limits<std::int64_t>::max() - duration_us) throw InvalidInput{};
    result.window_origin_us = request.window_origin_us;
    result.duration_seconds = duration;
    result.input_frames = request.pcm->size();
    result.input_sample_rate = request.sample_rate;
    result.analysis_sample_rate = request.path == Path::direct ? request.sample_rate : 11025;
    double squares = 0;
    for (std::size_t i = 0; i < request.pcm->size(); ++i) {
      if (i % 1024 == 0) control.checkpoint();
      const double v = (*request.pcm)[i];
      if (!std::isfinite(v)) throw InvalidInput{};
      squares += v * v;
      result.peak = std::max(result.peak, std::abs(v));
    }
    result.rms = std::sqrt(squares / request.pcm->size());
    result.input_statistics_complete = true;
    result.exact_silence = result.peak == 0; // no arbitrary amplitude/confidence gate
    auto resample_start = Clock::now();
    result.validation_us = micros(began, resample_start);
    const std::vector<float>* input = request.pcm;
    std::vector<float> converted;
    if (request.sample_rate != result.analysis_sample_rate) {
      control.enter(AnalysisStage::resampling);
#ifdef TINIUM_UNMODIFIED_REFERENCE
      converted = bitchord::smart::Resample(*input, request.sample_rate, result.analysis_sample_rate);
#else
      converted = bitchord::smart::ResampleControlled(*input, request.sample_rate, result.analysis_sample_rate, control);
#endif
      input = &converted;
    }
    control.checkpoint();
    const auto dsp_start = Clock::now();
    result.resample_us = micros(resample_start, dsp_start);
    // Sample-derived duration after conversion; no full-track/requested duration substitution.
    const double dsp_duration = static_cast<double>(input->size()) / result.analysis_sample_rate;
    result.analysis_duration_seconds = dsp_duration;
    result.analysis_frames = input->size();
    control.enter(AnalysisStage::tempo);
#ifdef TINIUM_UNMODIFIED_REFERENCE
    auto raw = bitchord::smart::AnalyzeTempo(*input, result.analysis_sample_rate, dsp_duration, request.audible_start_seconds);
#else
    auto raw = bitchord::smart::AnalyzeTempoControlled(*input, result.analysis_sample_rate, dsp_duration, request.audible_start_seconds, control);
#endif
    result.dsp_us = micros(dsp_start, Clock::now());
    control.checkpoint();
    Evidence e{raw.bpm, raw.beat_interval, raw.first_beat, raw.confidence, std::move(raw.beats), std::move(raw.downbeats)};
    // Upstream includes <=duration grid endpoints then adds the FFT half-window.
    // Validate raw geometry BEFORE dropping only those documented trailing events.
    const double raw_limit = dsp_duration + 256.0 / result.analysis_sample_rate + 1e-7;
    if (!ordered(e.beats_seconds, raw_limit) || !ordered(e.heuristic_downbeats_seconds, raw_limit) || !downbeat_subset(e)) {
      result.status = Status::invalid_output;
    } else {
      for (auto* events : {&e.beats_seconds, &e.heuristic_downbeats_seconds}) {
        const auto outside = std::lower_bound(events->begin(), events->end(), dsp_duration);
        result.boundary_events_omitted += static_cast<std::size_t>(events->end() - outside);
        events->erase(outside, events->end());
      }
      if (!valid_evidence(e, dsp_duration, result.analysis_sample_rate)) {
        result.status = Status::invalid_output;
      } else {
        result.evidence = std::move(e);
        result.status = result.exact_silence || result.evidence.bpm == 0 ? Status::no_evidence : Status::ok;
      }
    }
    control.checkpoint(); // A late cancellation cannot expose a successful stale result.
  } catch (const InvalidInput&) {
    result.status = Status::invalid_input;
  } catch (const Stop& stop) {
    result.status = stop.reason == StopReason::canceled ? Status::canceled : Status::deadline;
    result.evidence = {};
  } catch (const std::bad_alloc&) {
    result.status = Status::allocation_failure;
  } catch (...) {
    result.status = Status::internal_error;
  }
  result.elapsed_us = micros(began, Clock::now());
  return result;
}
}
