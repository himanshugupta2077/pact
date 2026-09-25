# Pact

Local Android prototype: pick an installed app, give it a daily minute limit, and block it when the time is gone.

This slice:

1. List launcher apps and today’s foreground time
2. Set a per-app daily limit (minutes) and time window
3. Block the app after the limit (AccessibilityService + full-screen cover)
4. App groups with a shared daily limit and time window
5. **Request override** — ask for 1–15 minutes with a comment. The request goes to the toolkit server. Approving it unlocks that app (or group, or settings) for the granted time.
6. **Settings lock** — daily limits, time windows, groups, and the server URL cannot change until a settings request is approved. After approval, edits are allowed only for the granted window.

Time spent staring at Pact’s lock overlay is not counted as app screen time, and blocked apps are muted while the overlay is up.

## Override flow

1. Hit a limit → lock screen → **Request override**
2. Choose minutes (max 15) and write a comment → **Send request**
3. On her iPhone, open `/pact` (home tile **Pact**) and unlock with Face ID. First visit: **Bind this iPhone** once in Safari.
4. Approve with a custom duration, or deny
5. The phone polls, applies a scoped unlock, and locks again when the time expires

Settings and groups use the same inbox. Approving a **settings** request unlocks editing. Approving a **group** request unlocks every app in that group.

Default server: `https://pact.example` (on a fresh install you can set it under the gear / setup screen; after any request it stays locked until a settings approval).

If you are giving this APK to someone else, or standing up a reviewer on a new VPS, read **[PAIRING.md](PAIRING.md)** — a short explanation for the friend, then the exact API an AI can implement.

## Build

```bash
cd ~/pact
./gradlew :app:installDebug
```

Phone must be authorized over ADB.

## First run

1. Open **Pact**
2. Grant **Usage access**
3. Enable the **Pact** accessibility service
4. On her iPhone, open `https://pact.example/pact/` in Safari and tap **Bind this iPhone** (once)
5. Request a settings change from an app, group, or setup screen, then she approves it at `/pact/`
6. While that window is open, set limits, windows, and groups
7. To test override: Request override on the lock screen, then she approves at `/pact/`

Pact itself, the default launcher, Settings, phone/dialer, and the permission controller are never blocked so you can recover.

## Mac screen time

The phone still counts only itself. Other devices get screen time from [ActivityWatch](https://activitywatch.net/), which is already recording this Mac: frontmost app, idle, and Firefox or Zen tab URLs.

`collector/aw_ship.py` reads that local data, drops the lock screen, and counts a video (YouTube, JioHotstar, Netflix, and the same kind of app) even when you are not typing. Other apps count only while the keyboard or mouse is in use. Each run replaces today's file for this Mac on the toolkit VPS and refreshes the cross-device total.

```bash
cd ~/pact
python3 collector/aw_ship.py --dry-run
python3 collector/aw_ship.py
python3 collector/aw_ship.py --install   # every 5 minutes, from ~/Library/Application Support/pact-aw
```

On the VPS:

- `/var/lib/pact/activity/<device-id>/<day>.json` — this Mac, including app, window title, and URL
- `/var/lib/pact/activity/summary/<day>.json` — sum across every device that has shipped that day

YouTube in a browser and the YouTube or JioHotstar apps use the same category name, so a later phone or laptop collector adds to the same total. Chrome and Safari count from the window title until their ActivityWatch browser extensions are installed.
