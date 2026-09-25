# Screen-time collectors — handoff

Pact on the Android phone still counts only that phone and does not upload usage. These scripts are the other half: read ActivityWatch on each machine and replace that device's day on the toolkit VPS, so screen time adds up across devices.

The VPS does not decide "too much" yet. It only stores the files.

## What ships

| Device | How | ActivityWatch |
|---|---|---|
| This Mac (`MacBook Air`, hostname `mac.local`) | `collector/aw_ship.py` every 5 min | Local server `http://127.0.0.1:5600`, no API key. App v0.13.2. Device id `22222222-2222-4222-8222-222222222222`. |
| Nothing Phone (2), USB to this Mac | `collector/aw_android.py` every 5 min **while plugged in** | App `net.activitywatch.android` 0.14.1, server `127.0.0.1:5600` **on the phone**. Forwarded to `127.0.0.1:5601` here so it does not collide with the Mac server. Device id `11111111-1111-4111-8111-111111111111`, bucket hostname `phone`. USB serial seen: `00000000`. |

Day boundary is `Asia/Kolkata`. Each run **replaces** that device's file for the day. Unplugged phone time stays on the phone and is included the next time it is plugged in.

## Where the files are

SSH: `ssh pact@pact-host` (Tailscale). Do not restart `pact.service`. `sudo` on that host asks for a password, and the live app is `/opt/pact` (source checkout on this Mac: `../toolkit`). No HTTP route was added. Shipping is an SSH write.

```
/var/lib/pact/activity/<device-id>/<YYYY-MM-DD>.json   # one device, full segments
/var/lib/pact/activity/summary/<YYYY-MM-DD>.json       # sum of every device file for that day
```

`summary` is rebuilt by the remote Python in `SUMMARY_SCRIPT` inside `aw_ship.py` after each upload. Category seconds are what a later limit should read. `screen_seconds` is the headline. `engaged_seconds` is the subset with keyboard or mouse activity (Mac only; on Android they are equal).

Schema is `1`, `source` is `activitywatch`. Segments have `app`, `title`, `url`, `domain`, `category`. Do not commit `~/.config/pact-aw/android-token` (mode `0600`). That file is the phone server's Bearer key. If a request returns 401, open the ActivityWatch app on the phone once; logcat contains `token=<uuid>`, and `aw_android.py` saves it.

## Counting rules (`aw_usage.py`)

- One timeline. Overlapping events do not add. The event that started later wins.
- Mac: foreground window ∩ not-afk, except **media** categories count the whole time they are on screen (video with no typing). Media set: `youtube`, `hotstar`, `netflix`, `primevideo`, `disney`, `twitch`, `tiktok`, `spotify`, `video`.
- Android: UsageStats sessions are already foreground time. There is no AFK bucket. Lock UI is dropped (`loginwindow`, `ScreenSaverEngine`, `com.android.systemui`).
- Same category names on purpose: `com.google.android.youtube` and YouTube Music → `youtube`; Netflix, WhatsApp, Instagram, Hotstar, Spotify, GitHub, ChatGPT likewise. Zen on the Mac is a Firefox build; its tabs are in `aw-watcher-web-firefox_*`.
- Browser URLs beat the app name. A terminal title that mentions YouTube stays the terminal.

## Run

```bash
cd ~/pact
python3 -m unittest collector.test_aw_usage
python3 collector/aw_ship.py --dry-run
python3 collector/aw_android.py --dry-run
```

`--install` copies the scripts to `~/Library/Application Support/pact-aw/` and loads a LaunchAgent. LaunchAgents cannot read `~/Documents` (macOS privacy), and their `PATH` does not include `adb`. The phone script looks for `~/Library/Android/sdk/platform-tools/adb`. After editing collectors, run `--install` again or the agents keep the old copy.

| Agent | Log |
|---|---|
| `app.pact.aw-ship` | `~/Library/Logs/pact-aw-ship.log` |
| `app.pact.aw-ship-android` | `~/Library/Logs/pact-aw-ship-android.log` |

The phone agent exits quietly when nothing is plugged in.

## Menu bar

`menubar/` is the Mac status-item app (`app.pact.screen`). It SSHs to `pact@pact-host` and runs `menubar/fetch_day.py` on the VPS. That script only reads the day files and prints totals, apps, and hour buckets. It does not write, and it does not print window titles or URLs. Do not restart `pact.service` for this.

Bucket marks (work, entertainment, gaming, communication) stay on this Mac in `~/Library/Application Support/PactScreen/rules.json`. The VPS files are unchanged. A later laptop shows up on its own once a collector ships a device file for the Kolkata day.

## Not done

- No "you have used YouTube too much" rule on the VPS. The menu bar reads the day files and groups them. It does not write a limit back.
- MSI laptop and office laptop are not collectors.
- The phone does not push by itself. Its server is localhost-only.
- Brave on the phone has no URLs. The Android web watcher only extracts Chrome, Firefox, Samsung Internet, Opera, and Edge (`WebWatcher.KNOWN_BROWSER_PACKAGES`). Brave time is `app:brave`.
- Pact's own `UsageStats` path is unchanged and still local.
