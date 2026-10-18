Looking to report an issue/bug or make a feature request? Please refer to the [README file](https://github.com/aniyomiorg/aniyomi#issues-feature-requests-and-contributing).

---

Thanks for your interest in contributing to Aniyomi!


# Fork development workflow

This fork uses task branches → `dev` → `main`. Start from the fork's development branch:

```sh
git clone --branch dev git@github.com:b1ack0u7/aniyomi.git
cd aniyomi
git switch -c codex/my-change
```

For an existing clone, use `git fetch origin`, `git switch dev` and
`git merge --ff-only origin/dev` before creating the task branch. Preserve local changes;
never reset a shared branch to make it match the remote.

Implement the change, add relevant coverage and user-facing `CHANGELOG.md` entries under
`Unreleased`, and run the affected checks listed in [AGENTS.md](AGENTS.md). When authorized
to commit and push, use a Conventional Commit and create a PR targeting `dev`:

```sh
git push -u origin codex/my-change
gh pr create --repo b1ack0u7/aniyomi --base dev --head codex/my-change --title "fix: Describe the change" --body-file /tmp/pr-body.md
```

Write the body file first with the problem, behavior, anime/manga impact and validation.
Wait for `CI / PR checks` to pass on the latest PR revision, resolve conflicts and merge
the task PR. Do not use `main` as the target for individual changes.

CI validates PRs targeting both long-lived branches. `main` enforces the successful
`PR checks` result, an up-to-date branch and merge commits, including for administrators.
`dev` remains unprotected to permit the release maintainer's fast-forward synchronization;
contributors and agents still use PRs and wait for CI.

The same agent or another release maintainer can then follow
[the release procedure](scripts/release/releasing.md). For a handoff, provide the task PR,
current `dev` commit, release notes, proposed version and checks already run. Merging work
into `dev` does not itself authorize publishing a release.

# Code contributions

Pull requests are welcome!

If you're interested in taking on [an open issue](https://github.com/aniyomiorg/aniyomi/issues), please comment on it so others are aware.
You do not need to ask for permission nor an assignment.

## Prerequisites

Before you start, please note that the ability to use following technologies is **required** and that existing contributors will not actively teach them to you.

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)

### Tools

- [Android Studio](https://developer.android.com/studio)
- Emulator or phone with developer options enabled to test changes.

## Getting help

- Join [the Discord server](https://discord.gg/F32UjdJZrR) for online help and to ask questions while developing.

# Translations

Translations are done externally via Weblate. See [our website](https://aniyomi.org/docs/contribute#translation) for more details.


# Forks

Forks are allowed so long as they abide by [the project's LICENSE](https://github.com/aniyomiorg/aniyomi/blob/main/LICENSE).

When creating a fork, remember to:

- To avoid confusion with the main app:
    - Change the app name
    - Change the app icon
    - Change or disable the [app update checker](https://github.com/aniyomiorg/aniyomi/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt)
- To avoid installation conflicts:
    - Change the `applicationId` in [`build.gradle.kts`](https://github.com/aniyomiorg/aniyomi/blob/main/app/build.gradle.kts)
- To avoid having your data polluting the main app's analytics and crash report services:
    - If you want to use Firebase analytics, replace [`google-services.json`](https://github.com/aniyomiorg/aniyomi/blob/main/app/src/standard/google-services.json) with your own
    - If you want to use ACRA crash reporting, replace the `ACRA_URI` endpoint in [`build.gradle.kts`](https://github.com/aniyomiorg/aniyomi/blob/main/app/build.gradle.kts) with your own
