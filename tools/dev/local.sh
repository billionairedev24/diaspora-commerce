#!/usr/bin/env bash
# Run AfroTransact locally: the core infrastructure in Docker, the back-end services as host processes (local
# profile, fixed ports from infra/local/PORTS.md), the three web apps against them; logs and PIDs under .local/.
#
#   tools/dev/local.sh up                      # infra + the default services + web apps, then prints every link
#   tools/dev/local.sh up identity catalog     # only these services (plus infra and the web apps; WEB=0 skips them)
#   tools/dev/local.sh status                  # what is running and healthy
#   tools/dev/local.sh links                   # print every link again
#   tools/dev/local.sh logs delivery-runs      # follow one log (web apps: web-storefront, web-staff-console, …)
#   tools/dev/local.sh restart orders          # rebuild + restart one service
#   tools/dev/local.sh seed                    # demo data (tools/seed): sellers, products with photos, home content
#   tools/dev/local.sh down                    # stop everything (keeps data; `make reset` wipes the Docker volumes)
#
# Make targets: make up (= dev-up), make down, make dev-status, make dev-links, make dev-logs SERVICE=x,
# make dev-restart SERVICE=x, make seed. Works with macOS's bash 3.2.
#
# `up` seeds the demo dataset when the catalog is empty (tools/seed/seed.py --if-empty; SEED=0 skips it).
#
# Only the infrastructure the apps need is started (no Grafana/Prometheus/Loki/Tempo/Kibana: `make up-infra` has
# those, ~12 GB). Java services start one at a time (Flyway migrations). Connection pools are kept small (DB_POOL,
# default 3) because ~20 services share one Postgres.
#
# Your own Postgres / Valkey: if something already listens on localhost:5432 (or 6379) and it is not this stack's
# container, it is used instead (force with PG_EXTERNAL=0|1, VALKEY_EXTERNAL=0|1). Postgres is prepared with
# infra/local/postgres/create-local-databases.sql (roles, databases, PostGIS/pg_trgm/btree_gist; also runnable from
# DBeaver) as a superuser: PG_SUPERUSER (default your login, as on Homebrew), PGPASSWORD if it needs one. PostGIS must
# be installed (brew install postgis). Temporal and OpenFGA (in Docker) reach it via host.docker.internal.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE="$ROOT/.local"
LOGS="$STATE/logs"
PIDS="$STATE/pids"
BIN="$STATE/bin"
COMPOSE_FILE="$ROOT/infra/local/docker-compose.yml"
OVERRIDE="$STATE/compose.external-postgres.yml"

INFRA_BASE="kafka kafka-init elasticsearch elasticsearch-init temporal minio minio-init mailpit openfga-migrate openfga"

# Everything the storefront, checkout, pre-orders and the staff console use. Order matters: identity first.
DEFAULT_SERVICES="identity catalog offers inventory payments orders delivery-runs content trust-ops seller
  geo media cart notifications search indexer promise gateway-bff"

ALL_SERVICES="identity catalog offers inventory orders payments seller retail fulfilment bookings trust-ops tax-returns
  content ai-gateway mcp-server delivery-runs gateway-bff search indexer cart promise geo storefront media
  notifications carrier-gateway authz-sync"

# Web apps (Next.js dev servers) against the local BFF, no mocks. WEB=0 skips them.
WEB_APPS="storefront seller-center staff-console"

port_of() {
  case "$1" in
    identity) echo 8100 ;; catalog) echo 8101 ;; offers) echo 8102 ;; inventory) echo 8103 ;; orders) echo 8104 ;;
    payments) echo 8105 ;; seller) echo 8106 ;; retail) echo 8107 ;; fulfilment) echo 8108 ;; bookings) echo 8109 ;;
    trust-ops) echo 8110 ;; tax-returns) echo 8111 ;; content) echo 8112 ;; ai-gateway) echo 8113 ;;
    mcp-server) echo 8114 ;; delivery-runs) echo 8115 ;;
    gateway-bff) echo 8200 ;; search) echo 8201 ;; indexer) echo 8202 ;; cart) echo 8203 ;; promise) echo 8204 ;;
    geo) echo 8205 ;; storefront) echo 8206 ;; media) echo 8207 ;; notifications) echo 8208 ;;
    carrier-gateway) echo 8209 ;; authz-sync) echo 8210 ;;
    *) echo "unknown service: $1 (see infra/local/PORTS.md)" >&2; exit 2 ;;
  esac
}

web_port_of() {
  case "$1" in storefront) echo 3000 ;; seller-center) echo 3001 ;; staff-console) echo 3002 ;; esac
}

compose() {
  if [[ -f "$OVERRIDE" ]]; then docker compose -f "$COMPOSE_FILE" -f "$OVERRIDE" "$@"
  else docker compose -f "$COMPOSE_FILE" "$@"; fi
}

runtime_of() {
  if [[ -f "$ROOT/java/services/$1/pom.xml" ]]; then echo java
  elif [[ -f "$ROOT/go/services/$1/go.mod" ]]; then echo go
  else echo "unknown service: $1 (see infra/local/PORTS.md)" >&2; exit 2; fi
}

health_url() {
  if [[ "$(runtime_of "$1")" == java ]]; then echo "http://localhost:$(port_of "$1")/actuator/health"
  else echo "http://localhost:$(port_of "$1")/readyz"; fi
}

running() { [[ -f "$PIDS/$1.pid" ]] && kill -0 "$(cat "$PIDS/$1.pid")" 2>/dev/null; }

healthy() { curl -fs -o /dev/null --max-time 2 "$(health_url "$1")"; }

wait_healthy() {
  local svc=$1 secs=${2:-180} i
  for ((i = 0; i < secs; i += 2)); do
    if healthy "$svc"; then echo "  ✓ $svc  http://localhost:$(port_of "$svc")"; return 0; fi
    if ! running "$svc"; then echo "  ✗ $svc exited; last log lines:" >&2; tail -n 25 "$LOGS/$svc.log" >&2; return 1; fi
    sleep 2
  done
  echo "  ✗ $svc not healthy after ${secs}s (still starting?) — tools/dev/local.sh logs $svc" >&2
  return 1
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

ours_running() { [[ -n "$(docker compose -f "$COMPOSE_FILE" ps -q --status running "$1" 2>/dev/null)" ]]; }

# external <flag value> <port> <compose service>: 1 when the host's own server should be used.
external() {
  case "${1:-auto}" in
    1|true|yes) echo 1 ;;
    0|false|no) echo 0 ;;
    *) if port_open "$2" && ! ours_running "$3"; then echo 1; else echo 0; fi ;;
  esac
}

# How to reach your Postgres as a superuser, first that works: PG_SUPERUSER over TCP (with PGPASSWORD if needed);
# your login over the local socket (Homebrew, Postgres.app, or Ubuntu after `sudo -u postgres createuser -s $USER`);
# your login over TCP; Ubuntu/Debian's packaged server via `sudo -u postgres` (asks for your sudo password once).
PSQL_MODE=""
psql_su() {
  case "$PSQL_MODE" in
    tcp) psql -h localhost -p 5432 -U "${PG_SUPERUSER:-$(whoami)}" -v ON_ERROR_STOP=1 -qtA "$@" ;;
    socket) psql -U "$(whoami)" -v ON_ERROR_STOP=1 -qtA "$@" ;;
    sudo) sudo -u postgres psql -p 5432 -v ON_ERROR_STOP=1 -qtA "$@" ;;
  esac
}

pick_psql_mode() {
  local mode modes
  if [[ -n "${PG_SUPERUSER:-}" ]]; then modes="tcp"; else modes="socket tcp"; fi
  if [[ -z "${PG_SUPERUSER:-}" && "$(uname -s)" == Linux ]] && command -v sudo >/dev/null && id postgres >/dev/null 2>&1; then
    modes="$modes sudo"
  fi
  for mode in $modes; do
    PSQL_MODE=$mode
    if [[ $mode == sudo ]]; then echo "  (connecting as the postgres system user: sudo may ask for your password)"; fi
    if [[ "$(psql_su -d postgres -c 'select rolsuper from pg_roles where rolname = current_user' 2>/dev/null)" == t ]]; then
      return 0
    fi
  done
  PSQL_MODE=""
  return 1
}

# Docker Desktop (macOS/Windows) reaches the host as host.docker.internal; Docker Engine on Linux reaches a Postgres
# that listens on 127.0.0.1 only through host networking.
docker_desktop() { [[ "$(docker info --format '{{.OperatingSystem}}' 2>/dev/null)" == *"Docker Desktop"* ]]; }

setup_host_postgres() {
  command -v psql >/dev/null || {
    echo "psql not found: install the Postgres client (brew install libpq) or set PG_EXTERNAL=0." >&2; exit 1; }
  pick_psql_mode || {
    echo "Can't reach your Postgres on localhost:5432 as a superuser. One of:" >&2
    echo "  PG_SUPERUSER=<superuser> PGPASSWORD=<password> make up" >&2
    echo "  sudo -u postgres createuser -s \$USER        # Ubuntu: lets your login in over the local socket" >&2
    echo "  PG_EXTERNAL=0 POSTGRES_HOST_PORT=5433 make up  # use the Docker Postgres next to yours instead" >&2
    exit 1; }
  [[ "$(psql_su -d postgres -c "select count(*) from pg_available_extensions where name = 'postgis'")" == 1 ]] || {
    echo "Your Postgres has no PostGIS (geo, fulfilment need it): brew install postgis, then re-run." >&2; exit 1; }
  local maxconn
  maxconn=$(psql_su -d postgres -c 'show max_connections')
  if ((maxconn < 200)); then
    echo "  ! your Postgres allows $maxconn connections; the whole stack needs ~150. Raise it once, then restart Postgres:" >&2
    echo "      psql -h localhost -d postgres -c 'ALTER SYSTEM SET max_connections = 300'" >&2
    echo "      brew services restart postgresql@17      # or restart it the way you installed it" >&2
    echo "    Continuing with small connection pools; below ~150 some services may fail to start." >&2
  fi
  echo "==> preparing your Postgres: infra/local/postgres/create-local-databases.sql (idempotent)"
  # Read through stdin: with `sudo -u postgres` the file may not be readable by the postgres user.
  PGOPTIONS="-c afrotransact.su_password=${PGPASSWORD:-}" psql_su -d postgres \
    <"$ROOT/infra/local/postgres/create-local-databases.sql" >/dev/null
  mkdir -p "$STATE"
  if ! docker_desktop; then
    cat >"$OVERRIDE" <<'YAML'
# Generated by tools/dev/local.sh (Docker Engine on Linux): Temporal and OpenFGA share the host's network to use the
# Postgres on this machine, which usually listens on 127.0.0.1 only.
services:
  temporal:
    depends_on: !reset {}
    network_mode: host
    ports: !reset []
    environment:
      POSTGRES_SEEDS: 127.0.0.1
  openfga-migrate:
    depends_on: !reset {}
    network_mode: host
    environment:
      OPENFGA_DATASTORE_URI: postgres://openfga:openfga@127.0.0.1:5432/openfga?sslmode=disable
  openfga:
    network_mode: host
    ports: !reset []
    environment:
      OPENFGA_DATASTORE_URI: postgres://openfga:openfga@127.0.0.1:5432/openfga?sslmode=disable
      OPENFGA_DATASTORE_MAX_OPEN_CONNS: 5
      OPENFGA_HTTP_ADDR: 0.0.0.0:8091
      OPENFGA_GRPC_ADDR: 0.0.0.0:8092
      OPENFGA_METRICS_ADDR: 0.0.0.0:2112
    healthcheck:
      test: ["CMD", "/hc/busybox", "wget", "-qO-", "http://127.0.0.1:8091/healthz"]
YAML
    return 0
  fi
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
      OPENFGA_DATASTORE_MAX_OPEN_CONNS: 5
YAML
}

infra_up() {
  command -v docker >/dev/null || { echo "Docker is required (Docker Desktop)." >&2; exit 1; }
  docker info >/dev/null 2>&1 || { echo "Docker is not running: start Docker Desktop first." >&2; exit 1; }
  local infra="$INFRA_BASE" waitfor="kafka elasticsearch temporal" i unhealthy=""
  if [[ "$(external "${PG_EXTERNAL:-}" 5432 postgres)" == 1 ]]; then
    echo "==> using your Postgres on localhost:5432"
    setup_host_postgres
  else
    rm -f "$OVERRIDE"
    infra="postgres $infra"; waitfor="postgres $waitfor"
  fi
  if [[ "$(external "${VALKEY_EXTERNAL:-}" 6379 valkey)" == 1 ]]; then
    echo "==> using your Valkey/Redis on localhost:6379"
  else
    infra="valkey $infra"
  fi
  echo "==> infrastructure: $infra"
  # shellcheck disable=SC2086 # word splitting of the service lists is intended
  compose up -d $infra
  echo "==> waiting for $waitfor"
  for ((i = 0; i < 300; i += 3)); do
    # shellcheck disable=SC2086
    unhealthy=$(compose ps --format '{{.Service}} {{.Health}}' $waitfor 2>/dev/null \
      | awk '$2 != "healthy" && $2 != "" {print $1}' || true)
    [[ -z "$unhealthy" ]] && { echo "  ✓ infrastructure healthy"; return 0; }
    sleep 3
  done
  echo "  ✗ still not healthy: $unhealthy — docker compose -f infra/local/docker-compose.yml ps" >&2
  exit 1
}

build() {
  local java="" go="" svc
  for svc in "$@"; do
    if [[ "$(runtime_of "$svc")" == java ]]; then java="$java${java:+,}services/$svc"; else go="$go $svc"; fi
  done
  mkdir -p "$BIN"
  if [[ -n "$java" ]]; then
    echo "==> building Java: ${java//services\//}"
    (cd "$ROOT/java" && ./mvnw -q -B -DskipTests -pl "$java" -am package)
  fi
  for svc in $go; do
    echo "==> building Go: $svc"
    (cd "$ROOT/go/services/$svc" && go build -o "$BIN/$svc" "./cmd/$svc")
  done
}

# Traces/metrics go to an OpenTelemetry collector on :4318, which this lean stack does not run: switch export off so
# the logs stay readable. TELEMETRY=1 keeps it (your own collector on localhost:4318, e.g. feeding your Grafana).
telemetry_env() {
  if [[ "${TELEMETRY:-0}" == 1 ]]; then return 0; fi
  export TRACE_SAMPLING=0 MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0 MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED=false
  export OTEL_EXPORTER_OTLP_ENDPOINT_HOST=""
}

# ~20 services share one Postgres: a stock server allows 100 connections (the compose one 300). Keep pools small.
pool_env() {
  local svc=$1 url=""
  export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="${DB_POOL:-3}" SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=1
  unset DB_URL
  if [[ "$(runtime_of "$svc")" == go && -f "$ROOT/go/services/$svc/config/local.yaml" ]]; then
    url=$( { grep -E '^[[:space:]]*(databaseUrl|url):[[:space:]]*"?postgres(ql)?://' \
      "$ROOT/go/services/$svc/config/local.yaml" || true; } | head -1 | sed -E 's/^[^:]+:[[:space:]]*"?([^" #]+)"?.*/\1/')
    if [[ -n "$url" && "$url" != *pool_max_conns* ]]; then
      if [[ "$url" == *\?* ]]; then url="$url&pool_max_conns=${DB_POOL:-3}"; else url="$url?pool_max_conns=${DB_POOL:-3}"; fi
      export DB_URL="$url"
    fi
  fi
}

start() {
  local svc=$1 jar
  mkdir -p "$LOGS" "$PIDS"
  if running "$svc"; then echo "  · $svc already running (pid $(cat "$PIDS/$svc.pid"))"; return 0; fi
  telemetry_env
  pool_env "$svc"
  # media signs uploads for this stack's MinIO (infra/local/docker-compose.yml root credentials).
  export MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-minioadmin}" MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-minioadmin}"
  : >"$LOGS/$svc.log"
  if [[ "$(runtime_of "$svc")" == java ]]; then
    jar=$( { ls "$ROOT"/java/services/"$svc"/target/"$svc"-service-*.jar 2>/dev/null || true; } | grep -v -- '-plain' | head -1 || true)
    [[ -n "$jar" ]] || { echo "no jar for $svc; run: tools/dev/local.sh restart $svc" >&2; return 1; }
    # `cd …` on its own line (not `cd … &&`): with `&&` the `&` would background the whole list and $! be a wrapper.
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
  local svc=$1 pid
  if running "$svc"; then
    pid=$(cat "$PIDS/$svc.pid")
    # Children first (Next.js dev servers fork workers); pkill -P matches only this process's children.
    pkill -TERM -P "$pid" 2>/dev/null || true
    kill "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do running "$svc" || break; sleep 0.5; done
    if running "$svc"; then kill -9 "$pid" 2>/dev/null || true; fi
    echo "  · stopped $svc"
  fi
  rm -f "$PIDS/$svc.pid"
}

web_up() {
  local app name url port i
  if [[ "${WEB:-1}" == 0 ]]; then return 0; fi
  command -v pnpm >/dev/null || { echo "pnpm not found: web apps skipped (corepack enable; then make up again)." >&2; return 0; }
  if [[ ! -x "$ROOT/web/apps/storefront/node_modules/.bin/next" ]]; then
    echo "==> installing web dependencies (pnpm install)"
    (cd "$ROOT" && pnpm install --frozen-lockfile)
  fi
  echo "==> starting web apps (Next.js dev against the local BFF on :8200; the first page load compiles for a minute)"
  mkdir -p "$LOGS" "$PIDS"
  for app in $WEB_APPS; do
    name="web-$app"; port=$(web_port_of "$app")
    if running "$name"; then echo "  · $app already running"; continue; fi
    : >"$LOGS/$name.log"
    (cd "$ROOT/web/apps/$app" || exit 1
      NEXT_PUBLIC_API_MOCKS=0 BFF_ORIGIN=http://localhost:8200 PUBLIC_WEB_URL=http://localhost:3000 \
        SELLER_CENTER_URL=http://localhost:3001 STAFF_CONSOLE_URL=http://localhost:3002 \
        nohup ./node_modules/.bin/next dev --port "$port" >>"$LOGS/$name.log" 2>&1 &
      echo $! >"$PIDS/$name.pid")
  done
  for app in $WEB_APPS; do
    name="web-$app"; url="http://localhost:$(web_port_of "$app")"
    for ((i = 0; i < 180; i += 3)); do
      if grep -qE "Ready in|ready started|Local:" "$LOGS/$name.log" 2>/dev/null; then echo "  ✓ $app  $url"; break; fi
      if ! running "$name"; then echo "  ✗ $app exited; last log lines:" >&2; tail -n 20 "$LOGS/$name.log" >&2; break; fi
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
  for svc in $ALL_SERVICES; do
    [[ -f "$PIDS/$svc.pid" ]] || continue
    printf '  %-20s http://localhost:%s/docs\n' "$svc" "$(port_of "$svc")"
  done
  echo "Infrastructure"
  echo "  Mailpit (mail, SMS)  http://localhost:8025"
  echo "  MinIO console        http://localhost:9001          minioadmin / minioadmin"
  echo "  Elasticsearch        http://localhost:9200          OpenFGA  http://localhost:8091"
  echo "  Kafka                localhost:9092                 Temporal localhost:7233"
  echo "  Postgres             localhost:5432                 Valkey   localhost:6379"
  echo "Manage"
  echo "  make dev-status | make dev-logs SERVICE=orders (or web-storefront) | make dev-links | make down"
  echo "  Checkout needs a Stripe test key: export STRIPE_SECRET_KEY=sk_test_... before make up, then"
  echo "  stripe listen --forward-to localhost:8105/v1/webhooks/stripe"
  echo "──────────────────────────────────────────────────────────────────────────────────────────────"
}

cmd_up() {
  local services="$DEFAULT_SERVICES" svc failed=""
  if [[ $# -gt 0 ]]; then services="$*"; fi
  for svc in $services; do runtime_of "$svc" >/dev/null; done
  infra_up
  # shellcheck disable=SC2086
  build $services
  echo "==> starting services (logs: .local/logs/<service>.log)"
  for svc in $services; do
    start "$svc" || failed="$failed $svc"
    # Java services migrate their database on start: one at a time keeps a laptop responsive.
    if [[ "$(runtime_of "$svc")" == java ]]; then wait_healthy "$svc" 240 || failed="$failed $svc"; fi
  done
  for svc in $services; do
    if [[ "$(runtime_of "$svc")" == go ]]; then wait_healthy "$svc" 60 || failed="$failed $svc"; fi
  done
  echo
  if [[ -n "$failed" ]]; then
    echo "Started with problems:$failed — see: tools/dev/local.sh logs <service>" >&2
  fi
  web_up
  seed_if_empty
  links
}

# Demo data on a fresh stack: sellers, ~75 products with photos, offers, stock, home content, pre-orders (tools/seed).
seed_if_empty() {
  if [[ "${SEED:-1}" == 0 ]]; then return 0; fi
  command -v python3 >/dev/null || { echo "python3 not found: demo data skipped (make seed later)" >&2; return 0; }
  echo "==> demo data (tools/seed; only when the catalog is empty, SEED=0 skips it; log: .local/logs/seed.log)"
  mkdir -p "$LOGS"
  python3 "$ROOT/tools/seed/seed.py" --if-empty 2>&1 | tee "$LOGS/seed.log" \
    | grep -E '^(seed:|services up|image sources|  [✗!]|  [a-z ]{3,20} {2,}|  attribution)' || true
}

cmd_status() {
  local svc app state
  printf '%-18s %-6s %-9s %s\n' SERVICE PORT STATE URL
  for svc in $ALL_SERVICES; do
    [[ -f "$PIDS/$svc.pid" ]] || continue
    state=down
    if running "$svc"; then state=starting; if healthy "$svc"; then state=healthy; fi; fi
    printf '%-18s %-6s %-9s %s\n' "$svc" "$(port_of "$svc")" "$state" "http://localhost:$(port_of "$svc")"
  done
  for app in $WEB_APPS; do
    [[ -f "$PIDS/web-$app.pid" ]] || continue
    state=down; if running "web-$app"; then state=running; fi
    printf '%-18s %-6s %-9s %s\n' "web-$app" "$(web_port_of "$app")" "$state" "http://localhost:$(web_port_of "$app")"
  done
  echo
  if [[ -f "$OVERRIDE" ]]; then echo "Postgres: yours (localhost:5432)"; fi
  compose ps --format 'table {{.Service}}\t{{.Status}}' 2>/dev/null || true
}

cmd_down() {
  local f
  echo "==> stopping web apps and services"
  if [[ -d "$PIDS" ]]; then
    for f in "$PIDS"/*.pid; do [[ -e "$f" ]] && stop "$(basename "$f" .pid)"; done
  fi
  echo "==> stopping infrastructure (data kept; 'make reset' deletes it). Your own Postgres/Valkey keep running."
  # shellcheck disable=SC2086
  compose stop postgres valkey $INFRA_BASE >/dev/null 2>&1 || true
}

case "${1:-}" in
  up) shift; cmd_up "$@" ;;
  down) cmd_down ;;
  status) cmd_status ;;
  links) links ;;
  logs) [[ -n "${2:-}" ]] || { echo "usage: $0 logs <service>" >&2; exit 2; }; tail -n 200 -f "$LOGS/$2.log" ;;
  seed) shift; python3 "$ROOT/tools/seed/seed.py" "$@" ;;
  restart) [[ -n "${2:-}" ]] || { echo "usage: $0 restart <service>" >&2; exit 2; }
    stop "$2"; build "$2"; start "$2"; wait_healthy "$2" 240 ;;
  *) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
