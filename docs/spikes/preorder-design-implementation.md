# Pre-orders: implementation spec (from `design/AfroTransact Pre-Order v2.dc.html`)

Source of truth: `design/AfroTransact Pre-Order v2.dc.html` (storefront + back office + "How it works"),
`design/at-preorder.js` (shared store, location model, attribution), `design/AfroTransact Shop.dc.html`
(ribbon, "send to family" banner, `/pre-orders` page) and `design/AfroTransact Backoffice.dc.html` +
`design/backoffice-config.js` (the **Pre-orders** module, permission matrix). Where this spec and the earlier
spike (E34, its economics and card-hold design kept in section 8 below) disagree, **the design wins**:

| Topic | Spike (E34) | Design (implemented) |
|---|---|---|
| Payment | authorise at order, capture at confirmation | **charged at checkout**, one payment for the whole cart; cancel pre-order lines for a full refund until close |
| Minimum orders | min 10, decide at cutoff | **no minimum, no extension**: we deliver on the set date however many orders came in |
| Capacity | 30 per run | none in the design (kept as an *optional* soft cap, off by default) |
| Late orders | none | customer service **late add** after close, with reason, logged and tagged `Agent (late)` |
| Delivery fee | per run | **$7.99 flat door** or **free pickup** (per window: door and/or pickup) |
| Where | one location | **targets**: one or more `country/region[/city]` keys; whole region or listed cities |
| Not covered | n/a | per-platform **fallback**: `ship` (UPS Ground, quoted) or `waitlist` (no checkout) |

The `delivery-runs` service owns pre-orders. A **pre-order window** is the shopper/marketing concept. When a window
closes, it hands its orders to a **run** (the existing dispatch aggregate) for batched pickups and the driver route.

## 1. Domain (delivery-runs, `com.afrotransact.deliveryruns`)

### PreorderWindow (aggregate, `@Version`)
- `id` (UUID), `code` (e.g. `LBK-7`, unique; prefix = first 3 letters of the first target city), `name`.
- `targets: List<AreaKey>`, where `AreaKey` = `country/region` or `country/region/city` (ISO-3166 alpha-2 country,
  subdivision code, city name; matching is case-insensitive on city).
- `opensAt`, `closesAt` (Instant, market timezone shown in UI), `deliversOn` (LocalDate).
- `ribbon` (≤ 80), `headline` (≤ 140), `smsCopy` (≤ 160, GSM-7 counted), `categories: Set<String>`
  (Pantry, Spices, Snacks, Oils, Grains & flours, Beverages; open list from catalog categories).
- `door: boolean`, `pickup: boolean` (+ `pickupPointId` optional, "10am–2pm" window), `showCount: boolean`,
  `socialBudget: Money`, `comms: Set<CommKind>` (ANNOUNCE, REMIND_48H, LAST_CALL_6H, CLOSED, OUT_FOR_DELIVERY, PAID_SOCIAL).
- `draft: boolean`, `active: boolean` (Pause/Resume).
- **Derived status** (pure function of now): `DRAFT` if draft, else `PAUSED` if !active, else `UPCOMING` < opens ≤
  `OPEN` ≤ closes < `CLOSED` < deliversOn + 1 day ≤ `DELIVERED`.
- **Publish checks** (all must pass, return each as a `RuleViolation`): name set; ≥ 1 target; closes > opens;
  deliversOn ≥ closes − 12h; hero creative uploaded; ribbon set; door or pickup.
- `creatives: Map<Slot, CreativeRef>`: slots `HERO 1920×640 (3:1, required)`, `PDP 1200×300 (4:1)`, `STORY 1080×1920`,
  `FEED 1080×1080`, `EMAIL 1200×400 (3:1)`. `CreativeRef{assetId, url, width, height}` from media-service;
  a creative whose aspect ratio is > 12 % off spec is flagged `RATIO_OFF` (warn, not block).

### Coverage (platform settings, single row, `@Version`)
- `enabled` (master switch), `fallback: SHIP | WAITLIST`,
  `placements: {ribbon, hero, badge, pdp, cart, family}` (all default on),
  `areas: List<CoverageArea{key, on}>`. A city row overrides its region row; a region row covers every city in it.
- `areaOn(country, region, city)` exactly as `at-preorder.js#areaOn`.
- `payment` (back office › Pre-orders › Settings › Payment & fees, Full access only):
  `doorFee` per currency (default USD 7.99), `pickupFee` (default 0), `pickupWindow` ("10am–2pm"),
  `allowedMethods` {CARD, WALLET (Apple Pay / Google Pay / Link), ACH}, refund rule (cancel for a full refund incl.
  the delivery fee until close), `lateAdd` {savedCard, paymentLink, linkExpiryHours = 24 (1–24: orders-service creates payment links for at
  most 24 h; delivery-runs rejects more, the console's input is capped)},
  `missedDelivery` {offerRefund, offerNewDate}. Windows may override `doorFee` / `pickupFee` and set a pickup point.
  Effective fees and methods flow through eligibility, resolve and the cart; nothing hard-codes $7.99.

### Resolution (`PreorderResolver`, domain service)
`resolve(country, region, city, now)`: windows that are not drafts and cover the place; pick the first of
`OPEN`, then `UPCOMING`, then `CLOSED` (status order); `on = window != null && enabled && active && areaOn(...)`.
Returns stage, countdown target (`closesAt` when open, `opensAt` when upcoming, `deliversOn` when closed), creatives,
live count (`showCount`, not when upcoming), and fallback when `on` is false. **Metro** cities (next-day delivery,
served by geo zones) never show pre-order. `resolve` is what the storefront calls with the shopper's delivery location.

### Orders projection (`PreorderOrder`)
Built from `orders.order.placed` / `orders.shipment.cancelled` / `orders.order.cancelled` and late adds:
`orderId, number (CODE-1001…; late adds CODE-L90…), windowId, customer name/phone/email, address line/city/region/zip,
delivery note, placedAt, channel, trackingTag, lines[{atin, name, sellerOrgId, qty}], units, fulfilment DOOR|PICKUP,
total, status PAID | LATE_ADD | CANCELLED, note`. PII here is the minimum dispatch needs; it is deleted 90 days after delivery
(retention job) and exports are audit-logged.

### Attribution
`channel` is one of `SITE_HERO, SITE_RIBBON, SITE_PDP, SITE_PAGE, SITE_DIRECT, APP, INSTAGRAM, FACEBOOK, TIKTOK,
WHATSAPP, EMAIL, SMS, AGENT_LATE`, from `?src=ig|fb|tt|wa|email|sms|app` (+ `cr=` creative, `po=` window code) or the
on-site placement clicked. Last touch wins; it is kept for the visit, and for 30 days only with marketing/analytics
consent (cookie `at_attr`). The storefront sends `attribution{src, cr, po}` with checkout; orders-service stores it
on the order and echoes it in `orders.order.placed`.

## 2. APIs

### delivery-runs (`contracts/openapi/delivery-runs.yaml`, new paths)
Staff (`delivery-runs:read` to view; `preorders:manage` to write; `preorders:export`; `preorders:late-add`):
- `GET  /v1/preorders?status=&q=&page=` → list rows (name, where, dates, status, count, revenue, areaOff).
- `POST /v1/preorders` (draft) · `GET/PUT /v1/preorders/{id}` (If-Match) · `POST /v1/preorders/{id}/publish`
  (422 with the failed checks) · `POST /v1/preorders/{id}/pause` · `POST /v1/preorders/{id}/resume`.
- `PUT  /v1/preorders/{id}/creatives/{slot}` `{assetId}` (verifies with media-service, stores w/h) · `DELETE` same.
- `GET  /v1/preorders/{id}/orders?status=&channel=&q=&page=&size=12`.
- `GET  /v1/preorders/{id}/summary` → KPIs (pre-orders, cancelled, unique customers, units, revenue incl. delivery,
  avg order, late adds), `byDay[]` (paid per day, flag after close), `byChannel[]`, `topProducts[]` (8),
  `fulfilmentSplit{door,pickup}`, `placements[]` (slot, creative present, impressions, clicks, CTR, orders).
- `GET  /v1/preorders/{id}/exports/{orders|orders-filtered|picking-list|advertorial-report}.csv` (`preorders:export`,
  filtered export takes the same query as the list; audit event `preorders.export.created`).
- `POST /v1/preorders/{id}/late-orders` (`preorders:late-add`): `{customerId, addressId | newAddress{line1, zip,
  placeId}, phoneConfirmed: true, items[{atin, qty}], payment: SAVED_CARD | PAYMENT_LINK, reason, note}` → calls
  orders-service `POST /v1/service/orders/late-preorder` (on-behalf order, window id, channel AGENT_LATE).
  Only when the window is `CLOSED` (or `OPEN`, as an assisted order); refused once `DELIVERED`.
- `GET/PUT /v1/preorder-settings` (enabled, fallback, placements) · `GET /v1/preorder-areas?country=&region=&q=&page=`
  (rows with on-count and window count) · `POST /v1/preorder-areas` `{key}` · `PATCH /v1/preorder-areas` `{keys[], on}`
  (bulk toggle a country/region) · `DELETE /v1/preorder-areas/{key}`.
- `GET /v1/places/search?q=` (country/region/city suggestions; geo-service backed, built-in list fallback).
Public (no auth):
- `GET /v1/public/preorders/resolve?country=&region=&city=` → the resolution above (no PII).
- `GET /v1/public/preorders/open` → open + upcoming windows for the `/pre-orders` page (`openList`).
Service (`delivery-runs:reserve`, orders-service):
- `GET /v1/service/preorders/eligibility?country=&region=&city=&atins=` → per-atin `{preorder: bool, windowId,
  stage, deliversOn, door, pickup}` (a product is pre-order when its category is in the window's categories and it
  is not flagged `shipsNow`).

### orders-service
- Cart/checkout: lines eligible for an `OPEN` window become a **pre-order package** (`fulfilmentType=PREORDER`,
  `windowId`, `promisedBy=deliversOn`), the rest stay normal packages. Fulfilment choice `DOOR` ($7.99, fee ledger
  account 4250) or `PICKUP` ($0). Lines for an `UPCOMING`/`CLOSED` window block checkout (`422 orders.preorder-not-open`).
- One PaymentIntent for the whole cart, captured at checkout (existing flow).
- `POST /v1/orders/{id}/shipments/{shipmentId}/cancel` by the customer is allowed for PREORDER shipments until
  `closesAt` (full refund including the $7.99); changing quantities = cancel line + re-add while open.
- `POST /v1/service/orders/late-preorder` (scope `orders:on-behalf`, delivery-runs-service only): creates the order
  for the customer, charges the saved default card off-session (`SAVED_CARD`) or creates a Stripe payment link sent by
  SMS (`PAYMENT_LINK`, order `AWAITING_PAYMENT` until paid), status tag `LATE_ADD`, actor = staff subject.
- Missed delivery date: `orders.preorder.delivery_missed` → customer chooses refund or new date (notification
  `preorder.delivery_missed` with two links).
- `attribution{src,cr,po}` stored on the order, included in `orders.order.placed`.

### catalog-service
- Product flag `preorderEligible` (default true for food categories) and `shipsNow` (always ships, e.g. shea, fabric).

### gateway-bff
- Storefront: `GET /v1/preorders/resolve` (uses the shopper's delivery location), `GET /v1/preorders/open`,
  `POST /v1/preorders/waitlist` (email + area, uses delivery-runs subscriptions); cart responses gain
  `packages[].preorder{windowId,name,deliversOn,stage,closesAt,fulfilment,options[]}`; `PUT /v1/cart/preorder-fulfilment`.
- Staff (`/v1/staff/preorders/**`) mirrors the delivery-runs staff API 1:1, plus `GET /v1/staff/customers/search`
  for the late-add customer picker (identity + addresses: identity `GET /v1/admin/users/{id}/addresses`, `users:read`)
  and CSV streaming passthrough. A late add with `addressId` goes to delivery-runs with its `address` snapshot
  (`fullName, phone, line1, line2, city, region, zip, country, instructions`): what the console sends wins, the BFF
  fills the blanks from the saved address (unknown address id → 422 on `addressId`).
- `POST /v1/preorders/waitlist/unsubscribe {token}` → delivery-runs `POST /v1/public/preorders/waitlist/unsubscribe`;
  the storefront page `/pre-orders/waitlist/unsubscribe?token=` asks once, then confirms (mail scanners that open
  links do not unsubscribe anyone).

## 3. Events (`contracts/asyncapi/fragments/delivery-runs.yaml`, topic `at.delivery-runs.events`)
`preorder.window.published|updated|paused|resumed|opened|closed|delivered`, `preorder.settings.changed`,
`preorder.comms.due {windowId, kind}` (emitted by the scheduler at: opening, closes − 48 h, closes − 6 h, closing;
OUT_FOR_DELIVERY comes from fulfilment's driver events), `preorder.late_order.added`, `preorder.export.created`.
On `closed`, the window's paid orders are attached to a run (`RunService.fromWindow`) for batched seller pickups.

## 4. Notifications (go/services/notifications, migration 0009)
Templates, all en, with sample fixtures for the gallery:
`preorder.announce` (email, push, SMS uses the window's `smsCopy`), `preorder.reminder` (email, push),
`preorder.last_call` (push, SMS), `preorder.closed` (email: closed + delivery date), `preorder.out_for_delivery`
(SMS, push), `preorder.confirmed` (email: "You're in", delivers, charged, cancel-until), `preorder.waitlist.opened`
(email), `preorder.late_order.payment_link` (SMS), `preorder.delivery_missed` (email, refund/new date links).
Audience for announce/remind/last call = area subscribers (consented channels) + past buyers in the targets.
Every link carries `?src=<channel>&po=<CODE>&cr=<slot>`.

## 5. Web
### Storefront (`web/apps/storefront`)
- **Ribbon** (under header, every page, placement `ribbon`): open = gold, `ribbon` text + "Order by … · delivered … ·
  $7.99 flat" + mono countdown chip "Closes in 3d 04h 12m 09s" + "View pre-order"; upcoming = ink-50 "Opens …";
  closed = ink-100 "Delivering …. Missed it? Call …". Sets attribution `ribbon`.
- **Send to family** banner (placement `family`): metro shopper with a saved address in a pre-order city.
- **Home hero** (placement `hero`): hero creative, kicker, headline, countdown blocks (days/hrs/min/sec), timeline
  Opens → Closes → Delivered, CTA, live count "142 pre-orders from Lubbock so far" when `showCount`.
- **`/pre-orders`** page (flat, Amazon-style, no boxes or dark bars): `PageHeader` (Fraunces h1 and a one-line lead, `preorder.page.lead`:
  "Order by {closes}. Everything is delivered together on {delivers}."), then hairline `Section`s. Window page: dates and stage
  with the inline countdown as definition rows (`preorder-timeline`), the "missed it" support block when closed (plain text and text
  links, phone), the four rules as a plain list, the window's products in the search grid (`CartAwareProductCard`, CTA "Reserve" via
  its `labels` prop; locked stages show the stage label), then "Open pre-orders in other places". Off page: fallback statement and
  waitlist form (or the ship explanation) over the list of open/upcoming windows by place (`openList`), each row with plain order-by
  and delivery dates and "Shop for <city>". Only the `inline` countdown is used on the page (the `bar` variant is gone). Customer
  copy never says AfroTransact delivers or collects ("Ships now", "Delivery").
- **Badge** on product cards/search: "Pre-order · delivered Oct 25" / "Pre-order opens Oct 6" / "Pre-order closed";
  non-pre-order items show "Ships now · UPS Ground 3–5 days" (or "Arrives tomorrow" in metro).
- **PDP**: pre-order delivery box + note (charged at checkout, cancel free until close), PDP banner creative
  (placement `pdp`), disabled add-to-cart label when not open.
- **Cart** (placement `cart`): two groups "Pre-order · delivered Sun, Oct 25" and "Ships now"; fulfilment cards
  Door $7.99 / Pickup free; summary rows; 4 pay notes; locked state when window not open; place order label.
- **Confirmation**: "You're in: Lubbock Pre-order #7", facts (Pre-order #, Delivered, Charged), body.
- **Off state**: fallback `ship` ribbon/headline/body, or `waitlist` (no checkout, waitlist form).
- Attribution capture middleware (`src/cr/po`), consent-aware cookie.
### Staff console (`web/apps/staff-console`, module **Pre-orders**, `ph-calendar-check`)
- **List**: tabs All/Open/Scheduled/Closed/Drafts with counts; table name, where, opens, closes, delivers, count,
  revenue, status pill (Open / Scheduled / Closed · delivering / Delivered / Draft / Paused / Area off);
  "Open a pre-order" (managers only); "Settings" entry; master switch.
- **Detail**: header (status pill, where, dates, time-progress bar "N days left", Pause/Resume, Edit, Add late order,
  Export menu), tabs **Overview** (6 KPIs, orders-by-day bars with post-close days in gold, by-channel bars, top 8
  products, door/pickup split), **Orders** (search by order/customer/phone/address/zip, status + channel filters,
  12 per page, address + delivery note, channel, tracking tag, status pill), **Creatives** (5 slot cards with spec,
  where, ratio check, upload/replace/remove via media upload), **Analytics** (placement performance table + report export).
- **Wizard** Details → Advertorials → Comms → Review: name, area picker (country → state/region → city or "Every city"
  with saved-area quick add), opens/closes/delivers, ribbon, headline, categories chips, door/pickup/showCount;
  creatives; comms toggles (6) + SMS copy with 160 counter (GSM-7 septets of the message as sent: `{link}` counted as
  the tracked link `<PUBLIC_WEB_URL>/pre-orders?src=sms&po=<CODE>&cr=announce`, typography made SMS-safe like
  notifications does, 70 for UCS-2) and the expanded preview + social budget; review summary + the 7 checks; Save draft;
  Publish (blocked with "N checks to fix").
- **Settings**: master switch; fallback (Ship by UPS Ground / Waitlist only); placements table (6 toggles with where
  and creative); coverage areas: add via country/region/city dropdowns (+ "City, ST" free entry), drill-down table
  country → region → city with on/off toggles, "N of M on", window counts, filter, 12 per page, remove city.
- **Late add** dialog: customer search, saved address or new address (verify with Google Places), "phone confirmed"
  checkbox, items (window products), payment (saved card / SMS payment link), reason (Customer called / Chat /
  Driver report / Other), note, total incl. $7.99; saves and jumps to Orders.
- **Roles** (`backoffice-config.js`): Super Admin & Marketing = Full (manager), Operations = Edit (manager without
  settings), Support = Act (view + late add), Finance/Auditor/Product owner/Fulfilment/Retail = View; Finance & auditor
  also export (analyst).

## 6. Identity (migration V22)
`preorders:manage`, `preorders:export`, `preorders:late-add` (+ existing `delivery-runs:read`). Grants:
super_admin, marketing (all); operations_manager (manage, export, late-add); customer_support (read, late-add);
finance, auditor (read, export); product_owner, fulfilment_ops, retail (read). delivery-runs-service client gains
`orders:on-behalf` (created by V23), `media:read` (V22); orders-service client already has `delivery-runs:reserve`.
media-service accepts delivery-runs-service's own client-credentials token for reads of public (storefront) assets
when it carries `media:read` (never uploads). Customer support finds customers with `users:read` (identity people
search and saved addresses).

## 7. Further ideas (proposed, not in the design)
1. **Waitlist → demand map**: waitlist sign-ups and "not delivering here" searches per city in Settings, to decide
   where the next window opens (with an "open a pre-order here" shortcut).
2. **Seller pickup batching**: at close, each seller gets one consolidated pick list + one pickup slot (we already
   have `/v1/dispatch/pickups`); show it in Seller Center.
3. **WhatsApp kit**: one-click copy of the announcement text + story creative + tracked `?src=wa` link for community
   admins (the Meta Groups API limit makes manual posting the realistic path).
4. **Group goal bar**: optional "40 orders unlocks free delivery" progress (motivation without a minimum).
5. **Recurring windows**: "repeat every 2 weeks" when publishing, drafts created ahead with copied creatives.
6. **Pickup point capacity + QR handover**: pickup orders get a QR / 6-digit code, checked in by the driver app.
7. **Late-add queue**: shoppers who miss the window can "request a late add" online; CS approves in one click.
8. **Delivery day live tracker**: driver ETA window per stop, SMS "you're next" (driver app events).
9. **Post-delivery review + re-order**: "Order again in the next Lubbock pre-order" saved cart.
10. **Margin guardrail**: show the run economics (route cost vs. delivery fees + take) on the detail page so ops sees
    when a window loses money (no minimum, but visible).

## 8. From the E34 spike: run economics and the card-hold design

(Kept from the former `docs/spikes/preorder-delivery-runs.md`, sections 9 and 7. Where they disagree with this spec, **the design wins**:
pre-orders are charged at checkout, with no minimum and no extension. The economics still size a run; `tools/spikes/run_economics.py`
reproduces the tables. The card-hold design is still the reference for authorisation expiry of manual capture, `docs/engineering/inventory-rules.md`.)

### A. Run economics model (Bastrop)

#### Model
This is the **same model as `delivery-runs`** (`DefaultRunEconomicsCalculator` **[code]**), so the staff console and this spike agree:

```
cost(n)   = fixed + n × perOrder
fixed     = (driveMin × 2 + collectionMin [+ pickupPointMin]) / 60 × driver$/h + roadMiles × 2 × vehicle$/mi
perOrder  = (doorstep stop min | handover min) / 60 × driver$/h
```

There are two break-even lenses:
- **Delivery lens:** the run fee alone covers the run. This is what S265 shows.
- **Contribution lens:** each order also brings the **seller pickup fee** ($2/package, seed tariff **[code]**) and AfroTransact's **take on the basket** (commission or Retail margin, net of basket card fees). The run fee loses about 2.9% to card fees.

#### Assumptions (all CLI flags)

| Assumption | Value | Basis |
|---|---|---|
| Origin | Downtown Austin (30.2672, −97.7431) | `delivery-runs` default origin **[code]**. **Q12:** confirm the real hub. |
| Road miles one way | **32** | Austin to Bastrop is about 30–31 mi via TX-71, 37 via US-290 ([mapsof.net](https://www.mapsof.net/distance/austin-to-bastrop-tx)). In production this comes from geo. |
| Drive minutes one way | **50** | 38–40 off-peak, about 50+ at peak. The PO says "about 1 hour". 60 is run as a sensitivity. |
| Round trip | yes | Matches service default |
| Driver cost | **$25/h** loaded | Service default `DRIVER_COST_PER_HOUR_MINOR=2500` **[assumption]** |
| Vehicle cost | **$0.70/mi** | Service default, about the IRS 2025 standard mileage rate **[check the 2026 rate]** |
| Collection (FC + seller sweep) | **45 min** fixed | Service default. Use `--sellers N` for extra stops. |
| Doorstep stop | **6 min** incl. local driving | Service default |
| Pickup point | 30 min stop + 1 min/order | Service default |
| Avg basket (excl. fees) | **$45** | **[assumption]**. Replace with actual AOV. |
| Take rate on basket | **10%** net of card fees | **[assumption]**. Bookings' fallback commission is 10% **[code]**. Retail margin is likely higher. |
| Seller pickup fee | **$2.00**/order (1 package) | Seed tariff **[code]** |

#### Today, one Bastrop order
`cost(1) = $105.22 + $2.50 = $107.72` against a $7.99 fee, a **loss of about $99.73**, or about $93 after the basket's contribution.

#### Calculator output (defaults: doorstep, 32 mi, 50 min)

`python3 tools/spikes/run_economics.py`

```
## Cost model
- fixed: 145 min + 64 mi = $105.22 per run
- per order: $2.50

## Break-even orders per run
| run fee | delivery lens | contribution lens | contribution / order |
|--------:|--------------:|------------------:|---------------------:|
|   $5.99 |            31 |                11 |               $12.32 |
|   $7.99 |            20 |                 9 |               $14.26 |
|   $9.99 |            15 |                 8 |               $16.20 |
|  $12.99 |            11 |                 7 |               $19.11 |

## Run P&L, delivery lens (run fees - estimated cost)
| orders |    cost | cost / order | fee $5.99 | fee $7.99 | fee $9.99 | fee $12.99 | fee to break even |
|-------:|--------:|-------------:|----------:|----------:|----------:|-----------:|------------------:|
|      5 | $117.72 |       $23.54 |   -$87.77 |   -$77.77 |   -$67.77 |    -$52.77 |            $23.55 |
|     10 | $130.22 |       $13.02 |   -$70.32 |   -$50.32 |   -$30.32 |     -$0.32 |            $13.03 |
|     15 | $142.72 |        $9.51 |   -$52.87 |   -$22.87 |     $7.13 |     $52.13 |             $9.52 |
|     20 | $155.22 |        $7.76 |   -$35.42 |     $4.58 |    $44.58 |    $104.58 |             $7.77 |
|     25 | $167.72 |        $6.71 |   -$17.97 |    $32.03 |    $82.03 |    $157.03 |             $6.71 |
|     30 | $180.22 |        $6.01 |    -$0.52 |    $59.48 |   $119.48 |    $209.48 |             $6.01 |
|     40 | $205.22 |        $5.13 |    $34.38 |   $114.38 |   $194.38 |    $314.38 |             $5.14 |

## Run P&L, contribution lens (fees net of card + seller fees + take on baskets - cost)
| orders | fee $5.99 | fee $7.99 | fee $9.99 | fee $12.99 |
|-------:|----------:|----------:|----------:|-----------:|
|      5 |   -$56.14 |   -$46.43 |   -$36.72 |    -$22.15 |
|     10 |    -$7.06 |    $12.36 |    $31.78 |     $60.91 |
|     15 |    $42.02 |    $71.15 |   $100.28 |    $143.98 |
|     20 |    $91.11 |   $129.95 |   $168.79 |    $227.05 |
|     25 |   $140.19 |   $188.74 |   $237.29 |    $310.11 |
|     30 |   $189.27 |   $247.53 |   $305.79 |    $393.18 |
|     40 |   $287.43 |   $365.11 |   $442.79 |    $559.31 |
```

#### Sensitivities (break-even orders at $5.99 / $7.99 / $9.99 / $12.99)

| Scenario | Fixed cost | Per order | Delivery lens | Contribution lens |
|---|---|---|---|---|
| Base (32 mi, 50 min, doorstep) | $105.22 | $2.50 | 31 / **20** / 15 / 11 | 11 / **9** / 8 / 7 |
| Peak or "1 hour" drive (37 mi, 60 min): `--road-miles 37 --drive-minutes 60` | $120.55 | $2.50 | 35 / **22** / 17 / 12 | 13 / **11** / 9 / 8 |
| Pickup point: `--drop-mode pickup-point` | $117.72 | $0.42 | 22 / **16** / 13 / 10 | 10 / **9** / 8 / 7 |
| 4 extra seller stops, 12 min each: `--sellers 4` | $125.22 | $2.50 | 36 / **23** / 17 / 12 | 13 / **11** / 10 / 8 |

#### Reading it
- At **$7.99** the run pays its own delivery cost from **about 20 doorstep orders** (16 at a pickup point). With the basket counted, it is **contribution-positive from about 9–11 orders**.
- **$5.99** needs 30+ orders to fund delivery and only makes sense as a pickup-point promo.
- **$9.99–12.99** roughly halves the delivery break-even. That's worth testing once a town has a habit. Communicate it as "town run" pricing, not a price rise.
- **Recommended Bastrop terms (Phase 0/1):** fee **$7.99**, **minimum 10 orders** (contribution break-even plus 1), **capacity 30 orders / 45 packages** (about 5.5 h of driver time doorstep), `CutoffPolicy.EXTEND` once by 7 days. Treat runs between 10 and 20 orders as acquisition spend (≤ $50 delivery loss per run, already contribution-positive).
- The service already shows the delivery lens. **S265 follow-up:** add the contribution lens (seller fees and commission share from payments) to `RunEconomics`. `atOrders` already accepts a `commissionShareMinor`.

### B. Card holds for long lead times (the spike's payment design)

#### Facts
- An online card authorisation is valid for about **7 days** for most networks. Stripe says to rely on each payment's **`capture_before`** field ([Stripe: place a hold](https://docs.stripe.com/payments/place-a-hold-on-a-payment-method), [capture later](https://docs.stripe.com/payments/capture-later)) **[doc]**.
- **Extended authorisation** (up to about 30 days) is offered to **IC+ pricing** users only. For Visa outside lodging, rental and cruise, *"the extended window doesn't apply to transactions with merchants in US or JP"*. Amex and Discover limit it to travel and lodging categories. Mastercard allows all categories (not Maestro or Cirrus) ([Stripe: extended authorization](https://docs.stripe.com/payments/extended-authorization)) **[doc]**. For a US grocery marketplace that is **not a general solution**.
- A failed off-session confirmation returns **402**, and the intent sits in `requires_payment_method`. Stripe's guidance is to bring the customer back on-session to authenticate or choose another method ([save and reuse](https://docs.stripe.com/docs/payments/save-and-reuse), [Stripe support: manual confirmation for SCA](https://support.stripe.com/questions/manual-confirmation-for-off-session-payments-requiring-strong-customer-authentication-sca)) **[doc]**. Our gateway already surfaces this as `authentication_required` or `REQUIRES_ACTION` **[code]**. In the US, 3-D Secure challenges are uncommon, so **declines** (funds, expired card) are the bigger risk **[unverified]**.
- **Refunds don't return Stripe's processing fee** ([Stripe support](https://support.stripe.com/questions/understanding-fees-for-refunded-payments)) **[doc]**. Cancelling an **uncaptured** intent involves no capture, so prefer cancel over refund wherever possible. Whether any fee applies to uncaptured authorisations depends on our Stripe pricing plan **[unverified]**.

#### Options

| | A. Authorise at order, capture at confirmation | B. Extended authorisation | C. SetupIntent at order, off-session charge at cutoff | **D. Hybrid (recommended)** |
|---|---|---|---|---|
| Customer effort | One confirmation | One | One (card saved, $0) | One (authorise + save together) |
| Funds checked at order | Yes | Yes | **No** (card validity only) | Yes |
| Works for 1–14-day cutoffs | **No**: holds lapse after about 7 days | Mostly no (US Visa non-travel ineligible, IC+ only) | Yes | **Yes** |
| Failure at charge time | Expired hold, so capture fails | Same, for ineligible cards | Declines and SCA for **every** order at cutoff | Only for **long-lead** orders (placed more than about 5 days before cutoff) |
| Visible "pending" on customer's card | Yes, until capture or cancel | Yes, for longer | No | Yes, until capture or cancel (one hold only) |
| Run cancelled | Cancel the hold (free) | Same | Nothing to do | Cancel the hold (free) |
| Build cost | Already exists | Stripe plan change + contracts | New flow | **Existing pieces**: `saveForOffSession`, `capture`, `release`, `OffSessionChargeService`, the bookings `authorisation-window` pattern |

#### Decision: D, hybrid
1. **At order:** create the PaymentIntent with `capture_method=manual` and `setup_future_usage=off_session` (`CreateIntent.saveForOffSession=true` **[code]**). Store `capture_before` on the payment, read from the latest charge's `payment_method_details.card.capture_before`.
2. **At run confirmation** (`run.confirmed`): for each order, if `now + safetyBuffer(12 h) < capture_before`, **capture the whole order** (all lines plus the run fee) and treat later shortages as **per-line refunds**. Otherwise **release** the expired or expiring hold and run an **off-session charge** for the same lines. This reuses `OffSessionChargeService`, which creates a new manual-capture payment on the order, and then captures it.
3. **If the off-session charge fails:** send `payments.intent.action_required` to notifications (email, push and WhatsApp utility) with a confirm link valid until **sellerPickupAt minus 6 h**. Retry declines once after 6 h, matching bookings' `max-attempts` and `retry-delay`. If the order is still unpaid at the deadline, the order is **removed from the run** (slot released, stock released, customer told).
4. **Why capture at confirmation, not at delivery:** the hold can lapse between cutoff and delivery, and sellers start packing at confirmation. If ops prefer capturing at pickup (no refunds for shorts), keep that as a per-location option once data shows short-pick rates.
5. **Cancellations:** before cutoff, the shopper cancels and the intent is cancelled (free). After confirmation, the order is **not cancellable by the shopper**, since sellers have committed (support can still override). A run cancelled before capture cancels the intents. A run cancelled after capture refunds in full, or offers a move to the next run with an optional goodwill credit.
6. **Card-on-file disclosure:** at checkout, say *"We'll hold $X now and charge when the run is confirmed. If the hold expires we'll charge your saved card."* That covers the consent for the off-session merchant-initiated charge **[legal wording to review]**.

#### Refund matrix

| Event | Run fee | Goods | Mechanism |
|---|---|---|---|
| Shopper cancels before cutoff | n/a | n/a | Cancel intent |
| Run cancelled before confirmation | n/a | n/a | Cancel intents |
| Run cancelled after capture | Refund | Refund (or move to next run) | `refund` all lines |
| Seller short or damaged at pickup | Keep | Refund line | `refund` line (existing) |
| Doorstep failed, customer fault | Keep | Redeliver next run, or refund less restocking (perishables: no restock) | Policy question Q9 |
| Not delivered, our fault | Refund | Refund or redeliver | `refund` |
