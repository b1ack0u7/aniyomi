# Releasing

Releases are cut from a git tag. Pushing a `v*` tag to `origin` makes
`.github/workflows/build_push.yml` build `assembleRelease -Penable-updater`, sign the
five APKs (universal + four ABIs), and open a **draft** GitHub release with the notes
taken from `CHANGELOG.md` and a SHA-256 table appended.

Nothing is published automatically — the draft has to be reviewed and published by hand.

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

- `.github/workflows/build_push.yml` — the five `github.repository == '…'` conditions
  that gate signing, release notes and release creation.
- `app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt` — `GITHUB_REPO`,
  which is where the in-app updater looks for new releases.

## Cutting a release

Work lands on `dev` and reaches `main` through a pull request; the tag is created on
`main` afterwards, on the merge commit. `scripts/release.py` does the repeatable part:
bumps the version, rolls the changelog, runs the CI gates locally, then commits — and
tags too, unless `--no-tag` is passed.

```sh
# On dev, with the release notes already written under "## Unreleased" in CHANGELOG.md.

# 1. See what would change without touching anything.
scripts/release.py 1.1.0 --no-tag --dry-run

# 2. Commit the bump and push dev (drop --push to review the commit first).
scripts/release.py 1.1.0 --no-tag --push

# 3. Open the pull request into main and wait for "PR build check" to pass.
gh pr create --base main --head dev --title "chore: Release v1.1.0" --body "See CHANGELOG.md"

# 4. Merge it with a MERGE COMMIT — not squash, not rebase (see below).
gh pr merge --merge

# 5. Tag the merge commit; this is what triggers the build and the draft release.
git switch main && git pull
git tag -a v1.1.0 -m "Aniyomi v1.1.0"
git push origin v1.1.0

# 6. Put dev back on top of main (fast-forward, no new commit).
git switch dev && git merge main && git push origin dev
```

The script refuses to run on a dirty worktree, on an empty `## Unreleased` section, if the
tag already exists locally or on `origin`, or if the new version is not strictly greater
than the current one.

`versionCode` is bumped by one; pass `--version-code N` to override. `--skip-checks` skips
`spotlessCheck` and `testReleaseUnitTest`, which the CI runs anyway — but a failure there
means a wasted tag, so prefer to let them run.

Without a pull request in the way — releasing straight off a branch — drop `--no-tag` and
the script commits, tags and (with `--push`) pushes both in one go.

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

1. Watch the run under the repo's **Actions** tab (~15–25 min; R8 is on).
2. Open the draft release, check the notes and the checksum table, then **Publish**.
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
