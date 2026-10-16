// SPDX-License-Identifier: AGPL-3.0-or-later
// Tinium standalone prototype, 2026. No process-global cancellation state.
#pragma once
#include <chrono>

namespace tinium::native_analysis {
enum class StopReason { canceled, deadline };
enum class AnalysisStage { input_validation = 0, resampling = 1, tempo = 2 };
struct Stop { StopReason reason; };
struct Control {
  // Call-owned context must remain alive through analyze(). No callback is retained.
  bool (*is_canceled)(void*) = nullptr;
  void* context = nullptr;
  // Optional synchronous observer, used by JNI diagnostics and deterministic cancellation tests.
  // Borrowed call-owned context; no thread or callback is retained.
  void (*stage_changed)(void*, AnalysisStage) = nullptr;
  std::chrono::steady_clock::time_point deadline = std::chrono::steady_clock::time_point::max();
  void checkpoint() const {
    if (is_canceled && is_canceled(context)) throw Stop{StopReason::canceled};
    if (std::chrono::steady_clock::now() >= deadline) throw Stop{StopReason::deadline};
  }
  void enter(AnalysisStage stage) const { if (stage_changed) stage_changed(context, stage); }
};
}
