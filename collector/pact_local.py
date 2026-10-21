"""Read gitignored pact.local.json. A missing file means generic defaults."""

from __future__ import annotations

import json
import os
from functools import lru_cache
from pathlib import Path


def _candidates() -> list[Path]:
    here = Path(__file__).resolve().parent
    return [
        here / "pact.local.json",
        here.parent / "pact.local.json",
    ]


@lru_cache(maxsize=1)
def data() -> dict:
    for path in _candidates():
        if not path.is_file():
            continue
        try:
            loaded = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}
        return loaded if isinstance(loaded, dict) else {}
    return {}


def text(key: str, default: str = "") -> str:
    value = data().get(key)
    if isinstance(value, str) and value.strip():
        return value.strip()
    return default


def strings(key: str) -> list[str]:
    value = data().get(key)
    if not isinstance(value, list):
        return []
    found = []
    for item in value:
        if isinstance(item, str) and item.strip():
            found.append(item.strip())
    return found


def install_beside(dest: Path) -> None:
    """Copy this module, and the local file when present, next to an installed script.

    LaunchAgents cannot read ~/Documents, so the installed copy needs its own file.
    """
    dest.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).resolve()
    (dest / "pact_local.py").write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
    for path in _candidates():
        if not path.is_file():
            continue
        target = dest / "pact.local.json"
        if path.resolve() == target.resolve():
            return
        target.write_text(path.read_text(encoding="utf-8"), encoding="utf-8")
        os.chmod(target, 0o600)
        return
