# 16 KB page size compatibility

Android 15+ devices can boot with 16 KB memory pages. An app is compatible only if
every 64-bit native library it ships is aligned for it. Two independent conditions
have to hold:

1. **APK layout** — the `lib/arm64-v8a/*.so` and `lib/x86_64/*.so` entries must be
   `STORED` (uncompressed) and start on a 16 KB boundary inside the zip.
   AGP 8.5.1+ does this automatically; nothing to configure here.
2. **ELF layout** — every `PT_LOAD` segment must have `p_align >= 16384`. This is
   baked in when the library is linked (`-Wl,-z,max-page-size=16384`, NDK r27+),
   so for a prebuilt dependency it can only be fixed by upgrading or rebuilding
   the artifact that ships it.

32-bit ABIs (`armeabi-v7a`, `x86`) are irrelevant — 16 KB pages are 64-bit only and
the NDK deliberately keeps 4 KB alignment there.

## Verifying

```sh
./gradlew :app:assembleDebug
./scripts/check-16kb-alignment.sh app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Exits non-zero and names every offending library.

## Current status

Fixed by dependency upgrades (verified in a real build):

| Library | Change |
| --- | --- |
| `libquickjs.so` | `app.cash.quickjs:quickjs-android:0.9.2` → `com.github.zhanghai.quickjs-java:quickjs-android:547f5b1597`. The Cash App artifact is abandoned; the fork is a drop-in — same `app.cash.quickjs` package, identical public API, so no source changes. |
| `libsqlite3x.so` | `com.github.requery:sqlite-android` 3.45.0 → 3.49.0 |
| `libarchive-jni.so` | `me.zhanghai.android.libarchive:library` 1.1.4 → 1.1.6 (class list unchanged) |

Everything else the app ships — mpv, the FFmpeg `libav*` family, conscrypt, libxml2,
`libc++_shared`, `libandroidx.graphics.path` — was already 16 KB aligned.

## Still blocked

Three libraries have no usable 16 KB build available. Each needs work outside this repo.

### `libimagedecoder.so` — blocked on the AGP/Gradle version

`com.github.mihonapp:image-decoder:e03b81e18a` **is** 16 KB aligned, but its AAR
declares `minCompileSdk=37`, and this project is on compileSdk 36 / AGP 8.9.1 /
Gradle 8.13. Swapping it in fails at configuration time:

```
Dependency 'com.github.mihonapp:image-decoder:e03b81e18a' requires libraries and
applications that depend on it to compile against version 37 or later of the
Android APIs. :app is currently compiled against android-36.
```

Every JitPack build of that fork carrying the 16 KB fix comes from the same
"Modernize build config" commit, so there is no lower-SDK variant to pin to.
Unblocking it needs either an AGP 9.x / compileSdk 37 migration, or a fork of
mihonapp/image-decoder pinned to compileSdk 36.

Meanwhile the app stays on `com.github.tachiyomiorg:image-decoder:41c059e540` (4 KB).

### `libffmpegkit.so`, `libffmpegkit_abidetect.so` — need an upstream rebuild

`com.github.jmir1:ffmpeg-kit:1.18` (the `aniyomi` branch of jmir1/ffmpeg-kit) is
Aniyomi-specific — it links against the FFmpeg shipped by `aniyomi-mpv-lib`, so a
generic 16 KB ffmpeg-kit fork is not a drop-in replacement. `1.18` is the newest
JitPack build and none are 16 KB aligned.

Fix: rebuild that branch with NDK r27+ and `-Wl,-z,max-page-size=16384`. Only these
two small glue libraries are affected; the heavy `libav*.so` come from
`aniyomi-mpv-lib` and are already aligned.

### `libtorrserver.so` — needs an upstream rebuild

`io.github.secozzi:torrserver:0.1.0` (gitlab.com/Secozzi/torrserver) is the only
published version. It is a Go/gomobile build, so the alignment has to come from the
Go toolchain / `-extldflags=-Wl,-z,max-page-size=16384`.

(`liblibrary.so` from the same artifact is 4 KB only on `armeabi-v7a`/`x86`, which
does not matter.)

## Reading the on-device dialog

The "Android App Compatibility" dialog on Android 16 QPR2+ is misleading: under
"The following libraries are not 16 KB aligned" it lists **every** 64-bit native
library in the APK, including the ones that passed.

- `… : LOAD segment not aligned` — genuinely broken.
- `… : Unknown error` — **the library is fine.** This is the fallthrough branch for
  error code 0 ("no problem found"). `checkApkAlignment()` in
  `core/jni/com_android_internal_content_NativeLibraryHelper.cpp` pushes every
  library into the report unconditionally, and `getErrorMessageForLib()` in
  `services/core/java/com/android/server/pm/PackageSetting.java` has no case for 0,
  so it falls through to `page_size_compat_unknown`.

Note also that the platform check flags a segment only when `p_align` is exactly
`0x1000`; `scripts/check-16kb-alignment.sh` is stricter and requires `>= 16384`.

The check runs at install/scan time only, and flags are recomputed on upgrade — so
reinstall after a change rather than trusting a stale dialog.
