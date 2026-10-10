---
title: Contract linting
sidebar_label: Contract linting
sidebar_position: 3
description: Validate every OpenAPI and AsyncAPI contract offline.
---

`tools/contracts/lint_contracts.sh` validates every contract with tools installed in the workspace (no network):

- **OpenAPI** — every `contracts/openapi/*.yaml` with the Redocly lint engine (`@redocly/openapi-core`, the
  library behind `redocly lint`) using the root `redocly.yaml` (the `recommended` ruleset plus
  `operation-operationId` and `operation-tag-defined`, matching the CI Spectral rules).
- **AsyncAPI** — `contracts/asyncapi/events.yaml` with the official `@asyncapi/parser` (schema validation and the
  AsyncAPI ruleset), plus a merge-freshness check against `contracts/asyncapi/fragments/` when Python and PyYAML
  are available.

```bash
pnpm install
tools/contracts/lint_contracts.sh              # all contracts, summary per file; exit 1 on errors
tools/contracts/lint_contracts.sh catalog      # only contracts whose file name contains "catalog"
tools/contracts/lint_contracts.sh --warnings   # also print every warning
```

CI (`.github/workflows/contracts.yml`) additionally runs Spectral and an `oasdiff` breaking-change gate against
the base branch.
