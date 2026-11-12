# Production secrets (SOPS + age)

Production runs on OVH Managed Kubernetes with the **prod** profile. Secrets are Kubernetes `Secret` objects that the pods read
with `envFrom` (`<service>-secrets`); Argo CD does not manage them. They are rendered on an operator's machine, encrypted with
[SOPS](https://github.com/getsops/sops) + [age](https://github.com/FiloSottile/age), and applied with `apply.sh`.
The OKMS + External Secrets alternative is optional and not verified against a live account
(`infra/k8s/platform/lean/external-secrets`).

| File | Purpose |
|---|---|
| `../../ovh-vps/secrets.sh` | `PROFILE=prod` renders every Secret of the 21-service launch set (no OpenFGA), `APP_ENV=prod`, with `SECRETS_OUT` writing YAML files instead of applying |
| Grafana Cloud | `./secrets.sh platform` also renders `observability/grafana-cloud-alloy` (Alloy: metrics + logs) next to `grafana-cloud-otlp` (traces); keys and where to get them: `docs/runbooks/observability-grafana-cloud.md` |
| `apply.sh` | decrypt with SOPS, refuse empty mandatory keys, `kubectl apply`, label namespaces |
| `escrow.sh` | offline age bundle of `STATE_DIR` (IDENTITY_DATA_KEY, JWT signing key, ...), kept separately from the DB backups |
| `.sops.yaml` | creation rules (put your two age public keys in) |
| `prod/` | the encrypted `*.yaml` (commit ONLY if this repository is private; otherwise keep them in a private repo or storage) |

## Render, encrypt, apply

```bash
cd infra/ovh-vps
cp config.env.example config.env            # DOMAIN=afrotransact.com, IMAGE_TAG=<sha>, DB_MODE=external, EXTERNAL_PG_* = Layerbase admin
cp secrets.local.env.example secrets.local.env   # Stripe LIVE keys, Resend, OpenRouter, Cloudflare tunnel token, backup remote ...
export PROFILE=prod STATE_DIR=$HOME/.afrotransact-prod SECRETS_OUT=$(mktemp -d)
./secrets.sh platform && ./secrets.sh services        # generated values are kept in STATE_DIR so re-runs change nothing

cd ../ops/secrets && cp -r "$SECRETS_OUT"/. prod/ && rm -rf "$SECRETS_OUT"
sops --encrypt --in-place prod/*.yaml                 # uses .sops.yaml; never commit before this
export CONFIRM_CONTEXT=$(kubectl config current-context)
SOPS_AGE_KEY_FILE=~/.config/sops/age/keys.txt ./apply.sh
```

Never reuse test or dev values: every internal secret is generated fresh for prod (`STATE_DIR` must be a new directory).
`secrets.sh` does not need a cluster in `SECRETS_OUT` mode (kubectl only renders YAML offline).

## What must be backed up, and where

`STATE_DIR` holds values that cannot be derived again: `IDENTITY_DATA_KEY`, the RSA signing key (`identity-signing-key.pem`),
`HANDOVER_CODE_KEY`, `PAY_LINK_KEY`, `FIELD_ENCRYPTION_KEY`, `LINK_SIGNING_KEY`, every `*_CLIENT_SECRET` and the database role passwords.
Run `escrow.sh create` right after the first render and again after every rotation. Store the result **offline and separately from
the database backups** (a perfect database restore without `IDENTITY_DATA_KEY` leaves identity's encrypted fields unreadable).

## Rotating

Edit the value in `STATE_DIR/generated.env` (or delete the line to regenerate), re-render, re-encrypt, re-apply, then
`kubectl -n afrotransact-prod rollout restart deploy/<service>`. `IDENTITY_DATA_KEY` and `LINK_SIGNING_KEY` have a
`*_PREVIOUS` list for graceful rotation; follow `docs/runbooks/production-readiness.md` section 3.
