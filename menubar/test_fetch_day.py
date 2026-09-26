#!/usr/bin/env python3
"""Tests for the read-only day summary the menu bar app runs on the VPS."""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

SCRIPT = Path(__file__).with_name("fetch_day.py")


def load():
    spec = importlib.util.spec_from_file_location("fetch_day", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fetch_day = load()
TZ = ZoneInfo("Asia/Kolkata")


def device(name: str, seconds: float, segments: list[dict], categories: list[dict] | None = None) -> dict:
    return {
        "schema": 1,
        "source": "activitywatch",
        "device_id": "device-" + name,
        "device_name": name,
        "hostname": name,
        "platform": "macos",
        "tz": "Asia/Kolkata",
        "day": "2026-09-25",
        "screen_seconds": seconds,
        "engaged_seconds": seconds,
        "gathered_at": "2026-09-25T12:00:00+05:30",
        "by_category": categories
        or [{"category": "youtube", "seconds": seconds, "engaged_seconds": 1}],
        "by_app": [{"app": "Zen", "seconds": seconds}],
        "segments": segments,
    }


class FetchDayTest(unittest.TestCase):
    def test_hour_split_crosses_the_hour(self):
        start = datetime(2026, 9, 25, 10, 30, tzinfo=TZ)
        end = datetime(2026, 9, 25, 11, 15, tzinfo=TZ)
        self.assertEqual(fetch_day.split_hours(start, end), [(10, 1800.0), (11, 900.0)])

    def test_collect_sums_hours_and_skips_summary_and_titles(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            mac = root / "mac-id"
            mac.mkdir()
            phone = root / "phone-id"
            phone.mkdir()
            (root / "summary").mkdir()
            secret = "SECRET_TITLE_SHOULD_NOT_LEAK"
            (mac / "2026-09-25.json").write_text(
                json.dumps(
                    device(
                        "MacBook",
                        2700,
                        [
                            {
                                "start": "2026-09-25T10:30:00+05:30",
                                "end": "2026-09-25T11:15:00+05:30",
                                "seconds": 2700,
                                "category": "app:ghostty",
                                "title": secret,
                                "url": "https://secret.example/watch",
                            }
                        ],
                        [{"category": "app:ghostty", "seconds": 2700}],
                    )
                ),
                encoding="utf-8",
            )
            (phone / "2026-09-25.json").write_text(
                json.dumps(
                    device(
                        "Phone",
                        300,
                        [
                            {
                                "start": "2026-09-25T11:00:00+05:30",
                                "end": "2026-09-25T11:05:00+05:30",
                                "seconds": 300,
                                "category": "youtube",
                                "title": secret,
                            }
                        ],
                    )
                ),
                encoding="utf-8",
            )
            (phone / "2026-09-24.json").write_text("{}", encoding="utf-8")
            (root / "summary" / "2026-09-25.json").write_text(
                json.dumps({"screen_seconds": 999999, "device_name": "summary"}),
                encoding="utf-8",
            )
            before = sorted(path.name for path in root.rglob("*"))
            report = fetch_day.collect(root, "2026-09-25")
            self.assertEqual(sorted(path.name for path in root.rglob("*")), before)
            self.assertEqual([item["device_name"] for item in report["devices"]], ["MacBook", "Phone"])
            mac_hours = {row["hour"]: row["categories"] for row in report["devices"][0]["hours"]}
            self.assertEqual(mac_hours[10]["app:ghostty"], 1800.0)
            self.assertEqual(mac_hours[11]["app:ghostty"], 900.0)
            phone_hours = {row["hour"]: row["categories"] for row in report["devices"][1]["hours"]}
            self.assertEqual(phone_hours[11]["youtube"], 300.0)
            blob = json.dumps(report)
            self.assertNotIn(secret, blob)
            self.assertNotIn("secret.example", blob)
            self.assertNotIn("999999", blob)

    def test_cli_rejects_a_bad_day_and_prints_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = dict(os.environ)
            env["PACT_ACTIVITY_DAY"] = "nope"
            env["PACT_ACTIVITY_ROOT"] = tmp
            bad = subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True)
            self.assertEqual(bad.returncode, 1)
            self.assertEqual(bad.stdout, "")

            env["PACT_ACTIVITY_DAY"] = "2026-09-25"
            good = subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True)
            self.assertEqual(good.returncode, 0, good.stderr)
            payload = json.loads(good.stdout)
            self.assertEqual(payload, {"day": "2026-09-25", "devices": []})

    def test_week_is_monday_through_sunday(self):
        self.assertEqual(
            fetch_day.week_days("2026-09-25"),
            [
                "2026-09-21",
                "2026-09-22",
                "2026-09-23",
                "2026-09-24",
                "2026-09-25",
                "2026-09-26",
                "2026-09-27",
            ],
        )
        self.assertEqual(fetch_day.week_days("2026-09-21")[0], "2026-09-21")
        self.assertEqual(fetch_day.week_days("2026-09-27")[-1], "2026-09-27")

    def test_cli_week_reads_each_day_and_writes_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            mac = root / "mac-id"
            mac.mkdir()
            secret = "SECRET_TITLE_SHOULD_NOT_LEAK"
            (mac / "2026-09-21.json").write_text(
                json.dumps(device("Monday", 100, [{"start": "2026-09-21T10:00:00+05:30", "end": "2026-09-21T10:01:00+05:30", "title": secret, "category": "youtube"}])),
                encoding="utf-8",
            )
            (mac / "2026-09-25.json").write_text(json.dumps(device("Friday", 50, [])), encoding="utf-8")
            before = sorted(path.name for path in root.rglob("*"))
            env = dict(os.environ)
            env["PACT_ACTIVITY_DAY"] = "2026-09-25"
            env["PACT_ACTIVITY_ROOT"] = tmp
            env["PACT_ACTIVITY_WEEK"] = "1"
            good = subprocess.run([sys.executable, str(SCRIPT)], env=env, capture_output=True, text=True)
            self.assertEqual(good.returncode, 0, good.stderr)
            self.assertEqual(sorted(path.name for path in root.rglob("*")), before)
            payload = json.loads(good.stdout)
            self.assertEqual([item["day"] for item in payload["days"]], fetch_day.week_days("2026-09-25"))
            by_day = {item["day"]: item["devices"] for item in payload["days"]}
            self.assertEqual(len(by_day["2026-09-21"]), 1)
            self.assertEqual(len(by_day["2026-09-25"]), 1)
            self.assertEqual(by_day["2026-09-22"], [])
            self.assertNotIn(secret, good.stdout)


if __name__ == "__main__":
    unittest.main()
