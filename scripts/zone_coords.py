#!/usr/bin/env python3
"""Writes app/src/main/assets/night/zones.tsv: the place of every tz time zone, for night light's sunset to
sunrise when the TV cannot tell its location.

Reads tzdata's zone.tab (public domain), by default the system copy:

    python3 scripts/zone_coords.py
    python3 scripts/zone_coords.py --zone-tab path/to/zone.tab

Each line is `zone<TAB>latitude<TAB>longitude<TAB>place`, degrees with east and north positive.
"""

import argparse
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/night/zones.tsv"
COORD = re.compile(r"^([+-])(\d{2})(\d{2})(\d{2})?([+-])(\d{3})(\d{2})(\d{2})?$")


def degrees(sign: str, d: str, m: str, s: str | None) -> float:
    value = int(d) + int(m) / 60 + (int(s) / 3600 if s else 0)
    return round(-value if sign == "-" else value, 3)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zone-tab", default="/usr/share/zoneinfo/zone.tab")
    args = parser.parse_args()
    rows = []
    for line in pathlib.Path(args.zone_tab).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        match = COORD.match(fields[1])
        if not match:
            raise SystemExit(f"bad coordinates: {line}")
        lat = degrees(*match.group(1, 2, 3, 4))
        lon = degrees(*match.group(5, 6, 7, 8))
        zone = fields[2]
        place = zone.rsplit("/", 1)[-1].replace("_", " ")
        rows.append(f"{zone}\t{lat}\t{lon}\t{place}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(sorted(rows)) + "\n", encoding="utf-8")
    print(f"{len(rows)} zones -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
