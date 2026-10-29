# Search providers: Elasticsearch or Meilisearch

Search-service (queries) and the indexer (writes) run on **Elasticsearch 9** (the default) or **Meilisearch**. One variable
picks the engine for both, so they always agree:

```bash
SEARCH_PROVIDER=elasticsearch   # default
SEARCH_PROVIDER=meilisearch     # + MEILISEARCH_URL, MEILISEARCH_API_KEY (the key is a secret: env only)
```

Locally put it in `.local/secrets.env`. `make up` then starts Meilisearch (`http://localhost:7700`, one container, ~1 GB)
**instead of** Elasticsearch and its exporter; `tools/dev/local.sh restart search indexer` applies a change. The
local key is `local-meili-master-key` (`config/local.yaml`). Outside local, set `MEILISEARCH_URL` and
`MEILISEARCH_API_KEY` on both services.

**Switching engines starts with an empty index.** Re-run the indexer backfill (and the embedding backfill) after
switching; nothing is copied between engines.

## How the two compare

| | Elasticsearch | Meilisearch |
|---|---|---|
| Index per market | `products-<market>` alias, mapping in `go/pkg/searchindex/mapping.json` | `products-<market>` index, settings in `searchindex.MeiliSettings` |
| Text match | BM25 over title, brand, category, description; every word must match; typos AUTO:3,6 with the first letter fixed; search-as-you-type | every word must match, last word as a prefix; typos for words of 4-7 letters (one) and 8+ (two) (Meilisearch cannot fix the first letter, so thresholds are a letter higher) |
| Ranking | text score × commercial multipliers (in stock ×3, fulfilled ×1.2, rating × reviews, fast delivery ×1.05) | ordered rules: explicit sort, words, **in stock first**, typo, proximity, attribute, exactness, then `rank_boost` (fulfilled, rating × reviews, fast delivery, computed by the indexer). Text relevance outranks the boosts here; in Elasticsearch a big boost can outrank a better text match |
| Hybrid (semantic) | BM25 + kNN fused by reciprocal rank in search-service | the same fusion: a lexical query and a vector-only query in one multi-search (user-provided embedder, 1536 dims) |
| Facets | terms and range aggregations | term facets; the range buckets (rating, price, delivery) are counted with one filtered query each |
| Synonyms | a synonyms set, edited from Staff | the rules live in an index `synonym-rules` and are pushed to every market index's settings on each change |
| Writes | scripted, version-guarded upserts | read, apply the same rules (version guard, availability totals, preserved fields), write back, wait for the task |
| Analytics (top queries, zero results, CTR) | scripted increments + aggregations | counters merged on write, reports aggregated in memory; two replicas flushing the same query at the same moment can lose a count |
| Geo | `geo_distance` on `ships_from` | `_geoRadius` on `_geo` |

Relevance judgements (`search/internal/relevance`) were tuned on Elasticsearch; run a few real queries after switching
and adjust `searchindex.MeiliSettings` (ranking rules, typo thresholds) if the order is not what you want.

## Tests

The Meilisearch adapters have tests against a real instance; they skip unless `MEILISEARCH_TEST_URL` is set:

```bash
docker run --rm -p 7700:7700 -e MEILI_MASTER_KEY=testkey getmeili/meilisearch:v1.12
MEILISEARCH_TEST_URL=http://127.0.0.1:7700 MEILISEARCH_TEST_KEY=testkey \
  go test ./go/services/search/internal/adapter/meilisearch ./go/services/indexer/internal/adapter/meilisearch
```

Meilisearch before 1.13 keeps its vector store behind an experimental switch; the index setup turns it on when needed.

## Adding another engine

Implement `port.Index`, `port.IndexAdmin` and `port.AnalyticsStore` (search) and `port.ProductIndex` (indexer), then add a
case to `newBackend` in each service's `cmd/<service>/backend.go`.
