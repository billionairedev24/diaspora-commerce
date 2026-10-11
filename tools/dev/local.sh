#!/usr/bin/env bash
# Run AfroTransact locally: the core infrastructure in Docker, the back-end services as host processes (local
# profile, fixed ports from infra/local/PORTS.md), logs and PIDs under .local/.
#
#   tools/dev/local.sh up                      # infra + the default service set, then waits for health
#   tools/dev/local.sh up identity catalog     # only these services (plus infra)
#   tools/dev/local.sh status                  # what is running and healthy
#   tools/dev/local.sh logs delivery-runs      # follow one service's log
#   tools/dev/local.sh restart orders          # rebuild + restart one service
#   tools/dev/local.sh down                    # stop the services and the infra (keeps data; `make reset` wipes it)
#
# Make targets: make dev-up [SERVICES="identity catalog"], make dev-status, make dev-logs SERVICE=x, make dev-down.
# The web apps run separately against the real BFF: make dev-web (storefront :3000, staff console :3002).
#
# Only the infrastructure the apps need is started (no Grafana/Prometheus/Loki/Tempo/Kibana): the full `make up`
# stack asks for ~12 GB, more than Docker Desktop's default. Java services start one at a time (Flyway migrations).
#
# Your own Postgres / Valkey: if something already listens on localhost:5432 (or 6379) and it is not this stack's
# container, it is used instead (override with PG_EXTERNAL=0|1, VALKEY_EXTERNAL=0|1). For Postgres the script creates
# the service roles/databases and the extensions (postgis, pg_trgm, btree_gist) once, as a superuser
# (PG_SUPERUSER, default your login — Homebrew's default; PGPASSWORD if it needs one). PostGIS must be installed
# (Homebrew: brew install postgis). Temporal and OpenFGA (in Docker) then reach it via host.docker.internal.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="$ROOT/.local"
LOGS="$STATE/logs"
PIDS="$STATE/pids"
BIN="$STATE/bin"
COMPOSE=(docker compose -f "$ROOT/infra/local/docker-compose.yml")

INFRA_BASE=(kafka kafka-init elasticsearch elasticsearch-init temporal minio minio-init mailpit openfga-migrate openfga)
OVERRIDE="$STATE/compose.external-postgres.yml"

# Everything the storefront, checkout, pre-orders and the staff console use. Order matters: identity first.
DEFAULT_SERVICES=(identity catalog offers inventory payments orders delivery-runs content trust-ops
  geo media cart notifications search indexer promise gateway-bff)

declare -A PORT=(
  [identity]=8100 [catalog]=8101 [offers]=8102 [inventory]=8103 [orders]=8104 [payments]=8105 [seller]=8106
  [retail]=8107 [fulfilment]=8108 [bookings]=8109 [trust-ops]=8110 [tax-returns]=8111 [content]=8112
  [ai-gateway]=8113 [mcp-server]=8114 [delivery-runs]=8115
  [gateway-bff]=8200 [search]=8201 [indexer]=8202 [cart]=8203 [promise]=8204 [geo]=8205 [storefront]=8206
  [media]=8207 [notifications]=8208 [carrier-gateway]=8209 [authz-sync]=8210)

# Web apps (Next.js dev servers) against the local BFF, no mocks. WEB=0 skips them.
WEB_APPS=(storefront seller-center staff-console)
declare -A WEB_PORT=([storefront]=3000 [seller-center]=3001 [staff-console]=3002)

runtime_of() {
  if [[ -f "$ROOT/java/services/$1/pom.xml" ]]; then echo java
  elif [[ -f "$ROOT/go/services/$1/go.mod" ]]; then echo go
  else echo "unknown service: $1 (see infra/local/PORTS.md)" >&2; exit 2; fi
}

health_url() {
  if [[ "$(runtime_of "$1")" == java ]]; then echo "http://localhost:${PORT[$1]}/actuator/health"
  else echo "http://localhost:${PORT[$1]}/readyz"; fi
}

running() { [[ -f "$PIDS/$1.pid" ]] && kill -0 "$(cat "$PIDS/$1.pid")" 2>/dev/null; }

healthy() { curl -fsS -o /dev/null --max-time 2 "$(health_url "$1")"; }

wait_healthy() {
  local svc=$1 secs=${2:-180}
  for ((i = 0; i < secs; i += 2)); do
    if healthy "$svc"; then echo "  ✓ $svc  http://localhost:${PORT[$svc]}"; return 0; fi
    if ! running "$svc"; then echo "  ✗ $svc exited; last log lines:" >&2; tail -n 25 "$LOGS/$svc.log" >&2; return 1; fi
    sleep 2
  done
  echo "  ✗ $svc not healthy after ${secs}s (still starting?) — tools/dev/local.sh logs $svc" >&2
  return 1
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

ours_running() { [[ -n "$("${COMPOSE[@]}" ps -q --status running "$1" 2>/dev/null)" ]]; }

# external <flag value> <port> <compose service>: 1 when the host's own server should be used.
external() {
  case "${1:-auto}" in
    1|true|yes) echo 1 ;;
    0|false|no) echo 0 ;;
    *) if port_open "$2" && ! ours_running "$3"; then echo 1; else echo 0; fi ;;
  esac
}

setup_host_postgres() {
  command -v psql >/dev/null || { echo "psql not found: install the Postgres client (brew install libpq) or set PG_EXTERNAL=0." >&2; exit 1; }
  local su="${PG_SUPERUSER:-$(whoami)}"
  local q=(psql -h localhost -p 5432 -U "$su" -v ON_ERROR_STOP=1 -qtA)
  "${q[@]}" -d postgres -c 'select 1' >/dev/null 2>&1 || {
    echo "Can't connect to your Postgres on localhost:5432 as '$su' (set PG_SUPERUSER / PGPASSWORD, or PG_EXTERNAL=0 to use the container on another port: POSTGRES_HOST_PORT=5433)." >&2; exit 1; }
  [[ "$("${q[@]}" -d postgres -c "select count(*) from pg_available_extensions where name = 'postgis'")" == 1 ]] || {
    echo "Your Postgres has no PostGIS (geo, fulfilment need it): brew install postgis, then re-run." >&2; exit 1; }
  echo "==> preparing your Postgres: infra/local/postgres/create-local-databases.sql (idempotent)"
  PGOPTIONS="-c afrotransact.su_password=${PGPASSWORD:-}" "${q[@]}" -d postgres \
    -f "$ROOT/infra/local/postgres/create-local-databases.sql" >/dev/null
  mkdir -p "$STATE"
  cat >"$OVERRIDE" <<'YAML'
# Generated by tools/dev/local.sh: Temporal and OpenFGA use the Postgres on your machine instead of the container.
services:
  temporal:
    depends_on: !reset {}
    extra_hosts: ["host.docker.internal:host-gateway"]
    environment:
      POSTGRES_SEEDS: host.docker.internal
  openfga-migrate:
    depends_on: !reset {}
    extra_hosts: ["host.docker.internal:host-gateway"]
    environment:
      OPENFGA_DATASTORE_URI: postgres://openfga:openfga@host.docker.internal:5432/openfga?sslmode=disable
  openfga:
    extra_hosts: ["host.docker.internal:host-gateway"]
    environment:
      OPENFGA_DATASTORE_URI: postgres://openfga:openfga@host.docker.internal:5432/openfga?sslmode=disable
YAML
}

infra_up() {
  command -v docker >/dev/null || { echo "Docker is required (Docker Desktop)." >&2; exit 1; }
  docker info >/dev/null 2>&1 || { echo "Docker is not running: start Docker Desktop first." >&2; exit 1; }
  local infra=("${INFRA_BASE[@]}") waitfor=(kafka elasticsearch temporal)
  if [[ "$(external "${PG_EXTERNAL:-}" 5432 postgres)" == 1 ]]; then
    echo "==> using your Postgres on localhost:5432"
    setup_host_postgres
    COMPOSE+=(-f "$OVERRIDE")
  else
    rm -f "$OVERRIDE"
    infra=(postgres "${infra[@]}"); waitfor=(postgres "${waitfor[@]}")
  fi
  if [[ "$(external "${VALKEY_EXTERNAL:-}" 6379 valkey)" == 1 ]]; then
    echo "==> using your Valkey/Redis on localhost:6379"
  else
    infra=(valkey "${infra[@]}")
  fi
  echo "==> infrastructure: ${infra[*]}"
  "${COMPOSE[@]}" up -d "${infra[@]}"
  echo "==> waiting for ${waitfor[*]}"
  for ((i = 0; i < 300; i += 3)); do
    local unhealthy
    unhealthy=$("${COMPOSE[@]}" ps --format '{{.Service}} {{.Health}}' "${waitfor[@]}" 2>/dev/null \
      | awk '$2 != "healthy" && $2 != "" {print $1}')
    [[ -z "$unhealthy" ]] && { echo "  ✓ infrastructure healthy"; return 0; }
    sleep 3
  done
  echo "  ✗ still not healthy: $unhealthy — docker compose -f infra/local/docker-compose.yml ps" >&2
  exit 1
}

build() {
  local java=() go=()
  for svc in "$@"; do
    if [[ "$(runtime_of "$svc")" == java ]]; then java+=("services/$svc"); else go+=("$svc"); fi
  done
  mkdir -p "$BIN"
  if ((${#java[@]})); then
    echo "==> building Java: ${java[*]#services/}"
    (cd "$ROOT/java" && ./mvnw -q -B -DskipTests -pl "$(IFS=,; echo "${java[*]}")" -am package)
  fi
  for svc in "${go[@]}"; do
    echo "==> building Go: $svc"
    (cd "$ROOT/go/services/$svc" && go build -o "$BIN/$svc" "./cmd/$svc")
  done
}

# Traces/metrics go to an OpenTelemetry collector on :4318, which this lean stack does not run: switch export off so
# the logs stay readable. TELEMETRY=1 keeps it (your own collector on localhost:4318, e.g. feeding your Grafana).
telemetry_env() {
  [[ "${TELEMETRY:-0}" == 1 ]] && return 0
  export TRACE_SAMPLING=0 MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0 MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=false
  export OTEL_EXPORTER_OTLP_ENDPOINT_HOST=""
}

start() {
  local svc=$1
  mkdir -p "$LOGS" "$PIDS"
  telemetry_env
  if running "$svc"; then echo "  · $svc already running (pid $(cat "$PIDS/$svc.pid"))"; return 0; fi
  : >"$LOGS/$svc.log"
  if [[ "$(runtime_of "$svc")" == java ]]; then
    local jar
    jar=$(ls "$ROOT"/java/services/"$svc"/target/"$svc"-service-*.jar 2>/dev/null | grep -v -- '-plain' | head -1)
    [[ -n "$jar" ]] || { echo "no jar for $svc; run: tools/dev/local.sh up $svc" >&2; return 1; }
    # `cd …;` (not `cd … &&`): with `&&` the `&` would background the whole list and $! be a wrapper shell.
    (cd "$ROOT/java/services/$svc" || exit 1
      SPRING_PROFILES_ACTIVE=local nohup java -XX:TieredStopAtLevel=1 -Xmx512m -jar "$jar" >>"$LOGS/$svc.log" 2>&1 &
      echo $! >"$PIDS/$svc.pid")
  else
    (cd "$ROOT/go/services/$svc" || exit 1
      APP_ENV=local nohup "$BIN/$svc" >>"$LOGS/$svc.log" 2>&1 &
      echo $! >"$PIDS/$svc.pid")
  fi
}

stop() {
  local svc=$1
  if running "$svc"; then
    local pid; pid=$(cat "$PIDS/$svc.pid")
    # Children first (Next.js dev servers fork workers); pkill -P matches only this process's children.
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do running "$svc" || break; sleep 0.5; done
    running "$svc" && kill -9 "$(cat "$PIDS/$svc.pid")" 2>/dev/null || true
    echo "  · stopped $svc"
  fi
  rm -f "$PIDS/$svc.pid"
}

web_up() {
  [[ "${WEB:-1}" == 0 ]] && return 0
  command -v pnpm >/dev/null || { echo "pnpm not found: web apps skipped (corepack enable; then make dev-up again)." >&2; return 0; }
  if [[ ! -x "$ROOT/web/apps/storefront/node_modules/.bin/next" ]]; then
    echo "==> installing web dependencies (pnpm install)"
    (cd "$ROOT" && pnpm install --frozen-lockfile)
  fi
  echo "==> starting web apps (Next.js dev against the local BFF on :8200; first page load compiles, give it a minute)"
  local app name url
  for app in "${WEB_APPS[@]}"; do
    name="web-$app"
    if running "$name"; then echo "  · $app already running"; continue; fi
    : >"$LOGS/$name.log"
    (cd "$ROOT/web/apps/$app" || exit 1
      NEXT_PUBLIC_API_MOCKS=0 BFF_ORIGIN=http://localhost:8200 PUBLIC_WEB_URL=http://localhost:3000 \
        SELLER_CENTER_URL=http://localhost:3001 STAFF_CONSOLE_URL=http://localhost:3002 \
        nohup ./node_modules/.bin/next dev --port "${WEB_PORT[$app]}" >>"$LOGS/$name.log" 2>&1 &
      echo $! >"$PIDS/$name.pid")
  done
  for app in "${WEB_APPS[@]}"; do
    name="web-$app"; url="http://localhost:${WEB_PORT[$app]}"
    for ((i = 0; i < 180; i += 3)); do
      if grep -qE "Ready in|ready started|Local:" "$LOGS/$name.log" 2>/dev/null; then echo "  ✓ $app  $url"; break; fi
      running "$name" || { echo "  ✗ $app exited; last log lines:" >&2; tail -n 20 "$LOGS/$name.log" >&2; break; }
      sleep 3
    done
  done
}

links() {
  local svc
  echo
  echo "──────────────────────────────── AfroTransact on this machine ────────────────────────────────"
  echo "Apps"
  echo "  Storefront           http://localhost:3000          pre-orders: http://localhost:3000/pre-orders"
  echo "  Seller Center        http://localhost:3001"
  echo "  Staff console        http://localhost:3002          pre-orders: http://localhost:3002/preorders"
  echo "  Staff sign-in        admin@afrotransact.com / LocalAdmin2026!  (identity's local bootstrap admin)"
  echo "API docs (Scalar; each service also serves /swagger-ui.html or /openapi.yaml, and /redoc)"
  for svc in "${!PORT[@]}"; do
    [[ -f "$PIDS/$svc.pid" ]] || continue
    printf '  %-20s http://localhost:%s/docs\n' "$svc" "${PORT[$svc]}"
  done | sort
  echo "Infrastructure"
  echo "  Mailpit (mail, SMS)  http://localhost:8025"
  echo "  MinIO console        http://localhost:9001          minioadmin / minioadmin"
  echo "  Elasticsearch        http://localhost:9200          OpenFGA  http://localhost:8091"
  echo "  Kafka                localhost:9092                 Temporal localhost:7233"
  echo "  Postgres             localhost:5432                 Valkey   localhost:6379"
  echo "Manage"
  echo "  make dev-status | make dev-logs SERVICE=orders (or web-storefront) | make dev-links | make dev-down"
  echo "  Checkout needs a Stripe test key: export STRIPE_SECRET_KEY=sk_test_... before make dev-up, then"
  echo "  stripe listen --forward-to localhost:8105/v1/webhooks/stripe"
  echo "──────────────────────────────────────────────────────────────────────────────────────────────"
}

cmd_up() {
  local services=("$@")
  ((${#services[@]})) || services=("${DEFAULT_SERVICES[@]}")
  for svc in "${services[@]}"; do runtime_of "$svc" >/dev/null; done
  infra_up
  build "${services[@]}"
  echo "==> starting services (logs: .local/logs/<service>.log)"
  local failed=()
  for svc in "${services[@]}"; do
    start "$svc"
    # Java services migrate their database on start: one at a time keeps a laptop responsive.
    if [[ "$(runtime_of "$svc")" == java ]]; then wait_healthy "$svc" 240 || failed+=("$svc"); fi
  done
  for svc in "${services[@]}"; do
    [[ "$(runtime_of "$svc")" == go ]] && { wait_healthy "$svc" 60 || failed+=("$svc"); }
  done
  echo
  if ((${#failed[@]})); then
    echo "Started with problems: ${failed[*]} — see tools/dev/local.sh logs <service>" >&2
  fi
  web_up
  links
}

cmd_status() {
  printf '%-16s %-6s %-8s %s\n' SERVICE PORT STATE URL
  for svc in "${!PORT[@]}"; do
    [[ -f "$PIDS/$svc.pid" ]] || continue
    local state=down
    running "$svc" && state=starting
    running "$svc" && healthy "$svc" && state=healthy
    printf '%-16s %-6s %-8s %s\n' "$svc" "${PORT[$svc]}" "$state" "http://localhost:${PORT[$svc]}"
  done | sort
  for app in "${WEB_APPS[@]}"; do
    [[ -f "$PIDS/web-$app.pid" ]] || continue
    local st=down; running "web-$app" && st=running
    printf '%-16s %-6s %-8s %s\n' "web-$app" "${WEB_PORT[$app]}" "$st" "http://localhost:${WEB_PORT[$app]}"
  done
  echo
  [[ -f "$OVERRIDE" ]] && { echo "Postgres: yours (localhost:5432)"; COMPOSE+=(-f "$OVERRIDE"); }
  "${COMPOSE[@]}" ps --format 'table {{.Service}}\t{{.Status}}' 2>/dev/null || true
}

cmd_down() {
  echo "==> stopping services"
  if [[ -d "$PIDS" ]]; then for f in "$PIDS"/*.pid; do [[ -e "$f" ]] && stop "$(basename "$f" .pid)"; done; fi
  echo "==> stopping infrastructure (data kept; 'make reset' deletes it). Your own Postgres/Valkey are left running."
  [[ -f "$OVERRIDE" ]] && COMPOSE+=(-f "$OVERRIDE")
  "${COMPOSE[@]}" stop postgres valkey "${INFRA_BASE[@]}" >/dev/null 2>&1 || true
}

case "${1:-}" in
  up) shift; cmd_up "$@" ;;
  down) cmd_down ;;
  status) cmd_status ;;
  links) links ;;
  logs) [[ -n "${2:-}" ]] || { echo "usage: $0 logs <service>" >&2; exit 2; }; tail -n 200 -f "$LOGS/$2.log" ;;
  restart) [[ -n "${2:-}" ]] || { echo "usage: $0 restart <service>" >&2; exit 2; }
    stop "$2"; build "$2"; start "$2"; wait_healthy "$2" 240 ;;
  *) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
