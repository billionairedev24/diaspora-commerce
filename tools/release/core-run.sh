#!/usr/bin/env bash
# Run afrotransact-core (every Java module in one JVM) in the foreground against the local infrastructure (make up-infra or make up).
# The same code path `make up` uses (tools/dev/local.sh core-run): provisions the `core` database on the Postgres the stack uses
# (host or Docker), builds the jar when missing or stale (REBUILD=1 or CORE_REBUILD=1 forces it, CORE_MVN_ARGS=-DnoAi leaves
# ai-gateway and mcp-server out), loads .local/secrets.env, and starts core with the `local` profile on :8120. Needs JDK 25.
# Settings are environment variables (names only here, values never printed): PORT via CORE_PORT (8120), CORE_DB_URL,
# CORE_DB_ADMIN_URL / _USER / _PASSWORD, CORE_DB_PROVISION=0 (a database you manage), CORE_JAVA_OPTS (default
# -Xmx1536m -XX:+UseSerialGC -XX:TieredStopAtLevel=1), MODULE_<NAME>_ENABLED, <NAME>_MODE, REDIS_URL, TEMPORAL_TARGET, KAFKA_BOOTSTRAP.
# Arguments are passed to the JVM's application (Spring properties).
set -euo pipefail
exec "$(dirname "$0")/../dev/local.sh" core-run "$@"
