/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), whose native
 * analyzer this file is adapted from almost unchanged: the mix-out budget,
 * phrase detection and cue scoring below were tuned against real material,
 * and reimplementing them from a description would produce different numbers
 * that BitChord's transition policy is not calibrated for.
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

// Tinium Phase 2.3A: declarations-only subset; no key/structure/application API.
#pragma once
#include <vector>
namespace bitchord::smart {
struct TempoResult {
  double bpm = 0;
  // Seconds per beat; zero means that no defensible tempo was found.
  double beat_interval = 0;
  double first_beat = 0;
  double confidence = 0;
  std::vector<double> beats;
  std::vector<double> downbeats;
};

/**
 * Estimates tempo from at most the first 180 seconds, then *tracks* the beat
 * grid through `duration` with a phase-locked loop over the onset envelope
 * rather than extrapolating a fixed interval. `audible_start` is used only to
 * align the first reported beat near meaningful content.
 *
 * `beats` is therefore not uniformly spaced, and `beat_interval` is the
 * median locked interval rather than a spacing every entry honours. Callers
 * that need a specific beat's position must read `beats`; callers that need
 * a tempo (a time-stretch ratio, say) want `bpm`.
 */
TempoResult AnalyzeTempo(
  const std::vector<float>& samples,
  double sample_rate,
  double duration,
  double audible_start
);

}  // namespace bitchord::smart
