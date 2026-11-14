# Detail sheets and fixed-row tables (staff console)

Every staff page with a record's detail (Tax review, Tax profiles, Orders, Payouts, Sellers, Disputes, Department requests, ...) uses
the same three pieces. They were fixed together after the owner's feedback on Sales tax > Tax review.

## The sheet never fights the menu

`Sheet` (`web/packages/ui/src/overlay/Dialog.tsx`) is a docked, non-modal right sheet: the sidebar stays clickable, Escape and the
close button close it, a click on the page outside it closes it, and focus returns to what opened it.

**What went wrong.** The sheet closes on the *pointer-down* of an outside click. The pages answered that with `router.replace(own
url)` built from the `searchParams` of the last render. On a click in the sidebar the order is pointer-down (close, `replace('/tax')`),
then the link's click (`push('/payouts')`); the first write won, so the click did nothing and the sheet "blocked" the menu, and a
stale write could bring `?variant=` back.

**Now.**
- The sheet ignores outside interactions that land on a link (`a[href]`, or anything marked `data-sheet-keep-open`): the link
  navigates and the page's unmount is the close.
- `useSheetUrl(key, extraKeys)` (`web/apps/staff-console/src/lib/use-sheet-url.ts`) is how a page keeps its open item in the URL:
  - opening from the list *pushes* a history entry, so **Back closes the sheet first**; switching item or an action inside it only replaces;
  - closing steps back over that entry (or drops the parameters when the page was opened by a deep link);
  - every write reads `window.location` now (not a render's copy) and does nothing once the route changed.
- Switching a tab of a page drops the sheet parameters (`variant`, `profile`, `open`, `do`), so coming back never reopens a stale sheet.
- Used by Tax review, Tax profiles, every `ResourceTable` (`?open=`) and Department requests. A new sheet that lives in the URL uses the same hook.

Right sheets are `inset-inline-end` (mirrors in RTL) and `min(820px, max(560px, 52vw))` wide, over an unchanged table.

## Rows that line up

`DataTable` (`web/packages/data-table`):
- cells are vertically centred; header and body share the same horizontal padding; the header is sticky;
- `rowHeight={n}` fixes the row height on wide layouts (cards on phones ignore it); text cells are one line with an ellipsis and the
  full text as a tooltip; `CellText` (`lines`, `mono`) and `ItemCell` (40px photo or a plain flat box, bold name on up to two lines,
  one muted line under it) are the cell helpers;
- column widths are plain percentages of the flexible space. (`calc(% - px)` in a `<col>` is ignored by some engines, which gave
  every column the same width.)

Tax review uses `rowHeight={72}`, Profiles 56, Provider codes 60.

## Tax review: name, photo, who decided

- The queue and the sheet read the product's name, the variant in words and the photo from catalog by the **variant ATIN**
  (`GET /v1/variants/{atin}`: product and variant), falling back to the product id tax-returns remembers. Tax records are keyed by
  variant ATIN (`ATH0ZXZ594`); the product has its own (`ATH1GHCM73`), which is a page address and never a key. Records made before
  catalog's event carried `title` and `productId` have neither, which is why the Item column was blank.
- "Decided by", "Changed by" and the staff moves in History are resolved from identity; the signed-in reviewer is named from their
  session when identity refuses the user read (most reviewers lack `access:read`); a person identity cannot name reads
  **Former staff member**, never "Unknown user". SYSTEM actors read "System"; the automatic approval keeps its wording.
- The notice on an item says **Why it needs review** only for items that wait for a person (pending, stale, missing facts, draft);
  an approved or rejected item says **Status**; the audit sample keeps **Why it is in the audit sample**.

## Tests

- `web/apps/staff-console/test/tax-sheet-lifecycle.test.tsx` (vitest): state handling around the sheet, Status vs Why it needs review, name and variant.
- `tests/e2e/specs/staff-console.sheet-lifecycle.spec.ts` (Playwright, mock gateway): the real pointer order. Open a sheet, click another
  menu item, the page changes and the sheet is gone; come back, plain list; Back closes first; Escape; deep link; tabs; Orders and
  Payouts; fixed row height and centred cells. It fails on the old `Sheet`.
- `go/services/gateway-bff/.../staff_tax_test.go`: `TestStaffTaxReviewNamesTheItemByVariantATIN` (distinct product and variant
  ATINs; fails on the old lookup) and `TestStaffTaxReviewNamesWhoDecided`.

Screenshots (mock gateway) are in `docs/engineering/img/tax-review-{1280,390}-*.png`.
