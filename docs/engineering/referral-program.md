# Referral program (S322, ADR 0006)

AfroTransact funds the program. A customer shares a code or link; when a friend who signed up with it has a first order
delivered (and its return window has passed), both are rewarded: credit through the credit ledger (source `REFERRAL`),
or free membership days (S323). Credit is never convertible to cash.

## Where it lives

In **payments-service** (`com.afrotransact.payments`, tables `referral*`, Flyway V21), next to the credit ledger. The reward
is a ledger grant: the grant, its program-cost booking (Dr 5200 / Cr 2600) and the once-only guard (one idempotency key per
referral and side, `referral:<id>:referrer|referred`) are one local SERIALIZABLE transaction. A separate service would
need a second remote call, retry and reconciliation for the same guarantee.

## Flow

1. **Code and link.** `GET /v1/referrals/me` creates the customer's code on first use. Eight characters from
   `BCDFGHJKMNPQRSTVWXZ2-9` (CSPRNG, about 38 bits, no vowels so a code cannot spell a word, no look-alikes), stable,
   never derived from the customer. The share link is `<storefront>/r/<code>`.
2. **Attach at sign-up.** The link opens identity's hosted sign-up with the code filled in (BFF `referral` parameter);
   the form field can also be typed. `identity.user.registered` carries `referralCode` and keyed hashes of the sign-up
   device cookie and network (`deviceHash`, `networkHash`). payments attaches the code (one referral per customer; an
   unknown code, the customer's own code, staff sign-ups and a program that is off attach nothing).
3. **Qualify.** From `orders.order.placed` payments tracks the shipments of a referred customer's orders; from
   `orders.shipment.delivered|cancelled` it ends them. When every shipment of an order ended and the **delivered** value
   reaches the minimum, the referral qualifies. This is the first delivered order that meets the minimum (a smaller first
   order does not use up the referral). The rewards of each side are snapshotted from the settings in force.
4. **Return window, then grant.** `eligibleAt` is the last delivery plus the return-window days. The job
   (`at.payments.jobs.referral-interval`, 5 min) re-checks that the order was not refunded below the minimum
   (payments' own refund total), applies the caps per side, and grants. A membership reward goes through the
   `MembershipGrants` port, whose adapter (`MembershipProgramGrants`, S323) grants free days of the entry membership tier;
   with no published tier it answers "unavailable", the reward stays `DEFERRED` and is retried. A member whose tier has
   the bigger-rewards benefit earns that much more on either kind of reward (see membership.md).
5. **Abuse checks.** Referrer and friend sharing a sign-up **device**, **network (/24 or /64)**, a **delivery address**
   (HMAC of the normalised street, unit, postal code and country) or a saved **card** (brand, last four, expiry; Stripe's
   card fingerprint is not stored) hold the referral in `IN_REVIEW`. Staff (ledger:adjust) approve or reject with a reason
   of at least 15 characters; approval returns it to the normal wait. Customers see a held referral as "waiting" and
   never the check.
6. **Caps and validity.** Per referrer: most rewarded referrals; per customer: most referral credit earned (either side);
   each side is capped on its own, so a capped referrer does not stop the friend's reward. A friend has `validityDays`
   from sign-up to qualify, then the referral expires.
7. **Off switch.** Turning the program off stops new referrals and pauses granting (a kill switch). Referrals resume
   when it is switched on again.

## Settings (staff, payments:configure)

`GET|PUT /v1/referral-settings` and `/history`: on/off, the reward of each side (credit or membership days), qualifying
event, minimum order, return-window days, caps, validity; optimistic `version`, a reason (15+ characters) on every change,
history kept. Defaults: $10 credit each after a first delivered order of at least $30 and a 30-day return window, 20
rewarded referrals and $500 of referral credit per customer, 90 days to qualify.

## Keys

`REFERRAL_SIGNAL_KEY` (payments) and `SIGNUP_FINGERPRINT_KEY` (identity) key the HMACs of the abuse-check fingerprints;
payments derives its key from the Stripe webhook secret when unset. Raw device ids and addresses are never stored.
