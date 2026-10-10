# geo

Addresses and places for every surface. Go 1.27.1 · PostgreSQL + PostGIS · local port **8205**.

| Capability | How |
|---|---|
| Autocomplete | Providers in priority order from config: **Google Places API (New)** (only when `GOOGLE_PLACES_API_KEY` is set; session tokens group keystrokes + details into one billable session), **OpenStreetMap Nominatim** (identifying User-Agent, ≤1 req/s, 1-hour cache), and an offline **fixture** list for local/tests. If a provider fails, the next one answers. Keys never reach clients. |
| Resolve | `GET /v1/addresses/resolve/{id}` returns street, unit, city, state, ZIP, country and coordinates. A typed house number is kept. |
| Validate | **Smarty US Street API** (USPS CASS/DPV): `VALID`, `CORRECTED` (suggestion shown, never silently applied), `MISSING_UNIT` (multi-unit building without apt/suite), `UNDELIVERABLE`, plus residential vs commercial. Without Smarty credentials, or if Smarty is down, format rules return `UNVERIFIED`. |
| ZIP lookup | `GET /v1/zip/{zip}` for the deliver-to picker (cached 24 h at the edge). |
| Zones | PostGIS `geography` zones: `DELIVERY` (AfroTransact Delivery), `FC_CATCHMENT`, `SERVICE_AREA` (provider radius such as "braider within 15 miles"). Polygons (GeoJSON) or circles; `GET /v1/zones/covering?lat&lon&type` answers eligibility. Writes need `geo:zones:write`. |

Contract: [`contracts/openapi/geo.yaml`](../../../contracts/openapi/geo.yaml); docs at `/docs` and `/redoc`.

## Configuration

`config/<APP_ENV>.yaml` + env: `DB_URL`, `GEO_PROVIDERS` (comma list), `GOOGLE_PLACES_API_KEY`, `NOMINATIM_URL`,
`NOMINATIM_USER_AGENT`, `SMARTY_AUTH_ID`, `SMARTY_AUTH_TOKEN`, `AUTH_ENABLED`, `AUTH_ISSUER_URI`, `DOCS_ENABLED`.
Local uses the fixture list first so typing works offline. For production volume, self-host Nominatim or rely on Places.

## Run and test

```bash
cd go/services/geo
GOTOOLCHAIN=go1.27.1 go test ./...      # providers via httptest, fallback logic, PostGIS Testcontainers test
APP_ENV=local go run ./cmd/geo           # needs the geo database from infra/local
```
