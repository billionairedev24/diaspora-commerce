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

The BFF cannot see taps in a native app, so the **idle timeout is enforced on the device**: the app reads
`idleTimeoutSeconds` from `GET /v1/session/policy`, tracks its own last input and signs out when the idle time passes.
The **session length** for apps is the lifetime of the refresh token, which identity sets per OAuth client (and can cap for
all clients in the Settings tab); it is not derived from this policy. What remains: the apps should also stop themselves after
`absoluteTimeoutSeconds` from sign-in, because the BFF does not shorten refresh tokens it did not mint. Native sign-in
(`/v1/auth/native/*`) is unchanged.

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
