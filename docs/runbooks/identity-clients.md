# Identity: OAuth client provisioning and drift (audit F15)

`StartupProvisioning` (identity) creates the OAuth clients listed in `application-<env>.yml` (`at.identity.clients`) when they are **missing**. A client that
already exists belongs to the staff console (S291), so a changed scope list or redirect URI in YAML does not reach a database that already has the client.
Start-up now **reconciles** scopes, redirect URIs and post-logout redirect URIs of existing clients, in a mode you choose:

| `IDENTITY_CLIENT_RECONCILE` (`at.identity.provisioning.reconcile`) | Effect |
|---|---|
| `report` (**default**) | Logs every difference as an audit line, changes nothing |
| `additive` | Adds the scopes and redirect URIs the YAML lists and the stored client lacks; never removes |
| `sync` | Makes stored scopes and redirect URIs equal to the YAML, removing what the YAML no longer lists (overwrites staff-console edits of those three lists) |

Only those three lists are touched. Secret, grant types, token lifetimes, consent, the client name and everything else stay as they are. The run is idempotent (a second
start finds nothing). **Secrets are never touched** unless `IDENTITY_CLIENT_ROTATE_SECRETS=true` is set: then a confidential client whose configured secret no longer matches the stored hash
is re-hashed from the configured one (the normal way to rotate stays the admin API; use this flag for the one start-up that applies a rotated Secret, then unset it).

Audit lines (log level WARN, field `audit`): `identity.client.drift` (a difference exists, in any mode) and `identity.client.reconciled` (the client was changed: scopes and redirect URIs
added or removed, `secretRotated`). They go to Grafana Cloud logs with the rest; search `audit: identity.client`.

## Applying a YAML change to production

1. Merge the YAML change, deploy identity (mode `report`): the log shows `identity.client.drift client=<id> ... scopes +[...] -[...]`.
2. Decide: only additions (`additive`) or also removals (`sync`). Set `IDENTITY_CLIENT_RECONCILE=additive` (or `sync`) for **one** deploy: `kubectl -n afrotransact-prod set env deploy/identity IDENTITY_CLIENT_RECONCILE=additive`
   (or the identity values `extraEnv`), wait for the rollout, check `identity.client.reconciled`, then set it back to `report`.
3. Check the client in the staff console (Developer > Identity and access > Clients) and sign in with it once.

## Public client scopes

On `uat` and `prod` identity **refuses to start** when a **public** client (no secret) is configured with a scope in
`at.identity.provisioning.public-client-forbidden-scopes` (default `mcp:staff`). The public `mcp-client` therefore carries `openid mcp:shopper mcp:seller` there; `local` and `dev`
keep `mcp:staff` for developers. mcp-server is private (ClusterIP only, `docs/runbooks/ai-spend-and-mcp-access.md`), and its staff tools additionally need a staff principal, so
nothing in the platform needed `mcp:staff` on the public client. **Existing production database**: the stored `mcp-client` may still carry `mcp:staff`. Remove it once with
`IDENTITY_CLIENT_RECONCILE=sync` for one deploy (step 2 above), or in the staff console. If staff MCP tools are wanted from an external agent later, add a separate
**confidential** client for that purpose.

## Local defaults

The base `application.yml` files no longer carry local passwords or secrets (`DB_PASSWORD` equal to the service name, `local-catalog-service-secret`, `local-offers-service-secret`,
`local-orders-service-secret`, `local-only-handover-code-key-change-me`, `local-only-pay-link-key-change-me`): they moved to `application-local.yml`. A deployed profile that
lacks `DB_PASSWORD`, `CATALOG_CLIENT_SECRET` (blank allowed: the client is skipped), `OFFERS_CLIENT_SECRET`, `ORDERS_CLIENT_SECRET`, `HANDOVER_CODE_KEY` or `PAY_LINK_KEY` now fails to start
with an unresolved placeholder instead of running with a published default. `infra/ovh-vps/secrets.sh` supplies all of them. Still defaulted in the base file: identity's
`SIGNUP_FINGERPRINT_KEY` (a keyed hash for abuse checks, not an authentication secret): add it to the identity Secret and remove the default as a follow-up.
