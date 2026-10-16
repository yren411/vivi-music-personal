// SPDX-License-Identifier: AGPL-3.0-or-later
#include "tinium/analyzer.h"
#include "controlled_dsp.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <future>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>
#if defined(__linux__)
#include <sys/resource.h>
#endif

using namespace tinium::native_analysis;
namespace {
int assertions = 0;
#define CHECK(x) do { ++assertions; if (!(x)) throw std::runtime_error(std::string(#x) + " line " + std::to_string(__LINE__)); } while (false)
constexpr double pi = 3.14159265358979323846;
std::vector<float> groove(double bpm = 128, int rate = 22050, double seconds = 30, double phase = .137) {
  std::vector<float> pcm(static_cast<std::size_t>(seconds * rate));
  for (std::size_t i = 0; i < pcm.size(); ++i) {
    const double t = static_cast<double>(i) / rate;
    pcm[i] = static_cast<float>(.012 * std::sin(2 * pi * 261.625565 * t));
  }
  for (double beat = phase; beat < seconds; beat += 60 / bpm) {
    const auto start = static_cast<std::size_t>(std::llround(beat * rate));
    for (int j = 0; j < rate / 6 && start + j < pcm.size(); ++j) {
      const double t = static_cast<double>(j) / rate;
      pcm[start + j] += static_cast<float>(.65 * std::exp(-t * 36) * std::sin(2 * pi * 70 * t));
    }
  }
  return pcm;
}
Request request(const std::vector<float>& pcm, int rate = 22050, Path path = Path::resample_11025) {
  Request r; r.pcm = &pcm; r.sample_rate = rate; r.path = path; return r;
}
void equal(const bitchord::smart::TempoResult& a, const bitchord::smart::TempoResult& b) {
  CHECK(a.bpm == b.bpm); CHECK(a.confidence == b.confidence); CHECK(a.beat_interval == b.beat_interval);
  CHECK(a.first_beat == b.first_beat); CHECK(a.beats == b.beats); CHECK(a.downbeats == b.downbeats);
}
void input_tests() {
  CHECK(analyze({}).status == Status::invalid_input);
  std::vector<float> empty;
  CHECK(analyze(request(empty)).status == Status::invalid_input);
  auto pcm = groove(120, 22050, 4);
  for (int rate : {0, -1, 7999, 96001, std::numeric_limits<int>::max()}) CHECK(analyze(request(pcm, rate)).status == Status::invalid_input);
  auto r = request(pcm); r.channels = 2; CHECK(analyze(r).status == Status::invalid_input);
  r = request(pcm); r.window_origin_us = -1; CHECK(analyze(r).status == Status::invalid_input);
  r.window_origin_us = std::numeric_limits<std::int64_t>::max(); CHECK(analyze(r).status == Status::invalid_input);
  r = request(pcm); r.audible_start_seconds = std::numeric_limits<double>::infinity(); CHECK(analyze(r).status == Status::invalid_input);
  r.audible_start_seconds = 4; CHECK(analyze(r).status == Status::invalid_input);
  r.audible_start_seconds = -1; CHECK(analyze(r).status == Status::invalid_input);
  r = request(pcm); r.path = static_cast<Path>(42); CHECK(analyze(r).status == Status::invalid_input);
  for (float bad : {std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity()}) {
    pcm[123] = bad; CHECK(analyze(request(pcm)).status == Status::invalid_input);
  }
  const std::vector<float> oversized(22050 * 32 + 1);
  CHECK(analyze(request(oversized)).status == Status::invalid_input);
  const std::vector<float> over_frames(max_input_frames + 1);
  CHECK(analyze(request(over_frames, 96000)).status == Status::invalid_input);
  const std::vector<float> short_pcm(128);
  CHECK(analyze(request(short_pcm)).status == Status::no_evidence);
  pcm[123] = 0;
  CHECK(analyze(request(pcm)).status == Status::ok); // errors did not poison the next call
  const auto copy = pcm; analyze(request(pcm)); CHECK(copy == pcm);
}
void output_tests() {
  Evidence good{120, .5, .1, .7, {.1, .6, 1.1, 1.6}, {.1}};
  CHECK(valid_evidence(good, 2, 11025));
  auto e = good; e.bpm = std::numeric_limits<double>::quiet_NaN(); CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.native_confidence = 1.1; CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.interval_seconds = 1; CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.first_beat_seconds = -1; CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.first_beat_seconds = .2; CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.beats_seconds[1] = -.1; CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.beats_seconds[1] = .1; CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.beats_seconds[1] = 2; CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.beats_seconds[1] = std::numeric_limits<double>::infinity(); CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.heuristic_downbeats_seconds = {.2}; CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.beats_seconds.resize(max_beat_events + 1); CHECK(!valid_evidence(e, 2, 11025));
  e = good; e.beats_seconds.clear(); CHECK(!valid_evidence(e, 2, 11025));
  CHECK(!valid_evidence(good, std::numeric_limits<double>::infinity(), 11025));
  CHECK(!valid_evidence(good, 2, 0));
}
void parity_tests() {
  for (int rate : {11025, 22050, 48000}) {
    for (double bpm : {90., 107., 120., 128., 140., 150., 174.}) {
      auto pcm = groove(bpm, rate, 30);
      const auto a = bitchord::smart::AnalyzeTempo(pcm, rate, 30, 0);
      const auto b = bitchord::smart::AnalyzeTempoControlled(pcm, rate, 30, 0, {});
      equal(a, b); // exact original arithmetic/grid/confidence parity, no tolerated phase rotation
      if (rate != 11025) {
        const auto x = bitchord::smart::Resample(pcm, rate, 11025);
        const auto y = bitchord::smart::ResampleControlled(pcm, rate, 11025, {});
        CHECK(x == y);
        equal(bitchord::smart::AnalyzeTempo(x, 11025, static_cast<double>(x.size()) / 11025, 0),
              bitchord::smart::AnalyzeTempoControlled(y, 11025, static_cast<double>(y.size()) / 11025, 0, {}));
      }
    }
  }
}
void rhythm_tests() {
  for (double bpm : {90., 107., 120., 128., 140., 150., 174.}) {
    auto pcm = groove(bpm);
    auto r = analyze(request(pcm));
    CHECK(r.status == Status::ok); CHECK(valid_evidence(r.evidence, r.duration_seconds, r.analysis_sample_rate));
    const double ratio = r.evidence.bpm / bpm;
    const double metrical_error = std::min({std::abs(ratio - 1), std::abs(ratio * 2 - 1), std::abs(ratio / 2 - 1)});
    CHECK(metrical_error < .02); // expressly octave-aware; strict errors/F1 remain in raw benchmark
  }
  for (double phase : {.023, .137, .31}) {
    auto pcm = groove(128, 22050, 30, phase);
    auto a = analyze(request(pcm)); auto req = request(pcm); req.window_origin_us = 180000000;
    auto b = analyze(req);
    CHECK(a.evidence.beats_seconds == b.evidence.beats_seconds); CHECK(b.window_origin_us == 180000000);
    CHECK(std::abs(a.evidence.bpm - 128) < 1);
    std::size_t matched = 0;
    for (double time : a.evidence.beats_seconds) {
      const double index = std::round((time - phase) * 128 / 60);
      if (index >= 0 && std::abs(time - (phase + index * 60 / 128)) < .03) ++matched;
    }
    CHECK(matched >= a.evidence.beats_seconds.size() * .9);
  }
}
void uncertainty_tests() {
  std::vector<float> silence(22050 * 30);
  const auto silent = analyze(request(silence));
  CHECK(silent.status == Status::no_evidence); CHECK(silent.exact_silence); CHECK(silent.rms == 0);
  std::vector<float> chord(silence.size());
  for (std::size_t i = 0; i < chord.size(); ++i) {
    double t = static_cast<double>(i) / 22050;
    chord[i] = static_cast<float>(.05 * (std::sin(2*pi*261.625565*t)+std::sin(2*pi*329.627557*t)+std::sin(2*pi*391.995436*t)));
  }
  auto a = analyze(request(chord));
  const auto x = bitchord::smart::Resample(chord, 22050, 11025);
  const auto raw = bitchord::smart::AnalyzeTempo(x, 11025, 30, 0);
  CHECK(a.evidence.native_confidence == raw.confidence); // do not tune away inconvenient false positives
  CHECK(a.evidence.bpm == raw.bpm);
  std::cout << "{\"stationary_chord_raw_confidence\":" << a.evidence.native_confidence << ",\"bpm\":" << a.evidence.bpm << "}\n";
}
void resampling_tests() {
  for (int rate : {8000, 11025, 16000, 22050, 32000, 44100, 48000, 96000}) {
    std::vector<float> dc(rate * 2, .125f);
    const auto out = bitchord::smart::ResampleControlled(dc, rate, 11025, {});
    CHECK(out.size() == 22050);
    CHECK(std::all_of(out.begin(), out.end(), [](float x) { return std::abs(x - .125f) < 1e-6; }));
    if (rate == 11025) CHECK(out == dc);
  }
  for (int rate : {22050, 44100, 48000, 96000}) {
    std::vector<float> tone(rate * 2), stop(rate * 2), impulse(rate * 2);
    for (std::size_t i = 0; i < tone.size(); ++i) {
      tone[i] = static_cast<float>(.5 * std::sin(2*pi*1000*i/rate));
      stop[i] = static_cast<float>(.5 * std::sin(2*pi*8000*i/rate));
    }
    impulse[rate] = 1;
    const auto pass = bitchord::smart::ResampleControlled(tone, rate, 11025, {});
    const auto rejected = bitchord::smart::ResampleControlled(stop, rate, 11025, {});
    double error = 0, alias = 0;
    for (std::size_t i = 256; i + 256 < pass.size(); ++i) {
      const double expected = .5 * std::sin(2*pi*1000*i/11025);
      error += (pass[i]-expected)*(pass[i]-expected); alias += rejected[i]*rejected[i];
    }
    CHECK(std::sqrt(error/(pass.size()-512)) < .001);
    CHECK(std::sqrt(alias/(pass.size()-512)) < .0001); // >70 dB below the .354 RMS input
    const auto pulse = bitchord::smart::ResampleControlled(impulse, rate, 11025, {});
    CHECK(static_cast<std::size_t>(std::max_element(pulse.begin(),pulse.end())-pulse.begin()) == 11025);
  }
}
struct Counter { int count = 0, stop_at = 10; };
bool count_stop(void* value) { auto& c = *static_cast<Counter*>(value); return ++c.count >= c.stop_at; }
void cancellation_tests() {
  auto pcm = groove(128, 22050, 32);
  Counter c; Control control; control.is_canceled = count_stop; control.context = &c;
  bool caught = false;
  try { bitchord::smart::AnalyzeTempoControlled(pcm, 22050, 32, 0, control); }
  catch (Stop s) { caught = s.reason == StopReason::canceled; }
  CHECK(caught); CHECK(c.count == c.stop_at); // interruption in real native DSP, not wrapper validation
  c = {}; caught = false;
  try { bitchord::smart::ResampleControlled(pcm, 22050, 11025, control); }
  catch (Stop s) { caught = s.reason == StopReason::canceled; }
  CHECK(caught); CHECK(c.count == c.stop_at);
  Control expired; expired.deadline = std::chrono::steady_clock::time_point::min();
  CHECK(analyze(request(pcm), expired).status == Status::deadline);
  Control canceled; canceled.is_canceled = [](void*) { return true; };
  CHECK(analyze(request(pcm), canceled).status == Status::canceled);
  Control bad_alloc; bad_alloc.is_canceled = [](void*) -> bool { throw std::bad_alloc(); };
  CHECK(analyze(request(pcm), bad_alloc).status == Status::allocation_failure); // injected exception mapping, not a forced system OOM
  Control unexpected; unexpected.is_canceled = [](void*) -> bool { throw std::runtime_error("test"); };
  CHECK(analyze(request(pcm), unexpected).status == Status::internal_error);
  Counter complete; complete.stop_at = std::numeric_limits<int>::max();
  Control counted; counted.context = &complete; counted.is_canceled = count_stop;
  CHECK(analyze(request(pcm), counted).status == Status::ok);
  Counter late; late.stop_at = complete.count;
  counted.context = &late;
  const auto stale = analyze(request(pcm), counted);
  CHECK(stale.status == Status::canceled); CHECK(stale.evidence.beats_seconds.empty());
  struct Async { std::atomic<int> checks{0}; std::atomic<bool> cancel{false}; } state;
  Control asynchronous; asynchronous.context = &state;
  asynchronous.is_canceled = [](void* p) { auto& s = *static_cast<Async*>(p); ++s.checks; return s.cancel.load(); };
  auto long_pcm = groove(128, 96000, 32);
  auto job = std::async(std::launch::async, [&] {
    try { bitchord::smart::AnalyzeTempoControlled(long_pcm, 96000, 32, 0, asynchronous); return false; }
    catch (Stop s) { return s.reason == StopReason::canceled; }
  });
  const auto wait_limit = std::chrono::steady_clock::now() + std::chrono::seconds(5);
  while (state.checks.load() < 100 && std::chrono::steady_clock::now() < wait_limit) std::this_thread::yield();
  CHECK(state.checks.load() >= 100);
  const auto cancel_start = std::chrono::steady_clock::now(); state.cancel = true;
  CHECK(job.get());
  const double latency = std::chrono::duration<double,std::micro>(std::chrono::steady_clock::now()-cancel_start).count();
  std::cout << "{\"raw_tempo_cancel_latency_us\":" << latency << "}\n";
  CHECK(analyze(request(pcm)).status == Status::ok);
}
void concurrency_tests() {
  auto pcm = groove(128, 22050, 32);
  const auto baseline = analyze(request(pcm));
  std::vector<std::future<Result>> jobs;
  for (int i = 0; i < 4; ++i) jobs.push_back(std::async(std::launch::async, [&] { return analyze(request(pcm)); }));
  for (auto& job : jobs) {
    const auto r = job.get(); CHECK(r.status == baseline.status);
    CHECK(r.evidence.beats_seconds == baseline.evidence.beats_seconds);
    CHECK(r.evidence.native_confidence == baseline.evidence.native_confidence);
  }
  // Also model independent worker-owned windows, not just shared read-only PCM.
  std::array<std::vector<float>, 4> separate;
  std::array<Result, 4> expected;
  jobs.clear();
  for (std::size_t i = 0; i < separate.size(); ++i) {
    separate[i] = groove(128, 22050, 32, .137 + .03 * i);
    expected[i] = analyze(request(separate[i]));
  }
  for (std::size_t i = 0; i < separate.size(); ++i)
    jobs.push_back(std::async(std::launch::async, [&, i] { return analyze(request(separate[i])); }));
  for (std::size_t i = 0; i < jobs.size(); ++i) {
    const auto r = jobs[i].get(); CHECK(r.status == expected[i].status);
    CHECK(r.evidence.beats_seconds == expected[i].evidence.beats_seconds);
    CHECK(r.evidence.native_confidence == expected[i].evidence.native_confidence);
  }
#if defined(__linux__)
  rusage usage{}; if (getrusage(RUSAGE_SELF, &usage) == 0)
    std::cout << "{\"four_job_process_peak_rss_kib\":" << usage.ru_maxrss << "}\n";
#endif
}
}
int main(int argc, char** argv) {
  try {
    if (argc != 2) throw std::runtime_error("suite required");
    const std::string suite = argv[1];
    if (suite == "input") input_tests(); else if (suite == "output") output_tests();
    else if (suite == "parity") parity_tests(); else if (suite == "rhythm") rhythm_tests();
    else if (suite == "uncertainty") uncertainty_tests(); else if (suite == "resampling") resampling_tests(); else if (suite == "cancellation") cancellation_tests();
    else if (suite == "concurrency") concurrency_tests(); else throw std::runtime_error("unknown suite");
    std::cout << "{\"suite\":\"" << suite << "\",\"assertions_passed\":" << assertions << "}\n";
    return 0;
  } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
