"""Pure checks for spotting the phone's ActivityWatch server. No adb, no VPS."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import aw_android


LISTEN_5600 = (
    "  sl  local_address rem_address   st tx_queue rx_queue\n"
    "   0: 0100007F:15E0 00000000:0000 0A 00000000:00000000\n"
)
ESTABLISHED_5600 = "   1: 0100007F:15E0 0100007F:C001 01 00000000:00000000\n"
LISTEN_OTHER = "   2: 00000000:15B3 00000000:0000 0A 00000000:00000000\n"


class ProcNetTests(unittest.TestCase):
    def test_listen_on_phone_port(self):
        self.assertTrue(aw_android.proc_net_has_listen(LISTEN_5600, 5600))

    def test_established_connection_is_not_the_server(self):
        self.assertFalse(aw_android.proc_net_has_listen(ESTABLISHED_5600, 5600))

    def test_other_listen_port(self):
        self.assertFalse(aw_android.proc_net_has_listen(LISTEN_OTHER, 5600))


class FocusTests(unittest.TestCase):
    def test_nothing_launcher(self):
        text = (
            "topResumedActivity=ActivityRecord{154717397 u0 "
            "com.nothing.launcher/com.android.searchlauncher.SearchLauncher t9073}\n"
        )
        self.assertEqual(
            aw_android.foreground_component(text),
            "com.nothing.launcher/com.android.searchlauncher.SearchLauncher",
        )

    def test_missing_focus(self):
        self.assertEqual(aw_android.foreground_component("no activities"), "")


if __name__ == "__main__":
    unittest.main()
