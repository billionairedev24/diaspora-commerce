#!/usr/bin/env bash
# Run afrotransact-core (every Java module in one JVM) against the local infrastructure (make up-infra). Builds the jar when missing
# (CORE_REBUILD=1 forces it). Needs JDK 25. Settings are environment variables (names only here, values never printed):
#   PORT (8120), CORE_DB_URL, MODULE_<NAME>_ENABLED (e.g. MODULE_AI_GATEWAY_ENABLED=false), <NAME>_MODE for http mode,
#   REDIS_URL, TEMPORAL_TARGET, KAFKA_BOOTSTRAP, provider keys from .local/secrets.env (loaded when present).
set -euo pipefail
cd "$(dirname "$0")/../.."
JAR=java/apps/core/target/afrotransact-core.jar
if [[ ! -f "$JAR" || "${CORE_REBUILD:-0}" == "1" ]]; then
  (cd java && ./mvnw -B -q -pl apps/core -am -DskipTests ${CORE_MVN_ARGS:-} package)
fi
if [[ -f .local/secrets.env ]]; then set -a; . .local/secrets.env; set +a; fi
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
exec java ${CORE_JAVA_OPTS:--Xmx1g -XX:+UseSerialGC -XX:TieredStopAtLevel=1} -jar "$JAR" "$@"
