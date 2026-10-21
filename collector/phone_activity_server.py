#!/usr/bin/env python3
"""Receive one phone's screen-time day over Tailscale and store it.

Pact on the phone POSTs today's report here every few minutes. The process
binds the Tailscale address only, so the public droplet IP does not see it.
Tailscale already encrypts the path. A bearer token is the extra check.

  python3 collector/phone_activity_server.py
"""

from __future__ import annotations

import hmac
import json
import os
import re
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(os.environ.get("PACT_ACTIVITY_ROOT", "/var/lib/pact/activity"))
TOKEN_PATH = Path(
    os.environ.get("PACT_PHONE_TOKEN", "/var/lib/pact/phone-upload-token")
)
PORT = int(os.environ.get("PACT_PHONE_PORT", "8765"))
MAX_BYTES = 2_000_000
DEVICE_RE = re.compile(r"^[A-Za-z0-9._:-]{8,80}$")
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_UNSET = object()


def allowed_device_ids() -> frozenset[str] | None:
    """None means any well-formed device id. A file or env list restricts it.

    The installer writes allowed-devices from pact.local.json. This module does
    not read that json, so a copy on the host stays free of the repo layout.
    """
    raw = os.environ.get("PACT_ALLOWED_DEVICES", "").strip()
    if raw == "*":
        return None
    if raw:
        return frozenset(part.strip() for part in raw.split(",") if part.strip())
    path = Path(os.environ.get("PACT_ALLOWED_DEVICES_FILE", str(ROOT.parent / "allowed-devices")))
    if not path.is_file():
        return None
    found = [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]
    return frozenset(found) if found else None


class Rejected(Exception):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status


def load_token(path: Path = TOKEN_PATH) -> str:
    token = path.read_text(encoding="utf-8").strip()
    if len(token) < 16:
        raise SystemExit(f"token in {path} is missing or too short")
    return token


def tailscale_ipv4() -> str:
    done = subprocess.run(
        ["tailscale", "ip", "-4"],
        capture_output=True,
        text=True,
        check=False,
    )
    ip = (done.stdout or "").strip().split()
    if done.returncode != 0 or not ip or not ip[0].startswith("100."):
        raise SystemExit("tailscale ip -4 did not return a Tailscale address")
    return ip[0]


def accept_payload(token: str, authorization: str, raw: bytes, allowed=_UNSET) -> dict:
    expected = "Bearer " + token
    if not hmac.compare_digest(authorization or "", expected):
        raise Rejected(401, "unauthorized")
    if not raw or len(raw) > MAX_BYTES:
        raise Rejected(413, "body is empty or too large")
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise Rejected(400, "body is not json") from exc
    if not isinstance(data, dict):
        raise Rejected(400, "body is not an object")
    device = str(data.get("device_id") or "")
    day = str(data.get("day") or "")
    allowlist = allowed_device_ids() if allowed is _UNSET else allowed
    if not DEVICE_RE.match(device) or (allowlist is not None and device not in allowlist):
        raise Rejected(403, "unknown device")
    if not DAY_RE.match(day):
        raise Rejected(400, "bad day")
    if data.get("schema") != 1:
        raise Rejected(400, "bad schema")
    try:
        screen = float(data.get("screen_seconds") or 0)
    except (TypeError, ValueError) as exc:
        raise Rejected(400, "bad screen_seconds") from exc
    if screen < 0 or screen > 86400:
        raise Rejected(400, "screen_seconds out of range")
    segments = data.get("segments") or []
    if not isinstance(segments, list) or len(segments) > 8000:
        raise Rejected(400, "too many segments")
    return data


def _add(bucket: dict, key: str, seconds: object) -> None:
    bucket[key] = bucket.get(key, 0.0) + float(seconds or 0)


def _rows(totals: dict, field: str, engaged: dict | None = None) -> list:
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


def rebuild_summary(root: Path, day: str) -> dict:
    categories: dict[str, float] = {}
    cat_engaged: dict[str, float] = {}
    apps: dict[str, float] = {}
    app_engaged: dict[str, float] = {}
    domains: dict[str, float] = {}
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
        devices.append(
            {
                "device_id": data.get("device_id"),
                "hostname": data.get("hostname"),
                "device_name": data.get("device_name") or data.get("hostname"),
                "platform": data.get("platform"),
                "screen_seconds": round(float(data.get("screen_seconds") or 0), 1),
                "engaged_seconds": round(float(data.get("engaged_seconds") or 0), 1),
                "gathered_at": data.get("gathered_at"),
            }
        )
        for row in data.get("by_category") or []:
            key = str(row.get("category") or "unknown")
            _add(categories, key, row.get("seconds"))
            _add(cat_engaged, key, row.get("engaged_seconds"))
        for row in data.get("by_app") or []:
            key = str(row.get("app") or "unknown")
            _add(apps, key, row.get("seconds"))
            _add(app_engaged, key, row.get("engaged_seconds"))
        for row in data.get("by_domain") or []:
            key = str(row.get("domain") or "")
            if key:
                _add(domains, key, row.get("seconds"))
    by_category = _rows(categories, "category", cat_engaged)
    summary = {
        "day": day,
        "screen_seconds": round(sum(row["seconds"] for row in by_category), 1),
        "engaged_seconds": round(sum(row.get("engaged_seconds", 0) for row in by_category), 1),
        "devices": devices,
        "by_category": by_category,
        "by_app": _rows(apps, "app", app_engaged),
        "by_domain": _rows(domains, "domain"),
    }
    summary_dir = root / "summary"
    summary_dir.mkdir(parents=True, exist_ok=True)
    final = summary_dir / (day + ".json")
    tmp = summary_dir / (day + ".json.tmp")
    tmp.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(final)
    return summary


def store_report(data: dict, root: Path = ROOT) -> dict:
    device = str(data["device_id"])
    day = str(data["day"])
    dest = root / device
    dest.mkdir(parents=True, exist_ok=True)
    final = dest / f"{day}.json"
    tmp = dest / f"{day}.json.tmp"
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(final)
    return rebuild_summary(root, day)


def make_handler(token: str):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_GET(self) -> None:  # noqa: N802
            if self.path.split("?", 1)[0] != "/health":
                self._send(404, b"not found\n")
                return
            self._send(200, b"ok\n")

        def do_POST(self) -> None:  # noqa: N802
            if self.path.split("?", 1)[0] != "/activity":
                self._send(404, b"not found\n")
                return
            try:
                length = int(self.headers.get("Content-Length") or "0")
            except ValueError:
                self._send(400, b"bad length\n")
                return
            if length <= 0 or length > MAX_BYTES:
                self._send(413, b"body is empty or too large\n")
                return
            raw = self.rfile.read(length)
            try:
                data = accept_payload(token, self.headers.get("Authorization") or "", raw)
                summary = store_report(data)
            except Rejected as exc:
                self._send(exc.status, (str(exc) + "\n").encode("utf-8"))
                return
            except OSError:
                self._send(500, b"store failed\n")
                return
            body = json.dumps(
                {
                    "ok": True,
                    "day": data["day"],
                    "screen_seconds": data.get("screen_seconds"),
                    "devices": len(summary.get("devices") or []),
                }
            ).encode("utf-8")
            self._send(200, body, "application/json")

        def _send(self, status: int, body: bytes, content_type: str = "text/plain") -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt: str, *args) -> None:
            sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    return Handler


def serve(host: str, port: int, token: str) -> None:
    pid_path = ROOT.parent / "phone-activity-server.pid"
    pid_path.write_text(str(os.getpid()) + "\n", encoding="utf-8")
    httpd = ThreadingHTTPServer((host, port), make_handler(token))
    sys.stderr.write(f"phone activity listening on {host}:{port}\n")
    httpd.serve_forever()


def main() -> int:
    token = load_token()
    host = os.environ.get("PACT_PHONE_BIND") or tailscale_ipv4()
    serve(host, PORT, token)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
