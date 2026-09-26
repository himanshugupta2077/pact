#!/usr/bin/env python3
"""Install the Tailscale receiver that Pact on the phone posts to.

The toolkit service is not restarted. This writes under /var/lib/pact,
which the toolkit user already owns, and leaves a process listening on the
Tailscale address only.

The bearer token stays in ~/.config/pact-aw/phone-upload-token (mode 0600).
Pass --adb to copy it into a debug Pact install on a USB phone.
"""

from __future__ import annotations

import argparse
import os
import secrets
import subprocess
import sys
from pathlib import Path

SSH = os.environ.get("PACT_AW_SSH", "pact@pact-host")
REMOTE_DIR = "/var/lib/pact"
REMOTE_SCRIPT = f"{REMOTE_DIR}/phone_activity_server.py"
REMOTE_TOKEN = f"{REMOTE_DIR}/phone-upload-token"
REMOTE_LOG = f"{REMOTE_DIR}/phone-activity-server.log"
REMOTE_PID = f"{REMOTE_DIR}/phone-activity-server.pid"
TOKEN_PATH = Path.home() / ".config" / "pact-aw" / "phone-upload-token"
HERE = Path(__file__).resolve().parent
MARKER = "pact-phone-activity"
ADB = Path.home() / "Library/Android/sdk/platform-tools/adb"


def ssh(remote: str, input_bytes: bytes | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", SSH, remote],
        input=input_bytes,
        capture_output=True,
        check=False,
    )


def require(done: subprocess.CompletedProcess, what: str) -> None:
    if done.returncode != 0:
        err = (done.stderr or done.stdout).decode("utf-8", "replace").strip()
        raise SystemExit(f"{what} failed: {err or done.returncode}")


def token() -> str:
    if TOKEN_PATH.is_file():
        value = TOKEN_PATH.read_text(encoding="utf-8").strip()
        if len(value) >= 16:
            return value
    TOKEN_PATH.parent.mkdir(parents=True, exist_ok=True)
    value = secrets.token_hex(32)
    TOKEN_PATH.write_text(value + "\n", encoding="utf-8")
    os.chmod(TOKEN_PATH, 0o600)
    return value


def install_remote(value: str) -> None:
    script = (HERE / "phone_activity_server.py").read_bytes()
    require(
        ssh(f"mkdir -p {REMOTE_DIR} && cat > {REMOTE_SCRIPT}.tmp && mv {REMOTE_SCRIPT}.tmp {REMOTE_SCRIPT}", script),
        "upload script",
    )
    require(
        ssh(f"cat > {REMOTE_TOKEN}.tmp && mv {REMOTE_TOKEN}.tmp {REMOTE_TOKEN} && chmod 600 {REMOTE_TOKEN}", (value + "\n").encode()),
        "upload token",
    )
    cron = (
        f"# {MARKER}\n"
        f"@reboot sleep 15 && /usr/bin/python3 {REMOTE_SCRIPT} >> {REMOTE_LOG} 2>&1\n"
        f"*/5 * * * * /usr/bin/pgrep -f '^/usr/bin/python3 {REMOTE_SCRIPT}$' >/dev/null "
        f"|| /usr/bin/python3 {REMOTE_SCRIPT} >> {REMOTE_LOG} 2>&1\n"
    )
    # Keep the existing backup job. Drop only a previous copy of this receiver.
    remote = r"""
import subprocess
existing = subprocess.run(["crontab", "-l"], capture_output=True, text=True)
text = existing.stdout if existing.returncode == 0 else ""
kept = [
    line for line in text.splitlines()
    if "phone_activity_server.py" not in line and line.strip() != "# pact-phone-activity"
]
body = "\n".join(kept).strip()
extra = """ + repr(cron) + r"""
new = (body + "\n\n" + extra).strip() + "\n"
proc = subprocess.run(["crontab", "-"], input=new, text=True, capture_output=True)
if proc.returncode != 0:
    raise SystemExit(proc.stderr.strip() or "crontab failed")
"""
    require(ssh("python3 -", remote.encode()), "crontab")
    start = (
        f"if [ -f {REMOTE_PID} ]; then kill $(cat {REMOTE_PID}) 2>/dev/null || true; sleep 0.4; fi; "
        f"setsid /usr/bin/python3 {REMOTE_SCRIPT} >> {REMOTE_LOG} 2>&1 < /dev/null & "
        f"sleep 0.6; tail -n 8 {REMOTE_LOG}"
    )
    done = ssh(start)
    sys.stdout.write(done.stdout.decode("utf-8", "replace"))
    err = done.stderr.decode("utf-8", "replace").strip()
    if err:
        sys.stderr.write(err + "\n")


def install_adb() -> None:
    if not ADB.is_file():
        raise SystemExit(f"adb not found at {ADB}")
    # Quotes have to survive adb's remote shell, or the redirect runs as the
    # shell user and never reaches the app's files directory.
    subprocess.run(
        [str(ADB), "shell", "run-as", "app.pact", "mkdir", "files/ship"],
        capture_output=True,
        check=False,
    )
    copy = subprocess.run(
        [str(ADB), "shell", "run-as app.pact sh -c 'cat > files/ship/token && chmod 600 files/ship/token'"],
        input=TOKEN_PATH.read_bytes(),
        capture_output=True,
        check=False,
    )
    require(copy, "copy token into Pact")
    print("token copied into the Pact app")


def main() -> int:
    parser = argparse.ArgumentParser(description="Install the phone screen-time receiver")
    parser.add_argument("--adb", action="store_true", help="copy the token into a debug Pact install")
    args = parser.parse_args()
    value = token()
    install_remote(value)
    if args.adb:
        install_adb()
    print(f"receiver installed, token {TOKEN_PATH}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
