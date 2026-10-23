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

## Where a transfer is in Stripe

A transfer is the money AfroTransact sends to a seller's Stripe account after the hold (`tr_…`). On a payout request
(Payouts & ledger), the sheet has a table **Money sent to this seller's Stripe account (transfers)** with each order and its
Stripe transfer id, and links *Latest transfer in Stripe* and *This payout in Stripe*. In Stripe: **Connect → Transfers** (test
mode: switch the dashboard to *Test mode* first), or paste the `tr_…` id in the dashboard search. Locally, with no Stripe key, the
stand-in's ids (`…_localtest_…`) do not exist at Stripe and have no link.
