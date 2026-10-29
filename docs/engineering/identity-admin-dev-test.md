# Identity admin console: developer test plan

A step-by-step plan to test **Settings › Identity & access** (story S291, guide: [identity-admin.md](identity-admin.md)) on a deployed
environment: the shared dev cluster or the single-server OVHcloud test environment ([ovh-vps runbook](../runbooks/ovh-vps.md)). It
takes about 45 minutes. Do the steps in order: later ones use what earlier ones create. Everything you create is prefixed
`dev-test-` so you can tell it from the platform's own clients.

Replace `<base>` with the environment's domain: `dev.afrotransact.com` for the shared dev cluster, your `OVH_DOMAIN` for the OVHcloud
environment. The addresses are `https://admin.<base>` (staff console), `https://auth.<base>` (identity, the OIDC issuer) and
`https://api.<base>` (gateway-bff). On the OVHcloud server the staff console is a Cloudflare Worker, not a pod: it must be deployed
(or run locally with `API_BASE_URL` pointing at `https://api.<base>`) and signed in through `https://auth.<base>`
([ovh-vps runbook, step 7](../runbooks/ovh-vps.md)).

## 0. Before you start

### What must be deployed

| Check | How | Expect |
|---|---|---|
| identity, gateway-bff and the staff console are built from the **same commit** (this branch or later) | Actions runs of `java-identity`, `go-gateway-bff` and the staff console deploy; the `image-tag` of the `services` run | the commit that contains migrations `V31` and `V32` |
| the migrations ran | `select version, success from flyway_schema_history order by installed_rank desc limit 3;` in identity's database | `32`, `31` (and before them `30`) all `t` |
| identity's token signing key is an **RSA** key | `curl -s https://auth.<base>/oauth2/jwks \| jq '.keys[] \| {kid, kty, alg}'` | one key: `kty` `RSA`, `alg` `RS256`, `kid` = `SIGNING_KEY_ID` (for example `identity-dev-1`) |
| `IDENTITY_DATA_KEY` is set | identity starts (without it the data-protection cipher refuses to) | pod `Running`, `/.well-known/openid-configuration` answers |

**OVHcloud environment, first time only.** Earlier versions of `infra/ovh-vps/secrets.sh` generated an *EC* signing key, which identity
cannot load: it stopped at start-up with `SIGNING_KEY_PEM must be a PKCS#8 RSA private key`. The script now generates RSA 3072 and replaces
an EC key it finds in `/var/lib/afrotransact/identity-signing-key.pem` (the old file is kept as `identity-signing-key.pem.not-rsa.<time>`).
Run the **`services`** action of the `ovh-vps` workflow once, then restart identity if it was crash-looping.

### Who can sign in

| Account | Where it comes from | Can do |
|---|---|---|
| The **bootstrap super admin** | OVHcloud: `ADMIN_EMAIL`, the generated password that `make ovh-gh-setup` showed once, `ADMIN_PHONE`. Shared dev: `BOOTSTRAP_ADMIN_*` in the `afrotransact/dev/identity` secret | Super Admin: everything, including `identity:admin` |
| Another **Super Admin** | Users & roles › People › the user, assign the Super Admin role | the same |
| A staff member **without** `identity:admin` (for step 1) | Users & roles › New staff user with the **Developer** role, or `staff.developer@afrotransact.test` where `IDENTITY_TEST_USERS=true` (password `IDENTITY_TEST_USERS_PASSWORD`) | everything the Developer role has, but no Identity & access |
| A customer (for steps 6 and 9) | sign up on the storefront, or `customer.houston@afrotransact.test` where test users are on | storefront sign-in only |

Staff **always** need a second factor. The code is a text message to the account's phone. On the OVHcloud environment without Twilio keys the
text is only logged: read it in `sudo k3s kubectl -n afrotransact-dev logs deploy/notifications --tail=50`. Tick *Trust this browser for
30 days* to skip it afterwards. Identity allows only a few codes per hour per account: do not sign the admin in and out repeatedly.

## 1. Who sees it (permission and navigation)

| Do | Expect |
|---|---|
| Sign in to `https://admin.<base>` as the **Developer** | the sidebar has Settings; Settings › Platform lists *Sign-in & integrations* but **not** *Identity & access* |
| Open `https://admin.<base>/identity` directly | "You don't have access to this page" |
| Sign out, sign in as the **Super Admin** | Settings › Platform now lists **Identity & access**; it opens at `/identity` with five tabs: OAuth clients, Sessions & tokens, Signing keys, Token inspector, Settings |
| Users & roles › **Roles** › Super Admin | the permission `identity:admin` (module *identity*) is granted. No other role has it (check Developer and Product Owner) |

Rule behind it: identity grants `identity:admin` to `platform.super_admin` in migration `V31`, the BFF shows the module only with that
permission, and identity checks it again on every call (a Developer token gets 403 from `/v1/admin/identity/**`).

## 2. OAuth clients

| Do | Expect |
|---|---|
| Open the **OAuth clients** tab | a table of every registered client: `gateway-bff`, `customer-app`, `driver-app` and one per service (`orders-service` and so on), each with type, grant types and status |
| Click `gateway-bff` | the sheet shows redirect URIs, post-logout URIs, PKCE, token lifetimes, "Platform client: Yes". **Disable client** is greyed out with "The platform signs in with this client; disabling it would lock every staff member out" |
| **New**. Client id `dev-test-<you>`, type *Confidential*, name "Dev test", grant types *Client credentials* only, scopes `users:read`. Reason "short" | the form refuses: "Give a reason of 10 to 500 characters" |
| Same form with a real reason ("Developer test of the identity admin console") and **Register** | a **Client secret** dialog shows the secret once. Copy it. **I have copied it** closes it |
| Reopen the client's sheet, reload the page, open the Network tab | the secret appears nowhere. There is no way to read it again |
| `curl -s -u dev-test-<you>:<secret> -d grant_type=client_credentials -d scope=users:read https://auth.<base>/oauth2/token` | 200 with `access_token` (a JWT), `expires_in` about 300 |
| **Rotate secret** (reason) | **New client secret** dialog with the note that the previous secret works for 24 hours. The sheet shows *Last rotated* and *Previous secret works until* |
| The same `curl` with the **old** secret, then with the **new** one | both answer 200 (the old one only for 24 hours) |
| **Edit**: access token lifetime 2 minutes (reason), save; run the `curl` again | `expires_in` is about 120. The sheet shows the edit |
| **Disable client**: reason only, **Disable** | the form refuses with an error on *Your password* (see step 8). Fill the reason and your password, **Disable** |
| the `curl` again | `{"error":"invalid_client"}` with HTTP 401. The sheet says Disabled and offers **Enable client** |
| **Enable client** (reason), `curl` again | 200 again |

**Existing clients are never changed by configuration.** Identity creates the clients in `at.identity.clients` (the environment's
`application-<env>.yml`) only when they do not exist yet. A redirect URI you add to the dev configuration later does nothing to
`gateway-bff`: edit it here (Edit on the client's sheet).

## 3. Sessions & tokens

Every row is one sign-in of one person in one app (an OAuth authorization with its refresh token).

| Do | Expect |
|---|---|
| In another browser sign in as a **customer** on the storefront | |
| **Sessions & tokens**, search for the customer's email | one row: the person, app "Gateway BFF (…)", *Last used*, *Refresh token: Yes*. The **IP address** is the one identity saw on the hosted sign-in page; it is empty for service accounts (shown as `Service account <client id>`) and for sign-ins made through identity's first-party sign-in API (no browser request) |
| Open the row, **Revoke this sign-in** (reason) | the row disappears. The customer is signed out at their next token refresh (at most the access token's lifetime, 5 minutes by default) |
| The customer signs in again; open the row; **Sign the user out everywhere** (reason **and your password**) | all the person's sign-ins and their single sign-on browser sessions end; the list for that email is empty |
| Search `dev-test-<you>` (the client credentials sign-in from step 2) | one row, *Refresh token: No*, shown as a service account. **Sign the user out everywhere** is unavailable for it |
| OAuth clients › `dev-test-<you>` › **Revoke all its sign-ins** (reason and password) | every sign-in of that client ends |

## 4. Signing keys and rotation

| Do | Expect |
|---|---|
| Open **Signing keys** | one **Active** key (`kid`, RS256, created); older keys, if any, **Retiring** or **Retired** |
| Note the `kid` of a fresh token: `curl -s -u dev-test-<you>:<secret> -d grant_type=client_credentials -d scope=users:read https://auth.<base>/oauth2/token \| jq -r .access_token \| cut -d. -f1 \| tr '_-' '/+' \| base64 -d` (add `=` padding if `base64` complains) | `{"kid":"<old kid>","alg":"RS256"}`. Keep this token for step 5 |
| **New** (Rotate the signing key): reason and your password, **Rotate** | the table now has a new **Active** key and the previous one **Retiring** with *Retires after* = now + the longest access token lifetime + 2 minutes (about 7 minutes with the defaults) |
| `curl -s https://auth.<base>/oauth2/jwks \| jq '[.keys[].kid]'` | **both** keys are published |
| Fetch a fresh token and decode its header as above | the **new** `kid`. This is immediate on the replica that handled the rotation and within about 5 seconds on every other replica |
| Sign in to the console and the storefront, open a few pages | everything keeps working: services verify with whichever published key matches the token's `kid` (they refetch the JWKS when they meet a new `kid`) |
| Wait past *Retires after* and open the Signing keys tab again | the old key moves to **Retired** and leaves the JWKS; its private half was already erased when it stopped being active |

## 5. Token inspector and published documents

| Do | Expect |
|---|---|
| Open **Token inspector**, paste the token from step 4 (signed by the old key), **Inspect token** | result region: header, claims (`sub`, `iss`, `scope`, `pt`, `perms` ...), scopes, key id, issued and expiry times. **Valid.** while the old key is still published and the token has not expired. Signature: valid, issuer: valid |
| After the old key retired, or after the token expired, inspect it again | **Not valid.** with the reason: "No published key has the id …" or "The token expired at …" |
| Change the last characters of the token and inspect | **Not valid.**: "The signature does not verify against the published key" |
| Paste an opaque string (a refresh token, `abc`) | "This is not a signed JWT". Only RS256 JWTs are accepted |
| **Clear** | the field and the result are gone. The token is not in the page, the URL or the browser storage; identity does not store or log it (the audit entry has only the subject and key id) |
| Scroll to the documents | the **OIDC discovery document** (issuer `https://auth.<base>`, endpoints) and the **JWKS** (public keys only: `kty`, `kid`, `n`, `e`; never a `d`) |

## 6. Settings

| Do | Expect |
|---|---|
| Open the **Settings** tab, open the row | what is in force and whether each value is the configured default or set here |
| **Edit**: shortest password 4 | refused before sending ("Must be at least 8") |
| Shortest password **14**, reason, your password, **Save settings** | saved; the sheet says "14 (set here; default 12)" |
| On the storefront try to sign up with a 13-character password | refused: the new minimum applies at once (identity reads the setting at runtime, cached for 5 seconds) |
| Edit again: access token lifetime ceiling **1** minute (reason, password) | after about 5 seconds new tokens have `expires_in` of at most 60, whatever the client allows |
| Set both values back to the defaults (type the default, or empty the ceiling) | "(default)" again |

Be careful with the second-factor switches on a shared environment: they apply to everyone of that portal who has a factor or a phone on file.

## 7. Audit trail

Every change in steps 2 to 6 wrote an entry to identity's append-only access log: who, what, the reason, and before/after values for edits.
Read it in the database (`sudo k3s kubectl -n data exec -it postgres-0 -- psql -U at_admin -d identity` on the OVHcloud server in
`DB_MODE=cluster`, the admin password is in `/var/lib/afrotransact/generated.env`):

```sql
select at, actor_id, action, subject, left(detail, 140)
from access_change_log order by id desc limit 30;
```

Expect `oauth-client.created`, `secret-rotated`, `updated`, `disabled`, `enabled`, `session.revoked`, `sessions.revoked-user`,
`sessions.revoked-client`, `signing-key.rotated`, `auth-settings.updated`, `token.inspected` and one `step-up.verified` per password
confirmation. `detail` holds `reason=…`. **No** secret, token, password or step-up proof may appear in it: search for the secret you copied.

## 8. Step-up: the password for the dangerous actions

Rotating the signing key, signing a user or a client out, disabling a client and changing the settings end with a field **Your password
(to confirm it is you)**. Details in [identity-admin.md](identity-admin.md#step-up-the-dangerous-actions).

| Do | Expect |
|---|---|
| Sign in as the super admin and do one of the five **within 5 minutes** with the password field blank | accepted: a sign-in from the last 5 minutes counts as proof |
| Wait more than 5 minutes after signing in (the session keeps working), repeat with the field blank | not done; an error under *Your password*: "Enter your password to confirm this. It can stay blank only within five minutes of signing in." |
| Enter a wrong password | "That password isn't right". Nothing happened. Five wrong passwords within 15 minutes lock the account for 15 minutes (doubling), exactly like a sign-in; the console then shows "Please wait a moment" |
| Enter the right password | done. The access log has `step-up.verified` |
| Ordinary changes (register or edit a client, enable it, rotate a client secret, revoke **one** sign-in) | never ask for the password, only for the reason |

## 9. Rolling back a bad key rotation

A rotation **cannot be undone from the console**: the previous key's private half is erased as soon as it stops being active, so it can
never sign again (that is the point of rotating). What you can do, from least to most drastic:

1. **Nothing needs rolling back in the normal case.** Tokens signed by the previous key keep verifying until *Retires after*. New tokens
   carry the new key. A service that complains about an unknown `kid` refetches the JWKS itself; if one keeps its cache, restart that
   pod (`sudo k3s kubectl -n afrotransact-dev rollout restart deploy/<service>`).
2. **Rotate again** from the console if the new key is unwanted (a new key replaces it; the unwanted key keeps verifying only until it
   retires).
3. **Compromised key, cut the overlap now.** After rotating, end the overlap so tokens signed by the old key stop verifying at once
   (people sign in again, services refetch the JWKS):

   ```sql
   update signing_key set retire_after = now() where status = 'RETIRING';
   ```

4. **Break glass: identity cannot sign** (for example the stored key cannot be opened after a data-key change, or you want the configured key
   back). The configured `SIGNING_KEY_PEM` (secret store, key id `SIGNING_KEY_ID`) is only the seed; the table `signing_key` is the source of
   truth, but an **empty table makes identity fall back to the configured key**. In identity's database:

   ```sql
   delete from signing_key;
   ```

   Within about 5 seconds every replica signs with the configured key and the JWKS lists only that key (restarting identity also re-stores it
   as the active key). Every token signed by a rotated key stops verifying: **everybody, including you, signs in again** (refresh tokens still
   work). Rotate again from the console once you are in to get a sealed key in the table. This was tried against a real identity service
   (JWKS back to the configured `kid` after 7 seconds).

Also: when you rotate the data-protection key (`IDENTITY_DATA_KEY`; `POST /v1/admin/data-protection/rotate` as a super admin, there is no screen for it), run the
rotation and read its report before you remove the previous key from `IDENTITY_DATA_KEYS_PREVIOUS`: it reseals the active signing key with the TOTP
secrets, and `GET /v1/admin/data-protection` counts both per key.

## 10. Known limits (not bugs)

- **An access token already issued stays valid until it expires** (5 minutes by default, 60 at most). Revoking a sign-in, signing a person out,
  disabling a client or retiring a key stops *refreshing* (and, for a retired key, verifying) but cannot recall a JWT that services verify
  offline. There is no deny list.
- The **IP address** is empty for service accounts and for sign-ins made through identity's first-party sign-in API; the "device" is the app
  (client); the browser's user agent is not recorded.
- A **secret** is shown once and never again, not even to a super admin. Lost: rotate. A rotated client secret must then be copied into the
  calling service's own secret (`<SERVICE>_CLIENT_SECRET`) within 24 hours, or that service stops authenticating.
- **Configuration only creates missing clients.** Changing a redirect URI, scope or secret of an existing client in the environment's
  configuration has no effect; use the console.
- A rotation cannot be reverted (step 9); the previous key only keeps verifying what it already signed.
- Step-up is the **password** (or a sign-in from the last 5 minutes). An account without a password (passkey or social sign-in only) signs in
  again instead. The window is `IDENTITY_STEP_UP_FRESH_SIGN_IN` (ISO duration, default `PT5M`).
- The inspector decodes **RS256 JWTs** only. Opaque refresh tokens are reported as "not a JWT".
- The sessions list shows OAuth authorizations. A person's single sign-on browser session on `auth.<base>` ends with *Sign the user out
  everywhere* or its own timeouts (Settings).
- Staff sign-in needs a second factor and identity allows a few codes per hour: a long test session should tick *Trust this browser*.

## 11. What was verified before this plan was written

| Level | Result |
|---|---|
| Playwright against the mock BFF (`staff-console.identity-admin.spec.ts`, Chromium) | passes, including the axe accessibility checks and the password step-up dialog |
| identity run locally on PostgreSQL 16 and driven with curl | create client, secret shown once, token with the new client, inspector, revoke, rotate the signing key (JWKS lists both keys at once, new tokens use the new `kid`), old token still verifies until retirement, disabled client refused, enabled again, `gateway-bff` cannot be disabled, step-up refused without proof, with a wrong password and with a made-up proof, accepted with a proof |
| Integration tests (`IdentityAdminIT`, `StepUpFreshSignInIT`, `DataProtectionIT`) | pass against PostgreSQL 16 |
| gateway-bff conformance test of every operation against `gateway-bff-staff.yaml` | passes (identity is a fake there) |
| **The real BFF in front of the real identity, the staff console in a browser, and the OVHcloud and shared dev environments** | **not verified before this plan: you are the first.** Report what differs |
