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
- a **radius** in miles: how far the provider **travels to customers** (it matters for `AT_HOME` services; a shop the customer
  goes to is not limited by it), between the staff-set minimum and maximum for the provider's service categories;
- optional **named extra areas**: a city (with state), a ZIP, or a whole metro zone;
- `servedCities`: the cities the radius reaches, worked out when the area is saved from geo's ZIP table (shown as
  "Serves Georgetown, Hutto, Pflugerville +4").

Whether a service comes to the customer or the customer goes to the provider is a property of each service (its modes).

## The matching rule

**The shopper decides how far to go. The platform sets no distance limit on shoppers.** What is left of limits is the
provider's own: someone who comes to the customer cannot travel past their declared radius.

`ServiceAreaMatcher` (pure domain code, unit tested). With *d* the straight-line (haversine) distance from the provider's
base to the customer's ZIP centroid and *r* the distance the shopper chose (`ShopperRadius`: 5, 10, 25, 50, 100 or any other
whole number up to 1000; `0` = any distance), a provider **serves** the customer when any of these holds:

1. the service comes to the customer (`AT_HOME`) and *d* ≤ the provider's declared radius (the shopper's *r* is irrelevant:
   the provider does the travelling);
2. the customer goes to the provider (`IN_PERSON`) and *d* ≤ *r*, or *r* is "any distance" (the provider's radius is
   irrelevant: the customer does the travelling);
3. the customer's ZIP, city (with state) or metro zone is one of the provider's named extra areas (either mode).

| Mode | Inside provider radius | Outside provider radius |
|---|---|---|
| `AT_HOME` | match | no match (unless a named area covers the shopper), at any *r* |
| `IN_PERSON` | match when *d* ≤ *r* | match when *d* ≤ *r* (any distance: always) |
| both modes | match if either row matches | |

Distances are inclusive. There is no "nearest suggestion" any more: nobody is shown as a fallback, the shopper widens the
distance instead ("Search wider" on the storefront). A provider with no service area is never shown for a location search (we
cannot place it); a search without a location lists everyone without distances.

**Default radius.** When the request carries no `radiusMiles` each service is judged with the default of *its* category
(hair and beauty 20, catering 40, tailoring 25, tax and legal 30, anything else 25: the staff-editable rules below). With a
`category` filter the response reports that category's default; without one it reports the general default (25) while each
category still uses its own. The response says `radiusDefault: true` so the page can show "25 miles" as the default and not as
a choice.

Search (`GET /v1/public/services/search?zip&q&category&sort&radiusMiles`) returns
`{place, coverage, services, categories, radiusMiles, radiusDefault}`:

- `coverage`: `LOCAL`, `NONE` or `ANY` (no location given);
- `radiusMiles`: the distance searched (`0` = any distance) and `radiusDefault`: it is the category default, not a choice;
  `422 bookings.invalid-radius` for a negative value or one above 1000 (a sanity bound, not a policy);
- `services`: ranked by `sort` = `NEAREST` (default), `SOONEST`, `PRICE`, `RATING`; each carries the provider's base city,
  `distanceMiles`, metro zone, the cities served (the customer's first), mode, rating (`0` = no reviews: show nothing),
  price, deposit, next free time (a UTC instant: the storefront formats it in the visitor's time zone) and a photo;
- `categories`: services per category that serve the place at that distance, ignoring the category filter (the chips; never
  a zero).

The gateway-bff (`GET /v1/services/search?radiusMiles=`) takes an integer 1-1000 or `any` (also `0`) and forwards it; anything
else is `422`. The candidate query is plain SQL on PostgreSQL (haversine on `provider_service_area`, nearest first, no PostGIS
needed); the rule itself runs in Java. geo-service supplies the ZIP table.

### Storefront /services

"Services near Georgetown" has a compact **Within [25 miles]** select beside it (steps 5, 10, 25, 50, 100, Any distance; a
native `<select>` with the label "Within", so keyboard and screen readers work). The choice is remembered per browser in
`localStorage` (key `at.servicesRadius`, wrapped in try/catch: a blocked storage just means it is not remembered, no cookie
is set) and mirrored in the URL as `?radius=25` / `?radius=any`. Order of precedence: the URL, then the remembered choice, then
the default the server reports (listed in the select too when it is not one of the steps, e.g. "20 miles"). The search waits
for that decision so it runs once. The empty state says how many miles were searched, offers **Search wider** (the next step;
hidden at "Any distance") and "Tell me when one joins". Chips never show a zero.

## Zones and the default search radius (staff)

Metro **zones** ("Greater Austin" = Georgetown, Round Rock, Hutto, Pflugerville, Cedar Park, Leander, Austin, Taylor, ...) are
platform data (`service_zone`). They name the area in results, let a provider add a whole metro as an extra area, and give
the empty state its wording. Seeded: Greater Austin, Houston, Dallas-Fort Worth, New York City, North & Central Jersey,
Washington DC Metro, Atlanta, Miami. A city belongs to a zone by name and state.

**Default search radius by category** (`service_area_policy`): the **default** is how far shoppers search in a category
until they choose their own distance. It is a default, never a limit. **Minimum** and **maximum** bound the radius a
*provider* declares for how far they travel to customers.

| Category | Default search radius | Provider min | Provider max |
|---|---|---|---|
| hair & beauty | 20 | 5 | 40 |
| catering | 40 | 10 | 100 |
| tailoring | 25 | 5 | 60 |
| tax & legal | 30 | 5 | 60 |
| any other | 25 | 5 | 60 |

A provider has one radius; its bounds are the widest of its active services' categories. The old platform "customer travel
limit" (30 mi) and "nearest-suggestion distance" (60 mi) are gone: migration `V7__default_search_radius.sql` removes them from
the stored policy, and the API no longer has them.

Staff edit both in **Settings > Markets & locations > Service areas** (resources `service-area-rules`, titled "Default search
radius by category", and `service-zones`; permission `content:markets.edit`, read with `content:markets.view`). Every change
needs a reason and is written to `service_area_audit` (`GET /v1/staff/service-areas/audit`).

## Tell me when one joins

When nothing is within the shopper's distance for the chosen category, the storefront offers an email alert that works signed out
(`POST /v1/services/alerts` on the gateway-bff, rate limited per IP; bookings `POST /v1/public/services/alerts`, always
202, no enumeration). Alerts live in `service_alert`. When a provider saves a service area or lists a service that now
serves an alert's place (and category) at the category's default radius (an alert stores no distance), bookings publishes `booking.service_alert` once and closes the alert.
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
tools/dev/local.sh restart geo bookings gateway-bff    # geo first: bookings asks it for ZIPs; Flyway V5 to V7 run on start
make seed                                              # providers, services, photos and service areas (idempotent)
```

Staff-console and storefront need no migration (bookings runs Flyway V7 on start). Demo expectations after seeding: Georgetown
(78626) lists Hill Country Braids (Round Rock), Zuri Hair Studio (Pflugerville) and Bola's Kitchen (Hutto) at the defaults,
never Brooklyn or Houston; tailoring in Georgetown (default 25 mi) is empty with "Within 25 miles" and "Search wider", and
Capital City Tailors (Austin, about 28 mi) appears at 50 miles or any distance; at any distance Brooklyn and Atlanta tailors
appear too, but never Hill Country Braids for a Houston shopper (they come to you only within their own 20 miles); Dallas has
no tailoring within reach at the default and shows the empty state with the alert and "Offer your services".
