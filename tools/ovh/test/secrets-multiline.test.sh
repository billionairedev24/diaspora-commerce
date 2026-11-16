#!/usr/bin/env bash
# Tests of infra/ovh-vps/secrets.sh and lib.sh `secret()` with a STUB kubectl (no cluster, no network, nothing installed):
#   * a multi-line value (identity's SIGNING_KEY_PEM, the pg-ca bundle) is ONE key with the exact bytes, not one junk key per line;
#   * the Secret keys of identity are exactly the expected names (no key that looks like a PEM line);
#   * no secret value ever appears in kubectl's argv (the stub records every argv; `ps` would show it) nor on stdout/stderr of the script.
# The key is a throwaway generated here; it is never used anywhere else.
#   bash tools/ovh/test/secrets-multiline.test.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REAL=$(cd "$HERE/../../.." && pwd)
PASS=0 FAILN=0
ok() { PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"; }
ko() { FAILN=$((FAILN + 1)); printf '  FAIL %s\n' "$1"; [ -z "${2:-}" ] || printf '%s\n' "$2" | sed 's/^/       | /'; }

command -v openssl >/dev/null || { echo "skip: openssl not installed"; exit 0; }
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/bin" "$W/state" "$W/infra"
cp -R "$REAL/infra/ovh-vps" "$W/ovh"
ln -s "$W/ovh" "$W/infra/ovh-vps"; mkdir -p "$W/infra/ops/postgres"

# --- stub kubectl: `create secret generic NAME --from-file=K=path ... --dry-run=client -o yaml` -> the YAML kubectl prints (data base64).
# Every argv is appended to $KUBECTL_LOG. --from-literal is refused: it is what leaked values into argv before.
cat > "$W/bin/kubectl" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${KUBECTL_LOG:?}"
ns="" name="" files=()
while [ $# -gt 0 ]; do
  case "$1" in
    -n) ns="$2"; shift ;;
    create|secret|generic|--dry-run=client|-o|yaml) ;;
    --from-literal=*) echo "stub kubectl: --from-literal puts values in argv" >&2; exit 9 ;;
    --from-file=*) files+=("${1#--from-file=}") ;;
    --docker-*) ;;
    *) [ -n "$name" ] || name="$1" ;;
  esac
  shift
done
echo "apiVersion: v1"; echo "data:"
for f in "${files[@]}"; do k="${f%%=*}"; p="${f#*=}"; printf '  %s: %s\n' "$k" "$(base64 -w0 < "$p")"; done
echo "kind: Secret"; echo "metadata:"; echo "  name: $name"; echo "  namespace: $ns"
STUB
chmod +x "$W/bin/kubectl"
export KUBECTL_LOG="$W/kubectl.log"

# --- the throwaway multi-line value: a PEM, with the awkward characters a base64 body has
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$W/key.pem" 2>/dev/null
printf -- '-----BEGIN CERTIFICATE-----\nMIIBfake=\nbmV2ZXIgYSByZWFsIGNlcnQ=\n-----END CERTIFICATE-----\n' > "$W/ca.pem"
cat > "$W/ovh/config.env" <<EOF
DOMAIN=example.com
IMAGE_REGISTRY=ghcr.io/example/x
IMAGE_TAG=0000000000000000000000000000000000000000
DB_MODE=external
EXTERNAL_PG_HOST=db.example.net
EXTERNAL_PG_ADMIN_USER=admin
EXTERNAL_PG_ADMIN_PASSWORD=not-a-real-password
EOF
printf 'identity java identity 1\ncatalog java catalog 2\ncart go cart 3\n' > "$W/ovh/services.test.conf"
printf 'ADMIN_EMAIL=ops@afrotransact.com\nADMIN_PASSWORD=a-long-test-password-123\n' > "$W/ovh/secrets.local.env"

render() { # render VAR=value... : runs `secrets.sh services`; RC, OUT (stdout+stderr) and the YAML files in $W/out
  rm -rf "$W/out" "$W/state"; mkdir -p "$W/state"; : > "$KUBECTL_LOG"
  cp "$W/key.pem" "$W/state/identity-signing-key.pem"
  OUT=$(env -i PATH="$W/bin:$PATH" HOME="$W" TMPDIR="$W" KUBECTL_LOG="$KUBECTL_LOG" PROFILE=prod SERVICES_CONF="$W/ovh/services.test.conf" STATE_DIR="$W/state" \
        SECRETS_OUT="$W/out" "$@" bash "$W/ovh/secrets.sh" services 2>&1)
  RC=$?
}
keys() { sed -n '/^data:/,/^kind:/p' "$W/out/$1" | sed -n 's/^  \([A-Za-z0-9._-]*\): .*/\1/p' | sort | tr '\n' ' '; }
val() { sed -n "s/^  $2: //p" "$W/out/$1" | base64 -d 2>/dev/null; }   # val FILE KEY

echo "== multi-line SIGNING_KEY_PEM is one key, byte for byte"
render
[ "$RC" = 0 ] && ok "renders" || ko "renders (rc $RC)" "$OUT"
f=afrotransact-prod.identity-secrets.yaml
[ "$(keys $f | tr ' ' '\n' | grep -c -- '-----')" = 0 ] && ok "no key looks like a PEM line" || ko "junk keys from the PEM" "$(keys $f)"
printf '%s' "$(keys $f)" | grep -qw SIGNING_KEY_PEM && ok "SIGNING_KEY_PEM is a key" || ko "SIGNING_KEY_PEM missing" "$(keys $f)"
[ "$(val $f SIGNING_KEY_PEM)" = "$(cat "$W/key.pem")" ] && ok "SIGNING_KEY_PEM value is the whole PEM (newlines kept)" || ko "PEM value differs" "$(val $f SIGNING_KEY_PEM | head -3)"
[ "$(val $f SIGNING_KEY_PEM | wc -l)" -gt 20 ] && ok "value spans many lines" || ko "value is not multi-line"
for k in DB_URL DB_USER DB_PASSWORD IDENTITY_DATA_KEY KAFKA_BOOTSTRAP APP_ENV BOOTSTRAP_ADMIN_EMAIL CATALOG_CLIENT_SECRET; do
  printf '%s' "$(keys $f)" | grep -qw "$k" && ok "identity key $k present" || ko "identity key $k missing" "$(keys $f)"
done
[ "$(val $f BOOTSTRAP_ADMIN_EMAIL)" = "ops@afrotransact.com" ] && ok "single-line values unchanged" || ko "BOOTSTRAP_ADMIN_EMAIL differs"
[ "$(val $f IDENTITY_DATA_KEYS_PREVIOUS | wc -c)" = 0 ] && ok "empty value stays an empty key" || ko "empty value lost"

echo "== multi-line pg-ca bundle"
render EXTERNAL_PG_SSLMODE=verify-full EXTERNAL_PG_CA_FILE="$W/ca.pem"
[ "$RC" = 0 ] && ok "renders" || ko "renders (rc $RC)" "$OUT"
[ "$(val afrotransact-prod.pg-ca.yaml ca.crt)" = "$(cat "$W/ca.pem")" ] && ok "pg-ca ca.crt is the whole bundle" || ko "pg-ca differs"

echo "== values stay out of argv, logs and temp files"
render
leak=""
for secretvalue in "$(sed -n '2p' "$W/key.pem")" "$(sed -n '5p' "$W/key.pem")" "$(val afrotransact-prod.identity-secrets.yaml IDENTITY_DATA_KEY)" "a-long-test-password-123"; do
  [ -n "$secretvalue" ] || continue
  grep -qF -- "$secretvalue" "$KUBECTL_LOG" && leak="$leak argv"
  printf '%s' "$OUT" | grep -qF -- "$secretvalue" && leak="$leak output"
done
[ -z "$leak" ] && ok "no secret value in kubectl argv or script output" || ko "values leaked:$leak"
grep -q -- '--from-literal' "$KUBECTL_LOG" && ko "--from-literal still used" || ok "no --from-literal"
[ -z "$(ls -d "$W"/at-secret.* 2>/dev/null)" ] && ok "temp directories removed" || ko "temp directory left behind" "$(ls -d "$W"/at-secret.*)"
perm=$(stat -c %a "$W/out/afrotransact-prod.identity-secrets.yaml"); [ "$perm" = 600 ] && ok "rendered file is 0600" || ko "rendered file mode $perm"

echo "== a bad key name is refused"
OUT=$(env -i PATH="$W/bin:$PATH" HOME="$W" TMPDIR="$W" bash -c ". '$W/ovh/lib.sh' 2>/dev/null; secret ns n 'bad key=v'" 2>&1); RC=$?
[ "$RC" != 0 ] && ok "invalid Secret key name refused" || ko "invalid key accepted"

printf '\npassed: %d, failed: %d\n' "$PASS" "$FAILN"
[ "$FAILN" = 0 ]
