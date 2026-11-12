# AfroTransact Delivery — driver app (`@afrotransact/driver-app`)

Expo SDK 57 / React Native 0.86 / expo-router, offline-first. Epic **E12**, client side of **S081–S087**.
Design: `design/AfroTransact Driver.dc.html`. Server contract: `contracts/openapi/fulfilment.yaml` (tag *Driver app*)
and `java/services/fulfilment/README.md` → "Driver app integration". Types are generated into
`src/api/generated/fulfilment.ts`; the app calls fulfilment through its own client (`src/api/client.ts`).

| Story | Client behaviour |
|---|---|
| S081 Sign-in + access + app lock | In-app sign-in, no hosted page or browser: work email + password, then mandatory two-step verification (`POST /v1/auth/native/{password,mfa/sms,mfa/verify}` on gateway-bff checks them with identity and returns an authorization code for the app's own PKCE request, which the app exchanges at identity; public client `driver-app`). Second step: six-box one-time code (autofill/paste, submits at the sixth digit), recovery code, text-message resend after 30 s, "Trust this phone" off by default (identity decides how long). Errors are inline and specific: wrong password (401 `INVALID_CREDENTIALS`), wrong or expired code (422 `INVALID_CODE`), rate limit (429 `RATE_LIMITED`, with the `Retry-After` the server sends, no invented attempt counts), expired challenge (410 `CHALLENGE_EXPIRED`, back to step one). Drivers are staff: no social sign-in, no create account, no in-app password reset or two-step enrolment (no API): help goes to dispatch. A refresh token that identity refuses ends the session ("Your session ended"); an unreachable identity does not. `GET /v1/driver/me` decides: 200 → work; 403 `fulfilment.no-driver-access` → "This account can't drive" (who to ask, Sign out); 403 `fulfilment.driver-access-revoked` or `signOut: true` on a sync → a last sync with the current token, then sign-out — **the SQLite outbox is kept** and syncs later (events captured before `revokedAt` are accepted). Access is re-checked on resume and every 5 min. App lock: see "App lock" below. |
| S082 Inspection | 10 checks (tyres, brakes, lights critical → route blocked + defect to dispatch), odometer ≥ last, fuel 0–100, cold box 33–40 °F when perishables are on the route, 4 photos from the **live camera only** (`expo-camera`; no picker in the app, media-library permissions blocked on Android), defect note, certification and signature. |
| S083 Load-out | Code 128 scanning (`expo-camera` barcode scanner) + manual entry (sent as `method: MANUAL`, flagged in the list). Unknown / already-scanned labels are rejected locally and by the server. |
| S084 Seller pickups | Arrive, scan each package, "handed over by", missing-package reason; problems: not ready (wait/skip), closed, wrong count (partial/skip, photo), damaged (photo). |
| S085 Proof of delivery | Handed to customer (4-digit code, verified by orders-service on sync), someone else (name, relationship, signature), safe place (blocked for perishables and when the customer did not allow it). Photo always, live, **stamped with time, GPS (`expo-location`) and route id** burned into the JPEG (`react-native-view-shot`) plus `{source: LIVE_CAMERA, capturedAt, lat, lon, accuracyM, stampRouteId, sha256}`; distance to the stop ≤ 150 m. |
| S255 Location pings | While on shift (route started, or assigned with a passed inspection) and the app is in the foreground, `POST /v1/driver/location` feeds nearest-driver pickup offers. Throttled (`domain/locationPing`): first fix of the shift, then every 60 s or after 200 m, never within 15 s; stale (> 2 min) or inaccurate (> 500 m) fixes are skipped. Foreground permission only, never prompted here (asked at sign-in), no background location. Pings are skipped offline, never queued (the newest fresh fix is sent on reconnect); a 403 stops them. The newest fix also stamps queued sync events, falling back to a one-off fix. |
| S086 Problems + reattempts | Customer unavailable: call, text and a 5-minute wait timer (`customer.contacted` events) before reattempt / pickup point / return; other problems need notes (and photos for access, damage, unsafe); ≤ 2 reattempts; retry stops must be resolved before the shift ends; failed packages scanned back in. |
| S087 Offline-first + SOS | Every action is an event with a client `eventId` (UUID) and a per-device `sequence` persisted in SQLite (`expo-sqlite`, sequence counter and events in one transaction — never reused). Applied locally at once; synced in order in batches ≤ 200 when online; per-event `APPLIED` / `REJECTED` (shown under Profile, queue continues) / `CONFLICT` (route refetched) / `DEFERRED` (resend from `expectedSequence`). Photos/signatures upload to media-service (`POST /v1/uploads` → PUT → complete) before their event is sent. Header shows **Synced / Syncing N / Offline · N**. SOS is POSTed to `/v1/driver/sos` immediately and stays in the outbox. |

## Run it

On a real phone (Expo Go over Wi-Fi, development build over USB or EAS):
[docs/engineering/mobile-on-your-phone.md](../../../docs/engineering/mobile-on-your-phone.md). Everything except
remote push works in Expo Go (camera, barcode scanning, GPS, SQLite); push needs a development build.

```bash
pnpm install
pnpm --filter @afrotransact/driver-app start:mock   # local env + in-app mock fulfilment/media/identity
pnpm --filter @afrotransact/driver-app start
```

Mocks (`EXPO_PUBLIC_MOCKS=1`, default for `local`): `src/api/mock/server.ts` keeps its own copy of the design's route
(RT-HOU-PM) and applies synced events with the server rules (sequence order, idempotency, label checks, handover codes
4821 / 1934 / 5507, revocation). Demo accounts: `tunde@afrotransact.com` (driver), `daniel@afrotransact.com` (ops admin
with driver access), `kofi@afrotransact.com` (no driver access). Profile has an **Online** switch (simulated loss of
signal) and, in mocks only, "simulate access revoked". The camera screen offers a demo photo only when mocks are on.

A development build is needed on devices (`eas build --profile development`) for the OIDC redirect, camera scanning
and SQLite; the web preview uses a `localStorage` outbox and the browser camera.

## Configuration

The URLs come from the EAS build profile (`eas.json`: `development` = dev, `preview` = uat, `production` = prod;
`development-mocks` = local on mocks); hosts per environment are in `docs/engineering/environments.md`. The code names
no deployed host: `src/config/env.ts` falls back to the local stack on `EXPO_PUBLIC_DEV_HOST` (when that is unset, a development phone uses the address Metro serves its JS from, `src/config/devHost.ts`, so `localhost` — the phone itself — is never tried by accident; otherwise `localhost`; the
dev machine's LAN IP for a physical device, `10.0.2.2` for the Android emulator), and `app.config.ts` fails a dev, uat or
prod build that leaves one of the URL variables unset.

| Variable | local | dev | uat | prod |
|---|---|---|---|---|
| `EXPO_PUBLIC_APP_ENV` | `local` | `dev` | `uat` | `prod` |
| `EXPO_PUBLIC_BFF_URL` | `http://localhost:8200` | `https://api-dev.afrotransact.com/bff` | `…uat…/bff` | `https://api.afrotransact.com/bff` |
| `EXPO_PUBLIC_FULFILMENT_URL` | `http://localhost:8108` | `https://api-dev.afrotransact.com/fulfilment` | `…uat…/fulfilment` | `https://api.afrotransact.com/fulfilment` |
| `EXPO_PUBLIC_MEDIA_URL` | `http://localhost:8207` | `https://api-dev.afrotransact.com/media` | `…uat…/media` | `https://api.afrotransact.com/media` |
| `EXPO_PUBLIC_OIDC_ISSUER` | `http://localhost:8100` | `https://auth-dev.afrotransact.com` | `https://auth-uat.afrotransact.com` | `https://auth.afrotransact.com` |
| `EXPO_PUBLIC_IDENTITY_URL` (push devices) | `http://localhost:8100` | `https://api-dev.afrotransact.com/identity` | `…uat…/identity` | `https://api.afrotransact.com/identity` |
| `EXPO_PUBLIC_OIDC_CLIENT_ID` | `driver-app` | `driver-app` | `driver-app` | `driver-app` |
| `EXPO_PUBLIC_MOCKS` | `1` (default) | `0` | `0` | always off |
| `EXPO_PUBLIC_DEV_HOST` (local stack host) | `localhost` | - | - | - |
| `EXPO_PUBLIC_DISPATCH_PHONE`, `EXPO_PUBLIC_DISPATCH_EMAIL` (optional) | - | - | - | set per profile |

Redirect: native `com.afrotransact.driver:/oauth2redirect`; web dev `http://localhost:8082/auth/callback`
(`expo start --web --port 8082`). Bundle ids `com.afrotransact.driver[.<env>]`.

EAS profiles (`eas.json`): `development` (dev client, `dev`), `development-mocks` (`local` + mocks), `preview`
(`uat`, internal) and `production` (`prod`, store).

The sign-in help line ("Locked out or need an account? Contact dispatch.") always shows; the *Call dispatch* / *Email dispatch* actions
show only when `EXPO_PUBLIC_DISPATCH_PHONE` / `EXPO_PUBLIC_DISPATCH_EMAIL` are set in the build profile (no number is baked into the app).

## Identity client registration needed (`driver-app`)

Seeded in identity's `application-{local,dev,uat,prod}.yml`: public client, `authorization_code` + `refresh_token`,
PKCE, rotating public refresh tokens (14 h TTL), scopes `openid profile email offline_access`, redirect
`com.afrotransact.driver:/oauth2redirect` (+ local `http://localhost:8082/auth/callback`). The access token must carry
`perms` with `delivery:operate`. Push registration (`POST /v1/me/devices`, `app: driver`) also requires
`delivery:operate`.

## App lock

After the first successful sign-in on a phone a short setup runs: offer Face ID / biometrics (only when the phone has them and the build can use them),
then create and confirm a 6-digit passcode (not one repeated digit; there is no skipping it). After that the app opens locked
(biometrics tried automatically, passcode keypad as the fallback, "Welcome back, <first name>") and locks again when it returns from the background after
30 s (`src/domain/lockPolicy.ts`). Profile has a *Face ID unlock* switch and *Lock now*. Same rules as the customer app (copies of its pure modules).

- **A layer, not a sign-out.** `src/session/LockProvider.tsx` only decides whether `src/features/lock/LockLayer.tsx` draws the lock screen over the navigator.
  The navigator stays mounted (a stop, scan or signature in progress is where you left it; the hardware back button is swallowed while locked and the screens
  underneath are hidden from screen readers). The lock modules cannot reach the sync engine, the outbox or the location pinger (`test/lockIsolation.test.ts`),
  and `DriverProvider` does not know about the lock: sync and location pings of a driver on shift keep running while locked. The barcode camera stays
  mounted but stops reading labels while locked.
- **Five wrong passcodes** (counter persisted in the secure store, survives restarts) sign the driver out through `DriverProvider.signOut`, exactly like
  Profile → Sign out: last best-effort sync, tokens revoked, **the SQLite outbox stays on the phone** and syncs when the same driver signs in again. The sign-in screen says why.
- **Storage.** Only a salted SHA-256 hash of the passcode, the Face ID flag, a setup flag and the attempts counter, all in the secure store (`at.driver.*`);
  nothing is logged. Signing out (any way, including revocation or an expired session) removes them from the phone, so the next sign-in sets up again.
- **Face ID** needs our own build (`NSFaceIDUsageDescription` and the `expo-local-authentication` plugin are in `app.config.ts`); in Expo Go on iOS it is
  unavailable and the passcode is the unlock. Android biometrics work in Expo Go.
- Not built (no API yet): in-app two-step enrolment, forgot password, devices and sessions list. Sign-out is not blocked mid-route.

## Sign-out after inactivity

The driver app signs the driver out after **14 hours without use** or **14 hours after sign-in** (defaults, one shift; staff can change them, see
[docs/engineering/session-timeouts.md](../../../docs/engineering/session-timeouts.md)).

Same rules in both apps (`src/domain/sessionPolicy.ts` and `sessionGuard.ts` are identical copies; keep them in step).

- **Policy.** `GET /v1/session/policy` on the gateway-bff (`audience`, `idleTimeoutSeconds`, `absoluteTimeoutSeconds`, `warnSeconds`; `lastActivityAt` and
  `expiresAt` are null for apps). Fetched after sign-in and on resume at most once an hour, always with `X-Background: 1` so it never counts as activity and `X-App: driver`.
  Validated on arrival (bounds, audience); a failed or invalid answer keeps the cached policy, which survives restarts in the secure store, or the built-in default
  (14 h idle, 14 h absolute). Staff change the numbers per audience; a new value applies at the next fetch.
- **Idle** is measured from the last real foreground interaction; time in the background counts as idle. **Absolute** is measured from sign-in, however active
  the person is. Both are decided on the device, because the BFF cannot see taps in a native app or cap a refresh token.
- **When it is checked:** at cold start (before the lock screen), on every resume (before the policy is re-read), and by a timer while the app is in the
  foreground (at the next limit, at most a minute apart).
- **Clock safety.** The sign-in time and last activity are stored as wall-clock times, with a high-water mark of the latest time ever seen. "Now" is never earlier
  than that mark, so moving the phone clock backwards never extends a session (it makes the app ask the server, a forced policy read); moving it forwards ends the
  session early. A tap on an already expired session does not revive it.
- **Lock is not sign-out.** The 30-second lock is untouched. Touches on the lock screen are not activity; a successful unlock is.
- **Activity is touches only** (root responder capture in `src/app/_layout.tsx`, the keyboard opening, unlocking, "Stay signed in"). **Location pings, the sync engine,
  push registration and the access re-check are background work and never count**, and nothing in this feature starts, stops or delays them (`test/sessionSignOut.test.ts`
  scans that the engine, pinger, location and push code do not reference the session clock, and that only the layout, `DriverProvider` and `LockProvider` record activity).
- **Ending the session** is `DriverProvider.signOut` (via `src/session/endSession.ts`): a last best-effort sync with the token that is still valid, then the push token and
  tokens go and the sign-out clock is forgotten. **The SQLite outbox, its sequence counter and the cached route stay on the phone**; unsynced events go out when the same driver
  signs in again (`test/sessionSignOut.test.ts` proves this against the real sync engine, offline and online). If the limit passes mid-route the driver is signed out only
  after that sync attempt. The sign-in screen shows the reason in the driver's language (`dr.session.idle` / `dr.session.absolute`, both say unsent updates are kept on the
  phone). A 401 `SESSION_IDLE_TIMEOUT` / `SESSION_ABSOLUTE_TIMEOUT` takes the same path.
- **Warning** (only if `warnSeconds` is above 0; the default is none): "Still there?" bottom sheet over the unlocked app, never over the lock screen.
- Policy types for the BFF endpoint are hand-written (`SessionPolicyBody` in `src/api/client.ts`): this app generates only fulfilment and identity types.

## Structure

```
src/domain/    pure TS (unit-tested): outbox (sequencing, batching, outcomes, SOS, status), inspection, scan, POD,
               problems + 5-minute timer, routeState reducer, shift rules, access decision, uploads
src/sync/      SyncEngine (injected store/api/clock → tested with fakes)
src/storage/   SQLite store (expo-sqlite) + memory/localStorage store (tests, web)
src/api/       bearer requester, fulfilment/media/identity client, contract → domain mapping, generated types, mock
src/session/   DriverProvider: OIDC session, access checks, revocation, network state, push registration;
               LockProvider: app lock state (setup / locked / unlocked), passcode + biometrics, lock on resume
src/auth/      native sign-in glue, OIDC tokens, secure storage, biometrics
src/features/  auth (password form, two-step, hero), lock (setup, lock screen, layer), LiveCamera (stamped live photos), BarcodeCamera, SignaturePad
src/app/       sign-in, no-access, (app)/(tabs)/{index,route,scan,profile}, inspect, scan/[ctx], stop/[id], end
```

## Checks

```bash
pnpm --filter @afrotransact/driver-app typecheck
pnpm --filter @afrotransact/driver-app lint
pnpm --filter @afrotransact/driver-app test             # Vitest: domain, engine, mock-server contract behaviour
pnpm --filter @afrotransact/driver-app check:generated
pnpm --filter @afrotransact/driver-app export:web
```

## Known gaps

- The live stream `GET /v1/driver/stream` (SSE `session.revoked`, `route.updated`, …) is not consumed: React Native's
  fetch has no streaming body. Revocation is caught by `/me` re-checks and `signOut` on sync; route changes by refetch.
- Customer instructions/contact come from orders-service `GET /v1/shipments/{id}/delivery-brief`, which is not wired
  yet (the mock route carries them); calls/texts should go through a masked-number service — the app records
  `customer.contacted` and only dials when an unmasked number is available.
- The fulfilment contract types most fields as optional strings (enums, ids); the app keeps strict domain types in
  `src/domain/types.ts` and maps them in `src/api/mapRoute.ts`. Route payloads have no depot name, vehicle label or
  package description (`depotNodeId`, `vehicleId`, `orderNumber` are shown instead).
- Queued events belong to the driver who captured them: if another driver signs in on the same phone, they wait for
  the first driver to sync.
