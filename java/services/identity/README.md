# identity-service

Spring Authorization Server (OAuth 2.1 / OpenID Connect) for the whole platform, plus the account, access-control,
MFA, passkey and social sign-in APIs. Port **8100**, PostgreSQL database **`identity`**.

Every other service is a resource server: it validates JWTs against `<issuer>/oauth2/jwks` and reads the platform
claims with `JwtActorResolver` (java/libs/platform-security) or `auth.Require` (Go).

## What it does

| Area | Summary |
|---|---|
| Authorization server | Discovery (`/.well-known/openid-configuration`), JWKS (`/oauth2/jwks`), Authorization Code + PKCE (public clients), Client Credentials (services), refresh-token rotation (refresh tokens are single-use; reuse → `invalid_grant`), OIDC userinfo / logout, revocation, introspection. JDBC-backed clients, authorizations and consent (official schemas). |
| Tokens | Access tokens **5 minutes**, RS256, key from `SIGNING_KEY_PEM` (PKCS#8; an ephemeral key is generated in `local`). Previous public keys can stay in the JWKS during rotation (`at.identity.signing.previous-public-keys-pem`). |
| Claims | `sub` = account id for every sign-in method; `pt` principal type; `org` active seller/provider org; `roles`; `perms` = role permissions ∪ direct grants. Recomputed on every issuance and refresh, so a removed permission is gone from the next token (≤ 5 min). Disabled accounts cannot obtain or refresh tokens. |
| Sign-up | One form for customers, sellers and providers (first/last name, email, E.164 mobile via libphonenumber, password + confirm, terms, optional SMS consent). `intent` (`shop`/`sell`/`provide`) adds `account.customer` and the matching applicant role server-side. Staff email domains cannot self-register. |
| Email verification | 6-digit code (hashed), 10-minute expiry, 5 wrong attempts, resend ≤ 1 per 30 s and ≤ 5 per hour (HTTP 429 + `Retry-After`). Signing up again with an unconfirmed email restarts the registration. |
| Landing | `GET /v1/me` → `landing` = `shop` / `seller-onboarding` / `seller-center` / `provider-onboarding` / `provider-hub` / `staff-console` / `driver-app`, from roles and org approval status. |
| RBAC | Permissions, roles per portal (account, platform, seller, provider), multiple roles + direct grants per user, enable/disable, module access matrix (none/view/edit/approve/full), append-only change log (DB trigger rejects UPDATE/DELETE). |
| MFA | TOTP (RFC 6238, otpauth URI for the QR code, replay-protected; secrets encrypted at rest, see [Data protection](#data-protection-s202)), SMS codes (outbox event), 10 hashed recovery codes, "trust this browser" for 30 days. Staff always need a second factor (SMS to the phone on file if nothing is enrolled) and cannot turn MFA off. Passkey sign-ins skip the prompt (phishing-resistant). |
| Passkeys | Spring Security 7 `webAuthn()` with JDBC repositories (`user_entities`, `user_credentials`): `/webauthn/register/options`, `/webauthn/register`, `/webauthn/authenticate/options`, `/login/webauthn`, management page `/webauthn/register`. The first-party apps and the mobile apps register over the API with the user's bearer token (S016, see [Passkeys over the API](#passkeys-over-the-api-s016)). `PasskeyService` lists, renames and removes them and refuses to remove the last sign-in method. The signature counter is enforced (a counter that does not increase is rejected as a cloned authenticator). |
| Hosted pages (SSO) | The only place credentials are entered, for every app: `/login` (email + password, passkey, configured social providers), `/signup` (+ `/signup/verify` email code, then the pending authorization continues signed in), `/login/mfa`, `/login/forgot` (+ `/login/forgot/code`: password reset with an emailed or texted code, S300), branded `error` page. Server-rendered Thymeleaf with the AfroTransact design tokens (`static/assets/tokens.css` + self-hosted Figtree, copies of `@afrotransact/tokens`, checked by `DesignTokensSyncTest`). Branded per app from the saved authorization request (client id, or for `gateway-bff` the origin of its validated redirect URI: storefront, Seller Center, staff console, following `PUBLIC_WEB_URL` / `SELLER_CENTER_URL` / `STAFF_CONSOLE_URL`; no self sign-up for staff/drivers); without a request, or for an unknown client or origin, the pages are neutral AfroTransact pages (never another app's branding). OIDC `prompt=create` opens sign-up, `prompt=login` asks again despite the SSO session, `login_hint` prefills the email, `idp_hint` goes straight to an enabled provider, `invitation` joins an organisation on sign-up. `/connect/logout` (RP-initiated logout) ends the identity session, also for a stale ID token (then only registered post-logout URIs are honoured), and returns each web app to its own landing (`<app>/api/bff/v1/auth/signed-out/<app>`, see [Sign-out and password reset](#sign-out-and-password-reset-s300)). |
| Social login | Google (OIDC) via its own consent page; Apple (OIDC, form_post) and Facebook registrations exist but are disabled by default. A provider is offered only when it has client credentials **and** is enabled in `identity_provider` (`PUT /v1/admin/identity-providers/{id}`). Accounts created from provider-verified emails skip email verification; a verified email matching an existing account links to it; signing in with a provider while already signed in links it. |
| First-party sign-in | `gateway-bff` only: password, TOTP/SMS/recovery-code and passkey steps as JSON (`/v1/auth/*`), ending in a one-time **login ticket** that the BFF redeems on the normal authorization-code flow (`/oauth2/authorize?…&login_ticket=…`). See [First-party sign-in](#first-party-sign-in-s209). |
| Brute-force protection | Per account (normalised email, also for unknown emails) and per IP: 5 / 30 failures within 15 min lock for 15 min, doubling per consecutive lockout up to 4 h (`at.identity.sign-in-protection`). Applies to the first-party API **and** the hosted `/login` and `/login/mfa` pages. 429 `identity.sign-in-locked` + `Retry-After`; append-only `sign_in_audit`; `identity.sign-in.locked` events. |
| Organisation teams (S231) | Member directory (names, email, role, status, joined date), role changes, removals and email invitations for seller and provider organisations. See [Organisation teams and invitations](#organisation-teams-and-invitations-s231). |
| Phone verification (S234) | `POST /v1/me/phone/verification` texts a 6-digit code (hashed, 10 minutes, 5 wrong attempts, 1 per 30 s / 5 per hour); `…/confirm` marks the account phone verified. Independent of MFA (confirming SMS sign-in enrolment also verifies the number). Changing the phone resets it. `GET /v1/me` and the contact lookup return the real `phoneVerified`. |
| Push devices & preferences | Shoppers and drivers register push tokens (`/v1/me/devices`) and set email/SMS/push switches and marketing opt-in (`/v1/me/notification-preferences`); notifications-service reads both through `GET /v1/internal/users/{id}/contact`. |
| Sessions | Login sessions live in Spring Session JDBC (works with several replicas). "Sessions" in Login & security are OAuth authorizations with a live refresh/access token; revoking deletes them. |

## Organisation kind BOTH and markets (S215)

`seller.org.*` events may carry kind `BOTH` (sells products and offers services) with `primaryKind` (onboarding
portal). Identity stores it as is: members of a BOTH organisation may hold `seller.*` or `provider.*` roles, the
owner role granted on approval follows `primaryKind`, the team module is `sTeam` (Seller Center), the token `pt` is
`SELLER_MEMBER` or `PROVIDER_MEMBER` from the member's role, and landing is Seller Center. `GET /v1/me` organisations
and `/v1/orgs/*` responses return kind `BOTH`. `organization.market` holds content-service market codes (ISO 3166-1
alpha-2, `UK` → `GB`; V15 normalises existing rows and adds a check constraint); other values are not stored.

## Passkeys over the API (S016)

Login & security in the storefront (through gateway-bff) and in the mobile apps manages passkeys with the user's
token; the hosted `/webauthn/register` page stays for identity's own pages. One relying party, one credential store:
a passkey registered either way signs in on the hosted page and through `POST /v1/auth/passkey/*`.

1. `POST /v1/me/passkeys/registrations {label?}` → `201 {registrationId, expiresAt, publicKey}`: WebAuthn creation
   options (`rp`, `user.id` = the account's WebAuthn handle, `challenge`, ES256 / EdDSA / RS256,
   `residentKey: required`, `userVerification: required`, `excludeCredentials` = the account's passkeys), valid five
   minutes for one registration; at most 20 passkeys (`422 identity.too-many-passkeys`).
2. The client runs `navigator.credentials.create({publicKey})` (web), `ASAuthorizationPlatformPublicKeyCredentialProvider`
   (iOS: Face ID / Touch ID) or Credential Manager `CreatePublicKeyCredentialRequest` (Android: fingerprint / face).
3. `POST /v1/me/passkeys {registrationId, credential, label?}` → `201 Passkey`. `credential` is the
   `PublicKeyCredential` JSON (`credential.toJSON()`, base64url fields). The attestation is verified by the same
   Spring Security / WebAuthn4J relying party (challenge, origin, RP id, user presence **and verification**); a
   rejected one is `422 identity.passkey-rejected` and uses up the registration; an expired, used or foreign
   registration is `409 identity.passkey-registration-expired`.
4. `PATCH /v1/me/passkeys/{credentialId} {label}` renames; `GET /v1/me/passkeys` and `DELETE …/{credentialId}` as before.

**Mobile origins.** Android passkeys report `android:apk-key-hash:<base64url SHA-256 of the signing certificate>` as
the origin: list each app's (debug, release, Play signing) in `WEBAUTHN_APP_ORIGINS` (comma-separated,
`at.identity.webauthn.app-origins`, merged into the allowed origins). iOS apps report the web origin of their
associated domain, which needs `webcredentials:<PLATFORM_DOMAIN>` in the app and the storefront's
`/.well-known/apple-app-site-association`; Android needs `/.well-known/assetlinks.json` with
`delegate_permission/common.get_login_creds`. The RP id stays the registrable domain (`PLATFORM_DOMAIN`), so web and
app passkeys are the same credentials. Sign-in on mobile uses the same first-party passkey flow as the storefront
(gateway-bff `POST /v1/auth/passkey/options` → platform assertion → `/verify`). The create-account screen offers no
passkey sign-in (UI rule); registration needs a signed-in account.

## Market-scoped staff roles (S248)

`PUT /v1/admin/users/{id}/roles/{role}?market=GB` grants a platform (staff) role for one market (content's market
codes, ISO 3166-1 alpha-2, `UK` → `GB`; repeat for more markets); `DELETE …?market=GB` removes that market (the role
goes with its last market); without `market` the role is platform-wide. A role is either platform-wide or
market-scoped (`409 identity.role-platform-wide` / `identity.role-market-scoped`); Super Admin and account roles
are never market-scoped (`422 identity.role-not-market-scoped`). Stored in `user_role_market` (V18); user views carry
`roleMarkets` (`{"platform.customer_support": ["GB", "US"]}`). RBAC permissions are unchanged (the token's `perms`);
the scope is enforced through OpenFGA: `identity.role.granted` / `revoked` carry `market`, one event per market, so
authz-sync writes `market:<code>#<relation>` instead of `platform:afrotransact#<relation>`. Organisation role events
carry the organisation's market for information.

## Organisation teams and invitations (S231)

Seller Center's team page reads identity's member directory instead of showing bare user ids. Identity's
organisations and memberships are the ones that drive the token `org` claim and org-role permissions.

**Who may call.** Staff and services: `orgs:read` or `orgs:manage` read, `orgs:manage` changes. Members are judged by
their role in *that* organisation (looked up in `org_membership`, not the token's active org) and the organisation
must be APPROVED: roles with `team:manage` (`seller.owner`, `seller.admin`, `provider.owner`, `provider.manager`)
read and change; other roles read only with at least VIEW on the Team & roles module (`sTeam` / `pTeam`) in the
module access matrix (none by default). Everyone else gets 403 (`identity.not-org-member`, `identity.org-not-approved`,
`identity.team-manage-required`, `identity.team-read-denied`).

**Rules.** Roles must fit the organisation kind: `seller.*` for SELLER, `provider.*` for PROVIDER (422
`identity.wrong-portal`; unknown roles 422 `identity.invalid-role`). Only owners (or staff/services) grant, change or
remove the owner role, and invite owners (403 `identity.owner-only`). Members cannot change their own role (403
`identity.own-role`) and can only change existing members (404 `identity.member-not-found`; invite instead). An
organisation never loses its last enabled owner: demoting or removing them is 422 `identity.last-owner` (the seller
service's `orgs:manage` mirror is exempt, it owns its own rules). A role change publishes `identity.role.revoked`
(old role) then `identity.role.granted` (new role); removal publishes `identity.role.revoked`; both carry `orgId`.

**Invitations.** `POST /v1/orgs/{orgId}/invitations {email, roleKey}` stores the invitation with the SHA-256 of a
256-bit single-use token, valid **7 days**, and publishes `identity.invitation.created` (key = org id) carrying the
plain `token` and `acceptUrl` (`at.identity.invitations.accept-url` + `?token=`) for notifications-service to email.
Inviting the same email again revokes the open invitation and sends a new one; inviting a member is 409
`identity.already-member`. `DELETE …/invitations/{id}` revokes a pending one (409 `identity.invitation-not-pending`
otherwise; `identity.invitation.revoked`). The invitee either:

- signs in with the invited email and calls `POST /v1/invitations/accept {token}`, or
- signs up from the link: `POST /v1/accounts` with `invitationToken` and the invited email. The link proved the
  mailbox, so the account starts verified (`verificationRequired: false`, no code) and joins at once.

Both add the membership with the invited role (a user without an active organisation gets this one), close the
invitation and publish `identity.role.granted` + `identity.invitation.accepted`. Token problems are 422 with field
`invitationToken`: `identity.invitation-invalid`, `-expired`, `-used`, `-revoked`; a different email is 422
`identity.invitation-email-mismatch`. `POST /v1/invitations/lookup {token}` (public) lets the accept / sign-up page
show the organisation, role, email and status first.

## Data protection (S202)

TOTP seeds are the only reversible MFA secret identity holds, and they are **encrypted at rest**: a database dump (or
backup, or replica) without the key cannot generate codes.

**What is protected, and how**

| Material | Table / column | At rest |
|---|---|---|
| TOTP secret (authenticator-app seed) | `mfa_totp.secret_ciphertext` + `secret_key_id` | **Envelope-encrypted** (below). The legacy `mfa_totp.secret` column is never written and is emptied at start-up. |
| Recovery codes | `mfa_recovery_code.code_hash` | SHA-256 hash only (codes are shown once; nothing to decrypt) |
| Trusted-browser tokens | `trusted_browser.token_hash` | SHA-256 of a 256-bit random token |
| SMS sign-in / enrolment codes | `verification_code.code_hash` | SHA-256 hash, 10-minute expiry, 5 attempts |
| SMS factor | `mfa_sms.phone_e164` | Phone number only (also on `user_account`); no secret material |
| Passkeys | `user_credentials.public_key` | Public keys only |
| Login tickets, invitation tokens | `login_ticket`, `org_invitation` | SHA-256 only |

**Envelope encryption** sits behind the `SecretCipher` port (`port/SecretCipher`), implemented by
`adapter/out/crypto/EnvelopeSecretCipher`; `JdbcMfaRepository` seals on write and opens on read, so the domain and use
cases only ever see the plain secret in memory. For every record:

1. a fresh random 256-bit **data key** and 96-bit **nonce** are drawn; the secret is encrypted with **AES-256-GCM**;
2. the data key is **wrapped** by the key-encryption key (KEK) through the `DataKeyWrapper` seam (AES-256-GCM with its
   own random nonce for the local key ring);
3. the stored envelope is `0x01 | kidLen | kid | wrappedLen | wrapped data key | nonce | ciphertext+tag`; the key id is
   also stored in `secret_key_id` so rotation can find rows on older keys.

The header (version, key id, wrapped key) and a record context (`mfa_totp.secret:<userId>`) are authenticated data:
changing any byte, the key id, or copying a ciphertext onto another user's row fails the GCM tag check
(`SecretCipherException`).

**Keys.** `at.identity.data-protection.provider=local` (default) builds a key ring from the secret store:

| Env var | Purpose |
|---|---|
| `IDENTITY_DATA_KEY` | Current KEK: base64 of 32 random bytes (`openssl rand -base64 32`), one per environment, never reused. Required outside `local` (start-up fails without it). |
| `IDENTITY_DATA_KEY_ID` | Optional id written with every ciphertext. When unset it is derived from the key (`k-` + 16 hex chars of a SHA-256 fingerprint), so a key swap can never reuse an id. |
| `IDENTITY_DATA_KEYS_PREVIOUS` | Only during a rotation: retired KEKs that may still open existing envelopes, comma-separated `[keyId:]base64`. |
| `IDENTITY_DATA_PROVIDER` | `local` (default), or `aws-kms` / `gcp-kms` / `azure-key-vault` once a KMS adapter is deployed. |

The `local` profile ships a fixed, committed **development** key (`local-dev-1`) so a local database survives restarts;
it protects nothing and must never be used elsewhere. Key material is never logged (`toString()` is redacted).

**Cloud KMS (prepared, off by default).** A KMS adapter implements `adapter/out/crypto/DataKeyWrapper`
(`currentKeyId()`, `wrap(dataKey, context)`, `unwrap(keyId, wrapped, context)`) as a Spring bean active for its
`provider` value: AWS KMS `Encrypt`/`Decrypt` with `EncryptionContext={context}` (key id = key ARN), GCP KMS
`encrypt`/`decrypt` with `additionalAuthenticatedData` (key id = CryptoKeyVersion name), Azure Key Vault
`wrapKey`/`unwrapKey` (RSA-OAEP-256 or AES-KW; key id = key version URL). The KEK then never leaves the KMS, the pod
reaches it through workload identity (`workloadIdentity.*` in the Helm values) and `IDENTITY_DATA_KEY` is not needed.
The envelope format, the `SecretCipher` port and rotation stay the same; local and KMS key ids can coexist in one
key ring during a migration. No KMS SDK is bundled today; selecting a cloud provider without an adapter fails
start-up.

**Migration (V13 + start-up migrator).** `V13__encrypt_totp_secrets.sql` adds `secret_ciphertext` / `secret_key_id`,
makes `secret` nullable and requires one of them. On every start, `DataProtectionService.sealPlaintextSecrets()` (an
`ApplicationRunner`) seals any row still in plain text and empties `secret`, one row at a time with a
compare-and-set update: idempotent, safe with several replicas starting together and with concurrent sign-ins
(`at.identity.data-protection.seal-on-startup`, default true). Until that has run, the repository can still read a
legacy plaintext row. The empty `secret` column is dropped by a later migration once every environment runs this
release.

**Key rotation (no downtime).**

1. Generate a new key. Add it to the secret store as `IDENTITY_DATA_KEY` and move the old value to
   `IDENTITY_DATA_KEYS_PREVIOUS` (`<oldKeyId>:<oldBase64>`; set `IDENTITY_DATA_KEY_ID` too if you name keys).
   For a strictly zero-error rollout across replicas, first deploy with the **new** key only in
   `IDENTITY_DATA_KEYS_PREVIOUS` (every pod can read it), then swap.
2. Roll the deployment. New and updated secrets (every TOTP sign-in re-seals its row) use the new key; old rows still
   open with the previous key.
3. As a staff user with `platform.super_admin` (MFA-protected staff token), call
   `POST /v1/admin/data-protection/rotate`. It re-encrypts every row that is plain text or on another key, in batches
   of 200 with optimistic updates (no locks, sign-ins continue), and answers
   `{keyId, resealed, skipped, failed, remaining: {keyId: count}}`. It is audited (`access_change_log`
   `data-protection.rotated`) and idempotent — run it again until `remaining` lists only the new key.
   `GET /v1/admin/data-protection` shows `{currentKeyId, secretsByKey}` at any time.
4. `failed > 0` means rows sealed under a key that is no longer in the ring; they are left untouched (add the key back
   to `IDENTITY_DATA_KEYS_PREVIOUS` and rerun). When nothing remains on the old key, remove
   `IDENTITY_DATA_KEYS_PREVIOUS` and roll again; destroy the old key after the backup retention period (older backups
   still need it).

Other callers get 403 `identity.super-admin-required` (staff without the role, services and customers alike).

## Sign-in flow (browser)

```
app ──/oauth2/authorize (PKCE)──▶ /login ──password──▶ [/login/mfa: TOTP | SMS | recovery code, trust browser] ──▶ code ──▶ /oauth2/token
                                     ├─ Sign in with a passkey (WebAuthn)        (no second factor)
                                     └─ /oauth2/authorization/google ─▶ Google consent ─▶ /login/oauth2/code/google ─▶ [MFA if enrolled]
```

Pages are served by the authorization server itself (`/login`, `/login/mfa`, `/webauthn/register`); apps never see
passwords. Public clients (web apps, mobile) use PKCE; the client setting
`settings.client.afrotransact.public-refresh-tokens` lets a public client receive rotating refresh tokens
(Spring Authorization Server does not issue them to public clients by default).

## Sign-out and password reset (S300)

**Single sign-out, back to the app.** Each web app signs out through gateway-bff (`POST /v1/auth/sign-out`), which
revokes its tokens and sends the browser to `/connect/logout` with `id_token_hint`, `client_id=gateway-bff`, the app's
own `post_logout_redirect_uri` and the page as `state`. The `gateway-bff` client registers one post-logout URI per app,
built from the same variables as its callbacks (so a moved port such as Seller Center on :3010, and LAN mode, follow
along): `${PUBLIC_WEB_URL}/api/bff/v1/auth/signed-out/storefront`, `${SELLER_CENTER_URL}/api/bff/v1/auth/signed-out/seller-center`,
`${STAFF_CONSOLE_URL}/api/bff/v1/auth/signed-out/staff-console`. An unregistered URI is never followed: the browser is
signed out and lands on `/login?logout`, a neutral page. The BFF's landing then shows the storefront signed out (the
page, or home, with a "You're signed out" toast; no sign-in wall), or starts Seller Center's or the staff console's own
sign-in (`prompt=login`, `notice=signed_out`): identity's page branded for that app, saying "You've been signed out",
returning to the app afterwards.

**Branding.** Identity pages are branded from the saved authorization request only. Before S300 a page opened without
one fell back to the storefront's copy, and Seller Center and the staff console were told apart only by comparing the
callback's origin with the configured URLs; the back-office apps' sign-outs returned to their home pages, which then
asked to sign in again (the staff console with "Use your @afrotransact.com staff account", leading to the staff-branded
identity page). Now there is a neutral brand (`auth.neutral.*`) for pages without an app, the staff brand needs a staff
console request (its callback origin, or the `staff-console` client), and every app has its own post-logout landing.
The staff console's own "Sign in to the staff console" page is gone too: signed out, it goes straight to identity's
sign-in like Seller Center (a sign-in that comes back signed out within two minutes shows "Sign-in is temporarily
unavailable" with Try again instead of looping).

**Forgotten password.** `/login/forgot` (and `POST /v1/password-reset` for the apps):

1. Enter the email. The answer is always the same ("If it has an AfroTransact account, we've sent a code"), whether
   or not the account exists (no enumeration). An account with a password gets a 6-digit code (10 minutes, single
   use, at most 5 wrong tries, SHA-256 hashed at rest like every one-time code) by email, and by SMS when the phone is
   verified: one code, purpose `PASSWORD_RESET` on `identity.verification.requested` (notifications template
   `identity.password_reset`; locally Mailpit, like sign-in codes). An account that only signs in with Google, Apple or
   Facebook gets `identity.password-reset.federated` (email: sign in with that provider). Passkeys are unchanged.
2. `/login/forgot/code`: the code and a new password (the sign-up policy and its live checklist). The password is
   checked before the code is used, so a weak password does not burn it.
3. On success the password changes, every refresh token (`oauth2_authorization`), identity browser session
   (`spring_session`) and trusted browser is revoked (signed out everywhere), `identity.password.changed` is published
   (email "your password was changed") and the browser lands on the sign-in page of the app that started the flow
   (the saved request is kept) with a success message and the email filled in.

Limits reuse the sign-in protection's counters under their own keys, for every email with or without an account:
`reset-request:<email|ip>` (5 requests per email / 30 per IP in 15 minutes) and `reset:<email|ip>` (5 wrong codes per
email / 30 per IP), then escalating lockouts (429 `identity.reset-locked` + `Retry-After`). Wrong, expired, used and
unknown codes all answer `identity.invalid-reset-code`. Every request, failure, lockout and completion is in
`sign_in_audit` (method `PASSWORD_RESET`), the completion also in the access change log (`password.reset`).

**Staff accounts** may use the same self-service reset. There was no admin-only reset rule or admin reset endpoint;
what protects staff is that they always need a second factor and cannot turn it off. A reset changes only the
password and forgets trusted browsers, so the next staff sign-in still asks for two-step verification (an attacker
with the inbox alone cannot get in). The staff-branded pages say so and point to an access administrator for a lost
authenticator or phone.

## First-party sign-in (S209)

The web apps no longer use this API: they sign in on the hosted pages above (single sign-on). It remains for
first-party clients that cannot use a browser redirect. The browser never talks to identity: the
storefront posts to gateway-bff, which calls identity server-to-server with its **client-credentials token**
(`gateway-bff`, scope `auth:first-party`; any other caller gets 403 `identity.first-party-client-required`). When every
factor is done, identity returns a **login ticket** (256-bit random, SHA-256 stored, single use, 60 s, bound to the
client). The BFF redeems it on the **normal authorization-code flow**: a `LoginTicketAuthenticationFilter` on the
authorization server chain authenticates `GET /oauth2/authorize?…&login_ticket=…` for that request only (no session
login), so the code, token response, `pt`/`roles`/`perms`/`org` claims, `auth_time`, refresh-token rotation, session
listing and revocation are exactly those of the hosted flow.

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser (storefront forms)
    participant F as gateway-bff
    participant I as identity
    F->>I: POST /oauth2/token grant_type=client_credentials scope=auth:first-party (Basic gateway-bff)
    I-->>F: service access token (cache until exp - 30 s)
    B->>F: POST /v1/auth/sign-in {email, password}
    F->>I: POST /v1/auth/password {email, password, clientIp, userAgent, trustedDeviceToken?}
    alt second factor required
        I-->>F: 200 {status: MFA_REQUIRED, challengeId, factors{totp, sms, smsHint, recoveryCode}}
        F-->>B: MFA form (challengeId)
        opt text me a code
            B->>F: resend / choose SMS
            F->>I: POST /v1/auth/mfa/sms {challengeId, clientIp}
            I-->>F: 202 {sentTo, expiresAt}
        end
        B->>F: POST /v1/auth/mfa/verify {challengeId, code, trustDevice}
        F->>I: POST /v1/auth/mfa/verify {challengeId, factor, code, trustDevice, clientIp, userAgent}
        I-->>F: 200 {status: AUTHENTICATED, loginTicket, trustedDeviceToken?}
    else no second factor (or trusted device)
        I-->>F: 200 {status: AUTHENTICATED, loginTicket}
    end
    Note over B,I: Passkey: POST /v1/auth/passkey/options → {challengeId, publicKey}; browser runs<br/>navigator.credentials.get({publicKey}); POST /v1/auth/passkey/verify {challengeId, credential} → AUTHENTICATED
    F->>I: GET /oauth2/authorize?response_type=code&client_id=gateway-bff&redirect_uri=…&scope=…&state&nonce&code_challenge(S256)&login_ticket=… (do not follow redirects)
    I-->>F: 302 Location: redirect_uri?code=…&state=…
    F->>I: POST /oauth2/token grant_type=authorization_code, code, redirect_uri, code_verifier (Basic gateway-bff)
    I-->>F: access, refresh and ID tokens (usual claims)
    F-->>B: Set-Cookie at_session (rotated), merged cart, {redirectTo}
```

### What the BFF calls

1. **Service token.** `POST {issuer}/oauth2/token` with HTTP Basic `gateway-bff:$OIDC_CLIENT_SECRET`,
   `grant_type=client_credentials&scope=auth:first-party`. Cache it; send it as `Authorization: Bearer` on every
   `/v1/auth/*` call. Always pass the **end user's** IP (`clientIp`, required, from the trusted `X-Forwarded-For` hop)
   and `userAgent`; never the BFF's own address, or IP lockouts would hit everyone.
2. **Password.** `POST /v1/auth/password` `{email, password, clientIp, userAgent, trustedDeviceToken?}`.
   `trustedDeviceToken` is the value from an earlier `trustDevice` verification, kept by the BFF in an HttpOnly, Secure,
   SameSite=Lax cookie (30 days). Response `status`:
   * `MFA_REQUIRED`: `challengeId` (10 min, 5 wrong codes) and `factors` → return the challenge to the MFA form
     (the BFF contract already uses `challengeId`).
   * `AUTHENTICATED`: `loginTicket` → step 5.
3. **Second factor.** Optional `POST /v1/auth/mfa/sms {challengeId, clientIp}` (202 `{sentTo, expiresAt}`; 429 when
   asked too often), then `POST /v1/auth/mfa/verify {challengeId, factor: TOTP|SMS|RECOVERY_CODE, code, trustDevice,
   clientIp, userAgent}` → `AUTHENTICATED` with `loginTicket` and, when `trustDevice`, `trustedDeviceToken` +
   `trustedDeviceExpiresAt` to store in the cookie.
4. **Passkey.** `POST /v1/auth/passkey/options {clientIp, userAgent}` → `{challengeId, expiresAt, publicKey}`. The
   storefront decodes the base64url `challenge` and calls `navigator.credentials.get({publicKey})` (discoverable
   passkey, `userVerification: required`, RP id = the storefront's registrable domain, origin must be listed in
   `at.identity.webauthn.allowed-origins`). It posts the `PublicKeyCredential` JSON (base64url fields, e.g.
   `credential.toJSON()`) back; the BFF forwards `POST /v1/auth/passkey/verify {challengeId, credential, clientIp,
   userAgent}` → `AUTHENTICATED` (passkeys need no second factor). A challenge is good for one assertion.
5. **Redeem the ticket** within 60 s, exactly like `GET /v1/auth/login` but server-side: generate `state`, `nonce` and
   a PKCE verifier, `GET {identity}/oauth2/authorize?response_type=code&client_id=gateway-bff&redirect_uri=<the
   registered callback, e.g. https://<storefront>/api/bff/v1/auth/oidc/callback>&scope=openid profile email phone
   offline_access&state=…&nonce=…&code_challenge=…&code_challenge_method=S256&login_ticket=<ticket>` **without
   following redirects**. Expect `302` to `redirect_uri?code=…&state=…` (check `state`); a `400 {"error":
   "invalid_grant"}` means the ticket was unknown, used, expired or for another client — ask the user to sign in again.
6. **Exchange** the code as the callback does today (`POST /oauth2/token`, Basic auth, `code_verifier`), verify the ID
   token (JWKS, audience, nonce), rotate the session id, store tokens, merge the guest cart and answer the storefront
   with the post-sign-in redirect.

### Errors (RFC 9457, `code` property)

| Status | `code` | Meaning |
|---|---|---|
| 401 | `identity.invalid-credentials` | Wrong email or password (same for unknown emails; timing equalised) |
| 401 | `identity.invalid-code` | Wrong second-factor code; `attemptsLeft` on the challenge |
| 401 | `identity.challenge-invalid` | Challenge unknown, expired, used up, completed or started by another client |
| 401 | `identity.passkey-rejected` | Assertion failed (signature, challenge, origin, RP id, user verification) |
| 403 | `identity.email-not-verified`, `identity.account-disabled` | Right password, but the account cannot sign in |
| 403 | `identity.first-party-client-required` | Caller is not a first-party client service token |
| 429 | `identity.sign-in-locked` | Account or IP locked; `Retry-After` / `retryAfterSeconds` |

Failed passwords, codes and passkey assertions count towards the lockouts. The account counter is cleared only when a
sign-in completes (not after the password when a second factor is pending), so a leaked password does not buy unlimited
code guesses. Passkey steps check the IP lock only (they cannot be guessed). Every attempt is written to
`sign_in_audit` (`channel` first-party or hosted; outcomes SUCCEEDED, FAILED, MFA_REQUIRED, LOCKED, BLOCKED,
TICKET_REDEEMED, TICKET_REJECTED).

## Service lookups for notifications

`GET /v1/internal/users/{userId}/contact` (client credentials, `pt=SERVICE`, `users:read`; `?app=customer|driver`
filters push tokens):

```json
{"userId": "5b0f…", "firstName": "Adaeze", "email": "ada@example.com", "emailVerified": true,
 "phone": "+17132490142", "phoneVerified": false, "locale": "fr-CA", "timeZone": "America/Toronto", "market": "US",
 "pushTokens": [{"token": "…", "platform": "apns", "app": "customer", "os": "ios"}],
 "preferences": {"email": true, "sms": true, "push": false}, "marketingOptIn": true}
```

`pushTokens[].platform` is the **transport** (`fcm` / `apns`) that notifications-service routes on; the device OS is
`os`. `phoneVerified` is true only when the account's current phone was confirmed with an SMS code (S234 phone
verification or SMS sign-in enrolment; stored in `phone_verification`, cleared when the number changes). `market` is the default
delivery address's country, else the active organisation's. `GET /v1/internal/orgs/{orgId}/owners` →
`{orgId, ownerUserIds}`. Mobile apps register tokens with `POST /v1/me/devices` on every start and token refresh, and
call `DELETE /v1/me/devices {token}` on sign-out.

## REST API (`/v1`, JSON, RFC 9457 problem details)

Full contract: [`contracts/openapi/identity.yaml`](../../../contracts/openapi/identity.yaml) (exported with
`tools/contracts/export_java_openapi.sh identity identity 8100`). Reference pages: `/swagger-ui.html`, `/docs`, `/redoc`
(off in prod).

| Method & path | Auth | Purpose |
|---|---|---|
| `POST /v1/accounts` | public | Unified sign-up (`intent` = shop/sell/provide) |
| `POST /v1/accounts/verification` | public | Confirm email with the 6-digit code |
| `POST /v1/accounts/verification/resend` | public | New code (rate limited) |
| `POST /v1/password-reset` | public | Ask for a password reset code (always 202, no enumeration; S300) |
| `POST /v1/password-reset/confirm` | public | New password with the code; signs out everywhere (S300) |
| `GET /v1/identity-providers` | public | Enabled social providers for sign-in screens |
| `GET /v1/me`, `PATCH /v1/me` | user | Profile, roles, permissions, orgs, `landing`; complete profile (phone, terms) |
| `POST /v1/me/roles/seller-applicant`, `…/provider-applicant` | user | Existing customer starts selling / offering services (same account, audited) |
| `PUT /v1/me/active-org` | user | Choose the organisation to act for (`org` claim) |
| `PUT /v1/me/password` | user | Change password (current password required) |
| `GET /v1/me/sessions`, `DELETE /v1/me/sessions/{id}`, `POST /v1/me/sessions/revoke-others` | user | Active sessions / sign out other devices |
| `GET /v1/me/linked-accounts`, `DELETE /v1/me/linked-accounts/{provider}` | user | Linked social accounts (cannot unlink the last sign-in method) |
| `GET /v1/me/passkeys`, `DELETE /v1/me/passkeys/{credentialId}`, `PATCH …/{credentialId} {label}` | user | Passkeys |
| `POST /v1/me/passkeys/registrations`, `POST /v1/me/passkeys` | user | Register a passkey over the API (S016, above) |
| `GET /v1/me/mfa`; `POST /v1/me/mfa/totp`, `…/totp/confirm`, `DELETE …/totp`; `POST /v1/me/mfa/sms`, `…/sms/confirm`, `DELETE …/sms`; `POST /v1/me/mfa/recovery-codes` | user | Two-step verification |
| `GET /v1/me/devices`, `POST /v1/me/devices`, `DELETE /v1/me/devices` (body `{token}`), `DELETE /v1/me/devices/{id}` | user (driver app: `delivery:operate`) | Push tokens: `platform` ios/android/web, `app` customer/driver, optional `provider` fcm/apns (default apns for iOS, else fcm), `deviceName`. A token belongs to one account (re-registering moves it); 10 per app, least recently seen evicted. Responses show only `tokenSuffix`. |
| `GET/PUT /v1/me/notification-preferences` | user | `email`, `sms`, `push` (default on), `marketingOptIn` (default off, `marketingOptInAt` recorded), optional `locale` (BCP 47) and `timeZone` (IANA) |
| `GET /v1/internal/users/{userId}/contact?app=` | SERVICE + `users:read` | Contact for notifications (shape below); 404 for unknown or non-UUID ids |
| `GET /v1/internal/orgs/{orgId}/owners` | SERVICE + `users:read` | `{orgId, ownerUserIds}`: enabled `seller.owner` / `provider.owner` members; 404 for unknown orgs |
| `POST /v1/auth/password`, `/v1/auth/mfa/sms`, `/v1/auth/mfa/verify`, `/v1/auth/passkey/options`, `/v1/auth/passkey/verify` | SERVICE `gateway-bff` + `auth:first-party` | First-party sign-in (S209), see below |
| `GET /v1/admin/permissions`, `GET /v1/admin/roles?portal=` | `access:read` | Permission catalogue, roles with permissions |
| `PUT/DELETE /v1/admin/roles/{role}/permissions/{perm}` | `access:admin` | Capabilities tab |
| `GET /v1/admin/users?q=`, `GET /v1/admin/users/{id}`, `POST /v1/admin/users` | read / admin | People tab, provision staff/driver accounts |
| `PUT/DELETE /v1/admin/users/{id}/roles/{role}?market=` | `access:admin` | Assign/remove account and platform roles; `market` scopes a staff role (S248) |
| `PUT/DELETE /v1/admin/users/{id}/permissions/{perm}` | `access:admin` | Direct grants (e.g. `delivery:operate`) |
| `POST /v1/admin/users/{id}/disable`, `…/enable` | `access:admin` | Disable ends every session immediately |
| `GET /v1/admin/module-access?portal=`, `PUT /v1/admin/module-access/{portal}/{module}/{role}` | read / admin | Module access matrix |
| `GET /v1/admin/access-log?userId=` | `access:read` | Append-only change log |
| `GET /v1/admin/identity-providers`, `PUT /v1/admin/identity-providers/{id}` | read / admin | Identity provider switches |
| `GET /v1/admin/data-protection`, `POST /v1/admin/data-protection/rotate` | STAFF + `platform.super_admin` | Encryption-key status, re-encrypt MFA secrets under the current key ([Data protection](#data-protection-s202)) |
| `PUT /v1/orgs/{orgId}` | `orgs:manage` (seller service) | Mirror seller/provider orgs and approval status |
| `GET /v1/orgs/{orgId}/roles` | team reader | S245: roles assignable in the organisation `{orgId, kind, roles: [{key, portal, name, description, permissions}]}` — seller roles for SELLER, provider roles for PROVIDER, both for BOTH (Seller Center role pickers and nav groups) |
| `GET /v1/orgs/{orgId}/members` | team reader (below) | Member directory `{orgId, items: [{userId, firstName, lastName, name, email, roleKey, roleLabel, status ACTIVE/DISABLED, joinedAt}]}` |
| `PUT /v1/orgs/{orgId}/members/{userId}` `{roleKey}`, `DELETE …` | team manager, staff/service `orgs:manage` | Change a member's role / remove a member (204). The seller service and staff may also add members directly |
| `POST /v1/orgs/{orgId}/invitations` `{email, roleKey}` → 201, `GET …/invitations`, `DELETE …/invitations/{id}` | team manager (list: team reader) | Invite by email, pending invitations `{orgId, items}`, revoke |
| `POST /v1/invitations/lookup` `{token}` | public | What an invite link is for (org, role, email, status) |
| `POST /v1/invitations/accept` `{token}` | user (same email) | Join the organisation |
| `POST /v1/me/phone/verification`, `POST /v1/me/phone/verification/confirm` `{code}` | user | S234 phone verification (202 `{sentTo, expiresAt}`; 200 `{phone, phoneVerified, verifiedAt}`) |

### Token claims

| Claim | Example | Notes |
|---|---|---|
| `sub` | `5b0f…` (account UUID) | client id for client-credentials tokens |
| `pt` | `CUSTOMER`, `SELLER_MEMBER`, `PROVIDER_MEMBER`, `STAFF`, `DRIVER`, `SERVICE` | STAFF = any platform role other than driver; DRIVER = driver only; *_MEMBER while acting for an org |
| `org` | `org_3k2j9x8q7w6e5r4t3y2u` | seller-service org id of the active organisation (selected, else the first approved); compared verbatim by every service |
| `roles` | `["account.customer","seller.owner"]` | account/platform roles + the active org role |
| `perms` | `["catalog:write","orders:read"]` | org-role permissions count only while the org is APPROVED; client tokens: granted scopes |
| `given_name`, `family_name`, `email`, `email_verified`, `phone_number` | `Adaeze`, … | on every user access token (prefill; `phone_number` only when set); ID tokens carry them per granted OIDC scope |

## Events (transactional outbox → `at.identity.events`, key = user id)

`identity.user.registered`, `identity.user.email-verified`, `identity.verification.requested` (one-time code for the
notifications service: `recipient`, `channel` email/sms, `code`, `expiresInMinutes`, `purpose`),
`identity.role.granted` / `identity.role.revoked`, `identity.permission.granted` / `identity.permission.revoked`
(direct grant or role capability change, key = role key for the latter), `identity.user.disabled` /
`identity.user.enabled`, `identity.sign-in.locked` (key = user id, or `ip:<address>` for IP lockouts; `scope`,
`channel` first-party/hosted, `lockouts`, `lockoutSeconds`, `lockedUntil`), `identity.device.registered` /
`identity.device.unregistered` (`deviceId`, `app`, `platform`, `provider`; never the token),
`identity.notification-preferences.updated` (the switches, `locale`, `timeZone`), `identity.user.phone-verified`
(`userId`, `phone`, `verifiedAt`), `identity.invitation.created` (key = org id; `token`, `acceptUrl`, `email`,
`recipient.email`, org and role names, `expiresAt`, `inviterName`; never log it), `identity.invitation.revoked` (key =
org id), `identity.invitation.accepted` (key = user id), `identity.password-reset.federated` (S300: a reset asked for
an account without a password; `recipient.email`, `providers`) and `identity.password.changed` (S300: `method` RESET,
`changedAt`, `sessionsRevoked`). Password reset codes are `identity.verification.requested` with `purpose:
PASSWORD_RESET` (email, and sms to a verified phone). Phone verification codes go out as
`identity.verification.requested` with `channel: sms` and `purpose: PHONE_VERIFICATION`. Schemas: [`contracts/asyncapi/fragments/identity.yaml`](../../../contracts/asyncapi/fragments/identity.yaml).

### Consumed (consumer group `identity-service`, inbox-deduplicated, `CONSUMERS_ENABLED`)

`at.seller-org.events`: `seller.org.approved` upserts the organisation (id = seller org id `org_…`, kind, display
name, status APPROVED, market = country) and makes `applicantUserId` and every `ownerUserIds` entry a member with
`seller.owner` / `provider.owner`; members without an active organisation get it, so the next token (refresh) carries
`org` and owner permissions. `seller.org.updated` refreshes name, kind, status and market.

## Data (Flyway, `db/migration`)

- `V1__identity_schema.sql`: `user_account`, `permission`, `role`, `role_permission`, `user_role`, `user_permission`,
  `organization`, `org_membership`, `access_module`, `module_access`, `access_change_log` (append-only),
  `verification_code`, `mfa_totp`, `mfa_sms`, `mfa_recovery_code`, `trusted_browser`, `federated_identity`,
  `identity_provider`; official Spring Authorization Server (`oauth2_*`), WebAuthn (`user_entities`,
  `user_credentials`) and Spring Session (`spring_session*`) tables. Platform outbox/inbox from `db/platform`.
- `V2__seed_rbac.sql`: permission catalogue, roles, role permissions, back-office modules and the module access matrix
  (seeded from the design prototype), identity providers (Google on, Apple/Facebook off).
- `V6__string_org_ids.sql`: organisation ids (`organization.id`, `org_membership.org_id`, `user_account.active_org_id`)
  become `varchar(80)` holding seller-service ids (`org_…`); pre-existing UUID ids stay valid as text. Adds
  `organization.market`.
- `V7__ai_mcp_permissions.sql`: `ai:use`, `ai:triage`, `mcp:shopper`, `mcp:seller`, `mcp:staff` and their role grants.
- `V11__devices_preferences_first_party_sign_in.sql`: permissions `users:read` (upserted, description widened to the
  service lookups), `auth:first-party` and `sellers:enforce` (granted to `platform.super_admin`,
  `platform.trust_safety`, `platform.operations_manager`) and `delivery:dispatch:read` (`platform.customer_support`);
  tables `push_device` (unique `token_hash`),
  `notification_preference`, `sign_in_challenge` (MFA / passkey, client-bound, 5 attempts), `login_ticket` (SHA-256
  only, 60 s, single use), `sign_in_throttle` (scope ACCOUNT/IP, failures, window, `locked_until`, `lockouts`) and the
  append-only `sign_in_audit` (trigger rejects UPDATE/DELETE).

- `V12__org_team_invitations_phone_verification.sql`: permissions `tax:quote` (super admin), `tax:read` (super admin,
  finance, auditor), `tax:configure` (super admin, finance) and carrier-gateway's `shipping:rate`, `shipping:label`,
  `shipping:void` (super admin, operations manager, fulfilment ops, `seller.owner`, `seller.admin`,
  `seller.fulfilment`; `shipping:rate` also dispatcher and customer support) and `shipping:admin` (every role with
  `shipping:manage`); table `org_invitation` (token SHA-256 only, PENDING/ACCEPTED/REVOKED, one open invitation per
  org and email) and `phone_verification` (verified E.164 per account, backfilled from confirmed SMS sign-in phones).
- `V13__encrypt_totp_secrets.sql`: `mfa_totp.secret_ciphertext` (AES-256-GCM envelope) and `secret_key_id`; the
  plaintext `secret` column becomes nullable, is no longer written and is emptied by the start-up migrator
  ([Data protection](#data-protection-s202)).

## Service clients added for FBA and checkout

`inventory-service` (confidential, client credentials, secret `INVENTORY_CLIENT_SECRET`; tokens have `pt=SERVICE`):
`catalog:read catalog:write shipping:label` (AT-FN unit labels, inbound shipping labels). `retail-service`:
`catalog:read` (any-status variant lookup, S226) and `inventory:read`; `inventory:adjust` was removed (retail-service
never requests it). `orders-service`: `offers:read` (reads the offer's `inventorySku` to reserve FBA stock).

## Permission catalogue

| Permission | Module | Description |
|---|---|---|
| `applications:submit` | onboarding | Submit and track a seller or provider application |
| `applications:review` | onboarding | First review of seller and provider applications |
| `applications:approve` | onboarding | Final approval of seller and provider applications (second reviewer) |
| `orgs:read` | orgs | View sellers, providers and their teams |
| `orgs:manage` | orgs | Create and update organisations and memberships (seller service) |
| `catalog:write` | catalog | Create and edit products, variants and listings |
| `catalog:moderate` | catalog | Approve or reject listings |
| `catalog:taxonomy` | catalog | Manage categories, product types and category requests |
| `catalog:retail` | catalog | List products as AfroTransact Retail (1P) |
| `labels:print` | catalog | Print barcode labels |
| `orders:read` | orders | View orders and shipments |
| `orders:update-status` | orders | Update status of seller-fulfilled orders and shipments |
| `orders:manage` | orders | Update AfroTransact-fulfilled shipments, cancel and reroute orders |
| `refunds:issue` | orders | Issue refunds |
| `returns:manage` | orders | Approve and process returns (RMAs) |
| `disputes:manage` | trust | Investigate and decide disputes |
| `inventory:read` | inventory | View stock positions, lots, reservations and the ledger |
| `inventory:adjust` | inventory | Adjust stock and cycle count |
| `inventory:scan` | inventory | Warehouse scanner: receive, pick and pack |
| `inventory:receive` | inventory | Receive inbound shipments and post goods receipts |
| `inventory:inbound` | inventory | Create and track FBA inbound shipments to AfroTransact FCs (V19): `seller.owner`, `seller.admin`, `seller.fulfilment`, `platform.super_admin`, `platform.inventory_manager`, `platform.fulfilment_ops` |
| `fulfilment:operate` | fulfilment | Run fulfilment-centre pick, pack and dispatch |
| `delivery:operate` | delivery | Use the driver app |
| `delivery:dispatch` | delivery | Plan routes and assign drivers |
| `shipping:manage` | delivery | Carrier integrations, webhooks and status mapping |
| `pickup:manage` | delivery | Pickup points, delivery zones and cut-offs |
| `payouts:read` | payments | View payouts and statements |
| `payouts:approve` | payments | Approve and release payouts |
| `payouts:hold` | payments | Pause or release seller payouts |
| `ledger:read` | payments | View the double-entry ledger |
| `fees:manage` | payments | Fees, commissions and plan pricing |
| `compliance:manage` | payments | Stripe Connect requirements, reminders and payout holds |
| `retail:purchase` | retail | Suppliers, purchase orders and inbound shipments (1P) |
| `marketing:manage` | marketing | Announcements, placements and campaigns |
| `ads:manage` | marketing | Run sponsored product ads |
| `coupons:manage` | marketing | Create, pause and expire coupons |
| `notifications:manage` | marketing | Notification templates and providers |
| `content:manage` | platform | Site content, legal pages and social links |
| `bookings:read` | bookings | View bookings and calendars |
| `bookings:manage` | bookings | Create, reschedule and cancel bookings |
| `services:write` | bookings | Services, prices, deposits and cancellation policies |
| `availability:manage` | bookings | Working hours, breaks and time off |
| `store:design` | storefront | Design the storefront or public page |
| `reviews:respond` | storefront | Reply to reviews |
| `team:manage` | team | Invite members and assign organisation roles |
| `billing:manage` | team | Plan, subscription and billing |
| `support:respond` | support | Answer customer messages and tickets |
| `users:read` | support | Look up customer accounts; services: contact details and organisation owners |
| `tax:quote` | tax | Calculate sales tax for a cart or order (service access; orders-service) |
| `tax:read` | tax | View tax transactions, liabilities and returns |
| `tax:configure` | tax | Configure tax registrations, nexus and filing settings |
| `shipping:rate` | delivery | Rate shopping, tracking look-ups and address validation (carrier gateway) |
| `shipping:label` | delivery | Buy shipping labels, register tracking and book carrier pickups |
| `shipping:void` | delivery | Void unused shipping labels |
| `shipping:admin` | delivery | Manage every pickup location and carrier-gateway settings |
| `auth:first-party` | access | Service access to the first-party sign-in API (gateway-bff only) |
| `sellers:enforce` | trust | Suspend, reinstate and recompute seller health (super admin, trust & safety, operations manager) |
| `delivery:dispatch:read` | delivery | View dispatch boards, routes and driver assignments (read-only; customer support) |
| `config:edit` | platform | Platform configuration: plans, markets, storefront settings |
| `flags:manage` | platform | Feature flags |
| `markets:manage` | platform | Activate countries and states |
| `developer:manage` | platform | API keys, webhooks and OAuth clients |
| `ai:manage` | platform | AI routes, budgets and Lola settings |
| `audit:read` | platform | Read the audit log |
| `reports:read` | platform | Dashboards and reports |
| `access:read` | access | View access control: roles, capabilities, people and change log |
| `access:admin` | access | Grant and revoke roles, permissions, module access and identity providers |

| Role | Portal | Permissions |
|---|---|---|
| `account.customer` (Customer) | account | — |
| `account.seller_applicant` (Seller applicant) | account | `applications:submit` |
| `account.provider_applicant` (Provider applicant) | account | `applications:submit` |
| `platform.super_admin` (Super Admin) | platform | all |
| `platform.operations_manager` (Operations Manager) | platform | `reports:read`, `orders:read`, `orders:manage`, `applications:review`, `applications:approve`, `orgs:read`, `disputes:manage`, `returns:manage`, `catalog:moderate`, `inventory:read`, `fulfilment:operate`, `delivery:dispatch`, `pickup:manage`, `shipping:manage`, `payouts:read`, `users:read`, `audit:read`, `notifications:manage`, `markets:manage` |
| `platform.trust_safety` (Trust & Safety) | platform | `reports:read`, `applications:review`, `applications:approve`, `orgs:read`, `catalog:moderate`, `disputes:manage`, `payouts:hold`, `compliance:manage`, `users:read`, `audit:read`, `orders:read` |
| `platform.finance` (Finance) | platform | `reports:read`, `orders:read`, `orgs:read`, `payouts:read`, `payouts:approve`, `payouts:hold`, `ledger:read`, `fees:manage`, `refunds:issue`, `compliance:manage`, `audit:read`, `inventory:read`, `coupons:manage` |
| `platform.customer_support` (Customer Support) | platform | `reports:read`, `users:read`, `orders:read`, `orders:update-status`, `refunds:issue`, `returns:manage`, `disputes:manage`, `support:respond`, `orgs:read`, `catalog:moderate`, `inventory:read`, `delivery:dispatch`, `pickup:manage` |
| `platform.marketing` (Marketing) | platform | `reports:read`, `orgs:read`, `marketing:manage`, `ads:manage`, `coupons:manage`, `notifications:manage`, `content:manage`, `config:edit` |
| `platform.catalog_manager` (Catalog Manager) | platform | `reports:read`, `orgs:read`, `orders:read`, `catalog:write`, `catalog:moderate`, `catalog:taxonomy`, `labels:print`, `retail:purchase`, `config:edit` |
| `platform.retail_buyer` (Retail Buyer (1P)) | platform | `reports:read`, `orders:read`, `catalog:write`, `catalog:retail`, `retail:purchase`, `inventory:read`, `inventory:receive`, `inventory:scan`, `labels:print`, `pickup:manage` |
| `platform.fulfilment_ops` (Fulfilment Ops) | platform | `reports:read`, `orders:read`, `orders:manage`, `fulfilment:operate`, `inventory:read`, `inventory:adjust`, `inventory:scan`, `inventory:receive`, `delivery:dispatch`, `pickup:manage`, `labels:print`, `retail:purchase` |
| `platform.inventory_manager` (Inventory Manager) | platform | `reports:read`, `orders:read`, `inventory:read`, `inventory:adjust`, `inventory:scan`, `inventory:receive`, `retail:purchase`, `labels:print` |
| `platform.dispatcher` (Dispatcher) | platform | `reports:read`, `orders:read`, `orders:manage`, `delivery:dispatch`, `pickup:manage`, `inventory:read` |
| `platform.product_owner` (Product Owner) | platform | `reports:read`, `config:edit`, `flags:manage`, `markets:manage`, `fees:manage`, `coupons:manage`, `content:manage`, `catalog:moderate`, `orders:read`, `orgs:read`, `payouts:read`, `inventory:read`, `access:read`, `audit:read`, `ai:manage` |
| `platform.developer` (Developer) | platform | Same integrations focus as before, plus **VIEW on every staff console module** (V28) and **EDIT** on marketing/catalog/orders for incident fixes; `users:read`, `orgs:read`, `inventory:read`. Payout approve and dispute approve stay with Finance/Trust. Senior/junior developer split is planned later. |
| `platform.auditor` (Auditor (read-only)) | platform | `reports:read`, `orders:read`, `orgs:read`, `payouts:read`, `ledger:read`, `inventory:read`, `audit:read`, `access:read`, `users:read` |
| `platform.driver` (Driver) | platform | `delivery:operate` |
| `seller.owner` (Owner) | seller | `catalog:write`, `labels:print`, `orders:read`, `orders:update-status`, `returns:manage`, `refunds:issue`, `inventory:read`, `inventory:adjust`, `ads:manage`, `store:design`, `reviews:respond`, `payouts:read`, `billing:manage`, `team:manage`, `compliance:manage`, `support:respond`, `reports:read` |
| `seller.admin` (Admin) | seller | `catalog:write`, `labels:print`, `orders:read`, `orders:update-status`, `returns:manage`, `refunds:issue`, `inventory:read`, `inventory:adjust`, `ads:manage`, `store:design`, `reviews:respond`, `payouts:read`, `team:manage`, `compliance:manage`, `support:respond`, `reports:read` |
| `seller.catalog` (Catalog manager) | seller | `catalog:write`, `labels:print`, `orders:read`, `inventory:read`, `inventory:adjust`, `store:design`, `reports:read` |
| `seller.fulfilment` (Fulfilment) | seller | `orders:read`, `orders:update-status`, `returns:manage`, `inventory:read`, `inventory:adjust`, `labels:print`, `reports:read` |
| `seller.finance` (Finance) | seller | `orders:read`, `payouts:read`, `billing:manage`, `compliance:manage`, `reports:read` |
| `seller.ads` (Marketing) | seller | `ads:manage`, `store:design`, `reviews:respond`, `reports:read` |
| `seller.support` (Support agent) | seller | `orders:read`, `orders:update-status`, `returns:manage`, `support:respond`, `reviews:respond` |
| `provider.owner` (Owner) | provider | `bookings:read`, `bookings:manage`, `services:write`, `availability:manage`, `store:design`, `reviews:respond`, `payouts:read`, `billing:manage`, `team:manage`, `compliance:manage`, `support:respond`, `reports:read` |
| `provider.manager` (Manager) | provider | `bookings:read`, `bookings:manage`, `services:write`, `availability:manage`, `store:design`, `reviews:respond`, `payouts:read`, `team:manage`, `compliance:manage`, `support:respond`, `reports:read` |
| `provider.practitioner` (Practitioner) | provider | `bookings:read`, `bookings:manage`, `availability:manage` |
| `provider.finance` (Finance) | provider | `bookings:read`, `payouts:read`, `billing:manage`, `compliance:manage`, `reports:read` |

Module access matrix: 27 platform modules × 15 platform roles, 10 Seller Center modules × 7 seller roles,
12 Provider Hub modules × 4 provider roles (`GET /v1/admin/module-access?portal=…`).

## Configuration

`application.yml` + `application-{local,dev,uat,prod}.yml`. Secrets come only from the environment
(ExternalSecret `afrotransact/<env>/identity`):

| Env var | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL `identity` |
| `KAFKA_BOOTSTRAP` | outbox relay |
| `SIGNING_KEY_PEM`, `SIGNING_KEY_ID` | RS256 private key (PKCS#8 PEM) and `kid`; required outside `local` |
| `IDENTITY_DATA_KEY` (+ optional `IDENTITY_DATA_KEY_ID`, `IDENTITY_DATA_KEYS_PREVIOUS`, `IDENTITY_DATA_PROVIDER`) | AES-256 key-encryption key for MFA secrets at rest; required outside `local` ([Data protection](#data-protection-s202)) |
| `AUTH_ISSUER_URL`, `HOME_URL` | issuer URL (`http://localhost:8100` locally), post-login fallback (default `PUBLIC_WEB_URL`) |
| `PUBLIC_WEB_URL`, `SELLER_CENTER_URL`, `STAFF_CONSOLE_URL`, `API_BASE_URL`, `PLATFORM_DOMAIN` | shared platform settings injected per environment by the Helm library chart (`docs/engineering/environments.md`): OAuth client redirect URIs, CORS origins, WebAuthn origins; the WebAuthn RP id defaults to `PLATFORM_DOMAIN` (`WEBAUTHN_RP_ID` overrides) |
| `WEBAUTHN_APP_ORIGINS` | S016: native app origins allowed for passkeys, comma-separated (`android:apk-key-hash:…` per Android app signing key); empty by default |
| `IDENTITY_STAFF_EMAIL_DOMAINS` | comma-separated email domains reserved for staff (default `afrotransact.com`) |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` (+ `APPLE_*`, `FACEBOOK_*`) | social sign-in; unset → provider not registered |
| `GATEWAY_BFF_CLIENT_SECRET` (or `BFF_CLIENT_SECRET`), `SELLER_SERVICE_CLIENT_SECRET`, `NOTIFICATIONS_CLIENT_SECRET`, `TRUSTOPS_CLIENT_SECRET`, `TAX_RETURNS_CLIENT_SECRET`, … | confidential client secrets (hashed with BCrypt before storage) |
| `INVITATION_ACCEPT_URL` | Seller Center invitation page (default `${SELLER_CENTER_URL}/invitations/accept`; locally `http://localhost:3001/invitations/accept`) |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`, `BOOTSTRAP_ADMIN_PHONE` | first super administrator (skipped when unset) |

Other settings (`at.identity.*`): `staff-email-domains`, `verification.sender` (`outbox` | `log`), `mfa.*`,
`data-protection.{provider,key-id,key,previous-keys,seal-on-startup}`,
`invitations.accept-url`,
`webauthn.{rp-id,rp-name,allowed-origins}`, `cors-allowed-origins`, and `clients` — the OAuth clients seeded (upserted by
client id) at start-up per environment: `storefront`, `seller-center`, `staff-console`, `customer-app`, `driver-app`
(public, PKCE, rotating refresh tokens), `gateway-bff` (confidential: authorization code + PKCE + refresh tokens, and
client credentials with `auth:first-party` for the first-party sign-in API), and service clients with client
credentials, e.g. `seller-service` (`orgs:manage users:read`), `notifications-service` (`users:read`) and
`trust-ops-service` (`catalog:moderate payments:service compliance:hold offers:write ai:triage users:read`, secret
`TRUSTOPS_CLIENT_SECRET`), `fulfilment-service` (`orders:update-status inventory:reserve delivery:relay`),
`orders-service` (`… users:read tax:quote`) and `tax-returns-service` (`payments:service inventory:adjust
shipping:label users:read`, secret `TAX_RETURNS_CLIENT_SECRET`, local default only in the `local` profile).
Client-credentials tokens always carry `pt=SERVICE` and their granted scopes in `perms`.
`webauthn.allowed-origins` lists identity's own origin and the storefront origins (their forms run the passkey
ceremony for the first-party API); `first-party.client-ids` (default `[gateway-bff]`) and
`sign-in-protection.{account,ip}.{max-failures,window,lockout,max-lockout}` tune the S209 behaviour. Access tokens 5 minutes; refresh tokens 30 days (12 h staff console, 14 h driver app).

## Run locally

```bash
docker compose -f infra/local/docker-compose.yml up -d postgres kafka   # database "identity"
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
cd java && ./mvnw -B -f services/identity/pom.xml spring-boot:run       # profile local, port 8100
```

The `local` profile generates a signing key, writes one-time codes to the log
(`[local] EMAIL_VERIFICATION code … : 123456`), and creates `admin@afrotransact.com` / `LocalAdmin2026!`
(super admin; its SMS sign-in code is logged too). Try it: open
`http://localhost:8100/oauth2/authorize?response_type=code&client_id=customer-app&redirect_uri=http://localhost:8081/auth/callback&scope=openid&code_challenge=…&code_challenge_method=S256`.

## Tests

`./mvnw -B -f services/identity/pom.xml verify` runs Checkstyle, domain unit tests (password policy, phone
validation, sign-up validation, permission resolution, landing rules, code expiry and resend limits, TOTP RFC 6238
vectors, MFA rules), `ArchitectureTest`, and `IdentityFlowIT` (Testcontainers PostgreSQL, real HTTP): discovery/JWKS,
client credentials, sign-up → email code → authorization code + PKCE → custom claims, refresh rotation and reuse
rejection, start selling → org approval → landing and `org`/`perms`, staff sign-in with SMS second factor, role/grant
changes visible on the next token, disable revokes sessions, module matrix and identity-provider switches (real redirect
to Google's consent page), social sign-up/linking, WebAuthn ceremony endpoints, TOTP enrolment + recovery code +
trusted browser, resend rate limit. `FirstPartySignInIT` drives the BFF flow end to end: password → MFA challenge → wrong
TOTP (attempts left) → TOTP with trust-device → login ticket → `/oauth2/authorize?login_ticket` → code → `/oauth2/token`
(client secret + PKCE) → claims, ID-token nonce, refresh; ticket and challenge replay rejected; trusted device skips
MFA; passkey assertion (software ES256 authenticator) → ticket → tokens; only `gateway-bff` may call; account lockout
(also blocks the hosted form) and IP lockout with audit rows and events; devices, preferences, contact and org-owner
lookups; `trust-ops-service` tokens are SERVICE principals; `tax-returns-service` and `orders-service` (`tax:quote`)
client credentials and the V12 tax/shipping grants. `OrgTeamAndPhoneVerificationIT` covers S231 and S234:
member directory, invitation token (hash only, replaced on re-invite, public lookup, email mismatch, single use),
accepting signed in and signing up from the link (no email code, `org` claim on the first token), readers and
managers (catalog manager 403, `orgs:read` service 200), role change events, owner-only and own-role rules, revoke,
removal and the last owner; phone verification (SMS event, hashed code, resend limit, 5 wrong attempts, expiry, single
use, `phoneVerified` in `/v1/me` and the contact lookup, reset on phone change). Unit tests cover the team policy,
`OrgTeamServiceImpl`, `OrgInvitationServiceImpl` and `PhoneVerificationServiceImpl` (Mockito). Unit tests cover the throttle policy, challenge/ticket
rules, the first-party service (Mockito), the login-ticket filter and WebAuthn assertion verification.

**Data protection (S202).** `EnvelopeSecretCipherTest`: round trip (ciphertext never contains the secret), a fresh
data key and nonce per record, tamper detection (every single-bit flip anywhere in the envelope, truncation), binding
to the record, wrong key material and unknown key ids, previous keys during rotation, derived key ids, rejected weak
or missing keys, redacted settings. `DataProtectionServiceImplTest`: super-admin only, paging, resealed / skipped /
failed counts, audit. `DataProtectionIT` (Testcontainers): migrates the schema to **V12** and inserts a plaintext
TOTP row *before* the application starts, then proves start-up applied V13 and sealed it (the authenticator app still
signs in); TOTP enrolment and sign-in never leave the secret in **any** table (every row of every table is scanned
as text, including the hex form of byte columns); super-admin rotation from a retired key (403 for others), a row on a
missing key reported as `failed`, idempotent re-runs, audit row.

**Passkeys end to end (S203).** `PasskeyCeremonyIT` runs the real WebAuthn ceremonies against the running service with
`testsupport/VirtualAuthenticator`, a pure-Java platform authenticator (EC P-256 key pair, attestation object with
`fmt: none` and authenticator data built by hand with a small CBOR encoder, ES256 assertion signatures over
`authenticatorData || SHA-256(clientDataJSON)`). It registers through the hosted ceremony
(`/webauthn/register/options` → `/webauthn/register`, as the passkey page's script does), then signs in on the
**hosted page** (`/oauth2/authorize` → `/webauthn/authenticate/options` → `/login/webauthn` → `redirectUrl` → code →
`/oauth2/token`) and through the **first-party API** (`/v1/auth/passkey/options` → `/verify` → login ticket → code →
tokens), checking the stored signature counter rises 1, 2, 3. Negative cases: registration and assertions from a
foreign origin, a replayed registration response, a replayed assertion (same browser, another browser, old challenge
after fresh options, API challenge reuse and cross-challenge), a cloned authenticator with a lower counter, and a
removed passkey (both sign-in paths reject it; the password still works).

Why not Playwright + Chromium's CDP virtual authenticator: the pure-Java authenticator runs in plain `mvn verify` with
no browser binary, display or CDP version coupling (the pre-installed Chromium and Playwright's CDP protocol must
match), it is deterministic and fast, and it can do what a browser deliberately forbids — claim a foreign origin,
replay a challenge, roll back the counter — which are exactly the attacks the relying party must reject. Browsers set
`origin` and `challenge` themselves, so with Playwright those cases would need extra phishing-origin servers or could
not be expressed at all. The IT drives the same JSON the hosted page's `webauthn.js` and `PublicKeyCredential.toJSON()`
send. What it does not cover is the page's JavaScript itself (button wiring, base64url decoding in the browser).

The ceremony test found two defects, fixed here: a hosted passkey sign-in could not finish the authorization-code flow
(`/oauth2/token` answered 500 because the stored authorization's `WebAuthnAuthentication` principal was not
deserialisable; the authorization service now registers Spring Security's WebAuthn Jackson module), and the signature
counter was never enforced (Spring Security 7.1 verifies against the registration-time counter;
`SignCountVerifyingRelyingParty` now rejects a counter that does not increase).

## Not covered here

- Fine-grained object checks (seller_org → store → listing) belong to OpenFGA (backlog S022), not this service.
- The KMS `DataKeyWrapper` adapters (AWS / GCP / Azure) are designed for but not implemented; the local key ring from
  the secret store is used everywhere today. The empty legacy `mfa_totp.secret` column is dropped in a later release.
- Recovery codes are unsalted SHA-256 hashes of ~50-bit codes and SMS codes are SHA-256 of 6 digits: hashing is kept
  (nothing is reversible), but an offline brute force of a dump could recover unused recovery codes. A keyed hash
  (HMAC with a pepper from the secret store) would close that; it needs a re-generation of existing codes.
- Spring Authorization Server stores access / refresh token values in `oauth2_authorization` as issued; encrypting or
  hashing them needs a custom `OAuth2AuthorizationService` (separate story).
- Spring Security's WebAuthn registration filter lets verification errors (e.g. a foreign origin) surface as a 5xx
  instead of a 400; the ceremony is still rejected.
- Token Exchange (RFC 8693), DPoP for mobile and the device-code grant from the architecture document are not enabled yet.
- Phone verification is not part of the sign-up form; apps prompt for it from the account page.
- Lockout notices to the account owner (security email on `identity.sign-in.locked`) need a notifications rule.

## Address book

Customers keep up to 20 delivery addresses (`GET/POST /v1/me/addresses`, `PATCH/DELETE /v1/me/addresses/{id}`,
`POST /v1/me/addresses/{id}/default`). Addresses are filled from geo-service autocomplete and carry its `verified`
result and coordinates, the recipient's phone (validated and stored in E.164) and delivery instructions for drivers.
The first address becomes the default; exactly one default is enforced by a partial unique index, and deleting the
default promotes the most recently updated address. The gateway-bff serves these to checkout and the account pages.
