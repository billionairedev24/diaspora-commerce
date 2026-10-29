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

## Review moderation

Reviews that failed the automatic checks, and reviews customers or sellers reported, appear in **Catalog, Moderation queue**
as *Product review* items (**Catalog, Product reviews** lists every review). Open one to read the stars, text and photos,
why it is here and who reported it. **Allow** publishes it (or keeps a reported review public); **Reject** or **Remove** takes
it down with a reason the customer sees (abusive, personal information, spam, off topic, conflict of interest, other). Rejected
and removed reviews leave the product's average and count straight away. People and products are shown by name.

## Categories and category requests

You see **Catalog, Categories** and **Category requests** when your role includes the `catalog:taxonomy` permission
(Catalog Manager and Super Admin). Everyone else sees the other Catalog tabs only.

**Categories** shows the tree: departments, the categories in them and the product types (what a seller picks, such as
*Dresses*). The number on each row is the products in it and under it. Retired categories are hidden until you tick
*Show retired*.

- **Add a department, category or product type**: open the place it goes in and choose *Add…*. Every change asks for a
  **reason**, which is kept with your name in the node's **History**.
- **Rename** or **Move**: a rename carries everything inside it; a move takes the products along. *Save* stays off until
  something has changed. If a colleague saved first, you are told to reload instead of overwriting them.
- **Reorder**: use the up and down arrows on a row, then **Save order** and say why. Shoppers and sellers see the new order.
- **Retire**: hides a category (and what is in it) from new listings. It is never deleted, and **a category that still has
  products cannot be retired**: move its product types to another category first. A retired category stays on the
  products that already use it. **Restore** brings it back.
- **Edit its details** (on a product type): this is what sellers fill in. Add, reorder or remove details, choose how
  each is answered (short text, long text, choose one, number with units), where it appears (product details, food,
  regulated), whether it is **required**, and whether to **ask sellers for this at all**. *Brand* is just a detail: a
  Books type has none, and switching it off removes it from the seller's form and upload template. The **What
  sellers will see** panel shows the form as you edit. The sales-tax question belongs to the tax service: you can choose
  whether to ask it, not change its wording. Other product types may share the same details, so the editor says how
  many categories use them.

**Category requests** is the queue of sellers' requests for a category that does not exist (the seller is shown by
name, with the path they asked for and how many of their products are waiting). Open one:

- **Approve & create** asks where it goes (a department creates a category for it too) and whether to reuse existing
  details or start new ones, and lists what will be created before you confirm. The waiting products move into
  review.
- **Reject** needs a reason; the seller sees it and the waiting products are rejected with it.

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
