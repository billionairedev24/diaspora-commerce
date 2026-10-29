# TransferReversalRefused

**Severity:** `ticket` · **Defined in:** `infra/observability/rules/payments-transfer-recovery.yaml` · **Dashboard:** Grafana → AfroTransact / Platform overview

Alerts: `TransferReversalRefused` (any refused reversal in the last hour) and `TransferReversalRefusedUnexpectedly` (a refusal whose
Stripe code is not `balance_insufficient`). Metrics: `payments_transfer_reversals_refused_total{code}` and
`payments_transfer_recoveries_total{outcome="opened|settled|received|written_off"}`.

## What it means
A customer was refunded after the seller's earnings for that order were already transferred. Payments asked Stripe to reverse the
transfer and Stripe said no for good (a 4xx; usually `balance_insufficient`: the seller's Stripe balance was already paid out, and
sellers' accounts have no Stripe dashboard and manual payouts, so only payments-service moves that money). The **refund still
completed**; the amount is now a *transfer recovery*: money the seller owes the platform. Transient failures (5xx, timeouts, rate
limits) are not refusals: those still fail the refund, which is retried under the same Idempotency-Key.

## Impact
No customer impact. The platform has paid out money it has not got back yet: the loss is bounded by the recovery's amount. The ledger
shows it as a **negative seller payable** (2000:org), and payments nets it against the seller's next transfers before anything else, so
no staff action is needed when the seller keeps selling.

## Diagnose
1. Staff console → Payouts → *Refund recoveries* (status *Open*): seller, order, amount, **Stripe said** (the code), what is still owed.
2. `balance_insufficient`: expected. How much does the seller have coming? *Payout requests* and the seller's Stripe Connect page show
   what is on hold and in flight. A seller with nothing coming will not repay by netting.
3. Another code (`transfer_already_reversed`, `resource_missing`): it is our data, not the seller's balance. Look the transfer up
   (*Stripe lookup*, `tr_…`) and compare with the order's transfers in payments. A reversal made in the Stripe dashboard shows up as a
   `transfer.reversed` webhook and is booked back to the seller's payable; if the dashboard and the refund both reversed, the
   ledger and Stripe disagree: use a ledger adjustment (see *finance-reconciliation*).
4. 401 or 403 from Stripe is not a refusal and fails the refund instead (a bad or restricted key): that is a `HighErrorRate` problem, not this one.

## Mitigate
- **Nothing, if the seller still has earnings coming**: the next transfer is smaller by the amount owed (Finance sees a *Refund recovery*
  deduction), the recovery turns *Recovered* by itself and the seller's payout balance shows it meanwhile (`transferRecoveriesMinor`).
- **The seller paid back outside Stripe** (bank transfer): *Mark recovered* with the bank reference. Posts Dr 1050 Operating bank /
  Cr 2000:org once a different Finance person approves it.
- **It will not be recovered** (the seller left): *Write off* with the reason. Posts Dr 5300 Transfer recovery losses /
  Cr 2000:org once a different person approves it. Both need `ledger:adjust` to propose and `ledger:approve` to approve.
- Do not reverse the transfer by hand in the Stripe dashboard to "fix" it: that reversal (`transfer.reversed` webhook) is booked to
  the order's payable, not matched to the recovery, so the recovery stays open and the two then have to be squared with a ledger
  adjustment.

## After the incident
- Link the incident ticket to this runbook and update the steps if they were wrong or incomplete.
- If the alert was noisy, tune the threshold in the rule file (then `make rules`) rather than silencing it permanently.
