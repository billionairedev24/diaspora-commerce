---
title: Generated TypeScript clients
sidebar_label: TypeScript clients
sidebar_position: 2
description: How web and mobile clients are generated from the OpenAPI contracts.
---

Web and mobile code never hand-writes API types. `@afrotransact/api-client` (`web/packages/api-client`) holds the
typed BFF client, Zod schemas, TanStack Query key factories and MSW mocks; the **types** under
`src/generated/` are generated from the contracts with [openapi-typescript](https://openapi-ts.dev) 7.

## How generation works

`web/packages/api-client/scripts/generate.mjs` runs `openapiTS()` on each contract and writes
`src/generated/<contract>.ts` with a banner:

```ts
// GENERATED from contracts/openapi/gateway-bff.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/api-client generate
/* eslint-disable */
```

The files export `paths`, `components` and `operations` interfaces. The hand-written client in `src/client.ts`
and the Zod schemas in `src/schemas/` are typed against them, and `test/contract.test.ts` checks the schemas
against the generated types.

```bash
pnpm --filter @afrotransact/api-client generate          # gateway-bff, gateway-bff-seller, search, catalog
pnpm --filter @afrotransact/api-client check:generated   # CI: fails when the committed output is stale
```

## Every BFF contract: `tools/contracts/generate_clients.sh`

Each BFF contract (`contracts/openapi/gateway-bff*.yaml`, e.g. `gateway-bff.yaml` for the storefront and app and
`gateway-bff-seller.yaml` for Seller Center) gets a client. The root script discovers them by glob and uses the
**same generator** (the `openapi-typescript` installed in `web/packages/api-client`, same banner), so its output is
byte-identical to `generate.mjs` for the files both produce.

```bash
tools/contracts/generate_clients.sh            # write web/packages/api-client/src/generated/gateway-bff*.ts
tools/contracts/generate_clients.sh --check    # CI: exit 1 when any generated client is missing or stale
OUT_DIR=/tmp/clients tools/contracts/generate_clients.sh   # write somewhere else (dry run)
```

Rules:

- Generated files are **never edited by hand**; change the contract and regenerate.
- CI runs `--check` whenever `contracts/**` changes, so a contract change without regenerated clients fails.
- Root shortcut: `pnpm contracts:clients` (and `pnpm contracts:clients:check`).
