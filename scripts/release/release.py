#!/usr/bin/env python3
"""Prepare a release: bump the version, roll the changelog, commit and tag.

Pushing is opt-in (--push). Once the tag reaches GitHub, the CI workflow in
.github/workflows/build_push.yml builds, signs and publishes a draft release
with the five APKs and the notes taken from CHANGELOG.md.

When the release goes through a pull request into main, run it with --no-tag: the
bump travels in the pull request, and the tag is created afterwards on the merge
commit, so the tagged commit is the tip of main rather than a commit on dev.
"""

from __future__ import annotations

import argparse
import datetime
import re
import subprocess
import sys
from pathlib import Path
from typing import NoReturn

REPO_ROOT = Path(__file__).resolve().parent.parent
GRADLE_FILE = REPO_ROOT / 'app' / 'build.gradle.kts'
CHANGELOG_FILE = REPO_ROOT / 'CHANGELOG.md'

# Versions are three-component semver: major.minor.patch.
VERSION_PATTERN = re.compile(r'^\d+\.\d+\.\d+$')

# [^\S\n] rather than \s so the trailing blank line after the block is left alone.
VERSION_CODE_PATTERN = re.compile(r'^([^\S\n]*versionCode[^\S\n]*=[^\S\n]*)(\d+)[^\S\n]*$', re.MULTILINE)
VERSION_NAME_PATTERN = re.compile(r'^([^\S\n]*versionName[^\S\n]*=[^\S\n]*)"([^"]+)"[^\S\n]*$', re.MULTILINE)

UNRELEASED_HEADING = '## Unreleased'


def fail(message: str) -> NoReturn:
    sys.stdout.flush()
    print(f'\nrelease: {message}', file=sys.stderr)
    sys.exit(1)


def step(text: str) -> None:
    print(f'\n=== {text} ===')


def run(command: list[str], *, capture: bool = False) -> str:
    print(f'> {" ".join(command)}')
    sys.stdout.flush()
    if capture:
        result = subprocess.run(command, cwd=REPO_ROOT, capture_output=True, text=True)
    else:
        result = subprocess.run(command, cwd=REPO_ROOT)
    if result.returncode != 0:
        stderr = (result.stderr or '').strip() if capture else ''
        fail(stderr or f'{" ".join(command)} failed.')
    return result.stdout if capture else ''


def git_output(git_args: list[str]) -> str:
    return run(['git', *git_args], capture=True).strip()


def read_current_version() -> tuple[int, str]:
    text = GRADLE_FILE.read_text(encoding='utf8')

    code_match = VERSION_CODE_PATTERN.search(text)
    name_match = VERSION_NAME_PATTERN.search(text)
    if not code_match or not name_match:
        fail(f'Could not find versionCode/versionName in {GRADLE_FILE.relative_to(REPO_ROOT)}.')

    return int(code_match.group(2)), name_match.group(2)


def assert_newer(new_version: str, current_version: str) -> None:
    if not VERSION_PATTERN.match(new_version):
        fail(f'Version must look like 1.0.0 (major.minor.patch), got "{new_version}".')

    new_parts = [int(part) for part in new_version.split('.')]
    current_parts = [int(part) for part in current_version.split('.')]
    if new_parts <= current_parts:
        fail(f'Version {new_version} is not greater than the current {current_version}.')


def assert_clean_worktree() -> None:
    if git_output(['status', '--porcelain']):
        fail('Working tree has uncommitted changes. Commit or stash them first.')


def assert_tag_is_free(tag: str) -> None:
    if git_output(['tag', '--list', tag]):
        fail(f'Tag {tag} already exists locally.')

    remote_tags = run(['git', 'ls-remote', '--tags', 'origin', tag], capture=True).strip()
    if remote_tags:
        fail(f'Tag {tag} already exists on origin.')


def bump_gradle_version(new_code: int, new_version: str) -> None:
    text = GRADLE_FILE.read_text(encoding='utf8')
    text = VERSION_CODE_PATTERN.sub(lambda match: f'{match.group(1)}{new_code}', text, count=1)
    text = VERSION_NAME_PATTERN.sub(lambda match: f'{match.group(1)}"{new_version}"', text, count=1)
    GRADLE_FILE.write_text(text, encoding='utf8')


def roll_changelog(tag: str, release_date: str) -> None:
    text = CHANGELOG_FILE.read_text(encoding='utf8')

    lines = text.split('\n')
    try:
        start = next(index for index, line in enumerate(lines) if line.strip() == UNRELEASED_HEADING)
    except StopIteration:
        fail(f'Could not find a "{UNRELEASED_HEADING}" section in CHANGELOG.md.')

    end = next(
        (index for index in range(start + 1, len(lines)) if lines[index].startswith('## ')),
        len(lines),
    )

    body = '\n'.join(lines[start + 1:end]).strip()
    if not body:
        fail(f'The "{UNRELEASED_HEADING}" section is empty; write the release notes first.')

    # Released sections put the first "### " heading straight under the version
    # heading, with no blank line in between; keep that shape.
    lines[start:end] = [
        UNRELEASED_HEADING,
        '',
        f'## [{tag}] - {release_date}',
        body,
        '',
    ]
    CHANGELOG_FILE.write_text('\n'.join(lines), encoding='utf8')


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog='scripts/release.py',
        description='Bump the version, roll the changelog, commit and tag a release.',
    )
    parser.add_argument('version', help='new versionName, e.g. 1.0.0')
    parser.add_argument('--date', help='release date for the changelog (default: today, UTC)')
    parser.add_argument(
        '--version-code',
        type=int,
        help='explicit versionCode (default: current + 1)',
    )
    parser.add_argument(
        '--skip-checks',
        action='store_true',
        help='skip spotlessCheck and testReleaseUnitTest',
    )
    parser.add_argument(
        '--no-tag',
        action='store_true',
        help='only commit the bump; tag the merge commit on main by hand after the pull request lands',
    )
    parser.add_argument('--dry-run', action='store_true', help='show what would change and stop')
    parser.add_argument('--push', action='store_true', help='push the branch, and the tag unless --no-tag')
    return parser.parse_args()


def main() -> None:
    args = parse_args()

    new_version = args.version.lstrip('v')
    tag = f'v{new_version}'
    release_date = args.date or datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d')

    if args.date and not re.match(r'^\d{4}-\d{2}-\d{2}$', args.date):
        fail('--date must be YYYY-MM-DD.')

    step('Preflight')
    current_code, current_version = read_current_version()
    assert_newer(new_version, current_version)
    assert_clean_worktree()
    assert_tag_is_free(tag)

    new_code = args.version_code if args.version_code is not None else current_code + 1
    if new_code <= current_code:
        fail(f'versionCode {new_code} is not greater than the current {current_code}.')

    branch = git_output(['rev-parse', '--abbrev-ref', 'HEAD'])
    print(f'\n  branch       {branch}')
    print(f'  versionCode  {current_code} -> {new_code}')
    print(f'  versionName  {current_version} -> {new_version}')
    if args.no_tag:
        print(f'  tag          {tag}  ({release_date}) — not created, --no-tag')
    else:
        print(f'  tag          {tag}  ({release_date})')

    if args.dry_run:
        print('\nDry run: nothing was modified.')
        return

    # Before touching any file: the gates validate committed code, not the version
    # bump, and running them first means a failure leaves the worktree clean and the
    # command retryable instead of half applied.
    if not args.skip_checks:
        step('Run CI gates locally')
        run(['./gradlew', 'spotlessCheck'])
        run(['./gradlew', 'testReleaseUnitTest'])

    step('Update version and changelog')
    bump_gradle_version(new_code, new_version)
    roll_changelog(tag, release_date)
    print(f'Updated {GRADLE_FILE.relative_to(REPO_ROOT)} and {CHANGELOG_FILE.relative_to(REPO_ROOT)}.')

    step('Commit' if args.no_tag else 'Commit and tag')
    run(['git', 'add', '--', str(GRADLE_FILE.relative_to(REPO_ROOT)), str(CHANGELOG_FILE.relative_to(REPO_ROOT))])
    run(['git', 'commit', '-m', f'chore: Bump version to {tag}'])
    if not args.no_tag:
        run(['git', 'tag', '-a', tag, '-m', f'Aniyomi {tag}'])

    if args.push:
        step('Push')
        run(['git', 'push', 'origin', branch])
        if args.no_tag:
            print(f'\nPushed {branch}. Open the pull request; tag {tag} on main once it is merged.')
        else:
            run(['git', 'push', 'origin', tag])
            print(f'\nPushed {tag}. CI will build, sign and open a draft release.')
    elif args.no_tag:
        print(
            f'\nCreated the bump commit for {tag} locally. Push it and open the pull request with:\n'
            f'  git push origin {branch}\n'
            f'Tag {tag} on main after the pull request is merged.',
        )
    else:
        print(
            f'\nCreated commit and tag {tag} locally. Push them with:\n'
            f'  git push origin {branch}\n'
            f'  git push origin {tag}',
        )


if __name__ == '__main__':
    main()
