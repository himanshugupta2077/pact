#!/bin/bash
# Build PactScreen.app. Pass --install to copy it into ~/Applications and open it.
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PY'
import json
from pathlib import Path
source = Path("..") / "pact.local.json"
if not source.is_file():
    raise SystemExit(0)
data = json.loads(source.read_text(encoding="utf-8"))
site = {
    "ssh": (data.get("ssh") or "").strip(),
    "activityRoot": (data.get("activityRoot") or "").strip(),
    "timezone": (data.get("timezone") or "").strip(),
}
dest = Path.home() / "Library" / "Application Support" / "PactScreen"
dest.mkdir(parents=True, exist_ok=True)
path = dest / "site.json"
path.write_text(json.dumps(site, indent=2) + "\n", encoding="utf-8")
path.chmod(0o600)
PY
swift build -c release
bin="$(swift build -c release --show-bin-path)"
rm -rf PactScreen.app
mkdir -p PactScreen.app/Contents/MacOS PactScreen.app/Contents/Resources
cp "$bin/PactScreen" PactScreen.app/Contents/MacOS/PactScreen
cp fetch_day.py PactScreen.app/Contents/Resources/fetch_day.py
cp Info.plist PactScreen.app/Contents/Info.plist
chmod +x PactScreen.app/Contents/MacOS/PactScreen
codesign --force --sign - PactScreen.app
if [[ "${1:-}" == "--install" ]]; then
  mkdir -p "$HOME/Applications"
  dest="$HOME/Applications/PactScreen.app"
  if pgrep -x PactScreen >/dev/null 2>&1; then
    killall PactScreen || true
    sleep 0.4
  fi
  rm -rf "$dest"
  cp -R PactScreen.app "$dest"
  codesign --force --sign - "$dest"
  open "$dest"
fi
