# Web storefront & shared packages

pnpm workspace (root `package.json`, `pnpm-workspace.yaml`): `web/apps/*`, `web/packages/*`, `mobile/apps/*`, `docs/portal`.

| Package | Purpose |
|---|---|
| `web/packages/tokens` | Single token source: `src/tokens.css` (`--at-*`, ported from `design/afrotransact-tokens.css`, plus global rules: white canvas, Figtree, hidden scrollbars, no horizontal scroll). `pnpm --filter @afrotransact/tokens generate` writes the typed object `src/generated/tokens.ts` (React Native). |
| `web/packages/ui` | Shared accessible React components + Storybook (`pnpm --filter @afrotransact/ui storybook`). |
| `web/packages/api-client` | Typed gateway-BFF client, Zod schemas, TanStack Query key factories / `queryOptions`, OpenAPI-generated types (`src/generated`, never edited), MSW mock BFF over the design's sample catalogue. |
| `web/apps/storefront` | Next.js 16 App Router storefront (port 3000). |

## Run

```bash
pnpm install
pnpm --filter storefront dev:mocks     # http://localhost:3000 against the in-process mock BFF
BFF_ORIGIN=http://localhost:8200 pnpm --filter storefront dev   # against the real gateway-bff
```

`NEXT_PUBLIC_API_MOCKS=1` (or `API_MOCKS=1`) is read **at runtime**, so one build serves mock and real modes.
The browser only ever calls same-origin `/api/bff/v1/*`; `src/app/api/bff/[...path]/route.ts` proxies to `BFF_ORIGIN`
(forwarding the `at_session` cookie) or runs the MSW handlers in-process. Server components use `serverApi()` with the
same client, cookies forwarded.

## Quality gates

```bash
pnpm -r lint        # ESLint (strict TS, no any, sorted imports, no namespace imports of app code, jsx-a11y, at/no-raw-hex) + Stylelint (color-no-hex)
pnpm -r typecheck
pnpm -r test        # Vitest
pnpm --filter storefront build
pnpm --filter @afrotransact/ui build-storybook
pnpm --filter storefront e2e        # Playwright smoke (builds, starts with mocks on :3005)
pnpm --filter storefront cf:build   # OpenNext → .open-next/worker.js (Cloudflare Workers)
pnpm --filter @afrotransact/api-client check:generated   # fails when generated clients are stale
```

Raw hex colours are rejected in `web/apps/**/src` and `web/packages/ui` (tokens package excepted).

## Contracts

`contracts/openapi/gateway-bff.yaml` is the screen-shaped BFF contract the storefront is built against. After changing a
contract run `pnpm generate:clients`; `test/contract.test.ts` asserts the Zod schemas stay assignable to the generated types.

## Deploy

Cloudflare Workers via OpenNext (`open-next.config.ts`, `wrangler.jsonc` with `preview`/`uat`/`prod` envs:
`pnpm --filter storefront cf:deploy -- --env uat`). The same `next build` deploys to Vercel unchanged.
