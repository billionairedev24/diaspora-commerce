# Seller Stripe verification step: how the first attempt works

The wizard's Stripe step (`web/apps/seller-center/src/components/onboarding/steps/StripeStep.tsx`) mounts Stripe's embedded
account onboarding. Test mode only; keys are env vars (`STRIPE_SECRET_KEY` for the seller service, `STRIPE_PUBLISHABLE_KEY`
for the BFF), never printed.

## Path

UI `POST /v1/seller/onboarding/stripe/session` -> BFF `ApplicationStripeSession` -> seller service
`POST /v1/applications/me/stripe/account-session` -> Stripe `POST /v1/accounts` (first use only) and
`POST /v1/account_sessions` -> client secret -> Connect.js `fetchClientSecret`.

## Why the first attempt used to fail

The first call of an application creates the connected account and then the session: two Stripe round trips, often more than
the BFF's 3 s seller timeout. The BFF answered 503 "Stripe is unavailable", although the seller service finished creating the
account. The retry only had to create a session (about 200 ms), so it worked. The step also made two session calls on opening
(one discarded, one for Connect.js), and React StrictMode ran the opening effect twice in development, so concurrent first
calls queued behind each other on the application row lock.

## What guarantees it now

- Idempotent per organisation: the account is created with the Stripe idempotency key `connect-account-v3-<orgId>` while the
  application row is locked, so any number of concurrent or repeated calls produce one account.
- BFF: the two session calls carry a per-call `httpclient.Policy` (15 s per attempt, 2 retries, 500 ms then 1 s backoff) for
  timeouts, network errors and 5xx. 4xx answers are never retried.
- Seller service: the session call retries (2 extra attempts, 300 ms then 600 ms) "no such account" right after creation, 429,
  5xx and dropped connections (`StripeCalls.callWithRetry`). Invalid requests and key problems fail at once with Stripe's reason.
- UI: one in-flight session call; its client secret is handed to Connect.js first, later secrets are fetched fresh when Stripe
  asks; transient failures are retried automatically (800 ms, 2 s) with "Still connecting to Stripe..."; only then a friendly
  message and a "Try again" button.

## Popup versus tab

Stripe's sign-in/verification window ("... uses Stripe for secure financial services", "Let's get started") is opened by
Connect.js from inside its own iframe, with `window.open`. Nothing in Seller Center calls `window.open`, sets a COOP header,
sandboxes the iframe or changes the `overlays` setting for it (`overlays: 'dialog'` only styles in-page overlays), and the CSP
(`frame-src`, `connect-src`) and `Permissions-Policy` (`publickey-credentials-get=(self)`) do not govern popups. The browser
decides between a popup and a tab from the way `window.open` is called: without a recent user click it is blocked or
downgraded to a tab. So the one thing our code can do is not disturb the click: the embedded component is mounted once and is
re-mounted only by the seller's own "Try again", never by an automatic retry, a re-created session object or a state change
(`StripeEmbedded.tsx`). The repository history for these files is a single squashed commit, so there is no earlier version to
compare; a browser setting or an extension that turns popups into tabs cannot be ruled out from the code.
