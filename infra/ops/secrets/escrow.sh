#!/usr/bin/env bash
# Offline escrow of the secrets that CANNOT be re-created: encrypts the state directory of infra/ovh-vps/secrets.sh
# (PROFILE=prod STATE_DIR) with age for 2+ recipients, into one file you store OFFLINE (password manager attachment + a USB stick
# or paper in another place), SEPARATELY from the database backups. Without these a perfect database restore is useless:
#   IDENTITY_DATA_KEY      encrypts personal data at rest in identity (lose it = those fields are unreadable)
#   SIGNING_KEY_PEM        the RS256 JWT signing key (lose it = every issued token and session dies; clients re-fetch JWKS)
#   HANDOVER_CODE_KEY, PAY_LINK_KEY (orders), FIELD_ENCRYPTION_KEY (seller), LINK_SIGNING_KEY (media), the *_CLIENT_SECRET set
#
#   infra/ops/secrets/escrow.sh create STATE_DIR OUT_FILE RECIPIENTS_FILE   encrypt (RECIPIENTS_FILE: one age1... public key per line)
#   infra/ops/secrets/escrow.sh cluster OUT_FILE RECIPIENTS_FILE             dump every *-secrets Secret of afrotransact-prod from the cluster, encrypted
#   infra/ops/secrets/escrow.sh restore IN_FILE IDENTITY_FILE OUT_DIR        decrypt into OUT_DIR (the restore drill, docs/runbooks/restore-drill.md)
# Needs: age (https://github.com/FiloSottile/age), tar; kubectl for `cluster`.
set -euo pipefail
cmd="${1:-}"; shift || true
case "$cmd" in
  create)
    state="${1:?STATE_DIR}"; out="${2:?OUT_FILE}"; rec="${3:?RECIPIENTS_FILE}"
    [[ -f "$state/generated.env" ]] || { echo "error: $state/generated.env not found (is this the STATE_DIR of secrets.sh?)" >&2; exit 1; }
    [[ "$(grep -c '^age1' "$rec")" -ge 2 ]] || { echo "error: list at least TWO age recipients (two people / two places) in $rec" >&2; exit 1; }
    tar -C "$state" -czf - . | age -R "$rec" -o "$out"
    echo "wrote $out ($(wc -c < "$out") bytes). Store it offline; test it with: $0 restore $out <identity file> /tmp/escrow-test"
    ;;
  cluster)
    out="${1:?OUT_FILE}"; rec="${2:?RECIPIENTS_FILE}"
    [[ "$(grep -c '^age1' "$rec")" -ge 2 ]] || { echo "error: list at least TWO age recipients in $rec" >&2; exit 1; }
    kubectl -n afrotransact-prod get secrets -o json | age -R "$rec" -o "$out"
    echo "wrote $out"
    ;;
  restore)
    in="${1:?IN_FILE}"; id="${2:?IDENTITY_FILE}"; dest="${3:?OUT_DIR}"
    mkdir -p "$dest"; chmod 700 "$dest"
    age -d -i "$id" "$in" | tar -C "$dest" -xzf - 2>/dev/null || age -d -i "$id" "$in" > "$dest/cluster-secrets.json"
    echo "restored into $dest"
    ;;
  *) echo "usage: escrow.sh create STATE_DIR OUT RECIPIENTS | cluster OUT RECIPIENTS | restore IN IDENTITY OUT_DIR" >&2; exit 2 ;;
esac
