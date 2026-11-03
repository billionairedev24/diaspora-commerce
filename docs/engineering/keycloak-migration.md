# Moving users from Keycloak to the identity service

Goal: every customer, seller and staff member signs in **the next day with the same email and password**, with the same
user id everywhere (orders, carts and addresses keep pointing at them), and nobody is asked to sign up again.

## What carries over, and how

| From Keycloak | To identity | Notes |
|---|---|---|
| user id (UUID) | `user_account.id` = token `sub` | Kept as is, so every other service's data still matches. |
| email, verified flag, names, enabled | same columns | Users without an email are skipped (reported). One account per email: a duplicate is skipped (reported). |
| phone attribute | `phone_e164` | Only valid E.164 numbers; others are dropped (reported). |
| **password hash** | `password_hash` as `{kc-pbkdf2-sha256}iterations$salt$key` | Verified by `KeycloakPbkdf2PasswordEncoder` (also `sha512` and legacy `sha1`). Users sign in with the password they have. The first password change or reset stores a normal BCrypt hash. |
| Google / Apple / Facebook links | `federated_identity` | Same provider subject, so "Continue with Google" lands on the same account. |
| (nothing: every sign-up gets it) | role `account.customer` | Granted to every imported user, exactly like a new sign-up, so shoppers can use the storefront at once. |
| Keycloak realm roles | `user_role` via `--role-map roles.json` | e.g. `{"staff-admin": "platform.super_admin", "seller-admin": "seller.admin"}`. Roles with no mapping are listed in the report. Group-inherited roles are not read: map them to a role first. |
| seller / provider organisations | `organization` + `org_membership` | Organisations are created in identity when seller-service publishes its approvals, not by this script. Replay the approved organisations first (so the rows exist), then pass `--memberships memberships.json` (`{"<user id>": [{"orgId": "...", "role": "seller.owner"}]}`, built from seller-service's own team table). Memberships to a missing organisation are skipped. |
| addresses, orders, carts, reviews | not in Keycloak | They live in the other services' databases keyed by the same user id. Nothing to copy, and they reappear on sign-in because `sub` is unchanged. Identity's own address book starts empty unless Keycloak held addresses: tell me where they are stored and I will add them to the import. |
| TOTP, passkeys | not copied | Keycloak's secrets are not portable. Staff and sellers who use MFA enrol again once in identity's security settings. Plan this with the few people affected. |
| sessions, refresh tokens | not copied | Everybody signs in once after cut-over. Mobile apps return to the sign-in screen once. |

Keycloak's hash is not weakened by the import (it is PBKDF2 with its own iteration count). Users with no portable password
(social-only accounts) sign in with their provider or use **Forgot password**.

## Steps

1. **Code and config.** Deploy identity (it already knows the Keycloak hash formats). Keep the issuer URL
   (`https://auth.afrotransact.com`) so tokens, JWKS discovery and the apps' settings do not change.
2. **Rehearse on a copy** (with `--role-map` and, for sellers, `--memberships`). Restore a Keycloak backup and an empty identity database locally, then:
   ```bash
   python3 tools/migrate/keycloak_to_identity.py report --keycloak "$KC_DB_URL"
   python3 tools/migrate/keycloak_to_identity.py sql    --keycloak "$KC_DB_URL" > import.sql   # read it
   python3 tools/migrate/keycloak_to_identity.py apply  --keycloak "$KC_DB_URL" --identity "$ID_DB_URL"
   ```
   The report lists how many users have a portable password, a social link, an unverified email, and every skipped user
   with the reason. Sign in as two or three real test users (one with a non-ASCII password) before going on.
3. **Dry run in production, days before.** Run `apply` against the production identity database while Keycloak is still the
   live provider. It is safe to repeat: a user is inserted once, a still-Keycloak hash is refreshed (so a password changed in
   Keycloak since the last run carries over), and a password already changed in identity is never overwritten.
4. **Cut-over (low-traffic hour).**
   a. Announce a short maintenance window; put Keycloak in read-only (stop registrations and password changes).
   b. Run `apply` once more (the delta).
   c. Point `auth.afrotransact.com` at identity (Cloudflare tunnel route), keep Keycloak running on another hostname.
   d. Sign in as a customer, a seller and a staff member; check `sign_in_audit` for failures.
5. **Watch for a week.** Failed sign-ins for imported users are the signal; the answer is always **Forgot password** (the
   reset email works for imported accounts). Keep Keycloak stopped but restorable for 30 days, then delete it and its database.

## What can go wrong

- **A user cannot sign in with the right password.** Check their `password_hash` starts with `{kc-pbkdf2-`; if it is null, the
  credential was not portable (report lists the count) and they use Forgot password.
- **Email collision.** Two Keycloak users shared an email under different usernames: the second is skipped; merge by hand.
- **Unverified emails.** They stay unverified and get the normal verification step at their next sign-in.
- **Rollback.** Until step 4c nothing changes for users. After it, point the hostname back to Keycloak; anyone who changed
  their password in identity in between has to use Keycloak's old one or reset it there.
