#!/usr/bin/env python3
"""Read one Pact screen-time day from disk and print a compact JSON summary.

The menu bar app runs this on the toolkit VPS over SSH. It only reads
/var/lib/pact/activity. It does not create or change files, and the
JSON has no window titles or URLs.
"""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

DEFAULT_ROOT = "/var/lib/pact/activity"
DEFAULT_TZ = "Asia/Kolkata"


def valid_day(day: str) -> bool:
    if len(day) != 10 or day[4] != "-" or day[7] != "-":
        return False
    year, month, date = day.split("-")
    if not (year.isdigit() and month.isdigit() and date.isdigit()):
        return False
    return 1 <= int(month) <= 12 and 1 <= int(date) <= 31


def as_float(value: object) -> float:
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return 0.0


def zone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name or DEFAULT_TZ)
    except Exception:
        return ZoneInfo(DEFAULT_TZ)


def parse_dt(value: str, tz: ZoneInfo) -> datetime | None:
    text = (value or "").strip()
    if not text:
        return None
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=tz)
    return parsed.astimezone(tz)


def split_hours(start: datetime, end: datetime) -> list[tuple[int, float]]:
    """Split a span into (local hour, seconds) pieces. Caps a bad span at 48 hours."""
    if end <= start:
        return []
    pieces: list[tuple[int, float]] = []
    cursor = start
    for _ in range(48):
        if cursor >= end:
            break
        hour_end = cursor.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1)
        nxt = end if end < hour_end else hour_end
        seconds = (nxt - cursor).total_seconds()
        if seconds > 0:
            pieces.append((cursor.hour, seconds))
        if nxt <= cursor:
            break
        cursor = nxt
    return pieces


def named_rows(data: dict, list_key: str, name_key: str) -> list[dict]:
    totals: dict[str, float] = {}
    for row in data.get(list_key) or []:
        if not isinstance(row, dict):
            continue
        name = str(row.get(name_key) or "").strip()
        seconds = as_float(row.get("seconds"))
        if not name or seconds <= 0:
            continue
        totals[name] = totals.get(name, 0.0) + seconds
    rows = []
    for name, seconds in sorted(totals.items(), key=lambda item: (-item[1], item[0])):
        if seconds < 1:
            continue
        rows.append({name_key: name, "seconds": round(seconds, 1)})
    return rows


def hour_rows(data: dict, tz: ZoneInfo) -> list[dict]:
    totals: dict[int, dict[str, float]] = {}
    for segment in data.get("segments") or []:
        if not isinstance(segment, dict):
            continue
        start = parse_dt(str(segment.get("start") or ""), tz)
        end = parse_dt(str(segment.get("end") or ""), tz)
        if start is None or end is None:
            continue
        category = str(segment.get("category") or "").strip() or "unknown"
        for hour, seconds in split_hours(start, end):
            slot = totals.setdefault(hour, {})
            slot[category] = slot.get(category, 0.0) + seconds
    rows = []
    for hour in sorted(totals):
        categories = {}
        for key, seconds in sorted(totals[hour].items()):
            if seconds < 1:
                continue
            categories[key] = round(seconds, 1)
        if categories:
            rows.append({"hour": hour, "categories": categories})
    return rows


def device_payload(data: dict) -> dict:
    tz = zone(str(data.get("tz") or DEFAULT_TZ))
    gathered = data.get("gathered_at")
    return {
        "device_id": str(data.get("device_id") or ""),
        "device_name": str(data.get("device_name") or data.get("hostname") or "Device"),
        "hostname": str(data.get("hostname") or ""),
        "platform": str(data.get("platform") or ""),
        "screen_seconds": round(as_float(data.get("screen_seconds")), 1),
        "engaged_seconds": round(as_float(data.get("engaged_seconds")), 1),
        "gathered_at": gathered if isinstance(gathered, str) else None,
        "by_category": named_rows(data, "by_category", "category"),
        "by_app": named_rows(data, "by_app", "app"),
        "hours": hour_rows(data, tz),
    }


def collect(root: Path, day: str) -> dict:
    devices = []
    if root.is_dir():
        for path in sorted(root.glob("*/*.json")):
            if path.parent.name == "summary" or path.name != day + ".json":
                continue
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError, UnicodeError):
                continue
            if isinstance(data, dict):
                devices.append(device_payload(data))
    devices.sort(key=lambda item: (-as_float(item.get("screen_seconds")), str(item.get("device_name"))))
    return {"day": day, "devices": devices}


def main() -> int:
    day = os.environ.get("PACT_ACTIVITY_DAY", "")
    if not valid_day(day):
        sys.stderr.write("bad day\n")
        return 1
    root = Path(os.environ.get("PACT_ACTIVITY_ROOT", DEFAULT_ROOT))
    json.dump(collect(root, day), sys.stdout, ensure_ascii=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
