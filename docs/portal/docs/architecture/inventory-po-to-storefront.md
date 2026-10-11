---
title: "Inventory: from purchase order to the storefront"
sidebar_label: "Inventory: PO to storefront"
sidebar_position: 2
description: How stock is announced, received, counted, kept by batch and expiry, and made sellable on the storefront.
---

This page follows one case of goods from a purchase order to a product page. It covers both AfroTransact Retail (1P),
which buys from suppliers, and sellers who send stock to AfroTransact fulfilment centres (FBA). It answers four
questions: how stock data comes in, how we record what a shipment holds, how counts, lots and expiry dates are kept,
and how the stock reaches the storefront. Story S281.

## The pipeline at a glance

| # | Stage | Owner (service) | Screen | Done when |
|---|---|---|---|---|
| 1 | Item and supplier | retail | Staff console › AfroTransact Retail › Products, Suppliers | Item has a GTIN, case pack and shelf life, and is linked to a catalog variant ATIN. Supplier is approved. |
| 2 | Purchase order | retail | Retail › Purchase orders | Submitted → approved → sent (PDF and EDI 850) → confirmed by the supplier |
| 3 | Inbound shipment (ASN) | retail → inventory | Retail › PO sheet › shipment; Inventory › Inbound | Lines carry lot, made-on and expiry dates. From DEPARTED onwards the units show as inbound ("coming soon") |
| 4 | Receiving | retail (1P) / inventory (FBA) | Inventory › Receiving | Counted by scan or by hand. Damaged units go to quarantine. Over-receipt above 2 % is approved |
| 5 | Lots and positions | inventory | Inventory › Stock, Expiry, Movements | Each receipt line becomes a lot with its batch and dates; positions show available units |
| 6 | Offer | offers | (automatic) | The 1P offer sells the retail SKU's stock. Listing the item LIVE repoints it |
| 7 | Product page | offers + storefront | Storefront PDP | Buy box shows the quantity and the "Fulfilled by AfroTransact" badge |
| 8 | Search | indexer | Storefront search | The card shows the buy-box quantity and is in stock |

The staff console's **Inventory hub** opens on this pipeline (`GET /v1/staff/inventory/flow`). It shows a count per
stage with a link to the screen that moves it forward. **Trace a SKU** (`GET /v1/staff/inventory/trace?sku=`) checks
every link for one item, from the retail item and its inbound shipments to its lots, positions, offer, PDP and
search. It marks the first broken link and says why.

## 1. How data comes in

- **Retail (1P).** A buyer creates the purchase order from retail items. Approval follows the module levels: the
  buyer works at Edit, finance approves at Approve. The PO is sent as a PDF and an EDI 850, and the supplier confirms
  quantity, price and ready date. Booking the shipment creates the ASN. `retail.shipment.stage-changed` events then
  carry it through cargo ready, picked up, export cleared, departed, arrived at port, customs cleared and delivered
  to the FC.
- **Sellers (FBA).** In Seller Center, **Inventory › Send to AfroTransact**, the seller creates an inbound shipment
  for their own organisation. Every list is forced to the active organisation, and `ownerOrg` is never taken from the
  body. The seller adds carrier and tracking, prints labels and marks the shipment shipped.
- **Staff.** Inventory › Inbound › **New inbound shipment** covers anything else, such as a transfer or a 1P
  replenishment without a PO.

## 2. Documenting what a shipment holds

Every inbound line (`POST /v1/inbound/shipments`) holds:

| Field | Rule |
|---|---|
| `atin` / `sku` | The variant, and the inventory SKU the stock is kept under: the retail SKU for 1P, or the seller's AT-FN label for FBA |
| `qty`, `casePack` | Units in whole cases (`qty % casePack == 0`) |
| `lotNo` | Batch code: up to 59 characters, starting with a letter or digit, then `A-Z 0-9 . _ / -`; never ending in `-DMG` (reserved for damaged stock) |
| `mfgDate` | Not in the future |
| `expDate` | **Required for perishables and for items with a shelf life**. Must be after `mfgDate` and not already past |

Retail shipments also track customs and shipping documents with the states MISSING, PENDING, OK and HOLD. A MISSING
document blocks departure. A HOLD blocks customs clearance.

**Labels** come from the shipment sheet as print-ready SVG, or as PDF from `GET .../labels`:

- **Pallet:** SSCC (AI 00) as GS1-128, using GS1 company prefix 0860012 with a mod-10 check digit.
- **Case:** GS1-128 with (01) GTIN-14, (17) expiry, (11) made on, (10) lot and (30) count. FNC1 follows each
  variable-length AI.
- **Unit:** EAN-13, or the AT-FN Code 128 label for FBA.

The encoder lives in `@afrotransact/ui/barcode` (Code 128 sets B and C, GS1-128, EAN-13). It has unit tests against
known check digits.

## 3. Receiving, counting, batches and expiry

- **Receiving** (Inventory › Receiving, or the dock link on a PO). Scanning a box LPN adds its contents. Scanning a
  GS1-128 case adds one case and fills in the lot, expiry and made-on dates from the barcode. Lines default to the
  batch announced on the ASN. Good and damaged units are counted separately, and damaged units become a `-DMG` lot in
  QUARANTINE. The variance is shown live: within 2 % over is accepted, and above that someone at the Approve level
  must approve the over-receipt. A shortage raises a claim. Completing the receipt posts one lot per batch with its
  landed cost (`POST /v1/receipts` with `inboundRef` = the shipment). Those units stop counting as inbound.
- **Cycle counts** (Inventory › Counts, `/v1/cycle-counts`). Start a count for an FC, optionally narrowed to one SKU,
  to snapshot up to 500 lots. Enter the counted units. Every variance needs a reason code: FOUND, LOST, DAMAGED,
  MISPICK, RECEIVING_ERROR, THEFT, DATA_ENTRY or OTHER. **Post** writes one audited adjustment per line, with the
  idempotency key `cycle-count-<id>-<line>`. A count never goes below the reserved units.
- **Lots** (Inventory › Stock, then the lot sheet) show the batch, dates, receipt, movements trace and the same batch
  in other FCs. From the sheet you can quarantine, release, transfer, count or write off; write-off needs the Approve
  level.
- **Expiry** (Inventory › Expiry) lists lots expiring soon, suggests markdowns (15 % off within 60 days of expiry, 30 % within 30 days),
  runs markdowns and writes off expired lots.
- **FEFO.** Picking takes sellable lots with available units, earliest expiry first. Lots without an expiry come
  last, ties go to the earliest received, then by lot number. The stock table shows each lot's pick order within
  its SKU and FC.

## 4. How stock reaches the storefront

```text
retail item LIVE ──retail.item.listing-changed──▶ offers: 1P offer sells the retail SKU, at the retail price
lots received ──inventory.availability.changed──▶ offers: buy box recomputed (winner + availableQty)
offers ──offers.buybox.changed (at.offers.events)──▶ indexer: "<atin>|BUYBOX" quantity, authoritative per variant
PDP ◀── offers public buy box (quantity, "Fulfilled by AfroTransact", delivery promise)
search card ◀── indexer document (available_qty, in_stock)
```

Two links were broken and are now fixed (S281):

1. Retail lots are stocked under the **retail SKU**, but the 1P offer sold the variant ATIN's SKU, so received stock
   never reached the PDP. Offers now consumes `retail.item.listing-changed`. When an item goes LIVE, the open
   AfroTransact offer's `inventorySku` becomes the item's SKU and its price becomes the item's price. PAUSED pauses
   the offer.
2. The indexer keyed availability by variant ATIN, so stock under a retail SKU or an FBA FNSKU never reached search.
   Offers now treats the winner's quantity as part of the buy-box headline, so a change in it republishes
   `offers.buybox.changed`. The indexer stores that quantity as the variant's sellable quantity, and when it is
   present it replaces the per-node entries.

## Endpoints

| Area | BFF (staff console) | Upstream |
|---|---|---|
| Pipeline and trace | `GET /v1/staff/inventory/flow`, `GET /v1/staff/inventory/trace` | inventory, retail, offers, search |
| Inbound, labels | `/v1/staff/inventory/inbound[/{id}[/ship\|/tracking\|/receipts\|/close\|/cancel\|/labels]]` | inventory `/v1/inbound/shipments` |
| Lots, positions, movements | `/v1/staff/inventory/lots[/{id}[/trace\|/quarantine\|/release\|/adjust\|/transfer\|/write-off]]`, `/positions`, `/movements` | inventory |
| Expiry | `/v1/staff/inventory/lots/markdown`, `/lots/markdown-runs`, `/lots/expiry-runs` | inventory |
| Cycle counts | `/v1/staff/inventory/cycle-counts[/{id}[/counts\|/post\|/cancel]]` | inventory `/v1/cycle-counts` |
| Retail | `/v1/staff/retail/{items,suppliers,purchase-orders,shipments,receipts,invoices}/…` | retail |
| Seller FBA | `/v1/seller/fba/{inbound,lots,…}` (active org only) | inventory |

Module levels: View reads, Edit creates and receives, Approve approves over-receipts, write-offs, POs and suppliers.
The BFF checks them, and every upstream checks its own permissions again.

## Verified on the local stack

Supplier SUP-ACC was approved. Item AT-COC-200100 (GTIN 860012001002) was linked to the Coconut Candy variant, and
PO-1P-323 for 96 units was approved, sent (PDF and EDI 850) and confirmed. Shipment SHP-2610-GH01 carried lot CC2610A,
expiring 2027-09-20, with its documents OK. At the dock, 95 good and 3 damaged units were counted (over by 2): the
over-receipt was approved and the receipt completed. That posted lot CC2610A with 95 units SELLABLE, and lot
CC2610A-DMG with 3 units in QUARANTINE. The item was then listed LIVE. The trace is DONE end to end: the PDP buy box
shows 95 available with the Fulfilled by AfroTransact and Sold by AfroTransact badges, and search shows 95 in stock.
