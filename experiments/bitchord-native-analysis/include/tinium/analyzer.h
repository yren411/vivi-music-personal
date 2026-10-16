// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include "control.h"
#include <cstdint>
#include <vector>

namespace tinium::native_analysis {
enum class Path { resample_11025, direct };
enum class Status { ok, no_evidence, invalid_input, invalid_output, canceled, deadline, allocation_failure, internal_error };
struct Request {
  const std::vector<float>* pcm = nullptr; // borrowed, mono IEEE Float32; never mutated/retained
  int sample_rate = 22050;
  int channels = 1;
  std::int64_t window_origin_us = 0; // metadata only: beat vectors stay WINDOW-relative seconds
  double audible_start_seconds = 0; // explicit caller input, NOT a ground-truth beat phase
  Path path = Path::resample_11025;
};
struct Evidence {
  double bpm = 0;
  double interval_seconds = 0;
  double first_beat_seconds = 0;
  double native_confidence = 0; // unchanged upstream heuristic, NOT a probability or legacy score
  std::vector<double> beats_seconds;
  std::vector<double> heuristic_downbeats_seconds; // bass-accent choice under assumed 4-beat grouping
};
struct Result {
  Status status = Status::invalid_input;
  Evidence evidence;
  std::int64_t window_origin_us = 0;
  double duration_seconds = 0;
  double analysis_duration_seconds = 0;
  std::size_t input_frames = 0, analysis_frames = 0;
  int input_sample_rate = 0;
  int analysis_sample_rate = 0;
  double rms = 0, peak = 0;
  bool input_statistics_complete = false;
  bool exact_silence = false;
  std::size_t boundary_events_omitted = 0;
  double resample_us = -1, dsp_us = -1, validation_us = -1, elapsed_us = 0;
};
constexpr double max_duration_seconds = 32;
constexpr std::size_t max_input_frames = 96000 * 32;
constexpr std::size_t max_beat_events = 512;
const char* status_name(Status status) noexcept;
// Also testable independently against malformed native output. No sorting/phase rotation.
bool valid_evidence(const Evidence& evidence, double duration_seconds, int analysis_rate) noexcept;
Result analyze(const Request& request, const Control& control = {}) noexcept;
}
