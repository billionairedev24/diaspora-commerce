# Service areas, zones and in-person only

AfroTransact services are **in person only**. There is no online, virtual, remote or video service, and nothing accepts
one (digital products such as online courses belong to the product catalogue, not to services). Every service happens in
one of two places:

| Mode (API) | Customer-facing words | Meaning |
|---|---|---|
| `AT_HOME` (provider UI: `AT_CUSTOMER`) | "Comes to you" | The provider travels to the customer's address: mobile and home services, catering, braids at home. |
| `IN_PERSON` (provider UI: `AT_PROVIDER`) | "You go to them" | The customer goes to the provider's salon, tailor shop, studio or office. |
| both | "Both" | The customer chooses at booking. |

The bookings API speaks `IN_PERSON` / `AT_HOME`; the Provider Hub and storefront (through the gateway-bff) speak
`AT_PROVIDER` / `AT_CUSTOMER` / `BOTH`. The gateway-bff maps one to the other (`deliveryOf`, `modesOf`).

## What a provider declares

A provider has one **service area** (bookings `provider_service_area`, `GET/PUT /v1/providers/{orgId}/service-area`):

- a **base location**: a ZIP (placed by geo-service from its ZIP table: city, state, centroid);
- a **radius** in miles, within staff-set limits for the provider's service categories (defaults below);
- optional **named extra areas**: a city (with state), a ZIP, or a whole metro zone;
- `servedCities`: the cities the radius reaches, worked out when the area is saved from geo's ZIP table (shown as
  "Serves Georgetown, Hutto, Pflugerville +4").

Whether a service comes to the customer or the customer goes to the provider is a property of each service (its modes).

## The matching rule

`ServiceAreaMatcher` (pure domain code, unit tested). With *d* the straight-line (haversine) distance from the provider's
base to the customer's ZIP centroid, a provider **serves** the customer when any of these holds:

1. the service comes to the customer (`AT_HOME`) and *d* ≤ the provider's radius;
2. the customer goes to the provider (`IN_PERSON`) and *d* ≤ min(radius, **customer-travel limit**, default 30 mi);
3. the customer's ZIP, city (with state) or metro zone is one of the provider's named extra areas.

Distances are inclusive. When nobody serves the place, providers within the **nearest-fallback distance** (default 60 mi)
are `NEAREST`: shown clearly labelled ("does not usually travel to Georgetown"), never as local. Beyond it: nothing. A
provider with no service area is never shown for a location search (we cannot place it); a search without a location lists
everyone without distances. **Another state is never shown as local**: cross-state providers only appear as a labelled
nearest suggestion when they are genuinely within the fallback distance.

Search (`GET /v1/public/services/search?zip&q&category&sort`) returns `{place, coverage, services, nearest, categories}`:

- `coverage`: `LOCAL`, `NEAREST`, `NONE`, or `ANY` (no location given);
- `services`: ranked by `sort` = `NEAREST` (default), `SOONEST`, `PRICE`, `RATING`; each carries the provider's base city,
  `distanceMiles`, metro zone, the cities served (the customer's first), mode, rating (`0` = no reviews: show nothing),
  price, deposit, next free time (a UTC instant: the storefront formats it in the visitor's time zone) and a photo;
- `categories`: services per category that serve the place, ignoring the category filter (the chips; never a zero).

The candidate query is plain SQL on PostgreSQL (haversine on `provider_service_area`, no PostGIS needed); the rule itself
runs in Java. geo-service supplies the ZIP table.

## Zones and radius rules (staff)

Metro **zones** ("Greater Austin" = Georgetown, Round Rock, Hutto, Pflugerville, Cedar Park, Leander, Austin, Taylor, ...) are
platform data (`service_zone`). They name the area in results, let a provider add a whole metro as an extra area, and give
the empty state its wording. Seeded: Greater Austin, Houston, Dallas-Fort Worth, New York City, North & Central Jersey,
Washington DC Metro, Atlanta, Miami. A city belongs to a zone by name and state.

**Radius rules** per service category (`service_area_policy`): default / minimum / maximum, plus the platform's
customer-travel limit and nearest-fallback distance.

| Category | Default | Min | Max |
|---|---|---|---|
| hair & beauty | 20 | 5 | 40 |
| catering | 40 | 10 | 100 |
| tailoring | 25 | 5 | 60 |
| tax & legal | 30 | 5 | 60 |
| any other | 25 | 5 | 60 |

A provider has one radius; its limits are the widest of its active services' categories. Customer-travel limit 30 mi,
nearest fallback 60 mi (set to 0 to turn the suggestions off).

Staff edit both in **Settings > Markets & locations > Service areas** (resources `service-area-rules` and
`service-zones`; permission `content:markets.edit`, read with `content:markets.view`). Every change needs a reason and is
written to `service_area_audit` (`GET /v1/staff/service-areas/audit`).

## Tell me when one joins

When a place has nothing for the chosen category, the storefront offers an email alert that works signed out
(`POST /v1/services/alerts` on the gateway-bff, rate limited per IP; bookings `POST /v1/public/services/alerts`, always
202, no enumeration). Alerts live in `service_alert`. When a provider saves a service area or lists a service that now
serves an alert's place (and category), bookings publishes `booking.service_alert` once and closes the alert.
**notifications still needs a rule and a localised template for `booking.service_alert`** (follow-up): until then the
event is on the bus but no email is sent.

## Hard rules enforced in code

- `ServiceMode` has only `IN_PERSON` and `AT_HOME`. Provider requests with `VIRTUAL`, `ONLINE`, `REMOTE`, or the flags
  `virtual`/`online`/`remote` get `422 bookings.in-person-only`; the gateway-bff refuses them first (`delivery`).
- Migration `V5__service_areas_in_person_only.sql` strips `VIRTUAL` from existing services (a virtual-only service is
  deactivated and becomes `IN_PERSON`) and rewrites historic virtual bookings to `IN_PERSON`.
- The Provider Hub service form offers three choices only: you go to them, comes to you, both.

## Data

geo-service embeds `us_zips.tsv.gz` (42,354 US ZIPs: city, state, centroid) and answers `GET /v1/zip/{zip}` and
`GET /v1/zips/nearby?lat&lon&miles` from it. Rebuild it with `tools/geo/build_zip_index.py <source.csv>`: GeoNames postal
codes (CC BY 4.0) or the free_zipcode_data csv work; the Census ZCTA gazetteer (public domain) has centroids but no city,
so join it with a city source. The shipped file came from the free_zipcode_data csv (public USPS/Census data).

## Run and migrate

```bash
tools/dev/local.sh restart geo bookings gateway-bff    # geo first: bookings asks it for ZIPs; Flyway V5/V6 run on start
make seed                                              # providers, services, photos and service areas (idempotent)
```

Staff-console and storefront need no migration. Demo expectations after seeding: Georgetown (78626) lists Hill Country
Braids (Round Rock), Zuri Hair Studio (Pflugerville) and Bola's Kitchen (Hutto), never Brooklyn or Houston; tailoring in
Georgetown shows Capital City Tailors (Austin) as the nearest suggestion; Dallas has no tailoring and shows the empty
state with the alert and "Offer your services".
