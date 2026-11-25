# infra/ovh-vps: render the Kubernetes Secrets of UAT and PROD

The folder name is historical (it once held a one-server k3s test box, removed). What is left is the **secrets renderer** of the release-1
go-live: it runs on an operator machine, never on a server, and writes Secret YAML files that are SOPS-encrypted and applied by
`infra/ops/secrets/apply.sh`. Procedure: [docs/runbooks/release-1-go-live.md](../../docs/runbooks/release-1-go-live.md) section 6 and
[infra/ops/secrets/README.md](../ops/secrets/README.md). Do not rename the folder: the tooling and the runbooks use this path.

| File | What |
|---|---|
| `secrets.sh` | `platform`, `services` (and `databases`): every Secret of the launch set, plus `afrotransact-core-secrets` / `afrotransact-go-secrets` with `DB_LAYOUT=schema`. `PROFILE=uat\|prod`, `SECRETS_OUT=<dir>` writes files instead of applying |
| `lib.sh` | `load_config` (reads `config.env` and `secrets.local.env` next to it), `gen` (generated values kept in `$STATE_DIR/generated.env`), `secret` (values never in argv) |
| `services.launch.conf` | the 21 launch-set services, read for `PROFILE=uat\|prod` |
| `services.conf` | every service (default `PROFILE=dev`); `tools/release/parity.py` checks a new service is listed here |
| `uat.all.env.example` | the one private file of every UAT value (`tools/release/uat-env.sh init`, `fill`, `check`, `split`) |
| `config.uat.env.example`, `secrets.uat.env.example` | UAT templates (`uat-env.sh split` writes `config.env` / `secrets.local.env` from the one file; `uat-env.sh drift` keeps the three in step) |
| `config.env.example`, `secrets.local.env.example` | PROD templates |

`config.env`, `secrets.local.env` and `uat.all.env` are git-ignored; never commit them. `STATE_DIR` (`$HOME/.afrotransact-<env>`) holds the
generated values (JWT signing key, `IDENTITY_DATA_KEY`, client secrets): back it up offline with `infra/ops/secrets/escrow.sh create`.

Tests (offline, stub `kubectl`): `bash tools/ovh/test/secrets-multiline.test.sh` (part of `make release-1-check`) and
`bash tools/ovh/test/pg-tls.test.sh` (needs `kubectl` for the client-side render).
