---
title: Staff console guide
sidebar_position: 4
---

You only see the menus your role allows. If a page says you have no access, ask an admin to add the permission.

## Review a seller application

Open **Applications** and pick one. The page shows the business, documents, Stripe verification and the timeline.

Reviews use **two steps** and the final approver must be a different person than the reviewer.

**Step 1 (reviewer)**

- **Approve** → passes to a final approver.
- **Return to applicant** → asks the seller to fix something. Tick the items and write a message; **the seller sees
  this message**.
- **Flag for senior review** → you are unsure (failed checks). The applicant is **not told**; it stays "In review"
  for them. Your note is internal.

**Step 2 (final approver)**

- **Final approve** → the seller can start selling.
- **Reject** → the seller is told; the note you write is shown to them.
- **Return to applicant** → use this for a flagged application that only needs a correction.
- **Send back to review** → return to step 1 (internal).

:::tip Who sees what
Notes on approve, flag and send-back are internal. Only the message on *Return to applicant* and on *Reject* is
shown to the applicant. Staff names are never shown to applicants.
:::

## Payouts and Stripe

**Payouts** lists every seller payout. Open one for the seller's details, amounts, the Stripe payout/transfer IDs
(with links to the Stripe dashboard) and a **Check with Stripe** action that re-reads the live status.
Differences between our ledger and Stripe appear under **Reconciliation**.

## Ledger adjustments

Fixes to the ledger are made by an **adjustment**, never by editing history. Finance creates it with a reason and an
amount; a **second person approves** before it posts. See the [finance reconciliation runbook](../runbooks/finance-reconciliation.md).

## Settings

All configuration lives in one place: **Settings** (sidebar). Sections include General (including the Help
center articles), Fees, Markets, Notifications, Developers and Tax. See the [settings runbook](../runbooks/settings.md).
The **Tax** section switches between our own rules and Stripe Tax.

## Maintenance mode

Before planned work, turn on **Settings → General → Maintenance mode**. Customers (and sellers, if you pick that)
see a page with your message and the time you expect to be back; staff keep working. Remember to turn it off. See the
[maintenance runbook](../runbooks/maintenance-mode.md).

## Lola (assistant)

Lola can look up applications, payouts, orders and settlements for you, read-only. Ask in plain language, for
example "why is payout po_… still in transit?". Lola cannot approve, pay or change anything.
