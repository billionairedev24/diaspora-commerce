# Release 1: running locally

One section per deployable. The Java section (afrotransact-core) is written by the Java batch; this file is appended to, not rewritten.

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

### Detach one module

1. Run it alone: `AT_MODULES_MEDIA_ENABLED=false` in afrotransact-go, and start `go/services/media/cmd/media` (or its own Deployment).
2. Point the callers at it: nothing else is needed when `MEDIA_URL=http://media:8207` is set, because in-process URLs only apply while the module runs inside.
3. To keep the module inside but make the others call it over HTTP (a step before detaching, or to test the network path): `AT_MODULES_MEDIA_MODE=http`.

### Database

Default: each Go service keeps its own database (`cart`, `geo`, `media`, `notifications`, `carriers`) through its `<MODULE>_DB_URL`. Optional single database
(`afrotransact_go`): schema and role per module (`infra/ops/postgres/release-1-bootstrap.sh`, `make db-release-1` locally), `search_path` pinned on the role and in
the URL, PostGIS created by the admin in `public` before deploy. The embedded migrations are idempotent SQL on unqualified names and notifications' `schema_migrations`
is unqualified too, so each schema keeps its own history with no code change. Verified with a real PostgreSQL: **not yet** (see the status table below).

### Tests

`cd go/apps/afrotransact-go && go test ./...` boots all ten modules with fake modules (real runtime, fake routes), plus the real promise module, and checks the
real OpenAPI route sets for collisions. Every service's own `go test ./...` is unchanged.
