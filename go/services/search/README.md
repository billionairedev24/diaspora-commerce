# search

Product search and typeahead over Elasticsearch 9. Go 1.27.1 · local port **8201**.

## Behaviour

- Every typed word must match. Partial words match (search-as-you-type), typos are tolerated
  (one edit for 3–5 letters, two for 6+, first letter fixed) and diaspora synonyms expand the query
  (gari/garri/cassava flakes, zobo/bissap/sobolo/hibiscus, egusi/melon seed, aso oke/aso-oke …).
- Filters: department, community, fulfilment (`AFROTRANSACT`/`SELLER`), seller kind (`PLATFORM`/`SELLER`), rating,
  price range (minor units), delivery days, in stock, distance from a point.
- Facets for every filter, sorts (relevance, price, rating, newest), relevance boosted by rating, stock and
  Fulfilled-by-AfroTransact.
- Natural-language queries (3+ words) blend kNN over the `embedding` field using the AI gateway's embeddings;
  if the gateway is slow (>150 ms) or down, results fall back to lexical search.
- Typeahead returns completed phrases (the first scoped to its department) and up to five products, cacheable 30 s.
- `lowStock` is set when 1–5 units are left (the storefront shows "Only N left").

The index mapping and document shape live in the shared [`go/pkg/searchindex`](../../pkg/searchindex) package,
used by the indexer (writer) and this service (reader). Index `products-<market>-v<N>` sits behind the alias
`products-<market>` for zero-downtime reindexing.

## API

`GET /v1/search`, `GET /v1/suggest` — contract [`contracts/openapi/search.yaml`](../../../contracts/openapi/search.yaml).
Also `/healthz`, `/readyz` (pings Elasticsearch), `/metrics`, `/openapi.yaml`, `/docs` (Scalar), `/redoc`.
Auth is optional (public reads); when a bearer token is present it is verified against the identity JWKS.

## Configuration

`config/<APP_ENV>.yaml` (local, dev, uat, prod) with env overrides: `PORT`, `ES_URLS`, `ES_USERNAME`,
`ES_PASSWORD`, `ES_API_KEY`, `AUTH_ENABLED`, `AUTH_ISSUER_URI`, `AI_GATEWAY_URL`,
`OTEL_EXPORTER_OTLP_ENDPOINT_HOST`, `TRACE_SAMPLING`, `DOCS_ENABLED`.
In `local` the service creates the index and alias on start.

## Run and test

```bash
cd go/services/search
GOTOOLCHAIN=go1.27.1 go test ./...        # unit + Elasticsearch Testcontainers integration test
golangci-lint run --config ../../.golangci.yml ./...
APP_ENV=local go run ./cmd/search          # http://localhost:8201/docs
docker build -f services/search/Dockerfile -t search ../..   # from go/: distroless image (~46 MB)
```
