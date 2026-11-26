#!/usr/bin/env python3
"""Unit tests for watch_notify_relay.py, including Java BulletinDistributorCodec round-trips."""

from __future__ import annotations

import base64
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import watch_notify_relay as relay

ROOT = Path(__file__).resolve().parent.parent
PROTOCOL_CANDIDATES = (
    ROOT / "target/linux-protocol",
    ROOT / "apple-watch-companion/build/linux/x64/release/bundle/lib/protocol",
)


def _find_protocol_dir() -> Path | None:
    for candidate in PROTOCOL_CANDIDATES:
        if (candidate / "watch-linux-protocol.jar").is_file():
            return candidate
    return None


class RelayFilterTests(unittest.TestCase):
    def test_ignores_transient_and_configured_apps(self) -> None:
        f = relay.NotificationFilter(relay.RelayConfig())
        ok, reason, _ = f.evaluate("Volume", "75%", "", {"transient": True}, now=10.0)
        self.assertFalse(ok)
        self.assertEqual(reason, "ignored_app:Volume")

        ok, reason, _ = f.evaluate("OSD", "Brightness", "50%", {"transient": True}, now=11.0)
        self.assertFalse(ok)
        self.assertEqual(reason, "transient")

    def test_low_urgency_maps_to_passive_no_sound_no_wake(self) -> None:
        f = relay.NotificationFilter(relay.RelayConfig())
        ok, _, item = f.evaluate("Syncthing", "Sync complete", "Updated 3 files", {"urgency": 0}, now=10.0)
        self.assertTrue(ok)
        assert item is not None
        self.assertFalse(item["sound"])
        self.assertFalse(item["turns_on_display"])

        ok2, _, item2 = f.evaluate("Mail", "New mail", "Meeting moved", {"urgency": 1}, now=12.0)
        self.assertTrue(ok2)
        assert item2 is not None
        self.assertTrue(item2["sound"])
        self.assertTrue(item2["turns_on_display"])

    def test_kdeconnect_formatting_extracts_phone_app_and_sender(self) -> None:
        f = relay.NotificationFilter(relay.RelayConfig(min_interval_sec=0.0))
        ok1, _, item1 = f.evaluate("KDE Connect", "WhatsApp: Alice", "See you tonight", {}, now=10.0)
        self.assertTrue(ok1)
        assert item1 is not None
        self.assertEqual(item1["title"], "Alice")
        self.assertEqual(item1["subtitle"], "WhatsApp")
        self.assertEqual(item1["body"], "See you tonight")

        ok2, _, item2 = f.evaluate("KDE Connect", "Signal", "Bob: Received", {}, now=11.0)
        self.assertTrue(ok2)
        assert item2 is not None
        self.assertEqual(item2["title"], "Bob")
        self.assertEqual(item2["subtitle"], "Signal")
        self.assertEqual(item2["body"], "Received")

        ok3, _, item3 = f.evaluate("KDE Connect", "Bank alert (HSBC)", "Deposit $100", {}, now=12.0)
        self.assertTrue(ok3)
        assert item3 is not None
        self.assertEqual(item3["title"], "Bank alert")
        self.assertEqual(item3["subtitle"], "HSBC")

    def test_dedupes_within_window_and_allows_after(self) -> None:
        f = relay.NotificationFilter(relay.RelayConfig(dedupe_window_sec=5.0, min_interval_sec=0.1))
        ok1, _, _ = f.evaluate("Mail", "Subject", "Hello", {}, now=100.0)
        self.assertTrue(ok1)
        ok2, reason2, _ = f.evaluate("Mail", "Subject", "Hello", {}, now=102.0)
        self.assertFalse(ok2)
        self.assertEqual(reason2, "duplicate")
        ok3, _, _ = f.evaluate("Mail", "Subject", "Hello", {}, now=106.0)
        self.assertTrue(ok3)

    def test_strips_html_and_respects_suppress_sound(self) -> None:
        f = relay.NotificationFilter(relay.RelayConfig())
        ok, _, item = f.evaluate("Mail", "<b>Subject</b>", "A &amp; B<br>Line 2", {"suppress-sound": True}, now=10.0)
        self.assertTrue(ok)
        assert item is not None
        self.assertEqual(item["title"], "Subject")
        self.assertEqual(item["body"], "A & BLine 2")
        self.assertFalse(item["sound"])
        self.assertTrue(item["turns_on_display"])


class NotificationTrackerTests(unittest.TestCase):
    def test_lifecycle_add_bind_modify_and_close_reasons(self) -> None:
        t = relay.NotificationTracker()
        bid1, ut1 = t.prepare_send(sender=":1.50", serial=42, replaces_id=0, section_id="com.apple.MobileSMS", now=1.0)
        self.assertEqual(ut1, relay.UPDATE_TYPE_ADD)
        self.assertTrue(t.bind_reply(destination=":1.50", reply_serial=42, nid=99, now=1.1))

        # Replacing nid=99 reuses bulletin_id and sets UPDATE_TYPE_MODIFY.
        bid2, ut2 = t.prepare_send(sender=":1.50", serial=43, replaces_id=99, section_id="com.apple.MobileSMS", now=2.0)
        self.assertEqual(bid2, bid1)
        self.assertEqual(ut2, relay.UPDATE_TYPE_MODIFY)

        # Popup timeout (reason=1) must NOT remove from watch.
        self.assertIsNone(t.on_closed(nid=99, reason=1))

        # User dismiss (reason=2) removes and returns the tracked bulletin.
        removed = t.on_closed(nid=99, reason=2)
        self.assertIsNotNone(removed)
        assert removed is not None
        self.assertEqual(removed.bulletin_id, bid1)
        self.assertIsNone(t.on_closed(nid=99, reason=2))


class ProtobufCrossCheckTests(unittest.TestCase):
    def test_java_codec_decodes_bulletin_and_remove_requests(self) -> None:
        protocol_dir = _find_protocol_dir()
        if protocol_dir is None:
            self.skipTest("protocol jar not built")
        bid, raw = relay.encode_bulletin_request(
            title="Test Title",
            subtitle="WhatsApp",
            body="Message body 123",
            section_id="com.apple.MobileSMS",
            section_display_name="WhatsApp",
            sound=False,
            turns_on_display=False,
            timestamp_ms=1764084000000,
            update_type=relay.UPDATE_TYPE_MODIFY,
        )
        rm_raw = relay.encode_remove_bulletin_request(bulletin_id=bid, section_id="com.apple.MobileSMS")
        b64_add = base64.b64encode(raw).decode("ascii")
        b64_rm = base64.b64encode(rm_raw).decode("ascii")
        code = f"""
import dev.applewatchandroid.bridge.BulletinDistributorCodec;
import java.util.Base64;
public class Check {{
    public static void main(String[] args) {{
        var req = BulletinDistributorCodec.BulletinRequest.decode(Base64.getDecoder().decode("{b64_add}"));
        var rm = BulletinDistributorCodec.RemoveBulletinRequest.decode(Base64.getDecoder().decode("{b64_rm}"));
        System.out.println(req.bulletin.bulletinId);
        System.out.println(req.bulletin.title);
        System.out.println(req.bulletin.subtitle);
        System.out.println(req.bulletin.messageTitle);
        System.out.println(req.bulletin.includesSound + ":" + req.bulletin.turnsOnDisplay() + ":" + req.updateType);
        System.out.println(rm.publisherBulletinId + ":" + rm.recordId + ":" + rm.sectionId);
    }}
}}
"""
        with tempfile.TemporaryDirectory() as td:
            src = Path(td) / "Check.java"
            src.write_text(code, encoding="utf-8")
            proc = subprocess.run(
                ["java", "-cp", f"{protocol_dir}/*", str(src)],
                text=True,
                capture_output=True,
                check=True,
            )
        lines = proc.stdout.strip().splitlines()
        self.assertEqual(
            lines,
            [
                bid,
                "Test Title",
                "WhatsApp",
                "Message body 123",
                "false:false:1",
                f"{bid}:{bid}:com.apple.MobileSMS",
            ],
        )


if __name__ == "__main__":
    unittest.main()
