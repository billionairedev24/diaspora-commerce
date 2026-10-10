# @afrotransact/telemetry

Front-end and mobile error tracking (S178) behind one interface. Apps call `getTelemetry()` (`captureException`,
`captureMessage`, `addBreadcrumb`, `setUser({ id })`, `setTag`, `reportWebVital`, `flush`); only the bootstrap files
touch the SDK. Any Sentry-compatible back end works (Sentry SaaS, self-hosted Sentry, GlitchTip): only the DSN
changes.

- **Off by default**: without a DSN no SDK is initialised and every call is a no-op. In the browser the Sentry SDK is
  a lazy chunk, downloaded only when a DSN is configured.
- **PII scrubbing** on every event, breadcrumb and transaction (`beforeSend`, `beforeSendTransaction`,
  `beforeBreadcrumb`): emails, phone numbers, bearer/JWT/API tokens, secrets in URLs and Luhn-valid card numbers are
  replaced; values under sensitive keys (`password`, `token`, `cookie`, `card`, `email`, `phone`, `address`…) are
  blanked; request bodies, cookies and auth headers are dropped; the user is reduced to its opaque id.
  `sendDefaultPii` is off, session replay is off, mobile screenshots and view hierarchy are off.
- **Errors grouped by release**: every event carries `environment` and `release`.
- **Error boundaries**: each Next.js app has `app/global-error.tsx` and an `error.tsx` that report through
  `useReportError`; `TelemetryErrorBoundary` wraps widgets; the Expo apps export an expo-router `ErrorBoundary`
  from the root layout. Next.js `onRequestError` reports server render, route handler and server action errors.
- **Web vitals**: `WebVitalsReporter` (root layouts) reports LCP, INP, CLS, FCP and TTFB as breadcrumbs and, when an
  OTLP endpoint is set, as attributes of the page-load span.
- **OTel web traces**: with `NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT` set, the browser sends one `page.load` span per
  navigation (Navigation Timing + web vitals) to `<endpoint>/v1/traces` as OTLP/HTTP JSON, without an OTel SDK
  dependency. The collector must allow the site origins (CORS `allowed_origins` on the OTLP HTTP receiver).

## Environment variables

Set per environment (local, dev, uat, prod) by the deploy (Helm values / Cloudflare vars / EAS build profiles).

| Variable | App | Default | Meaning |
|---|---|---|---|
| `NEXT_PUBLIC_SENTRY_DSN` | web (browser + server) | unset → off | DSN of the app's project |
| `SENTRY_DSN` | web (server only) | `NEXT_PUBLIC_SENTRY_DSN` | runtime override for the server |
| `NEXT_PUBLIC_APP_ENV` / `APP_ENV` | web | `local` | `local`, `dev`, `uat` or `prod` |
| `NEXT_PUBLIC_RELEASE` / `RELEASE` | web | unset | release id (git SHA); must match the source-map upload |
| `NEXT_PUBLIC_SENTRY_TRACES_SAMPLE_RATE` / `SENTRY_TRACES_SAMPLE_RATE` | web | local 0, dev 1, uat 0.25, prod 0.05 | transaction sampling |
| `NEXT_PUBLIC_OTEL_EXPORTER_OTLP_ENDPOINT` | web (browser) | unset → off | collector base URL for browser traces |
| `EXPO_PUBLIC_SENTRY_DSN` | mobile | unset → off | DSN of the app's project |
| `EXPO_PUBLIC_APP_ENV` | mobile | `local` | environment (already used by the apps) |
| `EXPO_PUBLIC_RELEASE` | mobile | native `<bundle id>@<version>+<build>` | release id |
| `EXPO_PUBLIC_SENTRY_TRACES_SAMPLE_RATE` | mobile | as web | transaction sampling |

`NEXT_PUBLIC_*` and `EXPO_PUBLIC_*` values are inlined at build time: set them for the build as well as at runtime.

## Source maps (CI only — documented, not run here)

Uploads happen only in CI release jobs and only when every variable below is set; otherwise builds never contact
Sentry and need no Sentry CLI.

| Variable | Where | Meaning |
|---|---|---|
| `SENTRY_UPLOAD_SOURCEMAPS=1` | web build step | opt-in switch (`withTelemetryConfig` in each `next.config.ts`) |
| `SENTRY_AUTH_TOKEN` | CI secret (web and EAS) | org auth token with `project:releases` / `project:write` |
| `SENTRY_ORG`, `SENTRY_PROJECT` | CI variables | e.g. `afrotransact` / `storefront-web`, `seller-center-web`, `staff-console-web`, `customer-app`, `driver-app` |
| `SENTRY_URL` | CI variable (optional) | self-hosted Sentry or a compatible server |
| `NEXT_PUBLIC_RELEASE` / `EXPO_PUBLIC_RELEASE` | CI | the same release the app reports (git SHA, or version+build) |

- **Web**: `withTelemetryConfig()` wraps the Next.js config with `withSentryConfig` (release created, client files
  uploaded with debug IDs, `.map` files deleted from the build output after upload, Sentry telemetry off).
- **Mobile**: the `@sentry/react-native/expo` config plugin (in each `app.config.ts`) uploads JS source maps and
  native debug symbols during EAS builds when `SENTRY_AUTH_TOKEN` is set; `metro.config.js` uses
  `getSentryExpoConfig` so bundles carry debug IDs.
- pnpm blocks `@sentry/cli`'s install script by default; the CI job that uploads must allow it
  (`pnpm approve-builds` / `onlyBuiltDependencies`) or install the CLI binary itself.
