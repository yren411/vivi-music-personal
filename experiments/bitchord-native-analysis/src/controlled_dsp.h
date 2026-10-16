// SPDX-License-Identifier: AGPL-3.0-or-later
#pragma once
#include "audio_analysis.h"
#include "resampler.h"
#include "tinium/control.h"
namespace bitchord::smart {
TempoResult AnalyzeTempoControlled(const std::vector<float>& samples, double sample_rate,
    double duration, double audible_start, const tinium::native_analysis::Control& control);
std::vector<float> ResampleControlled(const std::vector<float>& input, double input_rate,
    double output_rate, const tinium::native_analysis::Control& control);
}
