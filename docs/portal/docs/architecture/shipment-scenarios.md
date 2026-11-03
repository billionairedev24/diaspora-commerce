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
| `delivered` | The seller hands it over at its own shop, with the customer's 4-digit pickup code | Ops at the counter, with the code |

Staff can confirm a pickup handover without the code by giving a reason.

## Cancelling

| Who | When | Where |
|---|---|---|
| The customer | Only before the parcel is packed (`placed` or `confirmed`) **and** inside the cancellation window: 30 minutes after placing by default (`ORDERS_CANCELLATION_WINDOW`), or until a pre-order's window closes | Orders → Cancel |
| A platform admin | Any shipment that has not been picked up (`placed` to `ready`), with a reason | Staff › Orders › More options |
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

| Path | Proof recorded | Shown to |
|---|---|---|
| Carrier delivery | The carrier's tracking event | Everyone, as tracking and status |
| AfroTransact Delivery by the driver app | Photo, handover method, code check, recipient and signature, safe place, GPS | The customer, the seller and staff: how it was handed over, to whom, the door photo and the signature. Staff also see the driver's position |
| Manual "delivered" by staff on a driver shipment | The reason, the actor and the time | The activity line; there is no photo |
| Pickup handover | The customer's code was checked (seller) or a reason was given (staff) | The activity line |

The photo and the signature are private media. Orders-service returns their asset ids; the gateway turns them into
links that last about five minutes, using its own service token with `media:read` (media only lets it read
`proof-photo` assets). A page opened for longer shows the pictures broken until it is read again.

## Decisions

1. **Show the proof.** Done: the customer's order page, the seller's order sheet and the staff order sheet show it.
2. **A manual "delivered" on a driver shipment needs a reason.** A platform admin can still confirm a delivery the
   driver could not record (a dead phone, a lost signal), but must say why.
3. **Staff pick a step for the shipment in front of them.** The status form lists the next steps of each shipment,
   named as actions ("Mark packed", "Mark delivered"), not as raw statuses.
4. **Out-of-order events are ignored, not applied.** A late `in_transit` after `delivered` changes nothing.
5. **A pickup order is handed over with the customer's code.** Every new pickup shipment has a 4-digit handover code. The
   customer sees it as the pickup code; the seller enters it to hand the order over; staff may confirm without it by giving
   a reason. A seller-fulfilled pickup order could not be completed in Seller Center before this.
6. **The seller sees one next step.** The order sheet shows Accepted, Packed, Ready, Shipped and Delivered, and a single
   button with a line on what it does. "Mark as shipped" waits for a label.
7. **Cancel is offered only before packing and inside the window**, with its deadline ("You can cancel until 3:42 PM") and a
   confirmation before it refunds.

## The driver app's scans

The driver app already sends scans in batches, not one parcel at a time: finishing the load-out at the dock marks every loaded
parcel `picked_up` in one go, starting the route marks them `out_for_delivery`, and confirming a seller pickup marks that
stop's parcels `picked_up` and `out_for_delivery`. A delivery sends the proof for every parcel at the stop. If orders-service
is unreachable the fulfilment service queues the events and retries them, so a valid proof is never thrown away.

## API and tool changes

| Where | Change |
|---|---|
| orders `POST /v1/shipments/{id}/transitions` | New optional `handoverCode` (4 digits). Pickup `delivered` needs it, or a staff `reason`. 422 `orders.handover-code-required`, 409 `orders.handover-code-invalid` |
| orders `OrderView` shipment | New `cancellableUntil`; `cancellable` now also needs the cancellation window. Timeline entries carry `proof` (`DeliveryProof`: handover method, recipient, safe place, failure reason, photo and signature asset ids; GPS for staff only) |
| gateway-bff customer API | Order summary and detail shipments: `cancellableUntil`. Detail: `proof` (`DeliveryProof` with `photoUrl` and `signatureUrl`) |
| gateway-bff Seller Center API | `POST /v1/seller/orders/{n}/shipments/{id}/status` accepts `DELIVERED` with `handoverCode`; the shipment has `proof` |
| gateway-bff staff API | Each shipment's next daily step is a one-tap action (`step:<shipmentId>:<STATUS>`, no form); "More options" is one `step` choice plus `reason` and `handoverCode` for cancel, failed delivery and hand-over by code. Driver steps (picked up, out for delivery, delivered) come from the driver app. The order sheet has a Proof of delivery section |
| media `GET /v1/assets/{id}` | `gateway-bff` may read `proof-photo` assets with `media:read` |
| identity | The `gateway-bff` client gets the `media:read` scope |
| carrier-gateway | Dev, UAT and prod poll every carrier slowly as the net under webhooks |
| MCP `update_shipment_status` | Now calls the real orders routes. Steps: CONFIRMED, PACKED, READY, SHIPPED (carrier and tracking), DELIVERED (pickup orders, with `handoverCode`). New input `handoverCode`. Before this it called a route that does not exist and read fields orders does not return |
| MCP `list_my_orders` | Now `GET /v1/seller/shipments`. **Breaking:** the `page` input is replaced by `cursor` (the previous `nextCursor`) |
| MCP `track_order` | Returns `cancellable`, `cancellableUntil` and the delivered shipment's proof |

## Still open

- The photo link lasts five minutes; a long-open page needs a refresh to see it again.
- Carrier proof of delivery (signature, photo) is not fetched from the carrier.
- A refund when a bank-paid order is cancelled is logged, not made automatically.
- The customer mobile app does not show the cancel deadline or the proof of delivery yet.
- A return's inbound scan and refund steps are in [Orders and shipments](./orders-and-shipments.md).
