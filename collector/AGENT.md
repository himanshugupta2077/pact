# Screen-time collectors — handoff

The Mac collector reads ActivityWatch and replaces that Mac's day on the toolkit VPS. The phone uploads its own day from Pact. Screen time adds up across devices.

The VPS does not decide "too much" yet. It only stores the files.

## What ships

| Device | How | ActivityWatch |
|---|---|---|
| This Mac (`MacBook Air`, hostname `mac.local`) | `collector/aw_ship.py` every 5 min | Local server `http://127.0.0.1:5600`, no API key. App v0.13.2. Device id `22222222-2222-4222-8222-222222222222`. |
| Nothing Phone (2) | Pact on the phone, every 5 min over Tailscale, cable or not | Foreground time from Android usage events. Device id `11111111-1111-4111-8111-111111111111`, hostname `phone`. Posted to `pact.example:8765`, which is the Tailscale address only. |

Day boundary is `Asia/Kolkata`. Each run **replaces** that device's file for the day.

The phone does not wait for a cable. Pact reads the same usage events it already uses for limits and POSTs today's file every 5 minutes to a small receiver on the toolkit host. That receiver listens on the Tailscale address, port 8765, and writes `/var/lib/pact/activity/<device-id>/<day>.json`. The bearer token is `~/.config/pact-aw/phone-upload-token` on this Mac and `files/ship/token` inside the Pact app. Neither file is committed. Tailscale on the phone has to be connected, the same way override requests already work.

`collector/aw_android.py` is the USB fallback. It reads ActivityWatch on the phone, which only serves `127.0.0.1:5600` while its own foreground service is up. The 5-minute USB agent is not loaded; a plugged-in run would replace the phone's file. Use it when Tailscale is down:

```bash
python3 collector/aw_android.py
```

## Where the files are

SSH: `ssh pact@pact-host` (Tailscale). Do not restart `pact.service`. `sudo` on that host asks for a password, and the live app is `/opt/pact` (source checkout on this Mac: `../toolkit`). No HTTP route was added. Shipping is an SSH write.

```
/var/lib/pact/activity/<device-id>/<YYYY-MM-DD>.json   # one device, full segments
/var/lib/pact/activity/summary/<YYYY-MM-DD>.json       # sum of every device file for that day
```

`summary` is rebuilt by the remote Python in `SUMMARY_SCRIPT` inside `aw_ship.py` after each upload. Category seconds are what a later limit should read. `screen_seconds` is the headline. `engaged_seconds` is the subset with keyboard or mouse activity (Mac only; on Android they are equal).

Schema is `1`. Mac files use `source` `activitywatch`. Phone files uploaded by Pact use `source` `pact`. Segments have `app`, `title`, `url`, `domain`, `category`. Do not commit `~/.config/pact-aw/android-token` or `~/.config/pact-aw/phone-upload-token` (mode `0600`). The Android token is ActivityWatch's local key, used only by the USB fallback. The phone-upload token is what Pact sends to port 8765.

## Counting rules (`aw_usage.py`)

- One timeline. Overlapping events do not add. The event that started later wins.
- Mac: foreground window ∩ not-afk, except **media** categories count the whole time they are on screen (video with no typing). Media set: `youtube`, `hotstar`, `netflix`, `primevideo`, `disney`, `twitch`, `tiktok`, `spotify`, `video`.
- Android: UsageStats sessions are already foreground time. There is no AFK bucket. Lock UI is dropped (`loginwindow`, `ScreenSaverEngine`, `com.android.systemui`).
- Same category names on purpose: `com.google.android.youtube` and YouTube Music → `youtube`; Netflix, WhatsApp, Instagram, Hotstar, Spotify, GitHub, ChatGPT likewise. Zen on the Mac is a Firefox build; its tabs are in `aw-watcher-web-firefox_*`.
- Browser URLs beat the app name. A terminal title that mentions YouTube stays the terminal.

## Run

```bash
cd ~/pact
python3 -m unittest collector.test_aw_usage collector.test_aw_android collector.test_phone_activity_server
python3 collector/aw_ship.py --dry-run
python3 collector/aw_android.py --dry-run
```

`--install` copies the scripts to `~/Library/Application Support/pact-aw/` and loads a LaunchAgent. LaunchAgents cannot read `~/Documents` (macOS privacy), and their `PATH` does not include `adb`. The phone script looks for `~/Library/Android/sdk/platform-tools/adb`. After editing collectors, run `--install` again or the agents keep the old copy.

| Agent | Log |
|---|---|
| `app.pact.aw-ship` | `~/Library/Logs/pact-aw-ship.log` |
| `app.pact.aw-ship-android` | `~/Library/Logs/pact-aw-ship-android.log` |

The USB phone agent is not loaded. Pact on the phone uploads by itself. `aw_android.py` still opens ActivityWatch if port 5600 is closed, for a manual run while the phone is plugged in.

## Menu bar

`menubar/` is the Mac status-item app (`app.pact.screen`). It SSHs to `pact@pact-host` and runs `menubar/fetch_day.py` on the VPS. That script only reads the day files and prints totals, apps, and hour buckets. It does not write, and it does not print window titles or URLs. Do not restart `pact.service` for this.

Bucket marks (work, entertainment, gaming, communication) stay on this Mac in `~/Library/Application Support/PactScreen/rules.json`. The VPS files are unchanged. A later laptop shows up on its own once a collector ships a device file for the Kolkata day.

## Not done

- No "you have used YouTube too much" rule on the VPS. The menu bar reads the day files and groups them. It does not write a limit back.
- MSI laptop and office laptop are not collectors.
- ActivityWatch on the phone still does not push. Pact does, over Tailscale, every 5 minutes.
- Brave on the phone has no URLs. The Android web watcher only extracts Chrome, Firefox, Samsung Internet, Opera, and Edge (`WebWatcher.KNOWN_BROWSER_PACKAGES`). Brave time is `app:brave`.
- Pact still enforces limits from local usage. That same usage is what it uploads.
