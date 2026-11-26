#!/usr/bin/env python3
"""Forward Linux desktop (and KDE Connect) D-Bus notifications to Apple Watch Companion.

Monitors org.freedesktop.Notifications via org.freedesktop.DBus.Monitoring.BecomeMonitor
so it never replaces or interferes with the desktop notification daemon.
Supports:
- Apple watchOS HIG Short Look / Long Look field shaping (including KDE Connect formatting)
- UNNotificationInterruptionLevel-style urgency mapping (low -> passive/no wake, normal/critical -> active)
- Notification lifecycle sync (replaces_id -> UPDATE_TYPE_MODIFY, NotificationClosed -> REMOVE_BULLETIN)

Contributed by Nocehi.
"""

from __future__ import annotations

import argparse
import base64
import html
import json
import os
import re
import signal
import socket
import struct
import sys
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

WIRE_VARINT = 0
WIRE_FIXED64 = 1
WIRE_LENGTH_DELIMITED = 2

UPDATE_TYPE_ADD = 0
UPDATE_TYPE_MODIFY = 1
DEFAULT_FEED = 59
DEFAULT_SECTION_SUBTYPE = 2
DEFAULT_SOUND_ALERT_TYPE = 2

# Freedesktop NotificationClosed reasons:
# 1 = expired, 2 = dismissed by user, 3 = closed by CloseNotification, 4 = undefined
SYNC_CLOSE_REASONS = (2, 3)

_HTML_TAG_RE = re.compile(r"<[^>]+>")
_KDE_PARENS_RE = re.compile(r"^(?P<main>.+?)\s*\((?P<app>[^()]{1,32})\)$")
_KDE_COLON_RE = re.compile(r"^(?P<app>[A-Za-z0-9\u4e00-\u9fff._+\- ]{2,28}):\s+(?P<rest>.+)$")


def _encode_varint(value: int) -> bytes:
    if value < 0:
        value &= (1 << 64) - 1
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _encode_tag(field_number: int, wire_type: int) -> bytes:
    return _encode_varint((field_number << 3) | wire_type)


def _encode_bytes_field(field_number: int, data: bytes) -> bytes:
    return _encode_tag(field_number, WIRE_LENGTH_DELIMITED) + _encode_varint(len(data)) + data


def _encode_string_field(field_number: int, value: str) -> bytes:
    return _encode_bytes_field(field_number, value.encode("utf-8"))


def _encode_double_field(field_number: int, value: float) -> bytes:
    return _encode_tag(field_number, WIRE_FIXED64) + struct.pack("<d", value)


def _encode_varint_field(field_number: int, value: int) -> bytes:
    return _encode_tag(field_number, WIRE_VARINT) + _encode_varint(value)


def encode_bulletin_request(
    *,
    title: str,
    body: str,
    section_id: str = "com.apple.MobileSMS",
    section_display_name: str = "Linux",
    subtitle: str | None = None,
    sound: bool = True,
    turns_on_display: bool | None = None,
    bulletin_id: str | None = None,
    timestamp_ms: int | None = None,
    update_type: int = UPDATE_TYPE_ADD,
) -> tuple[str, bytes]:
    """Encode a BulletinDistributorCodec.BulletinRequest matching the Java codec."""
    bid = bulletin_id or str(uuid.uuid4())
    ts_ms = timestamp_ms if timestamp_ms and timestamp_ms > 0 else int(time.time() * 1000)
    ts_sec = ts_ms / 1000.0
    wake = sound if turns_on_display is None else turns_on_display

    b = bytearray()
    b += _encode_string_field(1, bid)
    b += _encode_string_field(2, section_id)
    b += _encode_string_field(3, section_display_name)
    b += _encode_string_field(4, title)
    if subtitle:
        b += _encode_string_field(5, subtitle)
    b += _encode_string_field(6, body)
    b += _encode_double_field(7, ts_sec)
    b += _encode_varint_field(10, DEFAULT_FEED)
    b += _encode_string_field(12, bid)
    b += _encode_string_field(13, bid)
    b += _encode_varint_field(15, DEFAULT_SECTION_SUBTYPE)
    b += _encode_varint_field(19, 1 if sound else 0)
    b += _encode_varint_field(24, DEFAULT_SOUND_ALERT_TYPE)
    b += _encode_varint_field(30, 1 if wake else 0)
    b += _encode_string_field(43, str(uuid.uuid4()))

    req = bytearray()
    req += _encode_bytes_field(1, bytes(b))
    req += _encode_varint_field(2, 1 if (sound or wake) else 0)
    req += _encode_double_field(3, ts_sec)
    req += _encode_varint_field(4, update_type)
    return bid, bytes(req)


def encode_remove_bulletin_request(
    *,
    bulletin_id: str,
    section_id: str = "com.apple.MobileSMS",
) -> bytes:
    """Encode a BulletinDistributorCodec.RemoveBulletinRequest matching decodeExact()."""
    if not bulletin_id or not bulletin_id.strip() or len(bulletin_id) > 128:
        raise ValueError("Invalid bulletin_id for RemoveBulletinRequest")
    if not section_id or not section_id.strip() or len(section_id) > 512:
        raise ValueError("Invalid section_id for RemoveBulletinRequest")
    out = bytearray()
    out += _encode_string_field(1, bulletin_id)
    out += _encode_string_field(2, bulletin_id)
    out += _encode_string_field(3, section_id)
    return bytes(out)


def clean_text(text: str, max_len: int) -> str:
    if not text:
        return ""
    stripped = _HTML_TAG_RE.sub("", text)
    stripped = html.unescape(stripped)
    lines = [line.strip() for line in stripped.splitlines() if line.strip()]
    joined = "\n".join(lines).strip()
    if len(joined) > max_len:
        return joined[: max_len - 1] + "…"
    return joined


def format_notification_fields(
    *,
    app_name: str,
    desktop_entry: str,
    summary: str,
    body: str,
    max_title_len: int,
    max_body_len: int,
    kdeconnect_apps: set[str],
    kdeconnect_default_label: str = "Phone",
) -> tuple[str, str, str | None, str]:
    """Shape (section_display_name, title, subtitle, body) for watchOS Short/Long Look."""
    app = clean_text(app_name, 80) or "Linux"
    raw_title = clean_text(summary, max_title_len)
    raw_body = clean_text(body, max_body_len)

    is_kdeconnect = (
        app.lower() in kdeconnect_apps
        or desktop_entry.lower().startswith("org.kde.kdeconnect")
    )

    if is_kdeconnect:
        # Typical KDE Connect patterns:
        # 1) summary="WhatsApp: Alice", body="Hello" -> title="Alice", subtitle="WhatsApp"
        # 2) summary="Alice (WhatsApp)", body="Hello" -> title="Alice", subtitle="WhatsApp"
        # 3) summary="WhatsApp", body="Alice: Hello" -> title="Alice", subtitle="WhatsApp", body="Hello"
        title = raw_title
        subtitle: str | None = None
        msg = raw_body
        section_name = kdeconnect_default_label

        m_colon = _KDE_COLON_RE.match(raw_title) if raw_title else None
        m_parens = _KDE_PARENS_RE.match(raw_title) if raw_title else None
        if m_colon and not m_colon.group("app").isdigit():
            subtitle = clean_text(m_colon.group("app"), 48)
            title = clean_text(m_colon.group("rest"), max_title_len)
            section_name = subtitle
        elif m_parens:
            subtitle = clean_text(m_parens.group("app"), 48)
            title = clean_text(m_parens.group("main"), max_title_len)
            section_name = subtitle
        elif raw_title and raw_body:
            m_body = _KDE_COLON_RE.match(raw_body)
            if m_body and len(raw_title) <= 28 and not m_body.group("app").isdigit():
                subtitle = raw_title
                section_name = raw_title
                title = clean_text(m_body.group("app"), max_title_len)
                msg = clean_text(m_body.group("rest"), max_body_len)
            else:
                subtitle = kdeconnect_default_label

        if not title:
            title = subtitle or f"{kdeconnect_default_label} notification"
        if not msg:
            msg = title
        return section_name, title, subtitle, msg

    # Regular desktop notification: front-load summary as Short Look title.
    if not raw_title:
        return app, app, None, raw_body
    if not raw_body:
        return app, raw_title, None, app
    subtitle = app if app.lower() != raw_title.lower() else None
    return app, raw_title, subtitle, raw_body


def default_socket_path() -> Path:
    override = os.environ.get("WATCH_COMPANION_CONTROL_SOCKET")
    if override:
        return Path(override)
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    if not runtime:
        raise RuntimeError("XDG_RUNTIME_DIR is not set")
    return Path(runtime) / "watch-companion" / "control.sock"


def default_config_path() -> Path:
    xdg = os.environ.get("XDG_CONFIG_HOME")
    base = Path(xdg) if xdg else Path.home() / ".config"
    return base / "watch-companion" / "notify-relay.json"


@dataclass
class RelayConfig:
    enabled: bool = True
    section_id: str = "com.apple.MobileSMS"
    silent: bool = False
    ignore_transient: bool = True
    ignore_low_urgency: bool = False
    sync_close: bool = True
    dedupe_window_sec: float = 5.0
    min_interval_sec: float = 0.5
    max_title_len: int = 80
    max_body_len: int = 800
    kdeconnect_default_label: str = "Phone"
    kdeconnect_apps: list[str] = field(
        default_factory=lambda: [
            "KDE Connect",
            "kdeconnect",
            "kdeconnectd",
        ]
    )
    ignore_apps: list[str] = field(
        default_factory=lambda: [
            "apple_watch_companion",
            "Watch Companion",
            "notify-osd",
            "Volume",
            "Brightness",
        ]
    )
    ignore_categories: list[str] = field(
        default_factory=lambda: [
            "device.added",
            "device.removed",
            "transfer.complete",
        ]
    )

    @classmethod
    def load(cls, path: Path) -> "RelayConfig":
        cfg = cls()
        if not path.is_file():
            return cfg
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            print(f"[watch-notify-relay] invalid config {path}: {exc}", file=sys.stderr)
            return cfg
        if not isinstance(raw, dict):
            return cfg
        for key in (
            "enabled",
            "section_id",
            "silent",
            "ignore_transient",
            "ignore_low_urgency",
            "sync_close",
            "dedupe_window_sec",
            "min_interval_sec",
            "max_title_len",
            "max_body_len",
            "kdeconnect_default_label",
            "kdeconnect_apps",
            "ignore_apps",
            "ignore_categories",
        ):
            if key in raw:
                setattr(cfg, key, raw[key])
        return cfg


@dataclass
class TrackedBulletin:
    bulletin_id: str
    section_id: str
    updated_at: float


class NotificationTracker:
    """Tracks D-Bus notification IDs <-> Apple Watch bulletinIds for updates and close sync."""

    def __init__(self, max_entries: int = 512, pending_ttl_sec: float = 15.0) -> None:
        self.max_entries = max_entries
        self.pending_ttl_sec = pending_ttl_sec
        self._active: dict[int, TrackedBulletin] = {}
        self._pending: dict[tuple[str, int], TrackedBulletin] = {}

    def prepare_send(
        self,
        *,
        sender: str,
        serial: int,
        replaces_id: int,
        section_id: str,
        now: float | None = None,
    ) -> tuple[str, int]:
        ts = time.monotonic() if now is None else now
        self._prune(ts)
        if replaces_id > 0 and replaces_id in self._active:
            entry = self._active[replaces_id]
            entry.updated_at = ts
            if sender and serial:
                self._pending[(sender, serial)] = entry
            return entry.bulletin_id, UPDATE_TYPE_MODIFY

        entry = TrackedBulletin(bulletin_id=str(uuid.uuid4()), section_id=section_id, updated_at=ts)
        if replaces_id > 0:
            self._active[replaces_id] = entry
        if sender and serial:
            self._pending[(sender, serial)] = entry
        return entry.bulletin_id, UPDATE_TYPE_ADD

    def bind_reply(self, *, destination: str, reply_serial: int, nid: int, now: float | None = None) -> bool:
        ts = time.monotonic() if now is None else now
        entry = self._pending.pop((destination, reply_serial), None)
        if entry is None or nid <= 0:
            return False
        entry.updated_at = ts
        self._active[nid] = entry
        self._prune(ts)
        return True

    def on_closed(self, *, nid: int, reason: int) -> TrackedBulletin | None:
        if reason not in SYNC_CLOSE_REASONS:
            return None
        return self._active.pop(nid, None)

    def _prune(self, now: float) -> None:
        cutoff = now - self.pending_ttl_sec
        self._pending = {k: v for k, v in self._pending.items() if v.updated_at >= cutoff}
        if len(self._active) > self.max_entries:
            oldest = sorted(self._active.items(), key=lambda kv: kv[1].updated_at)
            for nid, _ in oldest[: len(self._active) - self.max_entries]:
                self._active.pop(nid, None)


class NotificationFilter:
    def __init__(self, config: RelayConfig) -> None:
        self.config = config
        self._recent: dict[tuple[str, str, str], float] = {}
        self._last_sent_at: float = 0.0
        self._ignore_apps_lower = {a.lower() for a in config.ignore_apps}
        self._ignore_categories_lower = {c.lower() for c in config.ignore_categories}
        self._kdeconnect_apps_lower = {a.lower() for a in config.kdeconnect_apps}

    def evaluate(
        self,
        app_name: str,
        summary: str,
        body: str,
        hints: dict[str, Any],
        *,
        is_update: bool = False,
        now: float | None = None,
    ) -> tuple[bool, str, dict[str, Any] | None]:
        ts = time.monotonic() if now is None else now
        if not self.config.enabled:
            return False, "disabled", None

        app = clean_text(str(app_name or ""), 80) or "Linux"
        desktop_entry = str(hints.get("desktop-entry", "") or "").strip()
        if app.lower() in self._ignore_apps_lower or desktop_entry.lower() in self._ignore_apps_lower:
            return False, f"ignored_app:{app}", None

        category = str(hints.get("category", "") or "").strip().lower()
        if category and category in self._ignore_categories_lower:
            return False, f"ignored_category:{category}", None

        if self.config.ignore_transient and bool(hints.get("transient", False)):
            return False, "transient", None

        urgency = hints.get("urgency", 1)
        try:
            urgency_val = int(urgency)
        except (TypeError, ValueError):
            urgency_val = 1
        if self.config.ignore_low_urgency and urgency_val == 0:
            return False, "low_urgency", None

        if not clean_text(str(summary or ""), 16) and not clean_text(str(body or ""), 16):
            return False, "empty", None

        section_name, title, subtitle, msg = format_notification_fields(
            app_name=app,
            desktop_entry=desktop_entry,
            summary=str(summary or ""),
            body=str(body or ""),
            max_title_len=self.config.max_title_len,
            max_body_len=self.config.max_body_len,
            kdeconnect_apps=self._kdeconnect_apps_lower,
            kdeconnect_default_label=self.config.kdeconnect_default_label,
        )

        # Prune expired deduplication entries.
        cutoff = ts - self.config.dedupe_window_sec
        self._recent = {k: v for k, v in self._recent.items() if v >= cutoff}
        key = (section_name.lower(), title, msg)
        if key in self._recent:
            return False, "duplicate", None

        if (not is_update) and self._last_sent_at > 0 and (ts - self._last_sent_at) < self.config.min_interval_sec:
            return False, "rate_limited", None

        self._recent[key] = ts
        self._last_sent_at = ts

        suppress_sound = bool(hints.get("suppress-sound", False))
        # Urgency mapping aligned with Apple UNNotificationInterruptionLevel:
        # - urgency 0 (Low) -> passive (silent, no display wake)
        # - in-place updates -> passive unless urgency 2 (Critical)
        # - urgency 1/2 -> active (wakes display & plays sound unless muted)
        if urgency_val == 0 or (is_update and urgency_val < 2):
            sound = False
            turns_on_display = False
        else:
            sound = (not self.config.silent) and (not suppress_sound)
            turns_on_display = not self.config.silent

        return True, "ok", {
            "section_display_name": section_name,
            "title": title,
            "subtitle": subtitle,
            "body": msg,
            "sound": sound,
            "turns_on_display": turns_on_display,
            "urgency": urgency_val,
        }


def send_control_command(socket_path: Path, method: str, payload_b64: str, timeout: float = 2.0) -> dict[str, Any]:
    request = json.dumps({"method": method, "payload": payload_b64}, separators=(",", ":")) + "\n"
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        sock.connect(str(socket_path))
        sock.sendall(request.encode("utf-8"))
        chunks: list[bytes] = []
        while True:
            data = sock.recv(4096)
            if not data:
                break
            chunks.append(data)
            if b"\n" in data or sum(len(c) for c in chunks) > 65536:
                break
    raw = b"".join(chunks).decode("utf-8", errors="replace").strip()
    return json.loads(raw) if raw else {"status": "EMPTY_RESPONSE"}


def _unwrap_dbus_hints(raw_hints: Any) -> dict[str, Any]:
    result: dict[str, Any] = {}
    if not isinstance(raw_hints, dict):
        return result
    for k, v in raw_hints.items():
        key = str(k)
        if isinstance(v, (bool, int, float, str)):
            result[key] = v
        else:
            try:
                result[key] = int(v)
            except (TypeError, ValueError):
                result[key] = str(v)
    return result


def run_monitor(config: RelayConfig, socket_path: Path, dry_run: bool, verbose: bool) -> int:
    import dbus
    from dbus.mainloop.glib import DBusGMainLoop
    from gi.repository import GLib

    DBusGMainLoop(set_as_default=True)
    bus = dbus.SessionBus()
    filt = NotificationFilter(config)
    tracker = NotificationTracker()

    def on_message(_connection: Any, message: Any) -> Any:
        mtype = message.get_type()

        # 1) Capture Notify return value (uint32 notification ID) to bind for future updates/closes.
        if mtype == dbus.lowlevel.MESSAGE_TYPE_METHOD_RETURN:
            args = message.get_args_list()
            if len(args) == 1:
                try:
                    nid = int(args[0])
                except (TypeError, ValueError):
                    nid = 0
                if nid > 0:
                    dest = str(message.get_destination() or "")
                    reply_serial = int(message.get_reply_serial() or 0)
                    if tracker.bind_reply(destination=dest, reply_serial=reply_serial, nid=nid) and verbose:
                        print(f"[watch-notify-relay] bound nid={nid}", flush=True)
            return dbus.lowlevel.HANDLER_RESULT_HANDLED

        # 2) Sync user dismissal / CloseNotification -> REMOVE_BULLETIN.
        if (
            mtype == dbus.lowlevel.MESSAGE_TYPE_SIGNAL
            and message.get_interface() == "org.freedesktop.Notifications"
            and message.get_member() == "NotificationClosed"
        ):
            if not config.sync_close:
                return dbus.lowlevel.HANDLER_RESULT_HANDLED
            args = message.get_args_list()
            if len(args) >= 2:
                try:
                    nid = int(args[0])
                    reason = int(args[1])
                except (TypeError, ValueError):
                    return dbus.lowlevel.HANDLER_RESULT_HANDLED
                entry = tracker.on_closed(nid=nid, reason=reason)
                if entry is not None:
                    raw_rm = encode_remove_bulletin_request(
                        bulletin_id=entry.bulletin_id,
                        section_id=entry.section_id,
                    )
                    encoded_rm = base64.b64encode(raw_rm).decode("ascii")
                    if dry_run:
                        print(f"[watch-notify-relay] dry-run remove nid={nid} id={entry.bulletin_id}", flush=True)
                    else:
                        try:
                            resp = send_control_command(socket_path, "removeBulletin", encoded_rm)
                            print(
                                f"[watch-notify-relay] removed nid={nid} id={entry.bulletin_id} "
                                f"reason={reason} status={resp.get('status')}",
                                flush=True,
                            )
                        except OSError as exc:
                            print(f"[watch-notify-relay] remove socket unavailable: {exc}", file=sys.stderr, flush=True)
            return dbus.lowlevel.HANDLER_RESULT_HANDLED

        # 3) Forward Notify -> SEND_BULLETIN (ADD or MODIFY).
        if (
            mtype != dbus.lowlevel.MESSAGE_TYPE_METHOD_CALL
            or message.get_interface() != "org.freedesktop.Notifications"
            or message.get_member() != "Notify"
        ):
            return dbus.lowlevel.HANDLER_RESULT_HANDLED

        args = message.get_args_list()
        if len(args) < 7:
            return dbus.lowlevel.HANDLER_RESULT_HANDLED
        app_name = str(args[0])
        try:
            replaces_id = int(args[1])
        except (TypeError, ValueError):
            replaces_id = 0
        summary = str(args[3])
        body = str(args[4])
        hints = _unwrap_dbus_hints(args[6])

        is_known_update = replaces_id > 0 and replaces_id in tracker._active
        allow, reason, item = filt.evaluate(
            app_name,
            summary,
            body,
            hints,
            is_update=is_known_update,
        )
        if not allow or item is None:
            if verbose:
                print(f"[watch-notify-relay] skip ({reason}) app={app_name!r}", flush=True)
            return dbus.lowlevel.HANDLER_RESULT_HANDLED

        sender = str(message.get_sender() or "")
        serial = int(message.get_serial() or 0)
        bid, update_type = tracker.prepare_send(
            sender=sender,
            serial=serial,
            replaces_id=replaces_id,
            section_id=config.section_id,
        )
        _, raw_proto = encode_bulletin_request(
            bulletin_id=bid,
            title=item["title"],
            subtitle=item["subtitle"],
            body=item["body"],
            section_id=config.section_id,
            section_display_name=item["section_display_name"],
            sound=item["sound"],
            turns_on_display=item["turns_on_display"],
            update_type=update_type,
        )
        encoded = base64.b64encode(raw_proto).decode("ascii")
        op = "modify" if update_type == UPDATE_TYPE_MODIFY else "add"
        if dry_run:
            print(
                f"[watch-notify-relay] dry-run {op} id={bid} app={item['section_display_name']!r} "
                f"title={item['title']!r} sound={item['sound']} wake={item['turns_on_display']}",
                flush=True,
            )
            return dbus.lowlevel.HANDLER_RESULT_HANDLED

        try:
            resp = send_control_command(socket_path, "sendBulletin", encoded)
            print(
                f"[watch-notify-relay] sent {op} id={bid} app={item['section_display_name']!r} "
                f"sound={item['sound']} wake={item['turns_on_display']} status={resp.get('status')}",
                flush=True,
            )
        except OSError as exc:
            print(f"[watch-notify-relay] socket unavailable ({socket_path}): {exc}", file=sys.stderr, flush=True)
        except Exception as exc:
            print(f"[watch-notify-relay] send error: {exc}", file=sys.stderr, flush=True)
        return dbus.lowlevel.HANDLER_RESULT_HANDLED

    bus.add_message_filter(on_message)
    mon = bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus")
    iface = dbus.Interface(mon, "org.freedesktop.DBus.Monitoring")
    rules = [
        "type='method_call',interface='org.freedesktop.Notifications',member='Notify'",
        "type='method_return'",
        "type='signal',interface='org.freedesktop.Notifications',member='NotificationClosed'",
    ]
    iface.BecomeMonitor(rules, dbus.UInt32(0))

    loop = GLib.MainLoop()

    def _stop(_sig: int, _frame: Any) -> None:
        loop.quit()

    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)
    print(f"[watch-notify-relay] listening on session D-Bus -> {socket_path}", flush=True)
    loop.run()
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Relay Linux D-Bus notifications to Apple Watch Companion.")
    parser.add_argument("--socket", type=Path, default=None, help="Control socket path")
    parser.add_argument("--config", type=Path, default=None, help="JSON config path")
    parser.add_argument("--dry-run", action="store_true", help="Do not send to control socket")
    parser.add_argument("--verbose", action="store_true", help="Log skipped notifications and ID bindings")
    args = parser.parse_args()

    cfg = RelayConfig.load(args.config or default_config_path())
    sock_path = args.socket or default_socket_path()
    return run_monitor(cfg, sock_path, dry_run=args.dry_run, verbose=args.verbose)


if __name__ == "__main__":
    raise SystemExit(main())
