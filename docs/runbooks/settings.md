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
| Sales tax | **who calculates tax** (switch), state and local rates, what each item is for tax | Fees |
| Plans & billing | how sellers pay their plan fee | Plans |

The old addresses (`/fees`, `/markets`, `/site-content`, `/developers`, `/notifications`) open the matching section.
Day-to-day money work stays where it was: Payouts & ledger (approvals, reconciliation, adjustments) and Plans & billing (plan
catalogue, trials, rewards, each seller's billing).

## Who calculates sales tax

Sales tax is worked out by **our own rules** by default: the state and local rates (Tax rates) and what each item is for tax
(Tax classification, including items that are never taxed). Nothing is sent to a tax service and nothing costs per order.

Settings → Sales tax → *Who calculates tax* shows the method in force. To switch, open the row, choose the method and give a
reason (10+ characters); the change is recorded in the history. **Stripe Tax** can only be chosen once it is set up (its key is set
and registrations are done in the Stripe dashboard); until then it is refused and our own rules keep answering. A change applies
to new quotes within seconds; placed orders keep the tax they were charged. Needs `tax:configure`.

`TAX_PROVIDER` (`RULES` by default) is only the starting value before anyone chooses.

## Tax filings: what we filed and paid to each state

AfroTransact collects sales tax as marketplace facilitator, so it files and pays it to the states. **Settings → Sales tax →
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
- This is a record, not a filing: nothing is sent to a state. Filing, nexus registrations, exemption certificates and the
  seller tax statement screen are still manual or not built.
- Only states where AfroTransact is the facilitator and has collected tax appear. The seller's monthly statement of tax
  collected on their behalf is `GET /v1/tax/sellers/{orgId}/statement` (no screen yet).

## Where a transfer is in Stripe

A transfer is the money AfroTransact sends to a seller's Stripe account after the hold (`tr_…`). On a payout request
(Payouts & ledger), the sheet has a table **Money sent to this seller's Stripe account (transfers)** with each order and its
Stripe transfer id, and links *Latest transfer in Stripe* and *This payout in Stripe*. In Stripe: **Connect → Transfers** (test
mode: switch the dashboard to *Test mode* first), or paste the `tr_…` id in the dashboard search. Locally, with no Stripe key, the
stand-in's ids (`…_localtest_…`) do not exist at Stripe and have no link.
