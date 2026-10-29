# Testing standards

How we test AfroTransact v2, per runtime, and what CI gates on. New code follows this page; reviewers point at it.
Related: [new-service-checklist.md](new-service-checklist.md) (layout), [ci-cd.md](ci-cd.md) (pipelines),
[security-scanning.md](security-scanning.md) (scanners).

## 1. The pyramid per runtime

Most tests are fast and in-process; a few prove the wiring against real infrastructure; a handful drive the UI.

| Layer | Java (Spring Boot) | Go | Web (Next.js / packages) | Mobile (Expo) |
|---|---|---|---|---|
| **Unit** (most) | JUnit 5 + AssertJ (+ Mockito only at ports). `domain/*Test`, `application/*Test`. No Spring context. | `go test`, table-driven, `*_test.go` next to the code; fakes implement `internal/port` interfaces | Vitest + Testing Library (`test/**/*.test.ts(x)`), jsdom | Vitest (`test/**`) |
| **Architecture / lint** | `ArchitectureTest` → `HexagonalArchitectureRules.verify` (ArchUnit); Checkstyle | `golangci-lint` (`go/.golangci.yml`), `go vet` | ESLint (`at/no-raw-hex`, import order), Stylelint, `tsc --noEmit` | ESLint, `tsc` |
| **Contract** | OpenAPI exported from `/v3/api-docs` must equal `contracts/openapi/<svc>.yaml` (CI `contract` job, oasdiff) | `TestEmbeddedContractIsIdentical` + response conformance (§4); AsyncAPI contract tests (§4) | `@afrotransact/api-client` `test/contract.test.ts`: Zod schemas stay assignable to the OpenAPI-generated types | shares the api-client |
| **Integration** (some) | `*IT` with Testcontainers (Postgres, Kafka) through the real adapters, Flyway and the outbox | `*_integration_test.go` with testcontainers-go (Postgres/PostGIS, Valkey, Elasticsearch, MinIO) | — | — |
| **Component** | `@WebMvcTest`-style slice tests for controllers + problem details where useful | `httptest` against the real router with fake ports (`internal/testkit`) | Storybook stories for every component state (RTL, mobile); test runner (`play` interactions + axe) and Playwright visual diffs on the static build (§7) | — |
| **E2E** (few) | — | — | Playwright: `tests/e2e` (cross-app journeys + axe), `web/apps/storefront/e2e` (storefront smoke) | (planned: Maestro) |
| **Load** | — | — | k6: `tests/load` (search, checkout) | — |

Rules of thumb

- Test behaviour through the public interface (`XxxService`, exported Go interface), not private helpers.
- Domain code is pure: its tests need no Spring, no Docker, no network. That is where the 80% gate applies.
- Adapters are tested against the real technology (Testcontainers), not mocks of JDBC/Redis/HTTP clients.
- Every bug fix lands with a test that fails without it.
- No sleeps: poll with a deadline instead — Awaitility in Java (add `org.awaitility:awaitility` as a test
  dependency), a small `eventually` loop in Go, web-first assertions (`expect(...).toBeVisible()`) in Playwright.

## 2. Naming and placement

### Java

| Kind | Name | Location | Runs in |
|---|---|---|---|
| Unit | `XxxTest` | `src/test/java/.../<layer>/` mirroring the class | Surefire (`test` phase) |
| Architecture | `ArchitectureTest` (exactly one per service) | `src/test/java/com/afrotransact/<svc>/` | Surefire |
| Integration | `XxxIT` | `src/test/java/.../it/` | Failsafe (`integration-test` + `verify`) |

- Surefire picks up `*Test`, Failsafe picks up `*IT` (plugin defaults; configured once in `java/platform-parent/pom.xml`).
  Never name a Docker-backed test `*Test` — it would run in the fast phase and break `./mvnw test` without Docker.
- Method names describe behaviour: `rejectsOfferBelowFloorPrice()`, `@DisplayName` when a sentence reads better.
- `./mvnw -B -f services/<svc>/pom.xml verify` runs everything; `./mvnw test` is the fast loop.

### Go

| Kind | File | Package |
|---|---|---|
| Unit | `xxx_test.go` beside `xxx.go` | external `xxx_test` package by default (tests the exported API); internal package only to test unexported logic |
| Integration | `xxx_integration_test.go` | `xxx_test`; first line of each test: `testdocker.Require(t)` (`go/pkg/testdocker`) |
| Contract | `contract_test.go`, `conformance_test.go` | see §4 |

- Test names `TestThing_Behaviour` / table cases with readable `name` fields; `t.Parallel()` where state allows.
- `go test -short ./...` is the fast loop (no Docker); CI runs `go test -race ./...` with Docker.

### TypeScript

- Vitest: `test/**/*.test.ts(x)` in each package/app (configured in each `vitest.config.ts`).
- Playwright: `*.spec.ts` (`tests/e2e/specs/<app>.<area>.spec.ts`, `web/apps/storefront/e2e/*.spec.ts`).
- Storybook: `*.stories.tsx` next to the component (`web/packages/ui/src/**`), app component stories in
  `web/packages/ui/stories/apps/<app>/` or co-located in the app (`web/apps/*/src/**/*.stories.tsx`).

## 3. Testcontainers rules

1. **Images are pinned and already local.** Use exactly the images the local stack uses, so tests never pull
   something new and CI caches stay warm:

   | Dependency | Image | Java | Go |
   |---|---|---|---|
   | PostgreSQL 17 (+PostGIS) | `imresamu/postgis:17-3.5.4-alpine3.22` | `PostgresTestContainer.create()` | `testcontainers.Run(ctx, "imresamu/postgis:17-3.5.4-alpine3.22", …)` |
   | Kafka (KRaft) | `apache/kafka:4.1.0` | `new KafkaContainer("apache/kafka:4.1.0")` | — |
   | Valkey | `valkey/valkey:8.1-alpine` | — | yes |
   | Elasticsearch | `elasticsearch:9.3.0` (`xpack.security.enabled=false`, 512 MB heap) | — | yes |
   | S3 | `pgsty/minio:<pinned RELEASE tag>` (avoid `latest`) | — | yes |

   Adding a new image means adding it to `infra/local/docker-compose.yml` first.
2. **Java uses the shared `PostgresTestContainer`** (`java/libs/platform-test`): one PostGIS container per JVM,
   `withReuse(true)`, wired with `@ServiceConnection` (or `DynamicPropertySource`). Never `new PostgreSQLContainer("postgres:…")`
   in a service. Flyway runs the real migrations (`db/migration` + `classpath:db/platform` for outbox/inbox).
   Locally, `testcontainers.reuse.enable=true` in `~/.testcontainers.properties` keeps the container between runs;
   CI leaves it off.
3. **Isolation by data, not by container.** Each test creates its own aggregate ids (UUIDs) or truncates the
   tables it owns in `@BeforeEach`; never depend on test order.
4. **Fail fast without Docker in the fast loop.** Go integration tests call `testdocker.Require(t)`: they skip under
   `-short`/`SKIP_DOCKER_TESTS=1` and when no Docker daemon answers (stopped Docker Desktop, none installed), but fail
   when `CI` is set, so CI never silently drops them (`REQUIRE_DOCKER_TESTS=1` runs them without the probe);
   Java ITs only run in Failsafe. No test talks to the docker-compose stack on fixed ports.
5. **Wait strategies, not sleeps**: `wait.ForHTTP`, `wait.ForListeningPort`, log-message waits.
6. **Resources**: Elasticsearch at 512 MB heap, one container per package (Go `TestMain`) or per JVM (Java).

## 4. Contract tests

Contracts live in `contracts/` and are the source of truth (contract-first). We use provider-side conformance
tests against those documents instead of Pact brokers: the specs are versioned in the same repo, and
`contracts.yml` (oasdiff) blocks breaking changes.

### BFF / HTTP APIs (OpenAPI)

- **Embedded copy is identical**: `TestEmbeddedContractIsIdentical`
  (`go/services/gateway-bff/internal/adapter/httpapi/conformance_test.go`) — `api/openapi.yaml` served at
  `/openapi.yaml` must byte-equal `contracts/openapi/<svc>.yaml`. Copy this test into every Go service.
- **Responses conform**: `TestContractConformance` drives the main operations (success and documented errors)
  through the real handlers backed by fake upstreams (`internal/testkit`), finds each route with
  kin-openapi's router and validates every response (status documented + body/headers match the schema) with
  `openapi3filter.ValidateResponse`. A status the contract does not document fails the test. Seller
  endpoints follow the same pattern in `seller_test.go`.
- **Java**: the CI `contract` job exports `/v3/api-docs.yaml` from the running service
  (`tools/contracts/export_java_openapi.sh`) and diffs it with `contracts/openapi/<svc>.yaml`.
- **Consumers**: `web/packages/api-client` generates types from the BFF contract (`pnpm generate:clients`),
  and `test/contract.test.ts` asserts the hand-written Zod schemas stay assignable to them; the MSW mocks
  (`src/mocks`) are typed by the same schemas, so web E2E against mocks exercises the contract shape.

### Events (AsyncAPI) — the notifications pattern

`go/services/notifications/internal/app/contract_test.go` is the reference for every consumer and producer:

1. Parse the merged `contracts/asyncapi/events.yaml` (subset structs + `yaml.v3`), resolve each channel's
   message `$ref` and build `channel address → event types`.
2. Assert every topic the service consumes (`app.DefaultTopics`) is a channel address in the document, and every
   event type it handles (`app.DefaultEventRules`) is published on the topic it listens to.
3. Guard against vacuous passes: assert a known real event (`orders.order.placed` on `at.order.events`) is
   present and a removed/speculative one is absent.
4. Cross-check secondary artefacts the mapping depends on (here: a seeded template per rule and channel in the
   migrations).

Producers mirror it: assert every event type the service emits (`*Events` constants in Java,
`events.Publisher` calls in Go) is declared in `contracts/asyncapi/fragments/<svc>.yaml` with the topic used.
`tools/contracts/merge_asyncapi.py` builds `events.yaml`; `contracts.yml` validates it.

## 5. Coverage gates

| Runtime | Instrumentation | Report |
|---|---|---|
| Java | JaCoCo `prepare-agent` (Surefire **and** Failsafe, via `argLine`) + `report` at `verify` (`java/platform-parent/pom.xml`) | `java/{services,libs}/<svc>/target/site/jacoco/jacoco.xml` |
| Go | `go test -covermode=atomic -coverpkg=./... -coverprofile=<file> ./...` per module | `go/.coverage/<module>.out` (CI artifact) |
| Web | Vitest `--coverage` (v8) per package; gated so far: `@afrotransact/ui` stories (`test:storybook`) | `coverage/`; ui stories: `web/packages/ui/coverage/storybook/coverage-summary.json` |

`tools/quality/coverage_report.sh` summarises every report into a Markdown table (also written to the GitHub
step summary) plus `build/coverage/coverage-summary.json`, and **exits 1 when a service is below its threshold**:

```bash
# Java: after ./mvnw verify
tools/quality/coverage_report.sh --service catalog
# Go: run the tests with coverage first (unit only with -short; CI omits -short to include Testcontainers ITs)
tools/quality/coverage_report.sh --run-go --service search
# Everything that has a report, without failing
tools/quality/coverage_report.sh --no-fail
```

Thresholds (`tools/quality/coverage-thresholds.conf`; `COVERAGE_MIN_{JAVA,GO,DOMAIN,DOMAIN_GO,WEB}` env vars win):

| Metric | Default | Measured 2026-10-10 |
|---|---:|---|
| Java total (line) | 75% | identity 86.2, offers 85.3, seller 89.6 |
| Java domain packages | 80% | identity 96.6, offers 92.0, seller 92.7 |
| Go total (statements, excl. `cmd/`, `testkit/`) | 60% | gateway-bff 81.9, promise 81.5, notifications 76.5, geo 63.0, cart 60.8, search 47.0 |
| Go `internal/domain` | 80% | promise 93.8, notifications 84.8, search 84.2, gateway-bff 83.6, geo 81.1, cart 77.5 |
| Web (line, per Vitest project; `<pkg>-<project>`) | none (per project) | ui-storybook 84.17 → `ui-storybook.web = 84` |

Modules below a default have a **ratchet** override in the conf file (e.g. `search.go = 45`): it may only go up.
Domain coverage below 80% is a review blocker even when the ratchet passes.

CI wiring (in place: the `verify` jobs of `_java-service.yml` and `_go-service.yml`, `storybook.yml`):

```bash
# _java-service.yml, after `./mvnw -B -f services/$SVC/pom.xml verify`
tools/quality/coverage_report.sh --service "$SVC"
# _go-service.yml, verify job (Docker available, so ITs run)
(cd go/services/$SVC && go test -race -covermode=atomic -coverpkg=./... -coverprofile=../../.coverage/$SVC.out ./...)
tools/quality/coverage_report.sh --service "$SVC"
# storybook.yml test job, after `pnpm --filter @afrotransact/ui test:storybook`
tools/quality/coverage_report.sh --service ui-storybook
```

## 6. E2E (Playwright) — `tests/e2e`

Journeys across the storefront and Seller Center / Provider Hub against the in-process MSW mock BFF
(`NEXT_PUBLIC_API_MOCKS=1`), plus axe accessibility smoke checks.

```bash
pnpm e2e                          # = pnpm --filter @afrotransact/e2e test; builds both apps into .next-e2e and starts them on :3410/:3411
pnpm e2e:a11y                     # only the @a11y scans
E2E_SERVER=dev pnpm e2e           # next dev instead of build+start (faster start; HMR reloads can flake)
E2E_SKIP_BUILD=1 pnpm e2e         # reuse the last .next-e2e build
E2E_STOREFRONT_URL=https://pr-123.preview… E2E_SELLER_URL=… E2E_BROWSERS=chromium,firefox,webkit pnpm e2e   # previews
```

- Browsers come from `PLAYWRIGHT_BROWSERS_PATH` (`/opt/pw-browsers` in the dev container; `@playwright/test`
  1.56.1 ↔ chromium-1194). Don't run `playwright install` in the container; CI uses `npx playwright install --with-deps`.
- Selectors are roles and accessible names (`getByRole('navigation', { name: 'Back office' })`), never CSS classes.
- Seller mock state (active organisation) is process-wide: the seller project runs its tests in order and
  resets to the seller organisation; roles are switched per test with the `at_mock_role` cookie.
- **Accessibility**: `expectNoSeriousA11yViolations` (WCAG 2.2 A/AA tags) fails on serious/critical findings.
  Existing findings are listed in `tests/e2e/support/a11y-known-issues.ts` with an owner; remove the entry with the fix.
- **Flaky tests** are quarantined, never deleted: tag the title `@quarantine`, add `// owner: <handle> — <issue>`;
  the blocking job runs `--grep-invert @quarantine`, a non-blocking job runs `--grep @quarantine`.
- Clean up: `pnpm --filter @afrotransact/e2e clean` and `rm -rf web/apps/*/.next-e2e`.

## 7. Storybook — `web/packages/ui`

- Every exported component has stories for its states plus `RTL` and `Mobile` variants (`src/stories.helpers.ts`).
- Design tokens are documented in `stories/tokens/Tokens.mdx`, rendered from the generated token map.
- App presentational components get stories under `stories/apps/<app>/` (no app changes needed); the Storybook
  Vite config loads the Seller Center's Tailwind stylesheet for them.
- The a11y addon runs on every story in the Storybook UI (`parameters.a11y.test = 'error'`).
- `pnpm --filter @afrotransact/ui storybook` (dev, :6006) · `pnpm --filter @afrotransact/ui build-storybook`
  (static; delete `storybook-static/` afterwards).
- CI: `.github/workflows/storybook.yml` (see [ci-cd.md](ci-cd.md#storybook-cloudflare-pages)).

### Interaction and a11y tests (Vitest addon)

`@storybook/addon-vitest` turns every story into a Vitest test. The `storybook` project in
`web/packages/ui/vitest.config.ts` (the `storybookTest` plugin) renders each story from `.storybook/main.ts` (ui and
app stories) in **headless Playwright Chromium** (Vitest browser mode, `@vitest/browser-playwright`), through Vite with
the same `viteFinal` as `storybook build`. The `unit` project (Testing Library in jsdom, `test/**`) is separate.

```bash
pnpm --filter @afrotransact/ui test:storybook   # all stories + v8 coverage -> coverage/storybook (delete afterwards)
pnpm --filter @afrotransact/ui test             # unit project only (what `pnpm -r test` runs)
pnpm --filter @afrotransact/ui exec vitest --project storybook src/overlay   # watch one folder
```

Chromium comes from `PLAYWRIGHT_BROWSERS_PATH` (`/opt/pw-browsers` in the dev container, preinstalled in the CI
image). The `playwright` dev dependency is pinned to the same 1.56.1 as `@playwright/test`, so it resolves the same
Chromium build (1194). Never run `playwright install`. The Storybook UI's test widget runs the same project.

- Every story is rendered. A render error, an uncaught exception or a failing `play` function fails it. A story that
  does not compile already fails `storybook build`.
- `play` functions (import `expect`, `fn`, `screen`, `waitFor`, `within` from `storybook/test`; use the `canvas` and
  `userEvent` from the play context) cover the interactive components: Button (click, keyboard, loading), text
  field, password (live rules, show/hide), checkboxes, error summary (focus on failed submit, links focus the
  field), address autocomplete (type, arrow keys, Enter), quantity stepper (stock limit, remove), ProductCard (add
  to cart → stepper → remove), Dialog (Esc, reopen, Apply, focus trap), Menu, Tabs (arrow keys) and Toast
  (auto-dismiss). Query by role and accessible name, as in Playwright. Portalled content (Radix dialogs/menus) is
  outside the canvas: use `screen`. Each story file runs in one page, so a story must not depend on focus or state
  left by the previous one.
- **axe on every story** via `@storybook/addon-a11y`, set globally in `.storybook/preview.tsx`:
  `parameters.a11y = { test: 'error', options: { runOnly: <WCAG 2.2 A/AA tags as in tests/e2e>, impactLevels:
  ['serious', 'critical'] } }`. The scan runs after the `play` function. Serious and critical violations fail the
  story; minor/moderate ones only show in the Storybook UI's Accessibility panel. Turn a story's scan off only with
  `parameters.a11y.test = 'off'` and a reason next to it.
- **No known-issue allowlist**: the test runner's `.storybook/a11y-known-issues.ts` was empty and has been removed.
  If an allowance is ever needed, track it in `tests/e2e/support/a11y-known-issues.ts` first and give the story
  `parameters.a11y.test = 'todo'` (reported, not failing) with a pointer to that entry.
- **Coverage**: v8 over `src/**` (stories and `stories.helpers.ts` excluded), reporters `json-summary` and
  `lcovonly`. `tools/quality/coverage_report.sh --service ui-storybook` gates it at `ui-storybook.web` in
  `coverage-thresholds.conf` (84%, measured 84.17% lines on 2026-10-10). CI uploads it as `storybook-coverage`.
- Opt a story out with the `skip-test` tag (and say why next to it).
- History: until 2026-10 this ran on `@storybook/test-runner` (Jest) against the static build, with a root
  `pnpm.overrides` jest 30.4 pin. Both are gone. The static build is still what the visual test below checks.

### Visual regression (Playwright screenshots, baselines in the repo)

`web/packages/ui/visual/stories.visual.ts` takes one screenshot per story listed in `storybook-static/index.json`.
Each story loads on a fresh page and its `play` function runs. The screenshot is taken once Storybook reports the
render `finished`, fonts and images have loaded, and animations are disabled. The viewport is fixed at 1024×720
(375×740 for stories with the `mobile` viewport global). Baselines are PNGs (no LFS) in
`web/packages/ui/visual-baselines/<story-id>.png`: 70 files, ~1 MB.

```bash
pnpm --filter @afrotransact/ui build-storybook
pnpm --filter @afrotransact/ui visual:docker          # compare, in the CI image (needs Docker)
pnpm --filter @afrotransact/ui visual:update:docker   # accept intended changes: rewrite the baselines, commit them
pnpm --filter @afrotransact/ui visual                 # same without Docker (see the font caveat)
pnpm --filter @afrotransact/ui visual:update
```

- Tolerance: `VISUAL_MAX_DIFF_PIXEL_RATIO` (share of pixels that may differ, default `0.002`) and `VISUAL_THRESHOLD`
  (per-pixel colour distance 0–1, default `0.2`), in `visual/playwright.config.ts`.
- Diffs: `visual/test-results/<test>/*-{expected,actual,diff}.png` (CI artifact `visual-diffs`, plus an HTML report).
- In CI, add the **`update-visual-baselines`** label to the PR (same-repo branches). CI regenerates the baselines
  in the pinned image, commits them to the branch and removes the label. On another branch, run the workflow
  manually with `update_baselines`. The bot's commit does not start CI (GITHUB_TOKEN), so push again afterwards.
- Opt a story out with the `skip-visual` tag, e.g. for something that cannot be made deterministic. Prefer making
  it deterministic: fixed data, and a `play` that waits for the final state, as the Toast story does.
- **Font rendering caveat**: baselines are rendered in `mcr.microsoft.com/playwright:v1.56.1-noble` (Chromium 1194,
  the `@playwright/test` 1.56.1 build), which is also what CI runs. Inter and Fraunces are bundled (`@fontsource`), but glyph
  rasterisation and fallback fonts (Arabic in the RTL stories, symbols) come from the OS. Against the dev
  container's own Chromium (same build, other system fonts), 13 of 69 stories differ by ~1% of pixels. So update
  baselines with the `:docker` scripts or the CI label, never from macOS/Windows or a bare host. Bump the image tag
  in `storybook.yml` and `scripts/visual-docker.sh` together with `@playwright/test` and `playwright` (the Vitest
  browser provider), then regenerate.

## 8. Load tests (k6) — `tests/load`

See [tests/load/README.md](../../tests/load/README.md): search (query, facets, autocomplete) and checkout (cart →
checkout) with smoke/load/stress scenarios and p95/error-rate thresholds.
