# Identity admin console (S291)

Spring Authorization Server has no admin console. This one is ours: staff with the **`identity:admin`** permission (Super
Admin by default, granted by identity migration `V31`) manage the identity service from **Staff console › Settings ›
Identity & access** (also at `/identity`) without editing configuration or the database. The section is absent for
everyone else, and identity enforces the permission again on every call.

Code: `java/services/identity` (`OAuthClientAdminService`, `SessionAdminService`, `SigningKeyAdminService`,
`TokenInspectorService`, `AuthSettingsService`; adapters `JdbcOAuthClientAdminStore`, `GovernedRegisteredClientRepository`,
`RotatingSecretEncoder`, `JdbcSessionAdminStore`, `StoredJwkSource`, `NimbusJwtInspector`; web `IdentityAdminController`),
Flyway `V31__identity_admin_console.sql`, contracts `contracts/openapi/identity.yaml` (`/v1/admin/identity/**`) and
`contracts/openapi/gateway-bff-staff.yaml` (resources `oauth-clients`, `identity-sessions`, `signing-keys`,
`auth-settings`; operations `inspectIdentityToken`, `getIdentityDocuments`), gateway-bff
`internal/app/staff_resources_identity.go` and `staff_identity.go`, staff console `components/identity`.

## Rules every action follows

- **A reason, always.** Every change (register, edit, disable/enable, rotate a secret, revoke, rotate a signing key,
  change a setting) needs a reason of 10 to 500 characters. Without one identity answers 422 on `reason`.
- **The dangerous ones ask for your password again** ([Step-up](#step-up-the-dangerous-actions) below).
- **Audited.** Each change appends to `access_change_log` (append-only; `actor_id`, `action`, `subject`, `detail`). `detail`
  holds `reason=…` and, for edits, `before=…; after=…`. Actions: `oauth-client.created|updated|disabled|enabled|secret-rotated`,
  `session.revoked`, `sessions.revoked-user|revoked-client`, `signing-key.rotated`, `auth-settings.updated`,
  `token.inspected` (subject and key id only), `step-up.verified` (who confirmed their password, never the password or the proof). Read it in People › a user, or `GET /v1/admin/access-log`.
- **Secrets are shown once.** A new or rotated client secret is in the response of that one call (the sheet's `reveal`).
  Identity stores only a hash, never logs it, and cannot return it again; the BFF and the console keep it nowhere (it is
  kept out of the query cache and gone when the dialog closes). Tokens pasted into the inspector are handled the same way.
- **The platform cannot lock itself out.** The clients listed in `at.identity.first-party.client-ids` (default
  `gateway-bff`) cannot be disabled.

## OAuth clients

List: type, grant types, scopes, redirect and post-logout URIs, PKCE / consent / public-refresh flags, token lifetimes,
enabled, last secret rotation. Register (public or confidential), edit everything but the id and type, disable and enable.

- **Disable** hides the client from the authorization server (`GovernedRegisteredClientRepository`): its token endpoint
  calls fail with `invalid_client` and its refresh tokens stop working at once. Access tokens already issued live until
  they expire (minutes). Enable restores it.
- **Rotate a secret** (confidential clients): a new secret is generated and shown once. The previous secret keeps working
  for **24 hours** (`ClientSecrets.ROTATION_GRACE`) so deployed callers can switch over; during the grace period the client
  reads as `{rotating}<until>|<new hash>|<old hash>` and `RotatingSecretEncoder` accepts either. After a rotation, copy the
  new value into the calling service's secret (`<SERVICE>_CLIENT_SECRET`, see
  [secrets-and-configuration.md](secrets-and-configuration.md)) before the grace period ends.
- **Start-up provisioning only creates missing clients.** `at.identity.clients` (the environment's configuration) seeds a
  client when its id does not exist; it never overwrites an existing one, so an edit or a rotation made here survives a
  deployment. To change an existing client, change it here (the YAML is now only the first-run default).
- Token lifetimes are per client; the Settings ceilings (below) cap them.

## Step-up: the dangerous actions

Rotating the signing key, signing a user out everywhere, revoking all of a client's sign-ins, disabling a client and changing the
authentication settings can lock people out or change how every token is trusted. They need **proof that it is still you**, on
top of the staff sign-in (which already passed a second factor) and the reason:

- the console form for each of them ends with **Your password**: type it (a blank field is accepted only if you signed in less than 5
  minutes ago, see below). A wrong password is an error on that field and nothing happens;
- identity checks it (`POST /v1/admin/identity/step-up`, BFF `stepUpPassword`) and issues a **proof** valid for 5 minutes, bound to you
  (only its SHA-256 is stored, table `step_up_proof`); the dangerous call carries it as `X-Step-Up`. Through the API you can reuse one proof
  for several actions within its 5 minutes;
- **a fresh sign-in counts too**: the access token carries `auth_time` (when you signed in, kept across refreshes) and an action within
  `at.identity.step-up.fresh-sign-in` (`IDENTITY_STEP_UP_FRESH_SIGN_IN`, default `PT5M`) of it needs no password. Without either, identity answers
  403 `identity.step-up-required`;
- wrong passwords are counted by the same lockout as sign-in (per account, never per IP: the BFF calls for everyone), so guessing the password
  with a stolen token locks the account; an account with no password (passkey or social sign-in only) signs in again instead.

Ordinary changes (register or edit a client, enable it, rotate a client secret, revoke one sign-in, the inspector) need only the reason. Rotating a client
secret is not in the list because the old secret keeps working for 24 hours.

## Sessions & tokens

Every active sign-in is an OAuth authorization (one per sign-in per app). Search by user name or email, client id or name,
or filter by user or client; the table shows the person, app, IP address, last used and whether a refresh token exists.

| Action | Effect |
|---|---|
| Revoke this sign-in | Deletes the authorization; its refresh token fails at the next refresh (`invalid_grant`) |
| Sign the user out everywhere | Deletes all of the person's authorizations and ends their single sign-on browser sessions |
| Revoke all of a client's sign-ins (on the client's sheet) | Deletes every authorization of the client |

An **access token already issued stays valid until it expires** (resource servers verify the JWT offline; lifetimes are
minutes). The IP address is read from the web details saved with the sign-in and is empty for sign-ins made without a
browser request (service accounts). The device is the app (client); the user agent is not recorded.

## Signing keys and rotation

`/oauth2/jwks` publishes the keys of the `signing_key` table: **ACTIVE** (signs; its private half is sealed with the
data-protection cipher, like TOTP secrets, bound to the row) and **RETIRING** (public half only, still published so the
tokens it signed keep verifying). A key becomes **RETIRED** (unpublished) once `retire_after` has passed. At first start the
configured `SIGNING_KEY_PEM` key is stored as the active key (`SigningKeyAdminService.seedIfEmpty`); from then on the table
is the source of truth and `SIGNING_KEY_PEM` is only the seed. Replicas re-read the table every 5 seconds.

### Runbook: rotate the signing key

Routine rotation (quarterly, or when someone with access to the key leaves). No deployment, no downtime.

1. Staff console › Settings › Identity & access › **Signing keys** › *New* (Rotate the signing key). Give the reason (for
   example "Quarterly rotation, ticket SEC-123") and confirm.
2. A new key is **Active**; the previous one is **Retiring**, with **Retires after** = now + the longest access token
   lifetime (the longest client lifetime, capped by the Settings ceiling) + 2 minutes of skew.
3. The replica that took the request signs with the new key as soon as the rotation commits; the others re-read the table within about 5 seconds. Verify: paste a fresh access token in the **Token
   inspector** (it names the new `kid` and verifies), and check the JWKS under *Published documents* lists both keys.
4. Resource servers cache the JWKS and refetch when they meet an unknown `kid`; nothing to do. Tokens signed by the old key
   verify until it retires; after **Retires after** the old key leaves the JWKS (the table moves it to *Retired* the next
   time the list is opened or the key is rotated) and its private half is already gone.
5. Confirm in the access log: `signing-key.rotated` with your reason.

**A rotation cannot be undone from the console**: the old key's private half is erased the moment it stops being active (that is the point
of rotating), so it can never sign again; it only keeps verifying the tokens it already signed until it retires. If the new key
misbehaves, rotate again. The break-glass way back to the configured key is in
[identity-admin-dev-test.md](identity-admin-dev-test.md#9-rolling-back-a-bad-key-rotation).

The private half of the active key is sealed with the data-protection key (`IDENTITY_DATA_KEY`). The data-key rotation
(`POST /v1/admin/data-protection/rotate`, Users & roles) reseals it with the TOTP seeds and its status counts it, so a previous data key can be
dropped from `IDENTITY_DATA_KEYS_PREVIOUS` only when nothing is left under it.

If rotation fails midway (a unique-index error), nothing is half-applied: the old key was made *retiring* in the same
transaction, which rolls back. Retry.

Suspected key compromise: rotate first, then **sign everyone out** where it matters (a user's *Sign out everywhere*, or
*Revoke all sign-ins* on the platform client). The compromised key stays published as *Retiring* for the overlap, so tokens
forged with it would still verify for that long. To cut that off immediately, a database owner may end the overlap
(`update signing_key set retire_after = now() where status = 'RETIRING'`), accepting that tokens legitimately signed by the
old key (minutes old) fail at once and resource servers refetch the JWKS (their cache refreshes within minutes). Record it
in the incident log.

## Token inspector and published documents

Paste a JWT to see the header, claims, scopes and expiry, and whether the signature verifies against a published key, the
issuer is ours and the token has not expired. Only RS256 is accepted. The token is decoded in memory at identity: not
stored, not logged, not echoed in the answer; the audit entry records the subject and key id only. A bad token is a normal
answer that says what is wrong. The same tab shows the OIDC discovery document and the JWKS (public keys only).

## Settings

Stored in `auth_settings` (one row; a null column means "use the configured default"), validated, audited, read at runtime
(cached 5 seconds). Defaults come from `at.identity.auth.*` (environment `AT_IDENTITY_AUTH_…`).

| Setting | Default | Range | Applies to |
|---|---|---|---|
| Shortest password | 12 | 8 to 64 | sign-up, password change and reset (`DefaultPasswordPolicy`) |
| Second factor for customers / seller members / provider members | off | on/off | sign-in, for people who have a factor or a phone on file (staff always need one) |
| Sign-in session idle timeout | 30 min | 5 min to 24 h | the hosted sign-in (single sign-on) session, at its next request |
| Sign-in session absolute timeout | 24 h | 1 h to 30 days | the same session, however active |
| Access / refresh token lifetime ceiling | none | 1 to 60 min / 5 min to 90 days | caps every client's own lifetime for tokens issued from now on |

The idle timeout cannot exceed the absolute timeout. Saving a value equal to the default stores "use the default".

## Verification

- Unit: `OAuthClientAdminServiceImplTest`, `SigningKeyAdminServiceImplTest`, `AuthSettingsServiceImplTest`,
  `SessionAndInspectorServicesTest`, `SigningKeysAndInspectorTest`, `RotatingSecretEncoderTest`, `AuthSettingsRulesTest`;
  `ArchitectureTest` for layering.
- Integration (Testcontainers): `IdentityAdminIT`: permission, clients (create, one-time secret, grace period, disable,
  edit, provisioning never overwrites), sessions (revoked refresh tokens stop working), key rotation with the old key
  published until it retires, the inspector never storing a token, settings read at runtime.
- gateway-bff: `TestStaffIdentityAdminConformance` exercises every new operation against `gateway-bff-staff.yaml`.
- Console: `test/identity-admin.test.tsx` and `packages/api-client/test/staff-identity.test.ts` (Vitest, mock BFF); Playwright mock spec
  `staff-console.identity-admin.spec.ts` (including the axe checks).
- Step-up: `StepUpServiceImplTest`, `IdentityTokenCustomizerTest` (`auth_time`), `IdentityAdminIT` (proof required, wrong password,
  expiry, audit), `StepUpFreshSignInIT` (a fresh sign-in needs no proof).
- Developers testing on the dev / OVHcloud test environment follow [identity-admin-dev-test.md](identity-admin-dev-test.md).

## Not covered yet

- A **second factor** (code or passkey) at step-up: it is the password, or a sign-in from the last five minutes (which did pass the second factor).
- Revoking an **access token** before it expires (a deny-list) is not offered; lifetimes are minutes.
