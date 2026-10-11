---
title: Secrets and provider keys
sidebar_label: Secrets and keys
sidebar_position: 4
description: What each app needs (Stripe, Twilio, Resend, …), where to create it and where to put it, locally and deployed.
---

The local stack runs without any third-party account: email and one-time codes go to Mailpit, texts and push to the
log, addresses to OpenStreetMap, files to MinIO. Only **Stripe test keys** are needed to complete a checkout. To test
with real providers, put your credentials in one gitignored file:

```bash
mkdir -p .local && cp infra/local/secrets.env.example .local/secrets.env
$EDITOR .local/secrets.env      # grouped by provider, each with the dashboard path to create it
make secrets-check              # one table per app: set / missing / malformed (values masked)
make up                         # loads the file; prints which providers are real and which are local fallbacks
```

`make up` prints a line such as:

```text
secrets: Stripe: test keys set · Twilio: set → real SMS (test users' 555-01xx numbers still go to Mailpit) ·
Resend: missing → email to Mailpit · OpenRouter: missing → AI assistant off · Google sign-in: missing → no Google button
```

## Providers at a glance

| Provider | Powers | Create it at | Variables | Read by |
|---|---|---|---|---|
| **Stripe** (required) | checkout, saved cards, refunds, Connect, plans | Dashboard → Test mode → Developers → API keys; `stripe listen` for the webhook secret | `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET` | payments, seller, gateway-bff |
| Twilio | SMS codes, order and delivery texts | Console → Account Info (SID, Auth Token); buy a number or create a Messaging Service | `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM` or `TWILIO_MESSAGING_SERVICE_SID` | notifications |
| Resend | email to real inboxes | resend.com → API Keys; verify a domain or use `onboarding@resend.dev` | `RESEND_API_KEY`, `EMAIL_FROM` | notifications |
| OpenRouter | Lola assistant, semantic search, AI moderation | openrouter.ai → Keys | `OPENROUTER_API_KEY` | ai-gateway |
| Google / Apple / Facebook | social sign-in buttons | each provider's developer console (OAuth client) | `GOOGLE_CLIENT_ID`/`_SECRET`, `APPLE_…`, `FACEBOOK_…` | identity |
| Google Maps, Smarty | address autocomplete, geocoding, routing | Google Maps Platform → Keys & Credentials | `GOOGLE_PLACES_API_KEY`, `GOOGLE_MAPS_API_KEY` | geo |
| UPS, USPS, FedEx, DHL | rates, labels, tracking (sandboxes) | carrier developer portals | `UPS_*`, `USPS_*`, `FEDEX_*`, `DHL_*` | carrier-gateway |
| FCM, APNs, WhatsApp | push and WhatsApp messages | Firebase, Apple Developer, Meta for Developers | `FCM_SERVICE_ACCOUNT_JSON`, `APNS_*`, `WHATSAPP_*` | notifications |
| Pexels, Unsplash, GS1 | demo photos, product data | their developer pages | `PEXELS_API_KEY`, `UNSPLASH_ACCESS_KEY`, `GS1_API_KEY` | tools/seed, catalog |
| Sentry | error reporting | sentry.io → Client Keys (DSN) | `NEXT_PUBLIC_SENTRY_DSN`, `SENTRY_DSN`, `EXPO_PUBLIC_SENTRY_DSN` | web and mobile apps |

With Resend or Twilio set, the seeded test users (`@afrotransact.test`, `555-01xx` phones) still land in Mailpit;
real addresses and numbers go to the provider.

## The full reference

[Secrets and configuration](../engineering/guides/secrets-and-configuration.md) has, for every provider, the exact
dashboard path, test-mode notes, webhook set-up (`stripe listen`, Twilio callbacks via a tunnel), cost, and a
step-by-step **real test** (Stripe checkout with `4242 4242 4242 4242`, SMS sign-in, an order email in your inbox). It
also lists where each value goes in dev, uat and prod: the secret store path `afrotransact/<env>/<service>`, EAS
environment variables for the mobile builds and the GitHub Actions secrets.
