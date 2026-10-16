// SPDX-License-Identifier: AGPL-3.0-or-later
#include "tinium/analyzer.h"
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>

namespace {
long long integer(const std::string& text) {
  if (text.size() > 24) throw std::invalid_argument("argument");
  std::size_t consumed = 0;
  const auto number = std::stoll(text, &consumed);
  if (consumed != text.size()) throw std::invalid_argument("argument");
  return number;
}
void array(const std::vector<double>& values) {
  std::cout << '[';
  for (std::size_t i = 0; i < values.size(); ++i) { if (i) std::cout << ','; std::cout << values[i]; }
  std::cout << ']';
}
void timing(double value) { if (value < 0) std::cout << "null"; else std::cout << value; }
}
int main(int argc, char** argv) {
  using namespace tinium::native_analysis;
  try {
    static_assert(sizeof(float) == 4 && std::numeric_limits<float>::is_iec559, "IEEE Float32 required");
    Request request;
    std::string filename;
    long long budget_ms = 10000;
    for (int i = 1; i < argc; i += 2) {
      if (i + 1 == argc) throw std::invalid_argument("argument");
      const std::string arg = argv[i], value = argv[i + 1];
      if (arg == "--input") filename = value;
      else if (arg == "--sample-rate") {
        const auto rate = integer(value);
        if (rate < 8000 || rate > 96000) throw std::invalid_argument("rate");
        request.sample_rate = static_cast<int>(rate);
      } else if (arg == "--channels") {
        if (integer(value) != 1) throw std::invalid_argument("channels");
      } else if (arg == "--origin-us") request.window_origin_us = integer(value);
      else if (arg == "--deadline-ms") budget_ms = integer(value);
      else if (arg == "--path") {
        if (value == "direct") request.path = Path::direct;
        else if (value == "resample11025") request.path = Path::resample_11025;
        else throw std::invalid_argument("path");
      } else throw std::invalid_argument("argument");
    }
    if (filename.empty() || budget_ms < 1 || budget_ms > 60000) throw std::invalid_argument("argument");
    std::ifstream input(filename, std::ios::binary | std::ios::ate);
    const auto bytes = input.tellg();
    if (!input || bytes <= 0 || bytes % 4 != 0 || bytes / 4 > static_cast<std::streamoff>(max_input_frames))
      throw std::invalid_argument("file");
    input.seekg(0);
    std::vector<float> pcm(static_cast<std::size_t>(bytes / 4));
    input.read(reinterpret_cast<char*>(pcm.data()), bytes);
    if (!input) throw std::invalid_argument("file");
    const std::uint16_t endian = 1;
    if (*reinterpret_cast<const unsigned char*>(&endian) != 1) {
      for (float& value : pcm) {
        unsigned char b[4]; std::memcpy(b, &value, 4);
        const unsigned char swapped[]{b[3], b[2], b[1], b[0]}; std::memcpy(&value, swapped, 4);
      }
    }
    request.pcm = &pcm;
    Control control;
    control.deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(budget_ms);
    const Result r = analyze(request, control);
    const auto& e = r.evidence;
    std::cout << std::setprecision(17) << "{\"schema\":1,\"backend\":\"";
#ifdef TINIUM_UNMODIFIED_REFERENCE
    std::cout << "bitchord-fe95bb60-unmodified-reference";
#else
    std::cout << "bitchord-fe95bb60-controlled-v1";
#endif
    std::cout << "\",\"status\":\"" << status_name(r.status) << "\",\"path\":\""
      << (request.path == Path::direct ? "direct" : "resample11025") << "\",\"origin_us\":" << r.window_origin_us
      << ",\"duration_seconds\":" << r.duration_seconds << ",\"input_rate\":" << r.input_sample_rate
      << ",\"analysis_duration_seconds\":" << r.analysis_duration_seconds
      << ",\"input_frames\":" << r.input_frames << ",\"analysis_frames\":" << r.analysis_frames
      << ",\"analysis_rate\":" << r.analysis_sample_rate << ",\"bpm\":" << e.bpm
      << ",\"interval_seconds\":" << e.interval_seconds << ",\"first_beat_seconds\":" << e.first_beat_seconds
      << ",\"native_confidence\":" << e.native_confidence
      << ",\"confidence_semantics\":\"uncalibrated upstream tempo/phase/separation heuristic\""
      << ",\"downbeat_semantics\":\"bass-accent heuristic assuming four-beat grouping\""
      << ",\"rms\":";
    if (r.input_statistics_complete) std::cout << r.rms; else std::cout << "null";
    std::cout << ",\"peak\":";
    if (r.input_statistics_complete) std::cout << r.peak; else std::cout << "null";
    std::cout << ",\"exact_silence\":";
    if (r.input_statistics_complete) std::cout << (r.exact_silence ? "true" : "false"); else std::cout << "null";
    std::cout << ",\"boundary_events_omitted\":" << r.boundary_events_omitted << ",\"beats_seconds\":";
    array(e.beats_seconds);
    std::cout << ",\"heuristic_downbeats_seconds\":"; array(e.heuristic_downbeats_seconds);
    // These are unscored metrical hypotheses, not measured alternative-tempo probabilities.
    std::cout << ",\"unscored_metrical_hypotheses\":";
    std::vector<double> alternatives;
    if (e.bpm > 0) for (double factor : {0.5, 1.0, 2.0}) if (e.bpm * factor >= 60 && e.bpm * factor <= 220) alternatives.push_back(e.bpm * factor);
    array(alternatives);
    std::cout << ",\"validation_us\":"; timing(r.validation_us);
    std::cout << ",\"resample_us\":"; timing(r.resample_us);
    std::cout << ",\"dsp_us\":"; timing(r.dsp_us);
    std::cout << ",\"elapsed_us\":" << r.elapsed_us << "}\n";
    return r.status == Status::ok || r.status == Status::no_evidence ? 0 : 2;
  } catch (...) {
    std::cout << "{\"schema\":1,\"status\":\"CLI_INPUT_ERROR\"}\n";
    return 2;
  }
}
