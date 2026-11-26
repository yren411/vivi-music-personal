# Phase 3.3.4 evidence inventory

Base: PR #26 merged as `76eda458c3ac2dc0efd307d24116bf4c68cd31df`.
Read [architecture, limits and exact device commands](../../AUTOMIX_ADAPTIVE_ANALYSIS_AND_REUSE.md).
Historical Phase 3.3.3/v8 artifacts remain unchanged. The owner's prior 79/79 pass is not
transferred to this new implementation; new physical-device execution is pending.

- `generated-fixture.json`: genuine 30 s empty MP4 edit; unchanged encoded `mdat`, used to
  preserve strict unsupported-source service fallback assertions after small-edge recovery.
- `validation.json`: exact executed JVM/host-JNI/build counts, source baseline, commands and
  unavailable Android/native work. Do not equate compilation with phone execution.
- `host-jni-*.json`: actual host corrected capture/resampling/native execution and cached
  evidence reuse with simulated source transport. No Android codec or acoustic claims.
- `resource-budgets.json`: explicit limits and calculated PCM allocation comparisons,
  not measured Android RSS, CPU, battery or thermal behavior.
- `apk-packaging.json`: actual APK four-ABI, ELF/ZIP 16 KB, JNI/license verification.

Reproduce the new fixture with `python scripts/generate_automix_adaptive_fixture.py`.
Reproducibility is checked against the committed SHA-256. No commercial recording or
private source/cache material is included. The new host tests retain failed-boundary,
true-gap, stale-plan and conflict outcomes rather than turning them into artificial success.

Full local matrix (requires existing SDK/NDK/JDK and actual host JNI library):

```bash
bash gradlew :app:testArm64GmsDebugUnitTest --rerun \
  :app:testUniversalFossDebugUnitTest --rerun \
  :app:assembleArm64GmsDebug :app:assembleUniversalFossDebug \
  :app:assembleArm64GmsDebugAndroidTest :app:assembleUniversalFossDebugAndroidTest \
  -PautomixNativeHostLibrary=/tmp/tinium-android-jni-host/libtinium_automix.so \
  -PautomixDecoderTests=true --no-configuration-cache --no-daemon --max-workers=4 --console=plain
```

Host-JNI tests are included in JVM counts. Native DSP/JNI/CMake files are unchanged;
prior native CTest/sanitizer evidence remains historical, not freshly executed here.
Pool counts distinguish source-region jobs from actual decode calls (a boundary job can
make two). Replanning is not reanalysis. Optional enrichment has a separate finite budget,
requires offline-only reads and never runs during active execution/fallback.

No real commercial-song retest, Samsung screen-recorder reproduction, acoustic/EQ result,
phone CPU/thermal measurement or new physical-device pass is fabricated in this inventory.

Final local result: **599 + 599 = 1,198 JVM passes**, zero failures/errors/skips, including
38 actual host-JNI executions. Both app/test APK variants assembled and actual four-ABI /
16 KB / JNI / license checks passed. No new Android execution is claimed. Targeted phone
selection has 12 methods; expanded regression has 91. CI status belongs to its actual
commit and is reported in the PR rather than inferred from local tests.

Initial host boundary measurements are preserved as `initial-host-jni-*.json`; final
measurements are `host-jni-*.json`. `host-progressive-*.json` records three real native
segment calls covering a generated 96 s source model without a whole-track PCM allocation.
The initial new test requiring fallback after a passed cue failed because a later safe
cue was valid. Its corrected assertion requires stale-schedule removal and fresh admission;
no existing safety assertion was deleted or relaxed.
