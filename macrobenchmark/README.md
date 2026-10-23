# Baseline profiles

The baseline profile for this app is located at [`app/src/main/baseline-prof.txt`](../app/src/main/baseline-prof.txt).
It contains rules that enable AOT compilation of the critical user path taken during app launch.
For more information on baseline profiles, read [this document](https://developer.android.com/studio/profile/baselineprofiles).

> Note: The baseline profile needs to be re-generated for release builds that touch code which changes app startup.

The target app is `xyz.b1ack0u7.aniyomi.mi.benchmark`. Its `benchmark` variant is
profileable, non-debuggable, signed with the debug key, and inherits release shrinking.
Use a connected AOSP Android Emulator (API 33+) and complete initial app setup first.
Set the app language to English and use the default navigation layout, with History
inside More. The profile journey visits Anime, Manga, Updates, More, History and Settings.

Startup measurements and profile generation require separate builds. First run startup
measurements with R8 enabled, matching the normal benchmark configuration:

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.ColdStartupBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
```

Use a physical device for performance measurements. For an emulator smoke test of
the benchmark infrastructure, additionally pass
`-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`.
Emulator timings do not establish a performance improvement or regression.

Then generate the source profile with shrinking and obfuscation disabled for that invocation:

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pdisable-code-shrink \
  -Pandroid.testInstrumentationRunnerArguments.class=tachiyomi.macrobenchmark.BaselineProfileGenerator
```

Review the generated text profile and confirm its class and method names are unobfuscated
before copying it from the test output to
[`app/src/main/baseline-prof.txt`](../app/src/main/baseline-prof.txt). `BaselineProfileRule`
does not automatically retrace profiles captured from an obfuscated APK. Keep such output
with its exact APK and R8 mapping as diagnostic evidence; do not copy it into the source profile.

Omit `-Pdisable-code-shrink` when rebuilding release or measuring startup again. R8 transforms
the source profile to match the optimized release APK. See the
[profile generation and release build requirements](https://developer.android.com/topic/performance/baselineprofiles/overview).

Some older 16 KB emulator images produce invalid ftrace captures with
`FTRACE_STATUS_PARTIAL_PAGE_READ`. Use a 4 KB image or a 16 KB image with the
[kernel sub-buffer fix](https://android.googlesource.com/kernel/common/+/66e97080dfaa246d68d21ee8f48c05c693463d6e)
and capture again. Do not suppress critical parsing errors or interpret missing
metrics as a successful benchmark. TraceProcessor can report missing SQL tables
and terminate after receiving such an invalid capture.
