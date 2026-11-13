# Guided tours

Walkthroughs for the Seller Center / Provider Hub and the staff console. They are built with `@afrotransact/tour`
(`web/packages/tour`), on Driver.js (MIT) behind our own interface. Why that library and not Shepherd.js or Intro.js (both AGPL with a
paid commercial licence): `docs/adr/0008-guided-tours-library.md`. Where the progress is kept and why: `docs/adr/0009-guided-tour-progress-on-the-server.md`.

A tour is a **chapter**: 3 to 8 steps about one screen or one job. Chapters are grouped into **journeys** (for example "Products and
stock"), and the **Tour hub** page (Help → Tour hub) shows every journey with the person's progress, Resume, Replay and Skip.
Only a handful of first-time chapters start by themselves; everything else is started from the hub.

## Where the pieces are

| Piece | Where |
|---|---|
| Chapter definition schema (Zod 4), `defineChapter`, `defineTour`, `tourText` | `web/packages/tour/src/schema.ts` |
| Who sees a chapter (roles, permissions, flags) | `src/audience.ts` |
| Progress: statuses, the write rules, the local cache, the server port `TourProgressRemote` | `src/progress.ts` |
| `TourService` (steps, routes, skipping, auto-start, server sync, drift events) | `src/service.ts` |
| `TourEngine` seam and the Driver.js engine | `src/engine.ts`, `src/driver-engine.ts` |
| Page access (targets, viewport, open dialogs, unsaved forms) | `src/environment.ts` |
| `<TourProvider>`, `<TourHub>`, `useTourHub`, `<TourHelpMenu>` | `src/react.tsx` |
| Styling with tokens (popover, overlay, hub, focus ring, bottom sheet, forced colors) | `src/tour.css` |
| Seller Center chapters, journeys, subject, guards, server adapter, host, hub page | `web/apps/seller-center/src/tours/`, `components/shell/SellerTourHost.tsx`, `components/tours/TourHubView.tsx`, `app/(portal)/help/tours` |
| Staff console, the same | `web/apps/staff-console/src/tours/`, `components/shell/StaffTourHost.tsx`, `components/tours/TourHubView.tsx`, `app/(console)/help/tours` |
| Server: table, use case, endpoints | identity service (`V44__user_tour_progress.sql`, `TourProgressService`, `TourProgressController`); BFF routes `/v1/account/tour-progress` |
| Contracts and client types | `contracts/openapi/identity.yaml`, `contracts/openapi/gateway-bff.yaml`, `web/packages/api-client` (`getTourProgress`, `saveTourProgress`, `mergeTourProgress`, `resetTourProgress`) |
| Drift guard (Playwright) | `tests/e2e/specs/seller-center.tours.spec.ts`, `staff-console.tours.spec.ts`, `tests/e2e/support/tours.ts` |

## Progress is kept on the server

The owner asked that a tour does not show again on a new browser or device, so the server is the source of truth.

- **Owner and store.** The identity service owns it: one row per user and tour in `user_tour_progress` (same database as the user's
  other settings; no new service and no new database). Columns: `tour_id` (namespaced by app, `sc.orders`, `st.settings`),
  `content_version`, `status` (`IN_PROGRESS`, `COMPLETED`, `DISMISSED`; "not started" is no row), `last_step`, `updated_at`.
  Nothing personal: ids, a status and a time.
- **API.** Through the BFF (cookie session, the caller's own token) as `GET|PUT|DELETE /v1/account/tour-progress` and
  `PUT|DELETE /v1/account/tour-progress/{tourId}` (identity `/v1/me/tour-progress…`). Authenticated only; the person comes from the
  token, so nobody can read or write another person's progress. `PUT` is an idempotent upsert; the merge `PUT` carries at most 100
  items. At most 200 tours per person.
- **Write rules (the same in identity and in the client).** A newer content version replaces an older one and an older version is
  ignored. For the same version, "in progress" never undoes "completed" or "dismissed", so replaying a finished chapter keeps it finished.
  The merge only fills gaps and moves to newer versions: what the server holds for a version stands.
- **Client.** `TourService.sync()` loads the server copy into the cache before anything may start by itself, so a chapter never
  flashes up and then turns out to be finished elsewhere. If the load fails the service stays quiet (**no auto-start**) and tries again
  after 5 s, 15 s, 1 min, then every 5 min, and when the browser comes back online. A write that cannot be sent stays marked
  "dirty" in the cache and is sent at the next load. Steps are saved a moment after the person moves on, so a quick walk through is one call.
- **localStorage is only a cache** (`at.tour.v2:<userId>:<tourId>`): instant render of the hub and Help menu, and offline. Every
  read and write is in try/catch. After a successful load the cache is made equal to the server (anything the server no longer has is dropped).
- **Migration.** Progress written by the first version of the store (`at.tour.v1:<userId>:<tourId>:<version>`) is copied up once with
  the merge call, then removed and flagged (`at.tour.migrated:<userId>`). A failed upload is not flagged, so the next load tries again.
- **Statuses.** Auto-start records "in progress" at once (a reload never repeats it). Esc or X keeps "in progress" with the last step, and the
  hub and Help menu offer Resume. Skip tour and "Don't show again" record "dismissed". Finishing records "completed".
- **New content version.** Each chapter has a `version`. When it is newer than what the person last dealt with, the hub shows a small
  "What's new" mark (and the Help button a dot). A new version **never starts by itself**; replaying and finishing or closing it clears the mark.
- **Replay always works**, whatever the status. **Reset my progress** in the hub deletes the person's tour rows (all back offices).
- **Global switch.** `at.identity.tours.auto-start` (env `TOURS_AUTO_START`, default true) is returned as
  `autoStartEnabled`; false means first-time chapters never start by themselves anywhere, and the hub says so. A staff-editable setting for it is a follow-up story.
- **Metrics.** identity exposes `identity_tour_events_total{event}` (`started`, `completed`, `dismissed`, `reset`), no tour id and no user label.
- **Privacy.** No PII in the payload, the cache keys or the logs (identity logs nothing about tours; the client logs only in development, and only ids).

## Adding a chapter

1. **Put `data-tour` ids on the real components.** A step points at its target only by `data-tour="<id>"` (lower-case words joined
   by `-` or `.`). Never a CSS selector, a class or a generated id: the schema rejects them. Shared helpers already give you
   `page-<module>` (page header; staff modules are lower-cased, `page-deptrequests`) and `page-<module>-actions` (its buttons), `nav-<module>`,
   `sidebar`, `org-switcher`, `table-<table id>` (every `DataTable`: `table-seller.orders`, `table-staff.audit`), `account-head`,
   `account-nav`, `account-section`, `settings-section`, `help-menu`, `lola-launcher`. Add a new id with one attribute on the element;
   do not wrap things only to attach it (it can break page spacing). **Never point at something inside a dialog or sheet**: they trap focus.
2. **Write the chapter** in the app's `src/tours/*-tours.ts` with `defineChapter` (it validates at module load, so a mistake fails the tests, not a customer):

   ```ts
   defineChapter({
     id: 'inventory',                        // unique in the app
     prefix: 'sc.tour.inventory',            // i18n: <prefix>.title, .summary and <prefix>.<stepId>.title|body (hyphens become camelCase)
     journey: 'catalogue',                   // a group of the Tour hub
     version: 1,                             // bump when the words change enough to deserve a "What's new" mark
     route: '/inventory',                    // where it starts
     audience: { permissions: ['inventory:view'] },
     autoStart: { routes: ['/inventory'] },  // only for the busiest first-time chapters
     steps: [
       ['page', 'page-inventory'],
       ['count', 'inventory-adjust', { optional: true }],   // data-dependent target
       ['wrap']                                              // no target: centred
     ]
   })
   ```

   Step options: `placement` (`top`, `bottom`, `start`, `end`, `center`), `route` (go to this page first; later steps without a route stay on
   it), `when` (a guard registered in `subject.ts`: `can-<module>`, `can-settings-<section>`), `optional` (the target depends on data),
   `success` (`{ target, when: 'click' | 'appear' | 'disappear' }`: a quiet "Done" mark, never blocks Next) and `doIt`.
3. **Register it** in the app's list (`SELLER_TOURS`, `STAFF_TOURS`) in journey order, and its journey in `SELLER_JOURNEYS` / `STAFF_JOURNEYS`.
4. **Words** in `en.json` and the four other catalogues (see "Translations").
5. **Drift guard.** Add the chapter to the list in the app's `*.tours.spec.ts` with the number of steps it shows today.
6. Run `pnpm --filter <app> test`, `pnpm --filter @afrotransact/tour test` and the tour spec.

### Steps that wait for a page the person opens

A step may carry `success: { target, when: 'appear' }` without `doIt`: it shows a quiet "Done" mark when the target appears (the person opened the full
product form from the barcode step) and the person presses Next. The steps after it are `optional`, so a person who does something else (sells an
existing page with the offer form) simply ends the chapter without drift. The drift guard supplies the person's clicks through `runTour(..., { acts })`
(`tests/e2e/support/tours.ts`); the tour still never clicks for anyone.

### "Do it" steps

A step with `doIt: true` (and `success: { target, when: 'click' }`) waits for the person to click the real, highlighted control. Driver.js leaves
the target clickable; the popover says "Your turn" and its button reads "Skip this step", so the step **never blocks**. When the person clicks,
the tour moves on by itself after a moment (the page may navigate first). **The tour never clicks or submits anything for anyone.** Use it for
links and buttons that only navigate or open a form (Add a product), never for anything that changes data.

### Who sees a chapter

The app turns its session into a `TourSubject` (`subject.ts`): an opaque user id, role keys, permissions and flags. Nothing personal.
Permissions are `<module>:<level>` (levels `view`, `edit`, `approve`, `full`; each includes the ones before it) plus the platform
permissions the staff session lists. The audience has `roles` (any of), `permissions` (all of), `anyPermissions` (at least one) and
`flags` (all of: `seller`, `provider`, `approved`, `applying`, `offers-services`, `staff`, and `cap-<plan capability>` such as `cap-bulkUpload`).
The Tour hub lists only the chapters the person's audience admits, and starting one re-checks it. A staff member never sees a chapter about
something they cannot open, and a step whose `when` guard says no is skipped, so a chapter never walks someone to a page their role cannot reach.

## Rules for good tour content

- **Plain words.** Short sentences, no jargon, no codes or ids. Say "Add a product", not "create a listing record".
- **Names, not ids.** Use the label on screen. Never print a tax code, an order id or a status code.
- **Say only what exists today.** If a feature is a draft or a plan, leave it out. Check the screen, not the design. Gaps go in the matrix below.
- **No carriers, and no "AfroTransact collects and delivers on your behalf"** wording. Describe what the person does.
- **Tax wording stays safe.** Describe what a screen shows and how the platform treats tax; never tell anyone what to claim, deduct or pay. The
  tax chapters say "not tax advice".
- **One idea per step**, a title of a few words and a body of one to three sentences. A chapter is a map, not a manual.
- **Warn where it matters.** If a click has consequences (marking a package picked up charges the customer), say so plainly.
- **Never block.** Skip tour, Esc and the X always work. "Do it" steps can be skipped.
- **Targets must exist for the person.** If a screen only shows a thing sometimes (a banner, a row, a section that depends on the category), mark the step `optional`.

## Behaviour (what the service guarantees)

- **Accessible:** the popover is a named dialog; focus goes to its primary button and returns to where it was on close; Tab stays
  inside the popover and the highlighted element; Esc closes; arrow keys move (ArrowLeft goes forward in Arabic); a polite live
  region announces "Step n of N, title, text"; Skip, Back/Next and the step counter are always visible. Reduced motion turns
  animation and smooth scroll off. Forced-colors and `prefers-contrast: more` have their own rules. Targets are scrolled into view.
- **Right to left:** `dir` is set on the popover from the page; `start`/`end` placements and the arrow keys follow it.
- **Narrow screens:** under 640 px the popover is a bottom sheet and a chapter never starts by itself.
- **Missing target at run time:** the step is skipped quietly and logged to the console in development only. The step counter counts only the steps that are shown.
- **Auto-start, once:** the first time a person reaches a chapter's page, has no record of it at all (any version), and the server's progress has
  loaded; and only when the page has loaded and the first step can be drawn; no dialog, menu or sheet is open; no form holds typed text; the person is
  not typing in a field; the screen is at least 640 px wide; the browser is not automated; the global switch is on; and no other chapter started by
  itself in this visit.
- **No analytics, no PII in the browser.** The service dispatches a local DOM event `at-tour` (step shown, tour ended) that only the end-to-end tests listen to.

## How drift is caught

A chapter breaks silently when a screen changes and the `data-tour` attribute disappears. Three guards:

1. The schema refuses anything that is not a `data-tour` id, so chapters cannot lean on styles.
2. `pnpm test` in each app checks every chapter's keys exist in all five catalogues (and keep their `{placeholders}`), that each chapter has 3 to 8 steps and
   a listed journey, that audiences match what each role may open, that "do it" steps wait for a click, and that the content rules above hold.
3. **The Playwright specs run every chapter for real** through the mock BFF, from Help → Tour hub, pressing Next to the end. When a
   non-optional step's target (or its page) is missing, the chapter ends with `drift: [stepId]` and the spec fails, naming the step. The
   spec also asserts how many steps were shown, so a step that silently turns into "skipped" is caught, and runs axe on the first popover of each chapter. It also covers
   Resume, "do it", skip and reset in the hub, server-side progress surviving a new browser context, fail-quiet when the server is down, and the one-time migration from the browser. Run them with:

   ```bash
   pnpm --filter @afrotransact/e2e test -- seller-center.tours staff-console.tours
   ```

   Automated browsers (`navigator.webdriver`) never get chapters by themselves, so no other spec is covered by a popover; the auto-start
   tests set `localStorage['at.tour.autostart'] = 'force'` first. The mock BFF keeps tour progress in memory per process; `DELETE /v1/account/tour-progress` resets it.

## Coverage matrix

Audit of every route against the chapters. **Steps** is the number of steps defined / shown in the mock for a full-access person. "Gap" rows are
screens with no chapter, or things the brief named that the product does not have.

### Seller Center and Provider Hub (26 chapters in 5 journeys)

| Screen (route) | Chapter (journey) | Steps | Status |
|---|---|---|---|
| Home `/` | welcome, dashboard (setup, money) | 7/5, 4/4 | covered |
| Application `/onboarding`, `/onboarding/[step]` | application (setup); wizard steps are described by the step list | 4/n.a. | covered; browser pass needs an applying account |
| Account health and Stripe `/account-health` | account-health (setup) | 4/4 | covered (verification and bank account are Stripe's secure forms) |
| Team `/team`, business page `/team/business-page` | team (setup) | 5/5 | covered |
| My account `/account`, `/account/security`, `/account/notifications`, `/account/preferences` | account-settings (setup) | 6/6 | covered |
| Help, Lola assistant | help-support (setup) | 4/4 | covered |
| Listings `/listings` (tabs Listings, Drafts) | listings-overview (catalogue) | 4/4 | covered |
| New product `/listings/new` (barcode step, offer-only form, full form), edit `/listings/[id]/edit` | first-product, product-sections (catalogue) | 8/4, 8/1 | covered. Add a product opens on the barcode step (S382): the chapters start there (`barcode-input`, `barcode-step`), describe the offer-only path in plain words (a page that exists is sold with price, quantity and condition only; no step points into that form, it appears only after a match) and carry on to the full form (category, photos, what is missing, submit; sections) with **optional** steps that are found once the person has opened the form. The drift guard runs both chapters twice: as they stand (4 and 1 steps) and with the person's own clicks past the barcode step (8 and 3 steps). Sections that depend on the category stay optional (see gaps) |
| Selling approvals `/listings/approvals` | approvals (catalogue) | 4/4 | covered |
| Image library `/listings/images` | images (catalogue) | 4/4 | covered |
| Bulk upload `/listings/bulk`, history, `[importId]` | bulk-upload (catalogue) | 5/5 | covered (an individual report is described in the last step, not toured) |
| Inventory & FBA `/inventory` | inventory (catalogue) | 4/4 | covered (the FBA tab is not toured step by step) |
| Orders `/orders` (order sheet) | orders (fulfilment) | 5/5 | covered (the order sheet is a dialog: not pointed at) |
| Ship-from `/orders/ship-from` | shipping-setup (fulfilment) | 4/4 | covered |
| Run pick lists `/orders/run-pickups`, `[runId]` | run-pickups (fulfilment) | 3/3 | covered (a run's detail page is not toured) |
| Returns `/returns`, disputes `/disputes` | returns-disputes (fulfilment) | 5/5 | covered |
| Payouts `/payouts`, sales tax statement `/payouts/tax` | payouts (money) | 5/5 | covered |
| Fees `/fees`, plan and billing `/billing` | fees-billing (money) | 5/5 | covered |
| Coupons `/coupons` | coupons (money) | 4/4 | covered (the create form is a sheet: not pointed at) |
| Reviews `/reviews` | reviews (money) | 3/3 | covered |
| Services `/services` (with service area) | services (services) | 3/3 | covered |
| Working hours `/hours`, cancellation policy `/policy` | availability (services) | 3/3 | covered |
| Requests `/requests`, calendar `/calendar` | bookings (services) | 3/3 | covered |
| Earnings `/earnings` | provider-earnings (services) | 3/3 | covered |
| Tour hub `/help/tours` | (the hub itself) | n.a. | built |
| Accept invitation `/invitations/accept` | none | n.a. | gap: a public page outside the back office |
| Size chart manager | described in product-sections (sizes) | n.a. | gap: opens inside the product form, not a route |
| Barcode match and offer-only path | first-product (barcode, offer-only, carry-on steps) | 3 steps | covered in words and on the barcode step; the offer-only form itself, the match card and the change-request list are not pointed at step by step |
| Image-quality tasks | none | n.a. | gap: no screen exists |
| Messages with customers | none | n.a. | gap: no screen exists (replies to reviews are in reviews) |

### Staff console (28 chapters in 6 journeys)

| Screen (route) | Chapter (journey) | Steps | Status |
|---|---|---|---|
| Admin home `/` | admin-home (start) | 4/4 | covered |
| My account `/account/*` | my-account (start) | 6/6 | covered |
| Applications `/applications` (two-step review) | applications-review (partners) | 5/5 | covered |
| Selling approvals and held approvals (`/applications?tab=…`) | selling-approvals (partners) | 3/3 | covered |
| Organisations `/organisations` (Stripe Connect) | organisations (partners) | 4/4 | covered |
| Department requests `/department-requests` | department-requests (partners) | 3/3 | covered |
| Delivery quotes `/delivery-quotes` | delivery-quotes (partners) | 3/3 | covered |
| Catalog `/moderation` (moderation queue, product reviews, products) | moderation-queue (catalogue) | 5/5 | covered |
| Catalog tabs: category requests, brands, selling rules | catalogue-rules (catalogue) | 5/5 | covered (the category tree itself is not toured) |
| Orders `/orders` (shipment status, Picked up warning) | orders-shipments (commerce) | 4/4 | covered |
| Disputes and returns `/disputes` | disputes-returns (commerce) | 4/4 | covered |
| Customer support `/support` (refunds within a limit) | support-refunds (commerce) | 4/4 | covered |
| Payouts and ledger `/payouts` | payouts (commerce) | 5/5 | covered (the other tabs, such as referrals and daily runs, are not toured) |
| Tax review, tax gate banner (Settings → Sales tax) | tax-review (commerce) | 4/4 | covered |
| Tax method, profiles, decision records, provider codes | tax-profiles (commerce) | 5/5 | covered, with a "not tax advice" step |
| Fees and commissions (Settings → Fees), plans `/plans` | fees-plans (commerce) | 5/5 | covered |
| Inventory hub `/inventory` | inventory (operations) | 7/7 | covered |
| AfroTransact Retail `/retail` | retail (operations) | 6/6 | covered |
| Delivery dispatch `/dispatch` | dispatch (operations) | 5/5 | covered |
| Pre-orders `/preorders`, `/preorders/[id]`, `/new`, `/runs`, `/settings` | preorders (operations) | 4/4 | covered for the list; the detail, new, runs and settings pages are not toured |
| Exports `/exports` | exports (operations) | 3/3 | covered |
| Users and roles `/users` | roles-permissions (platform) | 4/4 | covered |
| Settings hub and sections (membership, shipping, delivery fees, markets) | settings (platform) | 5/5 | covered; other sections have their own chapters or none |
| Site content and legal (Settings → Published pages, General) | site-content (platform) | 3/3 | covered |
| Notification templates (Settings → Notifications) | notifications (platform) | 3/3 | covered |
| Identity and access (Settings → Identity & access) | identity-access (platform) | 4/4 | covered |
| Marketing `/marketing` (announcements, placements, Home settings, Home preview, coupons) | marketing (platform) | 6/6 | covered |
| Audit log `/audit` | audit-log (platform) | 3/3 | covered |
| Tour hub `/help/tours` | (the hub itself) | n.a. | built |
| Markets (Settings → Markets & locations) | settings (markets step) | n.a. | covered inside the settings chapter |
| Settings sections: exchange rates, credit and gift cards, referral program, run economics, sign-in & integrations, application review | none (the application-review section is named in applications-review) | n.a. | gap |
| Trust and safety queue and SLA | none | n.a. | gap: no dedicated screen (disputes and returns have their own limits; application SLAs are in Settings) |
| Search and indexing ops tools | none | n.a. | gap: no staff screen exists |

## Translations

Chapter text is in each app's catalogue (`web/apps/<app>/src/i18n/{en,fr,es,ar,yo}.json`), keys `sc.tour.*` and `st.tour.*` (chapters, journeys, the popover,
the Help menu and the hub). English is the source. **The fr, es, ar and yo strings are machine drafts** and are not in `reviewedKeys`, so the
catalogues keep their `needsReview` marker until a native speaker has gone through them (story S400; see `web/packages/i18n`). Workflow for a change: edit
`en.json`, update the four other files in the same commit, keep the `{n}`, `{total}`, `{done}` and `{title}` placeholders, run `pnpm --filter <app> test` (the
catalogue check compares keys and placeholders). A translator signs a key off by moving it to `reviewedKeys` in that locale's `$meta`.

## Replaying and support note

**Help → Tour hub** (the Help button on the black header, in every back office). The hub lists the chapters the person's role allows, by journey, with
a progress line, Start, Resume (where they left off), Replay, Skip (a chapter or a journey) and Reset my progress. Help also offers "Resume: …" for a chapter left
part way, and a dot when something is new. Progress follows the person to any browser or device. "Show tours again automatically" no longer exists:
Reset my progress does it.

## Swapping the library

Only `web/packages/tour/src/driver-engine.ts` knows Driver.js. To switch:

1. Write another `TourEngine` (`src/engine.ts`): `show(view, callbacks)` draws one step (title, body, target, placement, direction,
   sheet, counter, buttons, success mark, the "do it" hint) and calls back for next, back, skip, close and "don't show again"; `hide()` removes it
   and restores focus; `announce(message)` speaks to screen readers. Keep the data attributes the drift guard and tests read:
   `data-tour-popover` on the popover, `role="dialog"`, `.at-tour-count`, `.at-tour-next`, and the `data-tour-live` region.
2. Pass it with `<TourProvider engine={...}>` or change the default in `react.tsx`.
3. Replace the `driver.js` dependency and the `@import 'driver.js/dist/driver.css'` line in `src/tour.css`, and keep the `.at-tour-*` rules.
4. Run the package tests and the two tour specs.

Chapter definitions, progress, audiences, the hub and the Help menu do not change.

## Security and licences

`driver.js` (MIT) and `zod` (MIT, already used) are the only runtime dependencies. `pnpm audit` after adding them reported no advisory
for either. Do not add Shepherd.js or Intro.js (AGPL, commercial licence needed).
