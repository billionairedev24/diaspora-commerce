# indexer

Kafka → Elasticsearch projection of the product read model used by [search](../search). Go 1.27.2 · local port **8202**.

- Consumes `catalog.product.created|approved|rejected|paused|resumed|hidden|restored` (topic `at.product.events`),
  `inventory.availability.changed` (`at.inventory.events`) and `seller.org.approved|updated` (`at.seller-org.events`)
  as consumer group `indexer`; offsets are committed only after a batch is projected.
- Product events with `sellerHidden: true` (seller-service hid the owner's listings) are indexed with status
  `SELLER_HIDDEN`, so search drops them; `catalog.product.restored` restores the product's own status.
- **Seller directory (S213):** before consuming, the indexer warms its seller names from seller-service's
  `GET /v1/orgs/directory` (every page; client-credentials token of client `indexer-service`, scope
  `sellers:directory`), retrying for up to 30 s (`SELLER_DIRECTORY_WARM_TIMEOUT`), and re-warms every 30 min.
  `seller.org.*` events rename `seller_name` on every document of the organisation and update the directory (an event
  processed while a refresh is in flight wins over the older snapshot). An organisation still unknown is looked up once
  with `GET /v1/orgs/{id}/public` (misses cached 5 min). So a restart never changes indexed seller names; if seller is
  down, unknown sellers keep the name already indexed instead of the fallback.
- New product indices reference the `diaspora-synonyms` synonyms set (created empty when missing; search-service seeds
  and curates it), so synonym edits apply without reindexing.
- Product events become one document per product page (`searchindex.ProductDocument`): department and path resolved
  from catalog-service's category tree (cached), seller display name, fulfilment (`AFROTRANSACT` for AfroTransact Retail,
  `SELLER` otherwise), lowest variant price, variant ATINs and status. Only `LIVE` documents are searchable.
- **Ratings (S324):** `catalog.product.rating_changed` (`at.product.events`, published by catalog-service when a
  product review is published, edited, removed or deleted) sets the document's `rating` (average stars, two decimals)
  and `review_count` and, on Meilisearch, the `rank_boost` that depends on them. Product upserts never touch them, so a
  rating survives every catalog event; writes are version-guarded by the event time (`rating_version`) and an event for
  a product that is not indexed yet is logged and skipped. This is what the search rating filter and sort and the
  listing cards read.
- Availability events update one `variant×node` quantity and recompute `available_qty` / `in_stock` for the product
  (search turns them into the low-stock / out-of-stock signals of S038).
- **Embeddings (S036):** each product document carries an `embedding` (1536-dim `dense_vector`) of its text
  (`searchindex.EmbeddingText`: title, brand, category path) from ai-gateway `POST /v1/embeddings` (purpose
  `search-document`), called with the `indexer-service` client's token requested with scope `ai:use` (the client
  needs that scope in identity). Vectors are cached by text, so the status events of one product embed once. An AI
  failure never fails the event: the document is written without a new vector (an earlier one is kept) and the
  **backfill** (`EmbeddingBackfill`, at start and every `EMBEDDINGS_BACKFILL_INTERVAL`, 15 min) embeds every LIVE
  document that has none, with a version guard so a newer product event always wins. `indexer_embeddings_total{outcome}`
  counts calls.
- **Ordering and idempotency:** every write is a Painless script guarded by a version (event `occurred-at` in
  microseconds, or the inventory position version). Redelivered or out-of-order events are no-ops, so the read
  model converges regardless of delivery order.
- **Failures:** transient errors retry with exponential backoff (4 attempts by default); poison events go to
  `at.indexer.dlq.events` so a single bad event never blocks a partition. `indexer_events_total{type,outcome}` counts
  every event.

## Rebuilding the index: `indexer reindex`

The index is filled from Kafka, so a new engine (`SEARCH_PROVIDER`), a lost index or expired topics (7 days) leave it
empty. `indexer reindex` rebuilds it from the sources of truth instead of Kafka history, using this service's own
configuration and environment (`SEARCH_PROVIDER`, `MEILISEARCH_URL`/`MEILISEARCH_API_KEY` or `ES_*`, `CATALOG_URL`,
`OFFERS_URL`, `INVENTORY_URL`, `SELLER_URL`, `AUTH_TOKEN_URL`, `INDEXER_CLIENT_SECRET`, `REINDEX_SCOPES` (`catalog:read`),
`REINDEX_BATCH`, `REINDEX_CONCURRENCY`):

```bash
make reindex-search                      # local stack (builds the command, uses .local/secrets.env); ARGS="--dry-run" counts only
indexer reindex --dry-run                # the same binary and image as the consumer; no arguments = the Kafka consumer
kubectl -n <ns> create job indexer-reindex-$(date +%s) --from=cronjob/indexer-reindex   # chart value reindex.enabled=true
infra/ovh-vps/deploy.sh reindex-search [flags]                                           # OVH server / workflow action
```

Flags: `--market` (default the configured `MARKET`; comma separated), `--batch` (100), `--concurrency` (4), `--dry-run`,
`--since` (`36h`, a date or RFC 3339), `--after` (resume), `--status` (`LIVE`; add `PAUSED,TAKEN_DOWN` to refresh documents
that left LIVE), `--backfill-embeddings`.

How: catalog's `GET /v1/search-export/products` (keyset by id; client `indexer-service`, scope `catalog:read`) gives each
product as the `catalog.product.*` payload plus its rating; offers' public `GET /v1/public/buy-boxes` and inventory's
public `GET /v1/availability` give the buy box and per-node stock of its variants. `app.Reindexer` turns them into the
events the live path would have received and feeds them to the same `Projector` (so there is one projection, not two),
then the engine adapter's version guard applies: a product is dated with the time its state last changed, buy box and
stock with the read time minus 30 s, so a live indexer running at the same time always wins with anything newer, and a
re-run re-applies the same state. Seller names come from the seller directory warmed first, like at start.

Ensures the index and its settings exist first (not in a dry run), prints progress per page and a final per-market
summary (`read`, `written`, `planned`, `skipped`, `failed`), and exits non-zero if anything failed or the run stopped early
(then it prints the `--after` cursor to continue from). It writes documents without vectors; the embedding backfill below
fills them (within `EMBEDDINGS_BACKFILL_INTERVAL`, or at once with `--backfill-embeddings`). It does not delete documents
of products that no longer exist. Full runbook: [docs/runbooks/kafka-replay.md](../../../docs/runbooks/kafka-replay.md) section 5.1.

## Configuration

`config/<APP_ENV>.yaml` + env: `KAFKA_BOOTSTRAP`, `KAFKA_GROUP`, `KAFKA_TOPICS`, `ES_URLS`, `ES_API_KEY` /
`ES_USERNAME` + `ES_PASSWORD`, `ES_REFRESH`, `CATALOG_URL`, `RETRY_ATTEMPTS`, `RETRY_BACKOFF_MS`, `MARKET`,
`OTEL_EXPORTER_OTLP_ENDPOINT_HOST`, `TRACE_SAMPLING`, `SYNONYMS_SET`, `SELLER_DIRECTORY_ENABLED`, `SELLER_URL`,
`AUTH_TOKEN_URL`, `INDEXER_CLIENT_ID` (`indexer-service`), `INDEXER_CLIENT_SECRET` (secret), `INDEXER_CLIENT_SCOPES`
(`sellers:directory`), `SELLER_DIRECTORY_PAGE_SIZE`, `SELLER_DIRECTORY_WARM_TIMEOUT`,
`SELLER_DIRECTORY_REFRESH_INTERVAL`, `EMBEDDINGS_ENABLED`, `AI_GATEWAY_URL`, `EMBEDDINGS_TIMEOUT`,
`EMBEDDINGS_SCOPES` (`ai:use`), `EMBEDDINGS_CACHE_SIZE`, `EMBEDDINGS_BACKFILL_INTERVAL`, `EMBEDDINGS_BACKFILL_BATCH`. `sellerNames` in config seeds names the directory does not know (AfroTransact
Retail).

## Run and test

```bash
cd go/services/indexer
GOTOOLCHAIN=go1.27.2 go test ./...      # unit (fake embedder) + Elasticsearch Testcontainers integration (version
                                        # guards, stock sums, embeddings kept across upserts, backfill guard)
APP_ENV=local go run ./cmd/indexer
APP_ENV=local go run ./cmd/indexer reindex --dry-run

# Meilisearch tests (adapter, and the reindex against the event path), skipped unless set:
MEILISEARCH_TEST_URL=http://127.0.0.1:7700 MEILISEARCH_TEST_KEY=testkey GOTOOLCHAIN=go1.27.2 go test ./...
```
