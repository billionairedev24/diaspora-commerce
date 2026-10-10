# AfroTransact Delivery — driver app (`@afrotransact/driver-app`)

Expo SDK 57 / React Native 0.86 / expo-router, offline-first. Epic **E12**, client side of **S081–S087**.
Design: `design/AfroTransact Driver.dc.html`. Server contract: `contracts/openapi/fulfilment.yaml` (tag *Driver app*)
and `java/services/fulfilment/README.md` → "Driver app integration". Types are generated into
`src/api/generated/fulfilment.ts`; the app calls fulfilment through its own client (`src/api/client.ts`).

| Story | Client behaviour |
|---|---|
| S081 Sign-in + access | OIDC code + PKCE with identity (public client `driver-app`; password, 6-digit code or passkey on the hosted page). `GET /v1/driver/me` decides: 200 → work; 403 `fulfilment.no-driver-access` → "No driver access" with the grant hint; 403 `fulfilment.driver-access-revoked` or `signOut: true` on a sync → a last sync with the current token, then sign-out — **the SQLite outbox is kept** and syncs later (events captured before `revokedAt` are accepted). Access is re-checked on resume and every 5 min. |
| S082 Inspection | 10 checks (tyres, brakes, lights critical → route blocked + defect to dispatch), odometer ≥ last, fuel 0–100, cold box 33–40 °F when perishables are on the route, 4 photos from the **live camera only** (`expo-camera`; no picker in the app, media-library permissions blocked on Android), defect note, certification and signature. |
| S083 Load-out | Code 128 scanning (`expo-camera` barcode scanner) + manual entry (sent as `method: MANUAL`, flagged in the list). Unknown / already-scanned labels are rejected locally and by the server. |
| S084 Seller pickups | Arrive, scan each package, "handed over by", missing-package reason; problems: not ready (wait/skip), closed, wrong count (partial/skip, photo), damaged (photo). |
| S085 Proof of delivery | Handed to customer (4-digit code, verified by orders-service on sync), someone else (name, relationship, signature), safe place (blocked for perishables and when the customer did not allow it). Photo always, live, **stamped with time, GPS (`expo-location`) and route id** burned into the JPEG (`react-native-view-shot`) plus `{source: LIVE_CAMERA, capturedAt, lat, lon, accuracyM, stampRouteId, sha256}`; distance to the stop ≤ 150 m. |
| S255 Location pings | While on shift (route started, or assigned with a passed inspection) and the app is in the foreground, `POST /v1/driver/location` feeds nearest-driver pickup offers. Throttled (`domain/locationPing`): first fix of the shift, then every 60 s or after 200 m, never within 15 s; stale (> 2 min) or inaccurate (> 500 m) fixes are skipped. Foreground permission only, never prompted here (asked at sign-in), no background location. Pings are skipped offline, never queued (the newest fresh fix is sent on reconnect); a 403 stops them. The newest fix also stamps queued sync events, falling back to a one-off fix. |
| S086 Problems + reattempts | Customer unavailable: call, text and a 5-minute wait timer (`customer.contacted` events) before reattempt / pickup point / return; other problems need notes (and photos for access, damage, unsafe); ≤ 2 reattempts; retry stops must be resolved before the shift ends; failed packages scanned back in. |
| S087 Offline-first + SOS | Every action is an event with a client `eventId` (UUID) and a per-device `sequence` persisted in SQLite (`expo-sqlite`, sequence counter and events in one transaction — never reused). Applied locally at once; synced in order in batches ≤ 200 when online; per-event `APPLIED` / `REJECTED` (shown under Profile, queue continues) / `CONFLICT` (route refetched) / `DEFERRED` (resend from `expectedSequence`). Photos/signatures upload to media-service (`POST /v1/uploads` → PUT → complete) before their event is sent. Header shows **Synced / Syncing N / Offline · N**. SOS is POSTed to `/v1/driver/sos` immediately and stays in the outbox. |

## Run it

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
no deployed host: `src/config/env.ts` falls back to the local stack on `EXPO_PUBLIC_DEV_HOST` (default `localhost`; the
dev machine's LAN IP for a physical device, `10.0.2.2` for the Android emulator), and `app.config.ts` fails a dev, uat or
prod build that leaves one of the URL variables unset.

| Variable | local | dev | uat | prod |
|---|---|---|---|---|
| `EXPO_PUBLIC_APP_ENV` | `local` | `dev` | `uat` | `prod` |
| `EXPO_PUBLIC_FULFILMENT_URL` | `http://localhost:8108` | `https://api.dev.afrotransact.com/fulfilment` | `…uat…/fulfilment` | `https://api.afrotransact.com/fulfilment` |
| `EXPO_PUBLIC_MEDIA_URL` | `http://localhost:8207` | `https://api.dev.afrotransact.com/media` | `…uat…/media` | `https://api.afrotransact.com/media` |
| `EXPO_PUBLIC_OIDC_ISSUER` | `http://localhost:8100` | `https://auth.dev.afrotransact.com` | `https://auth.uat.afrotransact.com` | `https://auth.afrotransact.com` |
| `EXPO_PUBLIC_IDENTITY_URL` (push devices) | `http://localhost:8100` | `https://api.dev.afrotransact.com/identity` | `…uat…/identity` | `https://api.afrotransact.com/identity` |
| `EXPO_PUBLIC_OIDC_CLIENT_ID` | `driver-app` | `driver-app` | `driver-app` | `driver-app` |
| `EXPO_PUBLIC_MOCKS` | `1` (default) | `0` | `0` | always off |
| `EXPO_PUBLIC_DEV_HOST` (local stack host) | `localhost` | - | - | - |

Redirect: native `com.afrotransact.driver:/oauth2redirect`; web dev `http://localhost:8082/auth/callback`
(`expo start --web --port 8082`). Bundle ids `com.afrotransact.driver[.<env>]`.

EAS profiles (`eas.json`): `development` (dev client, `dev`), `development-mocks` (`local` + mocks), `preview`
(`uat`, internal) and `production` (`prod`, store).

## Identity client registration needed (`driver-app`)

Seeded in identity's `application-{local,dev,uat,prod}.yml`: public client, `authorization_code` + `refresh_token`,
PKCE, rotating public refresh tokens (14 h TTL), scopes `openid profile email offline_access`, redirect
`com.afrotransact.driver:/oauth2redirect` (+ local `http://localhost:8082/auth/callback`). The access token must carry
`perms` with `delivery:operate`. Push registration (`POST /v1/me/devices`, `app: driver`) also requires
`delivery:operate`.

## Structure

```
src/domain/    pure TS (unit-tested): outbox (sequencing, batching, outcomes, SOS, status), inspection, scan, POD,
               problems + 5-minute timer, routeState reducer, shift rules, access decision, uploads
src/sync/      SyncEngine (injected store/api/clock → tested with fakes)
src/storage/   SQLite store (expo-sqlite) + memory/localStorage store (tests, web)
src/api/       bearer requester, fulfilment/media/identity client, contract → domain mapping, generated types, mock
src/session/   DriverProvider: OIDC session, access checks, revocation, network state, push registration
src/features/  LiveCamera (stamped live photos), BarcodeCamera, SignaturePad
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
