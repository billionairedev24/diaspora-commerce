# diaspora-commerce — engineering conventions

AfroTransact v2: a multi-market marketplace (products + services) for diaspora shoppers,
with a first-party retail business (AfroTransact Retail), seller/provider back offices,
a staff console, a customer mobile app and a driver app.

The design source of truth is `design/` (Claude Design handoff). Read `design/chats/` for intent
and `design/AfroTransact Architecture.dc.html` for the architecture. The backlog lives in
`docs/backlog/backlog.csv` (Jira import format) — set a story's `Status` to `Done` when its
acceptance criteria are met and verified.

## Toolchain (pinned)

| Area | Version |
|---|---|
| Java | 25 (toolchain + `maven.compiler.release=25`) |
| Spring Boot | 4.1.1 (`spring-boot.version` in `java/platform-parent/pom.xml`; bump to 4.1.4 when GA) |
| Spring AI | 2.0.1 (AI gateway + MCP server, OpenRouter via OpenAI-compatible API) |
| Build (Java) | Maven 3.9 with `./mvnw` |
| Go | 1.27.2 toolchain (`go 1.27.1` directive + `toolchain go1.27.2` in every `go.mod` and `go/go.work`; Dockerfiles `golang:1.27.2`, CI `GO_VERSION`) |
| Node | 22 LTS, pnpm 10 |
| Next.js | 16, React 19, TanStack Query 5 / Table, Zod 4, Tailwind 4 |
| Mobile | Expo / React Native |
| Search | Elasticsearch 9 (default) or Meilisearch 1.12: `SEARCH_PROVIDER`, `docs/engineering/search-providers.md` |
| Data | PostgreSQL 17 (one database per service), Redis/Valkey 8, Kafka (KRaft), Temporal |

## Repository layout

```
contracts/      openapi/<service>.yaml (OpenAPI 3.1), asyncapi/events.yaml — contract first
java/           Maven reactor: platform-parent/, libs/, services/<name>/
go/             go.work: pkg/ (shared module) + services/<name>/ (one module per service)
web/            pnpm: apps/{storefront,seller-center,staff-console}, packages/{tokens,ui,api-client}
mobile/         apps/{customer,driver} (Expo)
docs/           portal/ (Docusaurus), adr/, runbooks/, backlog/backlog.csv
infra/          local/ (docker compose), helm/ (library chart + per-service charts),
                argocd/, terraform/{modules/{aws,gcp,azure},envs/{dev,uat,prod}}, cloudflare/,
                observability/
design/         the Claude Design prototypes (read-only reference)
```

## Non-negotiable code rules (enforced in CI)

### Java
- **Interface + implementation for everything with behaviour.** Use cases are `public interface XxxService`
  with a package-private `XxxServiceImpl`. Ports (outbound) are interfaces in `port/`; adapters implement them.
- **No wildcard imports, no inline fully-qualified names** in code bodies. Checkstyle `AvoidStarImport`,
  `AvoidStaticImport` (except JUnit/AssertJ/Mockito/Hamcrest in tests) and a custom regexp check fail the build.
- Hexagonal packages per service: `com.afrotransact.<svc>.{domain,application,port,adapter.{in.web,out.persistence,...},config}`.
  `domain` imports no Spring/Jakarta. ArchUnit test `ArchitectureTest` in every service verifies it.
- Constructor injection only. No `@Autowired` fields, no static singletons, no Lombok.
- Records for DTOs/value objects. Errors are RFC 9457 `ProblemDetail`.
- Persistence: Spring Data JDBC or JPA + Flyway migrations (`db/migration`), optimistic locking with `@Version`.
- Every service exposes: `/v3/api-docs` (springdoc), Swagger UI `/swagger-ui.html`, Scalar `/docs`,
  Redoc `/redoc`, Actuator `/actuator/{health,prometheus,info}`, OTLP traces.
- Spring profiles: `local`, `dev`, `uat`, `prod` (`application-<env>.yml`). Secrets only via env vars.
- Tests: JUnit 5 + AssertJ; Testcontainers for adapters; ArchUnit for layering.

### Go
- Exported interface, unexported `xxxImpl` struct, constructor returns the interface, compile-time
  assertion `var _ Iface = (*xxxImpl)(nil)`.
- No dot imports, no blank imports outside `main`, imports grouped (std / third-party / internal) by goimports.
- Layout per service: `cmd/<svc>/main.go` (explicit wiring), `internal/{domain,app,port,adapter/...,config}`.
- `golangci-lint` config at `go/.golangci.yml`. Errors wrapped with `%w`. `context.Context` first arg.
- Every service: `/healthz`, `/readyz`, `/metrics` (Prometheus), `/openapi.yaml`, `/docs` (Scalar), `/redoc`.
- Config via env vars with `APP_ENV` in {local, dev, uat, prod}; defaults in `config/<env>.yaml`.

### TypeScript
- Strict TS, no `any`, ESLint import rules (no namespace imports of app code, ordered imports).
- Design tokens only (`@afrotransact/tokens`) — no raw hex colours in components.
- Data fetching via TanStack Query key factories; tables via TanStack Table (server-driven).
- No visible scrollbars anywhere (global CSS), white canvas, Figtree font.

## Deployability
Every service is independently buildable, testable, containerised and deployable to Kubernetes:
its own Dockerfile, its own Helm chart (`deploy/helm`, depends on `infra/helm/library/at-service`),
its own GitHub Actions workflow (path-filtered) and its own Argo CD Application per environment.
Target cloud (aws | gcp | azure) is a deploy-time variable only.

## Ports (local)
See `infra/local/PORTS.md` — every service has a fixed local port so the stack runs side by side.
