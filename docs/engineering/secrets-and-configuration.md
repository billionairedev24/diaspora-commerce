# Secrets and configuration

Every secret, API key and provider setting the apps read: where each comes from, what breaks without it, where to
create it and where to put it, locally and in dev/uat/prod. The tables marked *generated* come from
`tools/dev/secrets_check.py`, which scans the code (Java `${VAR}` placeholders, Go `env:"VAR"` tags, `process.env` /
`NEXT_PUBLIC_*` / `EXPO_PUBLIC_*` in the web and mobile apps, `tools/seed`, GitHub workflows), so they cannot drift:
`python3 tools/dev/secrets_check.py --check` fails CI-style when a secret-looking variable in code is undocumented.

## The short version

```bash
mkdir -p .local && cp infra/local/secrets.env.example .local/secrets.env   # .local/ is gitignored
$EDITOR .local/secrets.env          # Stripe test keys, Twilio, Resend, … whatever you have; leave the rest empty
make secrets-check                  # table per app: set / missing / malformed, values masked
make up                             # sources the file; prints one line: which provider is real, which is a fallback
```

Only Stripe is **required** for a complete local run (checkout cannot finish without it). Everything else is optional:
without a key the stack uses a local fallback (Mailpit, the log, OpenStreetMap, MinIO) and says so.

## Three kinds of variables

| Kind | Examples | Locally | dev / uat / prod |
|---|---|---|---|
| **(a) External-provider secrets and ids** | `STRIPE_SECRET_KEY`, `TWILIO_AUTH_TOKEN`, `RESEND_API_KEY`, `OPENROUTER_API_KEY`, `GOOGLE_CLIENT_SECRET` | `.local/secrets.env` (you create them at the provider) | the service's secret in the cloud secret store |
| **(b) Internal secrets** | `DB_PASSWORD`, `SIGNING_KEY_PEM`, `IDENTITY_DATA_KEY`, `HANDOVER_CODE_KEY`, `*_CLIENT_SECRET` | committed, clearly-labelled local defaults in `application-local.yml` / `config/local.yaml`: **nothing to do** | generated once (`openssl rand`) into the secret store; OpenTofu creates DB roles |
| **(c) Plain configuration** | `PUBLIC_WEB_URL`, `AUTH_ISSUER_URL`, ports, timeouts, feature flags | defaults in `application.yml` / `config/local.yaml`, plus what `local.sh` exports | `application-<env>.yml`, `config/<env>.yaml`, Helm `values-<env>.yaml`, `platform-values.yaml` |

Plain configuration is too long to list here (about a thousand variable uses): `python3 tools/dev/secrets_check.py
--inventory` prints every variable each app reads, classified. Secrets never go in YAML, Helm values or git;
`tools/ci/check_env_config.py` and gitleaks enforce it.

## Locally: `.local/secrets.env`

`tools/dev/local.sh` sources `.local/secrets.env` (shell syntax, `set -a`) on `up`, `restart <service>` and `mobile`,
and every process it starts inherits the values:

- **Services** (Java and Go) read them as environment variables at start-up. After editing the file, restart what
  reads the value: `tools/dev/local.sh restart notifications` (Twilio, Resend), `restart payments` and
  `restart gateway-bff` (Stripe), `restart identity` (social sign-in, and the code-delivery switch below).
- **Web apps** (`next dev`) get `NEXT_PUBLIC_*` and server variables when the dev server starts: `make down && make up`
  (or stop and start that one app).
- **Expo apps** get `EXPO_PUBLIC_*`, which **Metro inlines at bundle time**: stop the Metro servers and run
  `tools/dev/local.sh mobile` again, then reload the app. `EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY` defaults to your
  `STRIPE_PUBLISHABLE_KEY`.

Rules: a non-empty value in the file wins over the same variable in your shell; an **empty** line is unset, so the
service keeps its own default (Spring and the Go config loader treat an empty variable as *set*, which would wipe
defaults); a value already in your shell survives an empty line. Quote values with spaces in single quotes; PEM keys
and JSON go in single quotes across lines. `SECRETS_FILE=/path make up` uses another file. Values are never printed:
`local.sh` reports the count, `make secrets-check` shows masked values (nothing for secrets, the first four characters
of public identifiers).

`make secrets-check` (`SERVICE=payments` for one table, `SECRETS_FILE=…` for another file) validates cheap formats
(`sk_test_`/`pk_test_`, `whsec_`, Twilio `AC…`/`MG…` and 32-hex token, `re_…`, `sk-or-…`, `AIza…`, E.164 numbers),
warns loudly on live keys (`sk_live_`, `pk_live_`) and on half-configured providers (Twilio without a sender, Resend
with an `EMAIL_FROM` it will refuse), and exits non-zero only when a locally required variable is missing.

### Real provider or local fallback: the switches

Each switch is decided at start-up from whether the credentials are present, and logged:

| Channel | Credentials present | Missing (fallback) | Where it is decided |
|---|---|---|---|
| Email | `RESEND_API_KEY` (else `SENDGRID_API_KEY`): Resend, then Mailpit as failover | SMTP to **Mailpit** <http://localhost:8025> | notifications `config.EffectiveRouting` (`routing.preferConfigured: true` in `config/local.yaml`); log line `local provider: REAL` / `local provider: fallback` |
| SMS | `TWILIO_ACCOUNT_SID` + `TWILIO_AUTH_TOKEN` + `TWILIO_FROM` or `TWILIO_MESSAGING_SERVICE_SID` (else Vonage) | the **log** | same |
| Push / WhatsApp | `FCM_SERVICE_ACCOUNT_JSON` or `APNS_KEY_P8`+`APNS_KEY_ID`+`APNS_TEAM_ID` / `WHATSAPP_TOKEN`+`WHATSAPP_PHONE_NUMBER_ID` | the log | same |
| One-time codes (identity) | Twilio as above: `VERIFICATION_SENDER=outbox`, codes go through notifications (SMS by Twilio, email by Resend or Mailpit) | `mailpit`: identity drops every code (email and texted) in Mailpit itself | `tools/dev/local.sh` (`verification_sender`); prints `identity: one-time codes → …`. Your own `VERIFICATION_SENDER` wins |
| Card payments | `STRIPE_PUBLISHABLE_KEY` set: Stripe Elements | the storefront's labelled dev-only card form ("Stripe test mode is not configured") | gateway-bff hands the key to the storefront; `isMockKey` in `web/apps/storefront/src/lib/payments/stripe.ts` |
| Payments back end | `STRIPE_SECRET_KEY` | **no fallback**: payments starts with a placeholder key and every Stripe call fails (401) | `application-local.yml` of payments |
| Address search | `GOOGLE_PLACES_API_KEY` or `GOOGLE_MAPS_API_KEY`: Google Places (New) is the only autocomplete provider | fixtures + OpenStreetMap Nominatim | geo `Config.AddressProviders` (log line `address autocomplete providers`) |
| Files | (UploadThing is never used locally) | MinIO | media `config/local.yaml` |

**Test users stay local.** With Resend or Twilio switched on, the seeded test users (`@afrotransact.test`) and their
fictional `+1 713 555 01xx` phones still land in Mailpit: notifications wraps the real provider in
`internal/adapter/localguard`, which sends reserved domains (`.test`, `.local`, `.example`, `.invalid`) to Mailpit and
turns texts to 555-01xx numbers into a Mailpit message *"SMS to +1…"* (tagged `sms`). Real addresses and numbers go to
the real provider. `ROUTING_PREFER_CONFIGURED=false` keeps every fallback even with keys set. Deployed environments
are unaffected: their routes are fixed in `config/<env>.yaml` (`resend, sendgrid, ses` / `twilio, aws, vonage`).

Twilio's delivery-status callbacks need a public URL: notifications only sends `StatusCallback` when `PUBLIC_BASE_URL`
is reachable from the internet (not localhost or a private IP), see [tunnels](#tunnels-for-webhooks).

## Provider guide

Each entry: what it powers, where to create the credential, test-mode notes, webhooks, which variable goes where, cost.
Prices and free tiers change: check the provider's pricing page.

### Stripe (required): payments, seller, gateway-bff, customer app

- **Powers:** checkout (PaymentIntents with manual capture per shipment), saved cards and bank accounts (SetupIntents),
  refunds, disputes, booking deposits, Apple Pay / Google Pay / Link (Express Checkout Element), the app's
  PaymentSheet; seller: Connect onboarding (embedded components), payouts, plan billing.
- **Create:** [dashboard.stripe.com](https://dashboard.stripe.com) → toggle **Test mode** → **Developers → API keys**:
  *Publishable key* (`pk_test_…`) → `STRIPE_PUBLISHABLE_KEY`; *Secret key* (`sk_test_…`, or a restricted `rk_test_…`)
  → `STRIPE_SECRET_KEY`. Both from the **same account**. For seller onboarding also enable **Connect** (Settings →
  Connect → get started; test mode needs no business verification) (sellers' accounts are created with Stripe
  liable for negative balances and Stripe collecting their verification, through the embedded onboarding on the
  Stripe verification step, with no redirect to Stripe: see "Seller accounts and liability" in
  docs/engineering/payments-idempotency.md).
- **Webhooks locally:** install the [Stripe CLI](https://docs.stripe.com/stripe-cli), then
  `stripe login` and, in a terminal you keep open:
  `stripe listen --events payment_intent.succeeded,payment_intent.amount_capturable_updated,payment_intent.payment_failed,payment_intent.requires_action,payment_intent.canceled,charge.refunded,charge.dispute.created,charge.dispute.closed,transfer.reversed,payout.paid,payout.failed,setup_intent.succeeded,setup_intent.requires_action,payment_method.attached,payment_method.detached --forward-to localhost:8105/v1/webhooks/stripe`.
  The CLI refuses to start without `--events`; do **not** use `--all-thin` (thin events are a different payload and every
  delivery answers 400) and `--all-snapshot` only works if you accept noisy 200s for events we ignore. It prints
  `Your webhook signing secret is whsec_…` → `STRIPE_WEBHOOK_SECRET`, then `tools/dev/local.sh restart payments`. The secret
  stays the same for your machine across runs. For seller events run a second listener:
  `stripe listen --events account.updated,capability.updated,person.created,person.updated,account.external_account.created,account.external_account.updated,account.external_account.deleted,payout.failed --forward-connect-to localhost:8106/v1/webhooks/stripe-connect` → `STRIPE_CONNECT_WEBHOOK_SECRET`, and, for bank debits of plan fees that settle days later,
  `stripe listen --events payment_intent.succeeded,payment_intent.payment_failed --forward-to localhost:8106/v1/webhooks/stripe-billing` → `STRIPE_BILLING_WEBHOOK_SECRET` (each listener prints its own secret).
- **Deployed:** Developers → **Webhooks** → Add endpoint `https://<api host>/payments/v1/webhooks/stripe` (and the two
  seller endpoints); the endpoint's *Signing secret* goes into the secret store. Events per endpoint:

  | Endpoint | Listen to | Events |
  |---|---|---|
  | payments `/v1/webhooks/stripe` | Your account | `payment_intent.succeeded`, `payment_intent.amount_capturable_updated`, `payment_intent.payment_failed`, `payment_intent.requires_action`, `payment_intent.canceled`, `charge.refunded`, `charge.dispute.created`, `charge.dispute.closed`, `payout.paid`, `payout.failed`, `setup_intent.succeeded`, `setup_intent.requires_action` |
  | seller `/v1/webhooks/stripe-connect` | Connected accounts | `account.updated`, `capability.updated`, `person.created`, `person.updated`, `account.external_account.created`, `account.external_account.updated`, `account.external_account.deleted`, `payout.failed`, `account.application.deauthorized` |
  | seller `/v1/webhooks/stripe-billing` | Your account | `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.created`, `invoice.finalized`, `invoice.paid`, `invoice.payment_failed`, `invoice.voided`, `invoice.deleted`, `credit_note.created` |
- **Plan fees paid from sales (S298):** `invoice.created` lets the seller service pay a renewal invoice from the
  seller's earnings before Stripe finalises it (it answers 503 while that is still pending, so Stripe redelivers and
  keeps the draft); `invoice.voided` / `invoice.deleted` / `credit_note.created` give it back. The kill switch is plain
  configuration, `COLLECT_PLAN_FEES_FROM_EARNINGS` (seller; on in local and dev, off in uat / prod until operations
  switch it on in Helm `values-<env>.yaml`), with `PAYMENTS_URL` and the seller-service client's `payments:plan-fees`
  scope at identity (no new secret). See docs/engineering/plans-and-billing.md.
- **Test cards:** `4242 4242 4242 4242` (any future date, any CVC, any ZIP), `4000 0027 6000 3184` (3-D Secure),
  `4000 0000 0000 9995` (declined).
- **Guards:** payments refuses live keys outside prod; gateway-bff refuses `pk_live_` locally; `make secrets-check`
  flags live keys.
- **Cost:** test mode is free.

### Twilio (optional): notifications

- **Powers:** SMS one-time codes (SMS two-step sign-in, phone verification and enrolment), order, delivery and
  pre-order texts; STOP/HELP handling (`/v1/webhooks/twilio/inbound`).
- **Create:** [console.twilio.com](https://console.twilio.com) → **Account Info** panel on the home page: *Account
  SID* (`AC…`) → `TWILIO_ACCOUNT_SID`, *Auth Token* → `TWILIO_AUTH_TOKEN`. A sender: **Phone Numbers → Manage → Buy a
  number** (SMS-capable) → `TWILIO_FROM` in E.164 (`+1…`), or **Messaging → Services → Create Messaging Service**, add
  the number as a sender → `TWILIO_MESSAGING_SERVICE_SID` (`MG…`; preferred, it carries the 10DLC campaign).
- **Test mode notes:** a *trial* account sends only to numbers you verify under **Phone Numbers → Manage → Verified
  Caller IDs**, prefixed "Sent from your Twilio trial account". Twilio's *test credentials* (Account → API keys &
  tokens → Test credentials) with the magic sender `+15005550006` exercise the API without sending anything: good for
  checking the wiring, but no text arrives. US long-code traffic needs **A2P 10DLC** registration (Messaging →
  Regulatory Compliance) or a verified toll-free number; Twilio's console says which applies to your account.
- **Webhooks:** status callbacks go to `PUBLIC_BASE_URL + /v1/webhooks/twilio/status` when `PUBLIC_BASE_URL` is a
  public tunnel URL; inbound STOP/HELP: set the number's (or Messaging Service's) *A message comes in* webhook to
  `<tunnel>/v1/webhooks/twilio/inbound`. Locally signatures are not verified (`webhooks.verify: false`); deployed,
  they are checked with `TWILIO_AUTH_TOKEN`.
- **Cost:** trial credit on sign-up; then a number per month plus per-message fees.

### Resend (optional): notifications

- **Powers:** every transactional email (order confirmation, shipping, account and seller emails, codes when identity
  uses the outbox) to real inboxes.
- **Create:** [resend.com](https://resend.com) → **API Keys → Create API Key** (permission *Sending access*) →
  `RESEND_API_KEY` (`re_…`). Sender: **Domains → Add Domain**, add the DNS records it shows, wait for *Verified*, then
  `EMAIL_FROM='AfroTransact <no-reply@your-domain>'`. Without a domain use
  `EMAIL_FROM='AfroTransact <onboarding@resend.dev>'`, which **only delivers to the email address of your Resend
  account**: sign up in the storefront with that address for the real test.
- **Webhooks (optional, deployed):** **Webhooks → Add Endpoint** `https://<api host>/notifications/v1/webhooks/resend`
  (email.delivered, bounced, complained) → its signing secret (`whsec_…`) → `RESEND_WEBHOOK_SECRET`. Locally a tunnel
  to `localhost:8208/v1/webhooks/resend` works; signatures are not verified locally.
- **Cost:** free tier (about 3,000 emails a month, 100 a day at the time of writing).

### OpenRouter (optional): ai-gateway

- **Powers:** Lola the shopping assistant, semantic search embeddings (indexer, search), AI image moderation (media),
  all through ai-gateway (OpenAI-compatible API, model routing per task).
- **Create:** [openrouter.ai](https://openrouter.ai) → **Keys → Create Key** → `OPENROUTER_API_KEY` (`sk-or-v1-…`); add
  credit under **Credits** (some models are free).
- **Note:** ai-gateway starts with `make up`. After adding the key, `tools/dev/local.sh restart ai-gateway` re-reads it. Without a key, AI chats answer 503. Every task defaults to the cheapest models (Gemini 2.5 Flash-Lite, then GPT-5 Nano); raise one with `AI_MODELS_<TASK>` (`INTENT`, `PLANNING`, `CHAT`, `LISTING_WRITER`, `TRANSLATION`, `TRIAGE`, `IMAGE_MODERATION`), a comma-separated list in fallback order.
- **Cost:** pay per token; set a credit limit on the key.

### Social sign-in (optional): identity

A provider's button appears on the sign-in pages only when its client id is set (`SocialLoginConfiguration`).
Redirect URI pattern: `{identity}/login/oauth2/code/{google|apple|facebook}`, locally
`http://localhost:8100/login/oauth2/code/google`.

- **Google:** [console.cloud.google.com](https://console.cloud.google.com) → **APIs & Services → OAuth consent screen**
  (External, add yourself as a test user) → **Credentials → Create credentials → OAuth client ID → Web application**,
  authorised redirect URI as above → `GOOGLE_CLIENT_ID` (`….apps.googleusercontent.com`), `GOOGLE_CLIENT_SECRET`
  (`GOCSPX-…`). Free.
- **Apple:** [developer.apple.com](https://developer.apple.com/account) (paid membership) → **Certificates, Identifiers
  & Profiles → Identifiers → Services IDs** → enable *Sign in with Apple*, return URL on an HTTPS domain (Apple refuses
  localhost: use a tunnel) → `APPLE_CLIENT_ID`; **Keys → +** with *Sign in with Apple*, download the `.p8`, sign a
  client-secret JWT with it (valid at most 6 months) → `APPLE_CLIENT_SECRET`.
- **Facebook:** [developers.facebook.com](https://developers.facebook.com) → **My Apps → Create App** → add **Facebook
  Login** → *Valid OAuth Redirect URIs* as above → **App settings → Basic**: App ID → `FACEBOOK_CLIENT_ID`, App Secret →
  `FACEBOOK_CLIENT_SECRET`. Free.

### Google Maps Platform and Smarty (optional): geo

- **Powers:** address autocomplete and the structured address of a picked suggestion (Google Places (New)
  Autocomplete + Place Details, provider `google-places`) in every address field of the storefront, Seller Center,
  staff console and customer app; reverse geocoding (`google-geocoding`, "Use my current location"); drive-time
  routing (`google`). Smarty: US deliverability checks (`smarty`).
- **Which provider answers:** with `GOOGLE_PLACES_API_KEY` (or, when that is blank, `GOOGLE_MAPS_API_KEY`) set,
  Google Places is the **only** autocomplete provider in every environment, local included; OpenStreetMap
  Nominatim (and the sample addresses locally) answer only when no key is configured. geo logs the chain at
  start-up: `address autocomplete providers providers=[google-places]`. `GEO_PROVIDERS` only orders the no-key
  fallbacks. One key can serve every Google feature.
- **Create the key:** [Google Cloud Console](https://console.cloud.google.com/) → pick or create a project → **Billing**:
  link a billing account (required, even inside the free usage) → **APIs & Services → Library**: enable
  **Places API (New)** (`places.googleapis.com`; *not* the legacy "Places API"), and for the other features
  **Geocoding API** and **Routes API** → **Google Maps Platform → Keys & Credentials → Create credentials → API
  key**.
- **Restrict the key** (Edit API key):
  - *API restrictions* → **Restrict key** → Places API (New) (+ Geocoding API, Routes API if you use them). This is
    the restriction that matters: geo calls Google from the server.
  - *Application restrictions*: **HTTP referrers cannot be used** (the browser never calls Google; geo does, with
    the key in the `X-Goog-Api-Key` header). Deployed: **IP addresses** = the cluster's egress (NAT) IPs. Locally:
    **None**, or your public IP; use a separate key for local development and keep it out of git
    (`.local/secrets.env`).
  - Put the key in the environment's secret store as `GOOGLE_MAPS_API_KEY` (or `GOOGLE_PLACES_API_KEY`) for geo
    only; no web or mobile app needs it.
- **Cost:** autocomplete is billed per **session**: the BFF and the address fields send one session token per
  address entry (all keystrokes) and the Place Details call of the picked suggestion closes it, so one address
  entry is one *Autocomplete session* + one *Place Details Essentials* request (geo asks only for
  `addressComponents,formattedAddress,location`). Suggestions are never resolved per keystroke. Typing without
  picking is billed as autocomplete requests. Google gives a monthly free usage cap per SKU; check current prices on
  the [Maps Platform pricing page](https://mapsplatform.google.com/pricing/) and set **budget alerts** and
  **per-API quotas** (APIs & Services → Places API (New) → Quotas) on production and on your local key.
- **Smarty:** [smarty.com](https://www.smarty.com) → **API Keys → Secret keys** → `SMARTY_AUTH_ID`,
  `SMARTY_AUTH_TOKEN` (free trial).
- **Locally** without a key geo uses the sample addresses + OpenStreetMap Nominatim. With a key in
  `.local/secrets.env`, `tools/dev/local.sh restart geo` switches local autocomplete to Google.

### Carriers: UPS, USPS, FedEx, DHL Express (optional): carrier-gateway

Sandbox developer accounts, step by step: [carrier sandboxes runbook](../runbooks/carrier-sandboxes.md). Variables per
carrier are in the table below; a carrier without credentials is skipped at start-up. carrier-gateway is not in the
default set: `tools/dev/local.sh restart carrier-gateway`, then `make smoke-carriers`. Free sandboxes.

### Push and WhatsApp (optional): notifications

- **Firebase Cloud Messaging:** [console.firebase.google.com](https://console.firebase.google.com) → project → **Project
  settings → Service accounts → Generate new private key** → the JSON (single-quoted) → `FCM_SERVICE_ACCOUNT_JSON`.
  Free.
- **Apple Push (APNs):** developer.apple.com → **Keys → +** → *Apple Push Notifications service (APNs)* → download the
  `.p8` once → `APNS_KEY_P8` (single-quoted PEM), *Key ID* → `APNS_KEY_ID`, your *Team ID* (Membership) →
  `APNS_TEAM_ID`.
- **WhatsApp Cloud API:** [developers.facebook.com](https://developers.facebook.com) → app → **WhatsApp → API Setup**:
  temporary access token (24 h) or a system-user token → `WHATSAPP_TOKEN`; *Phone number ID* →
  `WHATSAPP_PHONE_NUMBER_ID`.
- Push notifications reach a device only from a development or store build of the app (not Expo Go).

### Product data and demo photos (optional): catalog, `make seed`

- **GS1:** *Verified by GS1* API key from your GS1 member organisation (paid) → `GS1_API_KEY` and `GS1_ENABLED=true`.
  Without it catalog uses Open Food Facts (no key).
- **Pexels:** [pexels.com/api](https://www.pexels.com/api/) → request a key → `PEXELS_API_KEY`. **Unsplash:**
  [unsplash.com/developers](https://unsplash.com/developers) → **New Application** → *Access Key* →
  `UNSPLASH_ACCESS_KEY`. Without them `make seed` uses Wikimedia Commons. Free.

### Sentry (optional): web apps, mobile apps

[sentry.io](https://sentry.io) → **Projects → Create project** (Next.js; React Native) → **Settings → Client Keys
(DSN)** → `NEXT_PUBLIC_SENTRY_DSN` and `SENTRY_DSN` (web), `EXPO_PUBLIC_SENTRY_DSN` (apps). Source maps: **Settings →
Auth Tokens** (organisation token) → `SENTRY_AUTH_TOKEN` with `SENTRY_ORG`, `SENTRY_PROJECT` at build time (CI or
EAS), never at runtime. Free developer plan.

### Deployed only: UploadThing, AWS (SES, End User Messaging), Cloudflare

- **UploadThing** (media, product images in dev/uat/prod): [uploadthing.com](https://uploadthing.com) → app → **API
  Keys** → `UPLOADTHING_TOKEN`. Locally media stores everything in MinIO.
- **Amazon SES / End User Messaging SMS** (email and SMS failover): no keys; the notifications pod's workload identity
  (IRSA) needs `ses:SendEmail` / `sms-voice:SendTextMessage`. Settings `SES_REGION`, `AWS_SMS_REGION`,
  `AWS_SMS_ORIGINATION_IDENTITY` in `config/<env>.yaml`.
- **Cloudflare** (Workers deploys): `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ZONE_ID`.

### Tunnels for webhooks

A provider that must call your machine (Twilio status callbacks and inbound texts, Resend webhooks, Apple sign-in
return URLs) needs a public HTTPS URL. Stripe does not: `stripe listen` forwards for you.

```bash
cloudflared tunnel --url http://localhost:8208     # prints https://<random>.trycloudflare.com (no account needed)
# or: ngrok http 8208
```

Put that URL in `PUBLIC_BASE_URL` (notifications) and restart notifications; point the provider's webhook at
`<url>/v1/webhooks/twilio/status`, `/v1/webhooks/twilio/inbound` or `/v1/webhooks/resend`. The URL changes each time a
quick tunnel starts.

## A real test, end to end

You have Stripe (test mode), Twilio and Resend credentials.

1. **Fill the file.**

   ```bash
   mkdir -p .local && cp infra/local/secrets.env.example .local/secrets.env
   ```

   Set `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`; `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM` (or
   `TWILIO_MESSAGING_SERVICE_SID`); `RESEND_API_KEY` and `EMAIL_FROM` (a verified-domain address, or
   `onboarding@resend.dev`).
2. **Stripe webhooks.** In a second terminal: `stripe login` (once), then
   `stripe listen --forward-to localhost:8105/v1/webhooks/stripe`; copy the `whsec_…` into `STRIPE_WEBHOOK_SECRET`.
   Leave it running.
3. **Check.** `make secrets-check`: no `MISSING`, no `!!! LIVE KEY`; fix any `!` format warnings.
4. **Start.** `make up` (already running: `tools/dev/local.sh restart payments`, `restart gateway-bff`,
   `restart notifications`, `restart identity`). Look for:
   - `secrets: Stripe: test keys set · Twilio: set → real SMS … · Resend: set → real email …`
   - `identity: one-time codes via notifications → Twilio / Resend`
   - in `make dev-logs SERVICE=notifications`: `local provider: REAL channel=email active=resend` and
     `… channel=sms active=twilio`.
5. **Sign up with your own email** in the storefront (<http://localhost:3000>), *not* a test user (their mail stays in
   Mailpit by design). With `onboarding@resend.dev` use your Resend account's address. The verification code arrives
   in your inbox.
6. **SMS sign-in.** **Account → Login & security** (`/account/security`): add and verify your mobile number (a text
   arrives), then turn on two-step verification by text message. Sign out and sign in: the code arrives by SMS. A trial
   Twilio account only reaches numbers verified in its console.
7. **Checkout.** Add products, check out with card `4242 4242 4242 4242`, any future expiry, any CVC. The
   `stripe listen` terminal shows `payment_intent.amount_capturable_updated` → `200`; the order page confirms; the
   Stripe dashboard (test mode) shows the uncaptured payment.
8. **Order email.** The order confirmation arrives in your inbox (Resend → **Emails** shows it as delivered). Mailpit
   (<http://localhost:8025>) still holds the test users' messages.

If an email does not arrive: notifications' log shows the provider error (`domain is not verified`, or
`onboarding@resend.dev` sending to another address, are permanent errors and not retried). If a text does not arrive:
the Twilio console's **Monitor → Logs → Messaging** shows the reason (unverified trial recipient, 10DLC).

## Deployed environments (dev, uat, prod)

### Service secrets: the cloud secret store

Each service has **one secret holding a JSON object** whose keys are the environment variable names (the rows of the
per-app table below). External Secrets Operator syncs it into the Kubernetes Secret `<service>-secrets`, which the pod
loads with `envFrom` (`infra/helm/library/at-service`, `secrets.enabled: true` in the service's chart):

| Cloud | Secret name | Created by |
|---|---|---|
| AWS Secrets Manager | `afrotransact/<env>/<service>`, e.g. `afrotransact/uat/payments` | OpenTofu `modules/aws/secrets` (KMS) |
| GCP Secret Manager | `afrotransact-<env>-<service>` | OpenTofu `modules/gcp/secrets` |
| Azure Key Vault | `afrotransact-<env>-<service>` | OpenTofu `modules/azure/secrets` |

OpenTofu seeds the non-secret values once (`APP_ENV`, database name and user, Kafka bootstrap and per-service
credentials, `ES_URLS`); you add the secrets in the store (OpenTofu ignores later value drift). On GCP and Azure set
`secrets.keySeparator: "-"` in `infra/argocd/envs/<env>/platform-values.yaml`. Example (AWS):

```bash
aws secretsmanager put-secret-value --secret-id afrotransact/uat/notifications \
  --secret-string "$(jq -n --arg r "$RESEND_API_KEY" --arg t "$TWILIO_AUTH_TOKEN" \
     '{RESEND_API_KEY:$r, TWILIO_AUTH_TOKEN:$t, TWILIO_ACCOUNT_SID:"AC…", TWILIO_MESSAGING_SERVICE_SID:"MG…"}')"
```

(`put-secret-value` replaces the whole JSON: read it first and merge.) The Cloudflare tunnel token lives at
`afrotransact/<env>/cloudflared` (property `token`). dev and uat use **test** keys (Stripe `sk_test_`/`pk_test_`), prod
live keys; the services refuse the wrong kind. Deployed, the notifications routes are fixed (`resend, sendgrid, ses`
for email; `twilio, aws, vonage` for SMS) and webhook signatures are verified, so `RESEND_WEBHOOK_SECRET` and
`TWILIO_AUTH_TOKEN` must be set there.

Internal secrets: generate with `openssl rand -base64 32` (data and field encryption keys: exactly 32 random bytes,
base64), `openssl rand -hex 32` (HMAC keys), `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072` (identity's
`SIGNING_KEY_PEM`). Each `<SERVICE>_CLIENT_SECRET` must hold the same value in the service's secret and in identity's
(identity registers the client with it, **only when the client does not exist yet**: after that the staff console owns it,
and a secret rotated there must be copied into the service's secret; `SIGNING_KEY_PEM` likewise only seeds the first signing
key, later rotations are done in the console). See [identity-admin.md](identity-admin.md).

### Web apps (Cloudflare Workers)

Plain settings (`BFF_ORIGIN`, `SELLER_CENTER_URL`, `PUBLIC_WEB_URL`, `API_MOCKS`) are `vars` per environment in each
app's `wrangler.jsonc`. Secrets: `wrangler secret put SENTRY_DSN --env uat` (in `web/apps/<app>`). Build-time
`NEXT_PUBLIC_SENTRY_DSN`, `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, `SENTRY_PROJECT` come from the CI environment.

### Mobile apps (EAS)

`eas.json` sets each build profile's `EXPO_PUBLIC_*` URLs. Add the rest as EAS environment variables (expo.dev → project
→ **Environment variables**, or the CLI), per EAS environment (development, preview, production):

```bash
cd mobile/apps/customer
eas env:create --environment preview --name EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY --value pk_test_… --visibility plaintext
eas env:create --environment preview --name EXPO_PUBLIC_SENTRY_DSN --value https://…ingest.sentry.io/… --visibility plaintext
eas env:create --environment preview --name SENTRY_AUTH_TOKEN --value sntrys_… --visibility secret
```

Push notifications need the platform credentials **in the app build** (the notifications service holds the sending
side: `FCM_SERVICE_ACCOUNT_JSON`, `APNS_*` above). Expo Go cannot receive them (SDK 53+); use a development build
(docs/engineering/mobile-on-your-phone.md):

| EAS name | Kind | Used by | Where it comes from |
|---|---|---|---|
| `GOOGLE_SERVICES_JSON` | file variable (secret) | `app.config.ts` → `android.googleServicesFile` (FCM device tokens) | Firebase console → Project settings → *Your apps* → Android app with the build's package (`com.afrotransact.customer.dev`, `….driver.dev`, …) → download `google-services.json` |
| APNs key | EAS credentials (not a variable) | iOS device tokens (`aps-environment` entitlement from expo-notifications) | `eas credentials --platform ios` → *Push Notifications* → upload or generate the `.p8` key (the same key as `APNS_KEY_P8`) |

```bash
eas env:create --environment development --name GOOGLE_SERVICES_JSON --type file --value ./google-services.json --visibility secret
```

`EXPO_PUBLIC_*` values are inlined into the bundle, so they are never secret (publishable keys and DSNs only).
`EAS_PROJECT_ID` and `EAS_OWNER` (read by `app.config.ts`) come from `eas init` or the CI environment;
`EXPO_PUBLIC_STRIPE_MERCHANT_ID` is the Apple Pay merchant id (developer.apple.com → Identifiers → Merchant IDs).

### GitHub Actions

Repository (or environment) **secrets**, Settings → Secrets and variables → Actions:

| Secret | Workflow | Purpose |
|---|---|---|
| `GITOPS_APP_ID`, `GITOPS_APP_PRIVATE_KEY` | `_deploy.yml` | GitHub App that commits image tags to the GitOps branch |
| `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` | `_web-app.yml`, `docs.yml`, `storybook.yml`, `tofu.yml` | Workers deploys, Cloudflare OpenTofu |
| `VERCEL_TOKEN`, `VERCEL_ORG_ID`, `VERCEL_PROJECT_ID` | `_web-app.yml` | Vercel fallback hosting (`vars.VERCEL_FALLBACK`) |
| `EXPO_TOKEN` | `mobile.yml` | EAS builds and updates (skipped with a notice while unset) |
| `UPS_*`, `USPS_*`, `FEDEX_*`, `DHL_*`, `SMOKE_CLIENT_ID`, `SMOKE_CLIENT_SECRET` | `carrier-sandboxes.yml` | carrier sandbox smoke tests |
| `CONFLUENT_CLOUD_API_KEY`/`_SECRET`, `EC_API_KEY`, `TEMPORAL_CLOUD_API_KEY` | `tofu.yml` | managed Kafka, Elastic Cloud, Temporal Cloud |
| `NVD_API_KEY` | `security.yml` | OWASP dependency-check feed |

Repository **variables**: `CLOUD`, `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `GCP_WORKLOAD_IDENTITY_PROVIDER`,
`GCP_DEPLOY_SERVICE_ACCOUNT`, `GCP_REGISTRY_HOST`, `AZURE_DEPLOY_CLIENT_ID`, `AZURE_TENANT_ID`,
`AZURE_SUBSCRIPTION_ID`, `REGISTRY_URL`, `DEV_BASE_DOMAIN`, `GITOPS_BRANCH`, `TF_TARGETS`, `VERCEL_FALLBACK`. Cloud
access uses OIDC federation: no cloud keys in GitHub.

## Reference tables (generated)

### External providers and local switches

<!-- BEGIN GENERATED by tools/dev/secrets_check.py --write-docs: external -->
| Variable | Provider | Read by | Kind | Locally | What it powers / without it |
|---|---|---|---|---|---|
| `STRIPE_SECRET_KEY` | Stripe | payments, seller | secret | **required** | payments: PaymentIntents, refunds, saved cards, Connect transfers; seller: Connect onboarding, plan billing. Without: checkout and saved cards fail (payments falls back to a placeholder key; Stripe answers 401) |
| `STRIPE_PUBLISHABLE_KEY` | Stripe | gateway-bff | public id | **required** | Stripe.js Payment Element / Express Checkout in the storefront, PaymentSheet in the app (served by the BFF). Without: the storefront shows its labelled dev-only test card form instead of Stripe Elements |
| `STRIPE_WEBHOOK_SECRET` | Stripe | payments | secret | **required** | verifies Stripe's webhooks (payment succeeded, refunds, disputes) at POST :8105/v1/webhooks/stripe. Without: webhooks are rejected: orders never see the payment succeed |
| `STRIPE_CONNECT_WEBHOOK_SECRET` | Stripe | seller | secret | optional | Connect account updates (verification, payouts enabled) at POST :8106/v1/webhooks/stripe-connect. Without: Connect events are rejected: Account health does not update |
| `STRIPE_BILLING_WEBHOOK_SECRET` | Stripe | seller | secret | optional | plan subscription events (invoices, renewals) at POST :8106/v1/webhooks/stripe-billing. Without: billing events are rejected: plan status does not update |
| `EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY` | Stripe | mobile-customer | public id | optional | PaymentSheet in the customer app (inlined by Metro at bundle time). Without: local.sh copies STRIPE_PUBLISHABLE_KEY; without either the app uses pk_test_mock (mock sheet) |
| `EXPO_PUBLIC_STRIPE_MERCHANT_ID` | Stripe | mobile-customer | config | optional | Apple Pay merchant identifier for PaymentSheet. Without: default merchant.com.afrotransact (Apple Pay on device only) |
| `TWILIO_ACCOUNT_SID` | Twilio | notifications | public id | optional | SMS: sign-in and enrolment codes, order and delivery texts. Without: texts are only logged (identity's one-time codes go to Mailpit) |
| `TWILIO_AUTH_TOKEN` | Twilio | notifications | secret | optional | authenticates the Twilio API; also verifies Twilio's status callbacks. Without: texts are only logged |
| `TWILIO_FROM` | Twilio | notifications | config | optional | the sending number (when no Messaging Service is set). Without: Twilio stays off unless TWILIO_MESSAGING_SERVICE_SID is set |
| `TWILIO_MESSAGING_SERVICE_SID` | Twilio | notifications | public id | optional | sends through a Messaging Service (10DLC campaign; preferred over TWILIO_FROM). Without: TWILIO_FROM is used |
| `RESEND_API_KEY` | Resend | notifications | secret | optional | transactional email (order, account, seller emails) to real inboxes. Without: every email goes to Mailpit http://localhost:8025 |
| `EMAIL_FROM` | Resend | notifications | config | optional | sender address; must be on a domain verified in Resend (or onboarding@resend.dev). Without: AfroTransact <no-reply@afrotransact.local>: fine for Mailpit, refused by Resend |
| `EMAIL_REPLY_TO` | Resend | notifications | config | optional | Reply-To header. Without: support@afrotransact.local |
| `RESEND_WEBHOOK_SECRET` | Resend | notifications | secret | optional | verifies Resend delivery/bounce webhooks (POST /v1/webhooks/resend); deployed environments. Without: no delivery receipts from Resend (local does not verify webhooks) |
| `SENDGRID_API_KEY` | SendGrid | notifications | secret | optional | email failover after Resend (dev/uat/prod); locally used when Resend is not set. Without: not used |
| `SENDGRID_WEBHOOK_PUBLIC_KEY` | SendGrid | notifications | secret | optional | verifies SendGrid event webhooks (deployed). Without: no SendGrid receipts |
| `VONAGE_API_KEY` | Vonage | notifications | public id | optional | SMS failover after Twilio (deployed). Without: not used |
| `VONAGE_API_SECRET` | Vonage | notifications | secret | optional | SMS failover after Twilio (deployed). Without: not used |
| `VONAGE_FROM` | Vonage | notifications | config | optional | Vonage sender id. Without: AfroTransact |
| `FCM_SERVICE_ACCOUNT_JSON` | Firebase (FCM) | notifications | secret | optional | Android (and iOS via FCM) push notifications. Without: push messages are only logged |
| `FCM_PROJECT_ID` | Firebase (FCM) | notifications | config | optional | Firebase project id (default: from the JSON). Without: - |
| `APNS_KEY_P8` | Apple Push (APNs) | notifications | secret | optional | iOS push notifications (token-based .p8 key). Without: push messages are only logged |
| `APNS_KEY_ID` | Apple Push (APNs) | notifications | public id | optional | the .p8 key's Key ID. Without: - |
| `APNS_TEAM_ID` | Apple Push (APNs) | notifications | public id | optional | Apple Developer Team ID. Without: - |
| `WHATSAPP_TOKEN` | WhatsApp Cloud API | notifications | secret | optional | WhatsApp messages (chat channel). Without: WhatsApp messages are only logged |
| `WHATSAPP_PHONE_NUMBER_ID` | WhatsApp Cloud API | notifications | public id | optional | the WhatsApp Business phone number id. Without: - |
| `PUBLIC_BASE_URL` | Local switches | notifications | config | optional | notifications' public URL: a tunnel (cloudflared/ngrok) lets Twilio post delivery status callbacks. Without: http://localhost:8208: no Twilio status callbacks |
| `BOOTSTRAP_ADMIN_PHONE` | Local switches | identity | config | optional | the bootstrap admin's phone (staff sign-in codes) when the admin is first created. Without: none |
| `VERIFICATION_SENDER` | Local switches | identity | config | optional | where identity sends one-time codes: mailpit, log or outbox (= notifications: Twilio/Resend). Without: local.sh picks outbox when Twilio is set, else mailpit |
| `OPENROUTER_API_KEY` | OpenRouter | ai-gateway | secret | optional | Lola shopping assistant, semantic search embeddings, AI image moderation (all via ai-gateway). Without: AI calls fail: the assistant is unavailable, search stays keyword-only |
| `GOOGLE_CLIENT_ID` | Google sign-in | identity | public id | optional | Continue with Google. Without: no Google button |
| `GOOGLE_CLIENT_SECRET` | Google sign-in | identity | secret | optional | Continue with Google. Without: no Google button |
| `APPLE_CLIENT_ID` | Apple sign-in | identity | public id | optional | Sign in with Apple (Services ID). Without: no Apple button |
| `APPLE_CLIENT_SECRET` | Apple sign-in | identity | secret | optional | Sign in with Apple: a JWT you sign with your .p8 key (expires after at most 6 months). Without: no Apple button |
| `FACEBOOK_CLIENT_ID` | Facebook sign-in | identity | public id | optional | Continue with Facebook. Without: no Facebook button |
| `FACEBOOK_CLIENT_SECRET` | Facebook sign-in | identity | secret | optional | Continue with Facebook. Without: no Facebook button |
| `GOOGLE_MAPS_API_KEY` | Google Maps Platform | geo | secret | optional | address autocomplete + Place Details (Google Places (New), the only autocomplete provider when set), reverse geocoding (google-geocoding) and drive-time routing (google); enable Places API (New), Geocoding API, Routes API. Without: OpenStreetMap Nominatim + sample addresses; straight-line distance × detour factor |
| `GOOGLE_PLACES_API_KEY` | Google Maps Platform | geo | secret | optional | a separate key for Google Places (New) only; wins over GOOGLE_MAPS_API_KEY for autocomplete. Without: GOOGLE_MAPS_API_KEY, else OpenStreetMap Nominatim + sample addresses |
| `GEO_PROVIDERS` | Google Maps Platform | geo | config | optional | autocomplete providers without a Google key (locally fixture,osm-nominatim); ignored when a key is set. Without: fixture,osm-nominatim |
| `SMARTY_AUTH_ID` | Smarty | geo | public id | optional | US address autocomplete (provider smarty). Without: not used |
| `SMARTY_AUTH_TOKEN` | Smarty | geo | secret | optional | US address autocomplete (provider smarty). Without: not used |
| `UPS_CLIENT_ID` | UPS | carrier-gateway | public id | optional | UPS rates, labels, tracking (sandbox locally). Without: UPS is skipped at start-up |
| `UPS_CLIENT_SECRET` | UPS | carrier-gateway | secret | optional | UPS OAuth. Without: UPS is skipped |
| `UPS_ACCOUNT` | UPS | carrier-gateway | public id | optional | UPS shipper number. Without: UPS is skipped |
| `UPS_WEBHOOK_CREDENTIAL` | UPS | carrier-gateway | secret | optional | verifies UPS tracking webhooks. Without: polling only |
| `USPS_CLIENT_ID` | USPS | carrier-gateway | public id | optional | USPS rates, labels, tracking (TEM sandbox locally). Without: USPS is skipped |
| `USPS_CLIENT_SECRET` | USPS | carrier-gateway | secret | optional | USPS OAuth. Without: USPS is skipped |
| `USPS_PAYMENT_TOKEN` | USPS | carrier-gateway | secret | optional | USPS label payment authorization. Without: USPS labels cannot be bought |
| `FEDEX_CLIENT_ID` | FedEx | carrier-gateway | public id | optional | FedEx rates, labels, tracking (sandbox locally). Without: FedEx is skipped |
| `FEDEX_CLIENT_SECRET` | FedEx | carrier-gateway | secret | optional | FedEx OAuth. Without: FedEx is skipped |
| `FEDEX_ACCOUNT` | FedEx | carrier-gateway | public id | optional | FedEx account number. Without: FedEx is skipped |
| `FEDEX_WEBHOOK_SECRET` | FedEx | carrier-gateway | secret | optional | verifies FedEx tracking webhooks. Without: polling only |
| `DHL_USERNAME` | DHL Express | carrier-gateway | public id | optional | MyDHL API (test endpoint locally). Without: DHL is skipped |
| `DHL_PASSWORD` | DHL Express | carrier-gateway | secret | optional | MyDHL API. Without: DHL is skipped |
| `DHL_ACCOUNT` | DHL Express | carrier-gateway | public id | optional | DHL account number. Without: DHL is skipped |
| `DHL_WEBHOOK_SECRET` | DHL Express | carrier-gateway | secret | optional | verifies DHL push notifications. Without: polling only |
| `GS1_API_KEY` | GS1 | catalog | secret | optional | GTIN product data (Verified by GS1) when GS1_ENABLED=true. Without: Open Food Facts only (no key) |
| `GS1_ENABLED` | GS1 | catalog | config | optional | turns the GS1 lookup on. Without: false |
| `PEXELS_API_KEY` | Pexels | tools/seed | secret | optional | demo product photos for make seed; the mobile apps' welcome photo / clip (make mobile-hero). Without: Unsplash, then Wikimedia Commons (no key) |
| `UNSPLASH_ACCESS_KEY` | Unsplash | tools/seed | secret | optional | demo product photos for make seed. Without: Wikimedia Commons (no key) |
| `UPLOADTHING_TOKEN` | UploadThing | media | secret | deployed only | product and storefront images in dev/uat/prod (UploadThing v7 token). Without: local stores every file in MinIO |
| `UPLOADTHING_API_KEY` | UploadThing | media | secret | deployed only | UploadThing secret key (sk_live_…; with UPLOADTHING_APP_ID) when no v7 token is set. Without: local stores every file in MinIO |
| `UPLOADTHING_APP_ID` | UploadThing | media | public id | deployed only | UploadThing app id. Without: - |
| `SMTP_HOST` | SMTP relay | notifications | config | optional | the smtp email provider's host (locally Mailpit on localhost:1025). Without: localhost (Mailpit) |
| `SMTP_USERNAME` | SMTP relay | notifications | config | optional | SMTP login. Without: none (Mailpit) |
| `SMTP_PASSWORD` | SMTP relay | notifications | secret | optional | SMTP password. Without: none (Mailpit) |
| `NEXT_PUBLIC_SENTRY_DSN` | Sentry | web-storefront, web-seller-center, web-staff-console | public id | optional | browser error reporting. Without: errors stay in the browser console |
| `SENTRY_DSN` | Sentry | web-storefront, web-seller-center, web-staff-console | public id | optional | server-side (Next.js) error reporting. Without: errors stay in the dev server log |
| `EXPO_PUBLIC_SENTRY_DSN` | Sentry | mobile-customer, mobile-driver | public id | optional | app crash and error reporting. Without: errors stay in Metro's log |
| `SENTRY_AUTH_TOKEN` | Sentry | web-storefront, web-seller-center, web-staff-console, mobile-customer, mobile-driver | secret | optional | uploads source maps at build time (CI / EAS). Without: stack traces are minified |
<!-- END GENERATED: external -->

### Internal secrets

Locally each has a committed, labelled default; deployed it goes in the service's secret.

<!-- BEGIN GENERATED by tools/dev/secrets_check.py --write-docs: internal -->
| Variable | Read by | Purpose |
|---|---|---|
| `ADS_TOKEN_KEY` | search | search: HMAC key for sponsored-result click tokens |
| `BFF_CLIENT_SECRET` | identity, tools/seed | legacy alias of GATEWAY_BFF_CLIENT_SECRET |
| `BOOKINGS_CLIENT_SECRET` | bookings, identity | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `BOOTSTRAP_ADMIN_PASSWORD` | identity | first super admin's password (identity creates the admin once) |
| `CART_CLIENT_SECRET` | cart, identity | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `CATALOG_CLIENT_SECRET` | catalog, identity | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `CLOUDFLARE_API_TOKEN` | GitHub Actions, storefront | storefront service: Cloudflare cache purge (also a GitHub Actions secret) |
| `DB_PASSWORD` | ai-gateway, bookings, catalog, content, delivery-runs, fulfilment, identity, inventory, offers, orders, payments, retail, seller, tax-returns, trust-ops | the service's Postgres password (OpenTofu creates the role; one database per service) |
| `DB_URL` | ai-gateway, bookings, carrier-gateway, cart, catalog, content, delivery-runs, fulfilment, geo, identity, inventory, media, notifications, offers, orders, payments, retail, seller, storefront, tax-returns, trust-ops | the service's Postgres URL (Go: DSN incl. password; Java: JDBC URL, password in DB_PASSWORD) |
| `DELIVERY_RUNS_CLIENT_SECRET` | delivery-runs, identity | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `EDI_AS2_KEYSTORE_PATH` | retail | retail: AS2 keystore file for supplier EDI |
| `EDI_SFTP_PRIVATE_KEY_PATH` | retail | retail: SFTP private key file for supplier EDI |
| `ES_API_KEY` | indexer, search | Elasticsearch API key (search, indexer) |
| `ES_PASSWORD` | indexer, search | Elasticsearch password (when not using an API key) |
| `FGA_API_TOKEN` | catalog, offers, orders, payments | OpenFGA pre-shared key (Java services) |
| `FIELD_ENCRYPTION_KEY` | seller | seller: AES-256 key for bank and tax fields (base64 of 32 bytes) |
| `FULFILMENT_CLIENT_SECRET` | fulfilment, identity | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `GATEWAY_BFF_CLIENT_SECRET` | identity, tools/seed | identity's copy of the BFF client secret |
| `HANDOVER_CODE_KEY` | orders | orders: HMAC key for delivery hand-over codes |
| `IDENTITY_DATA_KEY` | identity | identity's AES-256 key for TOTP secrets at rest (base64 of 32 random bytes) |
| `IDENTITY_DATA_KEYS_PREVIOUS` | identity | retired data keys during rotation (id:base64,…) |
| `IDENTITY_TEST_USERS_PASSWORD` | identity | dev only: the shared password of the seeded test users (local: Test1234!Local) |
| `INDEXER_CLIENT_SECRET` | identity, indexer | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `INVENTORY_CLIENT_SECRET` | identity, inventory | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `LINK_SIGNING_KEY` | media | media: HMAC key for long-lived document links |
| `LINK_SIGNING_KEYS_PREVIOUS` | media | media: retired link keys during rotation |
| `MEDIA_CLIENT_SECRET` | identity, media | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `MEDIA_SERVICE_TOKEN` | seller | seller → media service token (legacy; client credentials preferred) |
| `NEWSLETTER_TOKEN_KEY` | content | content: HMAC key for newsletter confirm/unsubscribe links |
| `NOTIFICATIONS_CLIENT_SECRET` | identity, notifications | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `OFFERS_CLIENT_SECRET` | identity, offers | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `OIDC_CLIENT_SECRET` | gateway-bff | gateway-bff's confidential OIDC client at identity (= identity's GATEWAY_BFF_CLIENT_SECRET) |
| `OPENFGA_API_TOKEN` | authz-sync | OpenFGA pre-shared key (authz-sync) |
| `ORDERS_CLIENT_SECRET` | identity, orders | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `PAYMENTS_CLIENT_SECRET` | identity, payments | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `RETAIL_CLIENT_SECRET` | identity, retail | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `S3_ACCESS_KEY_ID` | carrier-gateway | carrier-gateway label store (S3-compatible) when not using workload identity |
| `S3_SECRET_ACCESS_KEY` | carrier-gateway | carrier-gateway label store secret |
| `SEARCH_CLIENT_SECRET` | identity, search | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `SEED_CLIENT_SECRET` | tools/seed | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `SEED_TEST_USERS_PASSWORD` | tools/seed | tools/seed: the test users' password when it is not the local one (dev) |
| `SEED_TOKEN` | tools/seed | tools/seed: a ready bearer token instead of client credentials (optional) |
| `SELLER_SERVICE_CLIENT_SECRET` | identity, seller | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `SESSION_SIGNING_KEY` | gateway-bff | gateway-bff session cookie HMAC key |
| `SIGNING_KEY_PEM` | identity | identity's JWT signing key (RSA, PEM); local generates an ephemeral one |
| `SMOKE_CLIENT_SECRET` | GitHub Actions | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `TAX_RETURNS_CLIENT_SECRET` | identity, tax-returns | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `TEMPORAL_TLS_KEY` | orders | orders: mTLS key for Temporal Cloud (with TEMPORAL_TLS_CERT) |
| `TRUSTOPS_CLIENT_SECRET` | identity, trust-ops | OAuth2 client-credentials secret of a service at identity (identity holds the same value as <SERVICE>_CLIENT_SECRET) |
| `UNSUBSCRIBE_SIGNING_KEY` | notifications | notifications: HMAC key for one-click unsubscribe links |
<!-- END GENERATED: internal -->

### Per app: what goes in `afrotransact/<env>/<service>`

Plus the OpenTofu-seeded values (`DB_*`, Kafka, `ES_URLS`). Web and mobile apps: see above.

<!-- BEGIN GENERATED by tools/dev/secrets_check.py --write-docs: per-app -->
| App / service | Provider variables it reads | Internal secrets it reads |
|---|---|---|
| ai-gateway | `OPENROUTER_API_KEY` | `DB_PASSWORD`, `DB_URL` |
| authz-sync | - | `OPENFGA_API_TOKEN` |
| bookings | - | `BOOKINGS_CLIENT_SECRET`, `DB_PASSWORD`, `DB_URL` |
| carrier-gateway | `DHL_ACCOUNT`, `DHL_PASSWORD`, `DHL_USERNAME`, `DHL_WEBHOOK_SECRET`, `FEDEX_ACCOUNT`, `FEDEX_CLIENT_ID`, `FEDEX_CLIENT_SECRET`, `FEDEX_WEBHOOK_SECRET`, `UPS_ACCOUNT`, `UPS_CLIENT_ID`, `UPS_CLIENT_SECRET`, `UPS_WEBHOOK_CREDENTIAL`, `USPS_CLIENT_ID`, `USPS_CLIENT_SECRET`, `USPS_PAYMENT_TOKEN` | `DB_URL`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY` |
| cart | - | `CART_CLIENT_SECRET`, `DB_URL` |
| catalog | `GS1_API_KEY`, `GS1_ENABLED` | `CATALOG_CLIENT_SECRET`, `DB_PASSWORD`, `DB_URL`, `FGA_API_TOKEN` |
| content | - | `DB_PASSWORD`, `DB_URL`, `NEWSLETTER_TOKEN_KEY` |
| delivery-runs | - | `DB_PASSWORD`, `DB_URL`, `DELIVERY_RUNS_CLIENT_SECRET` |
| fulfilment | - | `DB_PASSWORD`, `DB_URL`, `FULFILMENT_CLIENT_SECRET` |
| gateway-bff | `STRIPE_PUBLISHABLE_KEY` | `OIDC_CLIENT_SECRET`, `SESSION_SIGNING_KEY` |
| geo | `GEO_PROVIDERS`, `GOOGLE_MAPS_API_KEY`, `GOOGLE_PLACES_API_KEY`, `SMARTY_AUTH_ID`, `SMARTY_AUTH_TOKEN` | `DB_URL` |
| identity | `APPLE_CLIENT_ID`, `APPLE_CLIENT_SECRET`, `FACEBOOK_CLIENT_ID`, `FACEBOOK_CLIENT_SECRET`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | `BFF_CLIENT_SECRET`, `BOOKINGS_CLIENT_SECRET`, `BOOTSTRAP_ADMIN_PASSWORD`, `CART_CLIENT_SECRET`, `CATALOG_CLIENT_SECRET`, `DB_PASSWORD`, `DB_URL`, `DELIVERY_RUNS_CLIENT_SECRET`, `FULFILMENT_CLIENT_SECRET`, `GATEWAY_BFF_CLIENT_SECRET`, `IDENTITY_DATA_KEY`, `IDENTITY_DATA_KEYS_PREVIOUS`, `IDENTITY_TEST_USERS_PASSWORD`, `INDEXER_CLIENT_SECRET`, `INVENTORY_CLIENT_SECRET`, `MEDIA_CLIENT_SECRET`, `NOTIFICATIONS_CLIENT_SECRET`, `OFFERS_CLIENT_SECRET`, `ORDERS_CLIENT_SECRET`, `PAYMENTS_CLIENT_SECRET`, `RETAIL_CLIENT_SECRET`, `SEARCH_CLIENT_SECRET`, `SELLER_SERVICE_CLIENT_SECRET`, `SIGNING_KEY_PEM`, `TAX_RETURNS_CLIENT_SECRET`, `TRUSTOPS_CLIENT_SECRET` |
| indexer | - | `ES_API_KEY`, `ES_PASSWORD`, `INDEXER_CLIENT_SECRET` |
| inventory | - | `DB_PASSWORD`, `DB_URL`, `INVENTORY_CLIENT_SECRET` |
| media | `UPLOADTHING_API_KEY`, `UPLOADTHING_APP_ID`, `UPLOADTHING_TOKEN` | `DB_URL`, `LINK_SIGNING_KEY`, `LINK_SIGNING_KEYS_PREVIOUS`, `MEDIA_CLIENT_SECRET` |
| mobile-customer | `EXPO_PUBLIC_SENTRY_DSN`, `EXPO_PUBLIC_STRIPE_MERCHANT_ID`, `EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY` | - |
| mobile-driver | `EXPO_PUBLIC_SENTRY_DSN` | - |
| notifications | `APNS_KEY_ID`, `APNS_KEY_P8`, `APNS_TEAM_ID`, `EMAIL_FROM`, `EMAIL_REPLY_TO`, `FCM_PROJECT_ID`, `FCM_SERVICE_ACCOUNT_JSON`, `RESEND_API_KEY`, `RESEND_WEBHOOK_SECRET`, `SENDGRID_API_KEY`, `SENDGRID_WEBHOOK_PUBLIC_KEY`, `SMTP_HOST`, `SMTP_PASSWORD`, `SMTP_USERNAME`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM`, `TWILIO_MESSAGING_SERVICE_SID`, `VONAGE_API_KEY`, `VONAGE_API_SECRET`, `VONAGE_FROM`, `WHATSAPP_PHONE_NUMBER_ID`, `WHATSAPP_TOKEN` | `DB_URL`, `NOTIFICATIONS_CLIENT_SECRET`, `UNSUBSCRIBE_SIGNING_KEY` |
| offers | - | `DB_PASSWORD`, `DB_URL`, `FGA_API_TOKEN`, `OFFERS_CLIENT_SECRET` |
| orders | - | `DB_PASSWORD`, `DB_URL`, `FGA_API_TOKEN`, `HANDOVER_CODE_KEY`, `ORDERS_CLIENT_SECRET`, `TEMPORAL_TLS_KEY` |
| payments | `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | `DB_PASSWORD`, `DB_URL`, `FGA_API_TOKEN`, `PAYMENTS_CLIENT_SECRET` |
| retail | - | `DB_PASSWORD`, `DB_URL`, `EDI_AS2_KEYSTORE_PATH`, `EDI_SFTP_PRIVATE_KEY_PATH`, `RETAIL_CLIENT_SECRET` |
| search | - | `ADS_TOKEN_KEY`, `ES_API_KEY`, `ES_PASSWORD`, `SEARCH_CLIENT_SECRET` |
| seller | `STRIPE_BILLING_WEBHOOK_SECRET`, `STRIPE_CONNECT_WEBHOOK_SECRET`, `STRIPE_SECRET_KEY` | `DB_PASSWORD`, `DB_URL`, `FIELD_ENCRYPTION_KEY`, `MEDIA_SERVICE_TOKEN`, `SELLER_SERVICE_CLIENT_SECRET` |
| storefront | - | `CLOUDFLARE_API_TOKEN`, `DB_URL` |
| tax-returns | - | `DB_PASSWORD`, `DB_URL`, `TAX_RETURNS_CLIENT_SECRET` |
| tools/seed | `PEXELS_API_KEY`, `UNSPLASH_ACCESS_KEY` | `BFF_CLIENT_SECRET`, `GATEWAY_BFF_CLIENT_SECRET`, `SEED_CLIENT_SECRET`, `SEED_TEST_USERS_PASSWORD`, `SEED_TOKEN` |
| trust-ops | - | `DB_PASSWORD`, `DB_URL`, `TRUSTOPS_CLIENT_SECRET` |
| web-seller-center | `EXPO_PUBLIC_SENTRY_DSN`, `NEXT_PUBLIC_SENTRY_DSN`, `SENTRY_AUTH_TOKEN`, `SENTRY_DSN` | - |
| web-staff-console | `EXPO_PUBLIC_SENTRY_DSN`, `NEXT_PUBLIC_SENTRY_DSN`, `SENTRY_AUTH_TOKEN`, `SENTRY_DSN` | - |
| web-storefront | `EXPO_PUBLIC_SENTRY_DSN`, `NEXT_PUBLIC_SENTRY_DSN`, `SENTRY_AUTH_TOKEN`, `SENTRY_DSN` | - |
<!-- END GENERATED: per-app -->

Regenerate after adding a variable: `python3 tools/dev/secrets_check.py --write-docs` (and add a provider variable to
`CATALOGUE`, an internal one to `INTERNAL`, in that script; `--check` fails until you do).
