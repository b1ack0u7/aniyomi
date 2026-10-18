# Releasing

Releases are cut from a git tag. Pushing a `v*` tag to `origin` makes
`.github/workflows/release.yml` build `assembleRelease -Penable-updater`, sign the
five APKs (universal + four ABIs), and publish a GitHub release automatically with the notes
taken from `CHANGELOG.md` and a SHA-256 table appended.

Pushing the tag is the publication action: obtain release authorization before pushing it.
Review the notes and CI on the release PR first; the workflow uses `draft: false`.

## One-time setup

### 1. Create the signing keystore

Every release must be signed with the **same** key. If the key is lost, users cannot
update over an existing install — they have to uninstall first, losing their data unless
they restore a backup. Keep the keystore and its passwords backed up somewhere safe, and
never commit them.

Generate it outside the repository:

```sh
mkdir -p ~/.aniyomi-signing
keytool -genkeypair -v \
  -keystore ~/.aniyomi-signing/release.jks \
  -alias aniyomi \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype JKS
```

`keytool` asks for a keystore password, the certificate fields (name, org, country — any
value is fine for a fork), and a key password. Use the same value for both passwords:
the signing action passes them separately but does not handle the case where they differ
from what the alias expects.

Note down four values — these become the GitHub secrets:

| Secret | Value |
| --- | --- |
| `SIGNING_KEY` | the keystore file, base64-encoded (below) |
| `ALIAS` | `aniyomi` (the `-alias` used above) |
| `KEY_STORE_PASSWORD` | the keystore password |
| `KEY_PASSWORD` | the key password |

Encode the keystore:

```sh
base64 -w0 ~/.aniyomi-signing/release.jks > ~/.aniyomi-signing/release.jks.base64
```

`-w0` matters: the value must be a single line with no wrapping.

### 2. Upload the secrets to GitHub

Either through the web UI — **Settings → Secrets and variables → Actions → New
repository secret**, once per row of the table above — or with the `gh` CLI:

```sh
gh secret set SIGNING_KEY < ~/.aniyomi-signing/release.jks.base64
gh secret set ALIAS --body "aniyomi"
gh secret set KEY_STORE_PASSWORD --body "…"
gh secret set KEY_PASSWORD --body "…"
```

Verify with `gh secret list` — four entries must be present. A missing or mistyped secret
surfaces as a failure in the *Sign APK* step, or as a *Clean up build artifacts* step that
cannot find the `-signed.apk` files.

### 3. Check the fork wiring

Two places name the repository and must point at this fork, not upstream:

- `.github/workflows/release.yml` — the `github.repository == '…'` conditions
  that gate signing, release notes and release creation.
- `app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt` — `GITHUB_REPO`,
  which is where the in-app updater looks for new releases.

## Building a signed release locally

Useful to test the actual release build — R8 on, real signature, upgrade over an installed
release — before cutting a tag.

Create `scripts/local/keys/signing.properties` (the whole `scripts/local` directory is
gitignored) next to the keystore:

```properties
storeFile=scripts/local/keys/release.jks
storePassword=…
keyAlias=aniyomi
keyPassword=…
```

`storeFile` is relative to the repository root. When that file exists, `app/build.gradle.kts`
signs the `release` build type with it; when it does not — CI — the build keeps producing
`-unsigned.apk` for the workflow's *Sign APK* step, so nothing about the tag flow changes.

Then run the **Build local release** run configuration in Android Studio — it lives in
`.idea/runConfigurations/Build_local_release.xml`, which is gitignored like the rest of
`.idea`, so a fresh clone needs it recreated (Gradle task `:app:assembleRelease`, script
parameters `-Penable-updater`). The portable equivalent:

```sh
./gradlew :app:assembleRelease -Penable-updater
```

The five signed APKs land in `app/build/outputs/apk/release/` as `app-<abi>-release.apk`
(`app-universal-release.apk` is the one to sideload). The run configuration chains
`:app:openReleaseApks` after the build, which opens that directory in the file manager;
drop it from the task list if the build runs unattended. Add `-Pdisable-code-shrink` to skip R8
when the point is to test a change rather than the release build itself — it is much faster,
but it is no longer what ships.

Check the wiring with `./gradlew :app:signingReport` — the `release` variant must list the
same SHA-256 fingerprint as the published APKs. A build signed with a different key installs
only after uninstalling, which is exactly what this build is meant to catch early.

## Cutting a release

Task PRs land on `dev`; only a release PR from this repository's `dev` branch reaches
`main`. See [CONTRIBUTING.md](../../CONTRIBUTING.md) for cloning, task branches and handoffs.
The developer or another release maintainer can perform the following steps once authorized.

### Prepare the version through dev

Start with an updated, clean `dev`, then prepare the bump on a task branch. Replace
`1.1.0` with the next three-component version. Write user-facing notes under
`## Unreleased` in `CHANGELOG.md` before running the script (and commit them first if needed).

```sh
git fetch origin
git switch dev
git merge --ff-only origin/dev
git switch -c codex/release-1.1.0
python3 scripts/release/release.py 1.1.0 --no-tag --date YYYY-MM-DD --dry-run
python3 scripts/release/release.py 1.1.0 --no-tag --date YYYY-MM-DD --push
```

Replace `YYYY-MM-DD` with the intended release date. Without `--date` the script uses UTC.
It bumps `versionCode` by one, rolls the changelog, runs `spotlessCheck` and
`testReleaseUnitTest`, and commits the two release files. It refuses dirty worktrees,
empty release notes, duplicate tags and non-increasing versions. `--version-code N`
overrides the code; `--skip-checks` is only appropriate when the same checks have already
passed on that code, and must be disclosed in the PR. Always use `--no-tag` in this flow.

Write `/tmp/release-preparation.md` with the proposed version, notes and validation, then:

```sh
gh pr create --repo b1ack0u7/aniyomi --base dev --head codex/release-1.1.0 --title "chore: Prepare v1.1.0" --body-file /tmp/release-preparation.md
```

Wait for `CI / PR checks` and merge the preparation PR into `dev`. Freeze further changes
on `dev` until the release PR is merged and the branches are synchronized. New pushes to
`dev` are included in the open release PR and must pass its checks again.

### Promote dev to main and publish

Write `/tmp/release-pr.md` with the release summary, validation, version and publication
plan. A handoff to another agent should also include the preparation PR and exact `dev` SHA.

```sh
gh pr create --repo b1ack0u7/aniyomi --base main --head dev --title "chore: Release v1.1.0" --body-file /tmp/release-pr.md
```

The `secure-main` ruleset requires GitHub Actions `PR checks`, an up-to-date branch, and
a PR merged with a merge commit. It blocks deletion and force-pushes, with no administrator
bypass and no requirement for another approving reviewer. CI rejects PRs to `main` that
come from a branch other than this repository's `dev`. Do not rename the check without
updating the ruleset. Its versioned definition is [.github/rulesets/main.json](../../.github/rulesets/main.json).

After CI passes and the maintainer authorizes the release, replace `PR_NUMBER` below
with the release PR number. `--match-head-commit` prevents merging a different revision
if `dev` changes after the check:

```sh
gh pr checks PR_NUMBER --repo b1ack0u7/aniyomi --watch
release_head=$(gh pr view PR_NUMBER --repo b1ack0u7/aniyomi --json headRefOid --jq .headRefOid)
gh pr merge PR_NUMBER --repo b1ack0u7/aniyomi --merge --match-head-commit "$release_head"
git fetch origin
git switch main
git merge --ff-only origin/main
release_merge=$(gh pr view PR_NUMBER --repo b1ack0u7/aniyomi --json mergeCommit --jq .mergeCommit.oid)
test "$(git rev-parse HEAD)" = "$release_merge" && git tag -a v1.1.0 "$release_merge" -m "Aniyomi v1.1.0"
git push origin refs/tags/v1.1.0
```

Stop if any command fails. Verify `versionName`, `versionCode` and the changelog section
on the merged commit before creating the tag. Never move or force-push a published tag.
The tag workflow repeats validation and builds, signs and publishes all five APK variants.

### Synchronize the long-lived branches

Immediately after tagging, with `dev` still frozen:

```sh
git switch dev
git merge --ff-only origin/dev
git merge --ff-only main
git push origin dev
git rev-parse main dev origin/main origin/dev
```

All four SHAs should match the release merge commit. If fast-forward fails, stop and
inspect concurrent changes; do not reset, force-push or create an extra merge commit.
`dev` remains unprotected specifically to permit this maintainer synchronization; ordinary
changes still go through task PRs and successful CI. Leave the checkout on `dev`.

### Which workflow runs

| Event | Workflow | Work performed |
| --- | --- | --- |
| PR opened/updated targeting `dev` | CI / PR checks | Format, release unit tests, alignment-checker regression tests |
| PR opened/updated targeting `main` | CI / PR checks | Same validation plus verification that the source is this repository's `dev` |
| Manual CI dispatch | CI / PR checks | Same validation on the selected ref |
| Branch push, including post-release fast-forward to `dev` | None | PR validation already ran before merge |
| Push of a `v*` tag | Release / Publish APKs | Validation, release build, signing, APK upload and publication |

PR CI checks GitHub's proposed merge result, so it validates integration with the target
branch. There are no path filters that leave required checks pending for documentation PRs.
PR CI has read-only repository permissions; only the tag workflow can publish releases.
Previously `build_push.yml` listened to every push to `dev`, so synchronizing `main` back
into `dev` started another run even when the source files were identical. The PR-only
trigger removes that duplicate branch run.

To restore the live main ruleset from its versioned definition (repository admin required):

```sh
gh api --method PUT repos/b1ack0u7/aniyomi/rulesets/20164691 --input .github/rulesets/main.json
```

### Why a merge commit, and why tag afterwards

**Squash and rebase both orphan the tag.** GitHub rewrites the commits in those two modes,
so the tagged commit never becomes part of `main`: the release would point at a commit that
only exists on `dev`, and `dev` would permanently diverge from `main`. Only *Create a merge
commit* keeps the branch's commits reachable from `main`.

**Tagging after the merge puts the tag on the tip of `main`.** Tag on `dev` first and the
merge commit lands on top of it, so `main`'s tip is one commit ahead of what was actually
released — the APK is identical, but `git describe` on `main` no longer names the release
and building `main` is not literally building the tag.

### Version format

`versionName` is three-component semver — `major.minor.patch`, e.g. `1.0.0`. Upstream
Aniyomi uses four components; this fork deliberately does not.

The tag is always `v` + `versionName`. `RELEASE_URL` in `AppUpdateChecker.kt` is built from
that assumption, so a mismatch makes the "view release" link inside the app 404.

### After pushing the tag

1. Watch the **Release** run under the repo's **Actions** tab until it succeeds; R8 can take time.
2. Open the published release and check that all five APKs, notes and the checksum table are present.
3. Install the universal APK on a device and confirm it upgrades over the previous build
   rather than asking for an uninstall — if it asks, the signing key changed.

## Notes on this fork

The `applicationId` is `xyz.b1ack0u7.aniyomi.mi`, distinct from official Aniyomi's
`xyz.jmir.tachiyomi.mi`, so both can be installed side by side. Two consequences:

- There is no upgrade path from official Aniyomi — it is a separate app, with its own data
  directory. Users move over by exporting a backup from one and restoring it in the other.
- Both apps register the `aniyomi://` scheme used for tracker OAuth callbacks
  (`myanimelist-auth`, `anilist-auth`, …). With both installed, Android shows an app chooser
  when a login redirect comes back; picking the wrong one drops the token.

Backup filenames are derived from the `applicationId` (`BackupCreator`), so they are now
`xyz.b1ack0u7.aniyomi.mi_<date>.tachibk`. Backups from before the rename still restore —
only the automatic pruning of old backup files keys on the name.
