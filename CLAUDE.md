# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Aniyomi — an Android manga reader **and** anime player, forked from Mihon (formerly Tachiyomi). Content comes from user-installed extension APKs, not from this repo. Kotlin + Jetpack Compose, min SDK 26, compile/target SDK 36, JDK 17.

## Commands

```sh
./gradlew assembleDebug                 # per-ABI + universal debug APKs -> app/build/outputs/apk/debug/
./gradlew assembleRelease               # what CI builds (R8 on)
./gradlew spotlessCheck                 # ktlint + XML formatting; CI gate
./gradlew spotlessApply                 # autofix — run before committing
./gradlew testReleaseUnitTest           # all unit tests (CI)
./gradlew :domain:testDebugUnitTest     # one module
./gradlew :domain:testDebugUnitTest --tests "tachiyomi.domain.library.model.LibraryFlagsTest"
```

Tests run on the JUnit 5 platform (`junit-jupiter` + `kotest-assertions` + `mockk`). Tests live in `app/src/test`, `domain/src/test`, `core/common/src/test`.

Useful Gradle properties (all off by default): `-Pdisable-code-shrink` (skip R8 in release/preview builds — much faster), `-Penable-updater`, `-PenableComposeCompilerReports`, `warningsAsErrors=true`.

Build types: `debug` (`.dev` suffix), `release`, `preview` (release + debug signing, `.debug` suffix), `benchmark` (for `:macrobenchmark`). ABI splits are enabled, so `assemble*` emits one APK per ABI plus a universal one. There are **no product flavors** despite a vestigial `withFlavor("default" to "standard")` block in `app/build.gradle.kts`.

Native-library page-size check (Android 15+ 16 KB pages): `./scripts/check-16kb-alignment.sh <apk>` — see `docs/16kb-page-size.md` for current status and the three still-blocked libraries.

## Everything is duplicated: anime vs. manga

This is the defining fact of the codebase. Aniyomi carries Mihon's entire manga stack *and* a parallel anime stack. They are separate types, separate tables, separate databases — not a shared generic abstraction:

| Manga side | Anime side |
| --- | --- |
| `Manga` / `Chapter` | `Anime` / `Episode` |
| `MangaSource`, `CatalogueSource`, `HttpSource` | `AnimeSource`, `AnimeCatalogueSource`, `AnimeHttpSource` |
| `SManga`, `SChapter`, `Page` | `SAnime`, `SEpisode`, `Video`, `Hoster` |
| `Database` (`data/src/main/sqldelight`, pkg `tachiyomi.data`) | `AnimeDatabase` (`data/src/main/sqldelightanime`, pkg `tachiyomi.mi.data`) |
| `MangaDatabaseHandler` | `AnimeDatabaseHandler` |
| `ReaderActivity` / `ReaderViewModel` | `PlayerActivity` / `PlayerViewModel` (mpv-android) |
| `MangaExtensionManager` | `AnimeExtensionManager` |

Packages mirror this with `.../manga/` and `.../anime/` subpackages throughout `domain`, `data`, and `app/ui`. **A change to one side almost always needs the mirrored change on the other.** When you touch a manga file, look for its anime twin before declaring the work done.

Occasional shared code sits under `tachiyomi.domain.items.*` / `mihon.domain.items.*` where the logic genuinely is identical.

## Module layout

- `:app` — Compose UI, activities, extension loading/installing, downloads, backup, trackers, reader, player, DI wiring.
- `:domain` — models, repository *interfaces*, and interactors (use cases). No Android/UI deps.
- `:data` — SQLDelight schemas + repository implementations. Two databases (see table above).
- `:source-api` — the API extension APKs compile against (`SManga`/`SAnime`, filters, `HttpSource`). **Changing anything here is a compatibility break for every published extension** — treat as public API.
- `:source-local` — the built-in "Local" source that reads from device storage.
- `:core:common` — networking (`NetworkHelper`, OkHttp interceptors), preferences (`PreferenceStore`), storage, coroutine/lang utils, torrent (bencode) support.
- `:core:archive` — libarchive wrapper (CBZ/ZIP/EPUB).
- `:core-metadata` — ComicInfo and similar metadata formats.
- `:presentation-core` — shared Compose components/theme; `:presentation-widget` — homescreen widgets (Glance).
- `:i18n` (`MR`) / `:i18n-aniyomi` (`AYMR`) — moko-resources string modules.
- `:macrobenchmark` — baseline profile generation only (`benchmark` build type).

Version catalogs are split across `gradle/libs.versions.toml`, `androidx.versions.toml`, `compose.versions.toml`, `kotlinx.versions.toml`, `aniyomi.versions.toml` (accessors: `libs`, `androidx`, `compose`, `kotlinx`, `aniyomilibs`). Shared build logic lives in `buildSrc` as `mihon.*` convention plugins; SDK/JDK versions are in `buildSrc/.../AndroidConfig.kt`.

## Package-name convention (four coexisting namespaces)

- `eu.kanade.tachiyomi.*` — legacy Tachiyomi code (UI, data services, extensions).
- `tachiyomi.*` — the clean domain/data/core layers.
- `mihon.*` — newer upstream Mihon code (migrations, archive, upcoming feature).
- `aniyomi.*` — Aniyomi-specific additions (torrent, anime domain extras).

They are not layered by prefix — pick the namespace that matches the surrounding code of the file you're editing.

## Architecture patterns

**DI is Injekt, wired manually.** Modules are imported in `App.onCreate`: `PreferenceModule`, `AppModule` (`app/src/main/java/eu/kanade/tachiyomi/di/`), `DomainModule`, `SYDomainModule` (`eu/kanade/domain/`). New interactor or repository → register it in `DomainModule` with `addFactory { … }` / `addSingletonFactory<Iface> { Impl(get()) }`. Consumers use `Injekt.get()` / `injectLazy()`, not constructor injection.

**UI is Voyager + Compose.** `MainActivity` → `HomeScreen` → `Tab`s (library, updates, history, browse, more), each side having its own tab (e.g. `AnimeLibraryTab` / `MangaLibraryTab`). Per-screen state lives in a `StateScreenModel<State>` (`*ScreenModel.kt`) that exposes an `@Immutable` state class; the pure Compose rendering lives under `eu/kanade/presentation/`. `eu/kanade/tachiyomi/ui/` holds the screens/screen models, `eu/kanade/presentation/` holds the composables they render. Reader and player are separate Activities, not Voyager screens.

**Data flow:** SQLDelight `.sq` query → `*DatabaseHandler` (`subscribeToList`/`awaitOne`, dispatching to IO) → `*RepositoryImpl` in `:data` → repository interface in `:domain` → interactor in `:domain` → screen model. Interactors are named as verbs (`GetAnimeCategories`, `SetSeenStatus`, `UpdateChapter`).

**Preferences** are `PreferenceStore`-backed objects grouped by concern (`BasePreferences`, `UiPreferences`, `PlayerPreferences`, …). Each `Preference<T>` exposes `.get()`, `.set()`, `.changes()`, plus an `asState(scope)` extension (`eu.kanade.core.preference`) for Compose.

**App-version migrations** (not DB migrations) live in `mihon/core/migration/migrations/` — one `Migration` per version float, appended to the `migrations` list in that package's `Migrations.kt` and run by `Migrator` on startup. Add one when a release needs to rewrite preferences or data.

## Making common changes

**Database column/table:** add a numbered `.sqm` in `data/src/main/sqldelight/migrations/` (manga) or `data/src/main/sqldelightanime/migrations/` (anime — note the separate, higher version sequence), update the matching `.sq` file, then thread the field through the mapper in `:data`, the model in `:domain`, and backup serialization if it should survive a backup/restore.

**New preference:** nothing to do for backup. `PreferenceBackupCreator.createApp()` dumps `preferenceStore.getAll()` wholesale, so every new key is backed up automatically — it's an opt-out blocklist, not a registry. Opt *out* by declaring the key through `Preference.appStateKey(...)` (internal state; never backed up) or `Preference.privateKey(...)` (secrets; backed up only with `BackupOptions.privateSettings`). Only `Int`/`Long`/`Float`/`String`/`Boolean`/`Set<String>` survive; anything else is dropped silently by `toBackupPreferences()`. Enums go through `getEnum`, which stores the constant name and falls back to the default on an unknown one.

### Backup compatibility rules

Restore has to tolerate backups written by a *newer* app (downgrade). `PreferenceRestorer` merges rather than replaces, dispatches on the `PreferenceValue` subclass, and guards each write with a nullable type check (`prefs[key] is Int?`) — so an unknown key is written blindly and a key whose type changed is skipped in silence. Failures only reach logcat, never the restore error log. Three things break that tolerance:

- **Never add a `PreferenceValue` subclass, and never rename or move the existing six.** The sealed hierarchy is encoded polymorphically with the fully-qualified class name as discriminator, and `BackupDecoder` turns an unknown discriminator into `IOException(invalid_backup_file_unknown)` for the *whole file*. A seventh type makes every new backup unreadable by older versions.
- **Never reuse a preference key with a different type.** On a downgrade the value gets written with the new type, and the older app later blows up with `ClassCastException` inside `SharedPreferences.getX` — outside the restorer's `try/catch`. Use a new key and migrate in `mihon/core/migration/migrations/`.
- **Custom `getObject` serializers must tolerate garbage** and fall back to the default, the way `getEnum` does.

Adding a field to `Backup` is safe: unknown proto field numbers are skipped on decode. Use `@ProtoNumber(500+)` for Aniyomi-only fields, give it a default, and never reuse a burned number (100, 102). Tests for all of this live in `app/src/test/java/eu/kanade/tachiyomi/data/backup/`.

## Fixing bugs: check Mihon first, but don't be blocked by it

The manga half of this app is a fork of [Mihon](https://github.com/mihonapp/mihon), synced in periodically via merge commits. A manga-side bug here is therefore often a bug upstream already found and fixed. Check before writing your own fix — but treat upstream as a *reference*, never as something to apply verbatim.

**1. See whether upstream has it.** No `mihon` remote is configured by default; add one read-only:

```sh
git remote add mihon https://github.com/mihonapp/mihon.git
git fetch mihon
git log -1 -i --grep="Merge from Mihon" --format="%h %ad %s" --date=short   # last sync point
git log mihon/main --oneline -- <path/to/counterpart/file>                  # upstream churn since then
git show mihon/main:<path/to/counterpart/file>                              # read upstream's version
```

Upstream paths differ: Aniyomi prefixes the manga classes and inserts a `manga` package segment, so our `MangaSourceManager` / `MangaCoverCache` under `.../source/manga/` are upstream's `SourceManager` / `CoverCache` under `.../source/`. Drop the `Manga` prefix and the `/manga/` segment to guess the upstream path.

**2. Reconcile, don't cherry-pick.** `git cherry-pick mihon/...` will generally conflict on files that have diverged, and — worse — can apply cleanly while leaving the anime twin broken. Read the upstream diff, understand the actual defect, then write the equivalent change against our code: our names, our `MR`/`AYMR` string split, our `Database` vs `AnimeDatabase`. Mention the upstream commit in the commit body when the fix is derived from one.

**3. Then check the anime twin.** Upstream cannot tell you about it, and it is the step most often missed. If the bug is in shared logic or in mirrored code, `Episode`/`Anime`/player almost certainly has the same defect — fix both sides in the same change.

**Fix it directly here, without an upstream reference, when:**

- The code has no Mihon counterpart at all — anything anime, the mpv player, torrent streaming, `aniyomi.*` packages, `AYMR` strings, `:i18n-aniyomi`.
- Upstream never had the bug, because it's in code Aniyomi added or already rewrote.
- Upstream has the same bug unfixed, or fixed it in a way that doesn't fit our divergence.

In those cases just write the fix. Do not stall waiting on upstream, and do not reshape a change to resemble a patch that doesn't exist. If you did check upstream and found nothing, say so — that's a useful result, not a dead end.

## Style and commits

Spotless/ktlint (`intellij_idea` style) with 120-char lines, 4-space Kotlin indent, trailing commas allowed, and star imports disabled entirely. Composable function naming is exempt from ktlint's function-naming rule.

Commit messages are Conventional-Commits-style with **no scope** and a capitalized subject: `feat: Add torrent streaming support`. Allowed types: `build`, `chore`, `ci`, `docs`, `feat`, `fix`, `perf`, `refactor`, `style`, `test`. `scripts/commits/commiter.py` builds a series of local commits from a JSON plan (see `scripts/local/commit-plan.json`) and enforces exactly that format; it never pushes.

## Code comments — keep them scarce

Comments in this repo have drifted into essays. **Default to no comment.** Names, types, and small functions should carry the meaning; a comment is the fallback for what the code genuinely can't say.

**Only write a comment for non-obvious *why*:** a constraint that isn't visible locally, a gotcha that would look like a bug later, or a deliberate choice a reader would otherwise "fix". If the sentence starts by restating the code, delete it.

**Hard limits:**

- **1–2 lines.** Three only for something genuinely subtle. Never a multi-paragraph header above a file, a class, or a one-line function.
- **Scale with the diff.** A small change earns at most one comment — usually zero. Don't annotate lines you touched only incidentally.
- **One place, not every place.** State a design decision once at its source; don't repeat it on each caller. Mirrored anime/manga code is the one exception — each side is read on its own, so keep the note short on both rather than duplicating a long explanation.

**Never:**

- Narrate what the line does (`// Map of source id to source`, above `val sourcesMap = mutableMapOf<Long, Source>()`)
- Narrate the change itself (`// now takes an Anime instead of an id`) — that's the commit message's job, not the code's
- Explain Kotlin/Compose/coroutine basics, or add banner/section dividers
- Comment tests — encode the intent in the test function name (`` fun `returns empty list when no categories exist`() ``) instead

**Exception:** `:source-api` is public API that extension developers compile against — KDoc on its public types and members is expected and stays. Keep it factual and short; the rules above still apply everywhere else, including the rest of `:domain`, `:data`, and `:app`.

**When editing existing code**, trim comments you pass through that already break these rules; don't preserve them out of politeness.
