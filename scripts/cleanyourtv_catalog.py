#!/usr/bin/env python3
"""Builds the package catalog behind Settings > Apps > Clean up this TV from cleanyourtv's tested lists.

cleanyourtv (https://github.com/Gathin23/cleanyourtv, MIT) keeps one debloat list per TV model in
examples/<tv>/debloat*.txt, next to a tv.json that names the TV. This script reads every list at a
pinned commit and writes:

    app/src/main/assets/cleanup/catalog.json              packages with counts and notes, plus every list
    app/src/main/assets/cleanup/cleanyourtv-LICENSE.txt   cleanyourtv's licence, shipped with its data

    python3 -I scripts/cleanyourtv_catalog.py                 # the pinned commit (REF below)
    python3 -I scripts/cleanyourtv_catalog.py --ref <sha>     # another commit; update REF when you keep it
    python3 -I scripts/cleanyourtv_catalog.py --source <dir>  # an already checked out copy

List lines: `pkg  # note` is a package the TV's list turns off; `# pkg` or `# pkg  # note` is one the
list keeps on purpose; any other line is header text. Core Android packages are dropped, with the same
rule as cleanyourtv's tools/disable.sh and OwnTV's CleanupRules: without them a TV may not boot.
The output is sorted, so the same commit always gives the same file.
"""

import argparse
import collections
import io
import json
import os
import re
import sys
import tarfile
import tempfile
import urllib.request

REPO = "Gathin23/cleanyourtv"
REF = "b121838a666832fda3e7bddb702df9a6610d67aa"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "cleanup")

PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
# Keep in step with CleanupRules.CORE in the app (and cleanyourtv's CORE_RE / CORE_OK).
CORE = re.compile(
    r"^(android|android\.ext\.(services|shared)|com\.android\.sdksandbox|com\.android\.location\.fused"
    r"|com\.android\.systemui|com\.android\.shell|com\.android\.phone|com\.android\.settings|com\.android\.tv\.settings"
    r"|com\.(google\.)?android\.(permissioncontroller|packageinstaller|ext\.services|ext\.shared|modulemetadata)"
    r"|com\.google\.android\.overlay\.modules.*|com\.android\.providers\..*|com\.cltv\.mal"
    r"|com\.mediatek\.(tis|tv\.service|tv\.agent))$"
)
CORE_OK = re.compile(r"^com\.android\.providers\.(calendar|contacts|userdictionary|blockednumber)$")
HEADER_LINES = 3
MATCH_CHARS = 300


def is_core(name: str) -> bool:
    return bool(CORE.match(name)) and not CORE_OK.match(name)


def split_note(text: str) -> tuple[str, str]:
    """`pkg   # note` -> ("pkg", "note"); a line without a note gives an empty note."""
    head, _, note = text.partition("#")
    return head.strip(), " ".join(note.split())


def parse_list(text: str) -> tuple[list[tuple[str, str]], list[str], list[str]]:
    """Returns (turned off with notes, kept on purpose, header comment lines) of one debloat file."""
    off, kept, header = [], [], []
    for raw in text.lstrip("﻿").splitlines():
        line = raw.strip()
        if not line:
            continue
        if not line.startswith("#"):
            name, note = split_note(line)
            name = name.split()[0] if name.split() else ""
            if PACKAGE.match(name):
                off.append((name, note))
            continue
        body = line.lstrip("#").strip()
        first, _, rest = body.partition(" ")
        rest = rest.strip()
        if PACKAGE.match(first) and (not rest or rest.startswith("#")):
            kept.append(first)
        elif len(header) < HEADER_LINES and body:
            header.append(body)
    return off, kept, header


def read_tree(source: str) -> dict:
    examples = os.path.join(source, "examples")
    packages_off = collections.Counter()
    packages_kept = collections.Counter()
    notes: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    lists = []
    for folder in sorted(os.listdir(examples)):
        path = os.path.join(examples, folder)
        if not os.path.isdir(path):
            continue
        info = {}
        tv_json = os.path.join(path, "tv.json")
        if os.path.exists(tv_json):
            with open(tv_json, encoding="utf-8") as f:
                info = json.load(f)
        for file in sorted(os.listdir(path)):
            if not (file.startswith("debloat") and file.endswith(".txt")):
                continue
            with open(os.path.join(path, file), encoding="utf-8", errors="replace") as f:
                off, kept, header = parse_list(f.read())
            off = [(name, note) for name, note in off if not is_core(name)]
            names = sorted({name for name, _ in off})
            if not names:
                continue
            for name in names:
                packages_off[name] += 1
            for name in set(kept) - set(names):
                if not is_core(name):
                    packages_kept[name] += 1
            for name, note in off:
                if note:
                    notes[name][note] += 1
            tv = info.get("tv") or folder
            variant = file[len("debloat"):-len(".txt")].lstrip("-")
            match = " ".join([folder.replace("-", " "), tv, *header]).lower()
            entry = {
                "id": f"{folder}/{file}",
                "tv": tv,
                "brand": info.get("brand") or folder.split("-")[0].capitalize(),
                "os": info.get("os", ""),
                "match": " ".join(match.split())[:MATCH_CHARS],
                "off": names,
            }
            if variant:
                entry["variant"] = variant
            lists.append(entry)

    def best_note(name: str) -> str:
        counted = notes.get(name)
        if not counted:
            return ""
        # Most common, then shortest, then alphabetical: the same input always picks the same note.
        return min(counted.items(), key=lambda item: (-item[1], len(item[0]), item[0]))[0]

    packages = {}
    for name in sorted(set(packages_off) | set(packages_kept)):
        if not packages_off[name]:
            continue  # only ever kept: nothing tested says it is safe to turn off
        entry = {"off": packages_off[name]}
        if packages_kept[name]:
            entry["kept"] = packages_kept[name]
        note = best_note(name)
        if note:
            entry["note"] = note
        packages[name] = entry
    return {"packages": packages, "lists": lists}


def download(ref: str, into: str) -> str:
    url = f"https://codeload.github.com/{REPO}/tar.gz/{ref}"
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        wanted = [
            member for member in archive.getmembers()
            if member.name.count("/") >= 1
            and (member.name.split("/", 1)[1] == "LICENSE" or member.name.split("/", 1)[1].startswith("examples/"))
        ]
        archive.extractall(into, members=wanted, filter="data")
    tops = os.listdir(into)
    if len(tops) != 1:
        sys.exit(f"unexpected archive layout from {url}: {tops}")
    return os.path.join(into, tops[0])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ref", default=REF, help="cleanyourtv commit to read (default: the pinned one)")
    parser.add_argument("--source", help="read an already checked out copy instead of downloading")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as temp:
        source = args.source or download(args.ref, temp)
        catalog = {"source": {"repo": REPO, "ref": args.ref}, **read_tree(source)}
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(os.path.join(OUT_DIR, "catalog.json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
            f.write("\n")
        with open(os.path.join(source, "LICENSE"), encoding="utf-8") as f:
            licence = f.read()
        with open(os.path.join(OUT_DIR, "cleanyourtv-LICENSE.txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write(licence)
    print(f"{len(catalog['packages'])} packages, {len(catalog['lists'])} lists from {REPO}@{args.ref[:12]}")


if __name__ == "__main__":
    main()
