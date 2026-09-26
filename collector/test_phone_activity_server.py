"""Checks for the phone upload receiver. No network, no VPS."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import phone_activity_server as server

TOKEN = "a" * 24
DEVICE = "11111111-1111-4111-8111-111111111111"


def payload(**extra) -> bytes:
    body = {
        "schema": 1,
        "device_id": DEVICE,
        "day": "2026-09-26",
        "screen_seconds": 90,
        "segments": [],
    }
    body.update(extra)
    return json.dumps(body).encode("utf-8")


class AcceptTests(unittest.TestCase):
    def test_accepts_this_phone(self):
        data = server.accept_payload(TOKEN, "Bearer " + TOKEN, payload())
        self.assertEqual(data["device_id"], DEVICE)

    def test_rejects_a_bad_token(self):
        with self.assertRaises(server.Rejected) as caught:
            server.accept_payload(TOKEN, "Bearer no", payload())
        self.assertEqual(caught.exception.status, 401)

    def test_rejects_the_mac_device_id(self):
        raw = payload(device_id="22222222-2222-4222-8222-222222222222")
        with self.assertRaises(server.Rejected) as caught:
            server.accept_payload(TOKEN, "Bearer " + TOKEN, raw)
        self.assertEqual(caught.exception.status, 403)

    def test_rejects_more_than_a_day(self):
        with self.assertRaises(server.Rejected):
            server.accept_payload(TOKEN, "Bearer " + TOKEN, payload(screen_seconds=90000))


class StoreTests(unittest.TestCase):
    def test_writes_the_day_and_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = json.loads(payload())
            data["by_category"] = [{"category": "whatsapp", "seconds": 90, "engaged_seconds": 90}]
            summary = server.store_report(data, root)
            stored = json.loads((root / DEVICE / "2026-09-26.json").read_text())
            self.assertEqual(stored["screen_seconds"], 90)
            self.assertEqual(summary["screen_seconds"], 90)
            self.assertTrue((root / "summary" / "2026-09-26.json").is_file())


if __name__ == "__main__":
    unittest.main()
