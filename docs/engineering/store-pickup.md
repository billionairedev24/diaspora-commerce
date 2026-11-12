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

## Open decision (owner)

Whether sellers are prompted or defaulted to enable store pickup is not decided. Recommendation: prompt, do not default. Show a
one-time card in Seller Center when a seller has an approved ship-from address and no pickup place ("Let customers collect orders
here"), pre-filled with the address and the usual hours, with the switch off until the seller confirms. A default-on would promise
customers a counter the seller has not staffed.
