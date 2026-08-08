# Changelog

All notable changes to this project will be documented in this file.

The format is a modified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
- `Added` - for new features.
- `Changed ` - for changes in existing functionality.
- `Improved` - for enhancement or optimization in existing functionality.
- `Removed` - for now removed features.
- `Fixed` - for any bug fixes.
- `Other` - for technical stuff.

## Unreleased
### Added

- Show a "Similar Titles" row on anime and manga entry screens, combining recommendations from AniList, MyAnimeList and MangaUpdates with related titles found in the entry's own source. Cards from an installed source open the entry directly, the rest fall back to a global search, and "See all" opens the full list. The row only fetches once you scroll to it, and can be turned off under Settings → Browse ([@b1ack0u7](https://github.com/b1ack0u7))
- Collapse the chapter and episode lists to the first 10 entries, with a button to show the rest. Settings → Appearance → Title screens has a toggle per side to always show the full list ([@b1ack0u7](https://github.com/b1ack0u7))

### Improved

- Complete the Spanish translation, filling in 46 strings and 10 plurals that had no `es` entry — the torrent and TorrServer settings, the server-stall player messages, and the similar-titles and collapsible-list options ([@b1ack0u7](https://github.com/b1ack0u7))
- Stop the download queue from burning battery while it sits there. Every queued chapter was waking up twenty times a second to check whether its page list had arrived yet, so a queue of 150 chapters kept thousands of wake-ups per second going — around a fifth of a CPU core — for as long as an entry, updates or download queue screen was open, even with downloads paused. Each chapter now waits to be told instead of asking ([@b1ack0u7](https://github.com/b1ack0u7))
- Speed up chapter downloads by scanning the chapter folder once instead of twice per page. When downloads live on storage picked through the system file picker, every one of those scans is a separate lookup, so a 54-page chapter spent about 1.8 seconds doing nothing but listing files — 162 lookups where one is enough. In testing that chapter went from 8.3 to 6.1 seconds, and the saving grows with the page count ([@b1ack0u7](https://github.com/b1ack0u7))
- Speed up queueing chapters and episodes for download. Each one was walked down the download folder tree on its own to check whether it had already been downloaded; the folder is now listed once and checked in memory. Queueing 193 chapters went from 386 ms to 11 ms ([@b1ack0u7](https://github.com/b1ack0u7))
- Stop rebuilding the download notification twenty times a second for the whole length of an episode download, and once per page on the manga side. It now redraws only when progress actually moves, and at most five times a second ([@b1ack0u7](https://github.com/b1ack0u7))
- Restore the download queue on startup with one database query per entry instead of one per queued chapter or episode, and move the manga side off the main thread, where it had been blocking startup in proportion to the queue size ([@b1ack0u7](https://github.com/b1ack0u7))
- Ask anime sources for details and episodes in a single round instead of two requests one after the other, the way the manga side already does. With "Automatically refresh metadata" on, each anime spent 307 ms fetching details and only then 541 ms fetching episodes; sources can now answer both at once, and those that don't have the two fetched in parallel ([@b1ack0u7](https://github.com/b1ack0u7))
- Tell you how a library update went. The refresh spinner used to be faked — it appeared for exactly one second and then vanished regardless of what the update was doing — so a run that finished without finding anything looked identical to one that never started. A progress bar now sits at the top of both library tabs and both updates tabs for as long as the run lasts, counting the entries checked and naming the one being fetched; on the updates screens it takes the place of the "Library last updated" line, and on the library screens it expands and collapses rather than shoving the grid out of the way. When the run ends you get a message: how many new chapters or episodes turned up, that there were none, or how many entries failed. The pull-to-refresh spinner no longer stays up for the whole run, since the bar says the same thing, and it still follows your finger while you drag. It all works even with notifications turned off, and the new messages ship translated to Spanish ([@b1ack0u7](https://github.com/b1ack0u7))
- Stop the anime entry screen from asking its source for details and episodes at the same time when it opens or is pulled to refresh. Sources are allowed to turn down two overlapping requests for the same entry, which is why the manga side had already merged them; the anime side now asks once as well, and starts the torrent server once instead of twice ([@b1ack0u7](https://github.com/b1ack0u7))
- Stop rewriting chapters and episodes that did not change during a library update. In testing, 660 of 854 chapters were written back on every single refresh; that is now none. Each entry also writes its row once instead of twice, and skips one full read of its chapter or episode list when nothing changed ([@b1ack0u7](https://github.com/b1ack0u7))
- Match chapters and episodes against the database by lookup instead of scanning the whole list for each one, so syncing an entry no longer gets quadratically slower as its chapter count grows ([@b1ack0u7](https://github.com/b1ack0u7))
- Stop the library update from announcing every entry twice to the notification system, which outran its rate limit whenever a source answered quickly ([@b1ack0u7](https://github.com/b1ack0u7))

### Fixed

- Fix chapter and episode upload dates being replaced by the time of the last library refresh. Sources that don't publish a real upload date report the current time instead, and that was written straight over the recorded date, so a library with 660 such chapters lost all of their dates on every update — taking the release-time prediction with them. A date is now only ever filled in when there isn't one ([@b1ack0u7](https://github.com/b1ack0u7))
- Fix new episodes never being queued for download after an anime library update. The episodes to download were worked out and the downloader was told to start, but nothing was ever handed to it ([@b1ack0u7](https://github.com/b1ack0u7))
- Stop a scheduled anime library update from starting on top of a manual one that is still running, which the manga side already avoided ([@b1ack0u7](https://github.com/b1ack0u7))
- Fix "Library last updated" reporting a time while the update was still running. It was stamped when the job started rather than when it finished ([@b1ack0u7](https://github.com/b1ack0u7))
- Fix the status bar and navigation bar being see-through in the reader, so the manga page showed through behind the clock and the gesture bar while the reader controls were on screen. The reader asked the window to tint them to match the toolbar, but that has been ignored since Android 15; the toolbar and the bottom bar now paint their own background behind them ([@b1ack0u7](https://github.com/b1ack0u7))
- Fix a band of empty space, as tall as the status bar, between the Anime/Manga tabs and the top of the updates list. The list reserved room for the status bar a second time, even though the toolbar above it had already covered it ([@b1ack0u7](https://github.com/b1ack0u7))

- Fix the scrollbar jumping around, and sometimes backwards, while scrolling the entry screens. Its position was extrapolated from the heights of whatever happened to be on screen, which never held up on a screen that mixes a cover, a description and list rows; it is now measured in list items. Ported from Mihon ([@anirudhn](https://github.com/anirudhn)) ([mihon#2304](https://github.com/mihonapp/mihon/pull/2304)) and carried over to the grid variant the anime screen uses, which upstream had left for later ([@b1ack0u7](https://github.com/b1ack0u7))
- Fix the scrollbar never appearing when the system animator duration scale is turned off. Ported from Mihon ([@anirudhn](https://github.com/anirudhn)) ([mihon#2398](https://github.com/mihonapp/mihon/pull/2398))
- Translate the subtitle "Palette" label and fix the word order of the tracker error message in Spanish ([@b1ack0u7](https://github.com/b1ack0u7))
- Fix the app closing itself when opening a recently updated manga source, such as Comix. Extensions take kotlinx-coroutines from the app instead of bundling their own, and the manga extension repository moved to 1.11.0, where one of its functions is compiled under a new name that the app — still on 1.10.1 — did not have. The app is now on 1.11.0 as well, which keeps the old name too, so extensions built against either version work ([@b1ack0u7](https://github.com/b1ack0u7))
- Show an error in the source listing instead of closing the app when an extension is built against a newer library than the app ships, on both the anime and manga sides ([@b1ack0u7](https://github.com/b1ack0u7))
- Fix an episode being marked as downloaded when the download had left nothing but an incomplete temporary file. The check that was supposed to ignore those files compared the file extension against a value it could never equal, so it never ignored anything ([@b1ack0u7](https://github.com/b1ack0u7))
- Fix the download worker never ending on its own when the connection drops. It waited on a loop placed after code that never returns, so the worker stayed alive until something else cancelled it; it now stops cleanly on both the anime and manga sides ([@b1ack0u7](https://github.com/b1ack0u7))

### Other

- Remove an unused second copy of the episode download finalisation logic, which had drifted from the one actually in use ([@b1ack0u7](https://github.com/b1ack0u7))

## [v0.20.1] - 2026-08-01
### Fixed

- Fix bottom sheets and the cover/image viewers running past the bottom of the screen under the navigation bar, now that the system no longer lays dialog windows out inside the system bars ([@b1ack0u7](https://github.com/b1ack0u7))
- Fix the keyboard covering the input in the player dialogs, including the sleep timer's time picker ([@b1ack0u7](https://github.com/b1ack0u7))

## [v0.20.0] - 2026-08-01
### Added

- Added a description for the horizontal seek gesture setting ([@kenkoro](https://github.com/kenkoro)) ([#2224](https://github.com/aniyomiorg/aniyomi/pull/2224))
- Added an http server for use in extensions ([@Secozzi](https://github.com/Secozzi)) ([#2348](https://github.com/aniyomiorg/aniyomi/pull/2348))
- Added support for thumbnail preview when seeking ([@Secozzi](https://github.com/Secozzi)) ([#2343](https://github.com/aniyomiorg/aniyomi/pull/2343))
- Add torrent streaming support ([@Secozzi](https://github.com/Secozzi)) ([#2346](https://github.com/aniyomiorg/aniyomi/pull/2346))
- Automatically switch to the next server when playback stalls, with a configurable wait time in the player settings ([@b1ack0u7](https://github.com/b1ack0u7))
- Show the current server and the countdown to the next one in the player loading overlay ([@b1ack0u7](https://github.com/b1ack0u7))
- Support extension repositories in the index_v2 format, falling back to the legacy format ([@b1ack0u7](https://github.com/b1ack0u7))
- Support extensions built against extensions-lib 1.6 ([@b1ack0u7](https://github.com/b1ack0u7))

### Changed

- The app now uses its own application ID, so it installs next to official Aniyomi instead of replacing it. The two do not share data — move over by exporting a backup from one and restoring it in the other ([@b1ack0u7](https://github.com/b1ack0u7))
- Update checks now look at this fork's releases instead of upstream's ([@b1ack0u7](https://github.com/b1ack0u7))
- Version numbers now use three components (major.minor.patch) instead of four ([@b1ack0u7](https://github.com/b1ack0u7))

### Improved

- Fetch manga details and chapters in a single request on sources that support it, so refreshing a title is faster and hits the source less ([@b1ack0u7](https://github.com/b1ack0u7))
- Upgrade OkHttp and several native libraries towards 16 KB page size support on Android 15+; three libraries are still pending ([@b1ack0u7](https://github.com/b1ack0u7))

### Fixed

- Swapped keyEvent listeners for left and right keyboard arrow keys as they were swapped in the code causing the opposite of the desired behavior([@alphastark](https://github.com/alphastark)) ([#2219](https://github.com/aniyomiorg/aniyomi/pull/2219))
- Fix some malformed translated strings that made the player quit when Aniskip was enabled ([@686udjie](https://github.com/686udjie)) ([#2217](https://github.com/aniyomiorg/aniyomi/pull/2217))
- Apply the YUV420P option only when decoding in software, where it actually works; with hardware decoding it was dropped by the player after paying a copy for every frame ([@b1ack0u7](https://github.com/b1ack0u7))
- Fix video quality order coming out wrong on sources that use the legacy video list, which was being sorted twice ([@b1ack0u7](https://github.com/b1ack0u7))
- Stop the update check from offering an older release as an update, or failing outright when the installed version has more components than the release tag ([@b1ack0u7](https://github.com/b1ack0u7))

## [v0.18.1.2] - 2025-10-28
### Fixed

- Fix Hosters feature detection (again) ([@hollowshiroyuki](https://github.com/hollowshiroyuki)) ([#2216](https://github.com/aniyomiorg/aniyomi/pull/2216))

## [v0.18.1.1] - 2025-10-26
### Fixed

- Fix source Seasons/Hosters feature detection ([@hollowshiroyuki](https://github.com/hollowshiroyuki)) ([#2195](https://github.com/aniyomiorg/aniyomi/pull/2195))
- Fix shared download cache messing up downloaded episodes detection ([@choppeh](https://github.com/choppeh)) ([#2184](https://github.com/aniyomiorg/aniyomi/pull/2184))
- Fix Shikimori anime tracking ([@danya140](https://github.com/danya140)) ([#2205](https://github.com/aniyomiorg/aniyomi/pull/2205))

### Improved

- Make volume gesture the same sensitivity as brightness ([@jmir1](https://github.com/jmir1))

## [v0.18.1.0] - 2025-10-02
### Fixed

- Fix list view resetting scroll upon exiting child ([@quickdesh](https://github.com/quickdesh)) ([#1982](https://github.com/aniyomiorg/aniyomi/pull/1982))
- Fix episode number parsing ([@Secozzi](https://github.com/Secozzi)) ([#2096](https://github.com/aniyomiorg/aniyomi/pull/2096))
- Fix tracking menu not opening on add to library ([@Secozzi](https://github.com/Secozzi)) ([#2098](https://github.com/aniyomiorg/aniyomi/pull/2098))
- Fix stop/continue anime download button ([@Secozzi](https://github.com/Secozzi)) ([#2099](https://github.com/aniyomiorg/aniyomi/pull/2099))
- Fix creating/restoring backups between mihon and aniyomi ([@Secozzi](https://github.com/Secozzi)) ([#2117](https://github.com/aniyomiorg/aniyomi/pull/2117))

### Added

- Add support for new parameters from ext lib 16 ([@quickdesh](https://github.com/quickdesh)) ([#1982](https://github.com/aniyomiorg/aniyomi/pull/1982))
- Add player settings to the main settings screen ([@jmir1](https://github.com/jmir1)) ([#2081](https://github.com/aniyomiorg/aniyomi/pull/2081))
- Add seasons support ([@Secozzi](https://github.com/Secozzi)) ([#2095](https://github.com/aniyomiorg/aniyomi/pull/2095))

## [v0.18.0.1] - 2025-07-06
### Fixed

- Fix crash on migration ([@Secozzi](https://github.com/Secozzi)) ([#2079](https://github.com/aniyomiorg/aniyomi/pull/2079))

## [v0.18.0.0] - 2025-07-05
### Added

- Set mpv's media-title property ([@Secozzi](https://github.com/Secozzi)) ([#1672](https://github.com/aniyomiorg/aniyomi/pull/1672))
- Add mpvKt to external players ([@Secozzi](https://github.com/Secozzi)) ([#1674](https://github.com/aniyomiorg/aniyomi/pull/1674))
- Add video filters ([@abdallahmehiz](https://github.com/abdallahmehiz)) ([#1698](https://github.com/aniyomiorg/aniyomi/pull/1698))
- Show hours and minutes in relative time strings ([@jmir1](https://github.com/jmir1)) ([`1f3be7b`](https://github.com/aniyomiorg/aniyomi/commit/1f3be7b523136039b3b60213f2cee7959a9367d7))
  - Fix some issues with relative date calculations ([@jmir1](https://github.com/jmir1)) ([`03e1ecd`](https://github.com/aniyomiorg/aniyomi/commit/03e1ecd75edd2ea15dc8732ffeab32c6af26b202))
- Add better auto sub select ([@Secozzi](https://github.com/Secozzi)) ([#1706](https://github.com/aniyomiorg/aniyomi/pull/1706))
- Copy the file location when using ext downloader ([@quickdesh](https://github.com/quickdesh)) ([#1758](https://github.com/aniyomiorg/aniyomi/pull/1758))
- Replace player with mpvKt ([@Secozzi](https://github.com/Secozzi)) ([#1834](https://github.com/aniyomiorg/aniyomi/pull/1834), [#1855](https://github.com/aniyomiorg/aniyomi/pull/1855), [#1859](https://github.com/aniyomiorg/aniyomi/pull/1859), [#1860](https://github.com/aniyomiorg/aniyomi/pull/1860))
  - Move player preferences to separate section ([@Secozzi](https://github.com/Secozzi)) ([#1819](https://github.com/aniyomiorg/aniyomi/pull/1819))
- Implement video hosters ([@Secozzi](https://github.com/Secozzi)) ([#1892](https://github.com/aniyomiorg/aniyomi/pull/1892))
- Add size slider for the "List Display" Mode ([@MavikBow](https://github.com/MavikBow)) ([#1906](https://github.com/aniyomiorg/aniyomi/pull/1906))
  - Make the default list a set size and make browse list scale ([@MavikBow](https://github.com/MavikBow)) ([#1914](https://github.com/aniyomiorg/aniyomi/pull/1914))
- Allow negative brightness values (dimming) ([@jmir1](https://github.com/jmir1)) ([#1915](https://github.com/aniyomiorg/aniyomi/pull/1915))
- Add new lua functions for custom buttons ([@Secozzi](https://github.com/Secozzi)) ([#1980](https://github.com/aniyomiorg/aniyomi/pull/1980))
- Use timestamps provided by extensions ([@Secozzi](https://github.com/Secozzi)) ([#1983](https://github.com/aniyomiorg/aniyomi/pull/1983))
- Add titles to player sheets + consistency with More sheet ([@quickdesh](https://github.com/quickdesh)) ([#2015](https://github.com/aniyomiorg/aniyomi/pull/2015))
- Add script & script-opts editor to player settings ([@Secozzi](https://github.com/Secozzi)) ([#2019](https://github.com/aniyomiorg/aniyomi/pull/2019))

### Improved

- Show "Now" instead of "0 minutes ago" ([@Secozzi](https://github.com/Secozzi)) ([#1715](https://github.com/aniyomiorg/aniyomi/pull/1715))
- Add headers when using 1dm as external player ([@Secozzi](https://github.com/Secozzi)) ([#2032](https://github.com/aniyomiorg/aniyomi/pull/2032))

### Fixed

- Fix enhanced tracking for jellyfin ([@Secozzi](https://github.com/Secozzi)) ([#1656](https://github.com/aniyomiorg/aniyomi/pull/1656), [#1658](https://github.com/aniyomiorg/aniyomi/pull/1658))
- Use different status strings for anime trackers ([@jmir1](https://github.com/jmir1)) ([`74b32a3`](https://github.com/aniyomiorg/aniyomi/commit/74b32a3a0b323ed2f6f7929e131dcb4901e7bf9b))
- Fix Shikimori tracking for anime ([@jmir1](https://github.com/jmir1)) ([`58817c7`](https://github.com/aniyomiorg/aniyomi/commit/58817c724e2808072ff273329cee261d12084927))
- Group updates by date and not time ([@jmir1](https://github.com/jmir1)) ([`c83ebf3`](https://github.com/aniyomiorg/aniyomi/commit/c83ebf322f48d41ca1ad0105262160ecb7cde991))
- Fix airing time not showing ([@Secozzi](https://github.com/Secozzi)) ([#1720](https://github.com/aniyomiorg/aniyomi/pull/1720))
- Don't invalidate anime downloads on startup ([@Secozzi](https://github.com/Secozzi)) ([#1753](https://github.com/aniyomiorg/aniyomi/pull/1753))
- Fix hidden categories getting reset after delete/reorder ([@cuong-tran](https://github.com/cuong-tran)) ([#1780](https://github.com/aniyomiorg/aniyomi/pull/1780))
- Fix episode progress not being saved and duplicate tracks ([@perokhe](https://github.com/perokhe)) ([#1784](https://github.com/aniyomiorg/aniyomi/pull/1784), [#1785](https://github.com/aniyomiorg/aniyomi/pull/1785))
- Fix subtitle select not matching two letter language codes ([@Secozzi](https://github.com/Secozzi)) ([#1805](https://github.com/aniyomiorg/aniyomi/pull/1805))
- Fix potential intent extra npe ([@quickdesh](https://github.com/quickdesh)) ([#1816](https://github.com/aniyomiorg/aniyomi/pull/1816))
- Fix history date header duplication ([@quickdesh](https://github.com/quickdesh)) ([#1817](https://github.com/aniyomiorg/aniyomi/pull/1817))
- Fix migrations not getting context correctly ([@Secozzi](https://github.com/Secozzi)) ([#1820](https://github.com/aniyomiorg/aniyomi/pull/1820))
- Fix various issues due to replacing the player with mpvKt
  - Fix gesture seeking not seeking to start and end ([@perokhe](https://github.com/perokhe)) ([#1865](https://github.com/aniyomiorg/aniyomi/pull/1865))
  - Fix crash when opening player settings in tablet ui ([@Secozzi](https://github.com/Secozzi)) ([#1868](https://github.com/aniyomiorg/aniyomi/pull/1868))
  - Fix episode list in player not respecting filters & crash when exiting while stuff is loading ([@Secozzi](https://github.com/Secozzi)) ([#1869](https://github.com/aniyomiorg/aniyomi/pull/1869))
  - Fix episode being marked as seen at start ([@perokhe](https://github.com/perokhe)) ([#1871](https://github.com/aniyomiorg/aniyomi/pull/1871))
  - Fix player not being paused when loading tracks after changing quality ([@Secozzi](https://github.com/Secozzi)) ([#1878](https://github.com/aniyomiorg/aniyomi/pull/1878))
  - Fix lag when toggling player ui ([@Secozzi](https://github.com/Secozzi)) ([#1887](https://github.com/aniyomiorg/aniyomi/pull/1887))
  - Fix audio selection not working on external audio tracks ([@Secozzi](https://github.com/Secozzi)) ([#1901](https://github.com/aniyomiorg/aniyomi/pull/1901))
  - Reset "hide player controls time" when pressing custom button ([@Secozzi](https://github.com/Secozzi)) ([#1902](https://github.com/aniyomiorg/aniyomi/pull/1902))
  - Don't unpause on share and save ([@Secozzi](https://github.com/Secozzi)) ([#1905](https://github.com/aniyomiorg/aniyomi/pull/1905))
  - Fix player pausing with gesture seek ([@perokhe](https://github.com/perokhe)) ([#1916](https://github.com/aniyomiorg/aniyomi/pull/1916))
  - Fix potential npe issues with mpv-lib ([@Secozzi](https://github.com/Secozzi)) ([#1921](https://github.com/aniyomiorg/aniyomi/pull/1921))
  - Dismiss chapter sheet on chapter select ([@Secozzi](https://github.com/Secozzi)) ([#1976](https://github.com/aniyomiorg/aniyomi/pull/1976))
  - Fix some issues caused by [`10e28cc`](https://github.com/aniyomiorg/aniyomi/commit/10e28cc4092758cf38d27cc14aadf539698738f2) ([@Secozzi](https://github.com/Secozzi)) ([#1981](https://github.com/aniyomiorg/aniyomi/pull/1981))
  - Fix npe issue caused in player controls ([@Secozzi](https://github.com/Secozzi)) ([#1986](https://github.com/aniyomiorg/aniyomi/pull/1986))
- Replace some manga strings with respective anime strings ([@perokhe](https://github.com/perokhe)) ([#1864](https://github.com/aniyomiorg/aniyomi/pull/1864))
- Open correct tab from extension update notifications ([@jmir1](https://github.com/jmir1)) ([`161471d`](https://github.com/aniyomiorg/aniyomi/commit/161471d94a2350c0c983eeeccd3b7ac0dc66d429))
- Fix sub-auto not loading all external subtitle files ([@perokhe](https://github.com/perokhe)) ([#1866](https://github.com/aniyomiorg/aniyomi/pull/1866))
- Fix `ALSearchItem.format` nullability ([@Secozzi](https://github.com/Secozzi)) ([#1910](https://github.com/aniyomiorg/aniyomi/pull/1910))
- Don't format mpv preferences ([@Secozzi](https://github.com/Secozzi)) ([#1939](https://github.com/aniyomiorg/aniyomi/pull/1939))
- Prevent crash on app death when watching in external player ([@Secozzi](https://github.com/Secozzi)) ([#1945](https://github.com/aniyomiorg/aniyomi/pull/1945))
- Don't run unnecessary stuff when exiting the player ([@Secozzi](https://github.com/Secozzi)) ([#1961](https://github.com/aniyomiorg/aniyomi/pull/1961))
- Fix some downloader issues ([@Secozzi](https://github.com/Secozzi)) ([#1964](https://github.com/aniyomiorg/aniyomi/pull/1964))
  - Fix downloader not working for certain types of tracks & duration sometimes not being logged ([@Secozzi](https://github.com/Secozzi)) ([#2001](https://github.com/aniyomiorg/aniyomi/pull/2001))
- Fix some issues with intro skip length ([@jmir1](https://github.com/jmir1)) ([`72cac57`](https://github.com/aniyomiorg/aniyomi/commit/72cac57d8e66366cbc0f3106eb351c82250c460b), [`25dd3ea`](https://github.com/aniyomiorg/aniyomi/commit/25dd3ea69fb217de7b0485c29e4a9b970737fd45))
- Force clipboard to use UI thread when copying path for external players ([@quickdesh](https://github.com/quickdesh)) ([#1994](https://github.com/aniyomiorg/aniyomi/pull/1994))
- Use application directory for storing files used by mpv ([@Secozzi](https://github.com/Secozzi)) ([#1995](https://github.com/aniyomiorg/aniyomi/pull/1995))
- Update backup warning string (follow Mihon) ([@cuong-tran](https://github.com/cuong-tran)) ([#2012](https://github.com/aniyomiorg/aniyomi/pull/2012))
- Fix issues with episode deletion & more ([@quickdesh](https://github.com/quickdesh)) ([#2017](https://github.com/aniyomiorg/aniyomi/pull/2017))
- Fix vertical slider width issues and shift boost volume value to slider ([@quickdesh](https://github.com/quickdesh)) ([#2018](https://github.com/aniyomiorg/aniyomi/pull/2018))
- Fix MyAnimeList login ([@choppeh](https://github.com/choppeh)) ([#2035](https://github.com/aniyomiorg/aniyomi/pull/2035))
- Call sort methods for videos and hosters ([@cuong-tran](https://github.com/cuong-tran)) ([#2058](https://github.com/aniyomiorg/aniyomi/pull/2058))
- Invalidate preferred languages in settings ([@Secozzi](https://github.com/Secozzi)) ([#2075](https://github.com/aniyomiorg/aniyomi/pull/2075))
- Fix crash when using sort by airing time ([@quickdesh](https://github.com/quickdesh)) ([#2076](https://github.com/aniyomiorg/aniyomi/pull/2076))

### Other

- Merge from mihon until 0.16.5 ([@Secozzi](https://github.com/Secozzi)) ([#1663](https://github.com/aniyomiorg/aniyomi/pull/1663))
  - Merge until latest mihon commits ([@Secozzi](https://github.com/Secozzi)) ([#1693](https://github.com/aniyomiorg/aniyomi/pull/1693))
  - Merge until latest mihon commits (v0.17.0) ([@Secozzi](https://github.com/Secozzi)) ([#1804](https://github.com/aniyomiorg/aniyomi/pull/1804))
  - Merge until latest mihon commits (v0.18.0) ([@Secozzi](https://github.com/Secozzi)) ([#1863](https://github.com/aniyomiorg/aniyomi/pull/1863))
- Remove ACRA crash report analytics ([@jmir1](https://github.com/jmir1)) ([`d3c6a15`](https://github.com/aniyomiorg/aniyomi/commit/d3c6a159d82ca239c10e8f5822c3b2046c5545f2), [`5ae35c8`](https://github.com/aniyomiorg/aniyomi/commit/5ae35c891b90ae927200185641240280effaf667))

## [v0.16.4.3] - 2024-07-01
### Fixed

- Fix extensions disappearing due to errors with the ClassLoader ([@jmir1](https://github.com/jmir1)) ([`959f84a`](https://github.com/aniyomiorg/aniyomi/commit/959f84ab41859f90c458c076d83d363ae086e47f))

## [v0.16.4.2] - 2024-07-01
### Fixed

- Hotfix to eliminate all proguard issues causing errors and crashes ([@jmir1](https://github.com/jmir1)) ([`a8cd723`](https://github.com/aniyomiorg/aniyomi/commit/a8cd7233dfdf26c98ff86b1871a7ac5774379b5e), [`a7644c2`](https://github.com/aniyomiorg/aniyomi/commit/a7644c268153fc0b9f10c27202591f960c6f6384), [`5045fa1`](https://github.com/aniyomiorg/aniyomi/commit/5045fa18ce5a1faa2130f1a33609e43d8453f078))

## [v0.16.4.1] - 2024-07-01
### Fixed

- Hotfix release to address errors with extensions ([@jmir1](https://github.com/jmir1)) ([`98d2528`](https://github.com/aniyomiorg/aniyomi/commit/98d252866e17beba7d9a4d094797e23c05ead6c1))

## [v0.16.4.0] - 2024-07-01
### Fixed

- Fix pip not broadcasting intent in A14+ ([@quickdesh](https://github.com/quickdesh)) ([#1603](https://github.com/aniyomiorg/aniyomi/pull/1603))
- Fix advanced player settings crash in android ≤ 10 ([@perokhe](https://github.com/perokhe)) ([#1627](https://github.com/aniyomiorg/aniyomi/pull/1627))

### Improved

- Hide the skip intro button if the skipped amount == 0 ([@abdallahmehiz](https://github.com/abdallahmehiz)) ([#1598](https://github.com/aniyomiorg/aniyomi/pull/1598))

### Other

- Merge from mihon until mihon 0.16.2 ([@Secozzi](https://github.com/Secozzi)) ([#1578](https://github.com/aniyomiorg/aniyomi/pull/1578))
  - Merge from mihon until 0.16.4 ([@Secozzi](https://github.com/Secozzi)) ([#1601](https://github.com/aniyomiorg/aniyomi/pull/1601))
