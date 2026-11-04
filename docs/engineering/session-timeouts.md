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

### Web apps (client behaviour)

The storefront, Seller Center and staff console run one session timer for signed-in people. Guests have no session, so
nothing runs for them. Code: the timer and dialogs in `web/packages/ui/src/session/` (`timeout.ts` is the framework-free
timer), the wiring in `web/packages/account/src/session-timeout.tsx` (`SessionTimeout`, `SessionSignedOutLanding`), the
request layer in `web/packages/api-client/src/{http,session-api,session-end}.ts`, and one thin wrapper per app
(`StorefrontSessionTimeout`, `SellerSessionTimeout`, `StaffSessionTimeout`) that supplies the app's strings, sign-out and
return-to rule. Strings are in each app's catalogue under `session.*` (storefront), `sc.session.*` and `st.session.*`.

**Clock.** The timer works from `expiresAt` of `GET /v1/session/policy` and corrects for a wrong device clock with the
response's `Date` header (whole seconds, so it assumes the later second: the screen can end up to a second early, never
late). The `/api/bff` proxy forwards `Date` and `X-Background`; dropping either breaks the timer.

**Reporting activity.** Real input only: `pointerdown`, `keydown`, `wheel`, `touchstart`, `scroll`, and the tab becoming
visible again. Mouse movement does not count. Input reaches the timer at most once a second and goes to
`POST /v1/session/activity` at most once a minute (every quarter of the idle timeout when that is shorter, every 10 minutes
for day-long timeouts). After a report the expiry moves locally; the policy is read again only when the last read is
older than 5 minutes, before a warning, at the deadline and after "Stay signed in". Reading the policy sends
`X-Background`. Nothing is reported from timers, and nothing is reported while the warning shows (only its button counts)
or when the absolute limit is what ends the session (it cannot be extended).

**Warning.** `warnSeconds` before the end the timer asks the server once more (another tab or window may have kept the
session alive; unreported input is sent first), then opens a dialog (the shared `Dialog`: focus trap, Esc, focus returns):
"You'll be signed out in 0:45 because you've been inactive", a live m:ss countdown, **Stay signed in** (focused first;
reports activity, reads the policy, closes) and **Sign out now**. Esc or the close button keeps the person signed in. The
visible countdown is not a live region; a hidden polite status says it at the start and every 15 seconds, and an
assertive alert says it every second for the last 10. When the session ends at the **absolute limit** the warning says
"For your security you need to sign in again at 4:15 pm" and that staying active cannot extend it; the buttons are
**Got it** (hides it, and it returns for the last 10 seconds) and **Sign out now**. Audiences with `warnSeconds` 0
(customers by default) get no dialog; the 401 handling below still applies.

**The end.** At the deadline the timer asks the server a last time (it may know of activity the tab does not); if the
session is over, or the server cannot be reached, it ends. A 401 with `SESSION_IDLE_TIMEOUT` or `SESSION_ABSOLUTE_TIMEOUT`
on any request, or another tab saying so, ends it at once. Ending does this, in order: cancel every request and clear the
whole query cache; drop the signed-in page (it is unmounted, not hidden) for a full-screen, modal signed-out message that
says why (idle, maximum length, or "signed out in another window"); finish the sign-out on the server
(`POST /v1/auth/sign-out` with the page to return to, which also ends the single-sign-on session) and enable **Sign in again**,
which goes to identity's sign-in. The storefront has no sign-in wall: it goes straight back to the page as a guest and shows
the reason in its "You're signed out" notice. Nothing but the reason code and a time is ever written to storage or sent to
other tabs.

**Why on landing too.** For ten minutes `GET /v1/me` says why (`signedOutReason`). A back office that opens with no session
asks it before redirecting to identity, and the storefront's notice reads it, so a tab that never saw the 401 (or a person
who comes back to the address later) still gets the reason.

**Return to.** The page the person was on is kept (`path?query` of this app), except the landing page and pages that a
sign-in alone must not reopen: staff console `/settings/*` and `/account/*`, Seller Center `/account/*`, storefront
`/account*`, `/orders*`, `/checkout*` (the existing sign-out rule).

**Several tabs.** `BroadcastChannel` (`localStorage` `storage` events where there is none) carries two messages: *activity
reported* (the other tabs move their expiry and close a warning) and *ended* (timeout, 401 or a manual sign-out; the other
tabs end too and do not repeat the server sign-out). Manual sign-outs call `announceSignOut()`.

**Polling must be flagged.** Every request nobody asked for sends `X-Background: 1`. In code: the API methods take a
trailing `background` argument (`BACKGROUND`), polling lives only in the query factories (`pollWhile`, `pollingOf` in
`query/polling.ts`; components never set `refetchInterval`), and the maintenance screens' timed re-check sends the header.
`web/packages/api-client/test/background-polling.test.ts` lists every known poll and fails when a poll is added without
the flag, when a component sets its own `refetchInterval`, when a timer calls `fetch` without the header, or when a query
refetches on window focus. A poll whose first load is also flagged is fine: the click that opened the page already
reported activity.

**Mock BFF and tests.** The MSW handlers (`mocks/session.ts`) serve the policy, the activity endpoint and the `session` /
`signedOutReason` members of `/v1/me` for all three apps. Shorten the timeouts with `setMockSessionPolicy({ idle, warn,
absolute, signInAgeSeconds })` in tests or with the cookie `at_mock_session=idle:warn:absolute` (seconds, e.g. `30:10:600`)
when running an app on the mocks; `endMockSession('SESSION_ABSOLUTE_TIMEOUT')` ends the session at once.

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
- web timer: `packages/ui/test/session-timeout.test.ts` (warn threshold, extend, absolute limit, clock offset, throttling,
  tabs, long timeouts), `session-dialog.test.tsx` (focus trap, buttons, live regions, signed-out screen),
  `session-guard.test.tsx` (input events, guests, channels); `packages/api-client/test/session.test.ts` (policy,
  activity, 401 de-duplication) and `background-polling.test.ts` (polling audit); per app `test/session-timeout.test.tsx`
  (storefront, Seller Center, staff console: mounted for signed-in people only, warning, end, cache cleared, return-to).
- web in a real browser: `tests/e2e/specs/staff-console.session-timeout.spec.ts` (warning, "Stay signed in", end with the
  reason, axe scans, two real tabs). Not covered by automated tests: Seller Center and the storefront in a real browser, a
  sleeping laptop. Check those by hand with the mock BFF and the cookie `at_mock_session=30:20:600`.
- mobile: `mobile/apps/{customer,driver}/test/sessionPolicy.test.ts`, `sessionGuard.test.ts`, `sessionSignOut.test.ts` (driver: outbox kept on sign-out, background work never counts as activity).
