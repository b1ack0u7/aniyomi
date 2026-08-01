# Video player — pending work

Follow-ups from a playback performance investigation on the anime player. Everything
here is grounded in measurements taken against the Jkanime source (episode 16 of
*Tensei shitara Slime Datta Ken 4th Season*), on both a physical device and an
emulator.

## What was already fixed

These landed and are verified; they are context for what follows, not open work.

- **Failover when mpv cannot play a stream.** A stream mpv fails to decode raises
  `eof-reached` exactly like a finished episode. The player now tells them apart by
  whether playback ever advanced, and falls over to the next candidate instead of
  sitting on a dead file (or, with autoplay on, skipping the episode as if watched).
- **Stall watchdog.** Two triggers, one configurable threshold
  (*Settings → Player → Hosters*, default 20 s): a single continuous stall, and
  cumulative stalling on the same video. The second one exists because a server can
  be unusable without ever hanging outright — one measured run stalled nine times in
  76 s, none longer than 20 s, while playback crawled at 40% of real time.
- **Loading overlay.** Translucent panel that names the server being loaded and, once
  waiting drags on, counts down to the switch and names the next server.
- **Duplicate `sortVideos()`.** The legacy `getVideoList()` path sorted, then
  `getVideos()` sorted the already-sorted list again. With a comparator that is not a
  strict total order the second pass reordered the first one's result, so the quality
  that actually played was not the one the sort intended.
- **`format=yuv420p` vs hardware decoding.** Both defaulted to on and conflict:
  mediacodec surfaces cannot be copied to CPU memory, so mpv logged
  `cannot copy surface of this format to CPU memory` and dropped the filter. Where the
  copy *does* succeed it costs a GPU→CPU download per frame. The filter is now only
  applied when decoding in software.

## The problem that remains

Playback itself is not slow. On a healthy server the player is flawless: 24.00 fps
held exactly, zero dropped frames, `avsync=0.00`, zero stalls over three minutes, and
a demuxer cache that grows to several minutes of readahead.

What hurts is **bandwidth against bitrate**:

| Measured | Value |
| --- | --- |
| Throughput from a bad server | 126–258 KiB/s (≈1–2 Mbps) |
| Stream bitrate (1080p H.264) | peaks of 1,508–1,924 kbps |

Download sits at roughly the bitrate with no margin, so any dip drains the cache. And
mpv's `cache-pause-wait` defaults to **1 second**: as soon as one second of data
exists, playback resumes, burns it in two, and stalls again. That is the
play-2s / wait / play-2s cycle.

Note the readahead is *not* the constraint — a good server reaches 138 s of cached
duration. The constraint is purely throughput.

Separately, and outside this repo: `getVideoList()` on Jkanime takes **30–49 s**
because the extension resolves all ~15 servers sequentially (28 HTTP requests, plus a
WebView running mega.nz JavaScript that crashed the Chromium renderer on every run).
That is ~85% of time-to-first-frame and nothing on the app side can shorten it. The
fix is for the extension to adopt the Hoster API (`getHosterList()` with `lazy`
hosters), which loads servers in parallel and resolves only what is needed.

## Proposed work, cheapest first

### 1. Buffer a cushion before resuming

Directly targets the stutter cycle. Two mpv options:

```
cache-pause-wait=10      # instead of the default 1
cache-pause-initial=yes  # don't start until that cushion exists
```

This does not make the download faster; it converts many microstalls into one honest
wait followed by smooth playback, which is far more tolerable. The loading overlay is
already in place to explain the wait ("buffering N seconds") instead of reappearing
unannounced.

Worth exposing the wait length as a preference next to the stall timeout.

### 2. Step down quality instead of changing server

The highest impact-to-effort item, and currently not done at all.

On a stall-driven failure the player moves to the next entry in the list, which is
usually **another 1080p**. If 1.5 Mbps cannot be sustained at 200 KiB/s, another 1080p
will fail the same way. Lower variants are almost always present, and the titles even
carry the advertised speed:

```
VidHide:1080p — 1.60 MB/s
VidHide:720p  — 915.48 KB/s
VidHide:480p  — 468.05 KB/s
```

Since throughput and bitrate are both already measured at runtime, the rule follows:
when measured throughput does not cover observed bitrate, **degrade resolution before
switching host**. Less disruptive (same connection, same CDN, already known reachable)
and it addresses the actual cause.

### 3. Diversify by host, not by list position

`selectBestVideo` takes the next index. One measured list contained **VidHide six
times** (1080p ×2, 720p ×2, 480p ×2, duplicates included). If that CDN is the problem,
six consecutive attempts are burned before another host is tried.

Group candidates by host and rotate across hosts before retrying within one.

### 4. Probe before committing

Before abandoning the current server, issue a short `Range` request to the candidate
and measure its throughput. Only switch if it measurably beats the current one. This
prevents the worst case — leaving a mediocre server for a worse one — which is how a
single failure turns into a cycle.

### 5. Hysteresis and a switch budget

Anti-thrash:

- Cap automatic switches per episode (3 or so), then stop and let the user choose.
- Raise the threshold after each switch so the player does not ping-pong between two
  equally bad servers.
- When every candidate is exhausted, settle on the best one seen so far rather than
  continuing to rotate.

### 6. Let ffmpeg handle ABR (needs extension cooperation)

The extension fetches `master.m3u8` and then hands the app a specific variant
(`index-f3-v1-a1.m3u8`). That discards the adaptive bitrate switching ffmpeg's HLS
demuxer would do on its own given the master playlist.

Passing the master would adapt to available bandwidth for free. It is not an app-side
change: extensions split the variants precisely to expose them as quality options, so
this is a conversation for the extensions repo.

## Suggested order

1 and 5 first — cheap, low risk, and together they remove the symptom. Then 2, which
addresses the root cause and reuses metrics the player already has. 3 and 4 are
refinements for when failover sees real use. 6 is not ours to make.

## Smaller loose ends

- `HosterLoader.getResolvedVideo` swallows the exception from `source.resolveVideo`
  and returns null. A failed resolve is indistinguishable from "nothing to resolve";
  a `logcat` line there would make quality-fallback reports diagnosable.
- `Preference.PreferenceItem.SliderPreference` declares an `enabled` field that
  `PreferenceItem.kt` never forwards to `BaseSliderItem`, so sliders stay interactive
  when they are meant to be disabled.
- `Debanding.CPU` and `useYUV420P` both write mpv's `vf` option, so the second
  overwrites the first — enabling CPU debanding together with yuv420p silently drops
  the debanding.
