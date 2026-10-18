# Smart manga downloads

Settings → Downloads → Smart manga downloads controls manga download pacing. It is enabled by default.
Use **Restore recommended settings** in that section to enable smart downloads and reset all seven
settings to their defaults. Existing source cooldowns and Cloudflare pauses are preserved.

Anime source discovery and library updates share source coordination, as described below.
FFmpeg and external downloaders retain their existing media-transfer behavior.

The budget belongs to a source ID, across all queued mangas from that source. Reordering, starting
another manga, or restarting the app does not reset its batch counter or cooldown. Different source
IDs have independent budgets, including sources packaged in the same extension APK. This does not
combine sources by extension repository or shared CDN hostname.

| Setting | Default | Allowed range |
| --- | --- | --- |
| Minimum interval between source operations | 1,500 ms | 500–10,000 ms |
| Delay after a completed chapter | 10 s | 1–120 s |
| Chapters per batch | 5 | 1–20 |
| Rest after a completed batch | 120 s | 30–1,800 s |
| Initial retry delay | 300 s | 60–1,800 s |
| Maximum retry delay | 3,600 s | 300–86,400 s, at least the initial delay |

The segmented **Download profile** control applies these presets. All values remain editable;
manual values that do not match a preset show **Custom settings** with no selected segment.
Selecting a profile replaces all six numerical settings and retains existing source cooldowns.
The existing restore action enables smart downloads and selects the default profile again.

| Profile | Request interval | Chapter pause | Batch size | Batch rest | Initial retry | Maximum retry |
| --- | --- | --- | --- | --- | --- | --- |
| Fast | 750 ms | 5 s | 8 | 60 s | 5 min | 60 min |
| Default | 1,500 ms | 10 s | 5 | 120 s | 5 min | 60 min |
| Slow | 3,000 ms | 30 s | 3 | 300 s | 15 min | 120 min |

Fast increases traffic and may increase the risk of source blocking. Slow is intended for overnight
downloads or when there is no rush. Neither profile changes the one-operation concurrency limit,
server Retry-After handling or manual Cloudflare challenge resolution. The profiles are stored using
the existing primitive preferences and remain compatible with app-settings backup/restore.

Only one chapter and one page operation execute at a time per source in smart mode. The request
interval measures time between operation starts; a slower operation does not incur another full
interval afterward. Operations include fetching the page list, resolving an image URL and fetching
an image. Requests performed internally by an extension and traffic from browsing or the reader
remain outside this budget. Library updates use the same source gate, cooldowns and challenge pauses.
Existing extension rate limits still apply.
Cached images and already saved pages do not consume another image-fetch slot.

Batch rest replaces the shorter chapter delay. HTTP 429, HTTP 5xx and network IO failures pause the
source; other sources keep running. Retry delays follow 5, 15, 30, 60 minutes at default settings and
remain capped thereafter. The same multipliers apply to a customized initial delay. A valid
`Retry-After` (seconds or HTTP date) is always a lower bound, even when it exceeds the configured
maximum. A huge server deadline is checked again at most daily without sending an early request.

After a temporary failure the next attempt is the sole probe for that source. Recovery uses at least
3 seconds between operations and 30 seconds between completed chapters. Three successful chapters
restore normal pacing and reset the failure count. Disk errors and permanent HTTP failures such as
404 stay errors rather than becoming automatic retries.

An unresolved Cloudflare challenge pauses the entire source until the user resolves it. Tap the
warning notification to open that source in WebView, then resume downloads. A normal 403 is not
assumed to be Cloudflare. Resuming manually releases challenge pauses but preserves timed cooldowns.
Automatic starts from library updates or preloading never release challenge pauses, retry permanent
errors or override a manual pause. These choices survive process recreation.

Short waits of up to three minutes, including the recommended batch rest, keep the foreground
worker alive so Android job batching does not delay the next chapter. Longer waits release the
foreground downloader and use persisted WorkManager work to resume. Work is
constrained by connectivity and the Wi-Fi preference, and the downloader checks the actual network
again when it starts. Android may delay execution beyond the displayed time. Manual Pause or Clear
cancels automatic resumption. Already completed image files are reused on retry.

## Library update retries

Manga and anime library updates persist failed titles and allow at most two additional attempts
after a transient DNS, connection, timeout, HTTP 429 or HTTP 5xx failure. Retries update only titles
whose deadline has arrived, require connectivity and do not rerun successful titles. At default
settings the first waits at least five minutes and the next at least fifteen minutes. Server
`Retry-After` and an existing source cooldown can extend either wait. Android may start work later.

Library requests and manga download operations share a per-source lock, minimum operation interval
and persisted cooldown. Anime library requests and download source discovery have an independent
gate with the same conservative default timing. Manga download profiles do not change anime timing.
Successful metadata requests do not advance the manga download batch counter or clear download
failure recovery. Pauses imposed by one operation cannot be shortened by another operation.

A challenge stops automatic retries for that source. Resolve it in WebView, then explicitly resume
downloads or manually refresh the library to permit another attempt; a manual action still preserves
timed cooldowns. Other HTTP 4xx responses, certificate failures, parsing errors and local storage
errors are reported without automatic retries. Stopping a library update cancels its pending retries.

## Validation

```sh
./gradlew :domain:testDebugUnitTest :core:common:testDebugUnitTest :app:testDebugUnitTest spotlessCheck
```

Device checks should cover two mangas from the same source, a second independent source, a 429
with Retry-After during both page-list and image fetching, connection loss halfway through an image,
a challenge requiring WebView, manual pause during a cooldown, process recreation, and a reboot
while a delayed resume is scheduled. No pacing values can guarantee a site will never block a client.

### Device validation — 2026-10-17

Validated the debug APK on the connected Android x86_64 emulator using
**Yeokdaegeup Yeongji Seolgyesa**, MangaFire (EN), the library entry with 457 chapters.
Seven chapters completed with 637 images. All seven CBZ files passed ZIP integrity checks and
contained ComicInfo.xml; the download queue finished empty with no recorded source failures.

Verified changing the interval to 2,000 ms and restoring 1,500 ms, a manual pause at 95/184 images
without progress during the pause, and continuation from saved images. The default batch counter
reached five and set a 120-second deadline. A customized one-chapter batch also waited 120 seconds
and automatically started the next chapter while the app was in the background. Restoring the
recommended settings returned the batch size to five before the test ended.

Closing the background process preserved the queue, but exposed Android's background-job batching.
Short rests now retain their foreground worker. Following process loss, the persisted fallback still
depends on Android scheduling and may run later than the displayed deadline. No Cloudflare challenge
occurred in this real-source run; challenge and Retry-After handling are covered by unit tests.
