## What and why

<!-- One or two sentences. Link the backlog story: S0xx (docs/backlog/backlog.csv). -->

Stories: S

## Scope

- [ ] One service / app per PR where possible (each has its own path-filtered workflow and deploys on its own)
- [ ] Contract first: `contracts/openapi/<service>.yaml` / `contracts/asyncapi/` updated; no unplanned breaking change (oasdiff)
- [ ] Database changes are expand-and-contract Flyway/Atlas migrations

## Checks

- [ ] Code rules (CLAUDE.md): interface + implementation, no wildcard / inline FQN / static imports, hexagonal packages
- [ ] Unit, ArchUnit/lint and Testcontainers integration tests added or updated
- [ ] Helm chart renders for dev, uat and prod (`helm template … | kubeconform`)
- [ ] Config for local, dev, uat and prod; secrets only via env vars / the cloud secret store
- [ ] UI changes: Storybook stories, a11y checks, E2E journey updated
- [ ] Infra changes: `tofu plan` reviewed for every affected environment; policy (conftest) green
- [ ] Backlog story set to `Done` when its acceptance criteria are met (`python3 tools/backlog/mark.py S0xx --status Done`)

## release-1 parity (ADR 0011: release-1, the modular monolith, is what goes to production)

**Done on main/develop is not done until release-1 parity is green** ([docs/engineering/release-1-parity.md](../docs/engineering/release-1-parity.md)).

- [ ] Targets release-1, or main/develop and I accept that the next sync PR must show parity green before the story is `Done`
- [ ] New service: it is also a module (`@ModuleSpec` / `registry.go`, `release-1.yaml`, chart `modules:`, bootstrap SQL, `services.conf`, `secrets.sh`), or listed under `unmounted` in `tools/release/parity.yaml` with a reason
- [ ] New route: the contract route is answered by core (`route_diff.py`); new migration: the module's schema role can run it (no extension outside the bootstrap list, no other module's schema)
- [ ] New setting or secret: in the service chart, then `python3 tools/release/chart_env_sync.py --write` (secret names also in `secrets.sh`)
- [ ] New Kafka consumer keeps the standalone group `<module>-service[-suffix]`; new `service=` label values exist in the monolith
- [ ] New workflow classified in `tools/release/parity.yaml` (and triggers on release-1 if it should run there)
- [ ] `python3 tools/release/parity.py` is clean on a release-1 worktree (or N/A: docs, web, mobile only)

## Rollout

<!-- Feature flag? Canary notes? Anything to watch in Grafana after the uat/prod promotion? -->
