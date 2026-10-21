#!/usr/bin/env python3
"""Ship ActivityWatch from a USB-connected Android phone to the toolkit VPS.

The ActivityWatch app on the phone keeps its server on 127.0.0.1:5600.
That server runs only while the app's foreground service is up. Android can
restart the watchers without that service, so a plugged-in phone may still
refuse port 5600. This opens the ActivityWatch screen long enough to start
the server, then returns to the app that was in front.

This forwards that port over adb, reads the same buckets the app recorded,
and replaces today's file for that phone next to the Mac's file.

Nothing is sent when no phone is plugged in.

  python3 collector/aw_android.py --dry-run
  python3 collector/aw_android.py
  python3 collector/aw_android.py --install
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import aw_ship  # noqa: E402

AW_PACKAGE = "net.activitywatch.android"
AW_ACTIVITY = f"{AW_PACKAGE}/.MainActivity"
PHONE_SERVER_PORT = 5600
LOCAL_PORT = 5601
TOKEN_PATH = Path.home() / ".config" / "pact-aw" / "android-token"
LABEL = "app.pact.aw-ship-android"
SERVER_WAIT_SECONDS = 20
_TOKEN_RE = re.compile(r"token=([0-9a-fA-F-]{36})")
_FOCUS_RE = re.compile(
    r"topResumedActivity=ActivityRecord\{[^\s]+\s+\S+\s+(\S+/\S+)"
)


def adb_executable() -> str:
    candidates = [
        os.environ.get("ADB", "").strip(),
        "/opt/homebrew/bin/adb",
        "/usr/local/bin/adb",
        str(Path.home() / "Library/Android/sdk/platform-tools/adb"),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    return shutil.which("adb") or ""


def adb(serial: str, *args: str, check: bool = True) -> subprocess.CompletedProcess:
    binary = adb_executable()
    if not binary:
        raise SystemExit("adb is not installed")
    return subprocess.run(
        [binary, "-s", serial, *args],
        capture_output=True,
        text=True,
        check=check,
    )


def usb_serials() -> list[str]:
    binary = adb_executable()
    if not binary:
        return []
    try:
        done = subprocess.run([binary, "devices", "-l"], capture_output=True, text=True, check=False)
    except FileNotFoundError:
        return []
    serials = []
    for line in done.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device" and "usb:" in line:
            serials.append(parts[0])
    return serials


def phone_with_activitywatch() -> str:
    found = []
    for serial in usb_serials():
        probe = adb(serial, "shell", "pm", "path", AW_PACKAGE, check=False)
        if probe.returncode == 0 and "package:" in probe.stdout:
            found.append(serial)
    if len(found) == 1:
        return found[0]
    if len(found) > 1:
        raise SystemExit(f"More than one phone has ActivityWatch: {', '.join(found)}")
    return ""


def phone_prop(serial: str, name: str) -> str:
    done = adb(serial, "shell", "getprop", name, check=False)
    return done.stdout.strip()


def phone_setting(serial: str, name: str) -> str:
    done = adb(serial, "shell", "settings", "get", "global", name, check=False)
    value = done.stdout.strip()
    if value == "null":
        return ""
    return value


def read_saved_token() -> str:
    try:
        return TOKEN_PATH.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def save_token(token: str) -> None:
    TOKEN_PATH.parent.mkdir(parents=True, exist_ok=True)
    TOKEN_PATH.write_text(token + "\n", encoding="utf-8")
    os.chmod(TOKEN_PATH, 0o600)


def token_from_logcat(serial: str) -> str:
    done = adb(serial, "logcat", "-d", check=False)
    found = _TOKEN_RE.findall(done.stdout)
    return found[-1] if found else ""


def token_opens_buckets(token: str) -> bool:
    try:
        payload = aw_ship.get_json(
            f"http://127.0.0.1:{LOCAL_PORT}/api/0/buckets/",
            token,
        )
    except urllib.error.URLError:
        return False
    return isinstance(payload, dict) and "message" not in payload


def resolve_token(serial: str) -> str:
    candidates = []
    saved = read_saved_token()
    if saved:
        candidates.append(saved)
    fresh = token_from_logcat(serial)
    if fresh and fresh not in candidates:
        candidates.append(fresh)
    for token in candidates:
        if token_opens_buckets(token):
            save_token(token)
            return token
    raise SystemExit(
        "ActivityWatch on the phone refused the request. Open the ActivityWatch app once, "
        "then run this again so the local key can be read from the phone log."
    )


def proc_net_has_listen(text: str, port: int) -> bool:
    """True when /proc/net/tcp output shows `port` in TCP LISTEN (state 0A)."""
    want = f"{port:04X}"
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 4 or ":" not in parts[1]:
            continue
        local_port = parts[1].rsplit(":", 1)[-1].upper()
        if local_port == want and parts[3].upper() == "0A":
            return True
    return False


def foreground_component(dumpsys: str) -> str:
    match = _FOCUS_RE.search(dumpsys)
    return match.group(1) if match else ""


def server_listening(serial: str) -> bool:
    done = adb(serial, "shell", "cat", "/proc/net/tcp", "/proc/net/tcp6", check=False)
    return proc_net_has_listen(done.stdout, PHONE_SERVER_PORT)


def ensure_phone_server(serial: str) -> None:
    """Start ActivityWatch's on-device server if port 5600 is closed.

    The server lives in BackgroundService, which is not exported, so adb cannot
    start it directly. Opening MainActivity is what starts that service.
    """
    if server_listening(serial):
        return
    focus = adb(serial, "shell", "dumpsys", "activity", "activities", check=False)
    previous = foreground_component(focus.stdout)
    started = adb(serial, "shell", "am", "start", "-n", AW_ACTIVITY, check=False)
    if started.returncode != 0:
        detail = (started.stderr or started.stdout).strip()
        raise SystemExit(f"Could not open ActivityWatch on the phone: {detail}")
    deadline = time.monotonic() + SERVER_WAIT_SECONDS
    while time.monotonic() < deadline:
        if server_listening(serial):
            break
        time.sleep(0.4)
    else:
        raise SystemExit(
            "ActivityWatch on the phone is not serving data. "
            "Open the ActivityWatch app once, then run this again."
        )
    if previous.startswith(AW_PACKAGE + "/"):
        return
    if previous:
        restored = adb(serial, "shell", "am", "start", "-n", previous, check=False)
        detail = (restored.stderr or restored.stdout or "")
        if restored.returncode == 0 and "Error:" not in detail:
            return
    adb(serial, "shell", "input", "keyevent", "KEYCODE_HOME", check=False)


def gather_phone(serial: str, day: str | None):
    ensure_phone_server(serial)
    forwarded = adb(serial, "forward", f"tcp:{LOCAL_PORT}", f"tcp:{PHONE_SERVER_PORT}", check=False)
    if forwarded.returncode != 0:
        detail = (forwarded.stderr or forwarded.stdout).strip()
        raise SystemExit(f"adb forward failed: {detail}")
    token = resolve_token(serial)
    tz_name = phone_prop(serial, "persist.sys.timezone") or aw_ship.local_tz_name()
    device_name = phone_setting(serial, "device_name") or "Android"
    return aw_ship.gather(
        f"http://127.0.0.1:{LOCAL_PORT}",
        day,
        api_key=token,
        platform="android",
        device_name=device_name,
        tz_name=tz_name,
    )


def install_agent() -> None:
    src = Path(__file__).resolve().parent
    dest = Path.home() / "Library" / "Application Support" / "pact-aw"
    dest.mkdir(parents=True, exist_ok=True)
    for name in ("aw_android.py", "aw_ship.py", "aw_usage.py"):
        (dest / name).write_text((src / name).read_text(encoding="utf-8"), encoding="utf-8")
    aw_ship.pact_local.install_beside(dest)
    script = dest / "aw_android.py"
    plist_path = Path.home() / "Library" / "LaunchAgents" / f"{LABEL}.plist"
    log_path = Path.home() / "Library" / "Logs" / "pact-aw-ship-android.log"
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
    print(f"installed {plist_path} (every 5 min while the phone is plugged in, log {log_path})")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Ship Android ActivityWatch screen time to the toolkit VPS")
    parser.add_argument("--day", help="Phone-local day YYYY-MM-DD (default: today)")
    parser.add_argument("--ssh", default=aw_ship.configured_ssh())
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args(argv)

    serial = phone_with_activitywatch()
    if not serial:
        if sys.stdout.isatty() and not args.install:
            print("No USB phone with ActivityWatch is connected")
        if args.install:
            install_agent()
        return 0

    report = gather_phone(serial, args.day)
    summary = None
    if not args.dry_run:
        summary = aw_ship.ship(report, args.ssh)
    if args.json:
        import json

        print(json.dumps(report, ensure_ascii=False, indent=2))
    aw_ship.print_report(report, summary, json_mode=args.json)
    if args.install:
        install_agent()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
