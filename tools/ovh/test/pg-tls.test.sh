#!/usr/bin/env bash
# F11: tests of Postgres TLS rendering. secrets.sh (PROFILE=prod, SECRETS_OUT, no cluster) must write sslmode=require by default,
# sslmode=verify-full&sslrootcert=<mounted path> in BOTH the JDBC URL (Java) and the postgres:// DSN (Go) when asked, create the
# pg-ca Secret, and refuse unsafe combinations. When helm is installed, the at-service library chart must mount the CA Secret
# only when postgresCA.enabled. No network, no cluster (kubectl is used offline with --dry-run=client).
#   bash tools/ovh/test/pg-tls.test.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REAL=$(cd "$HERE/../../.." && pwd)
PASS=0 FAILN=0
ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
ko() { FAILN=$((FAILN + 1)); printf '  FAIL %s\n' "$1"; [ -z "${2:-}" ] || printf '%s\n' "$2" | sed 's/^/       | /'; }

command -v kubectl >/dev/null || { echo "skip: kubectl not installed"; exit 0; }
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cp -R "$REAL/infra/ovh-vps" "$W/ovh"
mkdir -p "$W/infra" && ln -s "$W/ovh" "$W/infra/ovh-vps" && mkdir -p "$W/state"
printf -- '-----BEGIN CERTIFICATE-----\nMIIBfake\n-----END CERTIFICATE-----\n' > "$W/ca.pem"
printf 'not a certificate\n' > "$W/bad.pem"
cat > "$W/ovh/config.env" <<EOF
DOMAIN=example.com
IMAGE_REGISTRY=ghcr.io/example/x
IMAGE_TAG=0000000000000000000000000000000000000000
DB_MODE=external
EXTERNAL_PG_HOST=db.example.net
EXTERNAL_PG_ADMIN_USER=admin
EXTERNAL_PG_ADMIN_PASSWORD=not-a-real-password
EOF
# One Java and one Go service with a database (identity's multi-line signing key is not what this test is about).
printf 'catalog java catalog 2\ncart go cart 3\n' > "$W/ovh/services.test.conf"
cat > "$W/ovh/secrets.local.env" <<EOF
ADMIN_EMAIL=ops@afrotransact.com
ADMIN_PASSWORD=a-long-test-password-123
EOF

# render VAR=value...: runs secrets.sh services; leaves RC, ERR and the rendered files in $W/out
render() {
  rm -rf "$W/out" "$W/state"; mkdir -p "$W/state"
  # BASH_ENV: optional file sourced by the script's bash (only needed on a bash 3.2 laptop that lacks mapfile; CI is bash 5).
  ERR=$(env -i PATH="$PATH" HOME="$W" BASH_ENV="${PGTLS_TEST_BASH_ENV:-}" PROFILE=prod SERVICES_CONF="$W/ovh/services.test.conf" STATE_DIR="$W/state" SECRETS_OUT="$W/out" "$@" bash "$W/ovh/secrets.sh" services 2>&1 >/dev/null)
  RC=$?
}
# the DB_URL of one service secret (kubectl dry-run writes base64 under data:)
db_url() { sed -n 's/^  DB_URL: //p' "$W/out/afrotransact-prod.$1-secrets.yaml" | base64 -d 2>/dev/null; }

echo "== default stays sslmode=require"
render 
[ "$RC" = 0 ] && ok "renders" || ko "renders (rc $RC)" "$ERR"
[ "$(db_url catalog)" = "jdbc:postgresql://db.example.net:5432/catalog?sslmode=require" ] && ok "JDBC url: sslmode=require, no rootcert" || ko "JDBC url" "$(db_url catalog)"
case "$(db_url cart)" in *"?sslmode=require") ok "Go DSN: sslmode=require, no rootcert" ;; *) ko "Go DSN" "$(db_url cart)" ;; esac
printf '%s' "$ERR" | grep -q "F11" && ok "prod warns that the server is not verified" || ko "no warning for require in prod" "$ERR"
[ ! -e "$W/out/afrotransact-prod.pg-ca.yaml" ] && ok "no pg-ca Secret" || ko "unexpected pg-ca Secret"

echo "== verify-full"
render EXTERNAL_PG_SSLMODE=verify-full EXTERNAL_PG_CA_FILE="$W/ca.pem"
[ "$RC" = 0 ] && ok "renders" || ko "renders (rc $RC)" "$ERR"
[ "$(db_url catalog)" = "jdbc:postgresql://db.example.net:5432/catalog?sslmode=verify-full&sslrootcert=/etc/pg-ca/ca.crt" ] && ok "JDBC url: verify-full + sslrootcert" || ko "JDBC url" "$(db_url catalog)"
case "$(db_url cart)" in *"?sslmode=verify-full&sslrootcert=/etc/pg-ca/ca.crt") ok "Go DSN: verify-full + sslrootcert" ;; *) ko "Go DSN" "$(db_url cart)" ;; esac
[ -e "$W/out/afrotransact-prod.pg-ca.yaml" ] && ok "pg-ca Secret rendered" || ko "pg-ca Secret missing"
printf '%s' "$ERR" | grep -q "F11" && ko "no warning expected for verify-full" "$ERR" || ok "no warning for verify-full"
render EXTERNAL_PG_SSLMODE=verify-full EXTERNAL_PG_CA_FILE="$W/ca.pem" EXTERNAL_PG_SSLROOTCERT=/certs/pg/ca.crt
case "$(db_url cart)" in *"sslrootcert=/certs/pg/ca.crt") ok "custom EXTERNAL_PG_SSLROOTCERT is used" ;; *) ko "custom rootcert" "$(db_url cart)" ;; esac

echo "== refusals"
render EXTERNAL_PG_SSLMODE=verify-full
[ "$RC" != 0 ] && printf '%s' "$ERR" | grep -q EXTERNAL_PG_CA_FILE && ok "verify-full without a CA file is refused" || ko "missing CA file accepted" "$ERR"
render EXTERNAL_PG_SSLMODE=verify-full EXTERNAL_PG_CA_FILE="$W/bad.pem"
[ "$RC" != 0 ] && ok "a non-PEM CA file is refused" || ko "bad CA accepted"
render EXTERNAL_PG_SSLMODE=verify-full EXTERNAL_PG_CA_FILE="$W/ca.pem" EXTERNAL_PG_SSLROOTCERT=/etc/pg-ca/root.pem
[ "$RC" != 0 ] && ok "a rootcert path not ending in ca.crt is refused" || ko "bad rootcert path accepted"
render EXTERNAL_PG_SSLMODE=sometimes
[ "$RC" != 0 ] && ok "an unknown sslmode is refused" || ko "unknown sslmode accepted"
render EXTERNAL_PG_REQUIRE_VERIFY=true
[ "$RC" != 0 ] && ok "EXTERNAL_PG_REQUIRE_VERIFY=true refuses sslmode=require in prod" || ko "REQUIRE_VERIFY ignored"

echo "== helm: the library chart mounts the CA only when asked"
if command -v helm >/dev/null; then
  C="$REAL/go/services/media/deploy/helm"
  helm dependency build "$C" >/dev/null 2>&1
  OFF=$(helm template media "$C" -f "$C/values-prod.yaml" 2>&1)
  ON=$(helm template media "$C" -f "$C/values-prod.yaml" --set postgresCA.enabled=true 2>&1)
  printf '%s' "$OFF" | grep -q "pg-ca" && ko "pg-ca mounted although disabled" || ok "disabled: no pg-ca volume"
  printf '%s' "$ON" | grep -q "secretName: pg-ca" && printf '%s' "$ON" | grep -q "mountPath: /etc/pg-ca" && ok "enabled: pg-ca Secret mounted at /etc/pg-ca" || ko "enabled but not mounted" "$(printf '%s' "$ON" | tail -5)"
  CUSTOM=$(helm template media "$C" -f "$C/values-prod.yaml" --set postgresCA.enabled=true --set postgresCA.secretName=x-ca --set postgresCA.mountPath=/certs/pg 2>&1)
  printf '%s' "$CUSTOM" | grep -q "secretName: x-ca" && printf '%s' "$CUSTOM" | grep -q "mountPath: /certs/pg" && ok "custom secret name and path" || ko "custom values ignored"
else
  echo "  skip helm not installed"
fi

echo "$PASS passed, $FAILN failed"
[ "$FAILN" = 0 ]
