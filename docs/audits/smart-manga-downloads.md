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
an image. Requests performed internally by an extension and traffic from browsing remain outside
this budget. The reader uses its own bounded foreground/background scheduling rather than download
pacing, but checks and records the same source pauses and cooldowns. Library updates and source
suggestion searches use the same source gate and pauses.
Existing extension rate limits still apply.
Cached images and already saved pages do not consume another image-fetch slot.

Batch rest replaces the shorter chapter delay. HTTP 429, HTTP 5xx and network IO failures pause the
source; other sources keep running. Retry delays follow 5, 15, 30, 60 minutes at default settings and
remain capped thereafter. The same multipliers apply to a customized initial delay. A valid
`Retry-After` (seconds or HTTP date) is always a lower bound, even when it exceeds the configured
maximum. A huge server deadline is checked again at most daily without sending an early request.

After a temporary failure the downloader's next attempt is its sole probe for that source. The reader
retains its normal concurrency once the shared error cooldown expires. Download recovery uses at least
3 seconds between operations and 30 seconds between completed chapters. Three successful chapters
restore normal pacing and reset the failure count. Disk errors and permanent HTTP failures such as
404 stay errors rather than becoming automatic retries.

An HTTP 403 pauses manga requests for the entire source until explicitly resumed from the reader
or download queue. The reader offers **Try again** when the initial chapter load hits this pause,
including when the download queue is empty. This action releases only that source's access-denied
pause and retries the requested chapter; existing challenge pauses and timed cooldowns are preserved.
The download queue does not advance to another page or chapter from that source, including when
smart pacing is disabled. The reader can still display cached pages; new reader requests are
rejected locally while the source is paused. Requests already admitted or in flight may complete.
Access denied is kept separate from a Cloudflare challenge and does not prompt WebView resolution.
Manual library updates, reopening the reader and ordinary page retries do not release this HTTP 403 pause.

An unresolved Cloudflare challenge pauses the entire source until the user resolves it. Tap the
warning notification to open that source in WebView, then resume downloads. Resuming downloads manually
releases access-denied and challenge pauses but preserves timed cooldowns. Disabling smart pacing
does not remove these safety pauses or timed cooldowns.
Automatic starts from library updates or preloading never release challenge pauses, retry permanent
errors or override a manual pause. These choices survive process recreation.

HTTP 429 and `Retry-After` observed by the reader, downloader, source suggestion searches or
coordinated library operations share the same persisted source cooldown. Suggestion searches run
through the source gate, including lazy filter initialization, instead of issuing parallel unpaced
queries. Access denial, rate limiting or a source pause stops the remaining title, author and genre
queries and automatic pagination for that suggestion pager; partial results remain available without
caching an interrupted search as complete. Manga and anime suggestion engines share this protection.
Browsing/WebView traffic and clients created independently by extensions remain outside
this protection. This is not a global per-host rate limiter. Anime retains its existing HTTP 403
classification; its coordinated requests continue sharing challenge and transient-error cooldowns.

Manga information shows a persistent warning for source-wide HTTP 429 and HTTP 403 restrictions.
A 429 includes the remaining wait and enables **Try again** only after the persisted cooldown,
including `Retry-After`, expires. The action checks the current manga's chapter list without
starting downloads or clearing cooldowns. The warning is removed after a successful source request;
another rejection updates it. A 403 warns that the IP may have been blocked and offers explicit
source resumption, preserving other restrictions. Manga detail refreshes use the same source gate.
The reader and manga information share the same messages, countdown and **Try again** button.
When access denial and rate limiting coexist, both causes are shown and retry remains disabled
until the cooldown ends. Expiry alone never releases the 403 pause: the user must confirm another
attempt. Retry authorization rechecks the persisted deadline before removing only that source's
403 pause, so an outdated screen cannot override a newer cooldown. The reader retries the requested
chapter rather than issuing the manga-information chapter-list check. Neither action explicitly resumes
the download queue; the reader keeps its existing reading and download-ahead preferences.
Source warnings also cover image and image-URL failures after the page list has loaded, including
cached page lists. Only an affected visible page opens the warning automatically; a cached page
remains readable when a background request is rejected. Dismissing an image warning returns to the
reader, and **Try again** retries that image after the same explicit source authorization. Both pager
and webtoon page retry buttons use this recovery path. A failed retry can show the warning again
without repeatedly reopening a dismissed warning for the same failure.
Existing timed pauses from older builds without a recorded HTTP status retain their generic wait
message instead of being mislabeled as rate limiting.

Short waits of up to three minutes, including the recommended batch rest, keep the foreground
worker alive so Android job batching does not delay the next chapter. Longer waits release the
foreground downloader and use persisted WorkManager work to resume. Work is
constrained by connectivity and the Wi-Fi preference, and the downloader checks the actual network
again when it starts. Android may delay execution beyond the displayed time. Manual Pause or Clear
cancels automatic resumption. Already completed image files are reused on retry.

## Reader preloading

The manga reader allows at most two page transfers per source, including at most one background
preload. Background operation starts are separated by at least one second; the selected page has
priority and can start sooner. Cached images do not wait for network admission. These limits apply
to page operations, which may issue multiple HTTP requests internally through an extension.

Only the active chapter preloads images. An adjacent chapter can prepare its page list, but does
not fetch images until selected. Changing chapters stops pending preload work for the previous
chapter. Page changes preserve already admitted transfers instead of canceling and requesting the
same image again. Failed pages require an explicit retry; binding a page in the viewer does not
retry it automatically. Closing the reader cancels its loaders and pending work.
Canceled or replaced preload plans recheck their cancellation and identity under the scheduler lock
before creating a worker. Workers also check cancellation when marking network admission, so a
deactivated chapter cannot create a new transfer through a stale planner.
The cache fast path can only complete a local load. If an image disappears after cache classification,
the worker obtains network admission before fetching it, retaining the same concurrency and pacing
limits. Waiting for this admission does not mark the transfer as started, so deactivation cancels it.

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
