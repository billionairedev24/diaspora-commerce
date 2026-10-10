# Security scanning

What scans the code, dependencies, secrets, containers and infrastructure, how to run each locally, and the exact
commands for CI. Backlog: S201 (critical findings fail the build, SBOMs on every image).
Related: [testing.md](testing.md), [ci-cd.md](ci-cd.md).

## 1. Overview

| Concern | Tool | Config | Blocks merge on | Where it runs |
|---|---|---|---|---|
| Code patterns (our rules + OWASP basics) | **Semgrep** | `tools/security/semgrep.yml` | ERROR-severity rules | per service + web (PR), repo-wide (weekly) |
| Deep code analysis (dataflow) | **CodeQL** | GitHub default queries (`security-extended`) | high/critical alerts (branch protection) | `security.yml`, optional per service |
| Secrets | **gitleaks** | `.gitleaks.toml` | any finding | per service (PR diff), `security.yml` (full history) |
| Dependencies + IaC + secrets | **Trivy** fs / config | `trivy.yaml` (+ `tools/security/trivy-secret.yaml`, `trivyignore.yaml`) | CRITICAL (repo), CRITICAL+HIGH (per service) | per service, `security.yml` |
| Go reachable vulns | **govulncheck** | — (`tools/security/govulncheck.sh`) | any reachable vuln | per Go service |
| Dependencies (all ecosystems, OSV.dev) | **osv-scanner** | `tools/security/osv-scanner.toml` | CRITICAL (CVSS ≥ 9) | `security.yml`, web pipeline |
| Java dependencies (NVD, optional) | OWASP **Dependency-Check** | `tools/security/dependency-check-suppressions.xml` | CVSS ≥ 9 | `security.yml` (weekly; needs `NVD_API_KEY`) |
| Container images | **Trivy** image | `trivy.yaml` | CRITICAL | `.github/actions/publish-image` (after build, before sign) |
| SBOM | **Syft** (SPDX) + cosign attestation | — | — | `.github/actions/publish-image` |
| Running app (DAST) | **OWASP ZAP** baseline | — | — (issues opened) | `security.yml` vs dev (weekly) |

Severity policy: **CRITICAL fails every pipeline. HIGH fails the per-service PR pipeline** (so it is fixed by the
team that owns it) and is reported in the weekly scan. MEDIUM/LOW are uploaded as SARIF to GitHub code scanning
and triaged weekly. Accepted risks live in the tool's ignore file with an owner, a reason and an expiry date —
never as a pipeline flag.

## 2. Tools

### Semgrep — `tools/security/semgrep.yml`

Fast, syntax-aware rules that encode our conventions plus common vulnerability patterns. Rules (prefix `at.`):

| Rule | Lang | Sev | What |
|---|---|---|---|
| `java.no-field-injection` | Java | ERROR | `@Autowired`/`@Inject` on fields (constructor injection only) |
| `java.no-system-out` | Java | ERROR | `System.out` / `System.err` / `printStackTrace()` |
| `java.sql-injection` | Java | ERROR | taint: a String parameter of a non-private method reaches SQL text (`JdbcClient.sql`, `JdbcTemplate`, `EntityManager`, JDBC) |
| `java.sql-string-concatenation` | Java | WARNING | any runtime concatenation/formatting of SQL text (constants and `UPPER_CASE` column lists excluded) — review queue |
| `java.unsafe-jackson-default-typing`, `java.cors-wildcard-with-credentials`, `java.command-injection` | Java | ERROR | deserialization gadgets, `*` CORS, shell commands |
| `java.weak-hash`, `java.insecure-random-for-secrets` | Java | WARNING | MD5/SHA-1, `java.util.Random` for tokens |
| `go.insecure-skip-verify` | Go | ERROR | `InsecureSkipVerify: true` |
| `go.sql-injection` | Go | ERROR | taint: a string parameter of an exported func reaches `Query/Exec/Prepare` SQL text |
| `go.sql-string-building` | Go | WARNING | non-constant `fmt.Sprintf`/`+` SQL text |
| `go.command-injection` | Go | ERROR | `exec.Command("sh", "-c", …)` |
| `go.http-server-without-timeouts`, `go.weak-crypto`, `go.math-rand-for-secrets`, `go.unescaped-template-html` | Go | WARNING | Slowloris, weak crypto, predictable randomness, `template.HTML(x)` |
| `ts.dangerously-set-inner-html-unsanitized` | TS/JS | ERROR | `dangerouslySetInnerHTML` not wrapped in `DOMPurify.sanitize`/`sanitize`/`sanitizeHtml` (escaped JSON-LD allowed) |
| `ts.dom-xss-sinks`, `ts.eval` | TS/JS | ERROR | `innerHTML=`, `document.write`, `insertAdjacentHTML`, `eval`, `new Function` |
| `ts.token-in-web-storage`, `ts.postmessage-wildcard-origin` | TS/JS | WARNING | tokens in `localStorage`, `postMessage(…, "*")` |

Rule fixtures in `tools/security/semgrep-tests/` (`// ruleid:` must match, `// ok:` must not); run the self-test
whenever a rule changes. In CI we add the registry packs `p/java p/golang p/typescript p/react p/secrets`
(OWASP Top 10 basics for Spring, Go, TS and React). Suppress a reviewed false positive on the line:
`// nosemgrep: at.java.sql-string-concatenation — <reason>`.

```bash
pipx install semgrep            # or: uv tool install semgrep / pip install semgrep / docker semgrep/semgrep
tools/security/run_semgrep.sh --self-test                      # rule fixtures
tools/security/run_semgrep.sh                                   # our rules over java go web mobile tests tools
tools/security/run_semgrep.sh --registry --sarif semgrep.sarif  # + registry packs (network)
tools/security/run_semgrep.sh -- java/services/catalog          # one service
```

### CodeQL

Dataflow analysis with GitHub's `security-extended` suites for `java-kotlin`, `go`, `javascript-typescript` and
`actions`. Already in `security.yml` (repo, weekly + main) and opt-in per service (`codeql: true` input of the
reusable workflows). Branch protection should require "no new high/critical code scanning alerts".

### gitleaks — `.gitleaks.toml`

Secret detection in commits and files. Default rules plus ours (Stripe live keys, OpenRouter keys, passwords in
Postgres URLs pointing at non-local hosts). Allowlists cover only documented local-only defaults
(`local-<svc>-secret`, `LocalAdmin2026!` of the `local` profile), fixture values in test sources, the fake
`ClusterSecretStore` for kind clusters, build output and `design/`. Inline escape hatch: `gitleaks:allow` comment.

```bash
go install github.com/zricethezav/gitleaks/v8@latest
gitleaks dir . --config .gitleaks.toml --redact                          # working tree (no git)
gitleaks git . --config .gitleaks.toml --redact                          # full history
gitleaks git . --config .gitleaks.toml --redact --log-opts="origin/main..HEAD"   # commits of a PR
```

> CI note: the per-service jobs scan `dir /repo/$SERVICE_DIR`. gitleaks only auto-loads `.gitleaks.toml` from the
> scanned directory, so pass `--config /repo/.gitleaks.toml` explicitly (see §3).

### Trivy — `trivy.yaml`

One scanner for dependency CVEs (Maven `pom.xml`, `go.mod`, `pnpm-lock.yaml`), secrets, IaC misconfiguration
(Dockerfiles, Kubernetes manifests, Helm charts rendered with the prod platform values, Terraform) and container
images. `trivy.yaml` sets scanners, skip dirs, `ignore-unfixed`, the secret allow-rules
(`tools/security/trivy-secret.yaml`) and the accepted-risk file (`tools/security/trivyignore.yaml`, entries expire).
Gates narrow it with `--severity … --exit-code 1`.

```bash
trivy fs --config trivy.yaml .                                     # everything, report only
trivy fs --config trivy.yaml --scanners vuln,secret --severity CRITICAL --exit-code 1 .   # repo gate
trivy config --config trivy.yaml infra/                            # IaC only
trivy image --config trivy.yaml --severity CRITICAL --exit-code 1 <image>@<digest>
# without a local install:
docker run --rm -v "$PWD:/repo:ro" -w /repo aquasec/trivy:0.69.1 fs --config trivy.yaml .
```

### govulncheck — `tools/security/govulncheck.sh`

The Go team's scanner: reports a vulnerability only when our code can reach the vulnerable symbol (call graph), so
findings are actionable. Includes the **Go standard library** — fixing usually means bumping the `toolchain` line.

```bash
go install golang.org/x/vuln/cmd/govulncheck@latest
tools/security/govulncheck.sh                       # every module (go/pkg + go/services/*)
tools/security/govulncheck.sh search cart           # some modules
GOVULNDB=https://storage.googleapis.com/go-vulndb tools/security/govulncheck.sh   # when vuln.go.dev is blocked
(cd go/services/search && govulncheck -format sarif ./... > govulncheck.sarif)   # SARIF for code scanning
```

### osv-scanner — `tools/security/osv-scanner.toml`

Lockfile scanner against OSV.dev (GitHub advisories + Go vuln DB + NVD). Covers the pnpm workspace (web, mobile,
docs portal) where Trivy's dev-dependency detection is weaker, and gives a second source for Go/Maven.

```bash
go install github.com/google/osv-scanner/v2/cmd/osv-scanner@latest
osv-scanner scan source --config tools/security/osv-scanner.toml -r .             # discovers lockfiles recursively
osv-scanner scan source --config tools/security/osv-scanner.toml --lockfile pnpm-lock.yaml --format sarif --output osv.sarif
```

### OWASP Dependency-Check (optional, Java)

NVD/CPE-based scanner for the Maven reactor; slower and noisier than Trivy/osv-scanner, kept as a weekly second
opinion. Needs an NVD API key (`NVD_API_KEY` secret). Suppressions: `tools/security/dependency-check-suppressions.xml`.

```bash
cd java && ./mvnw -B org.owasp:dependency-check-maven:12.1.0:aggregate -DnvdApiKey="$NVD_API_KEY" \
  -DfailBuildOnCVSS=9 -Dformats=HTML,SARIF,JSON -DsuppressionFile=../tools/security/dependency-check-suppressions.xml
```

### Images: Trivy + Syft SBOM + cosign

`.github/actions/publish-image` builds the image, scans it with Trivy (CRITICAL fails), generates an SPDX SBOM
with Syft (artifact + `cosign attest --type spdxjson`) and signs it keylessly. Deploys verify signature and
attestation. Add `trivy-config: trivy.yaml` to that step so image scans use the same ignore file.

### ZAP baseline

Passive DAST of the dev storefront and API (`security.yml`, weekly/manual); findings open a GitHub issue.

## 3. CI commands by workflow

Copy-paste ready for the CI owner. Versions are pinned; bump them deliberately.

### `_go-service.yml` → job `security` (PR + main; `SERVICE_DIR=go/services/<svc>`)

```bash
# gitleaks (service dir, repo config)
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 \
  dir "/repo/$SERVICE_DIR" --config /repo/.gitleaks.toml --redact --no-banner --exit-code 1
# Trivy fs (HIGH+CRITICAL fail) — trivy-action: scan-type: fs, scan-ref: $SERVICE_DIR, trivy-config: trivy.yaml,
#   severity: CRITICAL,HIGH, exit-code: "1"; or the CLI:
trivy fs --config trivy.yaml --severity CRITICAL,HIGH --exit-code 1 "$SERVICE_DIR"
# govulncheck (reachable vulns incl. stdlib)
go install golang.org/x/vuln/cmd/govulncheck@v1.8.0
tools/security/govulncheck.sh "$(basename "$SERVICE_DIR")"
# Semgrep (our rules + registry packs); ERROR fails, SARIF to code scanning
pip install semgrep==1.180.0
tools/security/run_semgrep.sh --registry --sarif semgrep.sarif -- "$SERVICE_DIR"
```

### `_java-service.yml` → job `security` (`SERVICE_DIR=java/services/<svc>`)

```bash
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 \
  dir "/repo/$SERVICE_DIR" --config /repo/.gitleaks.toml --redact --no-banner --exit-code 1
trivy fs --config trivy.yaml --severity CRITICAL,HIGH --exit-code 1 "$SERVICE_DIR"
pip install semgrep==1.180.0
tools/security/run_semgrep.sh --registry --sarif semgrep.sarif -- "$SERVICE_DIR" java/libs
```

### `_web-app.yml` → new job `security` (`APP=web/apps/<app>`)

```bash
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 \
  dir "/repo/web" --config /repo/.gitleaks.toml --redact --no-banner --exit-code 1
pip install semgrep==1.180.0
tools/security/run_semgrep.sh --registry --sarif semgrep.sarif -- "web/apps/$APP" web/packages
# pnpm dependencies (CRITICAL fails): osv-scanner exits 1 on any finding, so gate on severity with jq
osv-scanner scan source --config tools/security/osv-scanner.toml --lockfile pnpm-lock.yaml --format json --output osv.json || true
jq -e '[.results[].packages[].groups[] | select((.max_severity // "0" | tonumber) >= 9)] | length == 0' osv.json
```

### `.github/actions/publish-image` → step "Trivy image scan"

```yaml
with:
  image-ref: ${{ steps.meta.outputs.image }}@${{ steps.build.outputs.digest }}
  trivy-config: trivy.yaml
  severity: CRITICAL
  exit-code: "1"
```

### `security.yml` (main, weekly, manual)

```bash
# gitleaks, full history
docker run --rm -v "$PWD:/repo" ghcr.io/gitleaks/gitleaks:v8.30.1 git /repo --config /repo/.gitleaks.toml --redact --no-banner --exit-code 1
# Trivy repo: SARIF of everything (MEDIUM+), then the CRITICAL gate
trivy fs --config trivy.yaml --format sarif --output trivy.sarif .
trivy fs --config trivy.yaml --scanners vuln,secret --severity CRITICAL --exit-code 1 .
trivy config --config trivy.yaml --severity CRITICAL,HIGH --exit-code 1 infra
# Semgrep, whole repo
tools/security/run_semgrep.sh --registry --sarif semgrep.sarif
# govulncheck, every Go module
tools/security/govulncheck.sh
# osv-scanner, every lockfile (report)
osv-scanner scan source --config tools/security/osv-scanner.toml -r --format sarif --output osv.sarif . || true
# OWASP Dependency-Check (weekly; secret NVD_API_KEY)
(cd java && ./mvnw -B org.owasp:dependency-check-maven:12.1.0:aggregate -DnvdApiKey="$NVD_API_KEY" -DfailBuildOnCVSS=9 \
  -Dformats=SARIF,HTML -DsuppressionFile=../tools/security/dependency-check-suppressions.xml)
# CodeQL and ZAP baseline: unchanged
```

Upload every `*.sarif` with `github/codeql-action/upload-sarif@v4` (distinct `category` per tool).

## 4. Triage

1. CRITICAL/HIGH: the owning team fixes or upgrades within the SLA (critical 48 h, high 7 days).
2. Not exploitable here: add an expiring entry to the tool's ignore file with the reason, reviewed by security.
3. Rule false positive: `nosemgrep`/`gitleaks:allow` on the line with a reason, or fix the rule (and its fixture).
4. Build-time-only dependency (docs portal, test tooling): still upgrade; ignore only with an expiry.

## 5. Baseline scan — 2026-10-10

Run locally in the dev container (working tree, no git history). Owners fix in their areas; this list is the
starting backlog.

| Severity | Tool | Finding | Where | Fix |
|---|---|---|---|---|
| CRITICAL | Trivy (npm) | CVE-2026-104848, CVE-2026-104849 tinypool 1.1.1 prototype pollution → RCE | `pnpm-lock.yaml` via `@docusaurus/core` (docs portal build) | upgrade Docusaurus / override `tinypool` ≥ 2.1.2 |
| CRITICAL | Trivy (IaC) | AZU-0012 storage account network rules default action should be deny | `infra/terraform/bootstrap/azure/main.tf:42` | `network_rules { default_action = "Deny" }` + allowed IPs/subnets |
| HIGH | govulncheck | Go 1.27.1 stdlib: GO-2026-6617, -6613, -6612, -6611, -6610, -6608, -6607, -6605, -6603, -6600, -6599 (net/http HTTP/2 + HTTP/1, crypto/tls, net/textproto, html/template) — reachable | every Go module | `toolchain go1.27.2` in every `go.mod` (and CI `GO_VERSION`) |
| HIGH | Trivy/govulncheck | golang.org/x/net v0.59.0 (CVE-2026-78669 et al.) | every Go module (`go.mod`, indirect) | `go get golang.org/x/net@v0.60.0` |
| HIGH | Trivy (Maven) | jackson-core / jackson-databind 3.1.5 (CVE-2026-89407, -89425, -68497, -91776, -91777) | all Java services (Spring Boot 4.1.1 BOM) | Spring Boot 4.1.4 or `jackson-bom.version` 3.1.7 in `platform-parent` |
| HIGH | Trivy (Maven) | lz4-java 1.10.1 (CVE-2026-106451) | `java/libs/platform-events` (kafka-clients) | manage `at.yawk.lz4:lz4-java` 1.11.4 |
| HIGH | Trivy (Go) | moby/go-archive v0.2.0 (CVE-2026-17106) | 10 Go modules (testcontainers, test-only) | bump testcontainers-go |
| HIGH | Trivy (npm) | glob 10.3.3 (CVE-2025-64756), serialize-javascript 6.0.2 (GHSA-5c6j-r48x-rmvq) | `pnpm-lock.yaml` (build tooling: `@node-minify/core`, webpack plugins of the docs portal) | upgrade parents / pnpm overrides |
| MEDIUM | Trivy (IaC) | KSV-0013 image tag `latest`, KSV-0125 untrusted registry (charts rendered with default values; tags/registry are set by GitOps) | `*/deploy/helm/templates/all.yaml:52` | set `image.tag`/registry defaults in chart values or ignore with reason |
| MEDIUM | Trivy (IaC) | DS-0013 `RUN cd …` | `go/services/*/Dockerfile:8,12` | use `WORKDIR` |
| MEDIUM | Trivy (IaC) | AZU-0057 storage logging disabled | `infra/terraform/bootstrap/azure/main.tf:42` | enable blob logging |
| — | Semgrep | 0 ERROR; 116 WARNING `sql-string-concatenation`/`sql-string-building` (dynamic WHERE builders with bound values; reviewed sample: no user input in SQL text) | Java persistence adapters, `go/services/notifications/internal/adapter/postgres/notifications.go:158,191,269,375` | keep fragments constant; review queue |
| — | gitleaks | 0 findings with `.gitleaks.toml` (11 before allowlisting: all test fixtures/local defaults) | — | — |

Trivy totals for the repo (fs, vuln): 2 CRITICAL, 120 HIGH, 156 MEDIUM occurrences (per lockfile/module).
