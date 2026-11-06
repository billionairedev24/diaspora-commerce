# Deliver-to location

What the shopper sees is a **city** ("Deliver to Georgetown"), never a ZIP. The ZIP stays internal: delivery promises,
delivery runs, market eligibility and content targeting (home heroes, tiles, rails) all use it.

## Detection (first visit, silent)

There is no popup on load and the browser is never asked for its position automatically. A session with no stored
location gets an approximate one on its first `GET /v1/delivery-location` or `GET /v1/home` (gateway-bff
`LocationService.Detect`), stored in the same session location the picker writes, flagged `source: detected`.

Order, first usable wins:

1. `LOCATION_DETECT_ZIP` (`commerce.detectOverrideZip`): a fixed ZIP, for development. `config/local.yaml` sets 77042
   (Houston) so localhost, which has no edge headers, still "detects" a demo city.
2. Cloudflare visitor-location headers, when `TRUST_EDGE_GEO=true` (`commerce.trustEdgeGeo`, on in every profile). The
   Cloudflare "Add visitor location headers" managed transform must be on. The country (`CF-IPCountry`) must be the
   market's (US).
   1. `CF-IPPostalCode` (or `CF-Postal-Code`) looked up through the geo service for city and state;
   2. else `CF-IPLatitude` / `CF-IPLongitude` reverse-geocoded through the geo service;
   3. a ZIP geo cannot resolve still shows `CF-IPCity` with `CF-Region-Code`.
3. Nothing usable: the market default (`commerce.defaultZip`/`defaultCity`), `source: default`, nothing stored.

The Next.js `/api/bff` proxy and the server-rendered pages forward those `cf-*` headers (`EDGE_GEO_HEADERS` in
`@afrotransact/api-client`), so the first server render is already local. A **chosen** location is never replaced by
detection.

## Choosing (header link)

"Deliver to <city>" opens a small popover under the link (bottom sheet on phones; `Popover` in `@afrotransact/ui`):

- signed-in shoppers: saved addresses first (one tap applies its ZIP);
- one combobox (ARIA 1.2: debounced, up/down/Enter, Esc) over `GET /v1/geo/address-suggestions?kind=place`: cities listed
  with their state ("Georgetown, TX" / "Georgetown, KY"), streets as "12 Main St, Georgetown, TX", a typed 5-digit ZIP plus
  Enter, or a ZIP answered as its city. Picking applies at once: `PUT /v1/delivery-location {suggestionId}`;
- "Use my current location": the only place `navigator.geolocation` is called, on that click. The coordinates are
  reverse-geocoded by the geo service (`PUT {lat, lon}`); a denial is a plain inline message.

A city has no ZIP of its own: the BFF resolves the picked place and uses the ZIP at its centre.

After applying, the storefront invalidates home, search, product, cart and pre-order queries and refreshes the route.

## API

`DeliveryLocation`: `zip`, `city`, `state`, `label` (the city), `serviceable`, `source` (`chosen` | `detected` |
`default`; optional for older servers). `kind=place` suggestions return `label` + `secondary` ("Georgetown" / "TX",
"12 Main St" / "Georgetown, TX"); the ZIP is never a label.

## Related home-page rule

Every rail, tile and cross-sell passes `eligibleCard` (in stock and a real photo) in one place (`railItems`), whatever
the source kind (search, department, brand, atins, near-you, community, deliverable-fast). Cards with no reviews show no
rating row at all (never "0 reviews").
