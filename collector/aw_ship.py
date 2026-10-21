#!/usr/bin/env python3
"""Read local ActivityWatch and ship today's screen time to the toolkit VPS.

ActivityWatch already records this Mac (frontmost app, idle, Firefox tabs).
This tool does not watch the screen itself. Each run rebuilds the local
day from aw-server on 127.0.0.1:5600 and replaces that day's file on the VPS.

  python3 collector/aw_ship.py --dry-run
  python3 collector/aw_ship.py
  python3 collector/aw_ship.py --install
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent))

import aw_usage  # noqa: E402
import pact_local  # noqa: E402

DEFAULT_AW = "http://127.0.0.1:5600"
DEFAULT_SSH = "pact@pact-host"
REMOTE_ROOT = "/var/lib/pact/activity"
LABEL = "app.pact.aw-ship"

SUMMARY_SCRIPT = r"""
import json, os, sys
from pathlib import Path

root = Path(os.environ.get("PACT_ACTIVITY_ROOT", "/var/lib/pact/activity"))
day = os.environ.get("PACT_ACTIVITY_DAY", "")
if len(day) != 10 or day[4] != "-" or day[7] != "-":
    sys.stderr.write("bad day\n")
    raise SystemExit(1)

def add(bucket, key, seconds):
    bucket[key] = bucket.get(key, 0.0) + float(seconds or 0)

categories, cat_engaged = {}, {}
apps, app_engaged = {}, {}
domains = {}
devices = []
for path in sorted(root.glob("*/*")):
    if path.parent.name == "summary" or path.suffix != ".json":
        continue
    if path.name != day + ".json":
        continue
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        continue
    devices.append({
        "device_id": data.get("device_id"),
        "hostname": data.get("hostname"),
        "device_name": data.get("device_name") or data.get("hostname"),
        "platform": data.get("platform"),
        "screen_seconds": round(float(data.get("screen_seconds") or 0), 1),
        "engaged_seconds": round(float(data.get("engaged_seconds") or 0), 1),
        "gathered_at": data.get("gathered_at"),
    })
    for row in data.get("by_category") or []:
        key = str(row.get("category") or "unknown")
        add(categories, key, row.get("seconds"))
        add(cat_engaged, key, row.get("engaged_seconds"))
    for row in data.get("by_app") or []:
        key = str(row.get("app") or "unknown")
        add(apps, key, row.get("seconds"))
        add(app_engaged, key, row.get("engaged_seconds"))
    for row in data.get("by_domain") or []:
        key = str(row.get("domain") or "")
        if key:
            add(domains, key, row.get("seconds"))

def rows(totals, field, engaged=None):
    out = []
    for key, seconds in sorted(totals.items(), key=lambda item: (-item[1], item[0])):
        seconds = round(seconds, 1)
        if seconds <= 0:
            continue
        row = {field: key, "seconds": seconds}
        if engaged is not None:
            row["engaged_seconds"] = round(engaged.get(key, 0.0), 1)
        out.append(row)
    return out

by_category = rows(categories, "category", cat_engaged)
summary = {
    "day": day,
    "screen_seconds": round(sum(row["seconds"] for row in by_category), 1),
    "engaged_seconds": round(sum(row.get("engaged_seconds", 0) for row in by_category), 1),
    "devices": devices,
    "by_category": by_category,
    "by_app": rows(apps, "app", app_engaged),
    "by_domain": rows(domains, "domain"),
}
summary_dir = root / "summary"
summary_dir.mkdir(parents=True, exist_ok=True)
final = summary_dir / (day + ".json")
tmp = summary_dir / (day + ".json.tmp")
tmp.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
tmp.replace(final)
sys.stdout.write(json.dumps(summary) + "\n")
"""


def configured_ssh() -> str:
    return os.environ.get("PACT_AW_SSH") or pact_local.text("ssh", DEFAULT_SSH)


def configured_root() -> str:
    return pact_local.text("activityRoot", REMOTE_ROOT)


def local_tz_name() -> str:
    configured = pact_local.text("timezone")
    if configured:
        return configured
    link = os.path.realpath("/etc/localtime")
    marker = "zoneinfo/"
    if marker in link:
        name = link.split(marker, 1)[1]
        if name:
            return name
    return "UTC"


def computer_name() -> str:
    try:
        out = subprocess.check_output(
            ["scutil", "--get", "ComputerName"],
            text=True,
            timeout=2,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return out.strip()


def fmt_seconds(seconds: float) -> str:
    total = max(0, int(round(seconds)))
    hours, rem = divmod(total, 3600)
    minutes, secs = divmod(rem, 60)
    if hours:
        return f"{hours}h {minutes}m"
    if minutes:
        return f"{minutes}m {secs}s"
    return f"{secs}s"


def get_json(url: str, api_key: str = ""):
    headers = {"Accept": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_events(base: str, bucket_id: str, start: datetime, end: datetime, api_key: str = "") -> list:
    events: list = []
    seen: set = set()
    cursor_end = end.astimezone(timezone.utc)
    start_s = start.astimezone(timezone.utc).isoformat()
    quoted_bucket = urllib.parse.quote(bucket_id, safe="")
    for _ in range(40):
        end_s = cursor_end.isoformat()
        url = (
            f"{base}/api/0/buckets/{quoted_bucket}/events"
            f"?limit=5000&start={urllib.parse.quote(start_s)}"
            f"&end={urllib.parse.quote(end_s)}"
        )
        batch = get_json(url, api_key)
        if not isinstance(batch, list) or not batch:
            break
        oldest = None
        for event in batch:
            event_id = event.get("id")
            key = event_id if event_id is not None else (event.get("timestamp"), event.get("duration"))
            if key in seen:
                continue
            seen.add(key)
            events.append(event)
            try:
                stamp = aw_usage.parse_ts(str(event.get("timestamp") or ""))
            except (TypeError, ValueError):
                continue
            if oldest is None or stamp < oldest:
                oldest = stamp
        if len(batch) < 5000 or oldest is None:
            break
        nxt = datetime.fromtimestamp(oldest, timezone.utc)
        if nxt >= cursor_end:
            break
        cursor_end = nxt
    return events


def gather(
    aw_url: str,
    day: str | None,
    *,
    api_key: str = "",
    platform: str = "macos",
    device_name: str | None = None,
    tz_name: str | None = None,
) -> dict:
    base = aw_url.rstrip("/")
    try:
        info = get_json(f"{base}/api/0/info", api_key)
        buckets = get_json(f"{base}/api/0/buckets/", api_key)
    except urllib.error.HTTPError as exc:
        if exc.code == 401:
            raise SystemExit(f"ActivityWatch at {base} rejected the API key") from exc
        raise SystemExit(f"ActivityWatch at {base} returned HTTP {exc.code}") from exc
    except urllib.error.URLError as exc:
        raise SystemExit(f"ActivityWatch is not reachable at {base} ({exc})") from exc
    if not isinstance(buckets, dict):
        raise SystemExit(f"Unexpected buckets payload from {base}")

    tz_name = tz_name or local_tz_name()
    try:
        tz = ZoneInfo(tz_name)
    except Exception as exc:
        raise SystemExit(f"Unknown timezone {tz_name}") from exc
    now = datetime.now(tz)
    if day:
        if not aw_usage.DAY_RE.match(day):
            raise SystemExit(f"bad day {day!r}")
        year, month, dom = (int(part) for part in day.split("-"))
        day_start = datetime(year, month, dom, tzinfo=tz)
    else:
        day_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        day = day_start.date().isoformat()
    day_end = day_start + timedelta(days=1)
    if now < day_start:
        raise SystemExit(f"{day} is in the future")
    query_start = day_start - timedelta(hours=24)
    query_end = min(day_end, now) + timedelta(minutes=1)

    window_events: dict[str, list] = {}
    afk_events: dict[str, list] = {}
    web_events: dict[str, list] = {}
    meta = []
    for bucket_id, bucket in buckets.items():
        kind = str((bucket or {}).get("type") or "")
        if kind not in {"currentwindow", "afkstatus", "web.tab.current"}:
            continue
        events = fetch_events(base, bucket_id, query_start, query_end, api_key)
        meta.append({"id": bucket_id, "type": kind, "events": len(events)})
        if kind == "currentwindow":
            window_events[bucket_id] = events
        elif kind == "afkstatus":
            afk_events[bucket_id] = events
        else:
            web_events[bucket_id] = events

    device_id = str(info.get("device_id") or "").strip()
    if not aw_usage.DEVICE_RE.match(device_id):
        raise SystemExit(f"ActivityWatch device_id is unusable: {device_id!r}")
    hostname = str(info.get("hostname") or "").strip()
    if hostname in {"", "localhost", "unknown"}:
        for bucket in buckets.values():
            host = str((bucket or {}).get("hostname") or "").strip()
            if host and host not in {"unknown", "localhost"}:
                hostname = host
                break
    if not hostname:
        hostname = "device"
    # Today stops at the current clock. A past day includes that whole day.
    as_of = now if day_start.date() == now.date() else day_end
    return aw_usage.build_report(
        device_id=device_id,
        hostname=hostname,
        device_name=device_name or computer_name() or hostname,
        platform=platform,
        tz_name=tz_name,
        day=day,
        window_events=window_events,
        afk_events=afk_events,
        web_events=web_events,
        bucket_meta=meta,
        now=as_of,
        day_start=day_start,
        day_end=day_end,
        aw_version=str(info.get("version") or ""),
    )


def summary_command(day: str) -> str:
    return (
        f"PACT_ACTIVITY_DAY={shlex.quote(day)} "
        f"PACT_ACTIVITY_ROOT={shlex.quote(configured_root())} python3 -"
    )


def upload_command(device_id: str, day: str, root: str = REMOTE_ROOT) -> str:
    if not aw_usage.DEVICE_RE.match(device_id) or not aw_usage.DAY_RE.match(day):
        raise ValueError("refusing to build a remote path from an unsafe id")
    dest = f"{root.rstrip('/')}/{device_id}"
    tmp = f"{dest}/{day}.json.tmp"
    final = f"{dest}/{day}.json"
    return (
        f"mkdir -p {shlex.quote(dest)} && "
        f"cat > {shlex.quote(tmp)} && "
        f"mv {shlex.quote(tmp)} {shlex.quote(final)}"
    )


def ssh_run(target: str, remote_cmd: str, payload: bytes | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        [
            "ssh",
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=10",
            target,
            remote_cmd,
        ],
        input=payload,
        capture_output=True,
        check=False,
    )


def ship(report: dict, ssh_target: str) -> dict:
    device_id = report["device_id"]
    day = report["day"]
    blob = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    uploaded = ssh_run(ssh_target, upload_command(device_id, day, configured_root()), blob)
    if uploaded.returncode != 0:
        err = uploaded.stderr.decode("utf-8", "replace").strip()
        raise SystemExit(f"ssh upload failed: {err or uploaded.returncode}")
    remote = summary_command(day)
    summarized = ssh_run(ssh_target, remote, SUMMARY_SCRIPT.encode("utf-8"))
    if summarized.returncode != 0:
        err = summarized.stderr.decode("utf-8", "replace").strip()
        raise SystemExit(f"ssh summary failed: {err or summarized.returncode}")
    text = summarized.stdout.decode("utf-8", "replace").strip()
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"summary was not json: {text[:400]}") from exc


def print_report(report: dict, summary: dict | None, json_mode: bool) -> None:
    out = sys.stderr if json_mode else sys.stdout
    name = report.get("device_name") or report.get("hostname")
    print(
        f"{name}  {report['day']}  "
        f"screen {fmt_seconds(report['screen_seconds'])}  "
        f"engaged {fmt_seconds(report['engaged_seconds'])}",
        file=out,
    )
    hidden = 0
    for row in report.get("by_category") or []:
        seconds = row.get("seconds") or 0
        if seconds < 60:
            hidden += 1
            continue
        print(
            f"  {row['category']:<22} {fmt_seconds(seconds):>8}"
            f"   engaged {fmt_seconds(row.get('engaged_seconds') or 0)}",
            file=out,
        )
    if hidden:
        print(f"  {hidden} more under a minute, kept in the stored file", file=out)
    if summary is not None:
        print(
            f"all devices  screen {fmt_seconds(summary.get('screen_seconds') or 0)}"
            f"  ({len(summary.get('devices') or [])} device file"
            f"{'' if len(summary.get('devices') or []) == 1 else 's'})",
            file=out,
        )
        print(
            f"stored  {configured_root()}/{report['device_id']}/{report['day']}.json",
            file=out,
        )


def install_agent() -> None:
    # LaunchAgents cannot read ~/Documents (macOS privacy). Run a copy
    # from Application Support, refreshed whenever --install is used.
    src = Path(__file__).resolve().parent
    dest = Path.home() / "Library" / "Application Support" / "pact-aw"
    dest.mkdir(parents=True, exist_ok=True)
    for name in ("aw_ship.py", "aw_usage.py"):
        (dest / name).write_text((src / name).read_text(encoding="utf-8"), encoding="utf-8")
    pact_local.install_beside(dest)
    script = dest / "aw_ship.py"
    plist_path = Path.home() / "Library" / "LaunchAgents" / f"{LABEL}.plist"
    log_path = Path.home() / "Library" / "Logs" / "pact-aw-ship.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    plist_path.parent.mkdir(parents=True, exist_ok=True)
    xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>{LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/python3</string>
    <string>{script}</string>
  </array>
  <key>StartInterval</key>
  <integer>300</integer>
  <key>RunAtLoad</key>
  <true/>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PYTHONUNBUFFERED</key>
    <string>1</string>
  </dict>
  <key>StandardOutPath</key>
  <string>{log_path}</string>
  <key>StandardErrorPath</key>
  <string>{log_path}</string>
</dict>
</plist>
"""
    plist_path.write_text(xml, encoding="utf-8")
    uid = os.getuid()
    domain = f"gui/{uid}"
    subprocess.run(["launchctl", "bootout", domain, str(plist_path)], check=False, capture_output=True)
    boot = subprocess.run(
        ["launchctl", "bootstrap", domain, str(plist_path)],
        check=False,
        capture_output=True,
        text=True,
    )
    if boot.returncode != 0:
        raise SystemExit(f"launchctl bootstrap failed: {boot.stderr.strip() or boot.stdout.strip()}")
    subprocess.run(["launchctl", "enable", f"{domain}/{LABEL}"], check=False, capture_output=True)
    print(f"installed {plist_path} (every 5 min, log {log_path})")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Ship ActivityWatch screen time to the toolkit VPS")
    parser.add_argument("--day", help="Local day YYYY-MM-DD (default: today)")
    parser.add_argument("--aw-url", default=os.environ.get("AW_URL", DEFAULT_AW))
    parser.add_argument("--ssh", default=configured_ssh())
    parser.add_argument("--dry-run", action="store_true", help="Print the report and do not upload")
    parser.add_argument("--json", action="store_true", help="Print the full report as JSON")
    parser.add_argument("--summary-only", action="store_true", help="Print the VPS total for --day and do not gather")
    parser.add_argument("--install", action="store_true", help="Run every 5 minutes from a user LaunchAgent")
    args = parser.parse_args(argv)

    if args.summary_only:
        day = args.day or datetime.now(ZoneInfo(local_tz_name())).date().isoformat()
        if not aw_usage.DAY_RE.match(day):
            raise SystemExit(f"bad day {day!r}")
        done = ssh_run(args.ssh, summary_command(day), SUMMARY_SCRIPT.encode("utf-8"))
        if done.returncode != 0:
            err = done.stderr.decode("utf-8", "replace").strip()
            raise SystemExit(f"ssh summary failed: {err or done.returncode}")
        print(done.stdout.decode("utf-8", "replace").strip())
        return 0

    report = gather(args.aw_url, args.day)
    summary = None
    if not args.dry_run:
        summary = ship(report, args.ssh)
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    print_report(report, summary, json_mode=args.json)
    if args.install:
        install_agent()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
