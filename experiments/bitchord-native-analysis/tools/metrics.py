# SPDX-License-Identifier: AGPL-3.0-or-later
"""One-to-one monotonic beat matching. Never phase-rotate or octave-normalize beats."""
import hashlib
import json
import math
import statistics

def percentile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    position = (len(values)-1)*fraction
    lo = math.floor(position); hi = math.ceil(position)
    return values[lo] + (values[hi]-values[lo])*(position-lo)

def beats(reference, predicted, tolerance, start=0, end=math.inf):
    ref=[v for v in reference if start <= v < end]
    pred=[v for v in predicted if start <= v < end]
    i=j=0; errors=[]
    while i<len(ref) and j<len(pred):
        if pred[j] < ref[i]-tolerance:
            j+=1
        elif pred[j] > ref[i]+tolerance:
            i+=1
        else:
            errors.append(pred[j]-ref[i]); i+=1; j+=1
    matches=len(errors)
    precision=matches/len(pred) if pred else (1.0 if not ref else 0.0)
    recall=matches/len(ref) if ref else None
    f1=2*precision*recall/(precision+recall) if ref and precision+recall else (0.0 if ref else None)
    return dict(expected=len(ref),detected=len(pred),matches=matches,precision=precision,recall=recall,f1=f1,
                median_error_ms=statistics.median(abs(v)*1000 for v in errors) if errors else None,
                p95_error_ms=percentile([abs(v)*1000 for v in errors],.95),
                matched_drift_ms=(errors[-1]-errors[0])*1000 if len(errors)>1 else None,
                false_beats=len(pred)-matches)

def evaluate(fixture, result):
    expected=fixture.get('expected_bpm')
    bpm=result.get('bpm',0)
    # Drift fixtures have no single true constant tempo: report interval-average reference BPM.
    if fixture.get('drift_bpm') and len(fixture['expected_beats_seconds'])>1:
        times=fixture['expected_beats_seconds']; expected=60*(len(times)-1)/(times[-1]-times[0])
    ratio=bpm/expected if expected else None
    interpretation='unavailable'
    if ratio is not None and bpm>0:
        interpretation=('correct_2pct' if abs(ratio-1)<=.02 else 'half_time' if abs(ratio*2-1)<=.02 else
                        'double_time' if abs(ratio/2-1)<=.02 else 'other')
    margin=min(1.0,fixture['seconds']/10)
    return dict(expected_bpm=expected, absolute_bpm_error=abs(bpm-expected) if expected else None,
                metrical_interpretation=interpretation,
                at_30ms=beats(fixture['expected_beats_seconds'],result.get('beats_seconds',[]),.03,margin,fixture['seconds']-margin),
                at_70ms=beats(fixture['expected_beats_seconds'],result.get('beats_seconds',[]),.07,margin,fixture['seconds']-margin),
                evaluation_margin_seconds=margin)

SEMANTIC_KEYS=('status','path','origin_us','duration_seconds','input_rate','analysis_rate','bpm','interval_seconds',
               'analysis_duration_seconds','input_frames','analysis_frames',
               'first_beat_seconds','native_confidence','rms','peak','exact_silence','boundary_events_omitted',
               'beats_seconds','heuristic_downbeats_seconds','unscored_metrical_hypotheses')
def semantic(result):
    return {k:result[k] for k in SEMANTIC_KEYS}
def fingerprint(result):
    data=json.dumps(semantic(result),sort_keys=True,separators=(',',':'),allow_nan=False).encode()
    return hashlib.sha256(data).hexdigest()
