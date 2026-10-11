#!/usr/bin/env bash
# Export a Java service's OpenAPI document into contracts/openapi/<service>.yaml.
# Usage: tools/contracts/export_java_openapi.sh <service-dir-name> <db-name> <local-port> [extra spring args…]
# Starts a throwaway PostGIS container, runs the service jar with the local profile, fetches /v3/api-docs.yaml,
# then normalises servers with normalize_openapi.py. Requires Docker and a built jar (mvn package).
set -euo pipefail
svc="$1"; db="$2"; port="$3"; shift 3
root="$(cd "$(dirname "$0")/../.." && pwd)"
jar=$(ls "$root"/java/services/"$svc"/target/*-service-*.jar | grep -v plain | head -1)
name="openapi-export-$svc-$$"
pgport=$((20000 + RANDOM % 20000)); appport=$((40000 + RANDOM % 20000))
docker run -d --rm --name "$name" -e POSTGRES_USER="$db" -e POSTGRES_PASSWORD="$db" -e POSTGRES_DB="$db" \
  -p "$pgport:5432" imresamu/postgis:17-3.5.4-alpine3.22 >/dev/null
trap 'kill "${pid:-0}" 2>/dev/null || true; docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 60); do docker exec "$name" pg_isready -U "$db" >/dev/null 2>&1 && break; sleep 1; done
sleep 2
"${JAVA25_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}/bin/java" -jar "$jar" --spring.profiles.active=local \
  --server.port="$appport" --spring.datasource.url="jdbc:postgresql://localhost:$pgport/$db" \
  --spring.datasource.username="$db" --spring.datasource.password="$db" --at.events.relay-enabled=false "$@" \
  >/tmp/openapi-export-$svc.log 2>&1 &
pid=$!
for _ in $(seq 1 90); do curl -sf "localhost:$appport/actuator/health" >/dev/null && break; sleep 1; done
curl -sf "localhost:$appport/v3/api-docs.yaml" -o "$root/contracts/openapi/$svc.yaml"
python3 "$root/tools/contracts/normalize_openapi.py" "$root/contracts/openapi/$svc.yaml" "$svc" "$port"
echo "exported contracts/openapi/$svc.yaml ($(grep -c operationId "$root/contracts/openapi/$svc.yaml") operations)"
