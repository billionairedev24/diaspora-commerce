# Local development stack

Everything AfroTransact needs to run on a laptop, in one compose project (`afrotransact`). Host ports follow
[PORTS.md](PORTS.md) exactly, so services started from the IDE (`SPRING_PROFILES_ACTIVE=local` / `APP_ENV=local`)
find the stack on `localhost` with their default config.

```bash
make up          # infrastructure + observability, waits until every container is healthy (~2-3 min first time)
make ps          # status / health
make logs SERVICE=kafka
make apps-up     # also build + run the containerised services (compose profile "apps": catalog, search)
make down        # stop, keep data
make reset       # stop and delete all volumes (fresh databases, topics, indices, dashboards state)
```

Requirements: Docker with Compose v2, about 5 GB of free RAM for the default profile (measured: ~4.3 GB in use).
Credentials below are **for local use only**.

## What runs

| Component | URL / address (host) | In-network address | Credentials |
|---|---|---|---|
| PostgreSQL 17 + PostGIS 3.5 | `localhost:5432` | `postgres:5432` | admin `postgres` / `postgres`; per service: user = password = db name |
| Valkey 8 (Redis API) | `localhost:6379` | `valkey:6379` | — |
| Kafka 4.1 (KRaft, single node) | `localhost:9092` | `kafka:29092` | PLAINTEXT |
| Kafka UI (kafbat) | http://localhost:8090 | | — |
| Elasticsearch 9.3 (security off) | http://localhost:9200 | `elasticsearch:9200` | — |
| Kibana 9.3 | http://localhost:5601 | | — |
| Temporal 1.28 (gRPC) / UI | `localhost:7233` / http://localhost:8088 | `temporal:7233` | namespace `afrotransact` |
| MinIO S3 / console | http://localhost:9000 / http://localhost:9001 | `minio:9000` | `minioadmin` / `minioadmin` |
| Mailpit SMTP / UI | `localhost:1025` / http://localhost:8025 | `mailpit:1025` | any SMTP auth accepted |
| OpenFGA (HTTP API) | http://localhost:8091 | `openfga:8080` (gRPC `openfga:8081`) | no auth (playground off) |
| OTel Collector OTLP gRPC / HTTP | `localhost:4317` / `localhost:4318` | `otel-collector:4317/4318` | — |
| Prometheus | http://localhost:9090 | `prometheus:9090` | — |
| Alertmanager | http://localhost:9093 | | alerts are e-mailed to Mailpit |
| Grafana | http://localhost:3200 | | `admin` / `admin` (anonymous = Viewer) |
| Loki | http://localhost:3300 | `loki:3100` | — |
| Tempo | http://localhost:3201 | `tempo:3200` | — |

Internal-only helpers: `postgres-exporter`, `elasticsearch-exporter`, `kafka-exporter`, `redis-exporter`, and the
one-shot init jobs below. If 5432 is already taken on your machine: `POSTGRES_HOST_PORT=15432 make up`.

### Created automatically

- **Databases** (`postgres/init-databases.sh`, first start of an empty volume): one database **and** one login role per
  service — identity, catalog, offers, inventory, orders, payments, seller, retail, fulfilment, bookings, trustops,
  taxreturns, content, ai, cart, geo, storefront, media, notifications, carriers, temporal (+ `temporal_visibility`),
  openfga. Password = name. `geo` has PostGIS + pg_trgm. A `monitoring` role (pg_monitor) feeds the exporter.
  Adding a service: append it to `SERVICES` in the script, then `make reset && make up`.
- **Kafka topics** (`kafka/topics.txt` → `kafka-init`): `at.<aggregate>.events` and `at.<aggregate>.events.dlq` for
  every aggregate (3 partitions, 7-day retention). Auto-create is also on. Append aggregates to `topics.txt`.
- **Buckets** (`minio/create-buckets.sh`): `media` (anonymous download), `labels`, `exports`, `kyc-evidence`
  (object lock, default GOVERNANCE retention 30 days, versioned).
- **Elasticsearch** (`elasticsearch/init.sh`): slow-log thresholds for `products*` indices and an `at-logs` ILM policy.
- **Temporal** namespace `afrotransact`.

MinIO image: upstream MinIO no longer publishes container images, so the stack uses the maintained community build
`pgsty/minio` (includes `mc`). Set `MINIO_IMAGE=...` to use another S3-compatible image.

## Running services

**From the IDE** (recommended): just start the service — defaults already point at `localhost`
(`jdbc:postgresql://localhost:5432/<db>`, `localhost:9092`, `http://localhost:9200`, OTLP `localhost:4318`).
Prometheus scrapes every service port from PORTS.md on `host.docker.internal`, so metrics show up in Grafana as soon
as the service runs. Traces go to Tempo through the collector.

**In containers** (`make apps-up`): the `apps` profile builds
`catalog` (`java/Dockerfile`, `SERVICE=catalog`) and `search` (`go/services/search/Dockerfile`) and wires them to the
compose hostnames (`SPRING_PROFILES_ACTIVE=local` / `APP_ENV=local` with env overrides). They publish the same host
ports as the IDE would (8101, 8201). A `log-shipper` collector tails their stdout into Loki (Linux only — Docker
Desktop does not expose `/var/lib/docker/containers`).

To add another service, copy a block in `docker-compose.yml` using the `x-java-service` / `x-go-service` templates
(a commented `orders` example is at the end of the services list) and set its PORTS.md host port.

## Observability

Configs live in [`infra/observability/`](../observability) and are mounted read-only:

- `otel-collector.yaml` — OTLP in; traces → **tail sampling** (always keep errors and traces slower than 500 ms,
  plus `TAIL_SAMPLING_BASELINE_PERCENT`% of the rest, default 100 locally) → Tempo; metrics → Prometheus remote
  write; logs → Loki (native OTLP).
- `prometheus.yml` + `rules/*.yaml` — scrape config (every service, every exporter) and alert/SLO rules.
  `alertmanager.yml` mails alerts to Mailpit; `ServiceDown` is muted locally because most services are not running.
- Grafana is provisioned with Prometheus, Loki, Tempo (trace ↔ logs by trace id, exemplars → traces, service map)
  and Alertmanager datasources, and the dashboards in folder **AfroTransact**: Platform overview (RED), JVM,
  Go runtime, Elasticsearch, Kafka consumer lag, Inventory reconciliation drift, Checkout & business SLOs.
- Dashboards and SLO rules are generated: `make dashboards`, `make rules` (also emits the Kubernetes
  ConfigMaps / PrometheusRules under `infra/k8s/platform/observability/`). Runbooks: [`docs/runbooks/`](../../docs/runbooks).

Quick checks: Grafana → Explore → Tempo → Search; Loki `{service_name="search"}`;
Prometheus → Status → Targets (services not running show as down — expected).

### Metrics contract

Dashboards and alerts expect these series (labels `service`, `env` are added by the scrape config):

| Metric | Type | Emitted by | Used for |
|---|---|---|---|
| `http_server_requests_seconds_{count,sum,bucket}{status,uri}` | histogram | every Java service (Micrometer; enable `management.metrics.distribution.percentiles-histogram.http.server.requests=true`) | RED dashboards, HighErrorRate/HighLatency, canary analysis |
| `http_server_requests_total{status,route}`, `http_server_request_duration_seconds_bucket{route}` | counter / histogram | every Go service (`go/pkg/httpserver`) | same; search latency SLO (bucket `le="0.15"`) |
| `checkout_attempts_total{outcome="success\|failure\|declined"}`, `checkout_duration_seconds` | counter / histogram | orders | checkout SLO 99.9% |
| `payments_webhook_lag_seconds` (bucket at 60 s) | histogram | payments | webhook lag SLO |
| `payouts_completed_total{on_time="true\|false"}` | counter | payments | payout on-time SLO 99.5% |
| `notifications_delivery_duration_seconds{channel}` (bucket at 30 s) | histogram | notifications | notification SLO |
| `indexer_event_lag_seconds` | gauge | indexer | IndexerLagHigh (> 60 s) |
| `inventory_reconciliation_drift{fc,sku}`, `inventory_reconciliation_last_run_timestamp_seconds`, `inventory_reservations_total{outcome}` | gauge / gauge / counter | inventory | drift dashboard + alerts |
| `outbox_pending_events` | gauge | platform-events (Java) | OutboxBacklogGrowing |

## Troubleshooting

- **Elasticsearch exits or stays red** on small disks: the stack already sets
  `cluster.routing.allocation.disk.threshold_enabled=false`. On Linux hosts also run
  `sudo sysctl -w vm.max_map_count=262144`.
- **Port already in use**: something else is bound to a PORTS.md port; stop it (only Postgres has an override,
  `POSTGRES_HOST_PORT`).
- **Init scripts did not run**: they only run on an empty volume — `make reset && make up`.
