#!/usr/bin/env bash
# Unit-style tests of tools/dev/local.sh's release-1 pieces (STACK=monolith): service mapping, ports, the preflight, the `core`
# database provisioning (host and Docker Postgres, with stubbed psql / docker), DRYRUN. No Docker, no Postgres, nothing is started.
#   bash tools/dev/test/test-local.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
LOCAL_SH="$ROOT/tools/dev/local.sh"
T="$(mktemp -d)"
trap 'rm -rf "$T"; [[ -z "${LISTENER:-}" ]] || kill "$LISTENER" 2>/dev/null || true' EXIT
mkdir -p "$T/bin"
PASS=0
FAIL=0

ok() { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL $1" >&2; }
expect() { # expect <name> <expected> <actual>
  if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1: expected [$2], got [$3]"; fi
}
contains() { # contains <name> <needle> <haystack>
  if [[ "$3" == *"$2"* ]]; then ok "$1"; else bad "$1: [$2] not in: ${3:0:300}"; fi
}

# ---- stubs: psql and docker log their arguments and stdin under $T and answer the few queries local.sh asks
cat >"$T/bin/psql" <<'STUB'
#!/usr/bin/env bash
echo "psql $*" >>"$T/psql.log"
case "$*" in *" -c "*) ;; *) cat >>"$T/psql.stdin" ;; esac   # SQL piped in (no -c): record it, never wait on a terminal's stdin
case "$*" in
  *rolsuper*) [[ "${STUB_SUPER:-t}" == t ]] && echo t ;;
  *pg_available_extensions*) echo "${STUB_POSTGIS:-1}" ;;
  *"datname = 'core'"*) echo "${STUB_CORE_EXISTS:-1}" ;;
  *max_connections*) echo 300 ;;
esac
exit "${STUB_PSQL_EXIT:-0}"
STUB
cat >"$T/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "docker $*" >>"$T/docker.log"
case "$*" in
  "info"*) exit 0 ;;
  *"ps -q --status running postgres"*) [[ "${STUB_PG_CONTAINER:-1}" == 1 ]] && echo abc123; exit 0 ;;
  *"exec -T postgres psql"*) case "$*" in *" -c "*) ;; *) cat >>"$T/psql.stdin" ;; esac
    case "$*" in *"datname = 'core'"*) echo "${STUB_CORE_EXISTS:-1}" ;; esac
    exit "${STUB_PSQL_EXIT:-0}" ;;
esac
exit 0
STUB
chmod +x "$T/bin/psql" "$T/bin/docker"
export T

# run <script...>: a fresh bash that sources local.sh as a library (nothing runs), stubs first on PATH, state under $T.
run() {
  PATH="$T/bin:$PATH" AT_STATE_DIR="$T/state" LOCAL_SH_LIB=1 bash -c '. "$1"; set +e; shift; eval "$*"' _ "$LOCAL_SH" "$@" 2>&1 </dev/null
}
reset_logs() { rm -f "$T/psql.log" "$T/psql.stdin" "$T/docker.log"; }

echo "== service mapping and ports"
expect "port core" 8120 "$(run 'port_of core')"
expect "port afrotransact-go default (BFF_ORIGIN of the web apps)" 8200 "$(run 'port_of afrotransact-go')"
expect "AFROTRANSACT_GO_PORT override" 8300 "$(AFROTRANSACT_GO_PORT=8300 run 'port_of afrotransact-go')"
expect "old port kept" 8101 "$(run 'port_of catalog')"
expect "runtime core" java "$(run 'runtime_of core')"
expect "runtime afrotransact-go" go "$(run 'runtime_of afrotransact-go')"
expect "health core" "http://localhost:8120/actuator/health/readiness" "$(run 'health_url core')"
expect "health go" "http://localhost:8200/readyz" "$(run 'health_url afrotransact-go')"
expect "monolith: catalog health is core's" "http://localhost:8120/actuator/health/readiness" "$(run 'health_url catalog')"
expect "monolith: search health is go's" "http://localhost:8200/readyz" "$(run 'health_url search')"
expect "services: catalog health is its own" "http://localhost:8101/actuator/health" "$(STACK=services run 'health_url catalog')"
expect "bff origin monolith" "http://localhost:8200" "$(run 'bff_origin')"
expect "bff origin follows the port" "http://localhost:8300" "$(AFROTRANSACT_GO_PORT=8300 run 'bff_origin')"
expect "bff origin services" "http://localhost:8200" "$(STACK=services AFROTRANSACT_GO_PORT=8300 run 'bff_origin')"
expect "restart maps an old Java name" "core" "$(run 'mono_target identity 2>/dev/null')"
expect "restart maps an old Go name" "afrotransact-go" "$(run 'mono_target geo 2>/dev/null')"
expect "web names untouched" "web-storefront" "$(run 'mono_target web-storefront')"
expect "services mode untouched" "identity" "$(STACK=services run 'mono_target identity')"
expect "mono_select default" "1 1" "$(run 'mono_select; echo $WANT_CORE $WANT_GO')"
expect "mono_select Java name" "1 0" "$(run 'mono_select identity catalog >/dev/null; echo $WANT_CORE $WANT_GO')"
expect "mono_select Go name" "0 1" "$(run 'mono_select geo >/dev/null; echo $WANT_CORE $WANT_GO')"
expect "mono_select both deployables" "1 1" "$(run 'mono_select core afrotransact-go; echo $WANT_CORE $WANT_GO')"
expect "bad STACK" 2 "$(STACK=bogus run 'true' >/dev/null; echo $?)"

echo "== peers: Java reaches the Go modules in afrotransact-go"
out="$(run 'mono_peer_env; echo "$CART_URL $SEARCH_URL $MEDIA_URL $GEO_URL $CARRIER_GATEWAY_URL"')"
expect "peer URLs" "http://localhost:8200/modules/cart http://localhost:8200/modules/search http://localhost:8200 http://localhost:8200 http://localhost:8200/modules/carrier-gateway" "$out"
expect "own MEDIA_URL wins" "http://x" "$(MEDIA_URL=http://x run 'mono_peer_env; echo $MEDIA_URL')"
expect "core pools are not the services' pools" "unset" "$(DB_URL=x SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3 run 'core_env; echo ${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:-unset}')"
expect "core env: port" 8120 "$(run 'core_env; echo $PORT')"
expect "core env: retail on locally" "true true" "$(run 'core_env; echo $MODULE_RETAIL_ENABLED $MODULE_DELIVERY_RUNS_ENABLED')"
expect "go env: promise on locally" "local 8200 true" "$(run 'gomono_env; echo $APP_ENV $AFROTRANSACT_GO_PORT $AT_MODULES_PROMISE_ENABLED')"
expect "go env: no stray PORT/DB_URL" "-/-" "$(PORT=1 DB_URL=x run 'gomono_env; echo "${PORT:--}/${DB_URL:--}"')"

echo "== search wait target"
expect "meilisearch default" "http://localhost:7700/health" "$(run 'search_url')"
expect "elasticsearch" "http://localhost:9200/_cluster/health" "$(SEARCH_PROVIDER=elasticsearch run 'search_url')"

echo "== core database: Docker Postgres"
reset_logs
out="$(PG_EXTERNAL=0 run 'core_db; echo "rc=$? $CORE_DB_URL $CORE_DB_ADMIN_USER"')"
contains "docker: success" "rc=0 jdbc:postgresql://localhost:5432/core postgres" "$out"
contains "docker: provision SQL went into the container" "CREATE DATABASE core" "$(cat "$T/psql.stdin" 2>/dev/null)"
contains "docker: exec on the postgres service" "exec -T postgres psql -U postgres" "$(cat "$T/docker.log")"
out="$(PG_EXTERNAL=0 POSTGRES_HOST_PORT=5433 run 'core_db >/dev/null; echo $CORE_DB_URL')"
expect "docker: mapped port" "jdbc:postgresql://localhost:5433/core" "$out"
out="$(PG_EXTERNAL=0 CORE_DB_URL=jdbc:postgresql://db/core run 'core_db >/dev/null; echo $CORE_DB_URL')"
expect "own CORE_DB_URL wins" "jdbc:postgresql://db/core" "$out"
out="$(PG_EXTERNAL=0 STUB_PG_CONTAINER=0 run 'core_db; echo rc=$?')"
contains "docker stopped: says what to run" "make up-infra" "$out"
contains "docker stopped: rc 1" "rc=1" "$out"
out="$(PG_EXTERNAL=0 STUB_CORE_EXISTS=0 run 'core_db; echo rc=$?')"
contains "database still missing after provisioning is an error" "still not there" "$out"
out="$(PG_EXTERNAL=0 STUB_PSQL_EXIT=3 run 'core_db; echo rc=$?')"
contains "provisioning failure is reported" "provisioning on the Docker postgres failed" "$out"
reset_logs
out="$(CORE_DB_PROVISION=0 run 'core_db; echo rc=$?')"
contains "CORE_DB_PROVISION=0 skips" "rc=0" "$out"
expect "CORE_DB_PROVISION=0 touches nothing" "" "$(cat "$T/docker.log" "$T/psql.log" 2>/dev/null)"

echo "== core database: your own Postgres on localhost:5432"
reset_logs
out="$(PG_EXTERNAL=1 PG_SUPERUSER=me PGPASSWORD=pw run 'core_db; echo "rc=$? $CORE_DB_URL $CORE_DB_ADMIN_USER"')"
contains "host tcp: success" "rc=0 jdbc:postgresql://localhost:5432/core me" "$out"
contains "host tcp: psql over TCP as the superuser" "-h localhost -p 5432 -U me" "$(cat "$T/psql.log")"
contains "host: provision SQL piped to psql" "CREATE DATABASE core" "$(cat "$T/psql.stdin")"
expect "host: the Docker postgres is not touched" "" "$(grep -c 'exec' "$T/docker.log" 2>/dev/null | grep -v '^0$' || true)"
out="$(PG_EXTERNAL=1 run 'core_db; echo "rc=$? [$CORE_DB_ADMIN_URL] $PSQL_MODE"')"
contains "host socket: core's own admin connection is off, roles exist already" "rc=0 [] socket" "$out"
out="$(PG_EXTERNAL=1 STUB_SUPER=f run 'core_db; echo rc=$?')"
contains "host: no superuser, says what to run" "PG_SUPERUSER=<superuser> PGPASSWORD=<password> make core-db" "$out"
out="$(PG_EXTERNAL=1 PG_SUPERUSER=me STUB_POSTGIS=0 run 'core_db; echo rc=$?')"
contains "host: no PostGIS, says what to install" "brew install postgis" "$out"
expect "an empty admin password you set is kept" "" "$(PG_EXTERNAL=1 PG_SUPERUSER=me CORE_DB_ADMIN_PASSWORD='' run 'core_db >/dev/null; echo "$CORE_DB_ADMIN_PASSWORD"')"

echo "== preflight"
if command -v python3 >/dev/null && ! (exec 3<>/dev/tcp/127.0.0.1/8101) 2>/dev/null; then
  python3 -c 'import socket,time; s=socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(("127.0.0.1", 8101)); s.listen(); time.sleep(60)' >/dev/null 2>&1 &
  LISTENER=$!
  sleep 1
  out="$(run 'preflight 1 0; echo "$PREFLIGHT_PROBLEMS"')"
  contains "busy port 8101 is named" "port 8101 is in use by" "$out"
  contains "busy port: one actionable message" "stop it" "$out"
  kill "$LISTENER" 2>/dev/null; LISTENER=""
else
  echo "  skip port test (python3 missing or 8101 busy)"
fi
out="$(PATH="$T/bin:/usr/bin:/bin" AT_STATE_DIR="$T/state" LOCAL_SH_LIB=1 JAVA_HOME='' bash -c '. "$1"; set +e; preflight 0 1; echo "$PREFLIGHT_PROBLEMS"' _ "$LOCAL_SH" 2>&1)"
contains "Docker is checked" "Docker" "$out"

echo "== DRYRUN prints the plan and starts nothing"
out="$(PATH="$T/bin:$PATH" AT_STATE_DIR="$T/state" DRYRUN=1 "$LOCAL_SH" up 2>&1 </dev/null)"
contains "plan header" "STACK=monolith plan" "$out"
contains "plan: core on 8120" "start on :8120" "$out"
contains "plan: go on 8200" "start on :8200" "$out"
contains "plan: web apps use the BFF" "BFF_ORIGIN=http://localhost:8200" "$out"
contains "plan: database step" "provision-core.sql" "$out"
[[ ! -e "$T/state/pids" && ! -e "$T/state/logs" ]] && ok "DRYRUN creates no state" || bad "DRYRUN created state under $T/state"
out="$(PATH="$T/bin:$PATH" AT_STATE_DIR="$T/state" DRYRUN=1 STACK=services "$LOCAL_SH" up 2>&1 </dev/null)"
contains "services plan" "STACK=services plan" "$out"
out="$(PATH="$T/bin:$PATH" AT_STATE_DIR="$T/state" DRYRUN=1 "$LOCAL_SH" up geo 2>&1 </dev/null)"
contains "SERVICES=geo plans afrotransact-go only" "afrotransact-go: go build" "$out"
[[ "$out" != *"5. core:"* ]] && ok "SERVICES=geo leaves core out" || bad "SERVICES=geo still plans core"
out="$(PATH="$T/bin:$PATH" AT_STATE_DIR="$T/state" "$LOCAL_SH" bogus 2>&1)"
contains "usage mentions the release-1 commands" "core-db" "$(sed -n '2,40p' "$LOCAL_SH")"

echo
echo "passed: $PASS, failed: $FAIL"
[[ "$FAIL" -eq 0 ]]
