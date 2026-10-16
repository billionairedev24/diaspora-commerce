#!/usr/bin/env bash
# Run AfroTransact locally: the core infrastructure in Docker, the back-end services as host processes (local
# profile, fixed ports from infra/local/PORTS.md), the three web apps against them; logs and PIDs under .local/.
#
#   tools/dev/local.sh up                      # infra + the default services + web apps, then prints every link
#   DEV=1 tools/dev/local.sh up                # same, with Next dev servers (default: production builds, next build + start)
#   tools/dev/local.sh up identity catalog     # only these services (plus infra and the web apps; WEB=0 skips them)
#   tools/dev/local.sh status                  # what is running and healthy
#   tools/dev/local.sh links                   # print every link again (apps, docs portal, MCP, API docs)
#   tools/dev/local.sh logs delivery-runs      # follow one log (web apps: web-storefront, web-staff-console, …)
#   tools/dev/local.sh restart orders          # rebuild + restart one service (re-reads .local/secrets.env)
#   tools/dev/local.sh mobile                  # only the Expo apps (customer :8081, driver :8082)
#   MOBILE_HOST=lan tools/dev/local.sh up      # LAN mode: the whole stack on this Mac's Wi-Fi address, for a phone
#   MOBILE_HOST=192.168.1.50 tools/dev/local.sh lan-env   # print the addresses LAN mode would use (dry run)
#   tools/dev/local.sh device android customer # build + install the development build on a USB phone
#   tools/dev/local.sh docs                    # only the developer portal (:3100) and Storybook (:6006)
#   tools/dev/local.sh seed                    # demo data (tools/seed): sellers, products with photos, home content
#   tools/dev/local.sh repair-orgs             # identity re-syncs every approved seller/provider org from seller now
#   tools/dev/local.sh down                    # stop everything (keeps data; `make reset` wipes the Docker volumes)
#
# Make targets: make up (= dev-up), make down, make dev-status, make dev-links, make dev-logs SERVICE=x, make repair-orgs,
# make dev-restart SERVICE=x, make seed. Works with macOS's bash 3.2.
#
# `up` seeds the demo dataset when the catalog is empty (tools/seed/seed.py --if-empty; SEED=0 skips it).
#
# The infrastructure the apps need is started, plus monitoring: Grafana http://localhost:3200 (Prometheus metrics,
# Loki logs of every service and web app from .local/logs, Tempo traces). OBS=0 leaves monitoring out (~2 GB less);
# Kibana and Kafka UI only come with `make up-infra` (~12 GB). Java services start one at a time (Flyway migrations). Connection pools are kept small (DB_POOL,
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

INFRA_BASE="kafka kafka-init elasticsearch elasticsearch-init temporal temporal-ui minio minio-init mailpit openfga-migrate openfga"
# Monitoring (OBS=1, the default): collector, metrics, logs (host-log-shipper tails .local/logs), traces, Grafana.
# Postgres / Valkey exporters are added only when this stack runs those containers.
OBS_BASE="otel-collector prometheus alertmanager loki tempo grafana host-log-shipper elasticsearch-exporter kafka-exporter"

# Everything the storefront (incl. services/bookings), checkout, pre-orders, AfroTransact Retail (1P), delivery dispatch,
# returns and the staff console use. Identity first.
DEFAULT_SERVICES="identity catalog offers inventory payments orders delivery-runs content trust-ops tax-returns seller
  bookings retail fulfilment mcp-server geo media cart notifications search indexer promise storefront gateway-bff
  carrier-gateway"

ALL_SERVICES="identity catalog offers inventory orders payments seller retail fulfilment bookings trust-ops tax-returns
  content ai-gateway mcp-server delivery-runs gateway-bff search indexer cart promise geo storefront media
  notifications carrier-gateway authz-sync"

# Web apps (Next.js dev servers) against the local BFF, no mocks. WEB=0 skips them.
WEB_APPS="storefront seller-center staff-console"
# Their ports: 3000 / 3001 / 3002, or STOREFRONT_PORT / SELLER_CENTER_PORT / STAFF_CONSOLE_PORT. When a port is taken
# by something else (your Grafana, another dev server), `up` picks the next free one from 3010 and passes the apps'
# addresses to identity (sign-in return URLs), gateway-bff and notifications. The choice is kept in .local/web-ports.
WEB_PORTS_FILE="$STATE/web-ports"

# Your provider credentials: .local/secrets.env (gitignored; template infra/local/secrets.env.example; reference
# docs/engineering/secrets-and-configuration.md). `up`, `restart` and `mobile` source it and export every value to the
# services, web apps (NEXT_PUBLIC_* at dev-server start) and Expo apps (EXPO_PUBLIC_*, inlined when Metro bundles)
# started from here. Values are never printed. Names the file leaves empty are unset (or keep your shell's value), so a
# blank line keeps the service's local default (Spring and the Go loader treat an empty variable as set). SECRETS_FILE=<path> uses another.
SECRETS_FILE="${SECRETS_FILE:-$STATE/secrets.env}"
SECRETS_LOADED=0

load_secrets() {
  local name names keep count=0
  if [[ "$SECRETS_LOADED" == 1 ]]; then return 0; fi
  SECRETS_LOADED=1
  if [[ ! -f "$SECRETS_FILE" ]]; then
    echo "==> no ${SECRETS_FILE#"$ROOT"/}: local fallbacks only (Mailpit for email and codes, logged texts; payments"
    echo "    authorise like test card 4242, no Stripe). Start one: mkdir -p .local && cp infra/local/secrets.env.example .local/secrets.env"
    return 0
  fi
  names=$(sed -nE 's/^[[:space:]]*(export[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=.*/\2/p' "$SECRETS_FILE")
  # A value already in your shell survives an empty line in the file (a non-empty line in the file wins).
  for name in $names; do
    if [[ -n "${!name:-}" ]]; then printf -v "AT_SHELL_$name" '%s' "${!name}"; fi
  done
  set -a
  # shellcheck disable=SC1090 # the developer's own file
  if ! . "$SECRETS_FILE"; then
    set +a
    echo "  ✗ ${SECRETS_FILE#"$ROOT"/} is not valid shell (quote values with spaces in single quotes)" >&2; exit 1
  fi
  set +a
  for name in $names; do
    keep="AT_SHELL_$name"
    if [[ -z "${!name:-}" && -n "${!keep:-}" ]]; then export "$name=${!keep}"
    elif [[ -z "${!name:-}" ]]; then unset "$name"
    else count=$((count + 1)); fi
    unset "$keep"
  done
  # The customer app's PaymentSheet uses the storefront's publishable key unless the file sets its own.
  if [[ -z "${EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY:-}" && -n "${STRIPE_PUBLISHABLE_KEY:-}" ]]; then
    export EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY="$STRIPE_PUBLISHABLE_KEY"
  fi
  echo "==> ${SECRETS_FILE#"$ROOT"/}: $count values loaded (not shown; make secrets-check for the details)"
}

# One line per provider: real provider or local fallback (tools/dev/secrets_check.py --summary).
secrets_summary() {
  command -v python3 >/dev/null || return 0
  echo "  $(SECRETS_FILE="$SECRETS_FILE" python3 "$ROOT/tools/dev/secrets_check.py" --summary --file "$SECRETS_FILE" 2>/dev/null || true)"
}

# identity's one-time codes: straight to Mailpit by default; through notifications (Twilio for SMS, Resend or Mailpit
# for email; test users' codes still land in Mailpit) once Twilio is set. Your own VERIFICATION_SENDER wins.
verification_sender() {
  if [[ -n "${VERIFICATION_SENDER:-}" ]]; then echo "$VERIFICATION_SENDER"
  elif [[ -n "${TWILIO_ACCOUNT_SID:-}" && -n "${TWILIO_AUTH_TOKEN:-}" && -n "${TWILIO_FROM:-}${TWILIO_MESSAGING_SERVICE_SID:-}" ]]; then
    echo outbox
  else echo mailpit; fi
}

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
  platform_env
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
# -w: never prompt for a password (a prompt would hang make up); PGPASSWORD / ~/.pgpass still apply.
psql_su() {
  case "$PSQL_MODE" in
    tcp) psql -w -h localhost -p 5432 -U "${PG_SUPERUSER:-$(whoami)}" -v ON_ERROR_STOP=1 -qtA "$@" ;;
    socket) psql -w -p 5432 -U "$(whoami)" -v ON_ERROR_STOP=1 -qtA "$@" ;;
    sudo) sudo -u postgres psql -w -p 5432 -v ON_ERROR_STOP=1 -qtA "$@" ;;
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
    echo "Your Postgres on localhost:5432 (version $(psql_su -d postgres -c 'show server_version' 2>/dev/null), connected via $PSQL_MODE) has no PostGIS (geo, fulfilment need it)." >&2
    echo "  brew install postgis (it must match that server's major version), restart Postgres, then re-run." >&2
    echo "  Or: PG_EXTERNAL=0 POSTGRES_HOST_PORT=5433 make up  # the Docker Postgres, which ships PostGIS" >&2
    exit 1; }
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
  temporal-ui:
    depends_on: !reset {}
    network_mode: host
    ports: !reset []
    environment:
      TEMPORAL_ADDRESS: 127.0.0.1:7233
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
  if [[ "${OBS:-1}" != 0 ]]; then
    infra="$infra $OBS_BASE"
    [[ "$infra" == "postgres "* ]] && infra="$infra postgres-exporter"
    [[ "$infra" == *"valkey "* ]] && infra="$infra redis-exporter"
    mkdir -p "$LOGS"
    export AT_LOCAL_LOGS="$LOGS"
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

# Traces/metrics go to the OpenTelemetry collector on :4318 (started with the monitoring, OBS=1). With OBS=0 export is
# switched off so the logs stay readable; TELEMETRY=1 keeps it (your own collector on localhost:4318).
telemetry_on() { if [[ "${TELEMETRY:-${OBS:-1}}" == 1 ]]; then echo 1; else echo 0; fi; }
telemetry_env() {
  if [[ "$(telemetry_on)" == 1 ]]; then return 0; fi
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

# Fingerprint of a service's build (the Java jar or the Go binary): `up` restarts a running service whose build
# changed since it started, so new code and routes (a pull, another branch) are never served by an old process.
artifact_of() {
  if [[ "$(runtime_of "$1")" == java ]]; then
    { ls "$ROOT"/java/services/"$1"/target/"$1"-service-*.jar 2>/dev/null || true; } | grep -v -- '-plain' | head -1 || true
  else
    echo "$BIN/$1"
  fi
}
build_sig() {
  local f; f=$(artifact_of "$1")
  if [[ -n "$f" && -f "$f" ]]; then cksum <"$f" | awk '{print $1 "-" $2}'; fi
}

# A process this script doesn't track (an older run whose pid file is gone, a service started by hand) may still hold
# the service's port: the new one then can't bind, while the old one keeps answering health checks with old code.
# Replace it when it is one of ours (this repository's jar or binary); refuse with a clear message otherwise.
listener_pid() {
  if command -v lsof >/dev/null 2>&1; then lsof -nP -t -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1
  elif command -v ss >/dev/null 2>&1; then ss -ltnpH "sport = :$1" 2>/dev/null | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2
  fi
}

free_port_for() {
  local svc=$1 port pid cmd i
  port=$(port_of "$svc")
  port_open "$port" || return 0
  pid=$(listener_pid "$port")
  cmd=$(ps -o command= -p "$pid" 2>/dev/null || true)
  if [[ -n "$pid" && ( "$cmd" == *"$ROOT"* || "$cmd" == *".local/bin/$svc"* || "$cmd" == *"$svc-service-"* ) ]]; then
    echo "  · $svc: an old $svc (pid $pid, not started by this run) holds :$port; replacing it"
    kill "$pid" 2>/dev/null || true
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do port_open "$port" || return 0; sleep 0.5; done
    kill -9 "$pid" 2>/dev/null || true; sleep 1
    port_open "$port" || return 0
  fi
  echo "  ✗ $svc: localhost:$port is in use by another program${pid:+ (pid $pid: ${cmd:0:80})}; stop it and run make up again" >&2
  return 1
}

start() {
  local svc=$1 jar
  mkdir -p "$LOGS" "$PIDS"
  platform_env
  if running "$svc"; then
    if [[ -f "$PIDS/$svc.sig" && "$(cat "$PIDS/$svc.sig")" == "$(build_sig "$svc")" ]] \
      && [[ "$(cat "$PIDS/$svc.env" 2>/dev/null || echo localhost)" == "$(env_sig)" ]] \
      && [[ "$(cat "$PIDS/$svc.otel" 2>/dev/null || echo 0)" == "$(telemetry_on)" ]]; then
      echo "  · $svc already running (pid $(cat "$PIDS/$svc.pid"))"; return 0
    fi
    if [[ -f "$PIDS/$svc.sig" && "$(cat "$PIDS/$svc.sig")" == "$(build_sig "$svc")" ]] \
      && [[ "$(cat "$PIDS/$svc.env" 2>/dev/null || echo localhost)" == "$(env_sig)" ]]; then
      echo "  · $svc: telemetry export switched $([[ "$(telemetry_on)" == 1 ]] && echo on || echo off): restarting"
    elif [[ -f "$PIDS/$svc.sig" && "$(cat "$PIDS/$svc.sig")" == "$(build_sig "$svc")" ]]; then
      echo "  · $svc runs with the other addresses (localhost / LAN mode): restarting"
    else
      echo "  · $svc was rebuilt since it started: restarting"
    fi
    stop "$svc"
  fi
  free_port_for "$svc" || return 1
  telemetry_env
  pool_env "$svc"
  # media signs uploads for this stack's MinIO (infra/local/docker-compose.yml root credentials).
  export MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:-minioadmin}" MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-minioadmin}"
  local vsender=""
  if [[ "$svc" == identity ]]; then
    vsender=$(verification_sender)
    if [[ "$vsender" == outbox ]]; then echo "  · identity: one-time codes via notifications → Twilio / Resend (test users' codes: Mailpit)"
    else echo "  · identity: one-time codes → $vsender (http://localhost:8025)"; fi
  fi
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
      if [[ -n "$vsender" ]]; then export VERIFICATION_SENDER="$vsender"; fi
      SPRING_PROFILES_ACTIVE=local nohup java -XX:TieredStopAtLevel=1 -Xmx512m -jar "$BIN/$svc.jar" >>"$LOGS/$svc.log" 2>&1 &
      echo $! >"$PIDS/$svc.pid")
  else
    (cd "$ROOT/go/services/$svc" || exit 1
      APP_ENV=local nohup "$BIN/$svc" >>"$LOGS/$svc.log" 2>&1 &
      echo $! >"$PIDS/$svc.pid")
  fi
  build_sig "$svc" >"$PIDS/$svc.sig"
  env_sig >"$PIDS/$svc.env"
  telemetry_on >"$PIDS/$svc.otel"
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
  rm -f "$PIDS/$svc.pid" "$PIDS/$svc.sig" "$PIDS/$svc.mode" "$PIDS/$svc.env" "$PIDS/$svc.otel"
}

# The web apps run as production builds by default (next build + next start, output in .next-prod so the dev cache
# stays): real bundles, no dev overlay, production React. A build is skipped when nothing under the app, web/packages
# or its config changed since it was made (REBUILD=1 forces one). DEV=1 (make up DEV=1; or PROD=0) runs Next dev
# servers instead: hot reload, slow first page loads.
web_mode() {
  if [[ "${DEV:-0}" == 1 || "${PROD:-}" == 0 || "${WEB_MODE:-}" == dev ]]; then echo dev; else echo prod; fi
}

# node must actually start: after a brew upgrade a dylib it links (simdjson, icu, …) can be missing.
node_ok() {
  command -v node >/dev/null && node -e 0 >/dev/null 2>&1 && return 0
  echo "  ✗ node does not start (a library it needs is missing after a Homebrew upgrade). Fix it, then make up again:" >&2
  echo "      brew reinstall node@22   # or: brew reinstall simdjson" >&2
  node -v 2>&1 | head -2 | sed 's/^/      /' >&2
  return 1
}

# Is the production build of this app current: built from this source, for these addresses?
web_build_fresh() {
  local dir="$ROOT/web/apps/$1" id
  id="$dir/.next-prod/BUILD_ID"
  [[ "${REBUILD:-0}" != 1 && -f "$id" && "$(cat "$dir/.next-prod/.local-sig" 2>/dev/null)" == "$(env_sig)" ]] || return 1
  [[ -z "$(find "$dir/src" "$dir/public" "$ROOT/web/packages" "$dir/package.json" "$dir"/next.config.* "$ROOT/web/pnpm-lock.yaml" \
    \( -name node_modules -o -name .next -o -name .next-prod \) -prune -o -type f -newer "$id" -print -quit 2>/dev/null)" ]]
}

web_up() {
  local app name url port i mode
  if [[ "${WEB:-1}" == 0 ]]; then return 0; fi
  command -v pnpm >/dev/null || { echo "pnpm not found: web apps skipped (corepack enable; then make up again)." >&2; return 0; }
  if [[ ! -x "$ROOT/web/apps/storefront/node_modules/.bin/next" ]]; then
    echo "==> installing web dependencies (pnpm install)"
    (cd "$ROOT" && pnpm install --frozen-lockfile)
  fi
  node_ok || return 0
  mode=$(web_mode)
  mkdir -p "$LOGS" "$PIDS"
  platform_env
  # A running app in the other mode, or started with the other addresses (localhost / LAN mode), is replaced.
  for app in $WEB_APPS; do
    name="web-$app"
    if running "$name" && [[ "$(cat "$PIDS/$name.mode" 2>/dev/null || echo dev)" != "$mode" ]]; then
      echo "  · $app runs in the other mode: restarting it as $mode"; stop "$name"
    elif running "$name" && [[ "$(cat "$PIDS/$name.env" 2>/dev/null || echo localhost)" != "$(env_sig)" ]]; then
      echo "  · $app runs with the other addresses (localhost / LAN mode): restarting it"; stop "$name"
    fi
  done
  if [[ "$mode" == prod ]]; then
    echo "==> building the web apps for production (next build, one at a time; logs: .local/logs/web-<app>.log)"
    for app in $WEB_APPS; do
      name="web-$app"
      if running "$name"; then stop "$name"; fi
      : >"$LOGS/$name.log"
      if web_build_fresh "$app"; then
        echo "  · $app: build is current (REBUILD=1 to force)"
      else
        echo "  … building $app"
        if ! (cd "$ROOT/web/apps/$app" && NODE_ENV=production NEXT_DIST_DIR=.next-prod NEXT_TELEMETRY_DISABLED=1 \
          NEXT_PUBLIC_API_MOCKS=0 BFF_ORIGIN=http://localhost:8200 ./node_modules/.bin/next build >>"$LOGS/$name.log" 2>&1); then
          echo "  ✗ $app build failed; last log lines:" >&2; tail -n 25 "$LOGS/$name.log" >&2; continue
        fi
        env_sig >"$ROOT/web/apps/$app/.next-prod/.local-sig"
      fi
      port=$(web_port_of "$app")
      (cd "$ROOT/web/apps/$app" || exit 1
        NODE_ENV=production NEXT_DIST_DIR=.next-prod NEXT_TELEMETRY_DISABLED=1 NEXT_PUBLIC_API_MOCKS=0 \
          BFF_ORIGIN=http://localhost:8200 nohup ./node_modules/.bin/next start --port "$port" >>"$LOGS/$name.log" 2>&1 &
        echo $! >"$PIDS/$name.pid")
      echo prod >"$PIDS/$name.mode"; env_sig >"$PIDS/$name.env"
    done
  else
    echo "==> starting web apps (Next.js dev against the local BFF on :8200; the first page load compiles for a minute)"
    for app in $WEB_APPS; do
      name="web-$app"; port=$(web_port_of "$app")
      if running "$name"; then echo "  · $app already running"; continue; fi
      : >"$LOGS/$name.log"
      (cd "$ROOT/web/apps/$app" || exit 1
        NEXT_PUBLIC_API_MOCKS=0 BFF_ORIGIN=http://localhost:8200 \
          nohup ./node_modules/.bin/next dev --port "$port" >>"$LOGS/$name.log" 2>&1 &
        echo $! >"$PIDS/$name.pid")
      echo dev >"$PIDS/$name.mode"; env_sig >"$PIDS/$name.env"
    done
  fi
  for app in $WEB_APPS; do
    name="web-$app"; url="http://$(public_host):$(web_port_of "$app")"
    [[ -f "$PIDS/$name.pid" ]] || continue
    for ((i = 0; i < 180; i += 3)); do
      if grep -qE "Ready in|ready started|Local:" "$LOGS/$name.log" 2>/dev/null; then echo "  ✓ $app  $url  ($mode)"; break; fi
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

# The mobile part of `links`: in LAN mode the phone URLs, exp:// links and QR codes; otherwise how to get there.
mobile_links() {
  local h ip app port slug
  h=$(public_host); ip=$(lan_ip)
  echo "Mobile apps (Expo; logs: make dev-logs SERVICE=mobile-customer | mobile-driver; guide: docs/engineering/mobile-on-your-phone.md)"
  if [[ "$h" == localhost ]]; then
    printf '  %-20s %-30s %s\n' "Customer app" "http://localhost:$(mobile_port_of customer)" "web preview · simulator: xcrun simctl openurl booted exp://localhost:$(mobile_port_of customer)"
    printf '  %-20s %-30s %s\n' "Driver app" "http://localhost:$(mobile_port_of driver)" "web preview"
    echo "  Phone on your Wi-Fi  make up MOBILE_HOST=lan   (LAN mode: the whole stack on $ip, sign-in included)"
    echo "  Android over USB     make mobile-adb-reverse, then open the app (localhost mode works through the cable)"
    echo "  Development build    make mobile-android-device APP=customer | make mobile-ios-device APP=customer"
  else
    echo "  LAN mode: everything is served on $h — use these addresses on this Mac too (sign-in, cookies and tokens agree)."
    printf '  %-20s %s\n' "Storefront (phone)" "http://$h:$(web_port_of storefront)"
    for app in $MOBILE_APPS; do
      port=$(mobile_port_of "$app"); slug="afrotransact-$app"
      printf '  %-20s %-34s %s\n' "$app app · Expo Go" "exp://$h:$port" "scan with the Camera app (iOS) or Expo Go (Android)"
      qr "exp://$h:$port"
      printf '  %-20s %s\n' "$app · dev build" "exp+$slug://expo-development-client/?url=http%3A%2F%2F$h%3A$port"
    done
    echo "  Firewall (macOS)     allow incoming connections for node and java when asked (System Settings → Network → Firewall)"
    echo "  Back to localhost    make up   (without MOBILE_HOST; only the services, web apps and Metro restart)"
  fi
  echo "  Sign in              customer.houston@afrotransact.test / driver@afrotransact.test (Test1234!Local)"
}

monitoring_links() {
  echo "Monitoring"
  if [[ "${OBS:-1}" == 0 ]] || ! compose ps --status running --format '{{.Service}}' grafana 2>/dev/null | grep -q grafana; then
    echo "  off (OBS=0 or not started): make up starts Grafana, Prometheus, Loki and Tempo"
    return 0
  fi
  echo "  Grafana              http://localhost:3200          no sign-in locally (admin / admin if asked)"
  echo "  Logs                 Grafana → Drilldown → Logs, or Explore → Loki: {service_name=\"orders\"} |= \"ERROR\""
  echo "                       every service and web app (.local/logs/*.log; web apps as web-storefront etc.)"
  echo "  Traces               Grafana → Explore → Tempo (log line → trace and back by trace id)"
  echo "  Dashboards           Grafana → Dashboards → AfroTransact (platform overview, JVM, Go, Kafka lag, SLOs)"
  echo "  Prometheus           http://localhost:9090          Alertmanager http://localhost:9093"
  echo "  Your own Grafana     add data sources Prometheus http://localhost:9090, Loki http://localhost:3300,"
  echo "                       Tempo http://localhost:3201 (dashboards: infra/observability/grafana/dashboards)"
}

links() {
  local svc
  echo
  echo "──────────────────────────────── AfroTransact on this machine ────────────────────────────────"
  echo "Apps"
  local sf sc st h
  h=$(public_host)
  sf="http://$h:$(web_port_of storefront)"; sc="http://$h:$(web_port_of seller-center)"
  st="http://$h:$(web_port_of staff-console)"
  printf '  %-20s %-30s %s\n' "Storefront" "$sf" "pre-orders: $sf/pre-orders"
  printf '  %-20s %s\n' "Seller Center" "$sc"
  printf '  %-20s %-30s %s\n' "Staff console" "$st" "pre-orders: $st/preorders"
  test_users "$sf" "$sc" "$st"
  local dp; dp="http://localhost:$(docs_port)"
  if [[ -f "$PIDS/mobile-customer.pid" || -f "$PIDS/mobile-driver.pid" ]]; then
    mobile_links
  fi
  echo "Documentation"
  printf '  %-20s %-30s %s\n' "Developer portal" "$dp" "guides, architecture, runbooks, backlog"
  printf '  %-20s %s\n' "API reference (all)" "$dp/api   (every service's contract, rendered with Scalar)"
  printf '  %-20s %s\n' "MCP guide + tools" "$dp/mcp   and $dp/mcp/tools"
  printf '  %-20s %-30s %s\n' "Storybook" "http://localhost:$(storybook_port)" "design tokens, components, app screens (a11y checks)"
  echo "MCP server (Model Context Protocol, Streamable HTTP, JSON-RPC 2.0)"
  echo "  Endpoint             POST http://localhost:8114/mcp   (local: security off, runs as the local super admin)"
  echo "                       POST only: a GET (browser, curl without -X POST, the Inspector's \"SSE\" transport) gets 405"
  echo "  Tool catalogue       http://localhost:8114/v1/tools   (JSON schemas per tool; ?mine=true for the caller's own)"
  echo "  OAuth metadata       http://localhost:8114/.well-known/oauth-protected-resource"
  echo "  Try it               npx @modelcontextprotocol/inspector  → Transport Type \"Streamable HTTP\" (not SSE), URL http://localhost:8114/mcp"
  echo "  CLI check            npx @modelcontextprotocol/inspector --cli http://localhost:8114/mcp --transport http --method tools/list"
  echo "  Claude Code          claude mcp add --transport http afrotransact http://localhost:8114/mcp"
  echo "API docs per service (Scalar at /docs; also /swagger-ui.html, /redoc and /v3/api-docs or /openapi.yaml)"
  for svc in $ALL_SERVICES; do
    [[ -f "$PIDS/$svc.pid" ]] || continue
    printf '  %-20s http://localhost:%s/docs\n' "$svc" "$(port_of "$svc")"
  done
  monitoring_links
  echo "Infrastructure"
  echo "  Mailpit (mail, SMS)  http://localhost:8025"
  echo "  MinIO console        http://localhost:9001          minioadmin / minioadmin"
  echo "  Elasticsearch        http://localhost:9200          OpenFGA  http://localhost:8091"
  echo "  Kafka                localhost:9092                 Temporal gRPC localhost:7233 · UI http://localhost:8088"
  echo "  Postgres             localhost:5432                 Valkey   localhost:6379"
  echo "Manage"
  echo "  make dev-status | make dev-logs SERVICE=orders (or web-storefront) | make dev-links | make down"
  echo "Secrets (.local/secrets.env; make secrets-check; docs/engineering/secrets-and-configuration.md)"
  secrets_summary
  echo "  Payments: without STRIPE_SECRET_KEY a local stand-in authorises every card like test card 4242 (no money moves)."
  echo "  For Stripe test mode put the keys in .local/secrets.env, and for webhooks:"
  echo "  stripe listen --forward-to localhost:8105/v1/webhooks/stripe   (its whsec_… → STRIPE_WEBHOOK_SECRET)"
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
  node_ok || return 0
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
# Open them in a browser (web preview), an iOS simulator / Android emulator, Expo Go or a development build on a phone.
# MOBILE_MOCKS=1 runs them on the in-app mock backend. docs/engineering/mobile-on-your-phone.md has every route.
MOBILE_APPS="customer driver"

mobile_port_of() {
  case "$1" in customer) echo "${CUSTOMER_APP_PORT:-8081}" ;; driver) echo "${DRIVER_APP_PORT:-8082}" ;; esac
}

# This machine's address on the local network: the interface of the default route (macOS: route + ipconfig; Linux:
# ip route), then the usual Wi-Fi/Ethernet names, then hostname -I without Docker bridges. "localhost" when offline.
lan_ip() {
  local ip="" ifc=""
  if command -v ipconfig >/dev/null 2>&1 && command -v route >/dev/null 2>&1; then
    ifc=$(route -n get default 2>/dev/null | awk '/interface:/ {print $2; exit}' || true)
    if [[ -n "$ifc" ]]; then ip=$(ipconfig getifaddr "$ifc" 2>/dev/null || true); fi
  fi
  if [[ -z "$ip" ]] && command -v ipconfig >/dev/null 2>&1; then
    ip=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)
  fi
  if [[ -z "$ip" ]] && command -v ip >/dev/null 2>&1; then
    ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i < NF; i++) if ($i == "src") {print $(i + 1); exit}}' || true)
  fi
  if [[ -z "$ip" ]] && command -v hostname >/dev/null 2>&1; then
    ip=$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+\.' | grep -vE '^(127\.|172\.(1[7-9]|2[0-9]|3[01])\.)' | head -1 || true)
  fi
  echo "${ip:-localhost}"
}

# LAN mode. The stack advertises one host everywhere a browser or phone sees an address: identity's issuer (and so
# every service's token check), the web apps' URLs and sign-in callbacks, CORS origins, the Expo apps' API host,
# image and upload links. localhost (default) suits this machine's browser and simulators; on a phone "localhost" is
# the phone, so MOBILE_HOST=lan (this machine's Wi-Fi address) or MOBILE_HOST=<IPv4> switches the whole stack to that
# address. MOBILE_HOST=10.0.2.2 (the Android emulator's alias for this machine) keeps localhost mode: the emulator
# then reaches the stack through adb reverse (make mobile-adb-reverse) or uses LAN mode like a phone.
public_host() {
  local h="${MOBILE_HOST:-localhost}"
  case "$h" in lan|LAN|auto) h=$(lan_ip) ;; esac
  case "$h" in
    ""|localhost|127.0.0.1|10.0.2.2) echo localhost ;;
    *) echo "$h" ;;
  esac
}

# What a process was started with (.local/pids/<name>.env): `up` restarts services, web apps and Metro whose
# addresses differ, so switching LAN mode on or off restarts what depends on it and rebuilds nothing.
env_sig() { public_host; }

LAN_WARNED=0

# Exports the addresses for the services, web apps and Expo apps started from here (see public_host).
platform_env() {
  local h cu dr sf sc st mobile_origins
  h=$(public_host)
  if [[ "${MOBILE_HOST:-}" =~ ^(lan|LAN|auto)$ && "$h" == localhost && "$LAN_WARNED" == 0 ]]; then
    LAN_WARNED=1
    echo "  ! MOBILE_HOST=lan: no LAN address found (Wi-Fi off?); staying on localhost" >&2
  fi
  sf="http://$h:$(web_port_of storefront)"; sc="http://$h:$(web_port_of seller-center)"; st="http://$h:$(web_port_of staff-console)"
  cu="http://$h:$(mobile_port_of customer)"; dr="http://$h:$(mobile_port_of driver)"
  export PUBLIC_WEB_URL="$sf" SELLER_CENTER_URL="$sc" STAFF_CONSOLE_URL="$st"
  # Expo web previews may call the services directly (Java services: at.security.cors-allowed-origins).
  mobile_origins="http://localhost:$(mobile_port_of customer),http://localhost:$(mobile_port_of driver)"
  # Localhost mode: the apps use localhost, or 10.0.2.2 from the Android emulator (MOBILE_HOST=10.0.2.2).
  export EXPO_PUBLIC_DEV_HOST=localhost
  if [[ "${MOBILE_HOST:-}" == 10.0.2.2 ]]; then export EXPO_PUBLIC_DEV_HOST=10.0.2.2; fi
  if [[ "$h" != localhost ]]; then
    mobile_origins="$mobile_origins,$cu,$dr"
    export EXPO_PUBLIC_DEV_HOST="$h"
    # identity: issuer (iss claim, discovery, JWKS) — every service validates tokens against it.
    export AUTH_ISSUER_URL="http://$h:8100"
    # identity (application-local.yml): the LAN web previews' origins and Expo Go's redirect URIs
    # (expo-auth-session makeRedirectUri in Expo Go: exp://<host>:<Metro port>/--/auth/callback).
    export LAN_WEB_ORIGINS="$cu,$dr"
    export CUSTOMER_APP_DEV_REDIRECT_URIS="exp://$h:$(mobile_port_of customer)/--/auth/callback,$cu/auth/callback"
    export DRIVER_APP_DEV_REDIRECT_URIS="exp://$h:$(mobile_port_of driver)/--/auth/callback,$dr/auth/callback"
    # Images: gateway-bff rewrites stored http://localhost:9000/media/… links; media presigns uploads for this host.
    export LAN_MEDIA_URL="http://$h:9000/media" LAN_MINIO_ENDPOINT="http://$h:9000"
    # Next.js dev servers serve their dev assets / HMR to this host.
    export NEXT_ALLOWED_DEV_ORIGINS="$h"
  else
    unset AUTH_ISSUER_URL LAN_WEB_ORIGINS CUSTOMER_APP_DEV_REDIRECT_URIS DRIVER_APP_DEV_REDIRECT_URIS LAN_MEDIA_URL \
      LAN_MINIO_ENDPOINT NEXT_ALLOWED_DEV_ORIGINS
  fi
  export AT_SECURITY_CORS_ALLOWED_ORIGINS="$mobile_origins"
  export WEB_ORIGINS="$sf,$sc,$st,$mobile_origins"
}

# `lan-env`: the addresses the current MOBILE_HOST gives (no side effects; make lan-env MOBILE_HOST=…).
cmd_lan_env() {
  local v
  platform_env
  echo "mode: $(if [[ "$(public_host)" == localhost ]]; then echo localhost; else echo "LAN ($(public_host))"; fi)"
  for v in PUBLIC_WEB_URL SELLER_CENTER_URL STAFF_CONSOLE_URL AUTH_ISSUER_URL WEB_ORIGINS AT_SECURITY_CORS_ALLOWED_ORIGINS \
    LAN_WEB_ORIGINS CUSTOMER_APP_DEV_REDIRECT_URIS DRIVER_APP_DEV_REDIRECT_URIS LAN_MEDIA_URL LAN_MINIO_ENDPOINT \
    NEXT_ALLOWED_DEV_ORIGINS EXPO_PUBLIC_DEV_HOST; do
    printf '  %-32s %s\n' "$v" "${!v:-(unset: service default, localhost)}"
  done
}

mobile_up() {
  local app name port i mocks host clear
  if [[ "${MOBILE:-1}" == 0 ]]; then return 0; fi
  command -v pnpm >/dev/null || return 0
  if [[ ! -x "$ROOT/mobile/apps/customer/node_modules/.bin/expo" ]]; then
    echo "==> installing mobile dependencies (pnpm install)"
    (cd "$ROOT" && pnpm install --frozen-lockfile)
  fi
  platform_env
  mocks=0; if [[ "${MOBILE_MOCKS:-0}" == 1 ]]; then mocks=1; fi
  host="$EXPO_PUBLIC_DEV_HOST"
  echo "==> starting the mobile apps (Expo/Metro; the first bundle builds when you open the app)"
  for app in $MOBILE_APPS; do
    name="mobile-$app"; port=$(mobile_port_of "$app"); clear=""
    if running "$name"; then
      if [[ "$(cat "$PIDS/$name.env" 2>/dev/null || echo localhost)" == "$(env_sig)" ]]; then
        echo "  · $app app already running"; continue
      fi
      # EXPO_PUBLIC_* values are inlined when Metro bundles: restart with a clean cache for the new host.
      echo "  · $app app runs with the other addresses (localhost / LAN mode): restarting it"; stop "$name"; clear="--clear"
    fi
    if port_open "$port"; then
      echo "  ! localhost:$port is in use: $app app skipped (set CUSTOMER_APP_PORT / DRIVER_APP_PORT)" >&2; continue
    fi
    : >"$LOGS/$name.log"
    # --lan: Metro advertises this machine's network address (exp://<LAN IP>:port) to Expo Go and development builds.
    (cd "$ROOT/mobile/apps/$app" || exit 1
      CI=1 EXPO_NO_TELEMETRY=1 EXPO_NO_TYPESCRIPT_SETUP=1 EXPO_PUBLIC_APP_ENV=local EXPO_PUBLIC_MOCKS=$mocks EXPO_PUBLIC_DEV_HOST="$host" \
        EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY="${STRIPE_PUBLISHABLE_KEY:-}" \
        nohup ./node_modules/.bin/expo start --port "$port" --lan $clear >>"$LOGS/$name.log" 2>&1 &
      echo $! >"$PIDS/$name.pid")
    env_sig >"$PIDS/$name.env"
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

# A terminal QR code of a URL (qrencode, if installed: brew install qrencode / apt install qrencode).
qr() {
  if command -v qrencode >/dev/null 2>&1; then qrencode -t ANSIUTF8 -m 1 "$1" 2>/dev/null | sed 's/^/      /'; fi
}

# Ports the phone reaches over USB with `adb reverse` (localhost mode on an Android device or emulator): Metro, the
# web apps, identity, gateway-bff, bookings, fulfilment, media and MinIO.
ADB_PORTS="8081 8082 3000 3001 3002 8100 8200 8109 8108 8207 9000"

cmd_adb_reverse() {
  local p
  command -v adb >/dev/null 2>&1 || { echo "adb not found: install Android Studio (or brew install --cask android-platform-tools)" >&2; exit 1; }
  adb get-state >/dev/null 2>&1 || { echo "No Android device: plug it in, allow USB debugging, check adb devices" >&2; exit 1; }
  for p in $ADB_PORTS; do adb reverse "tcp:$p" "tcp:$p" >/dev/null; done
  echo "  ✓ adb reverse: the phone's localhost:{${ADB_PORTS// /,}} reach this machine (until it is unplugged)"
}

# `device android|ios <app>`: build the development build (expo-dev-client) and install it on a USB-connected phone.
# The first build takes a while (Android: Android Studio SDK + JDK 17; iOS: Xcode + a signing team). Metro from
# `make up` / `make mobile` keeps running: the build is installed with --no-bundler and connects to it.
cmd_device() {
  local platform="${1:-}" app="${2:-customer}" bundler=""
  case "$platform" in android|ios) ;; *) echo "usage: $0 device android|ios [customer|driver]" >&2; exit 2 ;; esac
  case "$app" in customer|driver) ;; *) echo "unknown app: $app (customer | driver)" >&2; exit 2 ;; esac
  mkdir -p "$LOGS" "$PIDS"
  load_secrets
  platform_env
  if port_open "$(mobile_port_of "$app")"; then bundler="--no-bundler"; fi
  if [[ "$platform" == android ]]; then
    command -v adb >/dev/null 2>&1 || { echo "adb not found: install Android Studio and add platform-tools to PATH (docs/engineering/mobile-on-your-phone.md)" >&2; exit 1; }
    adb devices
    if [[ "$(public_host)" == localhost ]]; then cmd_adb_reverse; fi
  fi
  echo "==> building and installing the $app development build on your $platform device (first build: several minutes)"
  (cd "$ROOT/mobile/apps/$app" && EXPO_PUBLIC_APP_ENV=local EXPO_PUBLIC_DEV_HOST="$EXPO_PUBLIC_DEV_HOST" \
    ./node_modules/.bin/expo run:"$platform" --device --port "$(mobile_port_of "$app")" $bundler)
}

# `apk <app>`: a standalone Android app: a release build with the JavaScript inside, so it runs without Metro and
# without a cable, like an installed store app. It talks to this machine's stack over Wi-Fi (LAN mode, which `up`
# must also run in) at the address baked in when it was built: rebuild after the Mac's address changes. Signed with
# the debug key (fine for sideloading, not for the Play Store: that is `eas build --profile production`).
# Needs the Android SDK (Android Studio) and JDK 17. Installs with adb when a phone is connected; otherwise the APK is
# served on the Wi-Fi so the phone's browser can download it.
cmd_apk() {
  local app="${1:-customer}" host built out dir sdk="" java17=""
  case "$app" in customer|driver) ;; *) echo "unknown app: $app (customer | driver)" >&2; exit 2 ;; esac
  export MOBILE_HOST="${MOBILE_HOST:-lan}"
  mkdir -p "$LOGS" "$PIDS"
  load_secrets
  platform_env
  host=$(public_host)
  if [[ "$host" == localhost ]]; then
    echo "No Wi-Fi address found: run with MOBILE_HOST=<this machine's IPv4>, e.g. make mobile-apk MOBILE_HOST=192.168.1.50" >&2
    exit 1
  fi
  for dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -n "$dir" && -d "$dir" ]]; then sdk="$dir"; break; fi
  done
  if [[ -z "$sdk" ]]; then
    echo "Android SDK not found: install Android Studio (it brings the SDK), or set ANDROID_HOME (docs/engineering/mobile-on-your-phone.md)" >&2
    exit 1
  fi
  export ANDROID_HOME="$sdk" PATH="$sdk/platform-tools:$PATH"
  # Gradle for React Native needs JDK 17: prefer it (Android Studio's bundled JBR, Homebrew Zulu) over a newer default.
  for dir in "${JAVA_HOME_17:-}" "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home"; do
    if [[ -n "$dir" && -x "$dir/bin/java" ]]; then java17="$dir"; break; fi
  done
  if [[ -z "$java17" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
    java17=$(/usr/libexec/java_home -v 17 2>/dev/null || true)
  fi
  if [[ -n "$java17" ]]; then export JAVA_HOME="$java17"; fi
  echo "==> building the standalone $app app for $host (release, JavaScript inside; first build: several minutes)"
  (cd "$ROOT/mobile/apps/$app" || exit 1
    export CI=1 EXPO_NO_TELEMETRY=1 EXPO_NO_TYPESCRIPT_SETUP=1 EXPO_PUBLIC_APP_ENV=local EXPO_PUBLIC_MOCKS=0 \
      EXPO_PUBLIC_DEV_HOST="$host" EXPO_PUBLIC_STRIPE_PUBLISHABLE_KEY="${STRIPE_PUBLISHABLE_KEY:-}"
    # android/ is generated (git-ignored): --clean regenerates it so the local settings (plain HTTP to the LAN) apply.
    ./node_modules/.bin/expo prebuild --platform android --clean --no-install
    cd android && ./gradlew assembleRelease) || { echo "Build failed (see above)." >&2; exit 1; }
  built="$ROOT/mobile/apps/$app/android/app/build/outputs/apk/release/app-release.apk"
  [[ -f "$built" ]] || { echo "No APK at $built" >&2; exit 1; }
  mkdir -p "$STATE/apk"
  out="$STATE/apk/afrotransact-$app.apk"
  cp "$built" "$out"
  echo "  ✓ $out (talks to http://$host; package com.afrotransact.$app.local)"
  if command -v adb >/dev/null 2>&1 && adb devices | awk 'NR > 1 && $2 == "device"' | grep -q .; then
    echo "==> installing on the connected phone"
    adb install -r "$out"
    echo "  ✓ installed: open \"$( [[ "$app" == customer ]] && echo AfroTransact || echo 'AfroTransact Delivery')\" on the phone"
    return 0
  fi
  echo "==> no phone on USB: serving the APK on your Wi-Fi (Ctrl-C when the phone has it)"
  echo "  On the phone (same Wi-Fi), open:  http://$host:8765/afrotransact-$app.apk"
  echo "  Allow \"Install unknown apps\" for the browser when Android asks, then open the downloaded file."
  python3 -m http.server 8765 --bind 0.0.0.0 --directory "$STATE/apk"
}

cmd_up() {
  local services="$DEFAULT_SERVICES" svc failed=""
  if [[ $# -gt 0 ]]; then services="$*"; fi
  for svc in $services; do runtime_of "$svc" >/dev/null; done
  load_secrets
  secrets_summary
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

# Demo data on a fresh stack: sellers, ~75 products with photos, offers, stock, home content, pre-orders, and the
# operations data (purchase orders, inbound shipments, orders in every state, routes, returns) when it is missing.
seed_if_empty() {
  if [[ "${SEED:-1}" == 0 ]]; then return 0; fi
  command -v python3 >/dev/null || { echo "python3 not found: demo data skipped (make seed later)" >&2; return 0; }
  echo "==> demo data (tools/seed; only what is missing, SEED=0 skips it; log: .local/logs/seed.log)"
  mkdir -p "$LOGS"
  python3 "$ROOT/tools/seed/seed.py" --if-empty 2>&1 | tee "$LOGS/seed.log" \
    | grep -E '^(seed:|services up|image sources|  [✗!]|  [a-z ]{3,20} {2,}|  attribution)' || true
}

# `repair-orgs`: identity reconciles its copy of every approved or suspended seller/provider organisation with the
# seller service now (what its job does on start-up and every 5 minutes): an approval whose event identity missed gets
# its organisation, status and owner memberships. Needs identity and seller running. Uses the seller-service client
# (SELLER_SERVICE_CLIENT_SECRET, local default), which holds orgs:manage. Signed-in people pick it up on their next
# token refresh (Seller Center does it at once).
cmd_repair_orgs() {
  local issuer secret token res
  load_secrets
  platform_env
  issuer="${AUTH_ISSUER_URL:-http://localhost:$(port_of identity)}"
  secret="${SELLER_SERVICE_CLIENT_SECRET:-local-seller-secret}"
  if ! healthy identity || ! healthy seller; then
    echo "  ✗ identity and seller must be running (make up, or tools/dev/local.sh up identity seller)" >&2; exit 1
  fi
  token=$(curl -fsS -u "seller-service:$secret" -d grant_type=client_credentials -d scope=orgs:manage \
    "$issuer/oauth2/token" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])') || {
    echo "  ✗ no token from $issuer for the seller-service client (SELLER_SERVICE_CLIENT_SECRET?)" >&2; exit 1; }
  res=$(curl -fsS -X POST -H "Authorization: Bearer $token" "http://localhost:$(port_of identity)/v1/internal/orgs/reconcile") || {
    echo "  ✗ identity did not reconcile (is it built from this checkout? tools/dev/local.sh restart identity)" >&2; exit 1; }
  echo "$res" | python3 -c 'import json,sys; print("  ✓ organisations checked: %(scanned)s, repaired: %(repaired)s, failed: %(failed)s" % json.load(sys.stdin))'
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
  # Leftovers this script no longer tracks (older runs): stop any of this repository's services still on their ports.
  for svc in $ALL_SERVICES; do free_port_for "$svc" >/dev/null 2>&1 || true; done
  echo "==> stopping infrastructure (data kept; 'make reset' deletes it). Your own Postgres/Valkey keep running."
  # shellcheck disable=SC2086
  compose stop postgres valkey $INFRA_BASE $OBS_BASE postgres-exporter redis-exporter >/dev/null 2>&1 || true
}

case "${1:-}" in
  up) shift; cmd_up "$@" ;;
  down) cmd_down ;;
  status) cmd_status ;;
  links) links ;;
  mobile) mkdir -p "$LOGS" "$PIDS"; load_secrets
    # identity's issuer must match the host the apps use: LAN mode is a whole-stack switch.
    if running identity && [[ "$(cat "$PIDS/identity.env" 2>/dev/null || echo localhost)" != "$(env_sig)" ]]; then
      echo "  ! the stack runs on $(cat "$PIDS/identity.env" 2>/dev/null || echo localhost), the apps would use $(public_host):" \
        "sign-in would fail. Switch everything with: make up MOBILE_HOST=${MOBILE_HOST:-localhost}" >&2
    fi
    mobile_up; links ;;
  docs) mkdir -p "$LOGS" "$PIDS" "$STATE"; docs_up; storybook_up ;;
  logs) [[ -n "${2:-}" ]] || { echo "usage: $0 logs <service>" >&2; exit 2; }; tail -n 200 -f "$LOGS/$2.log" ;;
  seed) shift; python3 "$ROOT/tools/seed/seed.py" "$@" ;;
  repair-orgs) cmd_repair_orgs ;;
  restart) [[ -n "${2:-}" ]] || { echo "usage: $0 restart <service>" >&2; exit 2; }
    load_secrets
    # Keep the address the stack runs on: a service restarted without MOBILE_HOST while the rest runs in LAN mode would
    # validate tokens against http://localhost:8100 and reject every phone's (iss = the LAN address): sign-in works,
    # everything after it fails.
    if [[ -z "${MOBILE_HOST:-}" && -f "$PIDS/identity.env" ]]; then
      stack_host=$(cat "$PIDS/identity.env" 2>/dev/null || true)
      if [[ -n "$stack_host" && "$stack_host" != localhost ]]; then
        MOBILE_HOST="$stack_host"; export MOBILE_HOST
        echo "  · the stack runs on $stack_host: restarting $2 with it (MOBILE_HOST=$stack_host)"
      fi
    fi
    platform_env
    stop "$2"; build "$2"; start "$2"; wait_healthy "$2" 240 ;;
  lan-env) cmd_lan_env ;;
  adb-reverse) cmd_adb_reverse ;;
  device) shift; cmd_device "$@" ;;
  apk) shift; cmd_apk "$@" ;;
  *) sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
