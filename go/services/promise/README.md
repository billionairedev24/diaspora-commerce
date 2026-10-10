# promise

Delivery promises per destination ZIP — "Tomorrow, Oct 10", "Wed, Oct 15 – Fri, Oct 17", "Order within 3 h 12 min" —
for search cards, the PDP, the cart and checkout. Go 1.27.2 · Valkey · local port **8204**.

## How a promise is computed

| Method | Rule |
|---|---|
| `CARRIER` from an **FC** | The FC's time zone and order cutoff (e.g. Houston 14:00 CT) decide the ship day: today if it is a work day and before the cutoff, otherwise the next work day. Carrier transit comes from the **ZIP3-to-ZIP3 table** for the launch FCs (Houston TX, Edison NJ, Atlanta GA) in `data/fc_transit.csv`. Service levels `GROUND`, `TWO_DAY` (≤ 2 days), `OVERNIGHT` (1 day); Saturday delivery is configurable per level. |
| `CARRIER` from a **seller** | Seller cutoff (default 12:00 local, time zone from the ship-from ZIP) and **handling days** (business days) decide the ship day; transit uses the table lane for the seller's ZIP3 when there is one, else the **distance between ZIP3 centroids** (`data/zip3_regions.csv`) mapped to carrier-style bands. Alaska, Hawaii and Puerto Rico are 5–7 days. |
| `AFROTRANSACT_DELIVERY` | Only inside an FC's driver zone (ZIP3 lists per FC). Same day inside the same-day zone before the same-day cutoff (11:00); otherwise the next delivery day (drivers can work Saturdays). Seller parcels inside a zone are collected and delivered the next delivery day. Outside every zone the offer is `available: false`. |
| `PICKUP` | FC counters: now + preparation time (2 h) aligned to the counter's opening hours. Seller pickup: after handling days, at opening time (hours from the request, i.e. carrier-gateway pickup locations, or the seller defaults). Closed on federal holidays. |

**Business calendar:** Saturdays and Sundays plus US federal holidays computed for any year (New Year's Day, MLK,
Washington's Birthday, Memorial Day, Juneteenth, Independence Day, Labor Day, Columbus Day, Veterans Day,
Thanksgiving, Christmas) with observed rules — Saturday holidays move to Friday, Sunday holidays to Monday, including
New Year's Day observed on Dec 31 of the previous year.

**Labels** are rendered for the customer's time zone (from the destination ZIP): one day → "Today, Oct 8",
"Tomorrow, Oct 9" or "Tue, Oct 13"; a range → "Wed, Oct 15 – Fri, Oct 17"; pickup → "Ready for pickup today at
12:30 PM". The countdown "Order within 3 h 12 min" is shown while the cutoff is less than 24 hours away.

## API

- `POST /v1/promises` — `{destinationZip, offers: [{offerId, origin, method}]}` (up to 100 offers) → one promise per
  offer, in order. Invalid offers come back `available: false` with a `reason`; an invalid ZIP is a 400.
- `GET /v1/promises/{id}` — the snapshot of an available promise, kept **24 h** in Valkey so checkout stores the exact
  promise on the order (and orders can measure it against actual delivery).

Promise ids are deterministic per offer and dates (`prm_…`). Computed dates are cached in Valkey per
(origin, method, ZIP, origin-local date-hour) until the next hour or the cutoff, whichever comes first; countdown
and relative labels are recomputed on every response. Reads are public (anonymous shoppers see promises on cards).
Contract: [`contracts/openapi/promise.yaml`](../../../contracts/openapi/promise.yaml); docs at `/docs` and `/redoc`.

## Configuration

`config/<APP_ENV>.yaml` + env: `REDIS_URL` (secret outside local), `SNAPSHOT_TTL`, `MAX_OFFERS`, `AUTH_ENABLED`,
`AUTH_ISSUER_URL`, `DOCS_ENABLED`. FCs (`fulfilmentCentres`: ZIP, time zone, cutoff, Saturday work, pickup counter
hours, same-day/next-day ZIP3 zones), service levels and seller defaults are YAML. Transit and ZIP regions are CSV
data in `data/` — the ZIP3 centroids are coarse regional points and the FC lanes were seeded from centroid distances;
replace them with carrier zone/transit charts and Census ZCTA centroids without code changes.

## Run and test

```bash
cd go/services/promise
export GOTOOLCHAIN=go1.27.2 GOWORK=off
go test ./...      # calendar/holidays, cutoffs, rollovers, handling, same-day, pickup, labels, batch API, Valkey Testcontainers
APP_ENV=local go run ./cmd/promise   # needs Valkey from infra/local
```
