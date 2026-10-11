# AfroTransact v2 — developer entry points. `make` or `make help` lists the targets.
SHELL := /usr/bin/env bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

COMPOSE      := docker compose -f infra/local/docker-compose.yml
HELM         ?= $(shell command -v helm 2>/dev/null || echo $(HOME)/go/bin/helm)
KUBECONFORM  ?= $(shell command -v kubeconform 2>/dev/null || echo $(HOME)/go/bin/kubeconform)
GOLANGCI     ?= $(shell command -v golangci-lint 2>/dev/null || echo $(HOME)/go/bin/golangci-lint)
# Same versions as CI (.github/workflows/_go-service.yml): golangci-lint must be built with the repo's Go toolchain.
GO_TOOLCHAIN       := go1.27.2
GOLANGCI_VERSION   := v2.14.0
GO_MODULES   := $(sort $(wildcard go/services/*/go.mod) go/pkg/go.mod)
HELM_CHARTS  := $(sort $(dir $(wildcard java/services/*/deploy/helm/Chart.yaml go/services/*/deploy/helm/Chart.yaml)))
ENVS         := dev uat prod
SERVICE      ?=
ENV          ?= dev
TOFU         ?= $(shell command -v tofu 2>/dev/null || echo $(HOME)/go/bin/tofu)
CONFTEST     ?= $(shell command -v conftest 2>/dev/null || echo $(HOME)/go/bin/conftest)

.PHONY: help up up-infra down logs ps reset apps-up apps-down \
        java-verify go-verify verify tools dev-up dev-down dev-status dev-logs dev-restart dev-links \
        helm-lint gitops-render rules dashboards observability-check \
        env-check render tf-check security coverage smoke-carriers \
        web-dev web-build backlog seed seed-test

help: ## List targets
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ------------------------------------------------------------------------------------------------ local stack
up: ## Start EVERYTHING locally (infra, services, web apps) and print all links — same as dev-up
	tools/dev/local.sh up $(SERVICES)

up-infra: ## Start only the full Docker infrastructure incl. observability (Grafana, Prometheus, Loki, Tempo, Kibana; ~12 GB)
	$(COMPOSE) up -d
	@infra/local/wait-healthy.sh 600
	@echo "Grafana http://localhost:3200 · Kafka UI http://localhost:8090 · Kibana http://localhost:5601 · Temporal http://localhost:8088 · Mailpit http://localhost:8025 · MinIO http://localhost:9001"

down: ## Stop everything make up started: web apps, services, infrastructure (keeps data; make reset wipes it)
	tools/dev/local.sh down
	$(COMPOSE) --profile apps down

logs: ## Follow logs (SERVICE=<name> for one service)
	$(COMPOSE) --profile apps logs -f --tail=200 $(SERVICE)

ps: ## Show local stack containers and health
	$(COMPOSE) --profile apps ps -a

reset: ## Stop the local stack and delete all volumes (databases, topics, indices, metrics)
	$(COMPOSE) --profile apps down -v --remove-orphans

apps-up: ## Build and start the containerised services too (compose profile "apps"; SERVICE=<name> for one)
	$(COMPOSE) --profile apps up -d --build $(SERVICE)

apps-down: ## Stop only the containerised services
	$(COMPOSE) --profile apps stop catalog search log-shipper

# ------------------------------------------------------------------------------------------------ build / test
java-verify: ## Java: checkstyle + unit + ArchUnit + Testcontainers ITs for the whole reactor
	cd java && ./mvnw -B verify

tools: ## Install golangci-lint $(GOLANGCI_VERSION) built with $(GO_TOOLCHAIN) (the version CI uses) into ~/go/bin
	GOTOOLCHAIN=$(GO_TOOLCHAIN) go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@$(GOLANGCI_VERSION)
	@$(HOME)/go/bin/golangci-lint version

go-verify: ## Go: vet, test and golangci-lint for go/pkg and every go/services/* module
	@set -e; lint=1; \
	if [ ! -x "$(GOLANGCI)" ]; then lint=0; echo "golangci-lint not found (PATH or ~/go/bin): lint skipped — run 'make tools'"; \
	elif ! "$(GOLANGCI)" version 2>/dev/null | grep -q "built with $(GO_TOOLCHAIN)"; then lint=0; \
	  echo "$(GOLANGCI) is not built with $(GO_TOOLCHAIN) ($$("$(GOLANGCI)" version 2>/dev/null | head -1)): lint skipped — run 'make tools'"; fi; \
	for mod in $(GO_MODULES); do \
	  dir=$$(dirname $$mod); echo "==> $$dir"; \
	  ( cd $$dir && go vet ./... && go test ./... ); \
	  if [ $$lint = 1 ]; then ( cd $$dir && "$(GOLANGCI)" run --config $(CURDIR)/go/.golangci.yml ./... ); fi; \
	done

verify: java-verify go-verify helm-lint ## Everything CI runs for back-end + deploy

.PHONY: template-gallery
template-gallery: ## Notifications: render every seeded template into build/template-gallery/index.html (Docker, or ARGS=--dsn=…)
	cd go/services/notifications && GOWORK=off go run ./cmd/template-gallery --out $(CURDIR)/build/template-gallery/index.html $(ARGS)

# ------------------------------------------------------------------------------------------------ deploy
helm-lint: ## Helm: dependency build + lint + template every chart for every env (+ platform-values) + kubeconform
	@set -e; for chart in $(HELM_CHARTS); do \
	  echo "==> $$chart"; \
	  $(HELM) dependency build $$chart >/dev/null; \
	  for env in $(ENVS); do \
	    $(HELM) lint $$chart -f $$chart/values-$$env.yaml -f infra/argocd/envs/$$env/platform-values.yaml >/dev/null; \
	    $(HELM) template x $$chart -n afrotransact-$$env -f $$chart/values.yaml -f $$chart/values-$$env.yaml \
	      -f infra/argocd/envs/$$env/platform-values.yaml \
	      | $(KUBECONFORM) -strict -summary -ignore-missing-schemas -kubernetes-version 1.33.0 | sed "s/^/   [$$env] /"; \
	  done; \
	done
	@$(MAKE) --no-print-directory gitops-render observability-check

gitops-render: ## Argo CD: emulate the ApplicationSets per env, render each app and kubeconform it (incl. CRDs)
	python3 infra/argocd/scripts/render_apps.py

rules: ## Regenerate SLO rule files + PrometheusRule CRs from infra/observability/rules
	python3 infra/observability/scripts/generate_rules.py

dashboards: ## Regenerate Grafana dashboards (JSON + Kubernetes ConfigMaps)
	python3 infra/observability/scripts/generate_dashboards.py

observability-check: ## Check generated rules are fresh, promtool-check rules, validate dashboards JSON
	python3 infra/observability/scripts/generate_rules.py --check
	@for f in infra/observability/grafana/dashboards/*.json; do python3 -m json.tool $$f >/dev/null || { echo "invalid JSON: $$f"; exit 1; }; done
	@if command -v promtool >/dev/null; then promtool check rules infra/observability/rules/*.yaml; \
	elif command -v docker >/dev/null; then docker run --rm --entrypoint /bin/sh -v $(CURDIR)/infra/observability:/obs:ro prom/prometheus:v3.6.0 -c 'promtool check rules /obs/rules/*.yaml'; \
	else echo "promtool not available — rule syntax check skipped"; fi

# ------------------------------------------------------------------------------------------------ environments (S188)
env-check: ## Environment guards: no hard-coded hosts, profiles + docs pages + trace sampling + sizing per env
	python3 tools/ci/check_env_urls.py
	python3 tools/ci/check_env_config.py

render: ## Render one service as Argo CD would for an env: make render SERVICE=cart ENV=uat
	@[ -n "$(SERVICE)" ] || { echo "usage: make render SERVICE=<service> [ENV=dev|uat|prod]"; exit 2; }
	@chart=""; for d in java/services/$(SERVICE)/deploy/helm go/services/$(SERVICE)/deploy/helm; do \
	  [ -d "$$d" ] && chart="$$d"; done; \
	[ -n "$$chart" ] || { echo "no chart for $(SERVICE)"; exit 2; }; \
	$(HELM) dependency build $$chart >/dev/null && \
	$(HELM) template $(SERVICE) $$chart -n afrotransact-$(ENV) -f $$chart/values.yaml -f $$chart/values-$(ENV).yaml \
	  -f infra/argocd/envs/$(ENV)/platform-values.yaml

# ------------------------------------------------------------------------------------------------ infra / security
tf-check: ## OpenTofu: fmt, module interface drift, conftest policy unit tests (validate needs providers: see infra/terraform/README.md)
	$(TOFU) fmt -check -recursive infra/terraform infra/cloudflare
	bash infra/terraform/scripts/sync-interface.sh --check
	$(CONFTEST) verify --policy infra/policy/terraform

security: ## Local security scans that work offline-first: gitleaks, Semgrep rules, govulncheck, Trivy (if installed)
	@if command -v gitleaks >/dev/null || [ -x $(HOME)/go/bin/gitleaks ]; then \
	  $$(command -v gitleaks || echo $(HOME)/go/bin/gitleaks) dir . --config .gitleaks.toml --redact --no-banner; \
	else echo "gitleaks not installed - skipped"; fi
	@if command -v semgrep >/dev/null; then tools/security/run_semgrep.sh; else echo "semgrep not installed - skipped"; fi
	@if command -v govulncheck >/dev/null || [ -x $(HOME)/go/bin/govulncheck ]; then tools/security/govulncheck.sh; \
	else echo "govulncheck not installed - skipped"; fi
	@if command -v trivy >/dev/null; then trivy fs --config trivy.yaml --severity CRITICAL,HIGH . && \
	  trivy config --config trivy.yaml --severity CRITICAL,HIGH infra; else echo "trivy not installed - skipped"; fi

coverage: ## Coverage summary + gate (SERVICE=<name> for one; Go modules are tested first with -short)
	tools/quality/coverage_report.sh --run-go $(if $(SERVICE),--service $(SERVICE),)

smoke-carriers: ## Carrier sandbox smoke against a running carrier-gateway (CARRIER_GATEWAY_URL, default localhost:8209)
	tools/smoke/carriers.sh

# ------------------------------------------------------------------------------------------------ web / misc
dev-up: ## Run everything locally: infra (Docker), services and the 3 web apps, then print all links (SERVICES=… subset, WEB=0)
	tools/dev/local.sh up $(SERVICES)

dev-down: ## Stop what dev-up started (keeps data)
	tools/dev/local.sh down

dev-status: ## Health of the services and infra started by dev-up
	tools/dev/local.sh status

dev-logs: ## Follow one service's log: make dev-logs SERVICE=orders
	tools/dev/local.sh logs $(SERVICE)

dev-restart: ## Rebuild and restart one service: make dev-restart SERVICE=delivery-runs
	tools/dev/local.sh restart $(SERVICE)

dev-links: ## Print every local URL (apps, API docs, infra) of what dev-up started
	tools/dev/local.sh links

seed: ## Demo data via the services' APIs: sellers, products with real photos, offers, stock, home content, pre-orders (ARGS="--only products")
	python3 tools/seed/seed.py $(ARGS)

seed-test: ## Unit tests of the demo-data seeder (tools/seed/tests, Python stdlib unittest)
	python3 -m unittest discover -s tools/seed/tests -t tools/seed

web-dev: ## Web apps on mock data (no back end needed): storefront :3000, seller center :3001, staff console :3002
	@command -v pnpm >/dev/null || { echo "pnpm not installed (corepack enable)"; exit 1; }
	pnpm --parallel --filter storefront --filter seller-center --filter staff-console run dev:mocks

web-build: ## Build the web apps (pnpm -C web build); skipped if web/ or pnpm is missing
	@if ! command -v pnpm >/dev/null; then echo "pnpm not installed — skipped"; \
	elif [ -f web/package.json ]; then pnpm -C web build; \
	elif [ -d web/apps ]; then pnpm -r --filter "./web/apps/**" build; \
	else echo "web/ not present — skipped"; fi

backlog: ## List backlog stories and their status
	python3 tools/backlog/mark.py --list
