#!/usr/bin/env bash
# Run AfroTransact locally: the core infrastructure in Docker, the back-end services as host processes (local
# profile, fixed ports from infra/local/PORTS.md), the three web apps against them; logs and PIDs under .local/.
#
#   tools/dev/local.sh up                      # infra + the default services + web apps, then prints every link
#   tools/dev/local.sh up identity catalog     # only these services (plus infra and the web apps; WEB=0 skips them)
#   tools/dev/local.sh status                  # what is running and healthy
#   tools/dev/local.sh links                   # print every link again (apps, docs portal, MCP, API docs)
#   tools/dev/local.sh logs delivery-runs      # follow one log (web apps: web-storefront, web-staff-console, …)
#   tools/dev/local.sh restart orders          # rebuild + restart one service
#   tools/dev/local.sh mobile                  # only the Expo apps (customer :8081, driver :8082)
#   tools/dev/local.sh docs                    # only the developer portal (:3100) and Storybook (:6006)
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

# The services need Java 25 (maven-enforcer). When the default `java` is older, use a JDK 25 that is installed:
# macOS java_home, then the usual Linux locations. JAVA_HOME pointing at 25 already is kept.
java_major() { "$1/bin/java" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print v[1]; exit}'; }
pick_jdk25() {
  local cand
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" && "$(java_major "$JAVA_HOME")" -ge 25 ]]; then return 0; fi
  if [[ -x /usr/libexec/java_home ]] && cand=$(/usr/libexec/java_home -v 25+ 2>/dev/null); then
    export JAVA_HOME="$cand" PATH="$cand/bin:$PATH"; return 0
  fi
  for cand in /usr/lib/jvm/java-25-openjdk* /usr/lib/jvm/*25* /opt/homebrew/opt/openjdk@25 /usr/local/opt/openjdk@25 \
    "$HOME"/.sdkman/candidates/java/25*; do
    if [[ -x "$cand/bin/java" && "$(java_major "$cand")" -ge 25 ]]; then export JAVA_HOME="$cand" PATH="$cand/bin:$PATH"; return 0; fi
  done
  echo "  ! Java 25 not found (brew install openjdk@25 / apt install openjdk-25-jdk, or set JAVA_HOME); Java builds will fail" >&2
}
pick_jdk25
STATE="$ROOT/.local"
LOGS="$STATE/logs"
PIDS="$STATE/pids"
BIN="$STATE/bin"
COMPOSE_FILE="$ROOT/infra/local/docker-compose.yml"
OVERRIDE="$STATE/compose.external-postgres.yml"

INFRA_BASE="kafka kafka-init elasticsearch elasticsearch-init temporal minio minio-init mailpit openfga-migrate openfga"

# Everything the storefront (incl. services/bookings), checkout, pre-orders, AfroTransact Retail (1P), delivery dispatch
# and the staff console use. Identity first.
DEFAULT_SERVICES="identity catalog offers inventory payments orders delivery-runs content trust-ops seller bookings
  retail fulfilment mcp-server geo media cart notifications search indexer promise gateway-bff"

ALL_SERVICES="identity catalog offers inventory orders payments seller retail fulfilment bookings trust-ops tax-returns
  content ai-gateway mcp-server delivery-runs gateway-bff search indexer cart promise geo storefront media
  notifications carrier-gateway authz-sync"

# Web apps (Next.js dev servers) against the local BFF, no mocks. WEB=0 skips them.
WEB_APPS="storefront seller-center staff-console"
# Their ports: 3000 / 3001 / 3002, or STOREFRONT_PORT / SELLER_CENTER_PORT / STAFF_CONSOLE_PORT. When a port is taken
# by something else (your Grafana, another dev server), `up` picks the next free one from 3010 and passes the apps'
# addresses to identity (sign-in return URLs), gateway-bff and notifications. The choice is kept in .local/web-ports.
WEB_PORTS_FILE="$STATE/web-ports"

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

web_port_var() {
  case "$1" in storefront) echo STOREFRONT_PORT ;; seller-center) echo SELLER_CENTER_PORT ;; staff-console) echo STAFF_CONSOLE_PORT ;; esac
}

web_default_port() {
  case "$1" in storefront) echo 3000 ;; seller-center) echo 3001 ;; staff-console) echo 3002 ;; esac
}

# The port an app uses: your *_PORT variable, else the one chosen earlier (.local/web-ports), else the default.
web_port_of() {
  local var saved=""
  var=$(web_port_var "$1")
  if [[ -n "${!var:-}" ]]; then echo "${!var}"; return 0; fi
  if [[ -f "$WEB_PORTS_FILE" ]]; then saved=$( { grep -E "^$var=" "$WEB_PORTS_FILE" || true; } | head -1 | cut -d= -f2); fi
  if [[ -n "$saved" ]]; then echo "$saved"; else web_default_port "$1"; fi
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

# Choose each web app's port before the services start (identity registers the apps' sign-in return URLs), then
# export the apps' addresses for identity, gateway-bff, notifications and the apps themselves.
resolve_web_ports() {
  local app var port taken="" before="" after="" svc
  if [[ -f "$WEB_PORTS_FILE" ]]; then before=$(grep -vE '^(DOCS|STORYBOOK)_PORT=' "$WEB_PORTS_FILE" || true)
  else before=$(printf 'STOREFRONT_PORT=3000\nSELLER_CENTER_PORT=3001\nSTAFF_CONSOLE_PORT=3002'); fi
  for app in $WEB_APPS; do
    var=$(web_port_var "$app")
    if [[ -n "${!var:-}" ]]; then port=${!var}
    else
      port=$(web_default_port "$app")
      # Keep the port our own running app already has; otherwise move off a port something else holds.
      if running "web-$app"; then port=$(web_port_of "$app")
      elif port_open "$port"; then
        local want=$port
        port=3010
        while port_open "$port" || [[ " $taken " == *" $port "* ]]; do port=$((port + 1)); done
        echo "  ! localhost:$want is in use by another program: $app runs on localhost:$port instead" \
          "(set $var to choose)"
      fi
    fi
    taken="$taken $port"
    after="$after$var=$port"$'\n'
  done
  mkdir -p "$STATE"
  local docs_line=""
  if [[ -f "$WEB_PORTS_FILE" ]]; then docs_line=$(grep -E '^(DOCS|STORYBOOK)_PORT=' "$WEB_PORTS_FILE" || true); fi
  printf '%s%s' "$after" "${docs_line:+$docs_line$'\n'}" >"$WEB_PORTS_FILE"
  export PUBLIC_WEB_URL="http://localhost:$(web_port_of storefront)"
  export SELLER_CENTER_URL="http://localhost:$(web_port_of seller-center)"
  export STAFF_CONSOLE_URL="http://localhost:$(web_port_of staff-console)"
  # Services started with other addresses would send sign-in back to the old ports: restart them with the new ones.
  if [[ "$before" != "$(grep -vE '^(DOCS|STORYBOOK)_PORT=' "$WEB_PORTS_FILE" || true)" ]]; then
    # The web apps link to each other too (storefront "Sell on AfroTransact" → Seller Center).
    for svc in identity gateway-bff notifications web-storefront web-seller-center web-staff-console; do
      if running "$svc"; then echo "  · web app ports changed: restarting $svc"; stop "$svc"; fi
    done
  fi
}

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
  # Browser origins allowed to call services directly: the Expo web previews of the mobile apps (Java services read
  # at.security.cors-allowed-origins; the BFF's list also keeps the three web apps).
  local mobile_origins="http://localhost:$(mobile_port_of customer),http://localhost:$(mobile_port_of driver)"
  export AT_SECURITY_CORS_ALLOWED_ORIGINS="$mobile_origins"
  export WEB_ORIGINS="http://localhost:$(web_port_of storefront),http://localhost:$(web_port_of seller-center),http://localhost:$(web_port_of staff-console),$mobile_origins"
  # media signs uploads for this stack's MinIO (infra/local/docker-compose.yml root credentials).
  export MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-minioadmin}" MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-minioadmin}"
  : >"$LOGS/$svc.log"
  if [[ "$(runtime_of "$svc")" == java ]]; then
    jar=$( { ls "$ROOT"/java/services/"$svc"/target/"$svc"-service-*.jar 2>/dev/null || true; } | grep -v -- '-plain' | head -1 || true)
    [[ -n "$jar" ]] || { echo "no jar for $svc; run: tools/dev/local.sh restart $svc" >&2; return 1; }
    # `cd …` on its own line (not `cd … &&`): with `&&` the `&` would background the whole list and $! be a wrapper.
    # Run a copy: a later `mvnw package` (tests, another terminal) rewrites target/*.jar in place, and a running JVM
    # whose jar changed under it fails to load classes and hangs.
    mkdir -p "$BIN"
    cp "$jar" "$BIN/$svc.jar"
    (cd "$ROOT/java/services/$svc" || exit 1
      SPRING_PROFILES_ACTIVE=local nohup java -XX:TieredStopAtLevel=1 -Xmx512m -jar "$BIN/$svc.jar" >>"$LOGS/$svc.log" 2>&1 &
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
      NEXT_PUBLIC_API_MOCKS=0 BFF_ORIGIN=http://localhost:8200 \
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

# S276: identity's local profile creates these (TestPersonas.java; `make seed` links the organisation members and checks
# every sign-in). Keep in step with tools/seed/seedlib/dataset.py TEST_USERS (make seed-test compares them).
test_users() {
  local sf=$1 sc=$2 st=$3 email persona app
  echo "Test users (password Test1234!Local; staff confirm a texted code, which lands in Mailpit http://localhost:8025)"
  while IFS='|' read -r email persona app; do
    printf '  %-36s %-34s %s\n' "$email" "$persona" "$app"
  done <<EOF
customer.houston@afrotransact.test|Customer, Houston 77042|$sf
customer.lubbock@afrotransact.test|Customer, Lubbock 79401 pre-order|$sf
seller.owner@afrotransact.test|Owner, Mama Put Pantry|$sc
seller.staff@afrotransact.test|Catalog manager, Mama Put Pantry|$sc
provider.owner@afrotransact.test|Owner, Aminata's Braids|$sc (Provider Hub)
retail.operator@afrotransact.test|Operator, AfroTransact Retail|$sc
staff.admin@afrotransact.test|Super Admin|$st
staff.ops@afrotransact.test|Operations Manager|$st
staff.trust@afrotransact.test|Trust & Safety|$st
staff.finance@afrotransact.test|Finance|$st
staff.support@afrotransact.test|Customer Support|$st
staff.marketing@afrotransact.test|Marketing (content, ads)|$st
staff.catalog@afrotransact.test|Catalog Manager|$st
staff.retail@afrotransact.test|Retail Buyer (1P)|$st
staff.fulfilment@afrotransact.test|Fulfilment Ops|$st
staff.inventory@afrotransact.test|Inventory Manager|$st
staff.dispatch@afrotransact.test|Dispatcher (delivery runs)|$st
staff.product@afrotransact.test|Product Owner|$st
staff.developer@afrotransact.test|Developer|$st
staff.auditor@afrotransact.test|Auditor (read-only)|$st
driver@afrotransact.test|Driver|driver app (mobile/apps/driver)
admin@afrotransact.com|Bootstrap admin: LocalAdmin2026!|$st
EOF
}

links() {
  local svc
  echo
  echo "──────────────────────────────── AfroTransact on this machine ────────────────────────────────"
  echo "Apps"
  local sf sc st
  sf="http://localhost:$(web_port_of storefront)"; sc="http://localhost:$(web_port_of seller-center)"
  st="http://localhost:$(web_port_of staff-console)"
  printf '  %-20s %-30s %s\n' "Storefront" "$sf" "pre-orders: $sf/pre-orders"
  printf '  %-20s %s\n' "Seller Center" "$sc"
  printf '  %-20s %-30s %s\n' "Staff console" "$st" "pre-orders: $st/preorders"
  test_users "$sf" "$sc" "$st"
  local dp; dp="http://localhost:$(docs_port)"
  if [[ -f "$PIDS/mobile-customer.pid" || -f "$PIDS/mobile-driver.pid" ]]; then
    local ip; ip=$(lan_ip)
    echo "Mobile apps (Expo; logs: make dev-logs SERVICE=mobile-customer | mobile-driver)"
    printf '  %-20s %-30s %s\n' "Customer app" "http://localhost:$(mobile_port_of customer)" "web preview · Expo Go: exp://$ip:$(mobile_port_of customer)"
    printf '  %-20s %-30s %s\n' "Driver app" "http://localhost:$(mobile_port_of driver)" "web preview · Expo Go: exp://$ip:$(mobile_port_of driver)"
    echo "  iOS simulator        cd mobile/apps/customer && npx expo run:ios   (or open the exp:// URL in the simulator)"
    echo "  Android emulator     MOBILE_HOST=10.0.2.2 make up   then: cd mobile/apps/customer && npx expo run:android"
    echo "  Phone on your Wi-Fi  MOBILE_HOST=$ip make up   then open the exp:// URL in Expo Go"
    echo "  Sign in              customer.houston@afrotransact.test / driver@afrotransact.test (Test1234!Local)"
  fi
  echo "Documentation"
  printf '  %-20s %-30s %s\n' "Developer portal" "$dp" "guides, architecture, runbooks, backlog"
  printf '  %-20s %s\n' "API reference (all)" "$dp/api   (every service's contract, rendered with Scalar)"
  printf '  %-20s %s\n' "MCP guide + tools" "$dp/mcp   and $dp/mcp/tools"
  printf '  %-20s %-30s %s\n' "Storybook" "http://localhost:$(storybook_port)" "design tokens, components, app screens (a11y checks)"
  echo "MCP server (Model Context Protocol, Streamable HTTP, JSON-RPC 2.0)"
  echo "  Endpoint             POST http://localhost:8114/mcp   (local: security off, runs as the local super admin)"
  echo "  Tool catalogue       http://localhost:8114/v1/tools   (JSON schemas per tool; ?mine=true for the caller's own)"
  echo "  OAuth metadata       http://localhost:8114/.well-known/oauth-protected-resource"
  echo "  Try it               npx @modelcontextprotocol/inspector  → Streamable HTTP, URL http://localhost:8114/mcp"
  echo "  Claude Code          claude mcp add --transport http afrotransact http://localhost:8114/mcp"
  echo "API docs per service (Scalar at /docs; also /swagger-ui.html, /redoc and /v3/api-docs or /openapi.yaml)"
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

# Developer portal (Docusaurus, docs/portal): guides, architecture, every API reference, the MCP pages. Port 3100, or
# DOCS_PORT, or the next free one from 3110 when something else holds it. DOCS=0 skips it.
docs_port() {
  local saved=""
  if [[ -n "${DOCS_PORT:-}" ]]; then echo "$DOCS_PORT"; return 0; fi
  if [[ -f "$WEB_PORTS_FILE" ]]; then saved=$( { grep -E "^DOCS_PORT=" "$WEB_PORTS_FILE" || true; } | head -1 | cut -d= -f2); fi
  echo "${saved:-3100}"
}

docs_up() {
  local port i
  if [[ "${DOCS:-1}" == 0 || "${WEB:-1}" == 0 ]]; then return 0; fi
  command -v pnpm >/dev/null || return 0
  if running docs-portal; then echo "  · docs portal already running"; return 0; fi
  port=$(docs_port)
  if [[ -z "${DOCS_PORT:-}" ]] && port_open "$port"; then
    local want=$port
    port=3110
    while port_open "$port"; do port=$((port + 1)); done
    echo "  ! localhost:$want is in use by another program: the docs portal runs on localhost:$port instead (set DOCS_PORT)"
  fi
  { grep -v '^DOCS_PORT=' "$WEB_PORTS_FILE" 2>/dev/null || true; echo "DOCS_PORT=$port"; } >"$WEB_PORTS_FILE.tmp"
  mv "$WEB_PORTS_FILE.tmp" "$WEB_PORTS_FILE"
  echo "==> starting the developer portal (Docusaurus; the first build takes a minute or two)"
  : >"$LOGS/docs-portal.log"
  (cd "$ROOT/docs/portal" || exit 1
    node scripts/sync-contracts.mjs >>"$LOGS/docs-portal.log" 2>&1
    nohup ./node_modules/.bin/docusaurus start --port "$port" --host localhost --no-open >>"$LOGS/docs-portal.log" 2>&1 &
    echo $! >"$PIDS/docs-portal.pid")
  for ((i = 0; i < 240; i += 3)); do
    if grep -q "Docusaurus website is running" "$LOGS/docs-portal.log" 2>/dev/null; then echo "  ✓ docs portal  http://localhost:$port"; return 0; fi
    if ! running docs-portal; then echo "  ✗ docs portal exited; last log lines:" >&2; tail -n 15 "$LOGS/docs-portal.log" >&2; return 0; fi
    sleep 3
  done
  echo "  … docs portal still compiling: tools/dev/local.sh logs docs-portal"
}

# Storybook (web/packages/ui): design tokens, shared components and the app screens' stories, with the a11y addon.
# Port 6006, or STORYBOOK_PORT, or the next free one from 6010. STORYBOOK=0 skips it.
storybook_port() {
  local saved=""
  if [[ -n "${STORYBOOK_PORT:-}" ]]; then echo "$STORYBOOK_PORT"; return 0; fi
  if [[ -f "$WEB_PORTS_FILE" ]]; then saved=$( { grep -E "^STORYBOOK_PORT=" "$WEB_PORTS_FILE" || true; } | head -1 | cut -d= -f2); fi
  echo "${saved:-6006}"
}

storybook_up() {
  local port i
  if [[ "${STORYBOOK:-1}" == 0 || "${WEB:-1}" == 0 ]]; then return 0; fi
  command -v pnpm >/dev/null || return 0
  if running storybook; then echo "  · Storybook already running"; return 0; fi
  port=$(storybook_port)
  if [[ -z "${STORYBOOK_PORT:-}" ]] && port_open "$port"; then
    local want=$port
    port=6010
    while port_open "$port"; do port=$((port + 1)); done
    echo "  ! localhost:$want is in use by another program: Storybook runs on localhost:$port instead (set STORYBOOK_PORT)"
  fi
  { grep -v '^STORYBOOK_PORT=' "$WEB_PORTS_FILE" 2>/dev/null || true; echo "STORYBOOK_PORT=$port"; } >"$WEB_PORTS_FILE.tmp"
  mv "$WEB_PORTS_FILE.tmp" "$WEB_PORTS_FILE"
  echo "==> starting Storybook (web/packages/ui)"
  : >"$LOGS/storybook.log"
  (cd "$ROOT/web/packages/ui" || exit 1
    nohup ./node_modules/.bin/storybook dev -p "$port" --ci --no-open >>"$LOGS/storybook.log" 2>&1 &
    echo $! >"$PIDS/storybook.pid")
  for ((i = 0; i < 180; i += 3)); do
    if grep -qE "Storybook .* started|Local:" "$LOGS/storybook.log" 2>/dev/null; then echo "  ✓ Storybook  http://localhost:$port"; return 0; fi
    if ! running storybook; then echo "  ✗ Storybook exited; last log lines:" >&2; tail -n 15 "$LOGS/storybook.log" >&2; return 0; fi
    sleep 3
  done
  echo "  … Storybook still starting: tools/dev/local.sh logs storybook"
}

# Mobile apps (Expo, mobile/apps/{customer,driver}) against the local stack: one Metro server each (customer :8081,
# driver :8082; identity's local OIDC clients redirect there for the web preview). MOBILE=0 skips them.
# Open them in a browser (web preview), an iOS simulator / Android emulator, or Expo Go on a phone (exp://<LAN IP>:port).
# EXPO_PUBLIC_DEV_HOST tells the apps where the stack is: localhost (browser, iOS simulator) by default; MOBILE_HOST=<ip>
# for a phone on the same Wi-Fi, 10.0.2.2 for the Android emulator. MOBILE_MOCKS=1 runs them on the in-app mock backend.
MOBILE_APPS="customer driver"

mobile_port_of() {
  case "$1" in customer) echo "${CUSTOMER_APP_PORT:-8081}" ;; driver) echo "${DRIVER_APP_PORT:-8082}" ;; esac
}

lan_ip() {
  local ip=""
  if command -v ipconfig >/dev/null 2>&1; then ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true); fi
  if [[ -z "$ip" ]] && command -v hostname >/dev/null 2>&1; then ip=$(hostname -I 2>/dev/null | awk '{print $1}' || true); fi
  echo "${ip:-localhost}"
}

mobile_up() {
  local app name port i mocks host
  if [[ "${MOBILE:-1}" == 0 ]]; then return 0; fi
  command -v pnpm >/dev/null || return 0
  if [[ ! -x "$ROOT/mobile/apps/customer/node_modules/.bin/expo" ]]; then
    echo "==> installing mobile dependencies (pnpm install)"
    (cd "$ROOT" && pnpm install --frozen-lockfile)
  fi
  mocks=0; if [[ "${MOBILE_MOCKS:-0}" == 1 ]]; then mocks=1; fi
  host="${MOBILE_HOST:-localhost}"
  echo "==> starting the mobile apps (Expo/Metro; the first bundle builds when you open the app)"
  for app in $MOBILE_APPS; do
    name="mobile-$app"; port=$(mobile_port_of "$app")
    if running "$name"; then echo "  · $app app already running"; continue; fi
    if port_open "$port"; then
      echo "  ! localhost:$port is in use: $app app skipped (set CUSTOMER_APP_PORT / DRIVER_APP_PORT)" >&2; continue
    fi
    : >"$LOGS/$name.log"
    (cd "$ROOT/mobile/apps/$app" || exit 1
      CI=1 EXPO_NO_TELEMETRY=1 EXPO_NO_TYPESCRIPT_SETUP=1 EXPO_PUBLIC_APP_ENV=local EXPO_PUBLIC_MOCKS=$mocks EXPO_PUBLIC_DEV_HOST="$host" \
        nohup ./node_modules/.bin/expo start --port "$port" --lan >>"$LOGS/$name.log" 2>&1 &
      echo $! >"$PIDS/$name.pid")
  done
  for app in $MOBILE_APPS; do
    name="mobile-$app"; port=$(mobile_port_of "$app")
    [[ -f "$PIDS/$name.pid" ]] || continue
    for ((i = 0; i < 120; i += 3)); do
      if grep -qE "Waiting on http|Metro waiting|Logs for your project" "$LOGS/$name.log" 2>/dev/null; then echo "  ✓ $app app  http://localhost:$port"; break; fi
      if ! running "$name"; then echo "  ✗ $app app exited; last log lines:" >&2; tail -n 15 "$LOGS/$name.log" >&2; break; fi
      sleep 3
    done
  done
}

cmd_up() {
  local services="$DEFAULT_SERVICES" svc failed=""
  if [[ $# -gt 0 ]]; then services="$*"; fi
  for svc in $services; do runtime_of "$svc" >/dev/null; done
  if [[ "${WEB:-1}" != 0 ]]; then resolve_web_ports; fi
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
  docs_up
  storybook_up
  mobile_up
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
  if [[ -f "$PIDS/docs-portal.pid" ]]; then
    state=down; if running docs-portal; then state=running; fi
    printf '%-18s %-6s %-9s %s\n' docs-portal "$(docs_port)" "$state" "http://localhost:$(docs_port)"
  fi
  if [[ -f "$PIDS/storybook.pid" ]]; then
    state=down; if running storybook; then state=running; fi
    printf '%-18s %-6s %-9s %s\n' storybook "$(storybook_port)" "$state" "http://localhost:$(storybook_port)"
  fi
  for app in $MOBILE_APPS; do
    [[ -f "$PIDS/mobile-$app.pid" ]] || continue
    state=down; if running "mobile-$app"; then state=running; fi
    printf '%-18s %-6s %-9s %s\n' "mobile-$app" "$(mobile_port_of "$app")" "$state" "http://localhost:$(mobile_port_of "$app")"
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
  mobile) mkdir -p "$LOGS" "$PIDS"; mobile_up; links ;;
  docs) mkdir -p "$LOGS" "$PIDS" "$STATE"; docs_up; storybook_up ;;
  logs) [[ -n "${2:-}" ]] || { echo "usage: $0 logs <service>" >&2; exit 2; }; tail -n 200 -f "$LOGS/$2.log" ;;
  seed) shift; python3 "$ROOT/tools/seed/seed.py" "$@" ;;
  restart) [[ -n "${2:-}" ]] || { echo "usage: $0 restart <service>" >&2; exit 2; }
    export PUBLIC_WEB_URL="http://localhost:$(web_port_of storefront)"
    export SELLER_CENTER_URL="http://localhost:$(web_port_of seller-center)"
    export STAFF_CONSOLE_URL="http://localhost:$(web_port_of staff-console)"
    stop "$2"; build "$2"; start "$2"; wait_healthy "$2" 240 ;;
  *) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
