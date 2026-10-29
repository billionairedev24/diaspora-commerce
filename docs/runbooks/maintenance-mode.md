# Maintenance mode

Use it before planned work (a release, a database migration, a provider switch) so customers see a page that says
what is happening and when we expect to be back, instead of errors.

## Turn it on or off

Staff console → **Settings → General → Maintenance mode** → **Edit** (needs `content:site.edit`).

| Field | Meaning |
|---|---|
| Maintenance mode on | The switch. |
| Who is paused | *Shoppers only* pauses the storefront, customer app and checkout; Seller Center keeps working. *Shoppers and sellers* also pauses Seller Center. |
| Heading / message | What customers read. The message is required when turning it on. Write it for customers, not engineers. |
| When we expect to be back | Optional. Shown as a clock time in each reader's own time zone, and sent as `Retry-After`. |

Every change is recorded with who and when (History table on the same page).

## What happens while it is on

- The gateway BFF answers every customer route (and `/v1/seller/*` when sellers are paused) with **503**, problem code
  `MAINTENANCE`, and `Retry-After`. Mobile apps can show the message from `detail`.
- `GET /v1/maintenance` always answers (`active`, `scope`, `title`, `message`, `expectedBackAt`).
- The storefront and Seller Center render the maintenance page instead of the site. It re-checks every 20 seconds
  and reloads by itself when maintenance ends.
- **Not affected:** staff routes (`/v1/staff/*`), sign-in (`/v1/auth/*`, `/v1/me`), health checks, and services that
  talk to each other or to Stripe/carriers directly (webhooks never pass through the BFF). Background jobs keep running.
  Payments already in flight complete.

## Where the switch lives

In the gateway BFF's Valkey (`bff:platform:maintenance`), not in a downstream service, so it works when those are down
and every BFF replica sees it within about 5 seconds. If Valkey itself is unreachable the gate fails **open** (the last
known value is kept) and the BFF is unhealthy anyway.

## If you cannot reach the console

The staff console talks to the same BFF, which keeps `/v1/staff/*` open. If the console is also down, clear the key:

```bash
valkey-cli DEL bff:platform:maintenance   # takes effect in ~5 s
```

## Limits to know

- The maintenance page is served with HTTP 200 by the web apps (the API calls underneath are 503). Search engines can
  see the page; keep maintenance windows short.
- The switch covers the BFF and the two web apps. It does not stop traffic that goes straight to a service.
- Not covered by an integration test against real Valkey (unit and contract tests only).
