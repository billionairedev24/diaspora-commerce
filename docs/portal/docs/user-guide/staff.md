---
title: Staff console guide
sidebar_position: 4
---

You only see the menus your role allows. If a page says you have no access, ask an admin to add the permission.

## Your home page

The console opens on one section per area your permissions cover: **Orders & shipments**, **Applications**,
**Payments & payouts**, **Trust & safety**, **Support**, **Catalog** and **Platform health**. Each shows one headline
number, a small chart and quick actions that open the matching filtered list (for example **Review queue** opens the
applications waiting for review). Choose **7**, **30** or **90 days** at the top; your choice is remembered. A queue
that is empty shows a short prompt instead of zeros, and a section whose data could not be loaded offers **Try again**.
Reports with PDF, Excel and CSV export are below the sections.

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

### Who has the application, and for how long

Every row of **Applications** says **who has it** (With), **how long it has waited** (Waiting), whether it is **on
time, due soon or overdue** (SLA) and whether it is **escalated**. You see this for every application, also the ones
you cannot decide; what you may *do* still depends on your role, and the applicant's personal data stays masked
without `applications:pii`. Names are shown, never ids.

The tabs: **My queue** (assigned to you, plus unassigned cases you are allowed to claim), **Awaiting review**,
**Awaiting approval**, **Escalated**, **Returned to applicant**, **All**, and the chips **Overdue** and **Unassigned**.
Search finds a person's name or a stage; any column sorts.

The case sheet starts with *Awaiting review · with Amina Bello · since 2 days* (or *any reviewer*, *any approver*).
Actions: **Claim** (take an unassigned case you may hold), **Assign…** (a supervisor, the person an escalation went to,
or an approver at the approval stage gives it to an eligible person: they hold the step's permission, are not the
applicant and, at approval, did not do the first review), **Release to the pool**, **Escalate now** (with a reason: the
person who has the case, or a supervisor). Every change is on the timeline with the person's name. The applicant is
never told about these internal moves.

When an approver uses **Send back**, the case returns to the review pool with a **Returned by approver** banner (who,
when, why, how many times) at the top.

Roles: **Application Reviewer** (step 1), **Application Approver** (step 2), **Application Supervisor** (sees all,
assigns, takes over, manages escalation; cannot decide unless also given a step role). One person may hold both
reviewer and approver roles; the two-person rule still applies per application. See the
[runbook](../../../runbooks/application-review-workflow.md).

:::tip Who sees what
Notes on approve, flag and send-back are internal. Only the message on *Return to applicant* and on *Reject* is
shown to the applicant. Staff names are never shown to applicants.
:::

## Department requests

A seller can only sell in the catalog departments recorded for its organisation. When a seller asks for another one
(*Need another department? Request it* in Seller Center), the request appears under **Partners, Department requests**
(you see it with the `applications:review` permission; deciding needs `applications:approve`: Super Admin, Operations
Manager and Trust & Safety). The number of requests waiting is the badge on the sidebar entry.

- **Pending** and **Decided** tabs; filter by department, by organisation name (the search box) and by the days the
  request was made. Click a row to open it.
- The request shows who asked, the seller's note, the organisation's current departments and what the department asks
  for (for example beauty labelling; the list comes from each department's compliance data, and Groceries asks for no food
  permit) with whether the organisation's compliance data already has it.
- **Approve** (optional note) adds the department at once. **Reject** needs a reason; the seller reads it.
- One person decides; unlike applications there is no second approver. Every decision is audited (who, what, why).

To change an organisation's departments yourself, open it under **Partners, Sellers & providers**: the **Departments**
section lists them by name, and **Edit** (with `applications:approve`) lets you choose from the catalog's departments.
A reason of at least 10 characters is required. While it is shorter, the field says how many characters are still missing
("Add a few more words: at least 10 characters (7 of 10)"), and when nothing is ticked or unticked yet the dialog says so; **Save**
is only off for one of those two reasons. Every reason box in the console works the same way. Live products in a
department you remove stay live, but their category cannot be changed.

## Suspend or reinstate a seller or provider

Under **Partners, Sellers & providers**, the **⋯** menu of an approved organisation has **Suspend…**, and a suspended one
has **Reinstate…**; the same two buttons are on the organisation's sheet. They need the `sellers:enforce` permission
(Super Admin, Trust & Safety, Operations); without it the menu is empty and the sheet's buttons are greyed out with the
reason.

- **Suspend…** asks for a reason (a list) and what happened (at least 10 characters). At once the organisation's listings
  are hidden from the marketplace, its payouts are paused and it is restricted in the buy box. Open orders are not
  cancelled. The status shows as **Suspended**. AfroTransact Retail cannot be suspended.
- **Reinstate…** asks why it is safe (at least 10 characters). The listings come back and payouts resume. A payout hold
  from Stripe compliance, if there is one (see Stripe Connect), stays until Stripe clears it.
- One person decides; trust-ops writes the audit entry (who, what, why) under Audit log. Suspending an organisation that
  is already suspended changes nothing.

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

### Brands

Under **Catalog, Brands** (same `catalog:taxonomy` permission) you decide which brands sellers can pick. The tab opens
on **Waiting for review**: brands a seller typed in because they were not listed, with the seller's name, how many
products hold the brand, where it applies and when it was asked for. Use the buttons above the table to see approved,
rejected, merged or retired brands, and the search box to find one by name. Open a row to act on it; every decision asks
for a reason and is kept in the brand's history with your name:

- **Approve** offers the brand to sellers wherever it applies (a rejected or retired brand can be approved again).
- **Reject** is for names that are not real brands; products that hold it must pick another brand when next edited.
- **Merge into another brand** is for spelling variants (*Nikee* into *Nike*): search the approved brands, and the sheet
  says how many products move before you confirm.
- **Edit name and scope** renames the brand (listings follow) and chooses the departments, categories or product types
  it applies to. Save stays disabled until something changed.
- **Retire** stops new listings choosing a brand; existing ones keep it.
- **Add a brand** creates an approved brand at once. **Add many** takes a pasted list, one name per line: choose where
  they apply, check the preview (new, already listed, not valid), then add the new ones.

When you edit a product type's details, **Brand** is a kind of answer: sellers search the approved brands of the
category or add one for review. You can also let them choose **No brand** or **Handmade**; the preview shows exactly
what the seller sees.

## Payouts and Stripe

**Payouts** lists every seller payout. Open one for the seller's details, amounts, the Stripe payout/transfer IDs
(with links to the Stripe dashboard) and a **Check with Stripe** action that re-reads the live status.
Differences between our ledger and Stripe appear under **Reconciliation**.

## Ledger adjustments

Fixes to the ledger are made by an **adjustment**, never by editing history. Finance creates it with a reason and an
amount; a **second person approves** before it posts. See the [finance reconciliation runbook](../runbooks/finance-reconciliation.md).

## Delivery runs: what a run earned (Pre-orders, Delivery runs)

Open a location, then a run date in its history, to see the run's profit. Before the run it is an estimate at the orders reserved
so far. After it, it is built from the run's **real orders**:

- **Orders:** delivered, not delivered, cancelled after reserving (these earn and cost nothing) and refunded. "Refunded" counts the
  same way here, in the history table and for a cancelled run.
- **Delivery fees by way of delivery:** door, pickup point and carrier can charge different fees, so the table shows what each way
  actually charged, what was handed back to customers and what was kept. A note says when some orders did not report their fee and
  the run's fee stands in for them (an estimate).
- **Cost and margin:** the cost is an estimate from the route and the driver cost model in Settings, Money, Run economics; once the
  run has been driven it uses the minutes the driver was out. The margin is the delivery fees kept minus the cost, and the
  break-even is worked out at the fee the orders actually paid.
- **Platform fees on goods:** what AfroTransact keeps on the goods of the run's orders, on its own line. It is not part of the
  delivery profit above; use it beside the delivery margin to decide fees and minimums. It comes from the commission payments
  reported, or, for orders payments has not reported yet, from the **Platform fee on goods, per order** estimate in Run economics
  (saved with a reason, like the other numbers). With neither, the line says there is no figure yet.

The location history lists every run with its cost, margin and break-even and whether its minimum covered it, trends orders and
margin per run, lists the pre-orders the location delivered, and shows subscribers who joined, who left and the net each month. When
you create a pre-order, the break-even panel also shows what a full run would earn (or lose) at the location's capacity.

## Published pages: preview, edit and publish a public page

Settings → **Published pages** (also the first tab of Site content) lists every page shoppers can read, with its live link (opens the storefront in a
new tab), when it was last changed and by whom. Legal documents and help pages are changed here, without a developer:

1. **Preview** shows the page as shoppers read it. Switch the language (a missing translation shows the English text with a notice) and the width
   (desktop, tablet, phone). A draft for counsel shows the banner, and any `[[LEGAL: …]]` text is highlighted and counted.
2. **Edit** has a toolbar (headings, bold, lists, links, tables), accepts text pasted from Word or Google Docs, and shows the page beside the text as you
   type. The checks list repeated section names and links that go nowhere. **Save draft** changes nothing public.
3. **Publish** asks for a reason. A legal document is published by a *second* person with the Approve level: the person who saved the last change cannot
   approve it. A final document (banner off) cannot be published while a `[[LEGAL: …]]` marker is left.
4. **History** lists every version with who, when and why, compares any two, and goes back: a help page republishes the old text; a legal document starts a
   new draft from it.

Careers roles and the cookie table are tabs of Site content. The About page still needs a developer; the index says so. Runbook:
`docs/runbooks/editing-published-pages.md`.

## Settings

All configuration lives in one place: **Settings** (sidebar). Sections include General (including the Help
center articles), Fees, Markets, Notifications, Developers and Tax. See the [settings runbook](../runbooks/settings.md).
The **Sales tax** section holds the tax tools (below).

## Sales tax: classification, review and providers

**Settings → Sales tax** has one tab per job. Reading needs `tax:read`; approving, moving a product to another category,
changing a category's status and editing provider codes need `tax:classify-admin`; changing who calculates tax needs
`tax:configure`. Every change asks for a reason of at least 10 characters (the rule shows as you type) and is kept with your
name. The tax rules are a **draft** until a Texas tax professional signs them off: nothing here goes to real customers before that.

- **Who calculates tax** explains each choice in plain words (our own rules, Stripe Tax, Avalara) and the **shadow mode**: a
  provider that is asked for the same lines on real orders and compared, never charged. A provider that is not set up, or whose
  codes are not all verified, cannot be chosen, and the page says why.
- **Tax profiles** lists the 73 categories with a plain status: *Ready*, *Needs tax sign-off*, *Needs a specific provider code*,
  *Blocked until bundles are itemised* or *Waiting on a decision*. Filter by department and status. Open one to read the questions
  sellers are asked, the rules as sentences (for example "If net weight is at least 2.5 oz and servings per package is more than 1,
  then exempt"), the notes flagged for the tax professional and the history. **Mark approved** needs the tax professional's name
  and the reference of their sign-off; **Reopen for approval** takes a category back. A category that needs a specific provider
  code is lifted product by product in Tax review, so the profile is read only.
- **Tax review** is the queue of products whose tax waits for a person: the product, the seller, the category, what the seller
  entered, the proof they attached, the proposed decision and why it needs review. **Approve**, **Move to another category** or
  **Ask the seller for more**. The **Decisions** view lists who decided, when and under which rule version.
- **Provider codes** shows, for Stripe and Avalara, the code for each outcome with whether a person verified it, and how many
  outcomes still need a code. A line with no verified code is blocked at checkout, never guessed. Export the map to CSV, or import a
  CSV: you see what would change first, and a file with a bad row is not applied at all.
- **Shadow report** compares a provider's quote with ours on real orders and says whether it is ready to cut over, check by check
  (long enough, enough orders, same exempt or taxable answer, reliable, every code verified). The steps to move to a provider are in
  the cutover runbook (`docs/runbooks/tax-provider-cutover.md`).

Lola can answer questions about the review queue and the shadow report; she only reads, she never approves.

## Maintenance mode

Before planned work, turn on **Settings → General → Maintenance mode**. Customers (and sellers, if you pick that)
see a page with your message and the time you expect to be back; staff keep working. Remember to turn it off. See the
[maintenance runbook](../runbooks/maintenance-mode.md).

## Lola (assistant)

Lola can look up applications, payouts, orders and settlements for you, read-only. Ask in plain language, for
example "why is payout po_… still in transit?". Lola cannot approve, pay or change anything.
