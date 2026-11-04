# Table actions

One pattern for what a person can do to a row of a table, in the storefront account pages, the Seller Center and the
staff console. It is the pattern of Stripe, Shopify, Amazon Seller Central, GitHub and Material, so nobody has to learn
it twice. Build it from these parts and do not draw your own row buttons.

| Part | Package | What it is |
|---|---|---|
| `RowName` | `@afrotransact/ui` | The first cell: the row's name as the **primary link** (open or edit), an optional status chip beside it and a quiet meta line under it (ID, SKU, category). |
| `RowActions` | `@afrotransact/ui` | The trailing "⋯" button and its menu. |
| `rowActionsColumn` | `@afrotransact/data-table` | The table column that holds `RowActions`: fixed 56 px, never sortable, hideable or exported, and in the corner of the card on phones. |
| `ConfirmDelete` | `@afrotransact/ui` | The confirmation of a destructive action, in the shared `Dialog`. |
| `InlineEdit` | `@afrotransact/ui` | A click-to-edit cell (price, stock) with Save and Cancel. |
| Selection bar | `DataTable` `bulkActions` | Bulk actions appear when rows are ticked. |

## The rules

1. **The name is the primary action.** Clicking it (or the row, when the table has `onRowClick`) opens the detail or the
   editor. A row with exactly one safe action needs nothing more than `RowName`.
2. **Everything else is in the "⋯" menu**, one trailing column per table, built with `rowActionsColumn`.
3. **Destructive items** (Delete, Discard, Withdraw, End, Retire, Remove) are marked `danger`. The menu puts them last,
   below a separator, in red. They never run on select: they open `ConfirmDelete`.
4. **Quick edits** of a number the seller changes all the time (price, stock) are `InlineEdit` cells: a dotted
   underline and a pencil on hover or focus, Enter saves, Esc cancels, and the problem shows under the field.
5. **Bulk actions** go in the selection bar (`bulkActions`), not on every row.
6. **No stacked outline pill buttons per row, no icon-only buttons without a name, no pencil next to a number.**

## `RowActions`

```tsx
rowActionsColumn<ListingRow>({
  header: t('sc.rows.actionsHeader'),                 // read by screen readers and the Columns menu
  name: (r) => r.title,
  label: (r) => t('sc.rows.actionsFor', { name: r.title }), // "Actions for Cotton kaftan"
  items: (r) => [
    { label: t('sc.listings.edit'), icon: <PencilSimple size={16} aria-hidden />, onSelect: () => go(editHref(r)) },
    r.status === 'LIVE' && { label: 'View on storefront', href: pdpUrl(r), external: true },
    r.status === 'TAKEN_DOWN' && { label: 'Resume', disabled: 'Taken down by moderation' },
    'separator',
    { label: 'Discard', danger: true, onSelect: () => setDiscarding(r) }
  ]
})
```

An item is `{ label, icon?, onSelect?, href?, external?, danger?, disabled? }`. `disabled` is `true` or a **reason**: the
reason shows under the label and as a tooltip, so a person can tell why. Falsy entries are skipped (`cond && {...}`); a
row with no items at all draws no button.

What you get without asking:

- The button is named "Actions for *row name*", has `aria-haspopup="menu"` and `aria-expanded`.
- Enter, Space or Down open it; the arrows, Home and End move; Esc closes and returns focus to the button.
- The menu is rendered in a portal, so the table's scroll area never clips it; it flips at the viewport edge, and
  closes on an outside click or a scroll.
- The button is 32 px on a mouse, 44 px on touch (`--at-size-control-sm`); items are 44 px tall.
- It reads the page's `dir`: in Arabic the menu opens towards the start edge and the arrows follow.
- Clicks inside the menu never reach the row's `onRowClick` (React events bubble out of portals; `DataTable` stops them).

Keep the **handlers you already had**: permission gating stays in `items` (`editable && {...}`), and a confirmation that
already existed stays.

## `ConfirmDelete`

```tsx
<ConfirmDelete
  open={discarding !== null}
  onOpenChange={(o) => (o ? undefined : setDiscarding(null))}
  title={t('sc.drafts.discardTitle', { title })}   // says what is affected
  description={t('sc.drafts.discardBody')}          // says what happens, in plain words
  verb={t('sc.drafts.discard')}                     // the red button is the verb, never "OK" or "Yes"
  cancelLabel={t('sc.rows.cancel')}
  closeLabel={t('sc.rows.close')}
  pending={busy}
  onConfirm={discard}
/>
```

Cancel has the focus when it opens, so Enter never destroys anything by accident. Bulk discards use the same dialog
with a count in the title and run one request per row with a summary (`runForEach`).

## Do and don't

| Do | Don't |
|---|---|
| `RowName` with the edit link, a chip and the ATIN or SKU as meta | A bold name that does nothing, plus an "Edit" button in the last column |
| One "⋯" column from `rowActionsColumn` | Two or three outline buttons ("Edit", "Pause") stacked in a cell |
| `danger` + `ConfirmDelete` for Delete, Discard, Withdraw, End, Retire | A red link that deletes on the first click |
| `disabled: 'reason'` for something that exists but is not possible now | Hiding the item with no explanation (unless the person may never do it) |
| `InlineEdit` for price and stock | A pencil icon beside a number |
| Bulk actions in the selection bar | The same button repeated on 25 rows |
| Strings from the app's catalogue in all five locales (en, fr, es, ar, yo) | English labels in `items` for new tables |

## Where it is used

Seller Center: Listings, Drafts, Coupons, Team, Size charts, Image library, Invoices; Services (name link only).
Staff console: Plans, Rewards, Trials, Seller invoices, Brands, Fee schedules, Custom rates, Published pages.

Left as they were, because their per-row control is not a CRUD action: Orders (a workflow step button that moves the
order forward), Fulfilment shipments and the staff inventory, retail and receiving panels (detail forms, not row
buttons), and the staff select buttons that open a side panel (Runs, Pickups, Search "Add synonym"), which are plain
link-style buttons now rather than outline pills.
