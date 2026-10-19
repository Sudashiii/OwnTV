#!/usr/bin/env python3
"""Works out the next OwnTV release from the conventional commits since the last vX.Y.Z tag.

Prints the next version, or nothing when no feat, fix or perf commit landed since the last
release, and writes the release notes as Markdown.

    python3 scripts/release.py --notes -           # preview: the version, then the notes
    python3 scripts/release.py --notes notes.md    # the release workflow
    python3 scripts/release.py --version 1.0.0 --notes notes.md

Bumps: a breaking change (`feat!:` or a `BREAKING CHANGE:` footer) bumps the major version, or
the minor version before 1.0.0; feat bumps the minor version; fix and perf bump the patch.
"""

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass

HEADER = re.compile(r"^(?P<type>\w+)(?:\((?P<scope>[^)]+)\))?(?P<bang>!)?: (?P<subject>.+)$")
BREAKING_FOOTER = re.compile(r"^BREAKING[ -]CHANGE: (?P<text>.+?)(?:\n\s*\n|\Z)", re.M | re.S)
TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
VERSION = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")

SECTIONS = [("feat", "Features"), ("fix", "Fixes"), ("perf", "Performance")]


@dataclass
class Commit:
    sha: str
    type: str
    scope: str | None
    subject: str
    breaking: str | None  # what breaks, when the commit is a breaking change


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def last_tag() -> str | None:
    try:
        tags = git("tag", "--merged", "HEAD", "--sort=-v:refname", "--list", "v*").split()
    except subprocess.CalledProcessError:
        return None
    return next((tag for tag in tags if TAG.match(tag)), None)


def commits_since(tag: str | None) -> list[Commit]:
    log = git("log", "--no-merges", "--format=%H%x1f%s%x1f%b%x1e", f"{tag}..HEAD" if tag else "HEAD")
    commits = []
    for record in log.split("\x1e"):
        if not record.strip():
            continue
        sha, subject, body = record.strip("\n").split("\x1f")
        header = HEADER.match(subject)
        if not header:
            continue
        subject = header["subject"][0].upper() + header["subject"][1:]
        footer = BREAKING_FOOTER.search(body)
        breaking = " ".join(footer["text"].split()) if footer else (subject if header["bang"] else None)
        commits.append(Commit(sha, header["type"], header["scope"], subject, breaking))
    return commits


def next_version(current: tuple[int, int, int], commits: list[Commit]) -> tuple[int, int, int] | None:
    major, minor, patch = current
    if any(c.breaking for c in commits):
        return (major + 1, 0, 0) if major > 0 else (0, minor + 1, 0)
    if any(c.type == "feat" for c in commits):
        return major, minor + 1, 0
    if any(c.type in ("fix", "perf") for c in commits):
        return major, minor, patch + 1
    return None


def repo_url() -> str | None:
    if os.environ.get("GITHUB_REPOSITORY"):
        return f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{os.environ['GITHUB_REPOSITORY']}"
    try:
        origin = git("remote", "get-url", "origin").strip()
    except subprocess.CalledProcessError:
        return None
    match = re.match(r"^(?:https://github\.com/|git@github\.com:)(.+?)(?:\.git)?$", origin)
    return f"https://github.com/{match[1]}" if match else None


def notes(commits: list[Commit], previous: str | None, version: str, url: str | None) -> str:
    def entry(commit: Commit, text: str) -> str:
        sha = commit.sha[:7]
        link = f"[{sha}]({url}/commit/{commit.sha})" if url else sha
        scope = f"**{commit.scope}:** " if commit.scope else ""
        return f"- {scope}{text} ({link})"

    parts = []
    breaking = [c for c in commits if c.breaking]
    if breaking:
        parts.append("### Breaking changes\n\n" + "\n".join(entry(c, c.breaking) for c in breaking))
    for type, title in SECTIONS:
        matching = [c for c in commits if c.type == type]
        if matching:
            parts.append(f"### {title}\n\n" + "\n".join(entry(c, c.subject) for c in matching))
    if not parts:
        parts.append("No new features or fixes.")
    if previous and url:
        parts.append(f"**Full changelog:** [{previous}...v{version}]({url}/compare/{previous}...v{version})")
    return "\n\n".join(parts) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--version", help="release this version instead of working it out (empty: work it out)")
    parser.add_argument("--notes", required=True, help="file for the release notes, or - to print them")
    args = parser.parse_args()

    tag = last_tag()
    current = tuple(map(int, TAG.match(tag).groups())) if tag else (0, 0, 0)
    commits = commits_since(tag)

    if args.version:
        if not VERSION.match(args.version):
            sys.exit(f"Expected a version like 1.2.3, got {args.version}")
        version = tuple(map(int, args.version.split(".")))
        if version <= current:
            sys.exit(f"{args.version} is not newer than the last release {tag}")
    else:
        version = next_version(current, commits)
    if version is None:
        return

    name = ".".join(map(str, version))
    text = notes(commits, tag, name, repo_url())
    print(name)
    if args.notes == "-":
        print()
        print(text, end="")
    else:
        with open(args.notes, "w") as file:
            file.write(text)


if __name__ == "__main__":
    main()
