# infra/ovh-vps: render the Kubernetes Secrets of production

The folder name is historical (it once held a one-server k3s test box, removed). What is left is the **secrets renderer**: it runs on an
operator machine, never on a server, and writes Secret YAML files that are SOPS-encrypted and applied by `infra/ops/secrets/apply.sh`.
Procedure: [docs/runbooks/ovh-production.md](../../docs/runbooks/ovh-production.md) section 6 and
[infra/ops/secrets/README.md](../ops/secrets/README.md).

| File | What |
|---|---|
| `secrets.sh` | `platform`, `databases`, `services`: every Secret of the launch set (`PROFILE=prod`); `SECRETS_OUT=<dir>` writes files instead of applying |
| `lib.sh` | `load_config` (reads `config.env` and `secrets.local.env` next to it), `gen` (generated values kept in `$STATE_DIR/generated.env`), `secret` (values never in argv) |
| `services.launch.conf` | the launch-set services, read for `PROFILE=prod` |
| `services.conf` | every service (default `PROFILE=dev`) |
| `config.env.example`, `secrets.local.env.example` | templates |

`config.env` and `secrets.local.env` are git-ignored; never commit them. `STATE_DIR` holds the generated values (JWT signing key,
`IDENTITY_DATA_KEY`, client secrets): back it up offline with `infra/ops/secrets/escrow.sh create`.

Tests (offline, stub `kubectl`): `bash tools/ovh/test/secrets-multiline.test.sh` and `bash tools/ovh/test/pg-tls.test.sh`.
