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
| Java services (Spring Boot 4.1, Java 25) | `java/services/*` |
| Go services (Go 1.27.2) | `go/services/*` |
| API contracts | `contracts/` (OpenAPI 3.1 + AsyncAPI) |
| Developer portal | `docs/portal` (Docusaurus, with Redoc + Scalar references) |
| Infrastructure | `infra/` (docker compose, Helm, Argo CD, Terraform for AWS/GCP/Azure, Cloudflare, observability) |
| Backlog | `docs/backlog/backlog.csv` (Jira import) |
| Design source | `design/` (Claude Design prototypes and chat transcripts) |

Read **[CLAUDE.md](CLAUDE.md)** for the engineering rules (interfaces + implementations, no wildcard or inline
imports, hexagonal layering) and **[infra/local/PORTS.md](infra/local/PORTS.md)** for the service catalogue.

## Quick start

```bash
make up                # local stack: Postgres, Kafka, Elasticsearch, Redis, Temporal, observability
make java-verify       # all Java services: checkstyle + unit + Testcontainers integration tests
make go-verify         # all Go services: vet + lint + tests
make web-dev           # storefront on http://localhost:3000
```

Each service is also built, tested, containerised and deployed on its own, for example:

```bash
cd java && ./mvnw -pl services/catalog -am verify
cd go/services/search && go test ./...
```

Every running service serves its API reference at `/docs` (Scalar), `/redoc` (Redoc) and Swagger UI,
plus `/v3/api-docs` or `/openapi.yaml`.
