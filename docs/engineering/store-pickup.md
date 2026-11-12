# Store pickup at checkout ("Collect from the store")

Plain answer to "why is Pickup not offered for this seller?". Nobody has to enable it in the staff console. It is **the seller's
switch**, per ship-from address, in Seller Center, and it needs a delivery address on the shopper's side. Nothing is defaulted:
the seed only creates one seller pickup place (Mama Put's kitchen, `tools/seed/seedlib/ops.py` `_seller_customer_pickup`).

## What must be true for a seller's package to offer Pickup

1. **The seller turned it on**: Seller Center > Orders > Ship-from & pickup > the address > "Customers can pick up their orders
   here", with at least one opening window. That saves an **active** carrier-gateway pickup location of kind `SELLER`, id =
   the ship-from address id, owner = the seller's organisation (`gateway-bff` `SetCustomerPickup`).
2. **The shopper has a delivery address with a 5 digit ZIP** (the summary has no pickup before that).
3. **The place is near the address**: within 60 miles (`pickupRadiusMiles`) when both the place and the address have coordinates,
   otherwise in the same ZIP area (first 5, then first 3 digits). Up to 5 places, nearest first.
4. The package is the seller's own (seller or AfroTransact Delivery fulfilment). AfroTransact-fulfilled packages (Retail, Fulfilled by
   AfroTransact) use the AfroTransact counters and lockers that staff manage in Delivery dispatch > Pickup points instead.
5. The package is not a pre-order.

Geocoding is **not** required for store pickup (a place without coordinates is matched by ZIP area). The
`Position.Precision = ADDRESS/POINT` check (`shopper_checkout.go`, local delivery) belongs to **AfroTransact Delivery from the
store** ("AfroTransact picks up from my store"), a different feature with its own switch (`afrotransactPickup`).

## How to see why it is missing

- **Checkout summary** (`GET /v1/checkout/summary`): each package carries `pickupUnavailableReason` when it has no pickup option:
  `NEEDS_ADDRESS`, `SELLER_NOT_ENABLED` (the seller has no active pickup place), `TOO_FAR`, `OTHER_AREA`, `NO_PLATFORM_POINT`,
  `LOOKUP_FAILED` (carrier-gateway unreachable). Machine-readable, no ids; the app words it.
- **gateway-bff log**: `no store pickup offered for the package` with `seller`, `sellerPickupPlaces`, `zip` and `reason`.
- **Seller Center**: the pickup card of each address lists plain issues (`customerPickup.issues`): pickup off, no hours, address
  not located on the map (only the same ZIP area sees it), located only by ZIP (approximate distance).
- **Staff console**: Delivery dispatch > Pickup points shows "Why not offered" per location (paused, no hours, not located).
- **SQL** (carrier-gateway database `carriers`): `select id, kind, owner_org, name, active, postal_code, position from pickup_location where owner_org = '<seller org id>';`
  Or `GET http://localhost:8209/v1/pickup-locations?ownerOrg=<seller org id>` (active only).

## Prompted, never defaulted (owner decision)

Sellers are **prompted** to enable store pickup; it is never on by default and nothing switches it on except the seller's own
explicit confirmation.

- **The card** ("Let customers collect orders from your address") shows on Seller Center home and on Orders > Ship-from & pickup
  to an approved seller organisation that has a ship-from address and **no active pickup place**. It is pre-filled from the default
  address (name, address, ZIP, phone if present). Opening hours and the ready time start **empty and required**; nothing is invented.
  It says in plain words: "Customers will be able to collect orders from this address during these hours. Only switch this on if
  someone will be there." and previews "Shoppers near ZIP 77002 will see Pickup". "Turn on pickup" stays disabled until the hours
  are valid, a ready time is typed and the seller ticks "Someone will be at this address during these hours."
- **Not now / Don't ask again** are saved **server side per seller organisation** (carrier-gateway `pickup_prompt`, `GET/PUT
  /v1/pickup-prompt`, via gateway-bff `GET/PUT /v1/seller/pickup-prompt`), so every member and every browser sees the same.
  "Not now" asks again after **30 days** (the staff setting `pickupPrompt.reappearDays`, `GET/PUT /v1/pickup-prompt/settings`,
  needs `shipping:admin`, 1 to 365); "Don't ask again" is final.
- **A small inline nudge** shows on Orders while the organisation has no pickup place, whatever was dismissed.
- **The server enforces it**: carrier-gateway refuses to switch a SELLER pickup place from off to on without `hoursConfirmed: true`
  and at least one valid opening window (400 `carrier-gateway.invalid-request`); gateway-bff checks the same first (422
  `PICKUP_HOURS_NOT_CONFIRMED`) and requires an approved organisation. It records **who** (`pickup_location.enabled_by`) and **when**
  (`enabled_at`, also shown to the seller as `customerPickup.enabledAt`) and ignores audit fields sent by a client. Editing a place
  that stays on needs no new confirmation and keeps the first record.
- **At checkout**, a package without pickup says why only when it helps the shopper: "This seller doesn't offer pickup." (seller
  has no place), "Add a delivery address to see pickup options." or "There's no pickup spot near this address." Internal reasons
  (`NO_PLATFORM_POINT`, `LOOKUP_FAILED`) say nothing.

"Approved address" has no per-address approval in the seller service, so it means: the seller organisation is approved and the
address is saved (an unlocated address still shows an issue on its pickup card, and is matched by ZIP area).
