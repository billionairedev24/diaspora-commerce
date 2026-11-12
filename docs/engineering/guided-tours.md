# Guided tours

First-run walkthroughs for the Seller Center / Provider Hub and the staff console. They are built with `@afrotransact/tour`
(`web/packages/tour`), on Driver.js (MIT) behind our own interface. Why that library and not Shepherd.js or Intro.js (both AGPL with a
paid commercial licence): `docs/adr/0008-guided-tours-library.md`.

## Where the pieces are

| Piece | Where |
|---|---|
| Tour definition schema (Zod 4), `defineTour`, `tourText` | `web/packages/tour/src/schema.ts` |
| Who sees a tour (roles, permissions, flags) | `src/audience.ts` |
| Progress per user, tour and version (localStorage, guarded) | `src/progress.ts` |
| `TourService` interface and implementation (steps, routes, skipping, auto-start, drift events) | `src/service.ts` |
| `TourEngine` interface (the seam to the library) and the Driver.js engine | `src/engine.ts`, `src/driver-engine.ts` |
| Page access (targets, viewport, open dialogs, unsaved forms) | `src/environment.ts` |
| `<TourProvider>`, `useTour`, `useTourLauncher`, `<TourHelpMenu>` | `src/react.tsx` |
| Styling with tokens (popover, overlay, focus ring, bottom sheet, forced colors) | `src/tour.css` |
| Seller Center tours, subject, guards | `web/apps/seller-center/src/tours/`, host `components/shell/SellerTourHost.tsx` |
| Staff console tours, subject, guards | `web/apps/staff-console/src/tours/`, host `components/shell/StaffTourHost.tsx` |
| Drift guard (Playwright) | `tests/e2e/specs/seller-center.tours.spec.ts`, `staff-console.tours.spec.ts`, `tests/e2e/support/tours.ts` |

## Adding a tour

1. **Put `data-tour` ids on the real components.** A step points at its target only by `data-tour="<id>"` (lower-case words joined
   by `-` or `.`). Never a CSS selector, a class or a generated id: the schema rejects them. Shared helpers already give you
   `page-<module>` (page header) and `page-<module>-actions` (its buttons), `nav-<module>` (menu entries), `sidebar`, `org-switcher`,
   `table-<table id>` (every `DataTable`), `help-menu`. Add a new id with one attribute on the element; do not wrap things only to
   attach it (it can break page spacing).
2. **Write the tour** in the app's `src/tours/*-tours.ts` with `defineTour` (it validates at module load, so a mistake fails the tests,
   not a customer):

   ```ts
   defineTour({
     id: 'inventory',                       // unique in the app
     version: 1,                            // bump to show it again to people who finished it
     title: 'sc.tour.inventory.title',      // i18n keys, never text
     summary: 'sc.tour.inventory.summary',  // the line under the title in the Help menu
     audience: { permissions: ['inventory:view'] },   // see "Who sees a tour"
     route: '/inventory',                   // where it starts when replayed from Help
     autoStart: { routes: ['/inventory'] }, // first-time start (optional); '/onboarding/*' means a prefix
     steps: [
       { id: 'page', target: 'page-inventory', placement: 'bottom', ...stock('page') },
       { id: 'count', target: 'inventory-adjust', optional: true, ...stock('count') },   // data-dependent target
       { id: 'wrap', placement: 'center', ...stock('wrap') }                               // no target: centred
     ]
   })
   ```

   Step fields: `target`, `placement` (`top`, `bottom`, `start`, `end`, `center`; start/end follow the reading direction), `route`
   (go to this page first; later steps without a route stay on it), `when` (name of a guard the app registers in `subject.ts`; false
   skips the step), `optional` (the target depends on data, its absence is normal), `success` (`{ target, when: 'click' | 'appear' |
   'disappear' }`: shows a quiet "Done" mark when the person did the thing; never blocks Next).
3. **Register it** in the app's tour list (`SELLER_TOURS`, `STAFF_TOURS`).
4. **Words.** Add the keys `<prefix>.<stepId>.title` and `.body` for the tour and every step to `en.json` and the four other catalogues
   (see "Translations").
5. **Drift guard.** Add the tour to the list in the app's `*.tours.spec.ts` with the number of steps it shows today.
6. Run `pnpm --filter <app> test`, `pnpm --filter @afrotransact/tour test` and the tour spec.

### Who sees a tour

The app turns its session into a `TourSubject` (`subject.ts`): an opaque user id, role keys, permissions and flags. Nothing personal.
Permissions are `<module>:<level>` (levels `view`, `edit`, `approve`, `full`; each includes the ones before it) plus the platform
permissions the staff session lists. The audience has `roles` (any of), `permissions` (all of), `anyPermissions` (at least one) and
`flags` (all of: `seller`, `provider`, `approved`, `applying`, `offers-services`, `staff`). The Help menu lists only the tours the
person's audience admits, and starting one by hand re-checks it. A staff member therefore never sees a tour about something they
cannot open.

## Rules for good tour content

- **Plain words.** Short sentences, no jargon, no codes or ids. Say "Add a product", not "create a listing record".
- **Names, not ids.** Use the label on screen (the button's own words). Never print a tax code, an order id or a status code.
- **Say only what exists today.** If a feature is a draft or a plan, leave it out. Check the screen, not the design.
- **No carriers, and no "AfroTransact collects and delivers"** wording. Describe what the person does.
- **One idea per step**, a title of a few words and a body of one to three sentences. Aim for four to six steps. A tour is a map, not a manual.
- **Warn where it matters.** If a click has consequences (marking a package picked up charges the customer), say so plainly.
- **Never block.** The tour can always be skipped (Skip tour, Esc, the X). Do not write steps that need the person to do something first.
- **Targets must exist for the person.** If a screen only shows a thing sometimes (a banner, a row), mark the step `optional`.

## Behaviour (what the service guarantees)

- **Accessible:** the popover is a named dialog; focus goes to its primary button and returns to where it was on close; Tab stays
  inside the popover and the highlighted element; Esc closes; arrow keys move (ArrowLeft goes forward in Arabic); a polite live
  region announces "Step n of N, title, text"; Skip, Back/Next and the step counter are always visible. Reduced motion turns
  animation and smooth scroll off. Forced-colors and `prefers-contrast: more` have their own rules. Targets are scrolled into view.
- **Right to left:** `dir` is set on the popover from the page; `start`/`end` placements and the arrow keys follow it.
- **Narrow screens:** under 640 px the popover is a bottom sheet and a tour never starts by itself.
- **Missing target at run time:** the step is skipped quietly and logged to the console in development only. The step counter
  counts only the steps that are shown.
- **Auto-start, once:** the first time a person reaches a tour's page (and has not seen this version) and only when: the page has
  loaded and the first step can be drawn; no dialog, menu or sheet is open; no form holds typed text; the person is not typing in a
  field; the screen is at least 640 px wide; the browser is not automated; and no other tour started by itself in this visit. It is
  recorded as seen the moment it starts, so a reload never repeats it. "Don't show again" (in the popover) keeps it away for good.
- **Progress:** `localStorage` key `at.tour.v1:<userId>:<tourId>:<version>` holding `{ outcome: completed | skipped | never, at }`.
  Every read and write is in try/catch, so private windows and blocked storage just mean "nothing saved". A new `version` is a new
  key. Help → "Show tours again automatically" forgets everything for the person.
- **No analytics, no PII.** Nothing is sent. The service dispatches a local DOM event `at-tour` (step shown, tour ended) that only the
  end-to-end tests listen to.
- **Server-side progress** is a documented port only (`TourProgressRemote` in `src/progress.ts`): no backend, no implementation.
  Whether to add one is an owner decision.

## How drift is caught

A tour breaks silently when a screen changes and the `data-tour` attribute disappears. Three guards:

1. The schema refuses anything that is not a `data-tour` id, so tours cannot lean on styles.
2. `pnpm test` in each app checks every tour's keys exist in all five catalogues, that audiences match what each role may open, and
   that the content rules above hold (no carriers etc.).
3. **The Playwright specs run every tour for real** through the mock BFF, from the Help menu, pressing Next to the end. When a
   non-optional step's target (or its page) is missing, the tour ends with `drift: [stepId]` and the spec fails, naming the step. The
   spec also asserts how many steps were shown, so a step that silently turns into "skipped" is caught, and runs axe on the first
   popover. Run them with:

   ```bash
   pnpm --filter @afrotransact/e2e test -- seller-center.tours staff-console.tours
   ```

   Automated browsers (`navigator.webdriver`) never get tours by themselves, so no other spec is covered by a popover; the auto-start
   tests set `localStorage['at.tour.autostart'] = 'force'` first.

## Translations

Tour text is in each app's catalogue (`web/apps/<app>/src/i18n/{en,fr,es,ar,yo}.json`), keys `sc.tour.*` and `st.tour.*`. English is the
source. **The fr, es, ar and yo strings are machine drafts** and are not in `reviewedKeys`, so the catalogues keep their
`needsReview` marker until a native speaker has gone through them (see `web/packages/i18n`). Workflow for a change: edit `en.json`,
update the four other files in the same commit, keep the `{n}` and `{total}` placeholders, run `pnpm --filter <app> test` (the catalogue
check compares keys and placeholders). A translator signs a key off by moving it to `reviewedKeys` in that locale's `$meta`.

## Replaying tours (support and staff note)

**Replay tours lives in the Help menu on the black header, in every back office.** Open Help, then pick a tour under "Replay a tour".
Each entry shows a one-line summary. Only the tours the person's role allows are listed. "Show tours again automatically" at the
bottom of the menu makes first-time tours start by themselves again. Help appears once the page has loaded; during sign-in or on
the access screens it is not shown.

## Swapping the library

Only `web/packages/tour/src/driver-engine.ts` knows Driver.js. To switch:

1. Write another `TourEngine` (`src/engine.ts`): `show(view, callbacks)` draws one step (title, body, target, placement, direction,
   sheet, counter, buttons, success mark) and calls back for next, back, skip, close and "don't show again"; `hide()` removes it
   and restores focus; `announce(message)` speaks to screen readers. Keep the data attributes the drift guard and tests read:
   `data-tour-popover` on the popover, `role="dialog"`, `.at-tour-count`, `.at-tour-next`, and the `data-tour-live` region.
2. Pass it with `<TourProvider engine={...}>` or change the default in `react.tsx`.
3. Replace the `driver.js` dependency and the `@import 'driver.js/dist/driver.css'` line in `src/tour.css`, and keep the `.at-tour-*` rules.
4. Run the package tests and the two tour specs.

Tour definitions, progress, audiences and the Help menu do not change.

## Security and licences

`driver.js` (MIT) and `zod` (MIT, already used) are the only runtime dependencies. `pnpm audit` after adding them reported no advisory
for either. Do not add Shepherd.js or Intro.js (AGPL, commercial licence needed).
