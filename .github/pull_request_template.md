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

## Rollout

<!-- Feature flag? Canary notes? Anything to watch in Grafana after the uat/prod promotion? -->
