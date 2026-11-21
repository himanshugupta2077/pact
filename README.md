# Pact

Local Android prototype: pick an installed app, give it a daily minute limit, and block it when the time is gone.

This slice:

1. List launcher apps and today’s foreground time
2. Set a per-app daily limit (minutes) and time window
3. Block the app after the limit (AccessibilityService + full-screen cover)
4. App groups with a shared daily limit and time window
5. **No way to ask for more time.** A hit limit stays hit until the next day or the window opens.
6. **Blocked websites** — sites such as youtube.com stay closed in Chrome, Brave, Firefox and other common browsers, so a banned app can't be opened in the browser instead. Edit the list on the setup screen.
7. **Black and white** — the screen stays grey, with color during calls using the camera or showing WhatsApp remote video, in Brawl Stars, and in apps switched to "Keep in color" on their page.
8. **Time-left pill** — a small "8m left · 12m of 20m" chip at the top of a limited app when it opens and at 10, 5 and 1 minutes left. It fades after 3 seconds; a tap or swipe dismisses it.
9. **Locked settings** — limits, windows, groups, sites, colors and payment apps are read-only on the phone. They unlock only from a computer over USB (below).
10. **Can't be uninstalled** — Pact is a device admin, so uninstalling and Force stop fail. Pact closes the Settings pages that would switch it off (its App info, device admin, usage access, notification access and accessibility pages), turns its accessibility service back on as soon as it goes off, keeps automatic date and time on, and turns Wireless debugging off.

Time spent staring at Pact’s lock overlay is not counted as app screen time, and blocked apps are muted while the overlay is up.

## Build

```bash
cd /path/to/pact
./gradlew :app:installDebug
```

Phone must be authorized over ADB. Then grant one permission, once:

```bash
adb shell pm grant app.pact android.permission.WRITE_SECURE_SETTINGS
```

Pact uses it to turn the screen black and white and to switch its accessibility service back on after a reinstall, a force stop or a reboot.

## First run

1. Open **Pact**
2. Grant **Usage access**
3. Enable the **Pact** accessibility service
4. Set limits, windows, and groups (unlock first, below)
5. Switch on the device admin from the Mac: `adb shell dpm set-active-admin app.pact/.enforce.PactAdmin`

## Changing settings

Plug the phone into the Mac with USB debugging on, then:

```bash
tools/pact-unlock        # unlock for 15 minutes (or: tools/pact-unlock 30, max 60)
tools/pact-unlock lock   # lock again now
tools/pact-unlock status
tools/pact-unlock release  # drop the device admin so `adb uninstall app.pact` works
```

The unlock is refused unless the phone is plugged in with USB debugging on, and it ends on reboot.

Pact itself, the default launcher, Settings, phone/dialer, and the permission controller are never blocked so you can recover.

A new agent should read [collector/AGENT.md](collector/AGENT.md) before changing the collectors.

## Mac screen time

The phone still counts only itself. Other devices get screen time from [ActivityWatch](https://activitywatch.net/), which is already recording this Mac: frontmost app, idle, and Firefox or Zen tab URLs.

`collector/aw_ship.py` reads that local data, drops the lock screen, and counts a video (YouTube, JioHotstar, Netflix, and the same kind of app) even when you are not typing. Other apps count only while the keyboard or mouse is in use. Each run replaces today's file for this Mac on the toolkit VPS and refreshes the cross-device total.

```bash
cd /path/to/pact
python3 collector/aw_ship.py --dry-run
python3 collector/aw_ship.py
python3 collector/aw_ship.py --install   # every 5 minutes, from ~/Library/Application Support/pact-aw
```

On the VPS:

- `<activityRoot>/<device-id>/<day>.json` — this Mac, including app, window title, and URL
- `<activityRoot>/summary/<day>.json` — sum across every device that has shipped that day

`activityRoot` defaults to `/var/lib/pact/activity`. This machine's real path is `activityRoot` in `pact.local.json`.

YouTube in a browser and the YouTube or JioHotstar apps use the same category name, so a later phone or laptop collector adds to the same total. Chrome and Safari count from the window title until their ActivityWatch browser extensions are installed.

## Nothing Phone

Pact on the phone uploads today's foreground time every 5 minutes over Tailscale. The cable is not required. Tailscale on the phone has to be connected.

The file is the second device under `activityRoot`, and `summary/<day>.json` adds it to the Mac. WhatsApp, Netflix, Instagram, YouTube, and YouTube Music use the same category names as the Mac. The upload URL and device id come from `pact.local.json` when you build the app.

If Tailscale is down, plug the phone in and run `python3 collector/aw_android.py` once. That reads ActivityWatch over USB. The automatic USB job is not loaded, so it does not replace the phone's own upload.

## Menu bar

`menubar/` shows today's screen time for every device that has shipped a file. The menu bar is the total. Open it for the split by device, by work / entertainment / gaming / communication, and for an hour, app, or device graph. Categories can be changed there. Marks stay on this Mac. The app only reads the VPS.

```bash
cd /path/to/pact/menubar
./build.sh --install
```

That builds `PactScreen.app`, copies it to `~/Applications`, and opens it. `build.sh` also writes `~/Library/Application Support/PactScreen/site.json` from `pact.local.json` when that file exists. The day follows `timezone` there, or the Mac's zone. A work laptop shows up in the same menu once its collector ships a day file.
