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

## Looking up a Stripe object

Payouts → *Stripe lookup* → *Look up*: a Stripe id (`po_`, `tr_`, `pi_`, `ch_`, `re_`, `txn_`, `dp_`, `acct_`). A payout lives on the
seller's Stripe account, so add the seller's organisation id. The sheet shows Stripe's object and everything the ledger booked
against that id. Locally (no Stripe key) only ids made by the local stand-in (`…_localtest_…`) are found, and they carry no data.

## Lola (staff)

Lola can read all of the above (the payout sheet, differences, adjustments, a Stripe lookup) and explain it. She never proposes,
approves or posts anything; she names the action and opens the record.

## Permissions

| Permission | Who |
|---|---|
| `payments:read` | Look up, read differences and adjustments |
| `payments:configure` | Run a reconciliation day |
| `ledger:adjust` | Explain a difference, propose an adjustment |
| `ledger:approve` | Approve or reject an adjustment (never your own) |
| `payouts:approve` | Approve, hold, release, check with Stripe |

`ledger:adjust` and `ledger:approve` are granted to Super Admin and Platform Finance (identity migration V30). Give them to
separate people if you want the approver to be someone who never proposes.
