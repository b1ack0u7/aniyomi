# AGENTS.md

Repository-wide guidance for coding agents. Read `CONTRIBUTING.md` for contribution requirements and use the build files and source code to verify details that may change.

## What this is

Aniyomi — an Android manga reader **and** anime player, forked from Mihon (formerly Tachiyomi). Content comes from user-installed extension APKs, not from this repo. Kotlin + Jetpack Compose, min SDK 26, compile/target SDK 36, JDK 17.

This checkout is a customized fork: `app/build.gradle.kts` currently uses application ID `xyz.b1ack0u7.aniyomi.mi`. Preserve the fork's identity and behavior when adapting upstream changes.

## Working in this repository

- Inspect `git status --short` before editing and preserve existing user changes.
- Keep changes focused on the requested behavior. Check the anime/manga counterpart and explain when only one side applies.
- Match surrounding namespaces, dependency injection, and UI patterns; avoid unrelated refactors or comment cleanup.
- Run checks appropriate to the change and report what ran, what failed, and any environment blockers. Documentation-only changes need diff and reference checks, not an Android build.
- Leave changes unstaged and uncommitted unless the user requests commits. Use the commit helper only when commit creation is requested.

## Build and validation

Use the checked-in Gradle wrapper (currently 8.13). The daemon toolchain is pinned to Adoptium JDK 17 in `gradle/gradle-daemon-jvm.properties`. Android SDK setup belongs in the untracked `local.properties` or the local environment; SDK, build-tools, and NDK requirements are in `buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt`. Do not commit machine-specific SDK paths or signing credentials.

```sh
./gradlew :app:assembleDebug            # per-ABI + universal APKs -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease          # release APKs; R8 on by default
./gradlew spotlessCheck                 # ktlint + XML formatting; CI gate
./gradlew spotlessApply                 # autofix; review the diff for unrelated changes
./gradlew testReleaseUnitTest           # all unit tests (CI)
./gradlew :domain:testDebugUnitTest     # one module
./gradlew :domain:testDebugUnitTest --tests "tachiyomi.domain.library.model.LibraryFlagsTest"
```

Tests run on the JUnit 5 platform (`junit-jupiter` + `kotest-assertions` + `mockk`). Tests live in `app/src/test`, `domain/src/test`, `core/common/src/test`.

Start with the affected module or test class; use `spotlessCheck` and `testReleaseUnitTest` for changes that cross modules or before release validation. Reader/player behavior needs device or emulator checks when unit tests cannot cover it.

CI (`.github/workflows/build_push.yml`) runs on pushes to `dev` and `v*` tags. Both run `spotlessCheck` and `testReleaseUnitTest`; only tags build APKs with `assembleRelease -Penable-updater` and run the artifact upload steps. Signing and publication are additionally restricted to the fork repository.

Useful Gradle properties:

- Presence flags: `-Pdisable-code-shrink` skips R8/resource shrinking in release-derived builds; `-Penable-updater` enables the updater; `-Pinclude-dependency-info` embeds dependency metadata. Omit these flags to leave them disabled; passing `=false` still counts as present.
- Boolean values: `-PenableComposeCompilerReports=true`, `-PenableComposeCompilerMetrics=true`, `-PwarningsAsErrors=true`. These default to false.

Build types: `debug` (`.dev` suffix), `release`, `preview` (release + debug signing, `.debug` suffix), and a declared `benchmark` type. Release signing uses `scripts/local/keys/signing.properties` when present; otherwise release APKs are unsigned locally. ABI splits produce `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, and universal APKs.

There are **no product flavors**. The app's `benchmark` variant is profileable, non-debuggable, and debug-signed, with release fallbacks for library dependencies. `:macrobenchmark` enables only its matching `benchmark` variant. See `macrobenchmark/README.md` for baseline-profile generation and device prerequisites.

Native-library check: `./scripts/check-16kb-alignment.sh <apk>` checks 64-bit ELF segment alignment, ZIP compression, and ZIP data offsets against 16 KB boundaries. It requires Bash, Python 3.8+, and readelf/llvm-readelf. Regression tests run with `python3 -B -m unittest discover -s scripts/tests -p 'test_check_16kb_alignment.py' -v`. See `docs/16kb-page-size.md` for recorded dependency blockers and recheck the actual APK after native dependency changes.

## Parallel anime and manga stacks

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

Packages mirror this with `.../manga/` and `.../anime/` subpackages throughout `domain`, `data`, and `app/ui`. When touching either side, look for its counterpart and assess whether the same defect or behavior applies. Reader, player, and media-specific logic can intentionally differ; do not force symmetry where it does not fit.

Occasional shared code sits under `tachiyomi.domain.items.*` / `mihon.domain.items.*` where the logic genuinely is identical.

## Module layout

- `:app` — Compose UI, activities, extension loading/installing, downloads, backup, trackers, reader, player, DI wiring.
- `:domain` — models, repository *interfaces*, and interactors (use cases). Built as an Android library, with Android/AndroidX dependencies; keep UI rendering in the presentation/app modules.
- `:data` — SQLDelight schemas + repository implementations. Two databases (see table above).
- `:source-api` — the API extension APKs compile against (`SManga`/`SAnime`, filters, `HttpSource`). Treat it as public API: preserve binary compatibility, method signatures, and serialized contracts used by existing extensions. Assess compatibility explicitly when changing it.
- `:source-local` — the built-in "Local" source that reads from device storage.
- `:core:common` — networking (`NetworkHelper`, OkHttp interceptors), preferences (`PreferenceStore`), storage, coroutine/lang utils, torrent (bencode) support.
- `:core:archive` — libarchive wrapper (CBZ/ZIP/EPUB).
- `:core-metadata` — ComicInfo and similar metadata formats.
- `:presentation-core` — shared Compose components/theme; `:presentation-widget` — homescreen widgets (Glance).
- `:i18n` (`MR`) / `:i18n-aniyomi` (`AYMR`) — moko-resources string modules.
- `:macrobenchmark` — baseline profile generation using the `benchmark` build type.

Version catalogs live under `gradle/`: `libs.versions.toml`, `androidx.versions.toml`, `compose.versions.toml`, `kotlinx.versions.toml`, `aniyomi.versions.toml` (accessors: `libs`, `androidx`, `compose`, `kotlinx`, `aniyomilibs`). Shared build logic lives in `buildSrc` as `mihon.*` convention plugins.

## Package-name convention (four coexisting namespaces)

- `eu.kanade.tachiyomi.*` — legacy Tachiyomi code (UI, data services, extensions).
- `tachiyomi.*` — the clean domain/data/core layers.
- `mihon.*` — newer upstream Mihon code (migrations, archive, upcoming feature).
- `aniyomi.*` — Aniyomi-specific additions (torrent, anime domain extras).

They are not layered by prefix — pick the namespace that matches the surrounding code of the file you're editing.

## Architecture patterns

**DI is Injekt, wired manually.** Modules are imported in `App.onCreate`: `PreferenceModule`, `AppModule` (`app/src/main/java/eu/kanade/tachiyomi/di/`), `DomainModule`, `SYDomainModule` (`app/src/main/java/eu/kanade/domain/`). Register new interactors/repositories in the appropriate existing module with `addFactory { … }` / `addSingletonFactory<Iface> { Impl(get()) }`. Consumers use `Injekt.get()` / `injectLazy()`, often as constructor defaults so dependencies can be supplied in tests.

**UI is Voyager + Compose.** `MainActivity` → `HomeScreen` → `Tab`s (library, updates, history, browse, more), each side having its own tab (e.g. `AnimeLibraryTab` / `MangaLibraryTab`). Per-screen state lives in a `StateScreenModel<State>` (`*ScreenModel.kt`) that exposes an `@Immutable` state class; the pure Compose rendering lives under `eu/kanade/presentation/`. `eu/kanade/tachiyomi/ui/` holds the screens/screen models, `eu/kanade/presentation/` holds the composables they render. Reader and player are separate Activities, not Voyager screens.

**Data flow:** SQLDelight `.sq` query → `*DatabaseHandler` (`subscribeToList`/`awaitOne`, dispatching to IO) → `*RepositoryImpl` in `:data` → repository interface in `:domain` → interactor in `:domain` → screen model. Interactors are named as verbs (`GetAnimeCategories`, `SetSeenStatus`, `UpdateChapter`).

**Preferences** are `PreferenceStore`-backed objects grouped by concern (`BasePreferences`, `UiPreferences`, `PlayerPreferences`, …). Each `Preference<T>` exposes `.get()`, `.set()`, `.changes()`, plus an `asState(scope)` extension (`eu.kanade.core.preference`) for Compose.

**App-version migrations** (not DB migrations) live in `app/src/main/java/mihon/core/migration/migrations/`. Add a `Migration` with the appropriate version float to that package's `Migrations.kt` list when a release needs to rewrite preferences or data. `Migrator` runs them on startup; follow existing ordering and version conventions.

## Making common changes

**Database column/table:** add a numbered `.sqm` in `data/src/main/sqldelight/migrations/` (manga) or `data/src/main/sqldelightanime/migrations/` (anime — note the separate, higher version sequence), update the matching `.sq` file, then thread the field through the mapper in `:data`, the model in `:domain`, and backup serialization if it should survive a backup/restore.

**Strings:** edit English base resources under `i18n/src/commonMain/moko-resources/base/` (`MR`) or `i18n-aniyomi/src/commonMain/moko-resources/base/` (`AYMR`, Aniyomi additions). Reuse existing keys when appropriate. Translations are maintained through Weblate; avoid bulk edits to translated or generated resources.

**New preference:** app settings are included automatically when app-settings backup is selected: `PreferenceBackupCreator.createApp()` reads `preferenceStore.getAll()`. Opt out through `Preference.appStateKey(...)` (internal state; never backed up) or restrict secrets with `Preference.privateKey(...)` (included only with `BackupOptions.privateSettings`). Only `Int`/`Long`/`Float`/`String`/`Boolean`/`Set<String>` are supported; other values are dropped by `toBackupPreferences()`. `getEnum` stores the constant name and falls back to the default on an unknown one.

### Backup compatibility rules

Restore has to tolerate backups written by a *newer* app (downgrade). `PreferenceRestorer` merges rather than replaces, dispatches on the `PreferenceValue` subclass, and guards each write with a nullable type check (`prefs[key] is Int?`) — so an unknown key is written blindly and a key whose type changed is skipped in silence. Failures only reach logcat, never the restore error log. Three things break that tolerance:

- **Preserve the six `PreferenceValue` subclasses and their serialized names.** The sealed hierarchy uses fully-qualified class names as polymorphic discriminators. An older decoder cannot read a backup containing an unknown subclass; `BackupDecoder` turns the serialization failure into `IOException(invalid_backup_file_unknown)` for the whole file. Encode new settings using the existing supported types.
- **Never reuse a preference key with a different type.** On a downgrade the value gets written with the new type, and the older app later blows up with `ClassCastException` inside `SharedPreferences.getX` — outside the restorer's `try/catch`. Use a new key and migrate in `mihon/core/migration/migrations/`.
- **Custom `getObject` serializers must tolerate garbage** and fall back to the default, the way `getEnum` does.

Active backup models live in `app/src/main/java/eu/kanade/tachiyomi/data/backup/models/`; older classes also exist under `backup/full/models/`, so follow the decoder's imports. Add fields with defaults and unique `@ProtoNumber`s; use the 500+ range for Aniyomi-only fields on the top-level `Backup`. Preserve existing numbers and legacy decoding, including retired numbers (100, 102). Unknown fields can be skipped, but changing an existing field's type or nested serialization can still break compatibility. There are currently no backup tests under `app/src/test`; add targeted compatibility coverage when changing this format.

## Using Mihon as a reference

For manga bugs with an upstream counterpart, check Mihon's implementation/history before writing a fix when available. Adapt the underlying fix to this fork's names, resource split, databases, and anime counterpart; a clean cherry-pick does not establish correctness.

Inspect remotes before fetching; this checkout already has a `mihon` remote. Use its actual default branch rather than assuming the branch name. For a checkout using `mihon/main`:

```sh
git remote -v
git fetch mihon
git log -1 -i --grep="Merge from Mihon" --format="%h %ad %s" --date=short   # last sync point
git log mihon/main --oneline -- <path/to/counterpart/file>                  # file history
git show mihon/main:<path/to/counterpart/file>                              # read upstream's version
```

Upstream paths often omit our `Manga` class prefix and `/manga/` package segment. Locate the actual counterpart before comparing. If a fix is derived from upstream, record the commit reference in the change summary and, when requested, the commit body.

Fix directly here when the code is Aniyomi-specific, upstream lacks a suitable fix, or upstream is unavailable. Report any lookup limitation and continue with local evidence. Always assess the anime counterpart independently; Mihon cannot validate it.

## Style and commits

Spotless/ktlint (`intellij_idea` style) with 120-char lines, 4-space Kotlin indent, trailing commas allowed, and star imports disabled entirely. Composable function naming is exempt from ktlint's function-naming rule.

When commits are requested, use Conventional-Commits-style messages with **no scope** and a capitalized subject: `feat: Add torrent streaming support`. Allowed types: `build`, `chore`, `ci`, `docs`, `feat`, `fix`, `perf`, `refactor`, `style`, `test`. `scripts/commits/commiter.py` can create local commits from an explicitly supplied JSON plan; `scripts/local/` is ignored. It never pushes.

## Code comments — keep them scarce

- Prefer clear names, types, and small functions. Add comments only for a non-obvious constraint or reason.
- Keep comments to 1–2 lines, or three for a subtle constraint. State decisions at their source; mirrored stacks may each need a short note.
- Avoid narrating the code, describing the diff, explaining language basics, or adding banner dividers. Use descriptive test names instead of explanatory test comments.
- Preserve useful public API KDoc in `:source-api`; extension developers depend on it.
- Update or remove misleading comments in code you actually change. Keep unrelated cleanup out of the diff.
