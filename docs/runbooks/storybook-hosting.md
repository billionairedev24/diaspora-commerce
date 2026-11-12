# Runbook: Storybook hosting

Storybook for `@afrotransact/ui` (design tokens, components, and the presentational components of the web apps) is a
**static site**: no server, no backend calls, no secrets. The stories render from fixtures only (no MSW, no `fetch`, no
environment variables in `.storybook/`), so the build output can be hosted anywhere that serves files.

## Build

```bash
pnpm install --frozen-lockfile
pnpm --filter @afrotransact/ui build-storybook     # -> web/packages/ui/storybook-static (about 10 MB, ignored by git)
```

`storybook-static/index.json` lists the stories; CI fails the build if it is empty
(`.github/workflows/storybook.yml`).

## Hosting options

### 1. Cloudflare Pages (what CI does today)

`storybook.yml` deploys `storybook-static` to the Pages project `afrotransact-storybook` with `wrangler pages deploy`:
a preview URL per PR branch (commented on the PR) and production from `main`. The project is created by
`infra/cloudflare/pages.tf` (first-environment runbook, section 8). Needs `CLOUDFLARE_API_TOKEN` and
`CLOUDFLARE_ACCOUNT_ID` as repository secrets; without them the deploy step is skipped with a notice.

### 2. Cloudflare Workers static assets (config in the repo)

Web apps run on Workers in every environment, so Storybook can follow: `web/packages/ui/wrangler.jsonc` declares a Worker named
`afrotransact-storybook` that is only an assets directory (`./storybook-static`). No Worker code, bindings, vars or secrets.

```bash
# credentials come from the environment, never from the file
export CLOUDFLARE_API_TOKEN=... CLOUDFLARE_ACCOUNT_ID=...
pnpm --filter @afrotransact/ui deploy-storybook      # storybook build && wrangler deploy
pnpm --filter @afrotransact/ui exec wrangler deploy --dry-run   # validates the config and the asset directory, uploads nothing
```

CI still uses Pages (option 1). Moving CI to Workers means replacing the `wrangler pages deploy` step with
`wrangler deploy` (production) and `wrangler versions upload` (PR previews), and deleting the Pages project once the
Worker serves the Storybook hostname. Do not run both under the same hostname. This is an owner decision.

### 3. Vercel (or any static host)

The owner may host the static output on Vercel instead: create a Vercel project with **Root Directory** `web/packages/ui`,
**Install Command** `pnpm install --frozen-lockfile` (run from the repository root, enable "Include source files outside of the
Root Directory"), **Build Command** `pnpm --filter @afrotransact/ui build-storybook` and **Output Directory** `storybook-static`.
Or build in CI and upload the `storybook-static` artifact with `vercel deploy --prebuilt`/`vercel deploy storybook-static`.
No environment variables are needed. Netlify, GitHub Pages and an S3 bucket work the same way (upload the directory).

## Things to decide or keep in mind

- **Who may see it.** The site is a design reference, not a product page: put it behind Cloudflare Access (or Vercel
  deployment protection) if it should not be public, and send `X-Robots-Tag: noindex` (a `_headers` file in the output) if it is.
- **Never add secrets** to the Storybook build (`STORYBOOK_*` variables are inlined into the public bundle).
- **Visual baselines** are rendered in the pinned Playwright image (`storybook.yml`); hosting does not affect them.
