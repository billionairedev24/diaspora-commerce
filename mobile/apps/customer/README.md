# AfroTransact — customer app (`@afrotransact/customer-app`)

Expo SDK 57 / React Native 0.86 / expo-router app for shoppers. Epic **E26**, stories **S163–S168**.
Design source: `design/AfroTransact Mobile App.dc.html` (+ `design/ios-frame.jsx`, `design/chats/chat2.md`).

| Story | What the app does |
|---|---|
| S163 Sign-in, Face ID, passcode | Opens on sign-in (no guest mode). OIDC authorization code + PKCE (S256) against identity with `expo-auth-session` (public client `customer-app`). After the first sign-in: Face ID (`expo-local-authentication`) → 6-digit passcode (create + confirm, salted SHA-256 in `expo-secure-store`) → notifications. Re-opening (cold start, or > 30 s in background) shows the unlock screen over the app, so unlocking returns you where you were. Five wrong passcodes force a full sign-in; sign-out removes tokens, Face ID and passcode from the device. Logo on sign-in, unlock and home. |
| S164 Layout | `ui/Screen.tsx`: solid status strip, fixed header, fixed footer/tab bar; only the inner `ScrollView`/`FlatList` scrolls, vertically. Inner pages pin title + back (`SubHeader`). |
| S165 Shopping | Home, browse/search, product, cart grouped by shipper, checkout from `GET /v1/checkout/summary` (per-package delivery option, AfroTransact Delivery preferences, pickup points). Saved card → `POST /v1/checkout/orders` (+ `handleNextAction` for 3-D Secure); Apple Pay / Google Pay / new card / US bank → Stripe **PaymentSheet in deferred-intent mode built from the summary total**, whose confirm handler places the order with the PaymentMethod and returns the PaymentIntent client secret. Add payment method = SetupIntent + PaymentSheet (card with ZIP, US bank account via Financial Connections). Orders over $100 and wallet payments ask for Face ID (or the app passcode). |
| Data loading | Every request has a 20 s deadline and an unreachable server becomes a `NetworkError` (`src/api/network.ts`), so a wrong host or no signal shows “You seem to be offline · Try again” (with the host in non-prod builds) instead of an endless spinner or a misleading “No orders yet”. React Query retries connectivity and 5xx twice with back-off, never a 4xx, and refetches stale data when the app returns to the foreground. Data screens use `ui/QueryStates.tsx` (error view, pull-to-refresh). |
| S166 Orders | Orders list, tracking (steps, driver, 4-digit delivery code for hand-delivery / perishables), buy again, returns (items, reason, method) → RMA + QR code. |
| Addresses | Native list and add/edit form (`app/(app)/addresses/`): state picker, default switch, remove, and the “couldn’t verify this address — fix it or keep mine” step. |
| S167 Bookings | Book a pro: providers, services, days/slots from the provider calendar, deposit via PaymentSheet on the bookings-service PaymentIntent, bookings list, appointment detail (accept/decline proposed time, reschedule, cancel with the **exact refund quoted before cancelling**, history). |
| S168 Push | Device token registered with identity on every start and token refresh (`POST /v1/me/devices`, `app: customer`, `provider: apns` on iOS / `fcm` on Android), unregistered on sign-out (`DELETE /v1/me/devices`). Taps deep-link to the right screen; foreground pushes respect per-category switches + identity's `GET/PUT /v1/me/notification-preferences`. `com.afrotransact.customer://…` and the environment's universal links `<EXPO_PUBLIC_WEB_URL>/app/…` route in-app. |

## Run it

```bash
pnpm install                                      # from the repo root
pnpm --filter @afrotransact/customer-app start:mock   # local env + in-app mock backend (no services needed)
pnpm --filter @afrotransact/customer-app start        # uses EXPO_PUBLIC_* below
```

- **On a phone:** [docs/engineering/mobile-on-your-phone.md](../../../docs/engineering/mobile-on-your-phone.md) — Expo Go
  over Wi-Fi (`make up MOBILE_HOST=lan`), a development build over USB (`make mobile-android-device APP=customer`,
  `make mobile-ios-device APP=customer`) or from EAS (`eas build --profile development`).
- **Expo Go** works with the local stack (sign-in returns to `exp://<ip>:8081/--/auth/callback`, registered in LAN
  mode). It has no remote push (SDK 53+), no Face ID on iPhone and no Apple/Google Pay: `src/platform/runtime.ts`
  detects it and the app falls back (no push registration, passcode, cards). A **development build** has everything.
- **Mocks** (`EXPO_PUBLIC_MOCKS=1`, default for `local`): `src/api/mock/router.ts` answers BFF, identity and bookings
  routes from fixtures that pass the api-client Zod schemas; sign-in completes as `adaeze@example.com`; Stripe is
  simulated (`src/payments/mockPayments.ts`). Mocks are forced off in `prod`.
- Web preview (`expo start --web`) works with mocks: Stripe and Keychain have no web build, so the `.web.ts`
  variants (mock payments, `sessionStorage`) are used there.

## Configuration

The URLs come from the EAS build profile (`eas.json`: `development` = dev, `preview` = uat, `production` = prod;
`development-mocks` = local on mocks); hosts per environment are in `docs/engineering/environments.md`. The code names
no deployed host: `src/config/env.ts` falls back to the local stack on `EXPO_PUBLIC_DEV_HOST` (when that is unset, a development phone uses the address Metro serves its JS from, `src/config/devHost.ts`, so `localhost` — the phone itself — is never tried by accident; otherwise `localhost`; the
dev machine's LAN IP for a physical device, `10.0.2.2` for the Android emulator), and `app.config.ts` fails a dev, uat or
prod build that leaves one of the URL variables unset. Universal links (iOS associated domain `applinks:<web host>`,
Android verified `https://<web host>/app` intent filter) use the host of `EXPO_PUBLIC_WEB_URL`; local builds have none.

| Variable | local | dev | uat | prod |
|---|---|---|---|---|
| `EXPO_PUBLIC_APP_ENV` | `local` | `dev` | `uat` | `prod` |
| `EXPO_PUBLIC_BFF_URL` | `http://localhost:8200` | `https://api.dev.afrotransact.com/bff` | `https://api.uat.afrotransact.com/bff` | `https://api.afrotransact.com/bff` |
| `EXPO_PUBLIC_OIDC_ISSUER` | `http://localhost:8100` | `https://auth.dev.afrotransact.com` | `https://auth.uat.afrotransact.com` | `https://auth.afrotransact.com` |
| `EXPO_PUBLIC_IDENTITY_URL` (devices, prefs) | `http://localhost:8100` | `https://api.dev.afrotransact.com/identity` | `…uat…/identity` | `https://api.afrotransact.com/identity` |
| `EXPO_PUBLIC_BOOKINGS_URL` | `http://localhost:8109` | `https://api.dev.afrotransact.com/bookings` | `…uat…/bookings` | `https://api.afrotransact.com/bookings` |
| `EXPO_PUBLIC_WEB_URL` (legal, help, account pages, share links, universal links) | `http://localhost:3000` | `https://dev.afrotransact.com` | `https://uat.afrotransact.com` | `https://afrotransact.com` |
| `EXPO_PUBLIC_OIDC_CLIENT_ID` | `customer-app` | `customer-app` | `customer-app` | `customer-app` |
| `EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY` | `pk_test_mock` | `pk_test_…` | `pk_test_…` | `pk_live_…` |
| `EXPO_PUBLIC_STRIPE_MERCHANT_ID` | `merchant.com.afrotransact` | ← | ← | ← |
| `EXPO_PUBLIC_MOCKS` | `1` (default) | `0` | `0` | always off |
| `EXPO_PUBLIC_DEV_HOST` (local stack host) | `localhost` | - | - | - |

Redirect: native `com.afrotransact.customer:/oauth2redirect`; web dev `http://localhost:8081/auth/callback`.
Bundle ids: `com.afrotransact.customer` (prod) and `com.afrotransact.customer.<env>` for the others (same URL scheme:
install one variant per phone).

### EAS build profiles (`eas.json`)

| Profile | Purpose | Env |
|---|---|---|
| `development` | dev client, internal distribution, iOS simulator build | `dev`, mocks off |
| `development-mocks` | dev client against the in-app mocks | `local`, mocks on |
| `preview` | internal QA build | `uat` |
| `production` | store build, auto-incremented build number | `prod` |

Set `EAS_PROJECT_ID` / `EAS_OWNER` and the Stripe keys as EAS secrets/environment variables.

## Identity client registration needed (`customer-app`)

Already seeded in `java/services/identity/src/main/resources/application-{local,dev,uat,prod}.yml`:
public client, grant types `authorization_code` + `refresh_token`, PKCE, `public-refresh-tokens: true`, scopes
`openid profile email phone offline_access`, redirect URIs `com.afrotransact.customer:/oauth2redirect` (+ local
`http://localhost:8081/auth/callback`). Still needed: a **post-logout redirect** (`com.afrotransact.customer:/signed-out`)
if RP-initiated logout is wanted, and honouring `login_hint`, `idp_hint` (google|apple|facebook) and `prompt=create`
on the hosted login page (the app sends them; today they are ignored).

## Structure

```
src/app/            expo-router routes: sign-in, setup, (app)/(tabs)/{index,search,lola,cart,account}, product, checkout,
                    orders, returns, payments, addresses, services, book, bookings, inbox, settings, +native-intent
src/domain/         plain TS rules (unit-tested): passcode, lock policy, checkout, tracking, returns, bookings,
                    notifications/deep links, payment form, jwt, lola
src/api/            bearer fetch + refresh, BFF client (@afrotransact/api-client), bookings/identity requesters,
                    TanStack Query key factories + hooks, generated contract types, dev mock router
src/auth/           OIDC (expo-auth-session), biometrics, secure storage, AuthProvider (lock/setup/sign-out)
src/payments/       Stripe PaymentSheet adapter (+ mock and web variants)
src/push/           device registration, deep-link routing, category preferences
src/ui/             token-based primitives (Screen, SubHeader, PinPad, Sheet, Toast, Logo)
```

Design tokens come only from `@afrotransact/tokens/native` (React Native view of the CSS tokens); ESLint rule
`at-mobile/no-raw-hex` rejects raw hex colours in `src/`.

## Checks

```bash
pnpm --filter @afrotransact/customer-app typecheck
pnpm --filter @afrotransact/customer-app lint
pnpm --filter @afrotransact/customer-app test            # Vitest, domain + mock-contract tests
pnpm --filter @afrotransact/customer-app check:generated # generated bookings/identity types are current
pnpm --filter @afrotransact/customer-app export:web      # build sanity check (writes dist/, git-ignored)
```

## Known gaps (upstream)

- BFF: no order-tracking route (the app calls the proposed `GET /v1/account/orders/{orderNumber}/tracking`, served by
  the mock); no address create/default routes; no Lola/assistant route; no bookings proxy (the app calls
  bookings-service directly); `PlaceOrderRequest.paymentMethodId` is documented as "saved" only and
  `OrderConfirmation.paymentClientSecret` is only set for 3-D Secure — the PaymentSheet deferred flow needs the BFF to
  accept a fresh PaymentMethod and always return the PaymentIntent client secret; no Stripe customer/ephemeral key, so
  the PaymentSheet cannot list saved methods; cart lines do not carry `perishable`.
- Identity: per-category push switches (orders/restock/deals/Lola) are device-local; "sign out of all devices" has no
  API (the app signs out locally and revokes its own refresh token); see client registration above.
- Fonts: Figtree is not bundled for native yet (system font is used).
