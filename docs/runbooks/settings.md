# Settings (staff console)

One place for everything that sets how AfroTransact runs: **Settings** at the bottom of the console sidebar (`/settings`). Pick a
section on the left (a row of chips on a phone). Each section is an existing console module, so who may see or change it is still
decided by their access to that module; a role that can only view a section sees it read-only.

| Section | What is in it | Module that governs access |
|---|---|---|
| General | contact details and social links, help centre, legal documents, privacy requests | Site content |
| Markets & locations | turn AfroTransact on or off by country and state | Markets |
| Notifications | email, SMS, push and WhatsApp templates (preview, history, new versions) | Notifications |
| Sign-in & integrations | which social sign-in buttons appear | Developers |
| Payments & payouts | hold before a seller can be paid, smallest payout, retry of a blocked transfer, Stripe mode (**read-only**: set by the deployment) | Payouts |
| Fees & commissions | fee schedule versions, commission by category and plan, connection fees, negotiated rates, calculator, delivery pricing | Fees |
| Plans & billing | how sellers pay their plan fee | Plans |

The old addresses (`/fees`, `/markets`, `/site-content`, `/developers`, `/notifications`) open the matching section.

**Sales tax is not a Settings section any more.** It is a link of its own in the sidebar (Finance, `/tax`), shown to anyone with
`tax:read`; `tax:classify-admin` is the edit level. Its tabs keep their keys, and the old addresses (`/settings?section=tax`,
`/fees?tab=tax-review` and the other `tab=tax-…` links) redirect to `/tax` on the same tab. Tax filings, Nexus tracker and Tax rates
are tables of the Fees module, so those three tabs only show to staff who can open Fees.
Day-to-day money work stays where it was: Payouts & ledger (approvals, reconciliation, adjustments) and Plans & billing (plan
catalogue, trials, rewards, each seller's billing).

## Suspending or reinstating a seller or provider

**Partners > Sellers & providers**, the row's **⋯** menu or the organisation's sheet: **Suspend…** (approved organisations) and
**Reinstate…** (suspended ones), permission `sellers:enforce`. The console calls trust-ops
(`POST /v1/seller-enforcement/{orgId}/suspend` with `{reason, note}`, `/reinstate` with `{note}`), which asks seller-service for the
suspension hold (listings hidden, payouts paused), restricts the seller in the buy box (offers) and audits who, what and why;
the note is at least 10 characters. Calling Suspend again retries a step that failed (seller-service hold or buy-box) and
changes nothing that already worked. A Stripe compliance payout hold survives reinstatement until Stripe clears it
(*Stripe Connect*, Release payout hold). AfroTransact Retail is refused (`trustops.retail-exempt`). Without the console:
the same two calls with a staff token holding `sellers:enforce`.

## Who calculates sales tax

Sales tax is worked out by **our own rules** by default: the state and local rates (Tax rates) and what each item is for tax
(the category tax profiles and each product's reviewed classification, including items that are never taxed). Nothing is sent to
a tax service and nothing costs per order.

Sales tax → *Who calculates tax* shows the method in force, explains each choice, and has the **shadow provider**
(compared with our rules on real orders, never charged). To change either, pick it and give a reason (10+ characters); the change
is recorded in the history. **Stripe Tax** and **Avalara** can only be chosen once they are set up *and* every outcome has a
verified code in *Provider codes*; until then they are refused (the page says why) and our own rules keep answering. A change
applies to new quotes within seconds; placed orders keep the tax they were charged. Needs `tax:configure`. How to move to a
provider: `docs/runbooks/tax-provider-cutover.md`. Profiles, review and provider codes need `tax:read` to read and
`tax:classify-admin` to change; see the [staff guide](../user-guide/staff.md).

`TAX_PROVIDER` (`RULES` by default) is only the starting value before anyone chooses.

**Tax review mode.** Sales tax → *Review settings* (`taxReview.mode`): *Review by risk* is the default: the tax rules decide, so
the system approves a variant's classification itself (ACTIVE category, clear rules, no missing proof, not on the always-review list)
and puts 5% of those in the *Audit sample* tab for a person to confirm or reclassify. Only exceptions wait in *Tax review*, each with its
reason (conflict, no matching rule, missing answer or proof, regulated category, category awaiting sign-off). *Review every item* sends
everything to a person; switching back to *Review by risk* approves, within seconds, the waiting items the rules decide and leaves
existing approvals alone. Needs `tax:classify-admin` and a reason (10+ characters). See `docs/engineering/category-tax-design.md` section 9.

## Tax filings: what we filed and paid to each state

AfroTransact collects sales tax as marketplace facilitator, so it files and pays it to the states. **Sales tax →
Tax filings** lists the last 12 months, one row per month and state, with:

| Column | Meaning |
|---|---|
| Due now | the ledger's net liability (collected − refunded) for that month and state, as it stands today |
| Paid / Filed / State confirmation | what staff recorded after filing and paying |
| Outstanding | due now − paid (negative: overpaid) |
| Status | **Not filed**, **Settled**, **Owes more**, **Overpaid**, **Nothing due** |

**Do this each month** (after month end, by the state's due date): file the return and pay the state outside the console, then
open the row → **Record filing and payment** → amount paid, the dates, the state's confirmation number and the payment reference.
The amount due at that moment is saved with the record. Needs `tax:configure`; reading needs `tax:read`.

- A refund is booked in the month it happens, so refunds after a month was filed lower *next* month's liability, not the filed one.
  A sale or refund that reaches the ledger late with a date inside a filed month changes that month: its status turns **Owes more**
  or **Overpaid** and the row shows how much it moved since filing. File an amended return or offset it, and note it.
- A wrong amount or date is **voided** (reason required, 10+ characters), never edited; the record stays visible as voided with
  who and why, then record the right one. Only one active record exists per month, state and currency.
- This is a record, not a filing: nothing is sent to a state. Filing, registering with a state and exemption certificates are
  still manual.
- Only states where AfroTransact has collected tax appear.

## Nexus tracker: where sales are close to a state's threshold

**Sales tax → Nexus tracker** lists every state with sales (or a recorded setting): the gross sales shipped there over
the **last 12 months** (net of refunds) and the number of orders, against that state's threshold, most urgent first:

| Status | Meaning |
|---|---|
| **Past the threshold, not registered** | sales (or orders, if the state has an order-count test) are at or over the threshold and no registration is recorded: decide with your tax advisor whether to register |
| **Close to the threshold** | 80 % or more of the nearest threshold |
| **Registered** | a registration number and effective date are recorded |
| **Below the threshold** | everything else |

- Open a state to set its **sales threshold**, an **order-count threshold** (leave empty if it has none) and to **record the
  registration** (number and effective date). Needs `tax:configure`; each change records who and when.
- The default threshold is **$100,000** for every state. That is a common figure, **not a statement of each state's law**:
  thresholds, how they are measured (calendar year or rolling) and whether an order-count test applies differ by state and
  change. Set each state from your tax advisor's advice before relying on the status.
- The tracker measures what AfroTransact itself sells into each state. It does not register anyone and sends nothing to a state.

## Sellers' sales tax statement

Sellers see **Seller Center → Payouts → Sales tax statement**: for a month (last 24 offered), the tax collected on their
orders by state, the refunds, the net, with totals and a CSV download (`GET /v1/seller/tax/statement`, `…/export`). It shows
that AfroTransact collects and remits it as marketplace facilitator, and is for the seller's books, not tax advice. It needs the
same access as Payouts; the underlying numbers are `GET /v1/tax/sellers/{orgId}/statement` in tax-returns.

## Where a transfer is in Stripe

A transfer is the money AfroTransact sends to a seller's Stripe account after the hold (`tr_…`). On a payout request
(Payouts & ledger), the sheet has a table **Money sent to this seller's Stripe account (transfers)** with each order and its
Stripe transfer id, and links *Latest transfer in Stripe* and *This payout in Stripe*. In Stripe: **Connect → Transfers** (test
mode: switch the dashboard to *Test mode* first), or paste the `tr_…` id in the dashboard search. Locally, with no Stripe key, the
stand-in's ids (`…_localtest_…`) do not exist at Stripe and have no link.
