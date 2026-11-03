# Finance: payouts, reconciliation and ledger adjustments

For Finance staff in the staff console, **Payouts & ledger**. The ledger is double-entry and immutable: nothing is ever edited,
a mistake is corrected by posting a new journal. Money-changing steps need two people.

## Approving a payout

Open the request (Payouts → *Payout requests*). The sheet shows the seller and who asked, then **Before you approve**:

| Check | If it says No |
|---|---|
| Stripe account on file | Payments has no connected account for the seller; approving fails. Staff: Stripe Connect → *Sync all accounts with payments*. Seller: finish Stripe onboarding in Account health. |
| Stripe verified / can pay out | Stripe has not finished verifying the seller; they must resolve Account health. |
| No compliance hold | Payouts are paused on the seller (Stripe Connect → *Release payout hold* once resolved). |
| Bank account | No bank on the Stripe account. |

*Approve & pay* creates the Stripe payout. Once it is **on the way**, the sheet shows **Live from Stripe**. If Stripe says it was
paid or failed but we still show it on the way (a missed webhook), the sheet says so: use **Check with Stripe**, which closes the
request to match.

## Daily reconciliation

Payments compares, per Stripe object, what the ledger booked on **1000 Stripe clearing** with Stripe's balance transactions. It
runs every night for the day before; to run any day now: Payouts → *Daily runs* → *Reconcile a day* (needs `payments:configure`).

Every object that differs becomes an item under *Differences* (ledger minus Stripe; positive means the ledger holds more).

For each item, read **What Stripe says now** and **What the ledger booked**, then choose:

1. **Mark as explained** (`ledger:adjust`) when it is not an error: a fee that settles the next day, a timing gap. Write why (10+
   characters). Nothing is posted. A later run of the same day that finds the same difference keeps it explained.
2. **Propose a correction** (`ledger:adjust`) when the ledger is wrong. The sheet fixes the amount (the difference) and the
   1000 Stripe clearing side; you pick the account the other side is booked against and say why (15+ characters). Seller payable
   (2000) needs the seller's organisation id; sales tax (2100) needs the jurisdiction.

## Ledger adjustments (four eyes)

A proposal waits under *Ledger adjustments*. **A different person** with `ledger:approve` reviews it:

* **Approve and post** posts journal `adjustment:<id>`, booked against the Stripe object so the next run sees it. The item becomes
  *Adjusted*. You cannot approve your own proposal (403 `payments.adjustment-four-eyes`).
* **Reject** with a reason. The proposer may withdraw their own proposal this way.

A standalone correction (not tied to a difference) is *Propose an adjustment* on the same tab. A line on 1000 Stripe clearing
needs the Stripe object it corrects. Posted adjustments cannot be edited; fix a wrong one with a new adjustment.

After approval, **run the day again** (Daily runs): the difference should be gone. If it carries on it appears as a new item.

## Refunds Stripe would not take back (Refund recoveries)

Sellers are paid by transfer after delivery and their payouts are manual, so by the time an order is refunded the seller may already
have been paid out. Payments asks Stripe to reverse the transfer; when Stripe **definitively refuses** (usually
`balance_insufficient`) the customer's refund still completes and the amount becomes a *refund recovery*: money the seller owes the
platform. It is under Payouts → *Refund recoveries*, by seller and order number, with Stripe's code and what is still owed. (A
timeout or Stripe outage is not a refusal: the refund fails and is retried under the same key, as before.)

What the ledger shows:

| When | Journal | Effect |
|---|---|---|
| Stripe refuses | `TRANSFER_RECOVERY_OPENED`: Dr 2000:org / Cr 2000:org on the order | The refund's debit moves from the order to the seller's unattributed payable, which goes **negative** (what the seller owes). 1000 Stripe clearing is untouched: no money came back. |
| A later transfer of the seller | `TRANSFER_RECOVERY_SETTLED`: Dr 2000:org on that order / Cr 2000:org | The transfer is smaller by the amount taken, oldest recovery first and before plan fees and label charges. The recovery turns *Recovered* when paid. |
| Finance: *Mark recovered* | `TRANSFER_RECOVERY_RECEIVED`: Dr 1050 Operating bank / Cr 2000:org | The seller paid by bank transfer. |
| Finance: *Write off* | `TRANSFER_RECOVERY_WRITE_OFF`: Dr 5300 Transfer recovery losses / Cr 2000:org | The platform absorbs the rest. |

The seller's payout balance (Seller Center and `GET /v1/payouts/balance`) deducts the amount owed from *available* in full, before any
new payout, and shows it as `transferRecoveriesMinor`.

**You normally do nothing**: the seller's next transfers pay it back. Act only when they will not (the seller has nothing coming):

1. Open the recovery (*Refund recoveries*, filter *Open*). Read *Stripe said*, *Still owed* and *Recovered from later transfers*.
2. **Mark recovered** (`ledger:adjust`) when the seller paid outside Stripe: give the bank reference and why (15+ characters).
   **Write off** (`ledger:adjust`) when it will not be recovered: say why (15+ characters).
3. This is a ledger posting, so it needs four eyes. The proposal waits (*Waiting for* on the row) and a **different** person with
   `ledger:approve` uses **Approve and post** or **Reject** (with a reason; the recovery stays open). You cannot approve your own
   proposal (403 `payments.recovery-four-eyes`). Only one proposal can wait at a time. Posted resolutions cannot be edited.
4. Approving closes what is still owed at that moment: if a transfer recovered part of it in the meantime, only the rest is booked.

A reversal made by hand in the Stripe dashboard is not matched to a recovery: it is booked to the order's payable by the
`transfer.reversed` webhook. Do not use it to settle a recovery; see the `TransferReversalRefused` runbook. Alerts:
`TransferReversalRefused` (a refusal in the last hour) and `TransferReversalRefusedUnexpectedly` (any code but
`balance_insufficient`; counters `payments_transfer_reversals_refused_total{code}` and
`payments_transfer_recoveries_total{outcome}`).

## Looking up a Stripe object

Payouts → *Stripe lookup* → *Look up*: a Stripe id (`po_`, `tr_`, `pi_`, `ch_`, `re_`, `txn_`, `dp_`, `acct_`). A payout lives on the
seller's Stripe account, so add the seller's organisation id. The sheet shows Stripe's object and everything the ledger booked
against that id. Locally (no Stripe key) only ids made by the local stand-in (`…_localtest_…`) are found, and they carry no data.

## Lola (staff)

Lola can read all of the above (the payout sheet, differences, adjustments, refund recoveries, a Stripe lookup) and explain it. She never proposes,
approves or posts anything; she names the action and opens the record.

## Permissions

| Permission | Who |
|---|---|
| `payments:read` | Look up, read differences and adjustments |
| `payments:configure` | Run a reconciliation day |
| `ledger:adjust` | Explain a difference, propose an adjustment, propose closing a refund recovery |
| `ledger:approve` | Approve or reject an adjustment or a refund recovery resolution (never your own) |
| `payouts:approve` | Approve, hold, release, check with Stripe |

`ledger:adjust` and `ledger:approve` are granted to Super Admin and Platform Finance (identity migration V30). Give them to
separate people if you want the approver to be someone who never proposes.
