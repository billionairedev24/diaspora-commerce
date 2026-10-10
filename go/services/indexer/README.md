# indexer

Kafka → Elasticsearch projection of the product read model used by [search](../search). Go 1.27.1 · local port **8202**.

- Consumes `catalog.product.created|approved|rejected|paused|resumed` (topic `at.product.events`) and
  `inventory.availability.changed` (`at.inventory.events`) as consumer group `indexer`; offsets are committed only after
  a batch is projected.
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
`OTEL_EXPORTER_OTLP_ENDPOINT_HOST`, `TRACE_SAMPLING`. Seller display names come from config until the seller
service publishes `seller.org.updated`.

## Run and test

```bash
cd go/services/indexer
GOTOOLCHAIN=go1.27.1 go test ./...      # unit + Elasticsearch Testcontainers integration (version guards, stock sums)
APP_ENV=local go run ./cmd/indexer
```
