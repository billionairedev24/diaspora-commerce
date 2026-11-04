# notifications

Go service (port **8208** locally, Postgres database `notifications`) that sends templated, consent-checked
email, SMS, push and WhatsApp messages with per-market provider failover, and records delivery receipts.
Architecture: section 18 of `design/AfroTransact Architecture.dc.html`. Backlog: E19 (S121–S125), S211 (back-in-stock alerts), S217 (rules aligned with real producer events), S230 (trust-ops claims, moderation and
suspensions), plus organisation invitations (S231), phone verification (S234) and retail's supplier
purchase-order emails with real attachments (S243).

## How a message flows

```
Kafka event ──► Ingest: rule → recipients ──────────┐  (payload, or identity by user/org id, TTL-cached)
inventory.availability.changed ──► StockAlerts ─────┤  (claim active alerts for the SKU)
POST /v1/notifications (Idempotency-Key) ───────────┴► Notifier: render template ─► notification table (outbox)
                                                         (events: inbox de-dup in the same transaction)  │
                                                                         SELECT … FOR UPDATE SKIP LOCKED │
Dispatcher ◄─────────────────────────────────────────────────────────────────────────────────────────────┘
  ├─ ConsentChecker: suppression, TCPA SMS consent, preferences, quiet hours ─► suppressed / rescheduled
  ├─ email attachments: signed media links fetched (allow-listed hosts, size limit); failures stay links
  ├─ Router: providers for (channel, market) in order, circuit breaker each, 3 s per attempt,
  │          fail over on timeout / 5xx / retryable; stop on permanent errors
  └─ sent ─► provider webhooks ─► delivered / bounced / complained / failed (never backwards)
     all failed ─► re-queue with exponential backoff ─► dead_letter (POST /v1/dead-letters/{id}/replay)
```

## Layout

| Path | What |
|---|---|
| `internal/domain` | channels, categories, statuses, `Decide` consent rules, quiet hours, SMS keywords, circuit breakers, retry policy, `ProviderError` (retryable vs permanent) |
| `internal/port` | `EmailSender` (+ optional `AttachmentCapable`), `AttachmentFetcher`, `SmsSender`, `PushSender`, `ChatSender`, `ConsentChecker`, `ConsentStore`, `TemplateStore`, `NotificationStore`, `ContactResolver`, `RecipientResolver`, `ContactInvalidator`, `StockAlertStore` |
| `internal/app` | `Router`, `Notifier`, `Dispatcher`, `Ingest` (+ `rules.go`: topics and event rules), `StockAlerts`, `Receipts`, `SmsCompliance`, `Templates`, `Preferences`, `NotificationLog`, `Unsubscriber`, `Renderer` |
| `internal/adapter/{resend,sendgrid,ses,smtp}` | email adapters (Resend uses `Idempotency-Key`); all four attach files |
| `internal/adapter/mediafetch` | `AttachmentFetcher`: downloads signed media links from allow-listed hosts (or `host/path/` prefixes), size-limited, redirects re-checked. Media-hosted long-lived links (`<API_BASE_URL>/media/v1/l/…`, S250) are allowed by path prefix only (`attachments.mediaLinkBaseUrl` / `MEDIA_LINK_BASE_URL`, default `<API_BASE_URL>/media`) and redirect to the storage host |
| `internal/adapter/{twilio,vonage,awssms}` | SMS adapters (AWS = End User Messaging `SendTextMessage`) |
| `internal/adapter/{fcm,apns}` | push: FCM HTTP v1 (OAuth2 service account), APNs HTTP/2 with ES256 provider token |
| `internal/adapter/whatsapp` | WhatsApp Cloud API (text, or approved template + parameters) |
| `internal/adapter/logsender` | log provider for every channel (local) |
| `internal/adapter/webhooksig` | Twilio HMAC-SHA1, Resend/Svix HMAC-SHA256, SendGrid ECDSA verifiers |
| `internal/adapter/postgres` | pgx/v5 stores, embedded migrations (`migrations/*.sql`, applied at startup), consent checker |
| `internal/adapter/httpapi` | REST API, webhooks, one-click unsubscribe |
| `internal/adapter/jwtcontact` | caller's own email/phone for test-send |
| `internal/adapter/identity` | `RecipientResolver` over identity (client credentials `notifications-service`, scope `users:read`) |
| `internal/adapter/contactcache` | TTL cache in front of the resolver (not-found cached briefly, errors never); `Forget(userId)` on identity contact changes |
| `cmd/notifications` | explicit wiring (`main.go`) and provider construction from config (`providers.go`) |

## Rules

* **Categories come from the template**: `security` (OTP, verification) always sends; `transactional` sends
  unless the recipient turned the channel off; `reminder` respects preferences and quiet hours; `marketing` needs
  explicit opt-in, respects quiet hours and carries `List-Unsubscribe` + `List-Unsubscribe-Post: List-Unsubscribe=One-Click`.
* **SMS** needs a TCPA consent record (`POST /v1/consents/sms`: source + disclosure text version, audit trail kept).
  Inbound STOP, END, CANCEL, UNSUBSCRIBE, QUIT (and STOPALL/OPTOUT/REVOKE) revoke it; START/UNSTOP/YES restore it;
  HELP/INFO get the program help text (TwiML reply).
* **Quiet hours** default 21:00–08:00 in the recipient's time zone (request `recipient.timeZone`, else the market's
  zone from `marketTimeZones`); held messages are rescheduled to the end of the window, not counted as failures.
* **Suppression**: permanent bounces and complaints from Resend/SendGrid add the address; everything except
  security messages is then suppressed.
* **Errors**: adapters return `*domain.ProviderError`. Retryable = transport errors, timeouts, 408/425/429/5xx,
  401/403 (provider-account problems another provider avoids) and provider-specific throttling codes.
  Permanent = other 4xx such as invalid numbers (Twilio 21211), unsubscribed numbers (21610), unregistered
  device tokens. Permanent errors are not failed over and go straight to the dead-letter table.
* **Breakers**: per provider; `failureThreshold` consecutive failures open it for `coolDown`, then one half-open
  probe decides.

## API

All `/v1` endpoints except webhooks and unsubscribe need a bearer token (`/docs`, `/redoc`, `/openapi.yaml`;
contract copy in `contracts/openapi/notifications.yaml`).

| Endpoint | Permission |
|---|---|
| `POST /v1/notifications` (header `Idempotency-Key`) | `notifications:send` |
| `GET /v1/notifications?recipient=&channel=&status=&before=&limit=`, `GET /v1/notifications/{id}` | `notifications:read` |
| `GET /v1/dead-letters`, `POST /v1/dead-letters/{id}/replay` | read / `notifications:manage` |
| `GET /v1/templates`, `GET /v1/templates/{key}/sample`, `POST /v1/templates/{key}/preview` (a stored version, or unsaved `draft` content) | `notifications:read` |
| `POST /v1/templates` (new version), `POST /v1/templates/{key}/test-send` (caller's own email/phone only) | `notifications:templates:write` |
| `GET/PUT /v1/preferences/{recipientId}` | the recipient, or `notifications:manage` |
| `POST /v1/consents/sms`, `GET /v1/consents/sms/{phone}` | `notifications:send` / read |
| `GET /v1/suppressions`, `DELETE /v1/suppressions/{address}` | read / `notifications:manage` |
| `POST /v1/webhooks/resend`, `/sendgrid`, `/twilio/status`, `/twilio/inbound` | provider signature |
| `GET/POST /v1/unsubscribe?t=…` | signed token (RFC 8058) |
| `POST /v1/stock-alerts`, `GET /v1/stock-alerts`, `DELETE /v1/stock-alerts/{atin}` | the shopper's own token (service tokens get 403) |

### Back-in-stock alerts (S211)

`POST /v1/stock-alerts` `{"atin": "ATGARI0001", "email"?, "market"?, "locale"?, "productTitle"?}` arms one alert per
shopper and variant ATIN (201; 200 with the existing alert when already active; 201 again re-arms one that was sent).
`GET` lists the caller's alerts (`active` / `notified`), `DELETE /v1/stock-alerts/{atin}` removes one (204 / 404).
On `inventory.availability.changed` (`at.inventory.events`, SKU = variant ATIN) with `available > 0`, up to 500 active
alerts for the SKU are claimed (`notified_event_id` = the event, so a redelivery re-claims the same rows and the inbox
prevents a second send) and `stock.back_in_stock` goes out by email (the alert's `email`, else identity's) and push.
Table `stock_alert` (migration `0004`).

## Email layout, brand and advertorials (S279)

Every email body is a **content fragment** rendered inside the shared layout `internal/app/email/layout.html.tmpl`
(a body that is a complete HTML document is sent as-is). The layout is table-based with inline styles, 600 px max,
a mobile media query, light only in every client (`color-scheme: light only`, no dark variant: a white body between the black header and footer), a hidden preheader
(`{{define "preheader"}}…{{end}}` in the body) and Figtree with a safe fallback stack. It has a black header with the
logo, a gold rule, the content, advertorials, a green **Join our WhatsApp community** block and a black footer. The
footer has social icons, App Store and Google Play badges when app links exist, the customer service phone, the postal
address, email preferences, unsubscribe (promotional emails) and legal links, plus wording for transactional,
reminder, security or promotional (CAN-SPAM) emails. The plain-text part gets the same footer.

Content helpers: `{{button url label}}` (gold, VML fallback for Outlook), `{{secondaryButton}}`, `{{otp code}}`,
`{{badge tone text}}`, `{{callout tone text}}`, `{{facts "Label" value …}}`, and the components
`{{template "packages" (index . "packages")}}` (line items with photo, quantity and price, grouped as Fulfilled by
AfroTransact, seller and pre-order packages), `"totals"`, `"address"` and `"bookingCard"`. Bare `<p>`, `<h1>`, `<a>`
and similar tags get the brand's inline styles.

* **Brand**: `adapter/sitecontent` refreshes content's public `GET /v1/site-config`, the same source as the
  storefront footer (socials, `whatsappCommunityUrl`, app links, phone, address and legal docs), over the
  `brand.*` defaults in config. Images are hosted PNGs under `brand.assetBaseUrl` (default `<links.storefront>/email`
  = `web/apps/storefront/public/email`): no SVG and no data URIs.
* **Advertorials**: `app.AdvertorialPolicy` lists the templates that may show a promotion below the content, and of
  which kinds: "Complete your pantry" (search), an open pre-order in the customer's city (delivery-runs
  `/v1/public/preorders/open`) or a house campaign (content `hero_carousel` placements). Sources are best-effort within
  `advertorials.timeout`. Advertorials never appear in security emails, nor for recipients with an
  email/marketing preference set to off.
* **Orders events** carry line photos (`lines[].imageUrl`), and shipment events carry `items`, `sellerName` and
  `currency` (additive, `contracts/asyncapi/fragments/orders.yaml`).
* **Tests**: `internal/app/emaillayout_test.go` renders every seeded email against golden files
  (`testdata/golden`; to refresh, run `go test ./internal/app -run Branded -update`). It checks the footer, socials,
  WhatsApp, unsubscribe on promotional emails, no ads in security emails, alt text, hosted PNGs, no external CSS and
  size under 100 KB. It also checks that every SMS has the brand, STOP and fits one GSM-7 segment.

## Events consumed

Consumer group `notifications`. Topics are configurable per environment (`kafka.topics.<name>` in
`config/<env>.yaml`) and default to the AsyncAPI channel addresses (`contracts/asyncapi/events.yaml`; consumer
fragment `contracts/asyncapi/fragments/notifications.yaml`):

| Name | Default address |
|---|---|
| `identity` | `at.identity.events` |
| `orders` | `at.order.events` |
| `sellerApplications` | `at.seller-application.events` |
| `sellerCompliance` | `at.seller-compliance.events` |
| `payouts` | `at.payout.events` |
| `bookings` | `at.bookings.events` |
| `content` | `at.content.events` |
| `inventory` | `at.inventory.events` |
| `payments` | `at.payment.events` |
| `trustOps` | `at.trustops.events` |
| `retail` | `at.retail.events` |

Rules (`internal/app/rules.go`) map an event type to one or more templates (a rule's `When` picks variants, e.g. by
`purpose`, `reason` or `status`). Recipients come from the payload's `recipient` block (or top-level `email`/`phone`),
from an identity user id (`customerId`, `applicantUserId`), or from every owner of an organisation (`orgId`,
`sellerOrgId`). Identity fills in missing email/phone/push tokens, first name, locale,
time zone and market, and channels the user switched off in their account are dropped (except security messages).

| Event type | Recipient | Template(s) | Channels |
|---|---|---|---|
| `identity.verification.requested` | payload (never looked up) | `identity.verification` | email, or the payload's `channel` |
| … with `purpose=PHONE_VERIFICATION` | payload (never looked up) | `identity.verification.phone` ("Your AfroTransact code is …") | sms, or the payload's `channel` |
| … with `purpose=PASSWORD_RESET` (S300) | payload (never looked up) | `identity.password_reset` (reset code) | the payload's `channel`: email, or sms to a verified phone |
| `identity.password-reset.federated` (S300) | payload (never looked up) | `identity.password_reset.federated` ("Sign in with Google") | email |
| `identity.password.changed` (S300) | payload (never looked up) | `identity.password_changed` ("Your password was changed") | email |
| `identity.invitation.created` | payload `recipient.email` (never looked up: the invitee may have no account) | `org.invitation` (`orgName`, `roleName`, `inviterName` or "A teammate", `acceptUrl`, `expiresInDays`) | email |
| `identity.user.phone-verified`, `identity.notification-preferences.updated` | — | none: the user's cached contact is dropped | — |
| `identity.user.registered` (not STAFF) | payload `email` | `account.welcome` | email |
| `orders.order.placed` | `customerId` | `order.confirmed` | email, sms, push |
| `orders.order.cancelled` (only `wasPlaced`) | `customerId` | `order.cancelled` | email, push |
| `orders.shipment.status_changed` → `picked_up` (or `in_transit` without a pickup scan) | `customerId` | `shipment.shipped` | email, push |
| … → `out_for_delivery` | `customerId` | `shipment.out_for_delivery` | sms, push, email |
| … → `ready` with `deliveryMethod=PICKUP` | `customerId` | `shipment.ready_for_pickup` | email, sms, push |
| … → `delivery_failed` | `customerId` | `shipment.delivery_failed` | email, sms, push |
| `orders.shipment.delivered` | `customerId` | `shipment.delivered` | email, push |
| `orders.shipment.cancelled` | `customerId` | `shipment.cancelled` (refund note from `captured`) | email, push; also sms when AfroTransact cancelled a pre-order (its window or run): "refunded in full, delivery fee included" |
| `seller.application.submitted` / `info_requested` / `approved` / `rejected` | `applicantUserId` | `seller.application.*` | email (+ sms for info requested / approved, + push for approved) |
| `seller.compliance.reminder` | org owners | `seller.compliance.reminder` | payload `channels` (`in_app` → push), default email, sms |
| `seller.compliance.changed` (new items, or → RESTRICTED_SOON / RESTRICTED) | org owners | `seller.compliance.action_needed` | as above |
| `seller.payouts.paused` / `resumed`, `seller.listings.hidden` / `restored` | org owners | same name | email (+ sms for paused / hidden) |
| … with `reason=SUSPENDED` / `REINSTATED` (Trust and Safety) | org owners | `seller.payouts.paused.suspended`, `seller.listings.hidden.suspended`, `seller.payouts.resumed.reinstated`, `seller.listings.restored.reinstated` (reason from the `[CODE] note`) | email (`trustops.seller.suspended` carries the sms) |
| `payments.payout.paid` / `failed` | org owners | `payout.paid` / `payout.failed` | email + push / email + sms |
| `booking.confirmed` / `cancelled` / `rescheduled` | payload `recipient` (+ identity) | same name | email, sms, push |
| `booking.declined` | payload `recipient` (+ identity) | `booking.declined` | email, push |
| `booking.balance_unpaid` | payload `recipient` (+ identity) | `booking.balance_unpaid` (update the card or authenticate; `balance`) | email, push |
| `payments.intent.action_required` (with `customerId`) | `customerId` | `payment.authentication_required` (`amount`, `reference`, `actionUrl`: `/bookings/BK-…` or `/orders`) | email, push |
| `trustops.claim.opened` | `customerId` | `claim.opened` | email, push |
| … while `status=AWAITING_SELLER` | `sellerOrgId` owners | `claim.seller_response_due` (respond by `dueAt`) | email, sms |
| `trustops.claim.decided` | `customerId` / `sellerOrgId` owners | `claim.decided` / `claim.decided.seller` (refund or denial) | email, push / email |
| `trustops.claim.withdrawn` | `sellerOrgId` owners | `claim.withdrawn` | email |
| `trustops.claim.response_due_soon` | `sellerOrgId` owners | `claim.seller_response_reminder` | email, sms |
| `trustops.claim.response_lapsed` | `sellerOrgId` owners | `claim.seller_response_lapsed` | email |
| `trustops.moderation.decided` with `status=REJECTED` | `sellerOrgId` owners | `listing.rejected` (`NEW_LISTING`) / `listing.taken_down` (`LIVE_LISTING`) | email / email, sms |
| `trustops.seller.suspended` / `reinstated` | `orgId` owners | `seller.suspended` / `seller.reinstated` | email, sms / email |
| `content.career-application.received` | payload `email` | `careers.application_received` | email |
| `content.newsletter.confirmation_requested` | payload `email` | `newsletter.confirm` | email |
| `delivery-runs.run.announced` (S262) | the location's subscribers per channel (`locationId`) | `run.announced` | email, push |
| `delivery-runs.run.opened` / `.cutoff_reminder` (S262) | the location's subscribers per channel | `run.opened` / `run.cutoff_reminder` | email, sms, push (+ WhatsApp templates when enabled) |
| `delivery-runs.run.out_for_delivery` (dispatch, S262; not a pre-order window's run) | the customers in `orders[]` (own `handoverCode` at a pickup point) | `run.out_for_delivery` | sms, push, email |
| `preorder.comms.due` `kind=OUT_FOR_DELIVERY` (S262: delivery-runs publishes it once when a window's run leaves) | the window's paid customers in `orders[]` (own `handoverCode` for a pickup-only window) | `preorder.out_for_delivery` | sms, push |
| `delivery-runs.run.confirmed` (not a pre-order window's run) | the customers in `orders[]` | `run.confirmed` | email, push |
| `delivery-runs.run.cancelled` (not a pre-order window's run) | the customers in `orders[]` | `run.cancelled` (refunded in full, delivery fee included) | email, sms, push |
| `delivery-runs.subscription.created` (pending, with `confirmUrl`) | payload `email` (never looked up) | `run.subscription.confirm` (double opt-in) | email |
| `delivery-runs.subscription.created` (active, signed in) / `.confirmed` | `userId` / payload `email` | `run.subscription.welcome` (+ WhatsApp community) | email |
| `inventory.availability.changed` | stock alerts | `stock.back_in_stock` | email, push |
| `notification.requested` (any topic) | payload `recipient` (+ identity) | `templateKey` | `channels` |
| … from retail with `templateKey=retail.purchase_order.sent` | payload `recipient.email` (the supplier contact; never looked up) | `retail.purchase_order.sent` (PDF attached, link fallback) | email |

### Delivery runs (S262, ADR 0005)

delivery-runs announces a run to the people who asked to hear about its town and tells the customers on it what
happens to their order.

- **Audience.** `delivery-runs.run.announced`, `.opened` and `.cutoff_reminder` page through
  `GET /v1/service/locations/{locationId}/audience?channel=EMAIL|SMS|PUSH|WHATSAPP` (scope
  `delivery-runs:audience.read`, the same client as pre-orders) once per channel the rule sends on. delivery-runs returns
  only members with consent on that channel, so an email-only subscriber gets email only and nobody is texted without
  an SMS opt-in. Each member carries their one-click `unsubscribeUrl`; push tokens, phone numbers and the account's own
  channel switches come from identity.
- **Attribution.** Every link is the run link with UTM from the event (`links.<channel>`: `utm_source` and
  `utm_medium` = the channel, `utm_campaign` = `delivery-run-<slug>-<yyyymmdd>`, `utm_content` = the event); the SMS link
  is the location page with the same UTM (the run id is dropped to keep the text short, so a run SMS is two GSM-7
  segments at most; every other SMS stays at one).
- **Dispatch and cancellation.** `.out_for_delivery` (the run left) and `.confirmed` go to the customers in `orders[]`.
  A run handed over from a pre-order window (`reason` "PREORDER <code>") is already covered by that window's own messages
  (its confirmation by `preorder.confirmed`, its dispatch by `preorder.out_for_delivery`, which delivery-runs publishes
  once, with each customer's handover code, when that run leaves; a window that is paused or has switched the message off
  sends nothing), and a run cancelled with its window (`reason` "Pre-order <code> cancelled: …") by the cancelled pre-order email
  orders-service sends per shipment, so neither is messaged twice. A run staff scheduled and then cancelled tells every
  customer on it, in email, SMS and push, that the order is refunded in full, delivery fee included (a card hold is
  released, nothing is charged).
- **Subscriptions.** `subscription.created` for a pending email-only subscriber sends the double opt-in link
  (`run.subscription.confirm`; nothing else is sent until it is used); a confirmed or signed-in subscriber gets the
  welcome with the location's WhatsApp community link. Withdrawing consent sends nothing: delivery-runs stops listing
  the member.
- **WhatsApp.** Off by default (`deliveryRuns.whatsapp.enabled`, env `DELIVERY_RUNS_WHATSAPP_ENABLED`) and it stays off
  until Meta has approved the templates: that is a review by Meta outside this repository and nothing here pretends
  otherwise. See "WhatsApp approval" below for the exact steps, names and bodies. Only members who opted in on WhatsApp
  (and have a verified phone in identity) receive them.
- **Locales.** The run and pre-order messages (`run.*`, `preorder.*`) exist in `en`, `fr`, `es`, `ar` and `yo`
  (migrations `0009`/`0011`/`0013`/`0019` for `en`, `0023` for the rest). The language is the event's `locale`, else the
  signed-in customer's language from identity; email-only subscribers have none yet, so they get English, and any other
  language falls back to English. `TestRunAndPreorderTemplatesExistInEveryLocale` fails when a locale, a channel or a
  placeholder is missing, so a new `run.*` / `preorder.*` message must come with all five. `fr` and `es` text messages
  are one GSM-7 segment (no `á í ó ú ê â ç`); `ar` and `yo` cannot be GSM-7 (UCS-2, 70 characters a segment) and may take
  more segments. The translations were written without a native-speaker review: have them reviewed before launch.
- **Pre-order announcement links.** Every `emailUrl` / `smsUrl` / `pushUrl` of a `preorder.comms.due` message is the
  pre-order page with the order attribution (`src` = email, sms or app for push, `po` = window code, `cr` = the message:
  announce, waitlist, remind, last, closed, ofd) and the UTM tags `utm_source` = `utm_medium` = the channel,
  `utm_campaign` = `preorder-<code>`, `utm_content` = the message, the same format delivery-runs builds
  (`PreorderUtm`). An order placed from the link is credited to that campaign in the staff pre-order analytics
  ("UTM campaign" chart). SMS with those links may take two segments, as run alerts do.
- **Cancelled windows and runs.** A pre-order window staff cancel (`preorder.window.cancelled`) is refunded by
  orders-service shipment by shipment; each `orders.shipment.cancelled` (PREORDER, actor not the customer) tells the
  customer by email, text message and push that the order is refunded in full, delivery fee included (or that nothing was
  charged and the card hold is released). A cancelled run says the same through `run.cancelled`.

### WhatsApp approval

WhatsApp only allows a business to start a conversation with a **template message that Meta has approved**. Until all of
the following is done, keep `deliveryRuns.whatsapp.enabled=false` (the default in every environment); email, text
message and push announcements are not affected. Nothing below can be done or tested from this repository.

1. **Business verification.** In Meta Business Manager (business.facebook.com) → Business settings → Security centre →
   start business verification for the AfroTransact legal entity. Without it the sending limits stay at the lowest tier.
2. **WhatsApp Business Account and number.** Create the WhatsApp Business Account, add a phone number that is not
   registered on a personal WhatsApp account, and get its display name approved. Note the **phone number ID**
   (WhatsApp Manager → Phone numbers) for `whatsapp.phoneNumberId` in the environment config.
3. **Templates.** WhatsApp Manager → Message templates → Create template, once per language below (same name, one
   language each: `en`, `fr`, `es`, `ar`, `yo`). Category **Marketing** (an announcement that a run is open is
   promotional; Meta re-categorises a promotional text submitted as Utility). Body only, no header, footer or buttons.
   Variables are positional and fixed: `{{1}}` town, `{{2}}` delivery day, `{{3}}` cutoff, `{{4}}` delivery fee, `{{5}}`
   link (the run link with UTM). Sample values for the review: Bastrop; Saturday, October 24; Thu, Oct 22 at 8:00 PM CDT;
   $7.99; https://afrotransact.com/delivery-runs/bastrop-tx?utm_source=whatsapp. Review takes minutes to a day; a
   template must be **Active** before it is used. The bodies do not tell the customer to reply STOP: replies to the
   business number are not processed by this service, so opting out is in the account (and the consent record).

   | Template name | Language | Body |
   |---|---|---|
   | `afrotransact_run_opened` | en | The {{1}} delivery run is open. We deliver on {{2}}. Order by {{3}}. Delivery fee: {{4}}. See the run and order: {{5}} Manage your alerts in your AfroTransact account. |
   | `afrotransact_run_opened` | fr | La tournée de livraison à {{1}} est ouverte. Livraison le {{2}}. Commandez avant {{3}}. Frais de livraison : {{4}}. Voir la tournée et commander : {{5}} Gérez vos alertes dans votre compte AfroTransact. |
   | `afrotransact_run_opened` | es | La ruta de entrega a {{1}} está abierta. Entregamos el {{2}}. Pide antes de {{3}}. Costo de entrega: {{4}}. Ver la ruta y pedir: {{5}} Gestiona tus alertas en tu cuenta de AfroTransact. |
   | `afrotransact_run_opened` | ar | جولة التوصيل إلى {{1}} مفتوحة. نوصّل في {{2}}. اطلب قبل {{3}}. رسوم التوصيل: {{4}}. شاهد الجولة واطلب: {{5}} أدِر تنبيهاتك من حسابك في أفروترانزاكت. |
   | `afrotransact_run_opened` | yo | Ìrìn ìfiránṣẹ́ sí {{1}} ti ṣí. A ń fi ránṣẹ́ ní {{2}}. Paṣẹ ṣáájú {{3}}. Owó ìfiránṣẹ́: {{4}}. Wo ìrìn náà kí o sì paṣẹ: {{5}} Ṣàkóso àwọn ìkìlọ̀ rẹ nínú àkọọ́lẹ̀ AfroTransact rẹ. |
   | `afrotransact_run_cutoff_reminder` | en | Last chance for the {{1}} delivery run. We deliver on {{2}}. Order by {{3}}. Delivery fee: {{4}}. Order here: {{5}} Manage your alerts in your AfroTransact account. |
   | `afrotransact_run_cutoff_reminder` | fr | Dernière chance pour la tournée de livraison à {{1}}. Livraison le {{2}}. Commandez avant {{3}}. Frais de livraison : {{4}}. Commander ici : {{5}} Gérez vos alertes dans votre compte AfroTransact. |
   | `afrotransact_run_cutoff_reminder` | es | Última oportunidad para la ruta de entrega a {{1}}. Entregamos el {{2}}. Pide antes de {{3}}. Costo de entrega: {{4}}. Pide aquí: {{5}} Gestiona tus alertas en tu cuenta de AfroTransact. |
   | `afrotransact_run_cutoff_reminder` | ar | الفرصة الأخيرة لجولة التوصيل إلى {{1}}. نوصّل في {{2}}. اطلب قبل {{3}}. رسوم التوصيل: {{4}}. اطلب من هنا: {{5}} أدِر تنبيهاتك من حسابك في أفروترانزاكت. |
   | `afrotransact_run_cutoff_reminder` | yo | Àǹfààní ìkẹyìn fún ìrìn ìfiránṣẹ́ sí {{1}}. A ń fi ránṣẹ́ ní {{2}}. Paṣẹ ṣáájú {{3}}. Owó ìfiránṣẹ́: {{4}}. Paṣẹ níbí: {{5}} Ṣàkóso àwọn ìkìlọ̀ rẹ nínú àkọọ́lẹ̀ AfroTransact rẹ. |

   The names and the parameter lines are the seeded `chat` rows of `run.opened` and `run.cutoff_reminder` (subject = the
   template name, one body line per parameter: town, delivery day, cutoff, fee, WhatsApp link; migrations `0019` and
   `0023`). The language code sent to Meta is the notification's locale (`en`, `fr`, `es`, `ar`, `yo`). If Meta does not
   offer a language for templates, or one is not approved, a send in that language is refused permanently and is not
   retried: either get it approved, or remove that language's `chat` rows in a new migration, **before** enabling.
4. **Access token.** Business settings → System users → add a system user, give it the WhatsApp account with the
   `whatsapp_business_messaging` permission, generate a token that never expires, and store it as the secret
   `WHATSAPP_TOKEN` (never in a config file). Set `whatsapp.phoneNumberId` (and `apiVersion` if Meta retires `v21.0`).
5. **Opt-in.** Meta requires the customer's opt-in; the record is the per-channel WhatsApp consent in delivery-runs, and
   only those members are listed for the WhatsApp channel. The location page asks for it with the other channels.
6. **Try it in uat first**, then production: send a test run announcement to a team phone that opted in; check the
   delivery receipt in the staff console and the template's quality rating in WhatsApp Manager.
7. **Switch on** with `DELIVERY_RUNS_WHATSAPP_ENABLED=true` (`deliveryRuns.whatsapp.enabled`) for notifications and roll
   the pods. To switch off, set it back to `false`; nothing else changes.

Not covered here: replies to the business number (there is no inbound WhatsApp webhook, so a customer cannot opt out
by replying) and the mobile customer app's subscription screen (not built; the storefront location page is the only
place to subscribe).

trust-ops also publishes `notification.requested` for `claim.opened`, `claim.decided` and `claim.withdrawn` (with the
seller org id as `recipient.id`, which identity cannot resolve as a user). Those keys (`SupersededGenericTemplates`)
are ignored on the generic path because the typed `trustops.claim.*` rules above already notify both parties.
trust-ops publishes no separate "response due" reminder or lapse event, so the seller's deadline goes out once, when
the claim opens.

### Supplier purchase-order emails (S243)

retail-service (S225) sends a sent PO to the supplier's purchasing contact as `notification.requested` on
`at.retail.events`: `templateKey` `retail.purchase_order.sent`, channel `email`, `recipient: {email, name}` (an external
address: `RequestedTemplates` marks the key `PayloadOnly`, so identity is never called, even if an id is present) and
`data` with `contactName`, `poNumber`, `supplierName`, `orderDate`, `deliverBy`, `incoterm`, `paymentTerms`, `total`,
`documentUrl` (signed media link to the PO PDF), `documentExpiresAt` and `attachments: [{assetId, fileName,
contentType, url, expiresAt}]`. The ingest turns JSON nulls into "" (the template drops empty rows), defaults
`contactName` ("there"), `supplierName` and `total` (from `totalMinor` + `currency`) and derives `documentExpiresOn`
("Oct 24, 2026"). That is the only template key retail requests (its dispatch `email-template` setting defaults to it).
Retail's typed events are not mapped: `retail.po.sent` would duplicate the request and `retail.po.dispatch-failed`
names no buyer to notify (its `recipient` is the supplier's address or EDI id).

### Attachments

Email requests may reference files by signed link: `attachments` on `POST /v1/notifications`, or `data.attachments` (as
retail sends them). At most 10 references are kept per email row (column `attachments`, migration `0007`; file names
are stripped of paths and quotes). The dispatcher fetches them when the email is sent (`AttachmentFetcher`,
`internal/adapter/mediafetch`):

* only `http(s)` URLs without user info whose host is allow-listed: the host of `attachments.mediaBaseUrl` plus
  `attachments.allowedHosts` (`host` matches any port, `host:port` that port, `*.example.com` any subdomain);
  redirects are re-checked against the same list; no credentials are sent (the link is signed);
* at most `attachments.maxBytes` per file (10 MiB; declared `Content-Length` or streamed) and
  `attachments.maxTotalBytes` per email (20 MiB);
* Resend, SendGrid, SES and SMTP attach the files (`SupportsAttachments`); the router strips them for providers that
  cannot (the log provider), so a failover still delivers the email;
* a file that is rejected, expired (403) or unreachable is left out with a warning, never failing the send: the
  template always renders the link (`documentUrl`), which is the fallback. With `attachments.enabled: false` emails
  carry links only.

`TestRulesMatchAsyncAPIContract` parses `contracts/asyncapi/events.yaml` and fails when a mapped event type or topic
is missing (or published on another topic), when a contact-change type is not on the identity topic, or when the S230
trust-ops rules (customer and seller owners for claims) are missing; `TestRequestedTemplatesMatchProducers` checks
each `RequestedTemplates` key (retail's PO email) against its producer: `notification.requested` is published on that
topic and the producer's send operation names the key; `TestEveryRuleHasSeededTemplates` checks every rule and
requested template has a seeded template per channel; the Postgres integration test renders a contract-shaped fixture of every mapped event.

Each event id is recorded in the `inbox` table in the same transaction as its notifications, so redeliveries
are ignored. A recipient that cannot be reached (no address, unknown user) is skipped; other recipients of the same
event still go out. Identity being unavailable fails the handler, and the consumer restarts from the last committed
offset (with backoff).

### Identity endpoints (planned)

`contracts/openapi/identity.yaml` has no service-callable user lookup yet, so the adapter targets:

* `GET /v1/internal/users/{userId}/contact` → `{userId, firstName, email, phone, phoneVerified, locale, timeZone,
  market, pushTokens: [{token, platform}], preferences: {email, sms, push}}` (404 = unknown user; `phoneVerified:
  false` drops the phone; `preferences.<channel>: false` drops the channel);
* `GET /v1/internal/orgs/{orgId}/owners` → `{orgId, ownerUserIds: [...]}`.

Both with a client-credentials token (`notifications-service`, scope `users:read`, secret `NOTIFICATIONS_CLIENT_SECRET`).
Until they exist, lookups return 404 and only payloads with addresses (OTP, bookings, content, generic) are delivered.

## Templates

Rows of `template(key, locale, channel, version)`; the latest version is used, with locale fallback
`fr-CA → fr → en`. Email HTML is Go `html/template` — compiled React Email or MJML output can be stored as-is with
`{{.field}}` placeholders; subjects and text are `text/template`. Missing fields fail rendering. Seeded in English (other
locales fall back to `en` until translated versions are added with `POST /v1/templates`): `0002` has the original v1
set, `0003` adds a template for every rule above plus `booking.reminder` (for `notification.requested` until bookings
publishes a reminder event) and `stock.back_in_stock`, each as the next version of its key so it lands on top of any
staff-created version. `order.confirmed` and `shipment.out_for_delivery` get a new version because the real payloads
carry no first name, formatted total or handover code: the ingest derives `firstName` (identity, else "there"),
`total`/`amount` (minor units + currency), `itemCount`, `estimatedDelivery`, `trackingLine`, `refundNote`,
`itemsText`, `deadline` and links (`orderUrl`, `portalUrl`, `actionUrl`, `productUrl`) from `links.*` config. `programName` / `portalUrl` come from the organisation kind (`domain.OrgKind`, S215): `PROVIDER` → Provider Hub; `SELLER` and `BOTH` (sells products and services) → Seller Center — for seller applications (`kind`) and organisation invitations (`orgKind`). `0005`
adds the S230 trust-ops templates (claims, moderation, suspension), `org.invitation`, `identity.verification.phone`,
the suspension / reinstatement variants of the seller enforcement templates, `booking.balance_unpaid` and
`payment.authentication_required`, `0006` the claim reminder / lapse templates, and `0008` the supplier
`retail.purchase_order.sent` email (S243); for `0005` the ingest derives `claimType`, `amount`, `dueDate`, `outcomeText`,
`sellerOutcomeText`, `reasonText`, `balance`, `reference`, `claimsUrl` and `actionUrl`, and turns JSON nulls in
optional fields (`note`, `inviterName`, `orderNumber`) into empty strings or defaults.

## Configuration

`config/{local,dev,uat,prod}.yaml` with env overrides (`APP_ENV` selects the file). `kafka.topics`, `identity.*` (base URL, token URL, client id, timeout, cache TTLs), `links.*`
(storefront, seller center, provider hub) and `attachments.*` (enabled, `mediaBaseUrl`, `allowedHosts`, `maxBytes`,
`maxTotalBytes`, `timeout`; local allows MinIO `localhost:9000`, other environments media's public host and private
bucket host) are per environment. Routing is an ordered
provider list per market (`default` is the fallback), e.g. `routing.email.us: [resend, sendgrid, ses]`,
`routing.sms.us: [twilio, aws, vonage]`. A provider whose credentials are missing is skipped with a warning.

Secrets (env only): `DB_URL`, `RESEND_API_KEY`, `SENDGRID_API_KEY`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`,
`VONAGE_API_KEY`, `VONAGE_API_SECRET`, `FCM_SERVICE_ACCOUNT_JSON`, `APNS_KEY_P8`, `WHATSAPP_TOKEN`,
`RESEND_WEBHOOK_SECRET`, `SENDGRID_WEBHOOK_PUBLIC_KEY`, `UNSUBSCRIBE_SIGNING_KEY`, `SMTP_PASSWORD`,
`NOTIFICATIONS_CLIENT_SECRET` (identity client; local defaults to identity's local secret; when missing elsewhere
identity lookups are disabled with an error log rather than stopping OTP delivery). AWS (SES, End User
Messaging) uses the default credential chain / workload identity. Other env overrides: `PUBLIC_BASE_URL`, `MEDIA_BASE_URL`, `ATTACHMENTS_ENABLED`, `ATTACHMENTS_ALLOWED_HOSTS`
(comma-separated), `ATTACHMENTS_MAX_BYTES`, `ATTACHMENTS_MAX_TOTAL_BYTES`, `ATTACHMENTS_TIMEOUT`,
`KAFKA_ENABLED`, `KAFKA_BROKERS`, `IDENTITY_ENABLED`, `IDENTITY_URL`, `AUTH_TOKEN_URL`, `DISPATCHER_*`, `BREAKER_*`, `QUIET_HOURS_START/END`, `SEND_TIMEOUT`.

Shared platform settings (injected per environment by the Helm library chart, see
`docs/engineering/environments.md`): `PUBLIC_WEB_URL` and `SELLER_CENTER_URL` (email/SMS/push links; Provider Hub
links default to Seller Center, override with `PROVIDER_HUB_URL`), `API_BASE_URL` (`PUBLIC_BASE_URL` defaults to
`<API_BASE_URL>/notifications`), `MEDIA_PUBLIC_URL` (its host is always an allowed attachment host) and
`AUTH_ISSUER_URL`.

Local: email → SMTP to Mailpit (`localhost:1025`, UI http://localhost:8025), SMS/push/WhatsApp → log provider,
auth off (local super admin), webhook signatures not checked, test-send goes to `testSend.localEmail/localPhone`.

## Run and test

```bash
make up                                        # repo root: Postgres, Kafka, Mailpit …
cd go/services/notifications
GOTOOLCHAIN=go1.27.2 GOWORK=off go run ./cmd/notifications       # http://localhost:8208/docs
GOTOOLCHAIN=go1.27.2 GOWORK=off go vet ./... && GOWORK=off go test ./...   # Docker needed for the Postgres test
GOWORK=off golangci-lint run --config ../../.golangci.yml ./...
```

The Postgres integration test (testcontainers) uses `imresamu/postgis:17-3.5.4-alpine3.22` (override with
`NOTIFICATIONS_TEST_PG_IMAGE`; skip with `-short` or `SKIP_DOCKER_TESTS=1`). It covers migrations, seeded templates
and versioning, idempotent submit, inbox de-duplication, `SKIP LOCKED` claiming across workers, retry with
backoff → dead letter → replay, TCPA consent with STOP/START, quiet hours, OTP bypass, receipts that never move
status backwards, suppression, one-click unsubscribe, every mapped producer event rendered with the seeded templates,
stock alerts (subscribe once, claim once per restock, end-to-end send) and retail's PO request (rendered to the
supplier without identity, attachment reference stored, PDF attached by the dispatcher). Attachment tests use fakes
(in-memory fetcher, fake HTTP transport for `mediafetch`) and never touch the network.

### Template gallery

`make template-gallery` (repo root; Docker needed, or `ARGS=--dsn=postgres://…` for a database it may migrate) writes
`build/template-gallery/index.html` (gitignored): one self-contained page with the latest version of every seeded key,
locale and channel. `cmd/template-gallery` runs the real migrations against a throwaway Testcontainers Postgres
(`imresamu/postgis:17-3.5.4-alpine3.22`, `--image` or `NOTIFICATIONS_TEST_PG_IMAGE` to override), renders with the real `Renderer`
and takes sample data from `app.NewTemplateSamples`: the contract-shaped producer fixtures in
`internal/app/samples_fixtures.go` (`SampleEvents`, `SampleRequests`) run through the real ingest, so each template
gets exactly the fields its rule's `Enrich` derives, plus `SampleData` for keys no event maps to (`booking.reminder`,
`payout.requirement_due`). Emails show subject, sandboxed HTML (desktop / mobile width) and text part; SMS a phone bubble
with characters, GSM-7 / UCS-2 encoding and segment count (`domain.CountSmsSegments`); push a notification card;
WhatsApp a chat bubble; plus the sample data and the triggering event types from `rules.go`. A template that fails to
render is listed at the top with its error. `--out` changes the path, `--repo` points at the monorepo when run from
elsewhere (tokens and Figtree are inlined from `web/packages/tokens`).

`TestEverySeededTemplateRendersWithSampleData` (unit, parses the seed migrations) and the Postgres integration test
(real migrations) render every seeded template with its sample data, so a template edit that needs a field the event
does not carry fails CI. A new event rule needs a fixture in `SampleEvents`; a key sent only through
`notification.requested` needs an entry in `SampleData`. `GET /v1/templates/{key}/sample` serves the same data to the
staff console's template editor.

Container: `docker build -f services/notifications/Dockerfile go`. Helm: `deploy/helm` on the `at-service` library
chart.
