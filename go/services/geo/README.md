# geo

Addresses and places for every surface. Go 1.27.2 · PostgreSQL + PostGIS · local port **8205**.

| Capability | How |
|---|---|
| Autocomplete | Providers in priority order from config: **Google Places API (New)** (only when `GOOGLE_PLACES_API_KEY` is set; session tokens group keystrokes + details into one billable session), **OpenStreetMap Nominatim** (identifying User-Agent, ≤1 req/s, 1-hour cache), and an offline **fixture** list for local/tests. If a provider fails, the next one answers. Keys never reach clients. |
| Resolve | `GET /v1/addresses/resolve/{id}` returns street, unit, city, state, ZIP, country and coordinates. A typed house number is kept. |
| Validate | **Smarty US Street API** (USPS CASS/DPV): `VALID`, `CORRECTED` (suggestion shown, never silently applied), `MISSING_UNIT` (multi-unit building without apt/suite), `UNDELIVERABLE`, plus residential vs commercial. Without Smarty credentials, or if Smarty is down, format rules return `UNVERIFIED`. |
| Reverse geocode | `GET /v1/reverse?lat=&lng=` (`lon` also accepted) returns ZIP, city, state and country for the deliver-to "use my location" button. Providers behind the `ReverseGeocoder` port, in order from `GEO_REVERSE_PROVIDERS`: **Google Geocoding API** (`google-geocoding`, needs the Google key), **Nominatim** `/reverse` (`osm-nominatim`), and the offline **fixture** (nearest seed within 25 km; local/dev only). Coordinates are rounded to 4 decimals (~11 m) before lookup; results without a postal code fall through to the next provider; ZIP+4 is truncated. 400 `geo.invalid-point`, 404 when no provider knows the point. `Cache-Control: private, max-age=3600`. Tests use fakes and never call the network. |
| ZIP lookup | `GET /v1/zip/{zip}` for the deliver-to picker (cached 24 h at the edge). |
| Distance (S255) | `POST /v1/distance {from, to}` and `POST /v1/distance/batch {from, to:[…≤100]}` → `{roadMiles, durationMinutes, straightLineMiles, method, cached}`. A place is `{lat,lng}` (coordinates stored on an address flow through as precision `POINT`), `{addressId}` (provider id from autocomplete), `{zip}` (centroid) or `{address}` (geocoded). **Road distance comes from routing providers** behind the `RoadRouter` port, in priority order from config (see Routing below): **Google Routes API** `computeRouteMatrix` (driving, metric; method `ROAD_GOOGLE`) and **self-hosted OSRM** `/table/v1/driving/{coords}?annotations=distance,duration` (method `ROAD_OSRM`). A provider that fails or times out hands over to the next; only when none is configured or all fail is the PostGIS geodesic distance (`DistanceCalculator` port, `ST_Distance` on `geography`) × detour factor used, with a duration at 25 mph (method `GEODESIC_DETOUR`). `straightLineMiles` is always the PostGIS geodesic. Provider results are cached in memory by origin / destination rounded to ~50 m for 24 h (`cached: true`). Batch: one PostGIS query, providers in chunks of 25; a destination that can't be located gets its own `error`. `estimatedRoadMiles` repeats `roadMiles` (deprecated). |
| Geocode | `POST /v1/geocode {line1, city, state, postalCode}` → `{lat, lng, precision}`: the autocomplete provider chain (first suggestion in the same ZIP, then resolve), falling back to the ZIP centroid. Used for seller ship-from addresses and customer addresses without stored coordinates. |
| Zones | PostGIS `geography` zones: `DELIVERY` (AfroTransact Delivery), `FC_CATCHMENT`, `SERVICE_AREA` (provider radius such as "braider within 15 miles"). Polygons (GeoJSON) or circles; `GET /v1/zones/covering?lat&lon&type` answers eligibility; with `withinMiles=` it also returns zones whose edge is that close, nearest first, with `inside` / `distanceMiles` (seller pickup validation). Writes need `geo:zones:write`. |

Contract: [`contracts/openapi/geo.yaml`](../../../contracts/openapi/geo.yaml); docs at `/docs` and `/redoc`.

## Configuration

`config/<APP_ENV>.yaml` + env: `DB_URL`, `GEO_PROVIDERS` (comma list), `GOOGLE_PLACES_API_KEY`, `NOMINATIM_URL`,
`NOMINATIM_USER_AGENT`, `SMARTY_AUTH_ID`, `SMARTY_AUTH_TOKEN`, `AUTH_ENABLED`, `AUTH_ISSUER_URL`, `DOCS_ENABLED`.
Local uses the fixture list first so typing works offline. For production volume, self-host Nominatim or rely on Places.

## Routing (road distance, S255)

| Setting (`routing.*` / env) | Default | Meaning |
|---|---|---|
| `providers` / `GEO_ROUTING_PROVIDERS` | local `[]`; dev, uat, prod `[google, osrm]` | priority order; each provider is enabled only when its config is present, so environments switch routing on by setting secrets |
| `GOOGLE_MAPS_API_KEY` | — (secret; falls back to `GOOGLE_PLACES_API_KEY`) | enables `google` (Routes API `computeRouteMatrix`; enable the Routes API on the key's project) |
| `osrmUrl` / `OSRM_URL` | — (secret or per-env value) | enables `osrm` (self-hosted OSRM, e.g. `http://osrm:5000`) |
| `timeout` / `GEO_ROUTING_TIMEOUT` | `2s` | per provider call; a timeout moves on to the next provider |
| `cacheTtl` / `GEO_ROUTE_CACHE_TTL` | `24h` (`0` disables) | route cache TTL |
| `cachePrecisionMeters` / `GEO_ROUTE_CACHE_PRECISION_METERS` | `50` | cache grid (origin and destination rounded to it) |
| `cacheMaxEntries` / `GEO_ROUTE_CACHE_MAX_ENTRIES` | `50000` | in-memory bound per pod |
| `detourFactor` / `GEO_DETOUR_FACTOR`, `fallbackSpeedMph` / `GEO_FALLBACK_SPEED_MPH` | `1.3`, `25` | last-resort estimate when no provider answers |

Local runs never call a routing vendor (no provider configured). Tests use httptest servers and fakes: provider
parsing (both), fallback order (failure and timeout), cache hits.

## Run and test

```bash
cd go/services/geo
GOTOOLCHAIN=go1.27.2 go test ./...      # address + routing providers via httptest, fakes for the provider chain and cache, PostGIS Testcontainers (local postgis/postgis:17-3.5) for zones and geodesic distance
APP_ENV=local go run ./cmd/geo           # needs the geo database from infra/local
```
