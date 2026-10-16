---
title: "Shipment scenarios: who moves what"
sidebar_label: "Shipment scenarios"
sidebar_position: 4
description: Every way a shipment can move, by who fulfils it and how it reaches the customer, with the proof each path leaves behind and the gaps still open.
---

This page is the working reference for shipment status changes. It follows the rules enforced in orders-service
(`DefaultShipmentTransitionPolicy`, `DefaultCourierProofPolicy`), not the screens. A screen that offers a step the
rules refuse is a bug in the screen. For the narrative version, read
[How orders and shipments work](./orders-and-shipments.md).

## The two questions that decide everything

1. **Who fulfils it?** The seller (`SELLER_FULFILLED`), or AfroTransact (`FBA_AFROTRANSACT` for a seller's stock in our
   centre, `RETAIL_1P` for our own stock). Pre-orders (`PREORDER`) are AfroTransact-fulfilled in batches.
2. **How does it reach the customer?** `CARRIER` (USPS, UPS, FedEx, DHL with a label), `AFROTRANSACT_DELIVERY` (our
   driver), or `PICKUP` (the customer collects).

Fulfilment decides who prepares the parcel. Delivery method decides who moves it after that.

## Preparing the parcel (placed → ready)

| Step | Seller-fulfilled | AfroTransact-fulfilled (FBA, Retail) |
|---|---|---|
| `placed` → `confirmed` | The seller | Fulfilment ops or a platform admin |
| `confirmed` → `packed` | The seller | Fulfilment ops or a platform admin |
| `packed` → `ready` | The seller | Fulfilment ops or a platform admin |
| Cancel before pickup | The customer (until packed); a platform admin | The customer (until packed); a platform admin |

A platform admin never packs a seller's parcel. Staff can only step in on a seller's shipment once it has left.

## Moving it (ready → delivered), by delivery method

### Carrier (label)

| Step | Seller-fulfilled | AfroTransact-fulfilled |
|---|---|---|
| `ready` → `picked_up` ("Mark shipped") | The seller, and only once a label or tracking number exists | Fulfilment ops or a platform admin |
| `in_transit`, `out_for_delivery`, `delivered`, `delivery_failed`, `returned` | Carrier tracking events (webhooks), forward only | Same |
| Manual fix | A platform admin: delivered, delivery failed, returned, cancelled | A platform admin or ops: any step |

Money for the shipment is captured at `picked_up`.

### AfroTransact Delivery (our driver)

| Step | Who | Needs |
|---|---|---|
| `ready` → `picked_up` | The driver, at the seller's store or the dock | The label scan (`ATS` + 9 characters) |
| `in_transit`, `out_for_delivery` | The driver app | Nothing extra |
| `delivered` | The driver app | A live photo within 150 m of the address, and one of: the customer's 4-digit code, someone else's name, relationship and signature, or a safe place (never for perishables or hand-delivery orders) |
| `delivery_failed` | The driver app | A reason |
| Manual fix | A platform admin (seller-fulfilled) or ops (AfroTransact-fulfilled) | A reason (see decisions below) |

### Pickup (the customer collects)

| Step | Seller-fulfilled | AfroTransact-fulfilled |
|---|---|---|
| `ready` | The seller, or the pickup point's "ready" notice | Ops, or the pickup point's notice |
| `delivered` | The seller hands it over at its own shop | Ops at the counter |

## Cancelling

| Who | When | Where |
|---|---|---|
| The customer | Only before the parcel is packed (`placed` or `confirmed`) **and** inside the cancellation window: 30 minutes after placing by default (`ORDERS_CANCELLATION_WINDOW`), or until a pre-order's window closes | Orders → Cancel |
| A platform admin | Any shipment that has not been picked up (`placed` to `ready`), with a reason | Staff › Orders › Update a shipment |
| The seller | Never. A seller who cannot ship asks support | — |

The Cancel button is shown only while the cancel would be accepted. After the window, or once the seller packs, it is gone and
the customer is pointed at returns instead.

## Carrier webhooks: what has to be true when we really ship

| Step | What happens | If it is missing |
|---|---|---|
| The seller buys a label | carrier-gateway stores the label, subscribes the tracking number (UPS Track Alert) and orders-service is given the carrier and number | Events cannot be matched to the shipment |
| The carrier pushes an event | `POST /v1/webhooks/{carrier}` is verified (UPS credential header, FedEx and DHL HMAC), parsed and stored once | A bad signature is refused with 401 |
| carrier-gateway publishes it | orders-service moves the shipment forward only (an older event is ignored) | — |
| A push never arrives | A slow poll (every 2 hours in dev, UAT and prod) asks UPS, FedEx, DHL and USPS for each active tracking number | The shipment would otherwise stay stuck |

A carrier event can move a shipment from `confirmed`, `packed` or `ready` straight to `in_transit`, so the seller forgetting
"Mark shipped" does not hold the money capture or the customer's tracking back.

To switch it on for an environment:

1. Set `PUBLIC_BASE_URL` (or the shared `API_BASE_URL`) to an address the carriers can reach; UPS subscribes to
   `<base>/v1/webhooks/ups`.
2. Set the secrets: `UPS_WEBHOOK_CREDENTIAL`, `FEDEX_WEBHOOK_SECRET`, `DHL_WEBHOOK_SECRET`. In the FedEx and DHL consoles, point
   the webhook project at `<base>/v1/webhooks/fedex` and `<base>/v1/webhooks/dhl`.
3. Check one real label: it should show `in_transit` in Staff › Orders within minutes of the carrier's first scan.

Locally, the test carrier has no push; the 1-minute poll moves its labels to delivered within 15 minutes.

## What proof each path leaves

| Path | Proof recorded today | Shown to anyone today |
|---|---|---|
| Carrier delivery | The carrier's tracking event | Tracking number and status |
| AfroTransact Delivery by the driver app | Photo, handover method, code check, recipient and signature, safe place, GPS | **No one.** It is stored on the status history and never returned |
| Manual "delivered" by staff | The reason, the actor and the time | The activity line |
| Pickup handover | Actor and time | The activity line |

This is the gap behind "FBA has no delivery proof". The driver app collects proof for FBA and seller parcels alike,
and orders-service stores it, but no API returns it. Fixing that comes first.

## Decisions

1. **Show the proof.** Orders-service returns a delivery proof (handover method, who received it, where it was left,
   whether a photo and a signature exist, and when) on the shipment's timeline entry. Staff see it on the order
   record. Showing the photo itself needs a signed media link and comes next.
2. **A manual "delivered" on a driver shipment needs a reason.** A platform admin can still confirm a delivery the
   driver could not record (a dead phone, a lost signal), but must say why, and the entry is marked as having no
   proof.
3. **Staff pick a step for the shipment in front of them.** The status form lists the next steps of the one
   shipment chosen, named as actions ("Mark packed", "Hand to the carrier"), not as raw statuses.
4. **Out-of-order events are ignored, not applied.** A late `in_transit` after `delivered` changes nothing.

## Still open

- Pickup handovers do not yet check the customer's code the way a driver delivery does.
- The driver app's batch scan at the dock sets `picked_up` one shipment at a time. A batch call would be faster.
- Carrier proof of delivery (signature, photo) is not fetched from the carrier.
- A return's inbound scan and refund steps are in [Disputes and returns](./orders-and-shipments.md).
