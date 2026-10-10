# search

Product search and typeahead over Elasticsearch 9. Go 1.27.2 · local port **8201**.

## Behaviour

- Every typed word must match. Partial words match (search-as-you-type), typos are tolerated
  (one edit for 3–5 letters, two for 6+, first letter fixed) and diaspora synonyms expand the query
  (gari/garri/cassava flakes, egusi/agushi/melon seeds, kpekere/ipekere/plantain chips, jollof (rice) seasoning/spice,
  zobo/bissap/hibiscus …).
- **Synonyms (S039)** are an Elasticsearch *synonyms set* (`diaspora-synonyms`, seeded from
  [`config/synonyms.yaml`](config/synonyms.yaml) when missing or empty; `SYNONYMS_FILE` / `SYNONYMS_OVERWRITE`) that the
  `product_search` analyzer references (`synonym_graph`, `updateable`): staff edits reload analyzers without reindexing.
  Indices created by the indexer or by search reference the set; an older index with inline synonyms is switched on
  start when `SYNONYMS_MIGRATE_INDEX=true` (close → update → open, a few seconds: on in local/dev, run once in a
  maintenance window in uat/prod).
- **Ranking (S039)**: BM25 over title^5, brand^3, category path^2 and attribute text (description)^1, plus literal
  (synonym-free) title / brand / phrase boosts, multiplied by commercial boosts that never zero a score: in stock ×3,
  Fulfilled by AfroTransact ×1.2, rating × review confidence (1 + 0.08 × rating × min(1, log10(1 + reviews) / 2)),
  delivery within 2 days ×1.05. Unknown markets are rejected (`400 search.unknown-market`).
- Filters: department, community, fulfilment (`AFROTRANSACT`/`SELLER`), seller kind (`PLATFORM`/`SELLER`), rating,
  price range (minor units), delivery days, in stock, distance from a point.
- Facets for every filter, sorts (relevance, price, rating, newest), relevance boosted by rating, stock and
  Fulfilled-by-AfroTransact.
- Natural-language queries (3+ words) blend kNN over the `embedding` field using the AI gateway's embeddings;
  if the gateway is slow (>150 ms) or down, results fall back to lexical search.
- Typeahead returns completed phrases (the first scoped to its department) and up to five products, cacheable 30 s.
- `lowStock` is set when 1–5 units are left (the storefront shows "Only N left").

## Relevance harness (S039)

[`relevance/judgements.yaml`](relevance/judgements.yaml) holds graded query → ATIN judgements over the fixtures in
[`relevance/products.yaml`](relevance/products.yaml). `TestRelevanceJudgements` indexes them into Elasticsearch 9.3
(Testcontainers, the production settings and query) and fails when mean NDCG@10 or precision@5 drops below the
thresholds in the file (it runs in CI with `go test ./...`). Measured: before tuning 0.855 / 0.807, after 0.942 / 0.913.

```bash
go test ./internal/adapter/elastic -run TestRelevanceJudgements -v   # prints the per-query table
```

## Search analytics (S039)

Every tracked search (`track` defaults to true; the BFF sends `track=false` for rails and Lola) publishes
`search.query.performed`; `POST /v1/search/clicks` publishes `search.result.clicked` (once per search and product).
Daily aggregates per normalised query (searches, zero results, clicks) live in Elasticsearch, index
`search-analytics-<market>`: one document per (day, query), incremented by a buffered flush every 10 s
(`ANALYTICS_FLUSH_INTERVAL`), purged after 400 days.

*Why Elasticsearch and not Postgres:* search has no database today and Elasticsearch is already its only store, so
analytics add no new datastore, credentials, migrations or infra; the reports are aggregations (terms by query,
date histogram by day) that Elasticsearch answers natively; the aggregates are derived data — rebuildable from the
`search.query.performed` stream — so they do not need transactional guarantees; and bounded (day × query) documents
updated by batched scripted upserts avoid per-search writes. The trade-off is that an unflushed buffer (≤ 10 s) is
lost on a crash; the Kafka events remain the durable record.

Staff console (`search:analytics.view`): `GET /v1/search/analytics/top-queries`, `/zero-results`, `/ctr`
(`market`, `from`, `to` as YYYY-MM-DD, `limit`). Synonym curation: `GET /v1/search/synonyms`
(`search:analytics.view` or `search:synonyms.edit`), `PUT|DELETE /v1/search/synonyms/{ruleId}` (`search:synonyms.edit`).

## Sponsored products (S127)

With `ads=true` (results pages only) ads take slots 1 and 5 (`ads.slots`), always labelled "Sponsored" with the
advertiser and a `reason` (targeting matched, relevance, auction rank, eligibility; never bids). Campaigns come from
content-service `GET /v1/ads/serving` (client `search-service`, scope `ads:serve`, cached 15 s, stale for 5 min when
content is down; without campaigns the page stays organic). Rules:

- eligible: campaign active, in schedule, budget left (content); product live — seller not hidden — and in stock,
  matching the shopper's query and filters, with the advertiser holding the buy box (search);
- targeting: a keyword whose words are all in the query, or a category matching the department filter or a top result;
- generalised second-price auction on bid × quality, quality = ad relevance ÷ best organic relevance, relevance floor
  0.3 (`ADS_MIN_QUALITY`), reserve 5¢; one ad per campaign and per product; an advertised product leaves its organic
  slot. The winner pays ceil(next rank ÷ own quality) + 1¢, at most its bid.

Each ad carries an HMAC-signed click token (`ADS_TOKEN_KEY`, ≥ 32 chars, required outside local; 1 h). Impressions
publish `ads.impression.recorded` (id stable per search and ad); a click with a valid, unexpired, first-seen token
publishes `ads.click.recorded` (id stable per token) with the price; content charges it.

The index mapping and document shape live in the shared [`go/pkg/searchindex`](../../pkg/searchindex) package,
used by the indexer (writer) and this service (reader). Index `products-<market>-v<N>` sits behind the alias
`products-<market>` for zero-downtime reindexing.

## API

`GET /v1/search`, `GET /v1/suggest`, `POST /v1/search/clicks`, `GET /v1/search/analytics/*`,
`/v1/search/synonyms[/{ruleId}]` — contract [`contracts/openapi/search.yaml`](../../../contracts/openapi/search.yaml).
Also `/healthz`, `/readyz` (pings Elasticsearch), `/metrics`, `/openapi.yaml`, `/docs` (Scalar), `/redoc`.
Auth is optional (public reads); when a bearer token is present it is verified against the identity JWKS.

## Configuration

`config/<APP_ENV>.yaml` (local, dev, uat, prod) with env overrides: `PORT`, `ES_URLS`, `ES_USERNAME`,
`ES_PASSWORD`, `ES_API_KEY`, `AUTH_ENABLED`, `AUTH_ISSUER_URL`, `AI_GATEWAY_URL`,
`OTEL_EXPORTER_OTLP_ENDPOINT_HOST`, `TRACE_SAMPLING`, `DOCS_ENABLED`, `MARKETS`, `KAFKA_BOOTSTRAP` (search and ads
events; empty = dropped and counted in `search_events_total`), `SYNONYMS_FILE`, `SYNONYMS_OVERWRITE`,
`SYNONYMS_MIGRATE_INDEX`, `ANALYTICS_ENABLED`, `ANALYTICS_FLUSH_INTERVAL`, `ANALYTICS_RETENTION_DAYS`,
`CLICK_DEDUPE_WINDOW`, `ADS_ENABLED`, `ADS_MIN_QUALITY`, `ADS_RESERVE_MINOR`, `ADS_TOKEN_KEY` (secret), `ADS_TOKEN_TTL`,
`ADS_CACHE_TTL`, `CONTENT_URL`, `AUTH_TOKEN_URL`, `SEARCH_CLIENT_ID`, `SEARCH_CLIENT_SECRET` (secret),
`SEARCH_CLIENT_SCOPES`. In `local` the service creates the index and alias on start; in every environment it seeds the
synonyms set and creates `search-analytics-<market>` (the Elasticsearch API key needs `create_index`, `write` and
`manage` on `search-analytics-*` and the `manage_search_synonyms` cluster privilege).

## Run and test

```bash
cd go/services/search
GOTOOLCHAIN=go1.27.2 go test ./...        # unit + Elasticsearch Testcontainers integration test
golangci-lint run --config ../../.golangci.yml ./...
APP_ENV=local go run ./cmd/search          # http://localhost:8201/docs
docker build -f services/search/Dockerfile -t search ../..   # from go/: distroless image (~46 MB)
```
