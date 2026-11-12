# Installable web app (PWA)

**Owner decision: no native mobile apps at launch.** Customers use the storefront in the phone's browser, and can install it
to the home screen as a web app. It is still the website: same code, same URLs, same sign-in, same Cloudflare Worker
(`storefront-<env>`, [environments](environments.md)). The Expo apps in `mobile/` are not part of launch (see
[Mobile apps](#mobile-apps-are-out-of-launch)).

## What ships

| Piece | Where |
|---|---|
| Shared helpers (manifest builder, service worker generator, offline page HTML, registration, install logic) | `web/packages/pwa` (`@afrotransact/pwa`) |
| Manifest `GET /manifest.webmanifest` (`application/manifest+json`) | `web/apps/storefront/src/app/manifest.webmanifest/route.ts`, values in `src/lib/pwa.ts` |
| Service worker `GET /sw.js` | `src/app/sw.js/route.ts` (generated from `@afrotransact/pwa`, config in `src/lib/pwa.ts`) |
| Offline page `GET /offline` (localised, no script) | `src/app/offline/route.ts` |
| Registration (production builds only) | `src/components/shell/ServiceWorkerRegistration.tsx`, mounted in `src/app/layout.tsx` |
| "Install the app" entry (footer) | `src/components/shell/InstallApp.tsx` |
| Icons 192 / 512 (any), 512 maskable, 180 Apple touch | `public/icons/*.png`, regenerated with `pnpm --filter storefront icons` from `public/logo-mark.svg` |
| Cloudflare static headers | `public/_headers` |
| Strings | `pwa.*` keys in `src/i18n/*.json` (fr, es, ar, yo are machine placeholders flagged `needsReview`, like the rest of those catalogues) |

Colours are design tokens only: `theme_color` is `--at-color-header-bg` (the same token as the layout's `viewport.themeColor`),
`background_color` is `--at-color-canvas`, the offline page and the maskable icon background read the tokens too.

The manifest: `name`/`short_name` AfroTransact, `display: standalone`, `scope: /`, `start_url: /?source=pwa` (the marker lets
analytics tell installed use apart), `id: /`. It carries no description or language, so it is identical for everyone and
needs no cookies.

## Service worker: conservative on purpose

A customer may be signed in on a shared phone, so the rule is: **nothing that can differ per person is ever stored.**

| Request | Behaviour |
|---|---|
| Page navigation (any HTML page) | Network, always. Only if the fetch fails (no connection) the worker answers with the precached `/offline` page. Pages are never stored. |
| `/_next/static/*` (content-hashed build output, fonts), `/icons/*`, `/logo-mark.svg` | Cache-first in `afrotransact-storefront-static-<version>`, public 200 responses only (not `private`/`no-store`/`Vary: *`), trimmed to 150 entries (oldest first). |
| `/api` (BFF proxy `/api/bff`), `/bff`, `/auth`, `/sign-in`, `/sign-up`, `/login`, `/logout`, `/account`, `/cart`, `/checkout`, `/orders`, `/membership`, `/back-office`, `/sell`, `/provide`, `/_next/data`, `/_next/image` | Not intercepted at all (plain network). The list is `COMMON_NEVER_CACHE` in `@afrotransact/pwa`. |
| React Server Components payloads (`?_rsc=`, `RSC`/`Next-*` request headers), requests with `Authorization`, non-GET, cross-origin, `Range` | Not intercepted. |
| Anything else (search pages' data, product JSON, images from the media CDN, ...) | Not intercepted. |

Lifecycle: `install` precaches only `/offline` (if it cannot be fetched the worker does not install) and calls
`skipWaiting`; `activate` deletes every cache with this app's prefix but another version, then claims clients. The
script is served `Cache-Control: no-cache` (browsers re-check it at least daily, `updateViaCache: 'none'`).

To change the rules, edit `service-worker.ts` in the package (the source is generated, so the bytes change and browsers
update) and bump `PWA_CACHE_VERSION` if old caches must be purged. The tests run the generated source in a sandbox
(`web/packages/pwa/test/service-worker.test.ts`), so they pin the rules that ship.

**Registration is production-only.** `syncServiceWorker(process.env.NODE_ENV)` registers in a production build and
*unregisters* a leftover worker in any other build (a dev server on a port that once ran a production build). PR previews
and uat are production builds, so they register.

## Headers on Cloudflare Workers

- `/sw.js`: `Content-Type: text/javascript`, `Cache-Control: no-cache, max-age=0, must-revalidate`, `Service-Worker-Allowed: /`
  (set in the route; routes rendered by the Worker keep their headers).
- `/manifest.webmanifest`: `Content-Type: application/manifest+json`, `Cache-Control: public, max-age=3600`.
- `/offline`: `private, no-cache` (it follows the visitor's language cookie), `X-Robots-Tag: noindex`.
- `public/_headers` (Workers static assets): `/_next/static/*` immutable for a year; `/icons/*` one day (names are unversioned).
  Nothing in `_headers` mentions the worker script, manifest or offline page, so none can be cached long by mistake.
- CSP is unchanged: `worker-src 'self' blob:` and `manifest-src 'self'` already allow both
  (`web/packages/ui/src/security/csp.ts`); the offline page has no script.

## Install entry

A quiet button in the footer ("Install the app"), never a banner and never blocking.

- Chromium (Android, desktop): `beforeinstallprompt` is kept (and its mini-infobar suppressed) and replayed when the person
  presses the button. The entry disappears after installing (`appinstalled`) and inside the installed app.
- iPhone and iPad (Safari has no prompt): the button reveals "To install on iPhone or iPad: tap Share, then Add to Home
  Screen." iPadOS reports a Mac user agent, so touch points are checked too.
- Other browsers (Firefox, desktop Safari): nothing is shown.
- The footer's App Store / Google Play buttons now render only when `appLinks.ios` / `appLinks.android` are set in site
  config; the old "app is in beta" toast buttons are gone (no native apps at launch).

## Back offices

Seller Center, Provider Hub and the staff console do **not** ship a manifest or worker. The shared helper makes it
short (a manifest route, a `sw.js` route, `offline` route, a registration component, icons and strings), but it is more than a few lines
per app, it needs per-app icons, strings and an offline page, and it buys little: these are authenticated desktop tools whose
every screen is live, personal data (the worker would only cache static assets and show "offline"). The staff console in particular must never
cache anything personalised, which the shared rules already guarantee, but no installed-app use case justifies the extra
surface. If the owner wants installable back offices later: copy the storefront's five files, pass
`cachePrefix: 'afrotransact-<app>'`, keep `COMMON_NEVER_CACHE` and add the app's own authenticated prefixes.

## Push notifications (not at launch)

Web Push is deliberately not built. The notifications service has only FCM and APNs adapters
(`go/services/notifications/internal/adapter/{fcm,apns}`), which deliver to native app tokens, not to browsers. Later it needs:

1. A **Web Push adapter** in notifications (the `PushSender` port gets a third implementation) speaking the Web Push protocol
   with **VAPID** (RFC 8292, `web-push` encryption RFC 8291): a VAPID key pair, the private key in the secret store
   (`secrets-and-configuration.md`), the public key as a public config value for the browser.
2. A **subscription registry**: the browser's `PushSubscription` (endpoint + keys) stored per person next to the existing
   device registry in identity (a `web` platform beside ios/android), with the same opt-in and notification preferences.
3. A **`push` + `notificationclick` handler** in the worker (the worker today has neither), a permission prompt that is only
   shown from a user gesture, and handling of expired subscriptions (410 from the push service).
4. **iOS**: Web Push works only for a web app that was **installed to the Home Screen** (iOS/iPadOS 16.4+), never in a normal
   Safari tab, and permission must be requested after a tap inside the installed app. So the iOS install entry above becomes
   the gateway to push, and the copy should say so when push ships.

## Verifying

- Unit: `pnpm --filter @afrotransact/pwa test`, `pnpm --filter storefront test` (`pwa.test.tsx`, `offline-route.test.ts`).
- Browser: `tests/e2e/specs/storefront.pwa.spec.ts` (manifest link and type, `sw.js` headers, offline page axe).
- By hand (production build, `make up` runs one): Chrome DevTools -> Application -> Manifest (installable, no warnings) and
  Service Workers; Cache Storage must hold only `/_next/static/*`, `/icons/*` and the offline page; go offline and open any page.

## Mobile apps are out of launch

`mobile/apps/{customer,driver}` (Expo) stay in the repository but are not part of the launch. See
[production readiness](../runbooks/production-readiness.md) and [launch plan](../runbooks/launch-plan.md) for the items that still
mention them, and `.github/workflows/mobile.yml` (EAS build/update; tag- and `main`-triggered, skipped without `EXPO_TOKEN`).
