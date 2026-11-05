# AfroTransact v1 → v2 migration runbook

Moving production from the v1 stack (`afrot/refined`: 13 services, Keycloak 26.6.4) to v2 (`diaspora-commerce`:
Spring Authorization Server + per-service databases). **One-way cutover**: v1 stops serving customers, v2 takes over,
no period of both running live.

The promise to customers: *sign in the next morning with the same email and password, see your old orders, keep your
addresses.*

## The size of this job

| | Production today |
|---|---|
| User accounts | under 30 |
| Orders | under 40 |
| Sellers | 1 |
| Returns, disputes | none |
| Products | not migrating — being re-uploaded |

**This is small enough to read every row before it lands.** That is the governing decision of this runbook: no
per-service ETL tools, no status-value mapping tables, no idempotent re-runnable migrators. One SQL script for the
order and payment history, generated once and reviewed in full; the seller set up by hand in the v2 back office;
users imported with the existing tool because password hashes are the one thing a human cannot retype.

An automated toolchain at this volume would be more code, more bugs, and less certainty than reading 40 orders.

> **Status.** The user import is **built and rehearsed end to end against a copy of the local v1 database**:
> argon2 passwords carry across and verify ([Part 1](#part-1--teaching-v2-to-read-keycloaks-hashes)). The order and
> payment SQL in [Part 4](#part-4--what-maps-to-what) is still to be written, fresh during the migration.

---

## Scope

| Migrating | Not migrating | Why not |
|---|---|---|
| Users, passwords, social logins, roles | Keycloak sessions and refresh tokens | Everyone signs in once after cutover. |
| Addresses | TOTP / passkey enrolments | Keycloak's secrets are not portable. Re-enrol — it is a handful of people. |
| Orders, shipments, order lines | **Products, variants, categories, images** | Being re-uploaded fresh. |
| Payments, refunds, transfers, saved cards | Carts and checkout sessions | Ephemeral. |
| Store credit, coupons, referrals *(if any rows exist)* | Wishlists | Point at v1 product ids that will not exist. |
| The one seller — by hand, not by SQL | Returns, disputes, order messages | None in production. |
| | `ledger.*` (accounting) | No v2 equivalent. Keep v1's database as a read-only finance archive. |

**Identity is the anchor.** Every Keycloak user id carries across unchanged and becomes the v2 token `sub`. That is
what lets orders, payments and addresses find their owner with no translation table.

---

## Part 1 — Teaching v2 to read Keycloak's hashes

Nobody resets a password. A hash cannot be converted — it is one-way, and without the plaintext there is no turning
argon2 into BCrypt — so instead **v2 keeps the hash exactly as Keycloak wrote it and learns to verify it.** That is
already how this repo handles PBKDF2 (`KeycloakPbkdf2PasswordEncoder`); argon2 is the missing half.

**Keycloak 26.6.4 hashes with argon2id by default** and your realm sets no `hashAlgorithm` override, so recently set
passwords are argon2. Today's importer understands PBKDF2 only:

```python
# tools/migrate/keycloak_to_identity.py:33
ALGORITHMS = {"pbkdf2": "sha1", "pbkdf2-sha256": "sha256", "pbkdf2-sha512": "sha512"}
```

An argon2 credential falls through to `None`: the user is imported with no password at all.

### Argon2 needs no verifier of our own

PBKDF2 needed custom code because Keycloak invented its own layout for it. Argon2 has a **standard encoded form** —
the PHC string `$argon2id$v=19$m=7168,t=5,p=1$<salt>$<hash>` — and Spring Security's stock `Argon2PasswordEncoder`
parses it, taking the parameters *out of the hash itself* rather than from its own configuration.

Keycloak holds the same facts, only split across two JSON columns:

| Keycloak | PHC field |
|---|---|
| `credential_data.algorithm` = `argon2`, `additionalParameters.type` = `id` | `$argon2id` |
| `additionalParameters.version` (`1.3`) | `v=19` |
| `additionalParameters.memory` | `m=` |
| `credential_data.hashIterations` | `t=` |
| `additionalParameters.parallelism` | `p=` |
| `secret_data.salt` | salt segment |
| `secret_data.value` | hash segment |

So the job is reassembling those fields into the standard string. No cryptography of ours.

**Done**, in three places:

1. `bcprov-jdk18on` added to `java/services/identity/pom.xml` — Spring's `Argon2PasswordEncoder` delegates to
   Bouncy Castle, which identity did not depend on.
2. `IdentityConfiguration.passwordEncoder` registers the stock encoder under id `argon2`, beside `bcrypt` and
   `kc-pbkdf2-*`.
3. `keycloak_to_identity.py` emits `{argon2}$argon2id$v=19$m=…,t=…,p=…$<salt>$<hash>` for argon2 credentials.

> **The gotcha, confirmed:** Keycloak base64-encodes salt and value **with** padding; the PHC form expects it
> **without**, and writes the version in dotted hex (`1.3`) where PHC wants decimal (`v=19`). Both are handled.

**Proven, not assumed.** `KeycloakArgon2PasswordTest` uses a credential Keycloak 26.6.4 actually produced: a user was
created through the admin API with a known password, the row read out of the `keycloak` schema, and the resulting
string verified by identity's real encoder bean. A full rehearsal then imported it and confirmed the hash stored in
`user_account` is byte-identical to the one the test verifies, with the Keycloak user id unchanged.

### Still worth measuring first

```sql
select credential_data->>'algorithm' as algo, count(*) as users
from keycloak.credential
where type = 'password'
group by 1 order by 2 desc;
```

Accounts created before the realm reached Keycloak 24 keep PBKDF2 until their owner next changes their password, so
expect a mix. Both formats are handled either way.

On the local database this returned **argon2 for every password, with no PBKDF2 at all** — so before this work the
importer would have given every single user a null password. Expect production to look the same.

**Social-only accounts never had a password** and are unaffected: they sign in with Google or Facebook as before.

After cutover the Keycloak formats drain away on their own — the first time a user changes or resets their password,
identity stores a normal BCrypt hash.

The migration is not proven until a real user with a real password signs in on a restored copy.

---

## Part 2 — Taking and restoring the dump

### 2.1 Dump with DBeaver

1. Right-click the `afrotransact` database → **Tools → Backup**.
2. Tick these schemas only: `keycloak`, `orders`, `seller`, `payment`, `profile`, `ledger`.
   Leave `catalog` out — products are being re-uploaded.
3. Settings page: **Format: Custom**, tick **No privileges** and **No owner**.
4. Output: `afrotransact-prod.dump`.
5. On a version-mismatch error, point **Preferences → Database → PostgreSQL → Local Client** at a PostgreSQL 16
   install (`pg_dump --version` must be ≥ 16; the server is pg16).

At this data size the dump is small and the DBeaver route is fine — no need to trim tables.

**DBeaver cannot express `-e citext`**, so a dump taken this way is missing the `citext` extension that the `seller`
schema depends on. Before restoring one, create it by hand (§2.3), or take the dump from the command line instead.

### 2.2 Command line, if DBeaver gives trouble

```bash
export PROD_URL='postgresql://USER:PASS@HOST:5432/afrotransact?sslmode=require'

pg_dump "$PROD_URL" -Fc -v -f afrotransact-prod.dump \
  --no-owner --no-privileges -e citext \
  -n keycloak -n orders -n seller -n payment -n profile -n ledger
```

`-e citext` matters. The `citext` extension is installed **into the `seller` schema**, and a schema-filtered dump
leaves extensions out by default — without it `seller.seller_invites` fails to restore and takes five dependent
statements with it. Verified on local data: with the flag, a clean restore reports zero errors.

`pg_dump` takes only ACCESS SHARE locks — it will not block reads or writes.

**Verify, then protect it:**

```bash
pg_restore -l afrotransact-prod.dump | wc -l      # expect hundreds of entries
gpg --symmetric --cipher-algo AES256 afrotransact-prod.dump
```

The file holds every customer's email, postal address and password hash. Encrypted in transit and at rest, never in
the repo, deleted at sign-off.

### 2.3 Restore into a rehearsal database

```bash
createdb v1_prod_copy
pg_restore -d v1_prod_copy --no-owner --no-privileges -j4 afrotransact-prod.dump
```

Expect **zero errors** from a dump taken with `-e citext`. If you took it with DBeaver, create the extension first —
otherwise `seller.seller_invites` is silently missing:

```bash
createdb v1_prod_copy
psql -d v1_prod_copy -c 'create schema if not exists seller;' \
                     -c 'create extension if not exists citext schema seller;'
pg_restore -d v1_prod_copy --no-owner --no-privileges afrotransact-prod.dump   # logs one harmless "schema already exists"
```

**Always check the error count**, and compare row counts against the source before trusting the copy —
`pg_restore` ignores errors and exits 0.

Bring up an empty v2 stack (`make up` runs every service's Flyway migrations) and rehearse against it.

---

## Part 3 — How each piece moves

| Piece | Method | Why |
|---|---|---|
| Users, passwords, social links, roles | `tools/migrate/keycloak_to_identity.py` | Password hashes cannot be moved by hand. Already built. |
| Addresses | Script, part of the same run | Trivial row copy; see §4.2. |
| The seller | **By hand in the v2 back office** | One organisation. A human who can see the result beats SQL across four tables. |
| Orders, shipments, lines | One reviewed SQL script | ~40 orders. Generated once, read in full, run once. |
| Payments, refunds, transfers, saved cards | Same script | Follows orders. |
| Store credit, coupons, referrals | Same script, *if rows exist* | Check first — there may be none. |
| Accounting ledger | Not migrated | Archive v1. |

### Running the user import

**The connection URL must carry the schema.** v1 keeps Keycloak in a `keycloak` *schema* of the shared
`afrotransact` database (`KC_DB_SCHEMA: keycloak`), not in a database of its own, and the importer queries
unqualified table names. Without `options=-csearch_path%3Dkeycloak` it stops immediately with
`relation "user_entity" does not exist`:

```bash
KC='postgresql://USER:PASS@HOST:5432/afrotransact?options=-csearch_path%3Dkeycloak'
ID='postgresql://USER:PASS@HOST:5432/identity'

python3 tools/migrate/keycloak_to_identity.py report --keycloak "$KC"              # counts, skips, unmapped roles
python3 tools/migrate/keycloak_to_identity.py sql    --keycloak "$KC" > import.sql # read it before running it
python3 tools/migrate/keycloak_to_identity.py apply  --keycloak "$KC" --identity "$ID"
```

`report` prints how many users have a portable password. **That number should equal your user count** — if it is
lower, read the skip reasons before going further.

**Only the `afrotransact` realm is imported.** Keycloak's own `master` realm administrator is excluded by design, so
a master-realm login does not become a v2 account. On the local database that correctly left one account behind.

Roles are listed as unmapped unless you pass `--role-map`. The local run reported `admin, buyer, inventory:access,
inventory:approve, seller` — decide which of those matter in v2 and map them; every imported user gets
`account.customer` regardless.

### The seller, by hand

Create the organisation in the v2 back office, then note the generated `org_id` — the order script needs it for every
`shipment.seller_org_id`. The one thing not to retype from memory is the Stripe **`account_id`** on `connect_account`:
copy it exactly from `seller.sellers.stripe_account_id`, or payouts break.

---

## Part 4 — What maps to what

Real column names on both sides. `_cents → _minor` is a rename, not a conversion: both are integer minor units.
This is the specification for the one-off SQL script.

### 4.1 Keycloak → identity *(tool exists; argon2 per Part 1)*

| v1 | v2 | Transform |
|---|---|---|
| `keycloak.user_entity.id` | `user_account.id` | Unchanged. Becomes the token `sub` and every service's `customer_id`. |
| `.email`, `.email_verified` | `email`, `email_verified` | Users with no email are skipped and reported. Duplicate email → second skipped; at 30 users, merge by hand. |
| `.first_name`, `.last_name`, `.enabled` | same | Direct. |
| `user_attribute` (`phoneNumber`/`phone`/`mobile`) | `phone_e164` | Valid E.164 only. |
| `credential` where `type='password'` | `password_hash` | `{kc-pbkdf2-*}iter$salt$key`, or `{kc-argon2}…`. |
| `federated_identity` | `federated_identity` | Same provider subject, so "Continue with Google" finds the same account. |
| `user_role_mapping` + `keycloak_role` | `user_role` | Via `--role-map roles.json`. Group-inherited roles are not read — map them explicitly. |
| — | `user_role` = `account.customer` | Granted to everyone, as a fresh sign-up would be. |
| `credential` where `type='otp'`, WebAuthn | — | Not portable. Re-enrol. |

### 4.2 user-profile → identity

| v1 | v2 | Transform |
|---|---|---|
| `profile.user_profiles.keycloak_id` | `user_account.id` | Reconcile only — Keycloak is the source of truth for name, email, phone. |
| `profile.addresses.*` | `identity.customer_address.*` | `line1/line2/city/state/postal_code/country_code→country/is_default/lat`. ⚠ `customer_address.full_name` is NOT NULL and v1 addresses carry no name — fill from the user's first + last name. |
| `profile.user_profiles.stripe_customer_id` | ⚠ target to confirm | Needed for saved cards and the seller subscription to keep working. |
| `profile.push_tokens` | — | Let the apps re-register on first launch. |
| `profile.wishlist_items`, `.login_sessions` | — | Dropped. |

### 4.3 order → orders

| v1 | v2 | Transform |
|---|---|---|
| `orders.orders.id` | `customer_order.id` | Unchanged. |
| `.buyer_id` (uuid) | `.customer_id` (varchar 80) | UUID as text. |
| `.order_number` (unbounded varchar) | `.number` (varchar 20) | ⚠ v1's column has no length limit — check all 40 fit before running. |
| `.status` | `.status` | Map by hand; with 40 orders you can list the distinct values and decide each. |
| `.subtotal_cents` | `.items_minor` | Rename. |
| `.tax_cents`, `.shipping_cost_cents`, `.total_cents` | `.tax_minor`, `.shipping_minor`, `.total_minor` | Rename. |
| `.discount_cents` + `.shipping_discount_cents` | `.discount_minor` | Summed. |
| `.shipping_address_id` + `.shipping_address` + `.recipient_name` | `.ship_to` (jsonb) | Join `profile.addresses`; prefer the frozen `shipping_address` text where present. |
| `.placed_at`, `.created_at`, `.updated_at` | same | Direct. |
| `.guest_email` | ⚠ decision | `customer_id` is NOT NULL. Check whether any guest orders exist at all. |
| `.region_id`, `.ops_review_flag` | — | No v2 equivalent. |
| `orders.sub_orders` | `shipment` | One row per seller — with one seller, one shipment per order. `seq` numbered per order. |
| `.store_id` | `shipment.seller_org_id` | The single `org_id` from Part 3. |
| `.fulfillment_status` | `shipment.status` | Map by hand. |
| `.tracking_number`, `.shipping_provider`, `.delivered_at` | `tracking_number`, `carrier`, `delivered_at` | Direct. |
| `.commission_cents`, `.transfer_amount_cents`, `.stripe_transfer_id` | `payments.settlement_transfer` | Money lives in payments in v2. |
| `orders.order_items` | `order_line` | |
| `.variant_id` (uuid) | `.variant_atin` (varchar 64, NOT NULL) | ⚠ v1 ids won't exist in the new catalogue. Write `LEGACY-<old uuid>` — valid, traceable, obviously not a catalogue id. |
| `.product_id` | `.product_atin` (nullable) | Same, or null. |
| `.product_title`, `.variant_name`, `.image_url` | `.title`, `.image_url` | **Why old orders still render.** v2 keeps the snapshot on the line, so a historical order shows what was bought with no live product behind it. |
| `.quantity`, `.unit_price_cents` | `.quantity`, `.unit_price_minor` | Rename. |
| `orders.coupon_usages`, `.coupon_code(s)` | `order_coupon` + `offers.coupon_redemption` | Only if rows exist. |
| `orders.returns`, `.return_items`, `disputes`, `.order_messages` | — | **None in production.** |
| `orders.carts`, `.cart_items`, `.abandoned_checkouts`, `.checkout_sessions` | — | Not migrated. |

### 4.4 payment → payments

| v1 | v2 | Transform |
|---|---|---|
| `payment.payments.id` | `payment.id` | Unchanged. |
| `.provider_payment_id` | `.stripe_payment_intent_id` | Direct. |
| `.amount_cents` | `.amount_minor` | Rename. |
| `.status` | `.status` | Map by hand. |
| — | `.customer_id` | Not on the v1 row; join through `orders.orders.buyer_id`. |
| `.stripe_fee_cents`, `.platform_commission_cents` | ⚠ | v2 derives fees from `fee_schedule_id`. Historical fees may only be archivable. |
| `payment.refunds` | `payment_refund` | `amount_cents→amount_minor`, `stripe_refund_id` direct. |
| `payment.saved_payment_methods` | `customer_payment_method` | `brand`, `last4`, `exp_month`, `exp_year` direct. ⚠ Confirm `stripe_pm_id` lands in `id`. |
| `payment.transfers` | `settlement_transfer` | `store_id → org_id` (the single seller). |
| `store_credit.entries` | `credit_entry` + `credit_account` | `delta_cents→amount_minor`; recompute `balance_minor` and `balance_after_minor` in timestamp order. Must balance to the cent. |
| `referral.codes`, `referral.referrals` | `referral_code`, `referral` | `referrer_user_id→referrer_id`, `referred_user_id→referred_id`, `reward_cents→qualifying_minor`. |

### 4.5 seller → by hand

| v1 | v2 | Transform |
|---|---|---|
| `seller.sellers.business_name` | `organisation.legal_name`, `.display_name` | Typed in. |
| `.user_id` | `org_member` + `identity.org_membership`, role `seller.owner` | Typed in. |
| `.stripe_account_id` | `connect_account.account_id` | **Copy exactly.** Payouts depend on it. |
| `.charges_enabled`, `.payouts_enabled` | same on `connect_account` | Typed in. |
| `.commission_rate` | ⚠ | v2 uses plan-based fee schedules. Confirm where a per-seller rate goes, if it differs from the plan. |
| `seller.stores` | `business_page` (slug) + `ship_from_address` | One store → a page plus an address. |
| `seller.seller_subscriptions` | `subscription` + `plan_assignment` | Pick the matching v2 plan in the back office. |
| `seller.seller_principals` | `connect_account.persons`, or skip | Stripe already holds it. Contains SSN last-4 — sensitive. |
| `seller.seller_documents`, `.billing_events`, `.store_shipping_regions` | — | Archive. |

### 4.6 accounting — do not migrate

`ledger.accounts`, `journal_entries`, `journal_lines`, `operating_expenses` are a double-entry ledger with no v2
counterpart. Rebuilding it from migrated orders would produce books that do not tie out to what was actually posted.
Keep v1 as a read-only finance archive; start v2's books at cutover.

---

## Part 5 — Verification

At this size, verify by reading as well as by query.

```sql
-- counts line up
select (select count(*) from keycloak.user_entity where email is not null) as kc_users,
       (select count(*) from user_account)                                 as v2_users;

-- nobody lost their password unexpectedly
select count(*) from user_account where password_hash is null;   -- compare to the social-only count

-- no orphans
select count(*) from customer_order o
  left join user_account u on u.id::text = o.customer_id where u.id is null;   -- expect 0

-- money is intact
select (select sum(total_cents) from orders.orders) as v1_total,
       (select sum(total_minor) from customer_order) as v2_total;   -- must be equal
```

Then **read all 40 orders side by side** — v1 and v2, totals and line titles. That is a twenty-minute job and worth
more than any automated check.

Sign in by hand as: a customer with an argon2 password, a customer with a PBKDF2 password, a Google-only account, the
seller owner, and a staff admin. Include one password with non-ASCII characters. Open a historical order as each.

---

## Part 6 — Cutover

| When | Step | Rollback |
|---|---|---|
| T−2 days | Full rehearsal on the restored copy. Part 5 green, 40 orders read. | — |
| T−1 day | Set up the seller in production v2 by hand. Verify the Stripe `account_id`. | Delete and redo. |
| T−1 hour | Announce the window. Keycloak read-only: no registrations, no password changes. | Lift the flag. |
| T−0 | Stop v1 writes. Run the user import, then the order/payment script, against production v2. | v1 is untouched and still serving. |
| T+10 min | Part 5 against production, including reading the orders. | Truncate v2 and re-run. |
| T+20 min | **Point `auth.afrotransact.com` and the storefront hostname at v2.** First irreversible step. | Point DNS back; anyone who changed a password in v2 must use the old one or reset. |
| T+30 min | Smoke test: sign in, open an old order, place a new order, seller opens the back office. | As above. |
| T+1 week | Watch failed sign-ins. The answer for a stuck user is **Forgot password**. | — |
| T+30 days | Delete the v1 database and the dump — except the `ledger.*` archive. | — |

**Keep v1 restorable for 30 days.** Stopped, not deleted.

---

## Open questions

1. **The argon2 split** (Part 1 query) — only to confirm whether Part 1 is needed at all. No resets either way.
2. **Guest orders** — does `orders.orders.guest_email` have any rows? `customer_id` is NOT NULL in v2.
3. **`stripe_customer_id`** — where does it live in v2, so saved cards and the seller subscription keep working?
4. **Store credit, coupons, referrals** — any rows at all? If not, three more sections disappear.
