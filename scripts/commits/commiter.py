#!/usr/bin/env python3
"""Create local commits from a commit-plan JSON file. This script never pushes."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from typing import Any, NoReturn

VALID_ACTIONS = {'track', 'delete'}
VALID_TYPES = {
    'build',
    'chore',
    'ci',
    'docs',
    'feat',
    'fix',
    'perf',
    'refactor',
    'style',
    'test',
}

USAGE = """Usage: scripts/commiter.py [commit-plan.json] [options]

Create local commits from a commit-plan JSON file. This script never pushes.

Options:
  --dry-run              Print the commits that would be created
  --patch-repeated       Interactively stage repeated files hunk by hunk
  --whole-files          Stage whole files; this is the default
  --no-initial-unstage   Do not unstage planned files before starting
  --start-at <number>    Start at a commit number in the plan
  -h, --help             Show this help message
"""


def warn(message: str) -> None:
    # Flush stdout first: it is block-buffered when piped, stderr is not, so
    # without this the warning would surface out of order.
    sys.stdout.flush()
    print(message, file=sys.stderr)


def fail(message: str) -> NoReturn:
    sys.stdout.flush()
    print(f'\ncommiter: {message}', file=sys.stderr)
    sys.exit(1)


def print_header(text: str) -> None:
    print(f'\n=== {text} ===')


def run_git(git_args: list[str], *, allow_failure: bool = False, label: str | None = None):
    if label:
        print(f'\n> {label}')
    sys.stdout.flush()
    result = subprocess.run(['git', *git_args])
    if not allow_failure and result.returncode != 0:
        fail(f'git {" ".join(git_args)} failed.')
    return result


def git_output(git_args: list[str]) -> str:
    result = subprocess.run(['git', *git_args], capture_output=True, text=True)
    if result.returncode != 0:
        fail(result.stderr or f'git {" ".join(git_args)} failed.')
    return result.stdout


def is_tracked(file_path: str) -> bool:
    result = subprocess.run(
        ['git', 'ls-files', '--error-unmatch', '--', file_path],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def has_staged_changes() -> bool:
    result = subprocess.run(['git', 'diff', '--cached', '--quiet'])
    if result.returncode == 0:
        return False
    if result.returncode == 1:
        return True
    fail('Unable to inspect staged changes.')


def validate_message(message: Any, location: str) -> None:
    if not isinstance(message, str) or message.strip() == '':
        fail(f'{location} must have a message.')

    match = re.match(r'([a-z]+): [A-Z].+', message)
    if not match:
        fail(f'{location} message must look like "feat: Add thing".')

    if match.group(1) not in VALID_TYPES:
        fail(f'{location} message uses unsupported type "{match.group(1)}".')

    if re.match(r'[a-z]+\(.+\):', message):
        fail(f'{location} message must not use a scope.')


def validate_plan(plan: Any) -> None:
    if not isinstance(plan, list) or len(plan) == 0:
        fail('Commit plan must be a non-empty JSON array.')

    for commit_index, commit in enumerate(plan):
        location = f'commit {commit_index + 1}'
        if not isinstance(commit, dict):
            fail(f'{location} must be an object.')
        title = commit.get('title')
        if not isinstance(title, str) or title.strip() == '':
            fail(f'{location} must have a title.')
        validate_message(commit.get('message'), location)

        files = commit.get('files')
        if not isinstance(files, list) or len(files) == 0:
            fail(f'{location} must include at least one file.')

        for file_index, file in enumerate(files):
            file_location = f'{location}, file {file_index + 1}'
            if not isinstance(file, dict):
                fail(f'{file_location} must be an object.')
            file_path = file.get('path')
            if not isinstance(file_path, str) or file_path.strip() == '':
                fail(f'{file_location} must have a path.')

            if os.path.isabs(file_path) or '...' in re.split(r'[\\/]', file_path):
                fail(f'{file_location} must use a safe workspace-relative path.')

            if file.get('action') not in VALID_ACTIONS:
                fail(f'{file_location} has invalid action "{file.get("action")}".')


def read_plan(resolved_plan_path: str) -> list[dict]:
    if not os.path.exists(resolved_plan_path):
        fail(f'Commit plan not found: {resolved_plan_path}')

    try:
        with open(resolved_plan_path, encoding='utf8') as handle:
            plan = json.load(handle)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        fail(f'Commit plan is not valid JSON: {error}')

    validate_plan(plan)
    return plan


def validate_start_at(value: int, plan_length: int) -> None:
    if value < 1 or value > plan_length:
        fail(f'--start-at must be a number between 1 and {plan_length}.')


def get_repeated_paths(plan: list[dict]) -> set[str]:
    counts: dict[str, int] = {}
    for commit in plan:
        for file in commit['files']:
            counts[file['path']] = counts.get(file['path'], 0) + 1
    return {file_path for file_path, count in counts.items() if count > 1}


def get_staged_paths() -> list[str]:
    output = git_output(
        ['diff', '--cached', '--name-only', '--diff-filter=ACDMRTUXB']
    )
    return [line.strip() for line in output.split('\n') if line.strip()]


def assert_clean_index_for_unplanned_files(planned_paths: list[str]) -> None:
    planned = set(planned_paths)
    unplanned = [
        file_path for file_path in get_staged_paths() if file_path not in planned
    ]
    if unplanned:
        listing = '\n'.join(f'  - {file_path}' for file_path in unplanned)
        fail(
            f'Unplanned files are already staged:\n{listing}\n'
            'Unstage them or adjust the plan.'
        )


def unstage_planned_files(planned_paths: list[str]) -> None:
    planned = set(planned_paths)
    staged_planned_paths = [
        file_path for file_path in get_staged_paths() if file_path in planned
    ]

    if not staged_planned_paths:
        return

    run_git(
        ['restore', '--staged', '--', *staged_planned_paths],
        label='Unstage planned files before staging commits',
    )


def make_patchable(file_path: str) -> None:
    if is_tracked(file_path) or not os.path.exists(file_path):
        return

    run_git(
        ['add', '-N', '--', file_path],
        label='Mark untracked file for interactive staging',
    )


def stage_commit_files(
    commit: dict, repeated_paths: set[str], *, whole_files: bool
) -> None:
    regular_files: list[str] = []
    patch_files: list[str] = []

    for file in commit['files']:
        # Skip paths that don't exist on disk and aren't tracked: there is nothing
        # to stage (e.g. a planned rename whose old path was already removed from
        # HEAD in a prior run). Passing such a path to `git add -A` makes the whole
        # call abort with "did not match any files", taking the valid paths with it.
        if not os.path.exists(file['path']) and not is_tracked(file['path']):
            warn(
                f'  skipping {file["action"]} of "{file["path"]}" '
                '(not on disk and not tracked — nothing to stage)'
            )
            continue

        if not whole_files and file['path'] in repeated_paths:
            patch_files.append(file['path'])
        else:
            regular_files.append(file['path'])

    if regular_files:
        run_git(['add', '-A', '--', *regular_files], label='Stage whole-file changes')

    for file_path in patch_files:
        print(f'\nInteractive staging for repeated file: {file_path}')
        make_patchable(file_path)
        run_git(['add', '-p', '--', file_path], label='Stage selected hunks')


def print_dry_run(
    plan: list[dict], repeated_paths: set[str], start_number: int, whole_files: bool
) -> None:
    print_header('Dry run')
    print(f'Commits to create: {len(plan)}')
    if repeated_paths:
        label = (
            'Repeated files that will be staged whole at first occurrence:'
            if whole_files
            else 'Files requiring interactive hunk staging:'
        )
        print(f'\n{label}')
        for file_path in sorted(repeated_paths):
            print(f'  - {file_path}')

    for index, commit in enumerate(plan):
        print(f'\n{start_number + index}. {commit["message"]}')
        for file in commit['files']:
            print(f'    {file["action"]:<6} {file["path"]}')


def parse_args(args: list[str]):
    dry_run = False
    initial_unstage = True
    whole_files = True
    plan_path = 'commit-plan.json'
    start_at = 1

    index = 0
    while index < len(args):
        arg = args[index]
        if arg == '--dry-run':
            dry_run = True
        elif arg == '--patch-repeated':
            whole_files = False
        elif arg == '--whole-files':
            whole_files = True
        elif arg == '--no-initial-unstage':
            initial_unstage = False
        elif arg == '--start-at':
            value = args[index + 1] if index + 1 < len(args) else None
            if not value:
                fail('--start-at requires a commit number.')
            try:
                start_at = int(value, 10)
            except ValueError:
                fail('--start-at must be a number.')
            index += 1
        elif arg in ('-h', '--help'):
            sys.stdout.write(USAGE)
            sys.exit(0)
        elif arg.startswith('-'):
            fail(f'Unknown option: {arg}')
        else:
            plan_path = arg
        index += 1

    return dry_run, initial_unstage, whole_files, plan_path, start_at


def main() -> None:
    dry_run, initial_unstage, whole_files, plan_path, start_at = parse_args(
        sys.argv[1:]
    )

    repo_root = git_output(['rev-parse', '--show-toplevel']).strip()
    os.chdir(repo_root)

    plan = read_plan(os.path.join(repo_root, plan_path))
    validate_start_at(start_at, len(plan))
    selected_plan = plan[start_at - 1 :]
    repeated_paths = get_repeated_paths(selected_plan)
    planned_paths = list(
        dict.fromkeys(
            file['path'] for commit in selected_plan for file in commit['files']
        )
    )

    if dry_run:
        print_dry_run(selected_plan, repeated_paths, start_at, whole_files)
        return

    if whole_files and repeated_paths:
        warn(
            'Whole-file staging enabled. Repeated files will be committed the '
            'first time they are listed.'
        )

    assert_clean_index_for_unplanned_files(planned_paths)

    if initial_unstage and planned_paths:
        unstage_planned_files(planned_paths)

    for index, commit in enumerate(selected_plan):
        number = start_at + index
        print_header(f'{number}/{len(plan)} {commit["title"]}')

        stage_commit_files(commit, repeated_paths, whole_files=whole_files)

        if not has_staged_changes():
            fail(
                f'No staged changes for {commit["title"]}. '
                'Stage at least one hunk or adjust the plan.'
            )

        run_git(['diff', '--cached', '--stat'], label='Review staged changes')
        run_git(['commit', '-m', commit['message']], label=f'Commit {number}')

    print_header('Done')
    print('Created local commits only. No push was performed.')


if __name__ == '__main__':
    main()
