# Delivery runs: attribution, analytics and WhatsApp (S262, S263)

How a visit, a sign-up or an order is traced to the link that brought it, what product analytics can consume, and what
WhatsApp needs before it is switched on.

## What is recorded

| Fact | Where it is kept | How it gets there |
|---|---|---|
| A visit to a town page or run link that carried `utm_*` tags | `run_attribution_stat` in delivery-runs: one counter per location, day, source, medium and campaign (and run) | The storefront sends `POST /v1/delivery-runs/locations/{slug}/visits` once per browser session and tag set (best effort). Only the three tags and the run viewed are sent: no cookie, IP address, user agent or visitor id. At most 100 different tag sets per location and day; the rest count as `other`. |
| A sign-up that came from such a link | the same counters (sign-ups), plus the consent record's `source` (for example `location-page.whatsapp`) | The guest or signed-in sign-up carries `utmSource`, `utmMedium`, `utmCampaign`; a guest sign-up counts once the email is confirmed. |
| An order that came through a run advertorial | `order_attribution` in delivery-runs, joined to the run's order slot | The advertorial button links to the shop with `src=page&cr=adv-<id>`; checkout keeps the pair on the order (`attribution`), orders-service publishes it in `orders.order.placed`, delivery-runs credits the advertorial. An order that later loses its slot (cancelled, released) stops counting. |

Staff read all of it in **Pre-orders -> Delivery runs**: "Visits and sign-ups by source" for a town and for the run picked in
its history, and the orders credited to each advertorial in its results
(`GET /v1/staff/delivery-runs/locations/{id}/attribution`, `GET /v1/staff/advertorials`).

## Product analytics

The repository has no analytics sink, so nothing is pushed anywhere. A product-analytics or data-platform consumer can build
the same picture from the event stream without new producers:

- **Orders**: `orders.order.placed` carries `attribution` (`src`, `cr`, `po`). `cr=adv-<id>` names the advertorial; join it with
  `delivery-runs.run.*` for the run.
- **Sign-ups**: `delivery-runs.subscription.created|confirmed|cancelled` carry `source`, which holds the UTM source after the dot
  (`location-page.whatsapp`). The full tag set is in the counters, readable through the staff endpoint above.
- **Run economics**: `delivery-runs.run.confirmed|cancelled|completed` with the orders on the run.

Visit counters are aggregates by design (no visitor id), so a consumer cannot join a visit to a person. If product analytics
later needs visit-level events, add an event to the visit endpoint behind the same consent rules as the rest of the storefront
analytics rather than extending the counters.

## WhatsApp

WhatsApp stays behind `DELIVERY_RUNS_WHATSAPP_ENABLED` (off). With it off, consent for the WhatsApp channel and the community
invite link on the town page work; no WhatsApp message is sent. Before switching it on in an environment:

1. **A Meta Business account** verified for the business, a WhatsApp Business Account and a phone number registered for the
   Cloud API (not shared with the consumer app).
2. **Approved message templates.** Every outbound message to a shopper is a template Meta must approve: run opened, cutoff
   reminder, run confirmed or cancelled, out for delivery, handover code. Utility templates (order and delivery updates) are the
   realistic category; do not plan on marketing templates for +1 numbers, which Meta has restricted. Each template needs its
   name, language, body with numbered variables and sample values at submission, and review takes from minutes to days.
3. **Opt-in on record.** Meta requires explicit opt-in for the number; the per-channel consent in delivery-runs
   (`consent_log`, source and time) is that record, and a shopper needs a verified phone number on the account.
4. **Operations**: the Cloud API token and phone-number id as environment secrets (see `docs/engineering/secrets-and-configuration.md`),
   webhook for delivery status and opt-outs ("STOP" must withdraw the WhatsApp consent), and the template names mapped in
   notifications.
5. **Community posts are manual.** Meta has no API for posting to a WhatsApp Community or group; staff post to the community by
   hand and the town page carries the invite link (`whatsappInviteUrl`, editable in Pre-orders -> Delivery runs -> Edit location).

Until every item is done the flag stays off. The staff and shopper screens work the same either way.
