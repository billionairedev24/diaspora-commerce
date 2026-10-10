# Pre-orders: implementation spec (from `design/AfroTransact Pre-Order v2.dc.html`)

Source of truth: `design/AfroTransact Pre-Order v2.dc.html` (storefront + back office + "How it works"),
`design/at-preorder.js` (shared store, location model, attribution), `design/AfroTransact Shop.dc.html`
(ribbon, "send to family" banner, `/pre-orders` page) and `design/AfroTransact Backoffice.dc.html` +
`design/backoffice-config.js` (the **Pre-orders** module, permission matrix). Where this spec and the earlier
spike (`preorder-delivery-runs.md`) disagree, **the design wins**:

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
  the delivery fee until close), `lateAdd` {savedCard, paymentLink, linkExpiryHours = 24},
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
  for the late-add customer picker (identity + addresses) and CSV streaming passthrough.

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
- **`/pre-orders`** page: off-page list of open/upcoming windows (`openList`); per-window page with rules (4 bullets),
  timeline, products in the window's categories, "missed it" support block (phone).
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
  creatives; comms toggles (6) + SMS copy with 160 counter + social budget; review summary + the 7 checks; Save draft;
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
`orders:on-behalf`, `media:read`; orders-service client already has `delivery-runs:reserve`.

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
