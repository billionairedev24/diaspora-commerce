# AfroTransact developer portal (`docs/portal`)

Docusaurus 3 (TypeScript) site for developers: getting started, architecture, engineering standards, one page per
service, the API reference (Redoc + Scalar for every OpenAPI contract), the AsyncAPI event catalog, the MCP tools,
runbooks and backlog status. Package `@afrotransact/docs-portal`, part of the root pnpm workspace. Local port **3100**
(`infra/local/PORTS.md`).

## Run and build

```bash
pnpm install                                     # at the repository root
pnpm --filter @afrotransact/docs-portal start    # dev server on http://localhost:3100 (or: pnpm docs:dev)
pnpm --filter @afrotransact/docs-portal build    # static site in docs/portal/build (or: pnpm docs:build)
pnpm --filter @afrotransact/docs-portal serve    # serve the build on http://localhost:3100
pnpm --filter @afrotransact/docs-portal typecheck
```

`start`, `build` and `typecheck` first run `scripts/sync-contracts.mjs` (the `pre*` scripts). The build fails on any
broken link (`onBrokenLinks: 'throw'`, `onBrokenMarkdownLinks: 'throw'`).

## What is generated (never edit, gitignored)

`scripts/sync-contracts.mjs` reads the monorepo at build time:

| Source | Output |
|---|---|
| `contracts/**` | `static/contracts/**` (raw YAML, downloadable from every API page) |
| `contracts/openapi/*.yaml` (globbed, so new contracts appear automatically) | `docs/api/<name>.mdx`: Redoc + Scalar views, download link |
| `contracts/asyncapi/events.yaml` (+ `fragments/` for producer/consumer ownership) | `src/generated/events.json`, `docs/events/topics/<topic>.mdx` |
| `{java,go}/services/*/README.md` + `infra/local/PORTS.md` | `docs/services/<name>.md` (services without a README get a stub from PORTS.md) |
| `CLAUDE.md`, `docs/engineering/*.md` | `docs/engineering/guides/*.md` |
| `docs/runbooks/*.md`, `docs/adr/**` (when present) | `docs/runbooks/*.md`, `docs/architecture/adr/**` |
| `infra/local/PORTS.md`, root `Makefile` (`## ` comments) | `docs/getting-started/{ports,make-targets}.md` |
| `docs/backlog/backlog.csv` | `src/generated/backlog.json` → Backlog status page |
| mcp-server `@McpTool` methods + `ToolCatalog` (or `$MCP_TOOLS_URL/v1/tools`) | `src/generated/mcp-tools.json` → MCP tools page |
| `@fontsource/figtree` (via `@afrotransact/tokens`), `web/packages/ui/assets/logo-mark.svg` | `static/fonts/`, `static/img/logo-mark.svg` |

Relative links inside copied Markdown are rewritten: links to another copied file go to its portal page, links to
contracts go to the API/event pages, everything else goes to GitHub (`REPO_URL`, `REPO_BRANCH` override the
defaults). Hand-written pages are the `index.mdx` files, `docs/engineering/{typescript-clients,contract-linting}.md`,
`docs/mcp/*`, `docs/backlog.mdx` and `src/components/**`.

## API reference renderers

`src/components/ApiReference` renders each contract in an isolated iframe with the same pinned versions as
`java/libs/platform-web` (`ApiDocsProperties`): **Redoc 2.5.4** (`redoc.standalone.js`) and **Scalar 1.73.1**
(`@scalar/api-reference`), both from `cdn.jsdelivr.net`. If the CDN cannot be reached, the frame shows a download
link to the contract instead. The brand palette is passed from the `--at-*` tokens.

## Styling

`src/css/custom.css` imports `@afrotransact/tokens` (tokens + self-hosted Figtree) and maps Infima variables to the
tokens: white canvas, black header with the gold accent, green links, no visible scrollbars. Components use token
variables only, no raw colours.

## Deploy: Cloudflare Pages

| Setting | Value |
|---|---|
| Project | `afrotransact-docs` (`vars.DOCS_PAGES_PROJECT` in `.github/workflows/docs.yml`) |
| Build command | `pnpm install --frozen-lockfile && pnpm --filter @afrotransact/docs-portal build` (run at the repository root) |
| Output directory | `docs/portal/build` |
| Node | 22 (`.nvmrc`) |
| Env (optional) | `SITE_URL` (default `https://developers.afrotransact.com`), `BASE_URL` (default `/`), `MCP_TOOLS_URL` |

`.github/workflows/docs.yml` builds on PRs (preview deployment) and on `main` (production) and deploys with
`wrangler pages deploy docs/portal/build --project-name=afrotransact-docs`. `infra/cloudflare` does not define a
Pages project or a `developers.` DNS record yet; create the project once (`wrangler pages project create
afrotransact-docs`) or add it to `infra/cloudflare` with the custom domain `developers.afrotransact.com`.

Note: the portal is a pnpm **workspace** package, so there is no `docs/portal/pnpm-lock.yaml`. The Build step of
`docs.yml` should install at the root (`pnpm install --frozen-lockfile && pnpm --filter @afrotransact/docs-portal
build`) rather than its current `npm ci` fallback in `docs/portal`.

## Related tools

- `tools/contracts/lint_contracts.sh`: offline lint of every OpenAPI contract (Redocly engine, root `redocly.yaml`)
  and the AsyncAPI document (`@asyncapi/parser`), using this package's dependencies.
- `tools/contracts/generate_clients.sh [--check]`: regenerates the TypeScript clients for every `gateway-bff*.yaml`
  with the same generator as `web/packages/api-client`.
