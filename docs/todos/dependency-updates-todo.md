# Dependency updates — pending work

Gap analysis of our version catalogs against Mihon. Every number below was read from
the two repos' catalogs, not estimated: ours from `gradle/{libs,androidx,compose,
kotlinx,aniyomi}.versions.toml` plus `buildSrc/.../AndroidConfig.kt`, theirs from
`mihon/main` at `55be95dd5` (2026-08-02).

To refresh this comparison in a later session:

```sh
git remote add mihon https://github.com/mihonapp/mihon.git   # if not present
git fetch mihon
git show mihon/main:gradle/libs.versions.toml
git show mihon/main:gradle/mihon.versions.toml
```

## Why the gap is this wide

The last `Merge from Mihon` was `3ecca45bb` (2025-05-30) — fourteen months. Since then
upstream also **restructured the build**, so there is no straight sync to perform:

- `buildSrc` → `gradle/build-logic`
- Five split version catalogs → a single `libs.versions.toml` (+ `mihon.versions.toml`
  holding only SDK/JDK/NDK numbers)
- `:macrobenchmark` → `baseline-profile`
- New `telemetry` module, which is where their Firebase/Crashlytics deps live

We keep `buildSrc`, the split catalogs and `:macrobenchmark`. Adopting their layout is
a separate decision from bumping versions; this document is only about versions.

## Already done

`kotlinx-coroutines` 1.10.1 → **1.11.0** landed separately as the fix for the Comix
crash (`NoSuchMethodError: No static method runBlockingK$default`). Extensions compile
against coroutines as `compileOnly` and resolve it from the host app, and keiyoushi
moved to 1.11.0, where `runBlocking` carries `@JvmName("runBlockingK")`. 1.11.0 keeps
the old `runBlocking` symbol too, so extensions built against 1.7.1/1.10.x still link —
which is why anime extensions were never affected.

## Already level with upstream

No action needed: `okhttp` 5.4.0, `kotlinx-coroutines` 1.11.0, `libarchive` 1.1.6,
`quickjs`, `injekt`, `material` 1.12.0, `leakcanary` 2.14, `rxjava` 1.3.8, `swipe`
1.3.0, `photoview` 2.3.0, `glance` 1.1.1, `desugar` 2.1.5, `flexible-adapter`,
`directionalviewpager`, `disklrucache`, `subsampling-scale-image-view`,
`natural-comparator`, `compose-grid`, `compose-webview`, `material-motion`,
`biometric`, `preference`, `profileinstaller`, `recyclerview`, `viewpager`,
`interpolator`, minSdk 26, targetSdk 36, Java 17.

## Level 1 — low-risk bumps

Leaf libraries, no API break expected. Reasonable as a single commit.

| Dep | Ours | Mihon |
| --- | --- | --- |
| `jsoup` | 1.19.1 | 1.23.1 |
| `okio` | 3.10.2 | 3.18.1 |
| `conscrypt` | 2.5.3 | 2.6.1 |
| `coil` | 3.1.0 | 3.5.0 |
| `shizuku` | 13.1.0 | 13.1.5 |
| `logcat` | 0.1 | 0.4 |
| `mockk` | 1.13.17 | 1.14.11 |
| `appcompat` | 1.7.0 | 1.7.1 |
| `constraintlayout` | 2.2.1 | 2.2.2 |
| `annotation` | 1.9.1 | 1.10.0 |
| `core-splashscreen` | 1.0.1 | 1.2.0 |
| `work` | 2.10.0 | 2.11.2 |
| `paging` | 3.3.6 | 3.5.0 |
| `lifecycle` | 2.8.7 | 2.11.0 |
| `benchmark` | 1.3.3 | 1.4.1 |
| `espresso` | 3.6.1 | 3.7.0 |
| `uiautomator` | 2.3.0 | 2.4.0 |
| `spotless` | 7.0.2 | 8.9.0 |
| `ktlint` | 1.5.0 | 1.8.0 |

Bumping Spotless/ktlint will likely reformat files on `spotlessApply`; keep that in its
own commit so it does not bury the real changes.

## Level 2 — needs code changes

| Dep | Ours | Mihon | What it drags in |
| --- | --- | --- | --- |
| `kotlinx-serialization` | 1.9.0 | 1.11.0 | pairs with the Kotlin bump |
| `xmlutil` | 0.90.3 | 1.0.1 | 1.0 release; check ComicInfo in `:core-metadata` |
| `moko-resources` | 0.24.5 | 0.26.4 | touches **both** `:i18n` (MR) and `:i18n-aniyomi` (AYMR) |
| `sqldelight` | 2.0.2 | 2.3.2 | they also moved to their own `sqldelight-androidx-driver` and added `async-extensions` |
| `aboutlibraries` | 11.6.3 | 15.0.4 | four majors; plugin id changed to `com.mikepenz.aboutlibraries.plugin.android` |
| `reorderable` | 2.4.3 | 3.1.0 | API break in 3.x |
| `junit` | 5.11.4 | 6.1.2 | major; upstream also adds `junit-platform-launcher` |
| `kotest-assertions` | 5.9.1 | 6.2.3 | major |
| `unifile` | `tachiyomiorg:e0def6b3dc` | `mihon:08f224c8f9` | fork moved groups |
| `image-decoder` | `tachiyomiorg:41c059e540` | `mihonapp:e03b81e18a` | fork moved groups |
| SQLite | `sqlite-framework` + `sqlite-ktx` 2.4.0 + requery `sqlite-android` 3.49.0 | `androidx-sqlite-bundled` 2.7.0 | architecture change |

The SQLite item is the one to be careful with: we carry **two** databases (`Database`
and `AnimeDatabase`, separate schemas and separate migration sequences), so anything
touching the driver has to be validated on both.

## Level 3 — real migrations, one branch each

| Item | Ours | Mihon |
| --- | --- | --- |
| Kotlin | 2.2.0 | 2.4.10 |
| AGP | 8.9.1 | 9.3.1 |
| Gradle | 8.13 | 9.6.1 |
| Voyager | 1.0.1 | 2.2.21-1.10.3 |
| compileSdk | 36 | 37 |
| NDK | 27.1.12297006 | 29.0.14206865 |
| Compose BOM | 2025.03.00 (stable) | 2026.07.01 (**alpha** channel) |

Two things worth deciding deliberately rather than by imitation:

- **Do not copy Mihon's Compose BOM.** They consume `androidx.compose:compose-bom-alpha`;
  we are on the stable `compose-bom`. The right move is the newest *stable* BOM, not
  their number.
- **The NDK bump has standalone value for us.** See `docs/16kb-page-size.md` — three
  native libraries are still blocked on 16 KB page alignment, and NDK 29 may resolve
  some of them. Worth doing on its own merits, not just to match upstream.

Voyager 2.x breaks the navigation API, and the whole UI sits on it — including the
duplicated anime/manga tabs, so expect the change to land twice.

## Deps Mihon has that we don't

Adopt only if we want the corresponding feature; these are not debt by themselves.

- `kotlinx-datetime` 0.8.0, `androidx-webkit` 1.16.0, `materialKolor` 5.0.0,
  `stringSimilarity` 0.1.0
- `multiplatform-markdown-renderer` 0.43.0 + `composeRichEditor` — upstream's
  **replacement for `compose-richtext`**. We are still on richtext 0.20.0, which is
  unmaintained, so this one is genuine debt even if the rest of the list is not.
- Firebase BOM + Crashlytics + `google-services`, and `tapmoc` — product decisions tied
  to their `telemetry` module.

## Deps we keep that Mihon dropped

Not gaps; listed so a future sync does not delete them by accident:
`kotlinx-collections-immutable`, `insetter`, `compose-stable-marker`,
`localbroadcastmanager`, `android-shortcut-gradle`. Plus the entire anime stack
(`aniyomi-mpv-lib`, `ffmpeg-kit`, `torrserver`, `nanohttpd`, `seeker`,
`truetypeparser`, `arthenica-smartexceptions`) which has no upstream counterpart and
tracks independently.

## Suggested order

1. Level 1 as one commit, with the Spotless/ktlint reformat split out.
2. NDK bump, checked against the 16 KB report.
3. Level 2 one dep at a time — `moko-resources` and SQLite last, since they have the
   widest blast radius.
4. Level 3 on separate branches: Kotlin first, then AGP + Gradle together, then
   compileSdk, then Voyager on its own.

## Verifying each step

```sh
./gradlew spotlessCheck
./gradlew testReleaseUnitTest
./gradlew assembleDebug -Pdisable-code-shrink
./scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

A green build is not sufficient for the Level 2/3 items — install on a device and
exercise **both** stacks (browse a manga source and an anime source, open a chapter in
the reader and an episode in the player), because almost everything here is mirrored
and a break on one side does not imply a compile failure on the other.
