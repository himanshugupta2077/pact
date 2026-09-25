"""Fixture tests for ActivityWatch → screen time. No live watcher, no VPS."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent))

import aw_ship
import aw_usage

TZ = ZoneInfo("Asia/Kolkata")
DAY_START = datetime(2026, 9, 25, 0, 0, tzinfo=TZ)
NOW = datetime(2026, 9, 25, 20, 0, tzinfo=TZ)


def iso(dt: datetime) -> str:
    return dt.isoformat()


def at(hour: int, minute: int = 0, second: int = 0) -> datetime:
    return DAY_START + timedelta(hours=hour, minutes=minute, seconds=second)


def event(start: datetime, duration: float, **data) -> dict:
    return {"timestamp": iso(start), "duration": duration, "data": data}


def report(
    *,
    windows: dict | None = None,
    afk: dict | None = None,
    web: dict | None = None,
    now: datetime | None = None,
) -> dict:
    return aw_usage.build_report(
        device_id="device-12345678",
        hostname="mac.local",
        device_name="Test Mac",
        tz_name="Asia/Kolkata",
        day="2026-09-25",
        window_events=windows or {},
        afk_events=afk or {},
        web_events=web or {},
        bucket_meta=[],
        now=now or NOW,
        day_start=DAY_START,
        day_end=DAY_START + timedelta(days=1),
    )


def cat(rep: dict, name: str) -> float:
    for row in rep["by_category"]:
        if row["category"] == name:
            return row["seconds"]
    return 0.0


class UsageTests(unittest.TestCase):
    def test_domain_rules_do_not_match_lookalikes(self):
        self.assertEqual(aw_usage.match_domain("youtube.com"), "youtube")
        self.assertEqual(aw_usage.match_domain("m.youtube.com"), "youtube")
        self.assertEqual(aw_usage.match_domain("notx.com"), "")
        self.assertEqual(aw_usage.match_domain("x.com"), "twitter")
        self.assertEqual(aw_usage.domain_of("https://www.youtube.com/watch?v=abc"), "youtube.com")
        self.assertEqual(aw_usage.domain_of("about:blank"), "")

    def test_terminal_title_mentioning_youtube_is_not_youtube(self):
        self.assertEqual(
            aw_usage.classify("Ghostty", "notes about YouTube", "", ""),
            "app:ghostty",
        )

    def test_safari_title_counts_as_youtube_without_extension(self):
        self.assertEqual(
            aw_usage.classify("Safari", "Lofi - YouTube", "", "safari"),
            "youtube",
        )

    def test_idle_video_counts_and_idle_editor_does_not(self):
        start = at(15)
        rep = report(
            windows={
                "aw-watcher-window_mac.local": [
                    event(start, 100, app="JioHotstar", title=""),
                    event(start + timedelta(seconds=100), 100, app="Ghostty", title="code"),
                ]
            },
            afk={
                "aw-watcher-afk_mac.local": [
                    event(start, 200, status="afk"),
                ]
            },
        )
        self.assertEqual(cat(rep, "hotstar"), 100.0)
        self.assertEqual(cat(rep, "app:ghostty"), 0.0)
        self.assertEqual(rep["screen_seconds"], 100.0)
        self.assertEqual(rep["engaged_seconds"], 0.0)

    def test_engaged_editor_counts(self):
        start = at(15)
        rep = report(
            windows={
                "aw-watcher-window_mac.local": [
                    event(start, 100, app="Ghostty", title="code"),
                ]
            },
            afk={
                "aw-watcher-afk_mac.local": [
                    event(start, 40, status="not-afk"),
                    event(start + timedelta(seconds=40), 60, status="afk"),
                ]
            },
        )
        self.assertEqual(cat(rep, "app:ghostty"), 40.0)
        self.assertEqual(rep["engaged_seconds"], 40.0)

    def test_zen_browser_uses_firefox_tab_urls(self):
        start = at(16)
        rep = report(
            windows={
                "aw-watcher-window_mac.local": [
                    event(start, 100, app="Zen", title="a video - YouTube"),
                ]
            },
            web={
                "aw-watcher-web-firefox_mac.local": [
                    event(
                        start,
                        100,
                        url="https://www.youtube.com/watch?v=abc",
                        title="a video - YouTube",
                    )
                ]
            },
            afk={
                "aw-watcher-afk_mac.local": [
                    event(start, 100, status="afk"),
                ]
            },
        )
        self.assertEqual(cat(rep, "youtube"), 100.0)
        self.assertEqual(rep["by_app"][0]["app"], "Zen")
        self.assertEqual(rep["screen_seconds"], 100.0)

    def test_firefox_youtube_is_not_double_counted(self):
        start = at(16)
        rep = report(
            windows={
                "aw-watcher-window_mac.local": [
                    event(start, 100, app="Firefox", title="a video"),
                ]
            },
            web={
                "aw-watcher-web-firefox_mac.local": [
                    event(
                        start,
                        100,
                        url="https://www.youtube.com/watch?v=abc",
                        title="a video - YouTube",
                    )
                ]
            },
            afk={
                "aw-watcher-afk_mac.local": [
                    event(start, 100, status="afk"),
                ]
            },
        )
        self.assertEqual(rep["screen_seconds"], 100.0)
        self.assertEqual(cat(rep, "youtube"), 100.0)
        self.assertEqual(rep["by_domain"][0]["domain"], "youtube.com")
        self.assertEqual(rep["by_app"][0]["app"], "Firefox")

    def test_overlapping_buckets_are_not_summed(self):
        start = at(12)
        rep = report(
            windows={
                "aw-watcher-window_old": [
                    event(start, 100, app="Ghostty", title="a"),
                ],
                "aw-watcher-window_new": [
                    event(start, 100, app="Ghostty", title="a"),
                ],
            },
            afk={
                "afk": [event(start, 100, status="not-afk")],
            },
        )
        self.assertEqual(rep["screen_seconds"], 100.0)

    def test_later_window_wins_overlap(self):
        start = at(12)
        rep = report(
            windows={
                "one": [
                    event(start, 100, app="Ghostty", title="a"),
                    event(start + timedelta(seconds=50), 100, app="Firefox", title="docs"),
                ]
            },
            afk={"afk": [event(start, 200, status="not-afk")]},
        )
        self.assertEqual(cat(rep, "app:ghostty"), 50.0)
        self.assertEqual(cat(rep, "app:firefox"), 100.0)
        self.assertEqual(rep["screen_seconds"], 150.0)

    def test_lock_screen_never_counts(self):
        start = at(11)
        rep = report(
            windows={
                "w": [event(start, 500, app="loginwindow", title="")],
            },
            afk={"a": [event(start, 500, status="not-afk")]},
        )
        self.assertEqual(rep["screen_seconds"], 0.0)

    def test_time_before_afk_watcher_started_still_counts(self):
        morning = at(9)
        afternoon = at(16)
        rep = report(
            windows={"w": [event(morning, 60, app="Ghostty", title="early")]},
            afk={"a": [event(afternoon, 60, status="not-afk")]},
        )
        self.assertEqual(cat(rep, "app:ghostty"), 60.0)

    def test_no_afk_data_counts_foreground(self):
        start = at(10)
        rep = report(
            windows={"w": [event(start, 30, app="Ghostty", title="x")]},
        )
        self.assertFalse(rep["afk_available"])
        self.assertEqual(rep["screen_seconds"], 30.0)
        self.assertEqual(rep["engaged_seconds"], 30.0)

    def test_zero_duration_fills_a_short_gap_only(self):
        start = at(13)
        rep = report(
            windows={
                "w": [
                    event(start, 0, app="Ghostty", title="a"),
                    event(start + timedelta(seconds=0.4), 1, app="Ghostty", title="a"),
                ]
            },
            afk={"a": [event(start, 5, status="not-afk")]},
        )
        self.assertGreaterEqual(rep["screen_seconds"], 1.4)
        self.assertLess(rep["screen_seconds"], 2.0)

    def test_web_zero_duration_does_not_cover_a_long_hole(self):
        start = at(14)
        rep = report(
            windows={"w": [event(start, 100, app="Firefox", title="New Tab")]},
            web={
                "aw-watcher-web-firefox_mac.local": [
                    event(start - timedelta(hours=2), 0, url="https://www.youtube.com/watch?v=old", title="old"),
                ]
            },
            afk={"a": [event(start, 100, status="not-afk")]},
        )
        self.assertEqual(cat(rep, "youtube"), 0.0)
        self.assertEqual(cat(rep, "app:firefox"), 100.0)

    def test_midnight_split_keeps_only_today(self):
        start = DAY_START - timedelta(minutes=30)
        rep = report(
            windows={"w": [event(start, 60 * 60, app="JioHotstar", title="")]},
            afk={"a": [event(start, 60 * 60, status="afk")]},
        )
        self.assertEqual(cat(rep, "hotstar"), 30 * 60)

    def test_category_rows_sum_to_screen_time(self):
        start = at(18)
        rep = report(
            windows={
                "w": [
                    event(start, 80, app="JioHotstar", title=""),
                    event(start + timedelta(seconds=80), 20, app="Firefox", title="gh"),
                ]
            },
            web={
                "aw-watcher-web-firefox_mac.local": [
                    event(start + timedelta(seconds=80), 20, url="https://github.com/himanshugupta2077/pact", title="pact"),
                ]
            },
            afk={"a": [event(start, 100, status="not-afk")]},
        )
        self.assertEqual(cat(rep, "hotstar"), 80.0)
        self.assertEqual(cat(rep, "github"), 20.0)
        self.assertEqual(sum(row["seconds"] for row in rep["by_category"]), rep["screen_seconds"])

    def test_two_devices_sum_youtube(self):
        summary = aw_usage.summarize_payloads(
            "2026-09-25",
            [
                {
                    "device_id": "device-mac-1111",
                    "hostname": "mac.local",
                    "device_name": "Mac",
                    "platform": "macos",
                    "screen_seconds": 100,
                    "engaged_seconds": 10,
                    "by_category": [{"category": "youtube", "seconds": 100, "engaged_seconds": 10}],
                    "by_app": [{"app": "Firefox", "seconds": 100, "engaged_seconds": 10}],
                    "by_domain": [{"domain": "youtube.com", "seconds": 100}],
                },
                {
                    "device_id": "device-phone-22",
                    "hostname": "phone",
                    "platform": "android",
                    "screen_seconds": 50,
                    "engaged_seconds": 50,
                    "by_category": [{"category": "youtube", "seconds": 50, "engaged_seconds": 50}],
                    "by_app": [{"app": "YouTube", "seconds": 50, "engaged_seconds": 50}],
                    "by_domain": [],
                },
            ],
        )
        self.assertEqual(summary["screen_seconds"], 150.0)
        self.assertEqual(summary["by_category"][0]["category"], "youtube")
        self.assertEqual(summary["by_category"][0]["seconds"], 150.0)

    def test_upload_command_quotes_the_path(self):
        cmd = aw_ship.upload_command("device-12345678", "2026-09-25")
        self.assertIn("/var/lib/pact/activity/device-12345678", cmd)
        self.assertIn("2026-09-25.json", cmd)
        with self.assertRaises(ValueError):
            aw_ship.upload_command("bad id", "2026-09-25")

    def test_remote_summary_script_sums_device_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for device, seconds in (("device-mac-1111", 100), ("device-phone-22", 40)):
                dest = root / device
                dest.mkdir()
                payload = {
                    "device_id": device,
                    "hostname": device,
                    "platform": "macos",
                    "screen_seconds": seconds,
                    "engaged_seconds": seconds / 2,
                    "by_category": [
                        {
                            "category": "youtube",
                            "seconds": seconds,
                            "engaged_seconds": seconds / 2,
                        }
                    ],
                    "by_app": [{"app": "Firefox", "seconds": seconds, "engaged_seconds": seconds / 2}],
                    "by_domain": [{"domain": "youtube.com", "seconds": seconds}],
                }
                (dest / "2026-09-25.json").write_text(json.dumps(payload), encoding="utf-8")
            env = os.environ.copy()
            env["PACT_ACTIVITY_ROOT"] = tmp
            env["PACT_ACTIVITY_DAY"] = "2026-09-25"
            done = subprocess.run(
                [sys.executable, "-c", aw_ship.SUMMARY_SCRIPT],
                env=env,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(done.returncode, 0, done.stderr)
            summary = json.loads(done.stdout)
            self.assertEqual(summary["screen_seconds"], 140.0)
            self.assertEqual(summary["by_category"][0]["seconds"], 140.0)
            stored = json.loads((root / "summary" / "2026-09-25.json").read_text(encoding="utf-8"))
            self.assertEqual(stored["screen_seconds"], 140.0)
            self.assertEqual(len(stored["devices"]), 2)


if __name__ == "__main__":
    unittest.main()
