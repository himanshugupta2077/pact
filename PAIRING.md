# Pact pairing

This file is for two readers.

1. **Your friend** — why Pact exists, how the pair works, what they do on the phone and what their partner does as reviewer.
2. **An AI (or engineer) building the reviewer server** — the exact protocol the Android app already speaks, so a toolkit on a separate VPS can approve and deny requests.

The Android APK is only half of Pact. Limits are useless if the same person can undo them. The other half is a small HTTPS server plus a bound reviewer device (iPhone or Android) that is the only thing allowed to say yes.

---

# Part 1 — For your friend

## What this is

Pact is a phone locker you set up when you are calm, then cannot quietly disable when you are not.

You pick apps (Instagram, YouTube, …) or a group of them (for example “Social”). You give them:

- a **daily minute limit**, and/or
- a **time window** (allowed only between two times, or blocked between two times).

When the limit is hit, or you are outside the window, Pact covers the app. Audio from that app is paused. Time spent staring at the lock screen is **not** counted as more screen time.

You cannot change those rules from the phone alone. You also cannot give yourself extra minutes. A second person has to approve it.

That second person is the **reviewer**. They are not a parent-control company. They are a friend, partner, or sibling with a web inbox on a server you both trust.

## The pair

| Role | Device | Job |
| --- | --- | --- |
| You | Android phone with Pact | Use the phone. Hit limits. Ask for extra time or for permission to edit rules. |
| Reviewer | iPhone or Android browser | Open `/pact`, unlock with Face ID / biometrics, approve or deny. |

The reviewer never types a PIN into your phone. There is no shared password you can reuse at 1am. Each ask is a one-shot request: which app or group or settings, how many minutes, and a short comment. The reviewer can grant a different duration than you asked for (up to 4 hours). When that clock runs out, the lock comes back by itself.

## A normal day

1. You installed Pact and granted **Usage access**, **Accessibility**, and **Notification access**.
2. Together you pointed the phone at the reviewer’s server URL and bound one reviewer device.
3. The reviewer approved a **settings** request so you could set limits while the edit window was open.
4. After that window closed, limits stuck.
5. You use Instagram until the daily cap. Pact covers it.
6. If you really need ten more minutes, you tap **Request override**, pick 1–15 minutes, write why, send.
7. The reviewer sees it in the inbox, approves or denies.
8. The phone notices within a few seconds. If approved, that app (or every app in that group) works until the grant expires. Settings grants only unlock **editing**, not the blocked apps.

Hard rules the phone and server both enforce:

- One request per app / per group / for settings, per calendar day.
- You must write a comment.
- You may ask for at most 15 minutes. The reviewer may grant 1–240 minutes.
- A pending ask dies at local midnight if nobody answered.
- Changing daily limits, time windows, groups, or the server URL needs a **settings** approval, then you may edit only for the granted minutes.

## What you need besides the APK

The APK does not include the reviewer. Someone has to run a small server (a VPS is fine) that implements the API in Part 2, serve the inbox page at `https://your-host/pact/`, and keep that host on HTTPS.

On a **fresh install**, you can save the server URL once with no approval. After the phone has sent any request, the URL locks until a settings grant. So: set the real URL first, then bind the reviewer, then request settings, then set limits.

Do **not** point the phone at a server you control yourself if the whole point is that you cannot approve your own unlocks.

## iPhone vs Android reviewer

The current production inbox was built around one iPhone: bind once in Safari with a platform passkey (Face ID), then the same iCloud key can unlock later. The protocol is standard WebAuthn. An Android reviewer works the same way in Chrome if the server allows non-iPhone user agents and a platform authenticator (or a passkey). Part 2 tells the implementer how to drop the iPhone-only check.

One reviewer credential per server. Do not bind two people to the same inbox unless you deliberately change that.

---

# Part 2 — For the AI implementing the toolkit

Rebuild a reviewer module that the existing Android app can talk to **without changing the APK**, unless you also recompile the phone with a new `DEFAULT_PACT_SERVER`.

The phone code lives in `app/src/main/java/app/pact/override/`. The reference server (not shipped inside this repo) is a FastAPI app serving JSON under `/api/pact` and an inbox at `/pact/`.

## Architecture

```
Android phone                         Reviewer VPS                         Reviewer phone
─────────────────                     ────────────                        ──────────────
OverrideClient                        HTTPS API
  POST /api/pact/requests  ─────────► create (no cookie)
  GET  /api/pact/requests/{id} ─────► poll status (no cookie)

OverrideSync poll 2.5s while waiting, 12s idle
  approved + expires_at_ms ─────────► RuleEngine grants
                                        app  → skip block for that package
                                        group → skip block for every member
                                        settings → RuleRepository / GroupRepository
                                                   accept writes until expiry

                                      GET  /api/pact            (session)
                                      POST .../approve|deny     (session)
                                      WebAuthn bind / unlock  ◄── Safari / Chrome
                                      GET  /pact/  inbox HTML
```

The phone **never** sends the reviewer’s cookie. Creating and polling requests is unauthenticated except that the server should not be open to the public internet (Tailscale, WireGuard, or equivalent). Approve, deny, and the inbox **require** a bound WebAuthn session.

## Phone configuration

| Item | Where | Notes |
| --- | --- | --- |
| Base URL | `OverrideStore.serverUrl()` SharedPreferences `pact_overrides` / `server_url` | No trailing slash. Default compile-time `DEFAULT_PACT_SERVER` in `OverrideModels.kt`. |
| Device id | `pact-` + UUID, persisted as `device_id` | Sent on every create. |
| Client | `HttpURLConnection`, JSON, 8s connect / 15s read | |

On a store with **zero** override records, the phone allows saving a new server URL. After any request is stored locally, changing the URL requires an active **settings** grant.

If you ship a custom APK, set `DEFAULT_PACT_SERVER` to the friend’s VPS before `./gradlew :app:installDebug`.

## HTTP API the phone already uses

All JSON. Errors are HTTP 4xx with body:

```json
{ "detail": { "error": "already_requested_today", "message": "…", "request": { } } }
```

`OverrideClient` also accepts `detail` as a string. On success, create/get wrap the row:

```json
{ "ok": true, "request": { … } }
```

The client also accepts a bare request object (no wrapper).

### `POST /api/pact/requests` — phone, no session

Body:

```json
{
  "device_id": "pact-…",
  "kind": "app" | "group" | "settings",
  "target_id": "com.instagram.android" | "<group-uuid>" | "settings",
  "package_name": "com.instagram.android" | "group.<id>" | "pact.settings",
  "app_label": "Instagram",
  "minutes": 5,
  "comment": "need to reply to X"
}
```

Server must:

- Require `device_id` matching `^[A-Za-z0-9._:-]{8,80}$`
- Require `kind` ∈ {`app`,`group`,`settings`}
- Require non-empty `comment`, max 400 chars
- Clamp **requested** minutes to **1..15**
- Normalize:
  - settings → `target_id=settings`, `package_name=pact.settings`
  - group → `package_name=group.{target_id}`
  - app → `target_id=package_name`
- Reject a second request the same **local calendar day** for the same `(device_id, kind, target_id)` with **409** `already_requested_today` (include the existing row so the phone can adopt it)
- Assign `id` (16 hex chars is fine), `status=pending`, `day=YYYY-MM-DD` in the server timezone, `created_at` ISO-8601, `created_at_ms` epoch millis
- `expires_at` / `expires_at_ms` stay empty until approve

Timezone: use one explicit zone (reference uses `Asia/Kolkata` via `PACT_TZ`). Midnight expiry and “once per day” are in that zone, not UTC.

### `GET /api/pact/requests/{id}` — phone, no session

Return the public view of that request. Used as a poll. If pending and `day` is yesterday, mark `expired` and persist.

### Public view fields the phone reads

| Field | Use on phone |
| --- | --- |
| `id` | Stored as `pendingId` while `status=pending` |
| `status` | `pending` / `approved` / `denied` / `expired` |
| `kind` | `app` / `group` / `settings` |
| `target_id` | package, group id, or `settings` |
| `package_name` | fallback target |
| `app_label` | display |
| `minutes` | granted minutes after approve; requested minutes while pending |
| `comment` | echoed |
| `day` | local day string; phone treats same-day denied/used as spent |
| `expires_at_ms` | grant end; phone sets `overrideUntilEpochMs` from this |
| `live` | `approved` and `expires_at_ms` still in the future |

If `status=approved` and `expires_at_ms` is 0, the phone falls back to `now + minutes * 60_000`.

### `GET /api/pact` — reviewer session required

Inbox bootstrap:

```json
{
  "ok": true,
  "max_minutes": 15,
  "max_approve_minutes": 240,
  "pending": [ /* public views */ ],
  "recent": [ /* last ~40 non-pending */ ]
}
```

### `POST /api/pact/requests/{id}/approve` — reviewer session required

Optional body `{ "minutes": 30 }`. If omitted, grant the requested minutes. Allowed grant range **1..240**, even if the phone asked for less. Set:

- `status=approved`
- `minutes` = granted
- `expires_at` / `expires_at_ms` = now + granted minutes
- `decided_at` / `decided_at_ms`

Reject if not pending, or if the request’s `day` is not today (expire it, 409 `expired`).

### `POST /api/pact/requests/{id}/deny` — reviewer session required

Set `status=denied` and `decided_at`. Same pending/day checks.

### Static page

`GET /pact` and `GET /pact/` → inbox HTML. The page:

1. Calls `GET /api/pact/auth` → `{ bound, unlocked }`
2. If not bound: **Bind this device** → WebAuthn register (once)
3. If bound but locked: **Unlock** → WebAuthn assert (Face ID / biometrics)
4. If unlocked: list pending cards (kind, label, comment, requested minutes), chips plus custom duration, Approve / Deny
5. `POST /api/pact/requests/{id}/approve` with `{minutes}` or deny

Keep the page installable as a home-screen web app if you want an iPhone “Pact” tile.

## WebAuthn (reviewer bind)

Store **one** credential in `reviewer.json` (or equivalent):

```json
{
  "bound": true,
  "user_id": "<b64url>",
  "credential_id": "<b64url>",
  "public_key": "<b64url>",
  "sign_count": 0,
  "bound_at": 1710000000,
  "session_secret": "<hex 32 bytes>"
}
```

Endpoints:

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/pact/auth` | `{ ok, bound, unlocked }` from cookie `pact_reviewer` |
| POST | `/api/pact/auth/register/options` | `generate_registration_options`, platform authenticator, UV required, resident key preferred |
| POST | `/api/pact/auth/register` | `verify_registration_response`, persist credential, set session cookie |
| POST | `/api/pact/auth/assert/options` | allowCredentials = stored id, UV required |
| POST | `/api/pact/auth/assert` | verify, mint session |

Session cookie: httponly, samesite=lax, secure on HTTPS, path `/`, ~7 days. Token = `b64url(json {v:1, exp, cid}).hmac_sha256(session_secret)`.

RP ID = request Host (no port). Origin must match (Tailscale `*.ts.net` is HTTPS).

**iPhone-only bind** in the reference server is a user-agent check (`iphone` / `ipod`) on register. To support an Android reviewer, **do not** require that UA. Keep platform authenticators + user verification. Chrome on Android and Safari on iPhone both work as WebAuthn clients over HTTPS. Binding remains **once**: `already_bound` if a credential exists.

The Android **Pact user** never talks to these auth routes.

## How the phone applies a grant

`OverrideSync.runLoop()`:

- If the wall clock jumped backwards by >2 minutes, clear active grants (anti-rollback).
- Poll every pending `pendingId`.
- Push maps into `RuleEngine.replaceGrants(apps, groups, settingsUntilMs)`.

Phases (`OverrideStore.snapshot`):

| Phase | Meaning |
| --- | --- |
| AVAILABLE | Can send a request |
| WAITING | `pendingId` set |
| ACTIVE | `overrideUntilEpochMs > now` |
| DENIED | same local day, last status denied |
| USED | same local day, last status anything else (approved that already expired, etc.) |

`RuleEngine.shouldBlock`:

1. Protected packages (Pact, launcher, Settings, dialer, permission controller, System UI) → never block
2. If app grant or parent group grant is live → do not block
3. App daily limit
4. Group daily limit (sum of members)
5. App time window
6. Group time window

Windows: `ALLOW` = usable only inside `[start, end)`; `BLOCK` = locked inside that range. Overnight if `start > end`.

Settings grant does **not** unlock blocked apps. It only flips `canEditSettings()` so `RuleRepository` / `GroupRepository` persist writes (`dailyLimitMinutes`, `windowMode`, members, server URL). Writes no-op when the grant is dead.

Group access grant unlocks **every member package** for the granted window.

Lock overlay “Request override” calls `submit(APP, packageName, …)`. App detail / groups / setup call `submitSettings` or `submitGroup`.

## Persistence on the server

A JSON list of requests is enough (`requests.json`). Keep ~60 days. File lock or equivalent around read-modify-write.

Do not put reviewer secrets in the request file.

## Hosting

- Public HTTPS hostname (or Tailscale HTTPS). WebAuthn will not bind on raw HTTP except localhost.
- Restrict who can reach `/api/pact/requests` (VPN). The phone has no bearer token.
- Python reference stack: FastAPI + `webauthn` package. Any language is fine if the JSON matches.
- Env: `PACT_TZ` for the reviewer’s “day”.

## Implementation checklist

1. HTTPS reverse proxy + process serving `/pact/` and `/api/pact/*`
2. Request store with create / get / approve / deny and the quotas above
3. One-shot WebAuthn bind + session cookie
4. Inbox UI: pending vs recent, kind badges (App / Group / Settings), custom duration 1–240
5. Friend installs APK, sets server URL on a fresh install, reviewer binds, settings request, set limits
6. Test: lock Instagram → request 5m → approve 10m → phone unlocks → wait out expiry → lock returns
7. Test: settings request → edit a limit → wait out grant → Save is refused
8. Test: second request same app same day → 409, phone shows already requested

## Suggested Android reviewer change

If the partner’s device is Android:

- Remove UA `require_iphone` on register (keep HTTPS + platform authenticator)
- Tell them to open `/pact/` in Chrome, Bind, then Add to Home screen
- Copy for “Unlock with the bound phone” instead of “bound iPhone”

The phone APK does not need a change for that. Only the VPS inbox does.

## Files on the phone (do not reimplement unless forking)

| Path | Role |
| --- | --- |
| `override/OverrideClient.kt` | HTTP |
| `override/OverrideStore.kt` | local records, device id, URL |
| `override/OverrideSync.kt` | submit, poll, push grants |
| `override/OverrideModels.kt` | kinds, 15-minute cap, default URL |
| `engine/RuleEngine.kt` | block decision |
| `data/RuleRepository.kt` | per-app rules, gated by settings grant |
| `data/GroupRepository.kt` | groups, same gate |
| `ui/common/ApprovalCard.kt` | request UI |
| `ui/block/BlockScreen.kt` | lock overlay request |
| `ui/setup/SetupScreen.kt` | permissions + server URL + settings lock |

That is the whole pairing surface. Screen-time charts, usage bars, and the rest of the APK are local-only and do not talk to the VPS.
