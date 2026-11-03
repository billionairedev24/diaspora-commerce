# Sign-out after inactivity and after a maximum session length

People are signed out of the web apps after a configured time without activity, and after a configured total session
length, per audience. Staff edit the numbers; the gateway-bff enforces them on its cookie sessions; the mobile apps run
the idle timer on the device. (Identity's own hosted sign-in session has separate idle and absolute settings, see
[identity-admin.md](identity-admin.md); they do not replace this policy.)

## The policy

| Audience (`audience`) | Who | Idle timeout | Session length | Warning |
|---|---|---|---|---|
| `staff` | Staff console | 15 min | 8 h | 60 s |
| `seller` | Seller Center, Provider Hub | 30 min | 12 h | 60 s |
| `customer-web` | Storefront | 30 days | 90 days | none |
| `customer-app` | Customer mobile app | 30 days | 90 days | none |
| `driver` | Driver app | 14 h (end of shift) | 14 h | none |

- **Idle timeout**: no real user activity for this long ends the session.
- **Session length** (absolute): this long after sign-in ends the session, however active the person is.
- **Warning**: clients show "You'll be signed out in N seconds" this long before the idle timeout. 0 means no warning
  (audiences whose idle timeout is a day or more).

Bounds (identity validates and answers 422 with a message per field): idle 1 minute to 180 days; session length 5 minutes
to 365 days; idle not longer than the session length; warning 0 to 300 s and shorter than idle.

## How staff change it

Staff console, Developer tools, Identity & access, **Sign-out** tab (needs `identity:admin`). One row per audience, shown
by name. Open a row, Edit: three numbers, a reason (10 to 500 characters) and the staff member's own password (step-up,
like the other identity settings). Saving is disabled until a value changes. A value equal to the standard setting is
stored as "use the standard setting", so it keeps following the default. Every change is written to identity's access
change log (`session-policy.updated`, audience, reason, before and after). Identity stores only the overrides
(`session_policy_override`); the defaults live in code (`SessionPolicyDefaults.java`, mirrored in
`go/services/gateway-bff/internal/domain/session_policy.go`: change both together).

A change reaches every gateway-bff replica within a minute (it caches the policy for 60 s) and applies to people who are
already signed in.

## How the gateway-bff enforces it (cookie sessions)

1. At sign-in the session records its **audience**: the web app the person signed in at (storefront, Seller Center, staff
   console). A person who is staff but signs in at the storefront gets the storefront's policy there; each app has its own
   cookie and session.
2. The session stores `CreatedAt` and `LastActivityAt`. On every `/v1` request the BFF reads the audience's policy and, if
   `now - LastActivityAt > idle` or `now - CreatedAt > absolute`, ends the session: tokens and profile are cleared and saved
   (guest cart and ZIP stay), the refresh and access tokens are revoked at identity (best effort), and the request is
   answered **401** with an RFC 9457 problem whose `code` is `SESSION_IDLE_TIMEOUT` or `SESSION_ABSOLUTE_TIMEOUT`.
   `/v1/auth/*` is never answered 401 by this (the session is ended and the request continues as a guest), so signing in again
   works at once. An expired session is never refreshed.
3. For ten minutes afterwards `GET /v1/me` answers `authenticated: false` with `signedOutReason`, so the app can say why even
   when a parallel request received the 401.
4. Errors never extend a session: a failed Valkey write leaves the stored times unchanged, a legacy session that cannot be
   stamped answers 503, and the policy fallback below is never "no timeout".

### Activity, and why background polling must not count

`LastActivityAt` moves forward on ordinary requests, written to Valkey at most once per 30 s per session (shorter when the
idle timeout is under two minutes), so it can lag real activity by up to 30 s: a session can end up to 30 s early, never
late.

Web apps poll the API in the background (TanStack Query `refetchInterval`, notification counts, token pings). If that counted as
activity, a tab left open would never time out. So:

- Every request the app makes **without the person doing anything** must send **`X-Background: 1`**. The BFF still checks the
  session against the policy (an expired session is refused) but does not count it as activity and extends nothing.
- `GET /v1/session/policy` never counts as activity, with or without the header.
- Everything else counts. A person who is only reading a page makes no requests, so the web app reports real input with
  **`POST /v1/session/activity`** (204): on pointer, key, touch and scroll events, when the tab becomes visible again, and
  from the "Stay signed in" button. Throttle it client-side to about once a minute. The server writes at most once per 5 s.
  Do not send it from timers.

### What clients call

- `GET /v1/session/policy` (any caller, cookie or bearer):
  `{audience, idleTimeoutSeconds, absoluteTimeoutSeconds, warnSeconds, lastActivityAt, expiresAt}`.
  For cookie sessions `expiresAt = min(lastActivityAt + idle, signIn + absolute)`; a web app shows the warning at
  `expiresAt - warnSeconds` and re-reads the policy after reporting activity. The same object is the `session` member of
  `GET /v1/me` (web callers). Send `X-App: driver` from the driver app; anything else is the customer app.
- Bearer (mobile) callers get `lastActivityAt` and `expiresAt` = `null`.

### Mobile apps

The BFF cannot see taps in a native app, so the **idle timeout is enforced on the device**: the app reads `idleTimeoutSeconds` from
`GET /v1/session/policy`, tracks its own last interaction and signs out when the idle time passes. The BFF also cannot shorten refresh tokens it
did not mint (the lifetime of a refresh token is set per OAuth client in identity), so the apps **also stop themselves after
`absoluteTimeoutSeconds` from sign-in**. Native sign-in (`/v1/auth/native/*`) is unchanged.

What the apps do (customer app and driver app share the same pure modules, `src/domain/sessionPolicy.ts` and `sessionGuard.ts`):

| | Customer app | Driver app |
|---|---|---|
| Policy request | `GET /v1/session/policy` | same, plus `X-App: driver` |
| Defaults until fetched (fail-safe) | 30 d idle, 90 d absolute | 14 h idle, 14 h absolute |
| Counts as activity | touches, keyboard, unlock, user-started API writes | touches, keyboard, unlock (never pings, sync, push, access re-check) |
| On expiry | existing sign-out (tokens revoked, passcode and Face ID removed), message on sign-in | existing sign-out: last best-effort sync, **outbox kept**, message on sign-in |

- The policy request is sent **with `X-Background: 1`** (it never counts as activity, with or without the header), after sign-in and on resume at most
  once an hour. A failed, malformed, out-of-bounds or other-audience answer is ignored; the cached policy (secure store, survives restarts) or the default
  keeps applying. The cached copy is never "no timeout".
- Idle runs from the last real foreground interaction (throttled to 30 s, written lazily, always when the app goes to the background); **time in the background is idle
  time**. Absolute runs from sign-in. Checked at cold start, on every resume and by a foreground timer (at most a minute apart).
- The device stores `signedInAt`, `lastActivityAt` and a high-water mark of the latest wall-clock time seen. "Now" is never earlier than the mark, so setting the clock
  back never extends a session and makes the app re-read the policy; setting it forward ends it early. A session that predates this feature gets one fresh window from
  the first launch with it (same as legacy web sessions).
- A 401 with `SESSION_IDLE_TIMEOUT` / `SESSION_ABSOLUTE_TIMEOUT` (when the BFF answers it for an app caller) is mapped to the same sign-out and message, and is not refreshed.
- The 30-second app lock is a separate layer and is unchanged; the lock is not a sign-out, touches on the lock screen are not activity.
- Sign-in screen messages: `cu.session.idle` / `cu.session.absolute` (customer), `dr.session.idle` / `dr.session.absolute` (driver), in the five locales
  (en, fr, es, ar, yo; machine translations flagged `needsReview`). An optional "Still there?" sheet appears only when `warnSeconds` is above 0 and the app is in the
  foreground and unlocked.
- Limits: the device clock and the secure store are the device's; a rooted phone can defeat the idle timer. The server-side backstop is the refresh-token
  lifetime in identity. Staff changes reach a phone at its next policy fetch (up to an hour after a resume).

## When identity is unreachable (fail safe)

The BFF reads `GET /v1/internal/session-policy` from identity with its service token (scope `sessions:policy.read`) and caches
it for 60 s. If identity cannot be read, it applies, per audience, the **stricter of the last policy it read and the built-in
default** until identity answers again (retried every 10 s). With no earlier read, the built-in defaults apply, so staff and
sellers always keep their short windows. An unusable value from identity (zero, negative, warning not shorter than idle) is
replaced by the default for that audience.

## Configuration

`session.idleTtl` in `go/services/gateway-bff/config/*.yaml` (2208 h) is the Valkey time-to-live of a session and the cookie's
Max-Age, only an **upper bound**. It must be at least 90 days (the longest default session length); the service refuses to
start with less. A policy longer than `idleTtl` is cut at `idleTtl`.

## Sessions created before the policy existed

A stored session without `LastActivityAt` or `CreatedAt` gets the missing value set to the time of its next request, once
(one fresh idle window), and its audience is worked out from the request's `Origin`, or from its roles when there is none
(`platform.*` is staff, `seller.*` and `provider.*` Seller Center). If that write fails the request answers 503 instead of
leaving the session unstamped.

## Verification

- identity: `SessionPolicyRulesTest`, `SessionPolicyServiceImplTest`, `ArchitectureTest`.
- gateway-bff: `internal/app/session_enforce_test.go` (idle, absolute, background, activity ping, write throttling, legacy
  sessions, failing store), `session_policy_test.go` (cache, fail-safe), `internal/adapter/httpapi/session_timeout_test.go`
  (end to end per app), `staff_identity_test.go` (the staff table against `gateway-bff-staff.yaml`).
- staff console: `test/identity-admin.test.tsx`, `packages/api-client/test/staff-identity.test.ts`.
- mobile: `mobile/apps/{customer,driver}/test/sessionPolicy.test.ts`, `sessionGuard.test.ts`, `sessionSignOut.test.ts` (driver: outbox kept on sign-out, background work never counts as activity).
