---
title: "How orders and shipments work"
sidebar_label: "Orders and shipments"
sidebar_position: 3
description: The order lifecycle from checkout to delivery and returns, and who does what in which screen.
---

This guide is for operators. It follows one order from checkout to delivery, and then to a return or a claim. It
names the screen where each step happens and who does it. Stock and purchase orders, which come before this, are
covered in [Inventory: from purchase order to the storefront](./inventory-po-to-storefront.md).

## The short version

An **order** is what the customer paid for. It is split into **shipments** (packages): one per seller and fulfilment
type, and one for each pre-order window. Each shipment moves on its own. The fulfilment centre picks and packs
AfroTransact-fulfilled packages, and sellers pack their own. A carrier or an AfroTransact driver then delivers each
package. The card is authorised at checkout, but money is only captured for a shipment once it leaves the building.
The order's status is worked out from its shipments.

## Lifecycle

| Shipment status | Meaning | Who moves it | Where |
|---|---|---|---|
| `pending_payment` | Checkout started, waiting for the card authorisation (15 minutes, then everything is released) | The system | — |
| `placed` | Paid (authorised); stock is reserved | The system | Staff › Orders; Seller Center › Orders |
| `confirmed` | Picking started (FC), or the seller accepted it | FC staff; the seller for seller-fulfilled packages | FC pick/pack queue; Staff › Orders; Seller Center › Orders |
| `packed` | In a box. Carrier packages get their label and tracking number now | FC staff; the seller | Same |
| `ready` | At the outbound dock or the seller's counter, waiting for collection | FC staff; the seller | Same |
| `picked_up` | Collected by the carrier or the driver. **The payment for this shipment is captured here** | Carrier webhook; the driver app; ops staff for AfroTransact-fulfilled packages | Staff › Orders (shipment actions) |
| `in_transit` / `out_for_delivery` | On its way | Carrier webhook; the driver app | Staff › Orders; Staff › Delivery dispatch |
| `delivered` | Handed over (photo and handover method for AfroTransact Delivery). The seller's 7-day settlement hold starts | Carrier; driver; staff can confirm | Same |
| `delivery_failed` | Attempt failed (customer unavailable, unsafe…); re-attempted or returned | Driver; carrier | Delivery dispatch |
| `cancelled` | The customer cancelled before packing and inside the cancellation window (30 minutes by default), or staff cancelled. Uncaptured money is released; captured money is refunded | Customer; staff | Storefront › Your orders; Staff › Orders |
| `returned` | Came back to the seller or the FC | Staff | Staff › Orders |

**Order status**, derived from its shipments: `placed` (nothing has moved yet) → `in_progress` (at least one
shipment is moving) → `completed` (every live shipment was delivered or returned), or `cancelled` (every shipment
was cancelled).

The state machine sets **who may move a shipment**, not the UI. For seller-fulfilled packages, only the seller
confirms, packs and marks ready. Platform staff can confirm delivery, record a failed delivery, process a return or
cancel, but they never pack. For AfroTransact-fulfilled packages (FBA and AfroTransact Retail), ops staff and the FC
own every step. Carriers and drivers only move a shipment forward.

## Who does what

| Step | Who | Screen |
|---|---|---|
| Checkout, track, cancel before packing, start a return | Customer | Storefront › Your orders |
| Pick, pack, label, stage at the dock | FC staff | The FC pick/pack queue (fulfilment-service `/v1/fc/tasks`, for the FC's handhelds; there is no console screen for it yet). Ops staff can also move these shipments in Staff › Orders: open the order and tap its next step (Mark packed, Mark ready) |
| Confirm, pack, print the label, mark ready (seller-fulfilled) | Seller | Seller Center › Orders |
| Plan routes, assign driver and vehicle, handle SOS alerts and vehicle defects, route seller pickups | Dispatcher | Staff › Delivery dispatch |
| Pre-trip inspection, load-out scans, deliver with a photo (and a 4-digit code when required), collect return pickups | Driver | Driver app |
| Find any order, move AfroTransact-fulfilled shipments, cancel, confirm delivery | Ops / support | Staff › Orders |
| Approve and inspect returns, decide A-to-z claims | Trust & support | Staff › Disputes & returns |
| Approve seller payout requests | Finance | Staff › Payouts |

## Delivery methods

- **Carrier** (UPS, USPS, FedEx, DHL): the label is bought when the package is packed. carrier-gateway's tracking
  webhooks then move it through picked up, in transit and delivered.
- **AfroTransact Delivery** (local, e.g. Houston): the package waits in the dispatch pool until a dispatcher puts it
  on a route, either one built per delivery zone or one made by hand. The driver inspects the van, scans the
  packages in at load-out, starts the route and delivers stop by stop. A failed vehicle check (a **defect**) blocks
  the route until dispatch resolves it, and an **SOS** goes straight to the dispatch screen.
  - **Seller pickups**: an AfroTransact Delivery offer that ships from the seller's own store or kitchen. The driver
    collects it from the seller's pickup location on the way, and it shows under Delivery dispatch › Seller pickups.
- **Pre-order windows** (e.g. Lubbock): the package is held until the window closes and is delivered on the window's
  delivery day, to the door or at the pickup point. Staff › Pre-orders.

## Codes and proof of delivery

Every pickup order and every hand-to-customer or perishable driver delivery has a 4-digit code that the customer sees on
their order page. Whoever hands the order over asks for it: the seller or the counter for a pickup, the driver at the door.
Our drivers also take a photo, and a signature or a safe-place note, and the customer, the seller and staff can see them on
the order afterwards. [Shipment scenarios](./shipment-scenarios.md) lists every path and what it records.

## Money

The card is authorised at checkout (manual capture). Each shipment's lines, with their share of shipping and tax, are
captured when it is picked up. Cancelling an uncaptured shipment releases its authorisation. A captured one is
refunded. After delivery, the seller's share is held for 7 days, then transferred to their Stripe account, and the
seller can request a payout, which Finance approves.

## Returns and claims

- **Return** (RMA): the customer starts it from Your orders, within the return window. Some reasons approve
  themselves; the rest wait for staff (or the seller, for seller-fulfilled packages). A drop-off return gets a QR
  label from the carrier. A driver-pickup return is collected on a route. The return centre marks it received and
  inspects each line, and every line that passes is refunded.
- **A-to-z claim**: the customer's guarantee when something went wrong (not received, damaged, wrong item). The
  seller has a response window. Trust & Safety then refunds or denies.

## Trying it locally

`make up` seeds an operations dataset when it is missing. You can also run `make seed ARGS="--only ops"`. It puts
orders in every state above, placed by `customer.houston@afrotransact.test` and `customer.lubbock@afrotransact.test`.
It also adds routes for `driver@afrotransact.test` (one in progress, plus an open SOS), a van with a defect, a seller
pickup waiting for a route, returns, an A-to-z claim and a payout request.

Locally there is usually no Stripe key, so payments use a stand-in that is only allowed on the local profile. Every
card is authorised the way Stripe's test card 4242 4242 4242 4242 is, and no money moves. Put `STRIPE_SECRET_KEY`
in `.local/secrets.env` to use Stripe test mode instead. Drop-off return labels and carrier tracking need
carrier-gateway with carrier sandbox credentials, so locally those parcels stay at "approved" or "in transit" until
staff move them.
