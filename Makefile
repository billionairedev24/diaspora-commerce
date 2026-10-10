# AfroTransact v2 — developer entry points. `make` or `make help` lists the targets.
SHELL := /usr/bin/env bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

COMPOSE      := docker compose -f infra/local/docker-compose.yml
HELM         ?= $(shell command -v helm 2>/dev/null || echo $(HOME)/go/bin/helm)
KUBECONFORM  ?= $(shell command -v kubeconform 2>/dev/null || echo $(HOME)/go/bin/kubeconform)
GOLANGCI     ?= $(shell command -v golangci-lint 2>/dev/null || echo $(HOME)/go/bin/golangci-lint)
GO_MODULES   := $(sort $(wildcard go/services/*/go.mod) go/pkg/go.mod)
HELM_CHARTS  := $(sort $(dir $(wildcard java/services/*/deploy/helm/Chart.yaml go/services/*/deploy/helm/Chart.yaml)))
ENVS         := dev uat prod
SERVICE      ?=

.PHONY: help up down logs ps reset apps-up apps-down \
        java-verify go-verify verify \
        helm-lint gitops-render rules dashboards observability-check \
        web-dev web-build backlog

help: ## List targets
	@awk 'BEGIN {FS = ":.*?## "} /^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

# ------------------------------------------------------------------------------------------------ local stack
up: ## Start local infrastructure + observability (docker compose default profile) and wait for health
	$(COMPOSE) up -d
	@infra/local/wait-healthy.sh 600
	@echo "Grafana http://localhost:3200 · Kafka UI http://localhost:8090 · Kibana http://localhost:5601 · Temporal http://localhost:8088 · Mailpit http://localhost:8025 · MinIO http://localhost:9001"

down: ## Stop the local stack (keeps volumes)
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

go-verify: ## Go: vet, test and golangci-lint for go/pkg and every go/services/* module
	@set -e; for mod in $(GO_MODULES); do \
	  dir=$$(dirname $$mod); echo "==> $$dir"; \
	  ( cd $$dir && go vet ./... && go test ./... ); \
	  if [ -x "$(GOLANGCI)" ]; then ( cd $$dir && $(GOLANGCI) run --config $(CURDIR)/go/.golangci.yml ./... ); \
	  else echo "   golangci-lint not found (PATH or ~/go/bin) — lint skipped"; fi; \
	done

verify: java-verify go-verify helm-lint ## Everything CI runs for back-end + deploy

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

# ------------------------------------------------------------------------------------------------ web / misc
web-dev: ## Run the web apps in dev mode (pnpm -C web dev); skipped if web/ or pnpm is missing
	@if ! command -v pnpm >/dev/null; then echo "pnpm not installed — skipped"; \
	elif [ -f web/package.json ]; then pnpm -C web dev; \
	elif [ -d web/apps ]; then pnpm -r --parallel --filter "./web/apps/**" dev; \
	else echo "web/ not present — skipped"; fi

web-build: ## Build the web apps (pnpm -C web build); skipped if web/ or pnpm is missing
	@if ! command -v pnpm >/dev/null; then echo "pnpm not installed — skipped"; \
	elif [ -f web/package.json ]; then pnpm -C web build; \
	elif [ -d web/apps ]; then pnpm -r --filter "./web/apps/**" build; \
	else echo "web/ not present — skipped"; fi

backlog: ## List backlog stories and their status
	python3 tools/backlog/mark.py --list
