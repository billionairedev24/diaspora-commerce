# New service checklist

Every service is built, tested, packaged and deployed **on its own**. Copy the reference implementations:

- Java: `java/services/catalog` (Spring Boot 4.1, Java 25, hexagonal, JdbcClient + Flyway, outbox events)
- Go: `go/services/search` (Go 1.27.1, hexagonal, shared `go/pkg` runtime)

## Layout

### Java (`java/services/<name>`, package `com.afrotransact.<name>`)

```
pom.xml                         parent: platform-parent (relativePath ../../platform-parent/pom.xml)
src/main/java/.../<Name>Application.java
  domain/        records, enums, domain policies (interface + Default… impl), rule exceptions. No Spring/Jakarta/Jackson.
  application/   use cases: public interface XxxService + package-private XxxServiceImpl (@Service),
                 commands, event payloads, permission constants
  port/          outbound interfaces (repositories, gateways, clients)
  adapter/in/web/        @RestController + DTO records (+ @Schema, jakarta.validation), error advice
  adapter/out/<tech>/    port implementations (package-private, @Repository/@Component)
  config/        @Configuration wiring for domain policies and properties
src/main/resources/
  application.yml + application-{local,dev,uat,prod}.yml, logback-spring.xml
  db/migration/V1__<name>_schema.sql (Flyway; add classpath:db/platform for outbox/inbox)
src/test/java/.../ArchitectureTest.java (HexagonalArchitectureRules.verify)
src/test/java/.../domain/*Test.java     unit tests
src/test/java/.../it/*IT.java           Testcontainers integration tests (PostgresTestContainer)
deploy/helm/                            python3 tools/scaffold/new_chart.py <name> java java/services/<name> --secrets …
```

### Go (`go/services/<name>`, module `github.com/billionairedev24/diaspora-commerce/go/services/<name>`)

```
go.mod (go 1.27.1, toolchain go1.27.1, replace …/go/pkg => ../../pkg)
embed.go                      //go:embed config/*.yaml api/openapi.yaml
cmd/<name>/main.go            explicit wiring only
internal/domain               types and rules, no infrastructure imports
internal/port                 outbound interfaces
internal/app                  exported use-case interfaces, unexported …Impl structs, constructors return interfaces,
                              `var _ Iface = (*implT)(nil)` assertions
internal/adapter/<tech>       port implementations; internal/adapter/httpapi for inbound HTTP
internal/config               Config struct with yaml + env tags
config/{local,dev,uat,prod}.yaml
api/openapi.yaml              identical copy in contracts/openapi/<name>.yaml
Dockerfile                    copy go/services/search/Dockerfile (build context go/)
deploy/helm/                  python3 tools/scaffold/new_chart.py <name> go go/services/<name> --secrets …
```

## Rules (CI enforces them)

- Interface + implementation for everything with behaviour; callers depend on the interface.
- **No wildcard imports, no inline fully-qualified names, no static imports** (except test assertion libraries),
  no field injection, no Lombok (Checkstyle). Go: no dot imports, no blank imports outside main, wrapped errors (golangci-lint).
- RFC 9457 problem details for every error; stable `code` values `<service>.<problem>`.
- Writes that move money or stock take an `Idempotency-Key` and are safe to retry.
- Events go through the transactional outbox (Java) or `events.Publisher` (Go) on topic `at.<aggregate>.events`;
  consumers de-duplicate with the inbox. Document new events in `contracts/asyncapi/fragments/<name>.yaml`.
- Permissions are checked in the application layer (`PermissionChecker` / `auth.Require`), never only in UIs.
- Reference pages: Java `/swagger-ui.html`, `/docs`, `/redoc`; Go `/docs`, `/redoc`; both off in prod.

## Commands

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64      # or any JDK 25
cd java && ./mvnw -B -f services/<name>/pom.xml verify    # checkstyle + unit + ArchUnit + Testcontainers ITs
cd go/services/<name> && GOTOOLCHAIN=go1.27.1 go vet ./... && go test ./... \
  && golangci-lint run --config ../../.golangci.yml ./...
helm dependency build <svc>/deploy/helm && helm template x <svc>/deploy/helm -f <svc>/deploy/helm/values-prod.yaml | kubeconform -strict -ignore-missing-schemas
```

## Definition of done

1. Code follows the layout and rules above; build, lint and all tests green.
2. `contracts/openapi/<name>.yaml` describes every endpoint (Java: export from `/v3/api-docs.yaml` of the running service).
3. Config for local, dev, uat and prod; secrets only from env vars.
4. Helm chart renders and validates for dev, uat and prod.
5. Service README (`README.md` in the service dir) explains purpose, endpoints, events, config and how to run it.
6. The backlog stories it completes are marked Done (`python3 tools/backlog/mark.py S0xx --status Done`).
