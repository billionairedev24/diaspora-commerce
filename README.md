# diaspora-commerce

AfroTransact v2: a marketplace for diaspora shoppers and the sellers and service providers who serve them.
Shoppers buy products (from independent sellers and from **AfroTransact Retail**, the first-party business) and book services.
Sellers and providers get onboarding, back offices and storefronts, and AfroTransact staff run oversight, inventory and delivery.

| Surface | Where |
|---|---|
| Storefront (web) | `web/apps/storefront` (Next.js 16 on Cloudflare Workers) |
| Seller Center, Provider Hub | `web/apps/seller-center` |
| Staff console (platform back office) | `web/apps/staff-console` |
| Customer app, driver app | `mobile/apps/customer`, `mobile/apps/driver` (Expo) |
| Java modules (Spring Boot 4.1, Java 25) | `java/services/*`, shipped together as **`afrotransact-core`** (`java/apps/core`) |
| Go modules (Go 1.27.4) | `go/services/*`, shipped together as **`afrotransact-go`** (`go/apps/afrotransact-go`) |
| API contracts | `contracts/` (OpenAPI 3.1 + AsyncAPI) |
| Developer portal | `docs/portal` (Docusaurus, with Redoc + Scalar references) |
| Infrastructure | `infra/` (docker compose, Helm, Argo CD, OpenTofu for OVH Managed Kubernetes, Cloudflare, observability, the secrets renderer in `infra/ovh-vps`) |
| Backlog | `docs/backlog/backlog.csv` (Jira import) |
| Design source | `design/` (Claude Design prototypes and chat transcripts) |

**Release 1 (this branch) is what goes to production: two deployables, `afrotransact-core` (Java) and `afrotransact-go` (Go), every service a
module inside them ([ADR 0015](docs/adr/0015-modular-monolith-release-1.md)).** It runs on one OVH Managed Kubernetes node per environment with
Postgres and Valkey on Layerbase, search on Meilisearch in the cluster, Kafka and Temporal in the cluster, and the web apps on Cloudflare Workers.
**To deploy, start at [docs/runbooks/release-1-go-live.md](docs/runbooks/release-1-go-live.md)** (UAT first, then PROD); all runbooks:
[docs/runbooks/README.md](docs/runbooks/README.md).

Read **[CLAUDE.md](CLAUDE.md)** for the engineering rules (interfaces + implementations, no wildcard or inline
imports, hexagonal layering) and **[infra/local/PORTS.md](infra/local/PORTS.md)** for the service catalogue.

## Quick start

```bash
make up                # local release-1 stack: Postgres, Kafka, Valkey, search, Temporal, core + afrotransact-go, web apps (docs/runbooks/release-1-local.md)
make java-verify       # all Java services: checkstyle + unit + Testcontainers integration tests
make go-verify         # all Go services: vet + lint + tests
make web-dev           # storefront on http://localhost:3000
```

Provider keys (Stripe test mode, Twilio, Resend, …) for a real local test go in `.local/secrets.env` (template
`infra/local/secrets.env.example`); `make secrets-check` shows what is set. Every secret and where it lives locally
and in dev/uat/prod (names per deployable: [docs/runbooks/release-1-config-inventory.md](docs/runbooks/release-1-config-inventory.md)): **[docs/engineering/secrets-and-configuration.md](docs/engineering/secrets-and-configuration.md)**.

Each module also builds and tests on its own (on `main` and `develop` each is still its own deployable), for example:

```bash
cd java && ./mvnw -pl services/catalog -am verify
cd go/services/search && go test ./...
```

Every running service serves its API reference at `/docs` (Scalar), `/redoc` (Redoc) and Swagger UI,
plus `/v3/api-docs` or `/openapi.yaml`.
