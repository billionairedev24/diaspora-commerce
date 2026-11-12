# Email through Resend (notifications service)

Production and the trial send every email (order updates, security codes, receipts, marketing) through
[Resend](https://resend.com). `go/services/notifications` refuses to start in `APP_ENV=prod` unless `RESEND_API_KEY`
and `RESEND_WEBHOOK_SECRET` are set, Resend is the first email provider of every market, `UNSUBSCRIBE_SIGNING_KEY` is at
least 32 characters and `email.from` is on a real domain (`internal/config/validate.go`). Nothing is routed to Mailpit
or the log in prod. No secret goes in the repo.

Resend plan limits change; check the current free-tier daily and monthly limits on the Resend pricing page before the
trial and before launch, and upgrade before the volume gets near them.

## 1. Account and sending domain (once per environment)

Use the sending subdomain `mail.afrotransact.com` (the `email.from` in `config/prod.yaml` is
`AfroTransact <no-reply@mail.afrotransact.com>`). A subdomain keeps the transactional reputation apart from the main domain.
Trial: use `mail.trial.afrotransact.com` and override `EMAIL_FROM` and `EMAIL_UNSUBSCRIBE_MAILTO` for the trial deployment.

1. Sign in at `https://resend.com` (create the team account with a shared mailbox, not a personal one).
2. Left menu **Domains** > **Add Domain**. Name `mail.afrotransact.com`. Region: the closest to your recipients (for example
   US East). Click **Add**.
3. Resend shows DNS records: an **MX** and a **TXT (SPF)** record for the `send` subdomain and a **TXT (DKIM)** record
   `resend._domainkey`. Add them in the Cloudflare dashboard: select the `afrotransact.com` zone > **DNS** > **Records** >
   **Add record**, type, name and value exactly as Resend shows, **Proxy status: DNS only** (grey cloud), TTL Auto. Save each.
4. Back in Resend click **Verify DNS Records**. Wait until the domain shows **Verified** (minutes, up to a day).
5. Add DMARC once on the root: **Add record**, type TXT, name `_dmarc`, value `v=DMARC1; p=none; rua=mailto:dmarc@afrotransact.com`.
   Tighten to `p=quarantine` after a month of clean reports.
6. In the domain's settings turn **Click tracking** and **Open tracking** off (transactional mail, and tracking rewrites
   the links in security emails). Leave **TLS** on **Opportunistic** or stricter.

## 2. API key (sending access, that domain only)

1. **API Keys** > **Create API Key**.
2. Name `notifications-prod` (trial: `notifications-trial`). **Permission: Sending access**. **Domain: mail.afrotransact.com**
   (never **Full access**). Click **Add**.
3. Copy the key (`re_...`) now; it is shown once. Store it as `RESEND_API_KEY` (section 4).

## 3. Webhook (bounces, complaints, deliveries)

Permanent bounces and spam complaints must reach the service so the address is suppressed; otherwise the sending
domain's reputation suffers.

1. **Webhooks** > **Add Webhook**.
2. Endpoint URL: `https://api.afrotransact.com/notifications/v1/webhooks/resend` (trial: `https://api.trial.afrotransact.com/notifications/v1/webhooks/resend`).
3. Events: `email.delivered`, `email.bounced`, `email.complained` (add `email.delivery_delayed` if you want it logged).
4. **Add**. Open the webhook and copy the **Signing Secret** (`whsec_...`). Store it as `RESEND_WEBHOOK_SECRET`.
5. The service verifies the Svix signature on every call; a webhook without the secret answers 503, never open.

## 4. Where each value goes

Secrets go to the cloud secret store at `afrotransact/<env>/notifications` with `infra/tofu/scripts/secret-set.sh`
(External Secrets syncs them into the pod); nothing is committed.

| Value | Secret / env var | Notes |
|---|---|---|
| API key `re_...` | `RESEND_API_KEY` | sending access, one domain |
| Webhook signing secret `whsec_...` | `RESEND_WEBHOOK_SECRET` | from the webhook page |
| Unsubscribe link signing key | `UNSUBSCRIBE_SIGNING_KEY` | at least 32 random characters: `openssl rand -base64 48`; rotating it invalidates old unsubscribe links |
| Sender | `EMAIL_FROM` (optional) | default `AfroTransact <no-reply@mail.afrotransact.com>`; must be on the verified domain |
| Reply-to | `EMAIL_REPLY_TO` (optional) | default `support@afrotransact.com` |

```bash
infra/tofu/scripts/secret-set.sh afrotransact/prod/notifications RESEND_API_KEY
infra/tofu/scripts/secret-set.sh afrotransact/prod/notifications RESEND_WEBHOOK_SECRET
infra/tofu/scripts/secret-set.sh afrotransact/prod/notifications UNSUBSCRIBE_SIGNING_KEY
```

Restart the notifications deployment after changing a secret. The service logs the exact missing item and exits if
something required is absent.

Attachments: purchase orders and similar documents are fetched from media's private R2 bucket links
(`*.r2.cloudflarestorage.com`, allow-listed in `config/prod.yaml`) and attached to the email; larger files stay links.

## 5. Test it

1. Send a rendered template to yourself. The destination is always the email address in your own staff token, never a
   caller-supplied one (needs `notifications:templates:write`). List the template keys, then test-send one:

```bash
curl -sS https://api.afrotransact.com/notifications/v1/templates -H "Authorization: Bearer $STAFF_TOKEN"
curl -sS -X POST https://api.afrotransact.com/notifications/v1/templates/$KEY/test-send \
  -H "Authorization: Bearer $STAFF_TOKEN" -H "Content-Type: application/json" -d '{}'
```

   The subject is prefixed `[TEST]`. A real flow also works: sign up with an address you own, the verification email goes
   through Resend. In Resend **Emails** the message shows status **Delivered**.
2. Open the received email's headers: `dkim=pass`, `spf=pass`, `dmarc=pass` for `mail.afrotransact.com`.
3. Bounce and complaint path: in Resend **Emails** > **Send email**, send from `no-reply@mail.afrotransact.com` to
   `bounced@resend.dev`, then to `complained@resend.dev` (Resend's test addresses). **Webhooks** > your webhook > **Logs**
   must show `email.bounced` and `email.complained` answered 200 by our endpoint. Our suppression list
   (`GET /v1/suppressions?address=...`, needs `notifications:read`) only gains an address for messages the service itself
   sent, so for a full end-to-end check have a real test mailbox you own hard-bounce once the service sends to it.
4. Wrong secret check: in the webhook page use **Send test event**: expect 200. A wrong `RESEND_WEBHOOK_SECRET` shows 401 in the
   delivery log.

## Trial notes (8 days)

Same steps with the trial domain. Revoke the trial API key and delete the trial webhook at teardown; remove the
`mail.trial` DNS records. Do not email real customers from the trial: use your own mailboxes and `*@resend.dev` test
addresses.

## Adding a failover later

`routing.email.default` and `.us` in `config/prod.yaml` hold the provider order. Append `sendgrid` (needs
`SENDGRID_API_KEY` and `SENDGRID_WEBHOOK_PUBLIC_KEY`) or `ses` (needs AWS credentials through workload identity), for
example `[resend, sendgrid]`. Resend must stay first; the startup check enforces it.
