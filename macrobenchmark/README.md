# Baseline profiles

The baseline profile for this app is located at [`app/src/main/baseline-prof.txt`](../app/src/main/baseline-prof.txt).
It contains rules that enable AOT compilation of the critical user path taken during app launch.
For more information on baseline profiles, read [this document](https://developer.android.com/studio/profile/baselineprofiles).

> Note: The baseline profile needs to be re-generated for release builds that touch code which changes app startup.

To generate the baseline profile, select the `benchmark` build variant and run the
`BaselineProfileGenerator` test on a connected AOSP Android Emulator (API 33+), or run:

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.BaselineProfileGenerator
```

The target app is `xyz.b1ack0u7.aniyomi.mi.benchmark`. Its `benchmark` variant is
profileable, non-debuggable, signed with the debug key, and inherits release shrinking.
Complete initial app setup before running the test. Then copy the generated baseline
profile from the test output to [`app/src/main/baseline-prof.txt`](../app/src/main/baseline-prof.txt).
