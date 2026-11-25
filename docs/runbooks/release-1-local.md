# Release 1: running locally

## Start everything: one command

```bash
make up        # everything, then prints every link
make down      # stops everything make up started (data is kept; make reset wipes the Docker volumes)
```

On this branch `make up` (and `make dev-up`, the same path) starts the whole local stack with the two deployables instead of the
16 Java and 10 Go services (`STACK=monolith`, the default here; `STACK=services` runs the old per-service processes as on main).
In this order, stopping with one actionable message at the first problem:

1. **Preflight**: JDK 25, the Go 1.27 toolchain (any Go 1.21+ fetches the pinned 1.27.4 itself), Docker running, and the ports free:
   8120, 8100-8115, 8200 (8250 is only a note) and the web ports (those move to the next free port by themselves).
   `make preflight` runs only this.
2. **Infrastructure**, unchanged: your own Postgres on `localhost:5432` when something other than this stack's container listens there
   (`PG_EXTERNAL=0|1` to force, `PG_SUPERUSER` / `PGPASSWORD` for the superuser), otherwise the Docker Postgres; Valkey, Meilisearch
   (or Elasticsearch with `SEARCH_PROVIDER=elasticsearch`), Kafka and its topics, Temporal, MinIO, Mailpit, OpenFGA, and Grafana,
   Prometheus, Loki and Tempo unless `OBS=0`.
3. **The `core` database** (`infra/local/postgres/provision-core.sql`, idempotent) on that same Postgres with the same superuser
   detection; `CORE_DB_URL` and `CORE_DB_ADMIN_URL` / `_USER` / `_PASSWORD` are exported for core. `make core-db` does only this step.
   If it cannot, it says why (no Postgres running, no superuser, no PostGIS) and what to run. `CORE_DB_PROVISION=0` skips it for a
   database you manage yourself. `GO_DB=single` also provisions the optional single `go` database for the Go modules (default: the
   Go services keep their own databases, as before).
4. **afrotransact-core**: builds `java/apps/core/target/afrotransact-core.jar` when it is missing or any file under `java/` is newer
   (`REBUILD=1` forces it, `CORE_MVN_ARGS=-DnoAi` leaves the AI modules out), runs a copy as `.local/bin/core.jar` with the `local`
   profile, `retail` and `delivery-runs` switched on as in the old stack, and waits for `http://localhost:8120/actuator/health/readiness`.
   JVM options: `CORE_JAVA_OPTS` (default `-Xmx1536m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`). Core keeps its own small pools: the
   old per-service pool settings are not applied.
5. **The search engine** is awaited (`:7700/health`, or Elasticsearch per `SEARCH_PROVIDER`), then **afrotransact-go** is built to
   `.local/bin/afrotransact-go` and started on **:8200** (`AFROTRANSACT_GO_PORT` overrides), waiting for `/readyz`. Port 8200 is where
   gateway-bff always was locally, so the web apps' `BFF_ORIGIN=http://localhost:8200` and the mobile apps' BFF URL are unchanged;
   the web apps follow `AFROTRANSACT_GO_PORT` when you set it. promise and carrier-gateway run too (they did in the old stack;
   `AT_MODULES_PROMISE_ENABLED=false` turns one off).
6. Web apps, docs portal, Storybook, mobile apps, demo data (`SEED=0` skips it) and the links, exactly as before. The links print
   `afrotransact-core` :8120 and `afrotransact-go` :8200 and the old-ports note.

`SERVICES="geo identity" make up` still selects: a Java name starts core, a Go name starts afrotransact-go (the old names are modules
of them now); `WEB=0`, `DOCS=0`, `STORYBOOK=0`, `MOBILE=0`, `MOBILE_HOST=lan`, `DEV=1`, `OBS=0` work as before. `DRYRUN=1 make up` prints
the plan and the preflight and starts nothing.

Everyday commands, with the names `core` and `afrotransact-go` (an old name such as `orders` or `search` maps to the deployable that
holds it):

```bash
make status                          # health of both deployables, web apps and containers
make logs SERVICE=core               # .local/logs/core.log  (afrotransact-go, web-storefront, ... likewise)
make restart SERVICE=core            # rebuild if needed and restart one deployable (re-reads .local/secrets.env)
make tax-gate-sweep                  # unchanged: catalog's old port :8101 is served by core
make local-test                      # bash tests of the start script (no Docker needed)
```

**Old ports.** Core serves 8100-8115 as before, so scripts, the MCP links (`:8114/mcp`), the Stripe webhook forward (`:8105`), the
mobile apps (fulfilment :8108, bookings :8109, identity :8100) and the demo-data seeder keep working. The Go services' own ports
8201-8209 are **not** served any more: gateway-bff, geo, media, notifications and promise are on **:8200**, cart, search and
carrier-gateway under `http://localhost:8200/modules/<name>`. `make up` points core, the seeder and the driver app's media URL
(`EXPO_PUBLIC_MEDIA_URL`) there for you (names you set yourself win). Monitoring follows the same shape: Prometheus scrapes exactly two
application targets, core `:8120/actuator/prometheus` and afrotransact-go `:8200/metrics`; the old ports are never scraped, a module is
a `module=` / `service=` label (see "Monitoring locally" below).

### Monitoring locally (OBS=1, the default)

`make up` starts Prometheus, Alertmanager, Grafana, Loki, Tempo, the OTel collector and the host log shipper beside the stack. Everything is
shaped for the two deployables:

| Signal | Where it comes from | How to read one module |
|---|---|---|
| Metrics | Prometheus scrapes core `host.docker.internal:8120/actuator/prometheus` (job `afrotransact-core`) and afrotransact-go `:8200/metrics` (job `afrotransact-go`), both with `honor_labels: true` | `{module="orders"}` or `{service="orders"}`; process series (`jvm_*`, `process_*`, `go_*`, `up`) carry the deployable |
| Readiness | `at_module_up{module}` from both deployables (1 = UP) | dashboard `AfroTransact / Release 1 modules` |
| Logs | the host log shipper tails `.local/logs/core.log` and `afrotransact-go.log`; Loki `service_name` is `afrotransact-core` and `afrotransact-go` | `{service_name="afrotransact-core"} \|= "com.afrotransact.orders"`, or `\| module="cart"` where the line states it (Go slog; core's JSON profiles) |
| Traces | both deployables send OTLP to the collector; Tempo `service.name` is `afrotransact-core` / `afrotransact-go`, the module is a span attribute | Grafana trace -> logs link uses `service_name`, which now matches |

Not scraped locally: the legacy ports (8100-8115, 8201-8210), Meilisearch (its `/metrics` needs the master key; production Alloy scrapes it).
`STACK=services` (the old per-service processes) is not covered by `prometheus.yml`.

What you may need to set: nothing for the defaults. `.local/secrets.env` (provider keys, `SEARCH_PROVIDER`), `PG_SUPERUSER` and
`PGPASSWORD` only when your own Postgres on :5432 needs them, JDK 25 (`brew install openjdk@25`), PostGIS on your own Postgres.

The rest of this page is the advanced path: running a deployable by hand, what each setting means, how to detach a module. One
section per deployable, the Java one first, then the Go one.

## Demo data: seeding, re-seeding, wiping, checking

`make up` seeds an empty catalogue once (`SEED=0 make up` skips it; log `.local/logs/seed.log`). Everything below is idempotent.

```bash
make seed                              # re-run everything that is missing (STACK=monolith: reaches afrotransact-go's modules itself)
make seed ARGS="--only products"       # one step: sellers, size-charts, products, content, ads, preorders, pickups, bookings, ops, users
make seed ARGS="--only ops"            # orders, fulfilment, returns, dispatch, payouts (needs the first-party sign-in below)
make seed-check                        # offline, read-only: every demo product against the catalog's recorded schemas
make seed-test                         # the seeder's unit tests
make smoke                             # with the stack up: proves catalogue, photos, search, cart, tax, checkout, apps, orders, metrics
```

**Wipe and start over.** There is no `make seed-reset`: the services own their data. `make reset` removes the Docker volumes (Kafka,
search index, MinIO photos), `make db-reset` also recreates every database (asks you to type `wipe`), and `rm -rf .local/seed` forgets
the upload cache and downloaded photos. Then `make up` (seeds by itself) or `make seed`.

**What a product needs (publish gate).** Catalog refuses a product that lacks what its category requires (`GET /v1/listing-rules`):
description, typed facts (net weight, servings, shelf life, storage, ingredients, material, model, age grade), a dangerous-goods
answer, the package of every variant, a barcode where the barcode is required, a label photo for food, health and personal care.
The seeder reads those rules and the product type for each category and fills them (`tools/seed/seedlib/listing.py`; nothing is keyed
on a category): the product's own data where the dataset has it, else a value derived from the field's type, options and units.
Demo barcodes are GTIN-13 with the restricted GS1 prefix `200` (never a real company's), label photos are generated stand-ins.
`make seed-check` runs the same code on recorded schemas (`tools/seed/testdata`, rebuilt by `python3 tools/seed/gen_testdata.py`;
`--record URL` refreshes them from a running catalog). Products in a gated category (NEEDS_APPROVAL) or without a usable photo are
skipped with a reason in the summary.

**Photos.** Every kept photo has at least 500 px on its short side and 1000 px on its long side (media's rules); the size of the real
file is checked before any upload, and a photo media refuses is replaced by another candidate. If MinIO was recreated the upload cache
is dropped with one line and the photos are uploaded again.

**Addresses.** In STACK=monolith (the default) the seeder and `make smoke` use core's single entry, `http://localhost:8120/modules/<module>`
(identity, catalog, offers, inventory, seller, bookings, content, delivery-runs, orders, payments, retail, fulfilment, trust-ops,
tax-returns), and afrotransact-go on :8200 (media, geo and the BFF flat; cart, search and carrier-gateway under `/modules/<name>`). The
legacy ports 8100-8115 are only used with `STACK=services`. Every address stays overridable (`CATALOG_URL`, `CORE_URL`, `BFF_URL`,
`AFROTRANSACT_GO_PORT` ...); each step prints the base URLs it uses once, `make up` / `make seed` export them (`mono_peer_env`).

**What a re-run does.** `make up` on a catalogue that already has products re-seeds only what is missing: demo products an earlier run lost
(for example after a publish-gate refusal) are created again, then ads and the order data. That is why products that failed once come
back by themselves.

**The order step names each cause once.** Before an order is placed the seeder checks that every product it buys is in the catalog, LIVE,
tax-READY and has an ACTIVE offer; it asks the BFF what delivery options checkout offers for the cart and sends one of those (parcel
scenarios use AfroTransact delivery because seller carrier shipping is off at launch; `carrier` only where offered). A cause that hits
several orders is one line with the orders and the fix, for example `2 order(s) not placed (delivered-return-requested, ...): Halal Goat
Meat is not in the catalog ... Fix: make seed ARGS="--only products"`. Orders that could not be placed are left out of fulfilment,
returns and dispatch instead of failing again there.

**Stripe test keys.** With `STRIPE_SECRET_KEY=sk_test_...` in `.local/secrets.env` payments uses real Stripe test mode, where an order waits
for its card. `make seed` loads that file and confirms each demo PaymentIntent with Stripe's test card `pm_card_visa` through api.stripe.com
(only `sk_test_` keys are ever used, the key is never printed), then asks orders to continue; no `stripe listen` is needed. Without a key
the local stand-in authorises every card.

**Order data needs identity's first-party sign-in.** The `ops` and `users` steps sign the test customers in as gateway-bff does (client
credentials `gateway-bff`, scope `auth:first-party`, then `POST /v1/auth/password`; the local secret is identity's default or
`GATEWAY_BFF_CLIENT_SECRET`). In core with `at.security.enabled=false` identity used to answer every such call with 403
`identity.first-party-client-required` (the local actor resolver turned the BFF's service token into the local super admin): fixed in core
(`CurrentUser.caller()`, `BearerServiceTokenVerifier`), so core must be rebuilt (`make restart SERVICE=core`) for the orders to seed. The
seeder checks this once and says so in one sentence instead of failing every step.

## Java: afrotransact-core (one JVM, every Java module)

`afrotransact-core` is every Java service as a module of one JVM (ADR 0011). This page is how to run it on your machine
against the local Docker infrastructure, how its database works, and how to take one module out. Environment variable
**names** only; values come from your shell or `.local/secrets.env`.

### Start

`make up` starts core with everything else (see "Start everything" above). By hand, only core, in the foreground:

```bash
make up-infra            # Postgres 17 (+PostGIS), Valkey, Kafka, Temporal, ... (as always)
make core-db             # the `core` database with ONE SCHEMA and ONE ROLE per module (idempotent); the same Postgres and superuser as make up
make core-run            # provisions the database again (idempotent), builds the jar when missing or stale (REBUILD=1 forces it), runs core on :8120
```

`make core-build` only builds (`CORE_MVN_ARGS=-DnoAi` leaves ai-gateway and mcp-server out of the jar). Needs JDK 25.
`make core-db` and `make core-run` use the same detection as `make up` (your own Postgres on :5432 or the Docker one; `PG_EXTERNAL`,
`PG_SUPERUSER`, `PGPASSWORD`), so they work with the Postgres the old flow used. With `STACK=services` (`make up STACK=services`) the
standalone Java services start instead: do **not** run those and core at the same time, they use the same ports.

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

Java modules no longer reach each other over HTTP in core (ai-gateway's readers and mcp-server's tools included, 12.10 and 12.11 of
`docs/engineering/release-1-architecture.md`); the only HTTP left is to the Go services and, for an edge whose callee is switched off (delivery-runs by
default), the old `http://localhost:81xx` URL, which locally is core, so nothing needs re-configuring. **Only the local profile serves the old ports**: in uat and prod these edges and gateway-bff's calls need their URLs set, see
`docs/runbooks/release-1-deploy.md` section 5a ("Upstream wiring").

#### Database: one database, one schema per module

`at.modules.<module>.database.mode` is `schema` (default in core) or `database`.

* `schema`: all modules use `CORE_DB_URL` (default `jdbc:postgresql://localhost:5432/core`; `make up` / `make core-db` / `make core-run` set it for the Postgres in use). Each module has its own role
  (`<module>` with `-` as `_`: `catalog`, `tax_returns`, `trust_ops`, `ai_gateway`, `delivery_runs` ...; password env
  `<MODULE>_DB_PASSWORD`, local default = role name), owns exactly one schema, has `search_path` pinned (role setting and every
  pooled connection) and keeps its own `flyway_schema_history` inside its schema. Migrations are the ones the services have.
* `database`: the module uses its own database as the standalone service does; set `<MODULE>_DB_URL`, `<MODULE>_DB_USER`,
  `<MODULE>_DB_PASSWORD`. Mix freely: a module in `database` mode next to the others in `schema` mode.
* Temporal keeps its own databases (`init-databases.sh`). Extensions (`postgis`, `btree_gist`, `pg_trgm`) are created once in
  `public` by `make core-db` (module roles cannot create extensions; the migrations use `create extension if not exists`).
* Locally the `local` profile also lets core create missing roles and schemas itself at start (`CORE_DB_ADMIN_URL`,
  `CORE_DB_ADMIN_USER`, `CORE_DB_ADMIN_PASSWORD`, default `postgres`/`postgres`); never set these in uat or prod. The start script
  exports them for the Postgres it provisioned (Docker: `postgres`/`postgres`; your own Postgres: your superuser over TCP, or switched
  off when only a socket login is known: the roles and schemas exist by then). The error `database "core" does not exist` at start
  means the provisioning did not run against the Postgres core connects to: run `make core-db` and read which Postgres it names.

Pools: 4 connections per module (orders, payments, catalog 8), minimum idle 0. Enabled by default that is 13 modules and at most
**64 connections** (limit on Layerbase: 500). Override with `<MODULE>_DB_POOL_SIZE`.

#### Modules on and off

| Module | Default | Switch |
|---|---|---|
| identity, catalog, tax-returns, offers, inventory, content, seller, orders, payments, bookings, fulfilment, trust-ops, ai-gateway, mcp-server | on | `MODULE_<NAME>_ENABLED=false` (`MODULE_TRUST_OPS_ENABLED`, `MODULE_AI_GATEWAY_ENABLED`, ...) |
| retail, delivery-runs | **off** (not in the launch set) | `MODULE_RETAIL_ENABLED=true`, `MODULE_DELIVERY_RUNS_ENABLED=true` |

A disabled module is not scanned, opens no connection and has no health component. How a module reaches the others is
`at.<caller>.mode` (`AT_CATALOG_MODE`, `AT_TAXRETURNS_MODE`, `AT_OFFERS_MODE`, `AT_INVENTORY_MODE`, `AT_SELLER_MODE`, ...: `in-process` | `http`),
and per edge `at.<caller>.<callee>.mode` (`AT_SELLER_PAYMENTS_MODE=http` detaches seller -> payments only). A callee that is switched off makes the
caller-level default fall back to `http` for that edge; an edge set explicitly to `in-process` whose callee is off fails the start with a message
naming both modules. Which edges are in-process and which stay HTTP: `docs/engineering/release-1-architecture.md` sections 12.7 and 12.8.

#### Detach one module (run it as its own process again)

1. In core: `MODULE_<NAME>_ENABLED=false` and restart. Its old port stops being served by core (the others keep working).
2. Start the module standalone as before (`make dev-restart SERVICE=<name>`, or the service jar): same port, same config. Give it
   its data: either `<NAME>_DB_URL` pointing at the `core` database with the role `<name>` and its schema (standalone services read
   `DB_URL`, `DB_USER`, `DB_PASSWORD`; add `?currentSchema=<schema>` to the URL), or keep the module in `database` mode from the
   start. Its Kafka consumer group is unchanged, so no events are replayed or lost.
3. Modules that call it in-process need that edge set to `http`: `AT_<CALLER>_<NAME>_MODE=http` for each caller (for tax-returns that is `AT_CATALOG_TAXRETURNS_MODE`, `AT_OFFERS_TAXRETURNS_MODE`), or the caller-level `AT_<CALLER>_MODE=http`.
4. ai-gateway, mcp-server: also leave them out of the build with `-DnoAi` (`CORE_MVN_ARGS=-DnoAi make core-build`).

#### What to know

* Orders needs Temporal (`TEMPORAL_TARGET`, `TEMPORAL_START_WORKERS=false` to run without workers) and Valkey (`REDIS_URL`).
  Kafka is `KAFKA_BOOTSTRAP`. Provider keys (Stripe, OpenRouter, ...) are read from `.local/secrets.env` as for the services.
* Lola (ai-gateway) reads catalog, offers, orders, bookings and tax-returns in-process, for the signed-in shopper captured when the chat request enters
  (`at.aigateway.mode=in-process`; one edge back to HTTP: `AT_AIGATEWAY_<CATALOG|OFFERS|ORDERS|BOOKINGS|TAXRETURNS>_MODE=http`, which then needs `at.ai.downstream.<callee>-url`). The cart,
  search and delivery promise are Go and stay HTTP. A tool that cannot reach its callee answers "not available" within 5 s (`at.aigateway.<callee>.timeout`); the USD/day AI cap is unchanged.
* `at.security.enabled=false` in the `local` profile, as for the services. With security on, the shared chain keeps every module's
  anonymous permits (seller storefront reads and webhooks, content visitor forms, identity sign-up, the MCP metadata).
* Not verified here: see "Status and what was not verified" in `docs/engineering/release-1-architecture.md`.

## Go: afrotransact-go (one process, every Go module)

`go/apps/afrotransact-go` runs the Go services as modules of one process (ADR 0011, `docs/engineering/release-1-architecture.md`
section 7). Each service still builds and runs alone from `go/services/<svc>/cmd/<svc>` on its own port (see `infra/local/PORTS.md`);
nothing below changes that.

### Run it

`make up` builds and starts it on :8200 after core and the search engine are up (see "Start everything" above). By hand, with the
infrastructure running (`make up-infra`; it needs Meilisearch on :7700, Valkey, Kafka):

```bash
make go-monolith-run                 # builds go/bin/afrotransact-go and runs it on :8250
APP_ENV=local AFROTRANSACT_GO_PORT=8250 go/bin/afrotransact-go
make go-monolith-image               # container image (go/Dockerfile.afrotransact-go)
```

One listener: the binary's default is port **8250** (`AFROTRANSACT_GO_PORT`, then `PORT`; the image sets `PORT=8080`); `make up`
runs it on **8200** locally so the web apps' BFF origin is unchanged. `MANAGEMENT_ADDR` (for example `:9090`)
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
