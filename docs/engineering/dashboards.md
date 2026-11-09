# Dashboards: the home pages

The landing pages of Seller Center (sellers and Provider Hub providers, one app) and the staff console are a grid of
**module summaries**. Layout language: one section per module with a hairline above (no card, no shadow), a small icon and
the module name, ONE headline figure with its label and period, a compact chart, a total line and up to three quick
actions. A module with nothing to show yet is an illustrated prompt with one primary action, never zeros. A help strip
closes the page. The left navigation is unchanged. Below the grid the existing exportable charts (epic E32: PDF, Excel
with a native chart, CSV) stay as "Reports and exports".

## Pieces

| Piece | Where |
|---|---|
| Kit: `ModuleSection`, `ModuleGrid`, `Sparkline`, `MiniBars`, `Donut`, `PeriodSelect` + `usePeriod`, `EmptyPrompt`, `HelpStrip`, `ModuleSummaries` | `web/packages/ui/src/dashboard/` (stories: Dashboard/Kit; tests: `web/packages/ui/test/dashboard-kit.test.tsx`) |
| Contract | `ModuleSummary` in `contracts/openapi/gateway-bff-seller.yaml` (`Dashboard.modules`, `GET /v1/seller/dashboard?rangeDays=7\|30\|90`) and `gateway-bff-staff.yaml` (`StaffDashboard.modules`, `GET /v1/staff/dashboard`); copies in `go/services/gateway-bff/api/` |
| Seller and provider modules | `go/services/gateway-bff/internal/app/seller_modules.go` (called from `seller_dashboard.go`) |
| Staff modules | `go/services/gateway-bff/internal/app/staff_modules.go` (called from `staff_dashboard.go`) |
| Helpers | `internal/app/dashboard_modules.go` (`readyModule`, `emptyModule`, `unavailableModule`, `headline`, `chartOf`) |
| Mocks (MSW) | `web/packages/api-client/src/mocks/seller/home-modules.ts`, `.../staff/home-modules.ts` |
| Screens | `apps/seller-center/src/components/dashboard/DashboardView.tsx`, `apps/staff-console/src/components/dashboard/DashboardView.tsx` |

One read endpoint per app: the existing dashboard feed carries `modules` next to `kpis`, `charts` and `todo`, so a page makes one call and the
BFF fans out to the upstream services in parallel. Cached for a minute per member and range, like the rest of the feed.

## Data rules

1. **Real data only.** A module is built from figures an upstream returned. A figure that cannot be computed yet is left
   out of the module; a module whose source cannot answer at all is returned with `state: "unavailable"` (the page shows
   **Try again**). Never fill a gap with a default, an estimate or a sample.
2. **No empty numbers.** Nothing to show means `state: "empty"` with an `empty` prompt (`prompt`, one `action`, an `art`),
   no `headline`, no `chart`. A good-news zero is a word ("All stocked", "Queue clear", "None open"), not "0".
3. **Permissions first.** A module appears only when the member's role has the matching module in the access matrix
   (seller: `Access.Level`, which already reflects the organisation's plan and role; staff: `Allows(module)` plus the
   permission the upstream read needs). Quick actions are filtered the same way; creating actions (`Add product`) need
   Edit (`+listings` in `sellerCtx.links`). Provider orgs never get product modules and sellers never get booking modules.
4. **Three quick actions at most**, each an app-relative route that exists. Staff actions open the filtered list
   (`/applications?status=PENDING_REVIEW`; modules with tabs use `?tab=<resource>&<resource>.status=<STATUS>`).
5. **Names, not ids** in anything staff read (total lines use counts, ages and dates).
6. **Money** in chart points is in major units (dollars) like `ChartSpec`; `headline.value` and `totalLine` are formatted by the BFF.
7. **Accessibility.** Every chart has a text alternative: the SVG `<title>` summarises it and a visually hidden table
   carries the numbers; donut legends always print label and value; headline tone never relies on colour alone (the label
   says what the figure is).

## Add a module

1. Contract: if it needs a new `icon` value, add it to `ModuleIcon` in both specs (and the zod enum in
   `packages/api-client/src/schemas/seller.ts`, the icon in `MODULE_ICONS` in `ModuleSummaries.tsx`). Copy the specs to
   `go/services/gateway-bff/api/` and run `node web/packages/api-client/scripts/generate.mjs`.
2. BFF: read the upstream in `gatherModules` (seller) or `staffQueues` / the dashboard `build` (staff), behind the access
   check, and add a builder that returns `readyModule`, `emptyModule` or `unavailableModule`. Add a unit test next to
   `dashboard_modules_test.go` for the empty and ready cases.
3. Mock: add the same module to `home-modules.ts` of the app's mocks, from mock records where the mock holds them.
4. Screen: nothing to do; `ModuleSummaries` renders whatever `modules` holds.

## Kit usage

```tsx
<ModuleSection
  icon={<Package size={18} />}
  title="Orders"
  headline={{ value: '7', label: 'to ship now', tone: 'attention' }}
  chart={<Donut label="Orders by fulfilment" points={points} />}
  totalLine="46 orders in the last 30 days"
  actions={[{ label: 'To ship', href: '/orders', icon: <ListChecks size={16} /> }]}
/>
```

`state` is `ready | empty | loading | error` (`empty` takes an `<EmptyPrompt art prompt action />`, `error` takes `onRetry`).
Pass `linkAs` (the app's router link) so quick actions navigate client side. `usePeriod(key)` keeps the 7/30/90 choice per
viewer in `localStorage` (every access in try/catch; the first render uses the default, so there is no hydration mismatch).
Styling uses design tokens only (a test fails on raw colours or shadows).

## Left out for now (no source yet)

Seller: late and delivered counts and an orders-by-status donut (orders analytics returns fulfilment, not status), out-of-stock
and listing quality score (offers and a quality service do not expose them), ads (no ads module in the matrix), the
department scope of a module. Provider: reviews (service reviews are not exposed). Staff: late and at-risk shipments, failed
jobs and dead-letter queues, reports of reviews and listings (only the moderation queue size is read).
