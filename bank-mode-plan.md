# Bank mode plan

Banking and UPI apps refuse to work while Pact's accessibility service is on, and some also check Developer options and USB debugging. Pact already turns its service back on every 15 s, so turning it off by hand doesn't stick. Bank mode switches those settings off while a banking app is in front and puts them back when you leave, without opening a hole in Pact's blocking.

Status: plan only, 2026-11-14. Reviewed by the critic. The build waits for step 0, which needs the phone over USB.

## Ideas taken (concepts only, no code copied; Warden and IMD are GPL)

| From | Idea | How Pact uses it |
|---|---|---|
| [Warden](https://github.com/abdulsamad/warden) | Really switch settings off, nothing spoofed | Same |
| Warden | Save a snapshot to disk before changing anything, restore it exactly, and recover a leftover snapshot on start and boot | Same |
| Warden | One code path writes secure settings | Extend `SecureSettings` |
| Warden | Protected launch: switch off first, confirm, then open the app | One Quick Settings tile, built only if step 0 shows apps that check at launch |
| [IMD](https://github.com/soul-99/SU_IMD) | Detect the app opening from an accessibility event, not by polling | Pact's own service sees the bank window within milliseconds |
| [GPay Ready](https://github.com/gdr1999/gpay-india-accessibility-fix) | Wait a moment before restoring, so an OTP or a 3-D Secure redirect doesn't restore mid-payment | Grace for neutral apps only |
| GPay Ready | Google Pay 356+ keeps blocking (it "latches") until a reboot | Google Pay is left out. Use PhonePe, BHIM or the bank's own UPI |

## Step 0: diagnose before writing code (about 20 min, phone on USB)

1. `adb devices`. List the bank and payment packages and their installers. Dump `enabled_accessibility_services`, `enabled_notification_listeners`, `development_settings_enabled`, `adb_enabled` and `adb_wifi_enabled`.
2. For each app that complains, switch off one thing at a time on the phone: Pact's service, any other accessibility service (ActivityWatch, if it has one), Developer options, USB debugging. Note which one clears the complaint.
3. Note when each app checks: at launch (reopening doesn't help) or later (reopening does). Note any app that needs a reboot (it latches).
4. Process check: with Pact's service off, run `dumpsys activity processes app.pact`. Does the notification listener keep the process alive?
5. A 5-minute variant: build with `android:isAccessibilityTool="true"` in `accessibility_service.xml` and retry. It is unverified, but some anti-fraud SDKs allowlist accessibility tools. **If this clears the complaints, stop here. Bank mode isn't needed.**

What step 0 decides:

- Every app checks at launch, or latches: build only the tile, with a hard 5-minute timer. Skip auto-detect.
- Some apps check later: build auto-detect (below), plus the tile for apps that check at launch.
- Switch off only what step 0 shows is needed. Leave USB debugging alone if nothing checks it: toggling wireless debugging changes its port and breaks the collector.

## How auto-detect works

1. **Enter.** `PactAccessibilityService` sees a window from a watched package. Then `BankMode.enter(pkg)` runs, in this order:
   1. Write the snapshot to `shared_prefs/pact_bank.xml` with `commit()`: the full `enabled_accessibility_services`, `accessibility_enabled`, any debug settings being managed, and the start time.
   2. Start the watcher.
   3. Switch off the debug settings, if managed.
   4. Switch off Pact's own accessibility service last. This unbinds the service, so nothing runs after it in the service context.
2. **Watch.** A coroutine in `PactApp` polls `UsageStatsManager.queryEvents` every 1 s, with a dynamic `ACTION_SCREEN_OFF` receiver. It stays a coroutine if step 0.4 shows the notification listener keeps the process alive. Otherwise it becomes a foreground service, started only from a user tap, since a tap is always allowed to start one.
   - A blocked app (`engine.shouldBlock`) comes to the front: **restore at once**, no grace.
   - Another watched app (a UPI hand-off): stay.
   - A neutral app (launcher, SMS for OTP, browser or Custom Tab for 3-D Secure): restore after 15 s.
   - Screen off: restore.
   - Cap of 20 min per session: restore.
3. **Restore.** Put the snapshot back exactly, check it, delete the snapshot. Pact's service reconnects.
4. **No re-entry loop.** After a restore, the same package can't re-enter until it has left the front, then a 10-minute cooldown. Daily budget: 30 min of bank mode in total. After that, the bank app has to wait until tomorrow or be opened from a computer-free fallback (none, by design).
5. **Self-heal.** The `PactApp` loop and `StartReceiver` skip only while a snapshot is younger than the cap plus 2 min. Past that, they restore whatever state the watcher is in, so a stuck snapshot can't switch self-heal off for good.
6. **Recovery.** On `PactApp` start and on boot, a leftover snapshot is restored at once. There is no hold through reboot.
7. **One notification**, only when a restore fails. Sessions go to logcat (`PactBank`).

## Tile (only if step 0 finds apps that check at launch)

The Quick Settings tile is labelled "Bank mode". A tap runs `BankMode.enter`. It then waits until `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` no longer lists Pact, with a 2 s timeout. Reading the setting back proves the write, not that the system has unbound the service. Then it opens the app chosen in setup, or the last bank app used. The same watch and restore rules apply. Pact can't force-stop another app on Android 14+, so a bank app that already caught the service has to be swiped away by hand once.

## Config

The bank list and what to switch off are hardcoded in `FocusStore` from step 0's results, and editable in one "Banking apps" list in setup. There is no picker with pre-ticks and no history screen.

## Files

- new `enforce/BankMode.kt`: snapshot, enter, watch, restore, recovery
- new `enforce/BankTileService.kt`: only if needed
- `enforce/SecureSettings.kt`: global settings writes
- `enforce/PactAccessibilityService.kt`: trigger on a bank window
- `PactApp.kt`, `enforce/StartReceiver.kt`: self-heal skip with an age limit, recovery
- `data/FocusStore.kt`: bank list, cooldown, daily budget
- `ui/setup/SetupScreen.kt`: Banking apps list
- `AndroidManifest.xml`: tile service, and foreground-service permissions only if step 0.4 needs them

## Risks

- **The focus loophole** is limited to bank apps and neutral apps, at most 20 min a session and 30 min a day. A blocked app ends it instantly.
- **Not fixable:** Google Pay 356's reboot latch, device-integrity blocks (root, unlocked bootloader), and Google Pay's overlay check.
- **Kill criteria, after 7 days:**
  - Bank mode averages more than 20 min a day.
  - Any blocked app sits in front for more than 2 s while bank mode is on.
  - A restore stays failed for more than 1 h.

  If any of these happen, cut bank mode back to the tile only.
