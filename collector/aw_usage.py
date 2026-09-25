"""Turn ActivityWatch events into one device's screen-time report.

ActivityWatch is the recorder. This module only reads buckets the local
aw-server already stored:

- currentwindow: frontmost app and window title
- web.tab.current: browser tab URL (Firefox extension on this Mac)
- afkstatus: keyboard and mouse idle

Screen time matches Pact on Android: time the thing was in front of you,
including a video playing with no typing. Idle time still counts for
known media (YouTube, JioHotstar, Netflix, …). It does not count for
everything else, so an editor left open overnight does not become a
workday. The lock screen and screensaver never count.

`engaged_seconds` is the subset where the AFK watcher said not-afk.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from typing import Iterable, Optional
from urllib.parse import urlparse

# Foreground time counts even while idle. Everything else counts only
# while the keyboard or mouse is in use.
MEDIA_CATEGORIES = frozenset(
    {
        "youtube",
        "hotstar",
        "netflix",
        "primevideo",
        "disney",
        "twitch",
        "tiktok",
        "spotify",
        "video",
    }
)

# (domain suffix, category). Match host == domain or host endswith "." + domain.
DOMAIN_CATEGORIES = (
    ("music.youtube.com", "youtube"),
    ("youtube-nocookie.com", "youtube"),
    ("youtube.com", "youtube"),
    ("youtu.be", "youtube"),
    ("netflix.com", "netflix"),
    ("hotstar.com", "hotstar"),
    ("jiocinema.com", "hotstar"),
    ("instagram.com", "instagram"),
    ("tiktok.com", "tiktok"),
    ("twitter.com", "twitter"),
    ("x.com", "twitter"),
    ("reddit.com", "reddit"),
    ("facebook.com", "facebook"),
    ("fb.watch", "facebook"),
    ("twitch.tv", "twitch"),
    ("primevideo.com", "primevideo"),
    ("disneyplus.com", "disney"),
    ("spotify.com", "spotify"),
    ("discord.com", "discord"),
    ("web.whatsapp.com", "whatsapp"),
    ("whatsapp.com", "whatsapp"),
    ("mail.google.com", "gmail"),
    ("chatgpt.com", "chatgpt"),
    ("github.com", "github"),
)

# Longer needles first so "jiohotstar" wins over "hotstar".
APP_CATEGORIES = (
    ("jiohotstar", "hotstar"),
    ("prime video", "primevideo"),
    ("quicktime player", "video"),
    ("yt music", "youtube"),
    ("hotstar", "hotstar"),
    ("netflix", "netflix"),
    ("youtube", "youtube"),
    ("instagram", "instagram"),
    ("tiktok", "tiktok"),
    ("spotify", "spotify"),
    ("whatsapp", "whatsapp"),
    ("disney+", "disney"),
    ("twitch", "twitch"),
    ("iina", "video"),
    ("vlc", "video"),
    ("infuse", "video"),
    ("mpv", "video"),
)

TITLE_CATEGORIES = (
    ("jiohotstar", "hotstar"),
    ("prime video", "primevideo"),
    ("yt music", "youtube"),
    ("youtube", "youtube"),
    ("hotstar", "hotstar"),
    ("netflix", "netflix"),
    ("instagram", "instagram"),
    ("tiktok", "tiktok"),
    ("disney+", "disney"),
    ("twitch", "twitch"),
)

BROWSER_APPS = {
    "firefox": "firefox",
    "firefox developer edition": "firefox",
    "google chrome": "chrome",
    "chrome": "chrome",
    "chromium": "chrome",
    "brave browser": "brave",
    "microsoft edge": "edge",
    "safari": "safari",
    "arc": "arc",
    "orion": "orion",
    "vivaldi": "vivaldi",
    "opera": "opera",
    # Zen is a Firefox build. The ActivityWatch extension records it in
    # the firefox web bucket, while macOS reports the app as Zen.
    "zen": "firefox",
    "zen browser": "firefox",
}

# Mac lock UI. Time here is not screen time, same idea as Pact's overlay.
SKIP_APPS = frozenset({"loginwindow", "screensaverengine"})

# A stored event with duration 0 is a heartbeat the server has not closed.
# Fill it forward only across a short gap. A long gap is a real stop
# (the tab was left, or the watcher died), not hidden usage.
WINDOW_ZERO_GAP = 10.0
WEB_ZERO_GAP = 60.0
# How far the newest event may be stretched to "now".
TAIL_GAP = 60.0
AFK_TAIL_GAP = 180.0

DEVICE_RE = re.compile(r"^[A-Za-z0-9._:-]{8,80}$")
DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_WEB_BUCKET_RE = re.compile(r"^aw-watcher-web-(.+?)_(.+)$")


@dataclass
class Span:
    start: float
    end: float
    app: str = ""
    title: str = ""
    url: str = ""
    domain: str = ""
    browser: str = ""
    category: str = ""

    def copy(self, **changes: object) -> "Span":
        return replace(self, **changes)


def parse_ts(value: str) -> float:
    text = value.strip().replace("Z", "+00:00")
    dt = datetime.fromisoformat(text)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.timestamp()


def browser_key(app: str) -> str:
    return BROWSER_APPS.get((app or "").strip().casefold(), "")


def browser_from_bucket(bucket_id: str) -> str:
    match = _WEB_BUCKET_RE.match(bucket_id or "")
    if not match:
        return ""
    return match.group(1).strip().casefold()


def domain_of(url: str) -> str:
    raw = (url or "").strip()
    if not raw or raw.startswith(("about:", "chrome:", "edge:", "moz-extension:", "file:")):
        return ""
    try:
        host = urlparse(raw).hostname or ""
    except ValueError:
        return ""
    host = host.lower().rstrip(".")
    if host.startswith("www."):
        host = host[4:]
    return host


def match_domain(host: str) -> str:
    for suffix, category in DOMAIN_CATEGORIES:
        if host == suffix or host.endswith("." + suffix):
            return category
    return ""


def match_keyword(text: str, rules: tuple[tuple[str, str], ...]) -> str:
    hay = (text or "").casefold()
    if not hay:
        return ""
    for needle, category in rules:
        if needle in hay:
            return category
    return ""


def app_slug(app: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", (app or "").casefold()).strip("-")
    return slug or "unknown"


def classify(app: str, title: str, domain: str, browser: str) -> str:
    if domain:
        hit = match_domain(domain)
        if hit:
            return hit
        return "site:" + domain
    hit = match_keyword(app, APP_CATEGORIES)
    if hit:
        return hit
    # Title keywords only for browsers. A terminal title that mentions
    # YouTube is not YouTube on screen.
    if browser:
        hit = match_keyword(title, TITLE_CATEGORIES)
        if hit:
            return hit
    return "app:" + app_slug(app)


def _close_durations(
    rows: list[tuple[float, float]],
    now: float,
    zero_gap: float,
    tail_gap: float,
) -> list[tuple[float, float]]:
    """rows are (start, end) oldest-first. end==start means duration 0."""
    if not rows:
        return []
    closed: list[tuple[float, float]] = []
    for index, (start, end) in enumerate(rows):
        if end <= start:
            if index + 1 < len(rows):
                nxt = rows[index + 1][0]
                gap = nxt - start
                if 0 < gap <= zero_gap:
                    end = nxt
            elif 0 < now - start <= tail_gap:
                end = now
        if end > start:
            closed.append((start, end))
    if closed:
        start, end = closed[-1]
        if end < now and now - end <= tail_gap:
            closed[-1] = (start, now)
        elif end > now:
            closed[-1] = (start, now)
    return [(s, e) for s, e in closed if e > s]


def clip_interval(start: float, end: float, lo: float, hi: float) -> Optional[tuple[float, float]]:
    s = max(start, lo)
    e = min(end, hi)
    if e > s:
        return s, e
    return None


def union_intervals(intervals: Iterable[tuple[float, float]]) -> list[tuple[float, float]]:
    items = sorted((s, e) for s, e in intervals if e > s)
    out: list[list[float]] = []
    for start, end in items:
        if not out or start > out[-1][1]:
            out.append([start, end])
        else:
            out[-1][1] = max(out[-1][1], end)
    return [(s, e) for s, e in out]


def intersect_interval(
    start: float,
    end: float,
    windows: list[tuple[float, float]],
) -> list[tuple[float, float]]:
    parts: list[tuple[float, float]] = []
    for win_s, win_e in windows:
        if win_e <= start:
            continue
        if win_s >= end:
            break
        s = max(start, win_s)
        e = min(end, win_e)
        if e > s:
            parts.append((s, e))
    return parts


def overlap_seconds(start: float, end: float, windows: list[tuple[float, float]]) -> float:
    return sum(e - s for s, e in intersect_interval(start, end, windows))


def resolve_overlaps(spans: list[Span]) -> list[Span]:
    """One timeline. On overlap, the event that started later wins."""
    spans = [s for s in spans if s.end > s.start]
    if not spans:
        return []
    events: list[tuple[float, int, int]] = []
    for index, span in enumerate(spans):
        events.append((span.start, 0, index))
        events.append((span.end, 1, index))
    # Ends before starts at the same instant, so a boundary is not double-counted.
    events.sort(key=lambda item: (item[0], item[1]))
    active: set[int] = set()
    out: list[Span] = []
    prev_t: float | None = None
    for stamp, kind, index in events:
        if prev_t is not None and stamp > prev_t and active:
            winner = max(active, key=lambda j: (spans[j].start, j))
            piece = spans[winner].copy(start=prev_t, end=stamp)
            if out and _same_slice(out[-1], piece) and out[-1].end == piece.start:
                out[-1].end = stamp
            else:
                out.append(piece)
        if kind == 1:
            active.discard(index)
        else:
            active.add(index)
        prev_t = stamp
    return out


def _same_slice(a: Span, b: Span) -> bool:
    return (
        a.app == b.app
        and a.title == b.title
        and a.url == b.url
        and a.domain == b.domain
        and a.browser == b.browser
        and a.category == b.category
    )


def merge_spans(spans: list[Span], gap: float = 1.0) -> list[Span]:
    spans = sorted((s for s in spans if s.end > s.start), key=lambda s: s.start)
    out: list[Span] = []
    for span in spans:
        if out:
            prev = out[-1]
            if _same_slice(prev, span) and span.start - prev.end <= gap:
                prev.end = max(prev.end, span.end)
                continue
        out.append(span)
    return out


def _event_rows(events: list[dict]) -> list[tuple[float, float, dict]]:
    rows = []
    for event in events:
        try:
            start = parse_ts(str(event.get("timestamp") or ""))
        except (TypeError, ValueError):
            continue
        duration = event.get("duration") or 0
        try:
            duration_s = float(duration)
        except (TypeError, ValueError):
            duration_s = 0.0
        if duration_s < 0:
            duration_s = 0.0
        rows.append((start, start + duration_s, event))
    rows.sort(key=lambda row: row[0])
    return rows


def window_spans(events: list[dict], now: float, lo: float, hi: float) -> list[Span]:
    spans: list[Span] = []
    for start, end, event in _spans_from_raw(_event_rows(events), now, WINDOW_ZERO_GAP, TAIL_GAP):
        clipped = clip_interval(start, end, lo, hi)
        if clipped is None:
            continue
        data = event.get("data") or {}
        app = str(data.get("app") or "")
        if app.casefold() in SKIP_APPS:
            continue
        title = str(data.get("title") or "")[:300]
        spans.append(
            Span(
                start=clipped[0],
                end=clipped[1],
                app=app,
                title=title,
                browser=browser_key(app),
            )
        )
    return spans


def _spans_from_raw(
    raw: list[tuple[float, float, dict]],
    now: float,
    zero_gap: float,
    tail_gap: float,
) -> list[tuple[float, float, dict]]:
    if not raw:
        return []
    closed_times = _close_durations(
        [(s, e) for s, e, _ in raw],
        now=now,
        zero_gap=zero_gap,
        tail_gap=tail_gap,
    )
    # Map each closed interval to the raw event with the same start.
    # Several raw rows can share a start (duplicate heartbeats). First wins.
    by_start: dict[float, dict] = {}
    for start, _end, event in raw:
        by_start.setdefault(start, event)
    out = []
    for start, end in closed_times:
        event = by_start.get(start)
        if event is None:
            continue
        out.append((start, end, event))
    return out


def afk_model(
    events: list[dict],
    now: float,
    lo: float,
    hi: float,
) -> tuple[bool, tuple[float, float] | None, list[tuple[float, float]]]:
    """Return (available, coverage, not-afk intervals) clipped to [lo, hi]."""
    raw = _event_rows(events)
    usable = _spans_from_raw(raw, now, zero_gap=WINDOW_ZERO_GAP, tail_gap=AFK_TAIL_GAP)
    if not usable:
        return False, None, []
    clipped_all: list[tuple[float, float]] = []
    not_afk: list[tuple[float, float]] = []
    for start, end, event in usable:
        clipped = clip_interval(start, end, lo, hi)
        if clipped is None:
            continue
        clipped_all.append(clipped)
        status = str((event.get("data") or {}).get("status") or "")
        if status == "not-afk":
            not_afk.append(clipped)
    if not clipped_all:
        return False, None, []
    coverage = (min(s for s, _ in clipped_all), max(e for _, e in clipped_all))
    return True, coverage, union_intervals(not_afk)


def web_spans(events: list[dict], browser: str, now: float, lo: float, hi: float) -> list[Span]:
    raw = _event_rows(events)
    spans: list[Span] = []
    for start, end, event in _spans_from_raw(raw, now, WEB_ZERO_GAP, TAIL_GAP):
        clipped = clip_interval(start, end, lo, hi)
        if clipped is None:
            continue
        data = event.get("data") or {}
        url = str(data.get("url") or "")[:2000]
        title = str(data.get("title") or "")[:300]
        spans.append(
            Span(
                start=clipped[0],
                end=clipped[1],
                title=title,
                url=url,
                domain=domain_of(url),
                browser=browser,
            )
        )
    return resolve_overlaps(spans)


def paint_urls(windows: list[Span], webs_by_browser: dict[str, list[Span]]) -> list[Span]:
    painted: list[Span] = []
    for window in windows:
        webs = webs_by_browser.get(window.browser) if window.browser else None
        if not webs:
            painted.append(window)
            continue
        overlaps = sorted(
            (
                web
                for web in webs
                if web.end > window.start and web.start < window.end
            ),
            key=lambda web: web.start,
        )
        if not overlaps:
            painted.append(window)
            continue
        cursor = window.start
        for web in overlaps:
            if web.end <= cursor:
                continue
            if web.start > cursor:
                painted.append(window.copy(start=cursor, end=min(web.start, window.end)))
            seg_s = max(cursor, web.start, window.start)
            seg_e = min(window.end, web.end)
            if seg_e > seg_s:
                url = web.url
                domain = web.domain
                title = web.title or window.title
                painted.append(
                    window.copy(
                        start=seg_s,
                        end=seg_e,
                        title=title,
                        url=url,
                        domain=domain,
                    )
                )
                cursor = seg_e
            if cursor >= window.end:
                break
        if cursor < window.end:
            painted.append(window.copy(start=cursor, end=window.end, url="", domain=""))
    return [s for s in painted if s.end > s.start]


def _screen_pieces(
    span: Span,
    not_afk: list[tuple[float, float]],
    afk_available: bool,
) -> list[Span]:
    if afk_available:
        engaged_parts = intersect_interval(span.start, span.end, not_afk)
    else:
        engaged_parts = [(span.start, span.end)]
    media = span.category in MEDIA_CATEGORIES
    if media or not afk_available:
        return [span]
    return [span.copy(start=s, end=e) for s, e in engaged_parts]


def _balanced(totals: dict[str, float], places: int = 1) -> list[tuple[str, float]]:
    if not totals:
        return []
    rounded = {key: round(value, places) for key, value in totals.items()}
    drift = round(sum(totals.values()) - sum(rounded.values()), places)
    if abs(drift) >= 10 ** (-places):
        key = max(totals, key=lambda item: (totals[item], item))
        rounded[key] = round(rounded[key] + drift, places)
    return sorted(rounded.items(), key=lambda item: (-item[1], item[0]))


def _rows(totals: dict[str, float], field: str, engaged: dict[str, float] | None = None) -> list[dict]:
    rows = []
    for key, seconds in _balanced({k: v for k, v in totals.items() if v > 0}):
        if seconds <= 0:
            continue
        row = {field: key, "seconds": seconds}
        if engaged is not None:
            row["engaged_seconds"] = round(engaged.get(key, 0.0), 1)
        rows.append(row)
    return rows


def build_report(
    *,
    device_id: str,
    hostname: str,
    device_name: str,
    tz_name: str,
    day: str,
    window_events: dict[str, list[dict]],
    afk_events: dict[str, list[dict]],
    web_events: dict[str, list[dict]],
    bucket_meta: list[dict],
    now: datetime,
    day_start: datetime,
    day_end: datetime,
    aw_version: str = "",
) -> dict:
    if now.tzinfo is None or day_start.tzinfo is None:
        raise ValueError("now and day_start must be timezone-aware")
    lo = day_start.timestamp()
    hi = min(day_end.timestamp(), now.timestamp())
    now_ts = now.timestamp()
    if hi < lo:
        hi = lo

    windows: list[Span] = []
    for events in window_events.values():
        windows.extend(window_spans(events, now_ts, lo, hi))
    windows = resolve_overlaps(windows)

    afk_available = False
    coverage: tuple[float, float] | None = None
    not_afk: list[tuple[float, float]] = []
    afk_flat: list[dict] = []
    for events in afk_events.values():
        afk_flat.extend(events)
    if afk_flat:
        afk_available, coverage, not_afk = afk_model(afk_flat, now_ts, lo, hi)

    # Window time outside the AFK watcher's coverage has no idle signal.
    # Keep it, and treat it as engaged. Inside coverage, not-afk is the
    # engaged set. Callers still drop idle non-media below.
    if afk_available and coverage is not None:
        cov_s, cov_e = coverage
        outside = []
        if lo < cov_s:
            outside.append((lo, min(hi, cov_s)))
        if cov_e < hi:
            outside.append((max(lo, cov_e), hi))
        not_afk = union_intervals([*not_afk, *outside])

    webs_by_browser: dict[str, list[Span]] = {}
    for bucket_id, events in web_events.items():
        browser = browser_from_bucket(bucket_id)
        if not browser:
            continue
        webs_by_browser.setdefault(browser, []).extend(
            web_spans(events, browser, now_ts, lo, hi)
        )
    for browser, spans in list(webs_by_browser.items()):
        webs_by_browser[browser] = resolve_overlaps(spans)

    labeled = paint_urls(windows, webs_by_browser)
    for span in labeled:
        span.category = classify(span.app, span.title, span.domain, span.browser)

    counted: list[Span] = []
    for span in labeled:
        counted.extend(_screen_pieces(span, not_afk, afk_available))
    counted = merge_spans(counted)

    cat_seconds: dict[str, float] = {}
    cat_engaged: dict[str, float] = {}
    app_seconds: dict[str, float] = {}
    app_engaged: dict[str, float] = {}
    domain_seconds: dict[str, float] = {}
    for span in counted:
        dur = span.end - span.start
        engaged = overlap_seconds(span.start, span.end, not_afk) if afk_available else dur
        engaged = min(engaged, dur)
        cat_seconds[span.category] = cat_seconds.get(span.category, 0.0) + dur
        cat_engaged[span.category] = cat_engaged.get(span.category, 0.0) + engaged
        app_name = span.app or "unknown"
        app_seconds[app_name] = app_seconds.get(app_name, 0.0) + dur
        app_engaged[app_name] = app_engaged.get(app_name, 0.0) + engaged
        if span.domain:
            domain_seconds[span.domain] = domain_seconds.get(span.domain, 0.0) + dur

    by_category = _rows(cat_seconds, "category", cat_engaged)
    screen_seconds = round(sum(row["seconds"] for row in by_category), 1)
    engaged_seconds = round(sum(row["engaged_seconds"] for row in by_category), 1)

    def iso(ts: float) -> str:
        return datetime.fromtimestamp(ts, day_start.tzinfo).isoformat(timespec="seconds")

    segments = []
    for span in counted:
        if span.end - span.start < 0.05:
            continue
        segments.append(
            {
                "start": iso(span.start),
                "end": iso(span.end),
                "seconds": round(span.end - span.start, 1),
                "app": span.app,
                "title": span.title,
                "url": span.url,
                "domain": span.domain,
                "category": span.category,
            }
        )

    return {
        "schema": 1,
        "source": "activitywatch",
        "aw_version": aw_version,
        "device_id": device_id,
        "hostname": hostname,
        "device_name": device_name or hostname,
        "platform": "macos",
        "tz": tz_name,
        "day": day,
        "gathered_at": now.isoformat(timespec="seconds"),
        "afk_available": afk_available,
        "screen_seconds": screen_seconds,
        "engaged_seconds": engaged_seconds,
        "by_category": by_category,
        "by_app": _rows(app_seconds, "app", app_engaged),
        "by_domain": _rows(domain_seconds, "domain"),
        "buckets": bucket_meta,
        "segments": segments,
    }


def summarize_payloads(day: str, payloads: list[dict]) -> dict:
    devices = []
    categories: dict[str, float] = {}
    cat_engaged: dict[str, float] = {}
    apps: dict[str, float] = {}
    app_engaged: dict[str, float] = {}
    domains: dict[str, float] = {}
    for data in payloads:
        screen = float(data.get("screen_seconds") or 0)
        engaged = float(data.get("engaged_seconds") or 0)
        devices.append(
            {
                "device_id": data.get("device_id"),
                "hostname": data.get("hostname"),
                "device_name": data.get("device_name") or data.get("hostname"),
                "platform": data.get("platform"),
                "screen_seconds": round(screen, 1),
                "engaged_seconds": round(engaged, 1),
                "gathered_at": data.get("gathered_at"),
            }
        )
        for row in data.get("by_category") or []:
            key = str(row.get("category") or "unknown")
            categories[key] = categories.get(key, 0.0) + float(row.get("seconds") or 0)
            cat_engaged[key] = cat_engaged.get(key, 0.0) + float(row.get("engaged_seconds") or 0)
        for row in data.get("by_app") or []:
            key = str(row.get("app") or "unknown")
            apps[key] = apps.get(key, 0.0) + float(row.get("seconds") or 0)
            app_engaged[key] = app_engaged.get(key, 0.0) + float(row.get("engaged_seconds") or 0)
        for row in data.get("by_domain") or []:
            key = str(row.get("domain") or "")
            if key:
                domains[key] = domains.get(key, 0.0) + float(row.get("seconds") or 0)
    by_category = _rows(categories, "category", cat_engaged)
    return {
        "day": day,
        "screen_seconds": round(sum(row["seconds"] for row in by_category), 1),
        "engaged_seconds": round(sum(row["engaged_seconds"] for row in by_category), 1),
        "devices": devices,
        "by_category": by_category,
        "by_app": _rows(apps, "app", app_engaged),
        "by_domain": _rows(domains, "domain"),
    }
