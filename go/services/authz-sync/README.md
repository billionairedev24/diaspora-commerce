# authz-sync

Kafka → OpenFGA projection of who belongs to which organisation, and which staff hold which platform roles (S022,
[ADR 0001](../../../docs/adr/0001-openfga-relationship-authorisation.md)). Go 1.27.2 · local port **8210**.

- Consumes `identity.role.granted|revoked` (topic `at.identity.events`) and `seller.org.approved|updated`
  (`at.seller-org.events`) as consumer group `authz-sync`. Offsets are committed only after the tuples are written.
- **Organisation roles** (`orgId` set) become `user:<userId> <relation> organization:<orgId>`:
  `seller.owner`/`provider.owner` → `owner`, `seller.admin` → `admin`, `provider.manager` → `manager`,
  `seller.catalog` → `catalog`, `seller.fulfilment` → `fulfilment`, `seller.finance`/`provider.finance` → `finance`,
  `seller.ads` → `ads`, `seller.support` → `support`, `provider.practitioner` → `practitioner`.
- **Platform roles** (no `orgId`) become relations on `platform:afrotransact` — `platform.customer_support` →
  `support`, `platform.catalog_manager` → `catalog_manager`, `platform.finance` → `finance`, `platform.trust_safety` →
  `trust_safety`, operations roles → `operations`, `platform.marketing` → `marketing`, `platform.auditor` → `auditor`,
  `platform.super_admin` → `super_admin`. A payload `market` (not published by identity yet) scopes the grant to
  `market:<code>` instead. `platform.retail_buyer` becomes `catalog` on AfroTransact Retail's organisation
  (`RETAIL_ORG_ID`). Drivers, developers and other roles are not mirrored.
- **Organisations** get `platform`, their `market` (country as content's market code; older market tuples are
  removed) and the capability flags of their kind: `sells_products` (SELLER), `offers_services` (PROVIDER), both
  (BOTH), as `user:*` and `client:*` wildcards; flags of other kinds are removed.
- **Idempotent and ordered:** each event is one atomic OpenFGA write with `on_duplicate` / `on_missing: ignore`.
  Identity events are keyed by user and seller-org events by organisation, so per-key order holds and a replay from
  the start of the topics converges to the same tuples.
- **Failures:** OpenFGA outages are retried with capped backoff (250 ms → 30 s) for as long as it takes — a
  revocation is never dropped. Malformed events and tuples OpenFGA rejects (4xx) go to `at.authz-sync.dlq.events`.
  `authz_sync_events_total{type,outcome}` counts every attempt.
- **Store and model:** at start-up it selects the store named `afrotransact` (creating it when missing) and, with
  `manageModel`, writes the embedded `model/model.json` when the store's latest model differs (ids and empty fields
  ignored). `model/model.json` is a copy of `contracts/openfga/model.json`; `TestEmbeddedModelMatchesContract` fails
  when they drift. Java services look up the same store by name and check against its latest model.
- **Not here:** resource ownership (offer → organisation, booking → organisation, …) stays in each owning service's
  database and is passed to OpenFGA as contextual tuples on every check; services that need persisted resource tuples
  (for ListObjects) write them with platform-security's `RelationshipWriter` after commit.

## Configuration

`config/<APP_ENV>.yaml` + env: `KAFKA_BOOTSTRAP`, `KAFKA_GROUP`, `KAFKA_TOPICS`, `OPENFGA_API_URL`,
`OPENFGA_API_TOKEN` (secret: one of OpenFGA's pre-shared keys; empty locally), `OPENFGA_STORE_NAME`,
`OPENFGA_STORE_ID` (pin a store instead of looking it up), `OPENFGA_TIMEOUT`, `OPENFGA_MANAGE_MODEL`,
`RETAIL_ORG_ID`, `SERVICE_CLIENTS` (comma-separated), `RETRY_BACKOFF_MS`, `RETRY_MAX_BACKOFF_MS`, `OTEL_EXPORTER_OTLP_ENDPOINT_HOST`, `TRACE_SAMPLING`.

## Service-client tuples (S266)

On start, after the model is deployed, authz-sync writes `client:<id> service platform:afrotransact` for every id in
`serviceClients` (identity's first-party `client_credentials` clients), so a service-to-service call passes
relationship checks once `FGA_ENFORCE=true`. The write is idempotent. A test keeps each environment's list equal to
the clients identity registers: add a new first-party service client to both.

## Rebuild every tuple

Tuples are a projection. To rebuild a store (new environment, model incompatible with old tuples): point
`OPENFGA_STORE_NAME` at a fresh store, reset the consumer group to the earliest offsets and restart; switch services'
`FGA_STORE_NAME` once the group has caught up. This relies on topic retention covering every membership; if
`at.identity.events` is time-limited, backfill from identity's `org_membership` table first (see
`docs/engineering/authorisation.md`).

## Run and test

```bash
cd go/services/authz-sync
GOTOOLCHAIN=go1.27.2 go test ./...      # unit + OpenFGA Testcontainers integration (local image openfga/openfga:v1.10.0)
APP_ENV=local go run ./cmd/authz-sync    # needs docker compose's Kafka (:9092) and OpenFGA (:8091)
```
