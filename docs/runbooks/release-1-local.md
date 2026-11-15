# Release 1: running locally

One section per deployable. One section per language: the Java one first, then the Go one.

## Java: afrotransact-core (one JVM, every Java module)

`afrotransact-core` is every Java service as a module of one JVM (ADR 0011). This page is how to run it on your machine
against the local Docker infrastructure, how its database works, and how to take one module out. Environment variable
**names** only; values come from your shell or `.local/secrets.env`.

### Start

```bash
make up-infra            # Postgres 17 (+PostGIS), Valkey, Kafka, Temporal, ... (as always)
make core-db             # once: database `core` with ONE SCHEMA and ONE ROLE per module (idempotent, safe to repeat)
make core-run            # builds the jar when missing (CORE_REBUILD=1 forces it) and starts core with the `local` profile
```

`make core-build` only builds (`CORE_MVN_ARGS=-DnoAi` leaves ai-gateway and mcp-server out of the jar). Needs JDK 25.
The Go services, the web apps and the mobile apps start as usual (`make up` / `make dev-up` start the standalone Java services; do
**not** run those and core at the same time, they use the same ports).

#### Ports

| What | Port |
|---|---|
| Core, every module's existing paths | **8120** (`PORT`) |
| The old per-service ports, served by the same JVM (local profile only) | 8100 identity, 8101 catalog, 8102 offers, 8103 inventory, 8104 orders, 8105 payments, 8106 seller, 8107 retail, 8108 fulfilment, 8109 bookings, 8110 trust-ops, 8111 tax-returns, 8112 content, 8113 ai-gateway, 8114 mcp-server, 8115 delivery-runs |
| Health | `http://localhost:8120/actuator/health`, readiness lists every module, `/actuator/health/<module>` adds its database check |

The old ports matter for two reasons: gateway-bff, the Go services and your scripts keep working unchanged, and three routes exist
in two modules (`POST /v1/returns` inventory and tax-returns, `POST /v1/receipts` inventory and retail,
`POST /v1/reconciliation/runs` inventory and payments). A request on a module's own port (or under `/modules/<name>/...` on
the core port) goes to that module; a request on the core port without prefix goes to the module mounted first (see the
architecture doc, "Route collisions").

Modules that still reach each other over HTTP (every edge except catalog and tax-returns, see the status table in
`docs/engineering/release-1-architecture.md`) use their old `http://localhost:81xx` URLs; those ports are now core, so nothing needs
re-configuring.

#### Database: one database, one schema per module

`at.modules.<module>.database.mode` is `schema` (default in core) or `database`.

* `schema`: all modules use `CORE_DB_URL` (default `jdbc:postgresql://localhost:5432/core`). Each module has its own role
  (`<module>` with `-` as `_`: `catalog`, `tax_returns`, `trust_ops`, `ai_gateway`, `delivery_runs` ...; password env
  `<MODULE>_DB_PASSWORD`, local default = role name), owns exactly one schema, has `search_path` pinned (role setting and every
  pooled connection) and keeps its own `flyway_schema_history` inside its schema. Migrations are the ones the services have.
* `database`: the module uses its own database as the standalone service does; set `<MODULE>_DB_URL`, `<MODULE>_DB_USER`,
  `<MODULE>_DB_PASSWORD`. Mix freely: a module in `database` mode next to the others in `schema` mode.
* Temporal keeps its own databases (`init-databases.sh`). Extensions (`postgis`, `btree_gist`, `pg_trgm`) are created once in
  `public` by `make core-db` (module roles cannot create extensions; the migrations use `create extension if not exists`).
* Locally the `local` profile also lets core create missing roles and schemas itself at start (`CORE_DB_ADMIN_URL`,
  `CORE_DB_ADMIN_USER`, `CORE_DB_ADMIN_PASSWORD`, default `postgres`/`postgres`); never set these in uat or prod.

Pools: 4 connections per module (orders, payments, catalog 8), minimum idle 0. Enabled by default that is 13 modules and at most
**64 connections** (limit on Layerbase: 500). Override with `<MODULE>_DB_POOL_SIZE`.

#### Modules on and off

| Module | Default | Switch |
|---|---|---|
| identity, catalog, tax-returns, offers, inventory, content, seller, orders, payments, bookings, fulfilment, trust-ops, ai-gateway, mcp-server | on | `MODULE_<NAME>_ENABLED=false` (`MODULE_TRUST_OPS_ENABLED`, `MODULE_AI_GATEWAY_ENABLED`, ...) |
| retail, delivery-runs | **off** (not in the launch set) | `MODULE_RETAIL_ENABLED=true`, `MODULE_DELIVERY_RUNS_ENABLED=true` |

A disabled module is not scanned, opens no connection and has no health component. Core fails at start with a message naming both
modules when an `in-process` module needs one that is off (catalog and tax-returns: `AT_CATALOG_MODE`, `AT_TAXRETURNS_MODE`:
`in-process` | `http`).

#### Detach one module (run it as its own process again)

1. In core: `MODULE_<NAME>_ENABLED=false` and restart. Its old port stops being served by core (the others keep working).
2. Start the module standalone as before (`make dev-restart SERVICE=<name>`, or the service jar): same port, same config. Give it
   its data: either `<NAME>_DB_URL` pointing at the `core` database with the role `<name>` and its schema (standalone services read
   `DB_URL`, `DB_USER`, `DB_PASSWORD`; add `?currentSchema=<schema>` to the URL), or keep the module in `database` mode from the
   start. Its Kafka consumer group is unchanged, so no events are replayed or lost.
3. Modules that call it in-process (catalog <-> tax-returns only) need `AT_CATALOG_MODE=http` / `AT_TAXRETURNS_MODE=http`.
4. ai-gateway, mcp-server: also leave them out of the build with `-DnoAi` (`CORE_MVN_ARGS=-DnoAi make core-build`).

#### What to know

* Orders needs Temporal (`TEMPORAL_TARGET`, `TEMPORAL_START_WORKERS=false` to run without workers) and Valkey (`REDIS_URL`).
  Kafka is `KAFKA_BOOTSTRAP`. Provider keys (Stripe, OpenRouter, ...) are read from `.local/secrets.env` as for the services.
* `at.security.enabled=false` in the `local` profile, as for the services. With security on, the shared chain keeps every module's
  anonymous permits (seller storefront reads and webhooks, content visitor forms, identity sign-up, the MCP metadata).
* Not verified here: see "Status and what was not verified" in `docs/engineering/release-1-architecture.md`.

## Go: afrotransact-go (one process, every Go module)

`go/apps/afrotransact-go` runs the Go services as modules of one process (ADR 0011, `docs/engineering/release-1-architecture.md`
section 7). Each service still builds and runs alone from `go/services/<svc>/cmd/<svc>` on its own port (see `infra/local/PORTS.md`);
nothing below changes that.

### Run it

```bash
make go-monolith-run                 # builds go/bin/afrotransact-go and runs it on :8250
APP_ENV=local AFROTRANSACT_GO_PORT=8250 go/bin/afrotransact-go
make go-monolith-image               # container image (go/Dockerfile.afrotransact-go)
```

One listener, port **8250** (`AFROTRANSACT_GO_PORT`, then `PORT`; the image sets `PORT=8080`). `MANAGEMENT_ADDR` (for example `:9090`)
adds a private listener with probes and metrics only. In `uat`/`prod` `/metrics` is never served on the public port.

Local default: the launch modules run (cart, geo, search, indexer, notifications, media, gateway-bff). **promise,
carrier-gateway and authz-sync are off** and are switched on with `AT_MODULES_<NAME>_ENABLED=true`. The modules need the same backing
services as when run alone (Redis/Valkey, PostgreSQL, Kafka, Meilisearch, Postgres/PostGIS for geo): `make dev-up` brings them up.

### Environment variable names

| Name | Meaning |
|---|---|
| `APP_ENV` | `local`, `dev`, `uat`, `prod` (one value for every module) |
| `AFROTRANSACT_GO_PORT`, `PORT`, `MANAGEMENT_ADDR` | the listener, the private listener |
| `AT_MODULES_<NAME>_ENABLED` | `true`/`false`; `<NAME>` is the module in upper case with `_` (`CART`, `GATEWAY_BFF`, `CARRIER_GATEWAY`, `AUTHZ_SYNC`). The Helm chart's `MODULE_<NAME>_ENABLED` is read as well; `AT_MODULES_` wins |
| `AT_MODULES_<NAME>_MODE` | `in-process` (default) or `http`: how the OTHER modules reach this one (see below) |
| `AT_MODULES_<NAME>_MOUNT` | `flat` (the module's paths on the shared port), `prefixed` (only under `/modules/<name>/`) or `internal` (in-process callers only) |
| `<MODULE>_<NAME>`, then `<NAME>` | every setting of a module: `CART_DB_URL` is tried before `DB_URL`, `GATEWAY_BFF_REDIS_URL` before `REDIS_URL`. A name no module prefix defines (`KAFKA_BOOTSTRAP`) is shared |
| `<MODULE>_DB_MAX_CONNS` | pool maximum for the module's database (added to `DB_URL` as `pool_max_conns`; each module also caps itself at 3) |
| `AT_GO_DB_URL` | optional: ONE database for the Go modules; a module without its own `<MODULE>_DB_URL`/`DB_URL` gets this URL with `search_path=<schema>` (cart `cart`, geo `geo,public`, media `media`, notifications `notifications`, carrier-gateway `carriers`) |

Secrets are the same names the services read today (`OIDC_CLIENT_SECRET`, `SESSION_SIGNING_KEY`, `<X>_CLIENT_SECRET`, ...); never put values
in files.

### What is where

- `GET /healthz` and `GET /readyz` on the port: JSON that names every module; `/readyz` is 503 and names the failing check. `GET /metrics`: one
  Prometheus exposition with every module's registry, each series labelled `module="<name>"` and `service="<name>"` (the old service label).
- Per module: `/modules/<name>/healthz`, `/readyz`, `/metrics`, and the module's own `/openapi.yaml`, `/docs`, `/redoc` under the same prefix.
- Routes: gateway-bff (the edge) keeps its paths at the root, so do geo, notifications, media. **cart and search are `prefixed`** because they document
  the same paths as gateway-bff's facade (`/v1/cart`, `/v1/search`), and carrier-gateway because it shares `POST /v1/addresses/validate` with geo and
  `/v1/webhooks/*` with notifications. gateway-bff reaches them in-process; any other caller uses `http://<host>:8250/modules/cart` as the base URL.
  Two modules claiming the same flat route stop the start and name both (`AT_MODULES_<NAME>_MOUNT=prefixed` resolves it).
- Calls between modules and from gateway-bff do not use a socket: `SEARCH_URL`, `CART_URL`, `GEO_URL`, `PROMISE_URL`, `MEDIA_URL`, `NOTIFICATIONS_URL`,
  `CARRIER_URL` resolve to `http://<module>.inproc`, a `RoundTripper` that serves the request through the module's own handler. The bearer token is still
  sent, so the callee's authentication middleware runs. `GATEWAY_BFF_SEARCH_URL=http://...` forces HTTP for that one edge.

When promise is off, gateway-bff must not call it: set `PROMISE_DISABLED=true` (and `CARRIER_DISABLED=true` for carrier-gateway), as the separate deployment does today.

### Detach one module

1. Run it alone: `AT_MODULES_MEDIA_ENABLED=false` in afrotransact-go, and start `go/services/media/cmd/media` (or its own Deployment).
2. Point the callers at it: nothing else is needed when `MEDIA_URL=http://media:8207` is set, because in-process URLs only apply while the module runs inside.
3. To keep the module inside but make the others call it over HTTP (a step before detaching, or to test the network path): `AT_MODULES_MEDIA_MODE=http`.

### Database

Default: each Go service keeps its own database (`cart`, `geo`, `media`, `notifications`, `carriers`) through its `<MODULE>_DB_URL`. Optional single database
(`go`): schema and role per module (`infra/ops/postgres/release-1-bootstrap.sh`, `make db-release-1` locally), `search_path` pinned on the role and in
the URL, PostGIS created by the admin in `public` before deploy. The embedded migrations are idempotent SQL on unqualified names and notifications' `schema_migrations`
is unqualified too, so each schema keeps its own history with no code change. Verified with a real PostgreSQL: **not yet** (see the status table below).

### Tests

`cd go/apps/afrotransact-go && go test ./...` boots all ten modules with fake modules (real runtime, fake routes), plus the real promise module, and checks the
real OpenAPI route sets for collisions. Every service's own `go test ./...` is unchanged.
