# indexer

Kafka → Elasticsearch projection of the product read model used by [search](../search). Go 1.27.1 · local port **8202**.

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
- Availability events update one `variant×node` quantity and recompute `available_qty` / `in_stock` for the product.
- **Ordering and idempotency:** every write is a Painless script guarded by a version (event `occurred-at` in
  microseconds, or the inventory position version). Redelivered or out-of-order events are no-ops, so the read
  model converges regardless of delivery order.
- **Failures:** transient errors retry with exponential backoff (4 attempts by default); poison events go to
  `at.indexer.dlq.events` so a single bad event never blocks a partition. `indexer_events_total{type,outcome}` counts
  every event.

## Configuration

`config/<APP_ENV>.yaml` + env: `KAFKA_BOOTSTRAP`, `KAFKA_GROUP`, `KAFKA_TOPICS`, `ES_URLS`, `ES_API_KEY` /
`ES_USERNAME` + `ES_PASSWORD`, `ES_REFRESH`, `CATALOG_URL`, `RETRY_ATTEMPTS`, `RETRY_BACKOFF_MS`, `MARKET`,
`OTEL_EXPORTER_OTLP_ENDPOINT_HOST`, `TRACE_SAMPLING`, `SYNONYMS_SET`, `SELLER_DIRECTORY_ENABLED`, `SELLER_URL`,
`AUTH_TOKEN_URL`, `INDEXER_CLIENT_ID` (`indexer-service`), `INDEXER_CLIENT_SECRET` (secret), `INDEXER_CLIENT_SCOPES`
(`sellers:directory`), `SELLER_DIRECTORY_PAGE_SIZE`, `SELLER_DIRECTORY_WARM_TIMEOUT`,
`SELLER_DIRECTORY_REFRESH_INTERVAL`. `sellerNames` in config seeds names the directory does not know (AfroTransact
Retail).

## Run and test

```bash
cd go/services/indexer
GOTOOLCHAIN=go1.27.1 go test ./...      # unit + Elasticsearch Testcontainers integration (version guards, stock sums)
APP_ENV=local go run ./cmd/indexer
```
