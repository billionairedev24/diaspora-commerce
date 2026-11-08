# CSP violations spike

**Severity:** `ticket` (page only if customers cannot sign in or pay) · **Source:** `csp.violation` log lines from `/api/csp-report` of storefront, seller-center, staff-console (Cloudflare Workers Logs) · **Policy:** [security headers](../engineering/security-headers.md)

## What it means
Browsers report more blocked (enforce) or would-be-blocked (report-only) resources than usual. Usual causes: a release added a script, image host or
iframe the policy does not allow; a config var changed (`MEDIA_PUBLIC_URL`, telemetry endpoints); Stripe changed a host; or a
browser extension / injected script is noisy (not our problem). Identity is enforced and has no report endpoint: a spike there shows as sign-in
complaints and `Refused to ... Content Security Policy` in the browser console.

## Impact
`disposition: report` (report-only): none for users. `disposition: enforce`: the blocked thing is missing or broken. Look for payment forms, images, sign-in.

## Diagnose
1. Workers & Pages > `<app>-<env>` > Logs, search `csp.violation`; or `pnpm exec wrangler tail --env <env> --search csp.violation`.
2. Group by `directive` and `blocked`. One origin dominating (`img-src https://media...`, `script-src-elem https://js.stripe.com`) is a policy or config gap; many different
   `inline`/`eval`/`blob` with a foreign `source` or a `chrome-extension://` origin is extension noise.
3. Correlate the start time with a deploy (`wrangler deployments list --env <env>`) and with changes to `wrangler.jsonc` vars (`MEDIA_PUBLIC_URL`, `NEXT_PUBLIC_*` at build time).
4. `source` + `line` point at the offending chunk; `page` is the path (no query) where it happened.

## Mitigate
- Customers are affected (enforce) -> roll back to Report-Only at once: set `CSP_MODE=report-only` (or `off`) for that app and redeploy
  (`wrangler deploy --env <env> --var CSP_MODE:report-only`). Headers change on the next request; no code change.
- Legitimate new origin -> add it to the exact directive in `web/packages/ui/src/security/csp.ts` with a test and a row in the docs table, ship, then re-enable.
- Wrong or missing `MEDIA_PUBLIC_URL` -> fix the Worker var for that environment (images are the most common spike).
- Real attack signal (unknown external `script-src` origins on our pages) -> treat as a security incident: check recent dependency/content changes and the CMS placements, rotate what is exposed.

## After the incident
- Update the allowance table in `docs/engineering/security-headers.md` if the policy changed, and the steps here if they were wrong.
- If the alert was extension noise, filter it in the log query rather than loosening the policy.
