// GENERATED from contracts/openapi/gateway-bff-staff.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/api-client generate
/* eslint-disable */

export interface paths {
    "/v1/staff/session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Signed-in staff member, roles, effective permissions and the RBAC-filtered sidebar
         * @description Composes identity `GET /v1/me` (profile, principal type, platform roles and effective permissions) and identity's
         *     module matrix `GET /v1/admin/module-access?portal=platform` (cached; seeded defaults when it cannot be read).
         *     Modules the staff member's roles cannot open are not returned. The dashboard is the landing page.
         */
        get: operations["getStaffSession"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/dashboard": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Platform dashboard feed (KPIs and every chart)
         * @description One feed for every figure on the dashboard: sales and net sales by day and orders by department (orders
         *     `GET /v1/admin/orders`, lines' category), applications by status (seller `GET /v1/applications`), payouts
         *     (payments `GET /v1/payouts/requests`) versus claim refunds (trust-ops `GET /v1/claims?status=RESOLVED_REFUND`)
         *     by week. Each chart reports `available: false` when its upstream cannot answer. Chart exports (PDF, Excel with a
         *     native chart, CSV) are built from these rows. Needs VIEW on the dashboard module.
         */
        get: operations["getStaffDashboard"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/assistant": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Lola in the staff console - today's messages and the tools she has
         * @description Any staff member. One level for everyone (200 messages a day, UTC); she is read-only and works with the member's own module access and permissions.
         */
        get: operations["getStaffAssistant"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/assistant/chat": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Ask Lola one question (the console keeps the conversation and sends the earlier turns)
         * @description Lola looks things up through the console's own use cases (dashboard, tables, sheets) and ai-gateway
         *     `POST /v1/ai/staff-assistant/complete`; payouts and Connect account health are read for one organisation. Emails and
         *     phone numbers are partly hidden from the model. She cannot change anything: `links` are buttons that open a record.
         *     429 `ASSISTANT_LIMIT` after the day's messages.
         */
        post: operations["staffAssistantChat"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/resources/{resource}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One page of a server-driven table (columns, facets, rows)
         * @description Upstreams per resource — users: identity `GET /v1/admin/users`; roles: identity `GET /v1/admin/roles`;
         *     applications: seller `GET /v1/applications`; orders: orders `GET /v1/admin/orders`; products: catalog
         *     `GET /v1/products`; moderation: trust-ops `GET /v1/moderation/items`; claims: trust-ops `GET /v1/claims`;
         *     returns: tax-returns `GET /v1/returns`; payouts: payments `GET /v1/payouts/requests`; inventory: inventory
         *     `GET /v1/lots`; purchase-orders: retail `GET /v1/purchase-orders`; routes: fulfilment `GET /v1/dispatch/routes` (create = the route builder
         *     `POST /v1/dispatch/routes/build`); dispatch-shipments: fulfilment `GET /v1/dispatch/shipments`; sos-alerts: fulfilment
         *     `GET /v1/dispatch/sos` (action acknowledge); vehicle-defects: fulfilment `GET /v1/dispatch/defects` (action resolve);
         *     driver-events: fulfilment `GET /v1/dispatch/events`; pickup-locations: carrier-gateway
         *     `GET /v1/pickup-locations?includeInactive=true` (create `POST`, edit `PUT /v1/pickup-locations/{id}`, shipping:admin:
         *     AfroTransact counters and lockers customers collect platform-fulfilled packages from; sellers' own locations are
         *     read-only here);
         *     support-cases: trust-ops `GET /v1/support-cases`; audit: trust-ops `GET /v1/audit/entries`; markets: content
         *     `GET /v1/markets/draft/countries`; site-settings: content `GET /v1/site-config/settings`; legal-documents: content
         *     `GET /v1/legal`; help-articles: content `GET /v1/help/admin/articles` (content:site.view); privacy-requests: content `GET /v1/privacy/requests` (content:legal.view); announcements and
         *     placements: content `GET /v1/placements`; identity-providers: identity
         *     `GET /v1/admin/identity-providers`; fee-schedules: payments `GET /v1/fee-schedules`; tax-rates: tax-returns
         *     `GET /v1/tax/rates`. Needs VIEW on the resource's module.
         *
         *     One search box, every column: `q` matches every column the table shows (ids, names, SKUs, statuses raw and
         *     humanised, amounts in currency units with or without separators, dates as ISO, "Oct 11, 2026" or "10/11/2026");
         *     every whitespace-separated term must match some column. `sort` orders by any column. Upstreams search few fields
         *     and sort by a fixed order, so with `q` or `sort` the BFF reads the table with the caller's token (up to 5,000
         *     rows, cached per caller for 20 seconds and dropped on any write through the BFF), filters, sorts and pages it
         *     itself; such pages are page-numbered (`total` set, `nextCursor` null) and say `partial: true` when the table had
         *     more rows than were read. Without `q` and `sort` the upstream's order and paging (page numbers or cursors) apply.
         */
        get: operations["listStaffResource"];
        put?: never;
        /**
         * Create a record from the table's create form (New)
         * @description users: identity `POST /v1/admin/users` (access:admin); help-articles: content `PUT /v1/help/articles/{slug}` for an
         *     unused address (content:site.edit); announcements and placements: content `POST /v1/placements`
         *     (content:placements.edit); fee-schedules: payments `POST /v1/fee-schedules` (payments:configure); tax-rates
         *     (local rate): tax-returns `PUT /v1/tax/rates/local/{state}/{zipPrefix}` (tax:configure). Fields are validated
         *     against the create form first (422 with per-field errors).
         */
        post: operations["createStaffRecord"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/resources/{resource}/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Detail sheet of one record (fields, nested tables, activity, RBAC-gated actions, edit form) */
        get: operations["getStaffRecord"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Save the edit form of a record (Edit)
         * @description markets: content `PATCH /v1/markets/draft/countries/{code}` (content:markets.edit); site-settings: content
         *     `PUT /v1/site-config` with the revision read last (content:site.edit); legal-documents: content
         *     `PATCH /v1/legal/{type}/current` (content:legal.edit); help-articles: content `PUT /v1/help/articles/{slug}` with
         *     `expectedVersion`, the page's Markdown split into sections at `## ` headings (content:site.edit); announcements and placements: content `PUT /v1/placements/{id}`
         *     with `expectedVersion` (content:placements.edit); tax-rates (state rule): tax-returns `PUT /v1/tax/rates/states/{state}`
         *     (tax:configure). A stale `version` answers 409 `CONFLICT`.
         */
        patch: operations["updateStaffRecord"];
        trace?: never;
    };
    "/v1/staff/resources/{resource}/{id}/actions/{action}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Run one action of a detail sheet (approve, hold, refund, cancel, …) and return the updated sheet
         * @description The action must be listed on the record's sheet with `allowed: true`; otherwise 403 `FORBIDDEN` (missing
         *     permission or module level) or 409 `CONFLICT` (not possible in the record's current state). Examples: applications
         *     `review-approve` / `review-flag` / `review-request-info` (seller `POST /v1/applications/{id}/review`,
         *     applications:review, while pending review) and `final-approve` / `send-back` / `reject` (seller
         *     `POST /v1/applications/{id}/decision`, applications:approve, awaiting final approval or flagged, never by the
         *     step-one reviewer). Review decisions are idempotent: a retry with the same `Idempotency-Key`, or the same person
         *     repeating the decision that put the application in its state, answers 200 with the current sheet and changes
         *     nothing; when the application already went past the action, 409 `ALREADY_DONE` says what was decided, by whom
         *     and when ("This application is already approved (by Ada Obi, 12 Oct 2026, 14:03 UTC)"); show it as
         *     information and refresh the sheet. Never retry a decision with a new key. Claims `refund` / `deny` (trust-ops
         *     `POST /v1/claims/{id}/decision`, disputes:manage, refunds capped by the role's refund limit); payouts `approve` /
         *     `hold` / `release` (payments, payouts:approve); orders `shipment-status` (orders `POST /v1/shipments/{id}/transitions`);
         *     privacy-requests `complete` / `deny` (content `POST /v1/privacy/requests/{reference}/resolution`, content:legal.edit;
         *     a denial needs a note).
         *     `Idempotency-Key` is relayed to upstreams that accept one.
         */
        post: operations["runStaffAction"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/resources/{resource}/{id}/files/{fileId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Open a file attached to a record (an application's uploaded document)
         * @description Redirects (302) to a short-lived signed URL of the file; the upstream logs who opened it. Needs view access to
         *     the resource. Applications: `fileId` is the document's `assetId` from the record's `links`.
         */
        get: operations["openStaffRecordFile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/resources/{resource}/{id}/notes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add an internal note to a record
         * @description No upstream stores internal notes on records yet, so this answers 503 `UPSTREAM_UNAVAILABLE` until one does
         *     (sheets report `notesEnabled: false` meanwhile).
         */
        post: operations["addStaffNote"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/exports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** My export jobs, newest first (kept 24 hours after they finish) */
        get: operations["listStaffExports"];
        put?: never;
        /**
         * Export a table as CSV, Excel (with a Summary sheet of native charts) or PDF (with a summary page of charts)
         * @description A resource export covers the table's search and sort (or only the selected rows, `ids`) with the visible columns
         *     in order. Without search, sort or selection every page of the resource's upstream list is streamed with the
         *     caller's token; with them the BFF reads up to 50,000 rows, filters and sorts them. A client-side table sends its
         *     rows (`dataset`, at most 5,000, always built within the request). Up to 5,000 rows finish within the request
         *     (201 READY); larger exports are queued (202 QUEUED) and built in the background — poll
         *     `GET /v1/staff/exports/{id}`. Capped at 200,000 rows. Needs VIEW on the resource's module.
         */
        post: operations["createStaffExport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/exports/{exportId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Status of one export job (poll until READY or FAILED) */
        get: operations["getStaffExport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/exports/{exportId}/download": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Redirect to the signed download URL of a ready export
         * @description 302 to the signed URL (valid until `expiresAt`); 409 while the job is not ready, 410 once it expired.
         */
        get: operations["downloadStaffExport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/export-files/{exportId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The export file behind a signed link (no session needed; the signature and expiry are checked)
         * @description Served when exports are stored by the BFF itself (default). With media storage the signed URL points at the
         *     object store instead. 403 for a bad signature, 410 after `expires`.
         */
        get: operations["getStaffExportFile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/support/customers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Find a customer by name or email
         * @description identity `GET /v1/admin/users?q=` (users:read). Needs VIEW on the support module.
         */
        get: operations["searchSupportCustomers"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/support/customers/{customerId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Customer 360 — account, orders with shipments, bookings, claims, returns, notifications, cases and audit
         * @description identity `GET /v1/admin/users/{id}`; orders `GET /v1/admin/orders?customer=`; trust-ops `GET /v1/claims?orderId=`
         *     and `GET /v1/support-cases`; tax-returns `GET /v1/returns?orderId=`; notifications `GET /v1/notifications?recipient=`;
         *     trust-ops `GET /v1/audit/entries?entityId=`. Bookings have no staff lookup by customer yet (listed in
         *     `unavailable`). Sections whose upstream fails are empty and named in `unavailable`; the rest still renders.
         */
        get: operations["getSupportCustomer"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/support/refunds": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Refund a customer within the role's refund limit (claim decision or a partial refund)
         * @description `via: CLAIM` decides the customer's open claim with a refund (trust-ops `POST /v1/claims/{id}/decision`,
         *     disputes:manage). `via: ORDER` issues a partial refund without a return (tax-returns `POST /v1/refunds`,
         *     refunds:issue), spread over the order's lines. Amounts above the role's limit answer 403 `REFUND_LIMIT`. Both
         *     upstreams write the decision to the trust-ops audit log.
         */
        post: operations["supportRefund"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/support/notifications/{notificationId}/resend": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Resend a notification to the customer
         * @description Failed notifications are replayed (notifications `POST /v1/dead-letters/{id}/replay`); delivered ones are queued
         *     again from the same template (notifications `POST /v1/notifications`, notifications:send). Needs EDIT on support.
         */
        post: operations["supportResendNotification"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/support/shipments/{shipmentId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cancel a customer's shipment (until it is packed)
         * @description orders `POST /v1/shipments/{id}/transitions` to `CANCELLED` with the reason (orders:manage or orders:update-status).
         */
        post: operations["supportCancelShipment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/plans": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Every plan with its versions, entitlements, prices and subscribers
         * @description Needs VIEW on Plans & billing (plans:manage, billing:adjust, seller:billing or billing:usage). The seller service validates and authorises again.
         */
        get: operations["listStaffPlans"];
        put?: never;
        /**
         * Create a plan (draft, version 1 as a draft; plans:manage)
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        post: operations["createStaffPlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/plans/{code}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One plan
         * @description Needs VIEW on Plans & billing (plans:manage, billing:adjust, seller:billing or billing:usage). The seller service validates and authorises again.
         */
        get: operations["getStaffPlan"];
        /**
         * Edit name, tagline, features, limits, visibility and sort (no new version; expectedVersion = rowVersion)
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        put: operations["updateStaffPlan"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/plans/{code}/draft": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Save the draft version's prices per cadence and default trial (a new version when they differ)
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        put: operations["saveStaffPlanDraft"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/plans/{code}/publish": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Publish the draft version (subscribers stay on theirs until migrated)
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        post: operations["publishStaffPlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/plans/{code}/retire": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Retire a plan: no new subscribers, existing ones keep it
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        post: operations["retireStaffPlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/plans/{code}/invites": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Organisations invited to an invite-only plan
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        put: operations["setStaffPlanInvites"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/plans/{code}/migration": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Subscribers on older versions and what the current version will charge them
         * @description Needs VIEW on Plans & billing (plans:manage, billing:adjust, seller:billing or billing:usage). The seller service validates and authorises again.
         */
        get: operations["previewStaffPlanMigration"];
        put?: never;
        /**
         * Move subscribers (all or orgIds) to the current version from their next renewal (no proration)
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        post: operations["migrateStaffPlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/trial-campaigns": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Trial campaigns (promo codes and sign-up windows)
         * @description Needs VIEW on Plans & billing (plans:manage, billing:adjust, seller:billing or billing:usage). The seller service validates and authorises again.
         */
        get: operations["listStaffTrialCampaigns"];
        put?: never;
        /**
         * Create a trial campaign
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        post: operations["createStaffTrialCampaign"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/trial-campaigns/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Edit a trial campaign (expectedVersion)
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        put: operations["updateStaffTrialCampaign"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/trial-campaigns/{id}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Activate, pause or end a trial campaign
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        post: operations["setStaffTrialCampaignStatus"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/reward-rules": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Reward rules
         * @description Needs VIEW on Plans & billing (plans:manage, billing:adjust, seller:billing or billing:usage). The seller service validates and authorises again.
         */
        get: operations["listStaffRewardRules"];
        put?: never;
        /**
         * Create a reward rule (DRAFT unless a status is given)
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        post: operations["createStaffRewardRule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/reward-rules/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Who qualifies now under a rule (unsaved or edited; nothing is granted)
         * @description Needs VIEW on Plans & billing (plans:manage, billing:adjust, seller:billing or billing:usage). The seller service validates and authorises again.
         */
        post: operations["previewStaffRewardRule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/reward-rules/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Edit a reward rule (expectedVersion)
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        put: operations["updateStaffRewardRule"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/reward-rules/{id}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Activate, pause or end a reward rule
         * @description Needs EDIT on Plans & billing and `plans:manage`. The seller service validates and authorises again.
         */
        post: operations["setStaffRewardRuleStatus"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/grants": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Grants log: rewards and staff adjustments, newest first (server-paged)
         * @description Needs VIEW on Plans & billing (plans:manage, billing:adjust, seller:billing or billing:usage). The seller service validates and authorises again.
         */
        get: operations["listStaffBillingGrants"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/reason-codes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Reason codes for adjustments
         * @description Needs VIEW on Plans & billing (plans:manage, billing:adjust, seller:billing or billing:usage). The seller service validates and authorises again.
         */
        get: operations["listStaffBillingReasonCodes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Find an organisation by name or id for its billing panel
         * @description Needs VIEW on Plans & billing (plans:manage, billing:adjust, seller:billing or billing:usage). The seller service validates and authorises again.
         */
        get: operations["searchStaffBillingOrgs"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs/{orgId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Per-seller billing panel: plan, trial, credit balance, grants, invoices, how the plan is paid and the invoices paid from earnings
         * @description Needs VIEW on Plans & billing (plans:manage, billing:adjust, seller:billing or billing:usage). The seller service validates and authorises again.
         */
        get: operations["getStaffBillingPanel"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs/{orgId}/credits": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Give platform credit the next invoices use first (billing:adjust, reason code)
         * @description Needs EDIT on Plans & billing and `billing:adjust`. The seller service validates and authorises again.
         */
        post: operations["creditStaffBillingOrg"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs/{orgId}/free-days": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Give free days: the first charge moves out (billing:adjust)
         * @description Needs EDIT on Plans & billing and `billing:adjust`. The seller service validates and authorises again.
         */
        post: operations["giveStaffBillingFreeDays"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs/{orgId}/free-months": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Give free calendar months: the first charge moves out (billing:adjust)
         * @description Needs EDIT on Plans & billing and `billing:adjust`. The seller service validates and authorises again.
         */
        post: operations["giveStaffBillingFreeMonths"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs/{orgId}/charge-gate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Put a condition on the plan charges: start after X sales or listings, waive while X listings, or waive until a date (billing:adjust)
         * @description Needs EDIT on Plans & billing and `billing:adjust`. A reason code is required; the grant is in the grants log. The seller sees the condition and their progress on the Billing page.
         */
        put: operations["setStaffBillingChargeGate"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs/{orgId}/charge-gate/clear": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Remove the condition on the plan charges: they apply as normal again (billing:adjust)
         * @description Needs EDIT on Plans & billing and `billing:adjust`.
         */
        post: operations["clearStaffBillingChargeGate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs/{orgId}/trial": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Override the trial: before activation its length, while trialing it ends N days after it started (billing:adjust)
         * @description Needs EDIT on Plans & billing and `billing:adjust`. The seller service validates and authorises again.
         */
        post: operations["overrideStaffBillingTrial"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs/{orgId}/invoices/{invoiceId}/waive": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Waive the rest of an open invoice (billing:adjust)
         * @description Needs EDIT on Plans & billing and `billing:adjust`. The seller service validates and authorises again.
         */
        post: operations["waiveStaffBillingInvoice"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs/{orgId}/plan": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Move the organisation to a plan, including an invite-only (negotiated) one, with a reason (billing:adjust; S306)
         * @description Needs EDIT on Plans & billing and `billing:adjust`. seller `POST /v1/orgs/{orgId}/billing/staff-plan`: upgrades prorate
         *     now; downgrades and cadence changes apply at renewal. Recorded with the reason and shown in the seller billing panel.
         */
        post: operations["assignStaffPlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/orgs/{orgId}/plan-fee-collection": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Override how the organisation pays its plan: from its sales first or always by card (billing:adjust, reason code; S298)
         * @description Needs EDIT on Plans & billing and `billing:adjust`. seller `PUT /v1/orgs/{orgId}/billing/plan-fee-collection/override`:
         *     audited with a billing reason code (OTHER needs a note) and applied from the organisation's next invoice. 409
         *     `seller.earnings-collection-off` while the kill switch COLLECT_PLAN_FEES_FROM_EARNINGS is off.
         */
        put: operations["overrideStaffPlanFeeCollection"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/billing/settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Platform billing settings: whether sellers may pay their plan from their sales (kill switch) and the default for new sellers (S298)
         * @description Needs VIEW on Plans & billing. seller `GET /v1/billing/settings`.
         */
        get: operations["getStaffBillingSettings"];
        /**
         * Set how new sellers pay their plan unless they choose (plans:manage; S298)
         * @description Needs EDIT on Plans & billing and `plans:manage`. seller `PUT /v1/billing/settings`; existing sellers keep their choice.
         */
        put: operations["updateStaffBillingSettings"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/fees": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Fee schedule versions (active, scheduled, past, cancelled), the base of a new version, categories and limits
         * @description payments `GET /v1/fee-schedules` (payments:read) and catalog `GET /v1/categories` (departments are the commission
         *     categories, lower-cased; `services` is always offered; categories schedules already use are added). Override
         *     organisations are named from seller `GET /v1/orgs/directory` (best effort; `orgNamesAvailable`). Needs VIEW on
         *     the fees module; `canConfigure` (payments:configure plus EDIT) unlocks creating and cancelling. Rates are basis
         *     points (1250 = 12.50 %): the console shows and takes percentages with two decimals.
         */
        get: operations["getStaffFees"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/fees/orgs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Find a seller or provider organisation by name for a commission override
         * @description seller `GET /v1/orgs/directory` (pages of 200, up to 1,000 organisations), filtered by name or exact id. AfroTransact
         *     Retail is returned with `selectable: false` — it is the merchant of record and pays no commission and no fees.
         */
        get: operations["searchFeeOrgs"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/fees/schedules": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Publish a new fee schedule version (Super Admin / Finance)
         * @description payments `POST /v1/fee-schedules` (payments:configure). The BFF validates first: effective date in the future and
         *     not before the latest scheduled version (or `immediate: true`, refused while a version is scheduled), commission
         *     0–50 %, card fee 0–10 % + up to $10.00, FBA fee up to $100.00, a note of 3–500 characters, unique categories and
         *     (organisation, category) overrides, and no override for AfroTransact Retail. Errors are keyed by property
         *     (`categoryRates.2.commissionBps`). Versions are immutable; the new one applies to orders placed from its date.
         */
        post: operations["createFeeSchedule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/fees/schedules/{scheduleId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cancel a scheduled fee schedule version before it takes effect (Super Admin / Finance)
         * @description payments `POST /v1/fee-schedules/{id}/cancel` (payments:configure). Only scheduled versions (409 once in force or
         *     already cancelled). The version is kept and marked cancelled (who, when, why) — never deleted — and payments
         *     audits it.
         */
        post: operations["cancelFeeSchedule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/fees/delivery-quote": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Price one AfroTransact Delivery trip under the live fee schedule (S255)
         * @description payments `POST /v1/delivery-pricing/quote` (staff payments:read): the customer charge and the seller's pickup-delivery
         *     fee for a seller organisation, a road distance and a number of packages, under the version in force at `at`
         *     (default now). `available: false` with `BEYOND_MAX_RADIUS` beyond the version's radius or `NOT_CONFIGURED` when it
         *     has no delivery pricing. Needs VIEW on the fees module. Drafts are previewed in the console with the same rules.
         */
        post: operations["quoteStaffDelivery"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/tax-rates/import": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Bulk upsert US state rules and local ZIP rates from CSV (USA pilot)
         * @description tax-returns `POST /v1/tax/rates/import` (tax:configure and EDIT on fees). Header row
         *     `kind,country,state,name,zipPrefix,generalRate,groceryRate,preparedFoodRate,shippingTaxable,facilitator,localRate,appliesToGrocery`
         *     with `kind=STATE|LOCAL`. Country column is stored for future markets; quotes today use US rules only.
         */
        post: operations["importStaffTaxRates"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/fees/negotiated-rates": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Negotiated commission and connection-fee rates (server-driven table)
         * @description payments `GET /v1/negotiated-rates` (staff payments:read), with organisation names from seller's directory. A
         *     negotiated rate wins over the organisation's plan rate, the category rate and the default while it is active
         *     (its category before `*`). Filters: one organisation (the seller billing panel lists its rates this way), type,
         *     status, and `q` over organisation, reason, reference and negotiator. Needs VIEW on the fees module.
         */
        get: operations["listNegotiatedRates"];
        put?: never;
        /**
         * Negotiate a commission or connection fee with an organisation (audited)
         * @description payments `POST /v1/negotiated-rates` (payments:configure and EDIT on fees). One category or every category, from
         *     `startsAt` (default now, never in the past) to `endsAt` (optional; a job records the expiry). The reason is required
         *     and kept in the audit trail with who negotiated it and the contract or ticket reference. 409 when another rate of the
         *     organisation, type and category overlaps; AfroTransact Retail never pays fees (422). Payments already created keep
         *     the rate snapshotted on their lines.
         */
        post: operations["createNegotiatedRate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/fees/negotiated-rates/{rateId}/end": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * End a negotiated rate now, with a reason (audited)
         * @description payments `POST /v1/negotiated-rates/{id}/end` (payments:configure and EDIT on fees). 409 when it already ended or expired.
         */
        post: operations["endNegotiatedRate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/fees/calculator": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Rate calculator: what one organisation pays on one kind and category now, and why
         * @description payments `GET /v1/fee-schedules/effective?kind=` (staff payments:read): kind PRODUCT is the commission, SERVICE the
         *     connection fee of a service category. The first match wins: an active negotiated rate (category, then `*`), the
         *     organisation's plan rate (category, then the plan's `*`), the category rate, the default; AfroTransact Retail pays
         *     none (RETAIL). Needs VIEW on the fees module.
         */
        get: operations["calculateFeeRate"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/fees/orgs/{orgId}/negotiated-plan": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Negotiate a plan price for one organisation (private, invite-only plan)
         * @description Over the seller service's plan catalogue (plans:manage and EDIT on plans): copies the base plan's kind, features,
         *     limits and trial into a new INVITE_ONLY plan `<base>_<org>` at the negotiated prices (quarterly and annual default
         *     to the catalogue's rule), invites the organisation and publishes it. The
         *     seller then chooses it in Plan & billing; it stays on its current plan until it does. 409 when that private plan
         *     already exists.
         */
        post: operations["negotiatePlanPrice"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/dispatch/pickups": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Seller pickups waiting for a route (AfroTransact Delivery from the seller's store)
         * @description fulfilment `GET /v1/dispatch/shipments` (delivery:dispatch), keeping the SELLER-origin entries. Pickups already
         *     routed (auto-assigned to the nearest driver) are not in the pool; open them by shipment id. Needs VIEW on dispatch.
         */
        get: operations["listStaffPickups"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/dispatch/pickups/{shipmentId}/driver-suggestions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Top 5 available drivers for a seller pickup, nearest first
         * @description fulfilment `GET /v1/dispatch/pickups/{id}/driver-suggestions`: fresh positions in the pickup's delivery zone,
         *     access active, an ASSIGNED or IN_PROGRESS route today, vehicle inspection passed, room left; ranked by geo road
         *     distance (straight line when geo is down — `distanceMethod: STRAIGHT_LINE`, `roadMiles: null`).
         */
        get: operations["getPickupDriverSuggestions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/dispatch/pickups/{shipmentId}/assign": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Assign or override the route of a seller pickup (reason required)
         * @description fulfilment `POST /v1/dispatch/pickups/{id}/assign {routeId, reason}` (delivery:dispatch, EDIT on dispatch). The
         *     reason (5–500 characters) is logged as a DISPATCH_OVERRIDE. 409 `CONFLICT` when the pickup can't move any more
         *     (collected, or its drop-off carries other shipments).
         */
        post: operations["assignPickup"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/dispatch/pickups/{shipmentId}/assignments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Assignment history of a seller pickup (automatic nearest-driver and dispatch overrides)
         * @description fulfilment `GET /v1/dispatch/pickups/{id}/assignments`, newest first.
         */
        get: operations["listPickupAssignments"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/dispatch/runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Delivery runs in dispatch, latest delivery day first (S264)
         * @description fulfilment `GET /v1/dispatch/runs` (delivery:dispatch or fulfilment:operate), with each run's lifecycle status from
         *     delivery-runs when it can be read. A run appears when delivery-runs confirms it (a closed pre-order window's paid
         *     orders). Needs VIEW on dispatch; `canManage` says whether the caller may plan or move a run.
         */
        get: operations["listStaffRuns"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/dispatch/runs/{runId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One run, its routes with every stop (seller pickups before drops) and how far it has got (S264)
         * @description fulfilment `GET /v1/dispatch/runs/{runId}` plus delivery-runs `GET /v1/runs/{id}` and `/progress` (the run's
         *     lifecycle status and its orders per stage, when readable). Needs VIEW on dispatch.
         */
        get: operations["getStaffRun"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/dispatch/runs/{runId}/plan": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Plan the run's routes (again) (S264)
         * @description fulfilment `POST /v1/dispatch/runs/{runId}/plan` (delivery:dispatch, EDIT on dispatch). Only shipments that are not
         *     on a live route get new routes, so planning twice is harmless. Use it when planning could not finish (the run says
         *     why in `planError`), after a route was cancelled, or for an order that arrived late.
         */
        post: operations["planStaffRun"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/dispatch/runs/{runId}/advance": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Move a run to its next step by hand when the drivers' events did not arrive (S264)
         * @description delivery-runs `POST /v1/runs/{id}/start-collecting`, `/out-for-delivery` or `/complete` (delivery-runs:manage, EDIT
         *     on dispatch). A run normally moves by itself from the drivers' shipment events (the first pickup, the last pickup,
         *     the last delivery); this is the override for when those did not arrive. It never marks packages delivered: those
         *     are delivered in the driver app or by staff in orders. 409 `CONFLICT` when the step does not follow the run's
         *     status; 422 for a step other than COLLECTING, OUT_FOR_DELIVERY or COMPLETED.
         */
        post: operations["advanceStaffRun"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/ship-from-locations/{locationId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * A seller's ship-from / pickup location (address, geocoded position, pickup hours)
         * @description seller `GET /v1/ship-from-locations/{id}` (staff seller:ship-from). Needs VIEW on dispatch.
         */
        get: operations["getStaffShipFromLocation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/notifications/templates": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Every notification template key with its channels, locales and latest version
         * @description notifications `GET /v1/templates` (latest version of every key, locale and channel) grouped by key; `q` filters
         *     by key, category or channel. Needs `notifications:read` (VIEW on the notifications module); `canEdit` says
         *     whether the staff member holds `notifications:templates:write`.
         */
        get: operations["listNotificationTemplates"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/notifications/templates/{key}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * A template key's versions per locale and channel, with its sample data
         * @description notifications `GET /v1/templates?key=` (every version, newest first per locale and channel) and
         *     `GET /v1/templates/{key}/sample`: the fields the key's event carries in production (a contract-shaped event run
         *     through the real ingest), used to prefill the preview. When the sample cannot be read the editor starts from
         *     empty data (`source: none`). Needs `notifications:read`.
         */
        get: operations["getNotificationTemplate"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/notifications/templates/{key}/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Render a stored version or an unsaved draft with sample data
         * @description notifications `POST /v1/templates/{key}/preview` with the service's real renderer. `version` 0 renders the
         *     latest stored version (locale fallback fr-CA → fr → en); `draft` renders unsaved content (the editor's live
         *     preview). A template that does not compile, or reads a field the data lacks, is a 422 on the part that failed
         *     (`subject`, `bodyText`, `bodyHtml`, prefixed `draft.` for drafts, else `data`). SMS and WhatsApp previews carry
         *     the GSM-7 / UCS-2 segment count. Needs `notifications:read`.
         */
        post: operations["previewNotificationTemplate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/notifications/templates/{key}/versions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Create the next version of a template for one locale and channel
         * @description notifications `POST /v1/templates` (`notifications:templates:write`). Before storing, the BFF checks that every
         *     placeholder (`{{.field}}`, `{{if .field}}`, `{{$.field}}`) is a field of the key's sample data (keys without a
         *     sample are only render-checked), renders the draft with the sample data, and refuses SMS longer than 3
         *     segments. Emails need a subject and an HTML body; push notifications need a title; only emails have HTML.
         *     The new version is what every later send of the key uses.
         */
        post: operations["createNotificationTemplateVersion"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/notifications/templates/{key}/test-send": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Send a stored version or a draft to your own email or phone
         * @description notifications `POST /v1/templates/{key}/test-send` (`notifications:templates:write`). The destination is
         *     always the signed-in staff member's own email (email) or phone (SMS, WhatsApp) from their access token, never
         *     an address in the request; email subjects are prefixed `[TEST]`. Push cannot be test-sent (422 on `channel`);
         *     no address on the account is a 422 without field errors.
         */
        post: operations["testSendNotificationTemplate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/identity/token-inspector": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Decode a JWT and check its signature and issuer
         * @description identity `POST /v1/admin/identity/token-inspector` (`identity:admin`). The pasted token is decoded and verified in
         *     memory at identity: it is never stored, cached or logged, it is not echoed back, and the audit trail records the
         *     subject and key id only. A bad token is a 200 that says what is wrong (`valid`, `problems`), not an error; empty or
         *     oversized input is a 422 on `token`.
         */
        post: operations["inspectIdentityToken"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/identity/documents": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The OIDC discovery document and the JWKS
         * @description identity `GET /v1/admin/identity/discovery` (`identity:admin`). The JWKS holds public keys only.
         */
        get: operations["getIdentityDocuments"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Pre-order windows (tabs All / Open / Scheduled / Closed / Drafts)
         * @description delivery-runs `GET /v1/preorders?status=&q=&page=&size=` (delivery-runs:read). Needs VIEW on preorders.
         */
        get: operations["listStaffPreorders"];
        put?: never;
        /**
         * Open a pre-order (saved as a draft)
         * @description delivery-runs `POST /v1/preorders` (preorders:manage, EDIT). `Idempotency-Key` is relayed.
         */
        post: operations["createStaffPreorder"];
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Switch several pre-orders on or off (bulk), each on its own
         * @description delivery-runs `PATCH /v1/preorders {ids[], active}` (preorders:manage, EDIT). 1–100 ids. Each window's own
         *     switch only (the master switch is never touched); items[] say SWITCHED, UNCHANGED or SKIPPED (draft, delivered,
         *     unknown or failing its publish checks, with the reason).
         */
        patch: operations["switchStaffPreorders"];
        trace?: never;
    };
    "/v1/staff/preorders/settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Platform pre-order settings (master switch, fallback, placements, payment setup)
         * @description delivery-runs `GET /v1/preorder-settings`. Needs VIEW on preorders.
         */
        get: operations["getStaffPreorderSettings"];
        /**
         * Change the settings, including the payment setup (FULL only)
         * @description delivery-runs `PUT /v1/preorder-settings` (If-Match relayed; preorders:manage). Only FULL on preorders (Super
         *     Admin, Marketing) may change settings: Operations (EDIT) gets 403.
         */
        put: operations["updateStaffPreorderSettings"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/areas": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Coverage areas (country → region → city, on-count and window count)
         * @description delivery-runs `GET /v1/preorder-areas?country=&region=&q=&page=&size=`.
         */
        get: operations["listStaffPreorderAreas"];
        put?: never;
        /**
         * Add a coverage area ("US/TX" or "US/TX/Lubbock")
         * @description delivery-runs `POST /v1/preorder-areas {key}` (preorders:manage, EDIT).
         */
        post: operations["addStaffPreorderArea"];
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Turn areas on or off in bulk (a country or region at once)
         * @description delivery-runs `PATCH /v1/preorder-areas {keys[], on}` (preorders:manage, EDIT). 1 to 500 keys.
         */
        patch: operations["toggleStaffPreorderAreas"];
        trace?: never;
    };
    "/v1/staff/preorders/areas/{key}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * Remove a coverage area
         * @description delivery-runs `DELETE /v1/preorder-areas/{key}` (preorders:manage, EDIT). The key is one path segment with its
         *     slashes encoded (`US%2FTX%2FLubbock`). Answers the refreshed area page.
         */
        delete: operations["deleteStaffPreorderArea"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/areas/regions/{country}/{region}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                country: string;
                region: string;
            };
            cookie?: never;
        };
        /**
         * One state's / province's coverage for the in-place editor
         * @description delivery-runs `GET /v1/preorder-areas/regions/{country}/{region}` (VIEW): the "Every city" row (or null), every
         *     listed city with its switch and the live pre-orders selling there, and `version` (also the ETag) to send back
         *     as If-Match.
         */
        get: operations["getStaffPreorderRegion"];
        /**
         * Edit a state's / province's cities in place (If-Match)
         * @description delivery-runs `PUT /v1/preorder-areas/regions/{country}/{region}` (preorders:manage, EDIT). Full replacement:
         *     listed cities are added or switched, cities left out are removed, everyCity adds / switches / (null) removes the
         *     "Every city" row. 412 when anyone changed the region since the version sent; 409 `delivery-runs.area-in-use`
         *     when a removal would leave a live pre-order's place uncovered (switch it off instead).
         */
        put: operations["updateStaffPreorderRegion"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/places/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Country / region / city suggestions for the area picker
         * @description delivery-runs `GET /v1/places/search?country=&region=&q=` (geo-service backed, built-in list fallback) → {items, regionLabel}.
         */
        get: operations["searchStaffPreorderPlaces"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/address-verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Verify a new late-add address (geo, Google Places with Nominatim fallback)
         * @description geo `POST /v1/addresses/validate`. `verified` for VALID or CORRECTED; `placeId` is geo's place id, else a stable
         *     reference of the standardised address (delivery-runs re-verifies it). Needs the late-add ability.
         */
        post: operations["verifyStaffPreorderAddress"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/uploads": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Sign a direct media upload for a creative
         * @description media `POST /v1/uploads` (purpose storefront; JPEG, PNG or WebP up to 10 MB). The console PUTs the file to
         *     `upload.url`, then sets the slot (`PUT /v1/staff/preorders/{id}/creatives/{slot} {assetId}`), where the BFF
         *     finalises the asset with media and delivery-runs verifies it. Needs EDIT and preorders:manage.
         */
        post: operations["requestStaffPreorderUpload"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/products": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The window's products (late-add item list)
         * @description delivery-runs `GET /v1/preorders/{id}/products` → items [{atin, name, price (minor units), sellerName}].
         */
        get: operations["listStaffPreorderProducts"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One window (detail header, wizard)
         * @description delivery-runs `GET /v1/preorders/{id}`.
         */
        get: operations["getStaffPreorder"];
        /**
         * Edit a window (If-Match)
         * @description delivery-runs `PUT /v1/preorders/{id}` (If-Match relayed; preorders:manage, EDIT).
         */
        put: operations["updateStaffPreorder"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Switch one pre-order on or off
         * @description delivery-runs `PATCH /v1/preorders/{id} {active}` (preorders:manage, EDIT; If-Match relayed). Sets this
         *     window's own switch only; it is live when the master switch AND its own switch AND one of its areas are on
         *     (`switchState`: ON, PAUSED, GLOBAL_OFF, AREA_OFF, NOT_LIVE). Off stops the storefront exposure (resolution,
         *     banners, eligibility, the cart's pre-order packages); orders already placed are kept. On re-runs the publish
         *     checks. Idempotent; drafts and delivered windows answer 409.
         */
        patch: operations["switchStaffPreorder"];
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/publish": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Publish a draft (422 lists the failed checks)
         * @description delivery-runs `POST /v1/preorders/{id}/publish` (preorders:manage, EDIT).
         */
        post: operations["publishStaffPreorder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/pause": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Pause a window
         * @description delivery-runs `POST /v1/preorders/{id}/pause` (preorders:manage, EDIT).
         */
        post: operations["pauseStaffPreorder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/resume": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Resume a paused window
         * @description delivery-runs `POST /v1/preorders/{id}/resume` (preorders:manage, EDIT).
         */
        post: operations["resumeStaffPreorder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/decision": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Decide a window that closed below its minimum (cancel and refund everyone, or proceed)
         * @description delivery-runs `POST /v1/preorders/{id}/decision` (preorders:manage, EDIT; If-Match relayed). Only a window in
         *     `AWAITING_DECISION` (closed with fewer orders than its `minOrders`; 409 otherwise). `CANCEL` cancels the window:
         *     every order is refunded in full, delivery fee included, and its stock released (orders-service); the window
         *     becomes `CANCELLED` and each order shows its `refundStatus`. `PROCEED` goes ahead below the minimum: the window
         *     closes and hands its paid orders to a run on the scheduler's next tick. The `reason` is required (422) and is
         *     logged with who and when (`GET .../decisions`).
         */
        post: operations["decideStaffPreorder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cancel a window and refund every order in full
         * @description delivery-runs `POST /v1/preorders/{id}/cancel` (preorders:manage, EDIT; If-Match relayed). Any published window
         *     that is not delivered or cancelled: it becomes `CANCELLED`, a run it was handed to is cancelled too (409 once
         *     that run is out for delivery) and every order is refunded in full, delivery fee included, with its stock
         *     released. The reason is required and logged with who and when.
         */
        post: operations["cancelStaffPreorder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/close-early": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Close an open window early once its minimum is met
         * @description delivery-runs `POST /v1/preorders/{id}/close-early` (preorders:manage, EDIT; If-Match relayed). Only an `OPEN`
         *     window whose `orderCount` has reached its `minOrders` (`canCloseEarly`; 422 `delivery-runs.preorder-minimum-not-met`
         *     otherwise, 409 when not open). The close date moves to now and the scheduler closes the window on its next tick.
         *     The reason is required and logged.
         */
        post: operations["closeEarlyStaffPreorder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/decisions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The window's decision log (who, when, why)
         * @description delivery-runs `GET /v1/preorders/{id}/decisions`, oldest first.
         */
        get: operations["listStaffPreorderDecisions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/refunds": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Refund progress of a cancelled window
         * @description delivery-runs `GET /v1/preorders/{id}/refunds`: orders per refund status, `done` once none is pending or failed.
         *     Each order's own `refundStatus` (PENDING, REFUNDED, VOIDED, FAILED), amount and error are on the orders list.
         */
        get: operations["getStaffPreorderRefunds"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/creatives/{slot}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Set a creative slot from an uploaded media asset
         * @description delivery-runs `PUT /v1/preorders/{id}/creatives/{slot} {assetId}` (verifies with media-service and stores the
         *     size; a ratio more than 12 % off spec is flagged RATIO_OFF). preorders:manage, EDIT.
         */
        put: operations["putStaffPreorderCreative"];
        post?: never;
        /**
         * Remove a creative
         * @description delivery-runs `DELETE /v1/preorders/{id}/creatives/{slot}` (preorders:manage, EDIT).
         */
        delete: operations["deleteStaffPreorderCreative"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The window's orders (one search over every column, sort by any column)
         * @description delivery-runs `GET /v1/preorders/{id}/orders?status=&channel=&page=&size=`. delivery-runs searches order,
         *     customer, phone, address and ZIP only, so with `q` or `sort` the BFF reads the window's orders (up to 5,000),
         *     matches `q` against every column the table shows (order, customer, phone, email, address, city, ZIP, delivery
         *     note, placed, channel, tracking tag, units, fulfilment, total as text, status) and sorts and pages them itself;
         *     the page then also carries `sort` and `partial`. Export the table with `POST /v1/staff/exports`
         *     (`table: preorder-orders`, `parent: <preorderId>`).
         */
        get: operations["listStaffPreorderOrders"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * KPIs, orders by day, by channel, top products, door / pickup split, placements
         * @description delivery-runs `GET /v1/preorders/{id}/summary`.
         */
        get: operations["getStaffPreorderSummary"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/exports/{file}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Download a CSV (streamed from delivery-runs)
         * @description delivery-runs `GET /v1/preorders/{id}/exports/{file}` (preorders:export; audit event preorders.export.created),
         *     relayed without buffering. `orders-filtered.csv` takes the orders list's status / channel / q.
         */
        get: operations["exportStaffPreorder"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/late-orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add a late order for a customer (tagged Agent (late))
         * @description delivery-runs `POST /v1/preorders/{id}/late-orders` (preorders:late-add; APPROVE, EDIT or FULL on preorders),
         *     which creates the on-behalf order in orders-service (saved card charged off-session, or a pay-now link sent by
         *     SMS when lateAdd.paymentLink is allowed in the payment settings). Only while the window is CLOSED (or OPEN, as
         *     an assisted order); refused once DELIVERED. `Idempotency-Key` is required (422 without it): the console
         *     makes one when the dialog opens and reuses it on every retry, so a retry returns the same order and charges
         *     once. PAYMENT_LINK texts a pay-now link to AfroTransact's own pay page (never a Stripe-hosted page).
         *     409 when the same items were just added for the customer under another key.
         */
        post: operations["addStaffPreorderLateOrder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/delivery-fees": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Every delivery fee rule (one per delivery method and currency)
         * @description delivery-runs `GET /v1/delivery-fees` (delivery-runs:read; module `preorders`, any level). The screen shows three
         *     sections, one per method: AfroTransact's own delivery inside covered locations (a flat fee by distance band),
         *     carrier shipping beyond coverage (the carrier-gateway quote plus handling, a fallback fee, free over a
         *     threshold) and pickup (often 0). Answers carry `can.manage`.
         */
        get: operations["listStaffDeliveryFees"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/delivery-fees/changes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Audit trail of the delivery fees (who changed what, when and why)
         * @description delivery-runs `GET /v1/delivery-fees/changes`, newest first: every version of every rule (or one method and
         *     currency) with the rule before and after, the staff subject, the time and the reason.
         */
        get: operations["listStaffDeliveryFeeChanges"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/delivery-fees/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * What would a customer pay?
         * @description delivery-runs `POST /v1/delivery-fees/preview`: the resolution checkout runs. A fee set on the pre-order itself
         *     (`windowId`) wins, then the method's rule: AFROTRANSACT_DELIVERY by `distanceMiles` (or the window's own
         *     distance), PICKUP, CARRIER by `carrierQuoteMinor` and `itemsMinor`. `applies` is false when no rule can price
         *     it: no rule, or the address is beyond how far AfroTransact delivers (the order ships by carrier).
         */
        post: operations["previewStaffDeliveryFee"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/delivery-fees/{method}/{currency}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Set the delivery fee of one method and currency
         * @description delivery-runs `PUT /v1/delivery-fees/{method}/{currency}` (preorders:manage, EDIT; If-Match relayed: the
         *     version seen, `0` when the rule does not exist yet). Creates the rule or makes its next version; a save that
         *     changes nothing makes no new version. AFROTRANSACT_DELIVERY takes `bands` (1 to 12 of `fromMiles`, `toMiles`,
         *     `feeMinor`; the first starts at 0 miles and each starts where the last ends; the last band's end is how far
         *     AfroTransact delivers, beyond it the order ships by carrier), CARRIER `handlingMinor` (added to the carrier
         *     quote), `fallbackMinor` (when there is no quote) and `freeAboveMinor` (goods subtotal from which it is free; null
         *     = never), PICKUP `feeMinor`. Fees are 0 to 10000 minor units. The `reason` is required (422) and is logged with
         *     who and when. Orders already placed keep the rule version they were charged by.
         */
        put: operations["updateStaffDeliveryFee"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/economics-settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The driver cost model behind run economics
         * @description delivery-runs `GET /v1/economics-settings` (delivery-runs:read; module `preorders`, any level). Cost per driver hour
         *     and per vehicle mile, the time a seller sweep, a doorstep stop, a pickup-point stop and a handover take, and whether
         *     the drive back counts: the numbers every run and window P&L and break-even is calculated from. Version 0
         *     (`source` CONFIG) means nobody saved yet and the environment's defaults apply. The ETag carries the version.
         */
        get: operations["getStaffEconomicsSettings"];
        /**
         * Change the driver cost model
         * @description delivery-runs `PUT /v1/economics-settings` (preorders:manage, EDIT; If-Match relayed: the version seen, `0` for the
         *     environment's defaults). Makes the next version; a save that changes nothing makes no new version. Money is in minor
         *     units (cost per hour 0 to 1000.00, per mile 0 to 100.00), times in minutes (0 to 1440). The `reason` is required
         *     (422) and is logged with who and when. Runs and windows are priced with the new numbers from the next calculation.
         */
        put: operations["updateStaffEconomicsSettings"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/economics-settings/changes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Audit trail of the driver cost model (who changed what, when and why)
         * @description delivery-runs `GET /v1/economics-settings/changes`, newest first: every save with the numbers before (the
         *     environment's defaults for the first save) and after, the staff subject, the time and the reason.
         */
        get: operations["listStaffEconomicsChanges"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/economics-preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Break-even for a window being created, beside its minimum order count
         * @description delivery-runs `POST /v1/preorders/economics-preview` (delivery-runs:read). The location is the one named, else the
         *     active one matching the first city target. Gives the delivery fee per order the plan assumes (the window's own fee,
         *     else the fee rule for the location's distance), the cost of the run that would deliver it, the orders needed to
         *     break even and whether the minimum reaches them (`minimumCoversBreakEven`). `economics` is null when no active
         *     location serves the targets or geo-service cannot measure the route.
         */
        post: operations["previewStaffWindowEconomics"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/preorders/{preorderId}/economics": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * A window's orders, revenue, delivery fees, cost, margin and break-even
         * @description delivery-runs `GET /v1/preorders/{id}/economics` (delivery-runs:read). At the orders placed so far: what customers
         *     paid, the delivery fees collected, the estimated cost of the run that delivers the window, the contribution margin
         *     and the break-even orders. A cancelled window reports the orders refunded (`ordersRefunded`, `refundedMinor`,
         *     `refundsSettled`) instead of revenue.
         */
        get: operations["getStaffPreorderEconomics"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/delivery-runs/locations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The towns delivery runs serve
         * @description delivery-runs `GET /v1/locations` (delivery-runs:read).
         */
        get: operations["listStaffRunLocations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/delivery-runs/locations/{locationId}/history": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * A location's runs (orders per run), subscriber counts and subscriber growth
         * @description delivery-runs `GET /v1/locations/{id}/history` (delivery-runs:read): the latest runs with their orders and the
         *     delivery fees they earn (a cancelled run: `ordersRefunded`, no fees), the subscribers (total, active, pending, per
         *     channel) and 12 months of subscriber growth.
         */
        get: operations["getStaffRunLocationHistory"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/delivery-runs/runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Delivery runs, newest window first
         * @description delivery-runs `GET /v1/runs` (delivery-runs:read).
         */
        get: operations["listStaffRuns"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/delivery-runs/runs/{runId}/pnl": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * A run's P&L before and after it
         * @description delivery-runs `GET /v1/runs/{id}/pnl` (delivery-runs:read). `BEFORE`: the estimate at the orders reserved so far.
         *     `AFTER`: the completed run at its orders. `CANCELLED`: nothing earned or spent; `ordersRefunded` and
         *     `refundedFeesMinor` say what went back to customers. `commissionShareMinor` is the commission attributed to the run,
         *     reported by analytics.
         */
        get: operations["getStaffRunPnl"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/delivery-runs/economics-preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cost, break-even and P&L of a run being planned
         * @description delivery-runs `POST /v1/runs/economics-preview` (delivery-runs:read). Nulls take the location's defaults;
         *     `expectedOrders` defaults to the minimum. 502 when geo-service cannot measure the route.
         */
        post: operations["previewStaffRunEconomics"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/advertorials": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Location advertorials with views, clicks and orders
         * @description delivery-runs `GET /v1/advertorials` (delivery-runs:read), newest first. Each is words for the visitors of the area a
         *     pre-order window or delivery run serves, shown with a live closing countdown while ordering is open and over when it
         *     closes or is cancelled. `results` are its views, clicks and (windows) the orders whose tracking tag carries it.
         */
        get: operations["listStaffAdvertorials"];
        put?: never;
        /**
         * Write an advertorial (a variant) for a window or run
         * @description delivery-runs `POST /v1/advertorials` (preorders:manage, EDIT). Created as a draft; publish it to make it live. Several
         *     variants (A, B…) of one window or run share its visitors by weight. Variant defaults to the next free label.
         */
        post: operations["createStaffAdvertorial"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/advertorials/generate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Draft the advertorial of a window or run from its data
         * @description delivery-runs `POST /v1/advertorials/generate` (preorders:manage, EDIT). Town, delivery day, closing time and fee
         *     become the words, live at once. Asking again re-drafts the generated one (a variant staff edited is left alone). 404
         *     for an unknown window or run; 409 for one that is over.
         */
        post: operations["generateStaffAdvertorial"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/advertorials/{advertorialId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * One advertorial with its results
         * @description delivery-runs `GET /v1/advertorials/{id}` (delivery-runs:read). The ETag carries the version.
         */
        get: operations["getStaffAdvertorial"];
        /**
         * Edit an advertorial
         * @description delivery-runs `PUT /v1/advertorials/{id}` (preorders:manage, EDIT; If-Match relayed). Not an ended one. The words stop
         *     following the window or run.
         */
        put: operations["updateStaffAdvertorial"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/advertorials/{advertorialId}/{action}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Publish, pause or end an advertorial
         * @description delivery-runs `POST /v1/advertorials/{id}/publish|pause|end` (preorders:manage, EDIT; If-Match relayed). publish: draft
         *     or paused to live; pause: live to paused; end: over for good (reason STAFF).
         */
        post: operations["actStaffAdvertorial"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/customers/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Late-add customer picker (identity people search with saved addresses)
         * @description identity `GET /v1/admin/users?q=&size=10` (customers only) and, per hit, `GET /v1/admin/users/{id}/addresses`
         *     (best effort: `addressesUnavailable` when identity can't list them — enter a new address). Needs the late-add
         *     ability on preorders. Fewer than 2 characters answer no one. `windowId` is the window the late add is for
         *     (validated; reserved for ranking customers of the window's areas first). Addresses are default first; the
         *     customer's city / region / country are the default address's.
         */
        get: operations["searchStaffCustomers"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/markets/countries": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Every country with its status and counts, plus the unpublished changes
         * @description content `GET /v1/markets/draft/countries` and `GET /v1/markets/draft/changes` (VIEW).
         */
        get: operations["listStaffMarketCountries"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/markets/countries/{code}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["MarketCountryCode"];
            };
            cookie?: never;
        };
        /**
         * One country with its states, cities and what depends on them
         * @description content `GET /v1/markets/draft/countries/{code}` (VIEW) plus open pre-orders per state and city (delivery-runs, best effort).
         */
        get: operations["getStaffMarketCountry"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** Change a country's status or launch note (If-Match = the country's version) */
        patch: operations["updateStaffMarketCountry"];
        trace?: never;
    };
    "/v1/staff/markets/countries/{code}/regions": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["MarketCountryCode"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add a state / province (from the gazetteer); it starts PLANNED
         * @description content `POST /v1/markets/draft/countries/{code}/regions`. 409 when the state is already LIVE or PLANNED.
         */
        post: operations["addStaffMarketRegion"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/markets/countries/{code}/regions/{region}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["MarketCountryCode"];
                region: components["parameters"]["MarketRegionCode"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Change a state's status, city coverage or capability switches (If-Match = the state's version)
         * @description Only what is present changes. LIVE switches the marketplace on; PLANNED and OFF switch it off (and every
         *     capability with it; LIVE cities become PLANNED). everyCity true covers every city (listed cities are overrides);
         *     false serves only the cities listed LIVE.
         */
        patch: operations["updateStaffMarketRegion"];
        trace?: never;
    };
    "/v1/staff/markets/countries/{code}/regions/{region}/cities": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["MarketCountryCode"];
                region: components["parameters"]["MarketRegionCode"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Append cities to a state (If-Match = the state's version)
         * @description Cities already listed keep their status; new ones default to PLANNED. A LIVE city needs the state LIVE (422).
         */
        post: operations["addStaffMarketCities"];
        delete?: never;
        options?: never;
        head?: never;
        /** Switch listed cities to LIVE, PLANNED or OFF (If-Match = the state's version) */
        patch: operations["setStaffMarketCities"];
        trace?: never;
    };
    "/v1/staff/markets/countries/{code}/regions/{region}/cities/{city}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["MarketCountryCode"];
                region: components["parameters"]["MarketRegionCode"];
                city: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /**
         * Remove a listed city (If-Match = the state's version)
         * @description 409 `markets.city-in-use` while an open pre-order sells there (switch it off instead).
         */
        delete: operations["removeStaffMarketCity"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/markets/publications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Publish the draft as a new version (eligibility, checkout and the deliver-to check read it)
         * @description content `POST /v1/markets/publications`. 409 when there is nothing to publish.
         */
        post: operations["publishStaffMarkets"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/places": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Browse geo's gazetteer for the location pickers (Markets and Pre-order areas)
         * @description No country → every country; country → its states / provinces (label = what the country calls them);
         *     country + region → its cities (q filters, limit at most 5000, total = matches before the limit). Needs VIEW on
         *     Markets or Pre-orders.
         */
        get: operations["listStaffPlaces"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/flow": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The pipeline from a purchase order to "Sold & shipped by AfroTransact" with live counts
         * @description Nine steps (create the product, raise a PO, approve & send, announce the inbound, transit, receive & count,
         *     lots into stock, sell on the storefront, match the invoice) with what is waiting at each, counted from
         *     retail-service (items, POs by status, open goods receipts, live listings, invoice exceptions) and
         *     inventory-service (inbound shipments by status, sellable lots). A step whose service cannot be reached has
         *     `count: null` and names it in `unavailable`; retail steps need the `retail` module at VIEW. Module `inventory`
         *     at VIEW.
         */
        get: operations["getStaffInventoryFlow"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/trace": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Follow one product from its retail item to the product page
         * @description Given a variant ATIN and / or an inventory SKU: the retail item and its listing, the latest inbound shipment,
         *     the sellable lots (next pick by FEFO), availability, the open Fulfilled-by-AfroTransact offer (its inventory SKU
         *     must be the SKU the lots are under, else BROKEN), the search index and the public buy box the product page
         *     shows (badges, delivery days). Module `inventory` at VIEW.
         */
        get: operations["getStaffInventoryTrace"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/overview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/overview`
         * @description Mirrors inventory `GET /v1/overview` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again).
         */
        get: operations["getStaffOverview"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/nodes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/nodes`
         * @description Mirrors inventory `GET /v1/nodes` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again).
         */
        get: operations["getStaffNodes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/currencies": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/currencies`
         * @description Mirrors inventory `GET /v1/currencies` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again). The currencies stock can be received in (customers always pay in USD): `code`, `name`, `minorUnits`, and `base` for USD, first.
         */
        get: operations["getStaffCurrencies"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/exchange-rates/current": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/exchange-rates/current`
         * @description Mirrors inventory `GET /v1/exchange-rates/current` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again). The rate receiving suggests for every currency that has one: the latest on or before `asOf` (default today), as `currency`, `rateDate` and `rateToUsd` (US dollars per unit). The table itself is the `exchange-rates` Resource (Settings → Exchange rates).
         */
        get: operations["getStaffExchangeRatesCurrent"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/inbound": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/inbound/shipments`
         * @description Mirrors inventory `GET /v1/inbound/shipments` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again). The body is inventory's `InboundShipmentPage` (contracts/openapi/inventory.yaml).
         */
        get: operations["getStaffInbound"];
        put?: never;
        /**
         * inventory `POST /v1/inbound/shipments`
         * @description Mirrors inventory `POST /v1/inbound/shipments` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffInbound"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/inbound/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/inbound/shipments/{id}`
         * @description Mirrors inventory `GET /v1/inbound/shipments/{id}` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again). The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        get: operations["getStaffInboundById"];
        /**
         * inventory `PUT /v1/inbound/shipments/{id}`
         * @description Mirrors inventory `PUT /v1/inbound/shipments/{id}` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        put: operations["putStaffInboundById"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/inbound/{id}/tracking": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * inventory `PUT /v1/inbound/shipments/{id}/tracking`
         * @description Mirrors inventory `PUT /v1/inbound/shipments/{id}/tracking` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `InboundTrackingResult` (contracts/openapi/inventory.yaml).
         */
        put: operations["putStaffInboundTracking"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/inbound/{id}/ship": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/inbound/shipments/{id}/ship`
         * @description Mirrors inventory `POST /v1/inbound/shipments/{id}/ship` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffInboundShip"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/inbound/{id}/receipts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/inbound/shipments/{id}/receipts`
         * @description Mirrors inventory `POST /v1/inbound/shipments/{id}/receipts` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `InboundReceiptResult` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffInboundReceipts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/inbound/{id}/close": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/inbound/shipments/{id}/close`
         * @description Mirrors inventory `POST /v1/inbound/shipments/{id}/close` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffInboundClose"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/inbound/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/inbound/shipments/{id}/cancel`
         * @description Mirrors inventory `POST /v1/inbound/shipments/{id}/cancel` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffInboundCancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/inbound/{id}/labels": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/inbound/shipments/{id}/labels`
         * @description Mirrors inventory `GET /v1/inbound/shipments/{id}/labels` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again).
         */
        get: operations["getStaffInboundLabels"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/lots`
         * @description Mirrors inventory `GET /v1/lots` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again). The body is inventory's `LotPage` (contracts/openapi/inventory.yaml).
         */
        get: operations["getStaffLots"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots/markdown": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/lots/markdown`
         * @description Mirrors inventory `GET /v1/lots/markdown` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again).
         */
        get: operations["getStaffLotsMarkdown"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots/expiry-runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/lots/expiry-runs`
         * @description Mirrors inventory `POST /v1/lots/expiry-runs` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again).
         */
        post: operations["postStaffLotsExpiryRuns"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots/markdown-runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/lots/markdown-runs`
         * @description Mirrors inventory `POST /v1/lots/markdown-runs` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again).
         */
        post: operations["postStaffLotsMarkdownRuns"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/lots/{id}`
         * @description Mirrors inventory `GET /v1/lots/{id}` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again). The body is inventory's `Lot` (contracts/openapi/inventory.yaml).
         */
        get: operations["getStaffLotsById"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots/{id}/trace": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/lots/{id}/trace`
         * @description Mirrors inventory `GET /v1/lots/{id}/trace` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again). The body is inventory's `LotTrace` (contracts/openapi/inventory.yaml).
         */
        get: operations["getStaffLotsTrace"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots/{id}/quarantine": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/lots/{id}/quarantine`
         * @description Mirrors inventory `POST /v1/lots/{id}/quarantine` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `Lot` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffLotsQuarantine"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots/{id}/release": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/lots/{id}/release`
         * @description Mirrors inventory `POST /v1/lots/{id}/release` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `Lot` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffLotsRelease"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots/{id}/adjust": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/lots/{id}/adjust`
         * @description Mirrors inventory `POST /v1/lots/{id}/adjust` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `Lot` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffLotsAdjust"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots/{id}/transfer": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/lots/{id}/transfer`
         * @description Mirrors inventory `POST /v1/lots/{id}/transfer` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again).
         */
        post: operations["postStaffLotsTransfer"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/lots/{id}/write-off": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/lots/{id}/write-off`
         * @description Mirrors inventory `POST /v1/lots/{id}/write-off` 1:1 with the staff member's token (module `inventory` at APPROVE or above; inventory checks its own permissions again). The body is inventory's `Lot` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffLotsWriteOff"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/positions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/positions`
         * @description Mirrors inventory `GET /v1/positions` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again).
         */
        get: operations["getStaffPositions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/movements": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/movements`
         * @description Mirrors inventory `GET /v1/movements` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again).
         */
        get: operations["getStaffMovements"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/reservations/{orderId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/reservations/{orderId}`
         * @description Mirrors inventory `GET /v1/reservations/{orderId}` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again).
         */
        get: operations["getStaffReservationsById"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/reconciliation/latest": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/reconciliation/runs/latest`
         * @description Mirrors inventory `GET /v1/reconciliation/runs/latest` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again).
         */
        get: operations["getStaffReconciliationLatest"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/reconciliation/runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/reconciliation/runs`
         * @description Mirrors inventory `POST /v1/reconciliation/runs` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again).
         */
        post: operations["postStaffReconciliationRuns"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/cycle-counts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/cycle-counts`
         * @description Mirrors inventory `GET /v1/cycle-counts` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again). The body is inventory's `CycleCountPage` (contracts/openapi/inventory.yaml).
         */
        get: operations["getStaffCycleCounts"];
        put?: never;
        /**
         * inventory `POST /v1/cycle-counts`
         * @description Mirrors inventory `POST /v1/cycle-counts` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `CycleCount` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffCycleCounts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/cycle-counts/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/cycle-counts/{id}`
         * @description Mirrors inventory `GET /v1/cycle-counts/{id}` 1:1 with the staff member's token (module `inventory` at VIEW or above; inventory checks its own permissions again). The body is inventory's `CycleCount` (contracts/openapi/inventory.yaml).
         */
        get: operations["getStaffCycleCountsById"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/cycle-counts/{id}/counts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * inventory `PUT /v1/cycle-counts/{id}/counts`
         * @description Mirrors inventory `PUT /v1/cycle-counts/{id}/counts` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `CycleCount` (contracts/openapi/inventory.yaml).
         */
        put: operations["putStaffCycleCountsCounts"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/cycle-counts/{id}/post": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/cycle-counts/{id}/post`
         * @description Mirrors inventory `POST /v1/cycle-counts/{id}/post` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `CycleCount` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffCycleCountsPost"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/inventory/cycle-counts/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * inventory `POST /v1/cycle-counts/{id}/cancel`
         * @description Mirrors inventory `POST /v1/cycle-counts/{id}/cancel` 1:1 with the staff member's token (module `inventory` at EDIT or above; inventory checks its own permissions again). The body is inventory's `CycleCount` (contracts/openapi/inventory.yaml).
         */
        post: operations["postStaffCycleCountsCancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/items": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/items`
         * @description Mirrors retail `GET /v1/items` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffItems"];
        put?: never;
        /**
         * retail `POST /v1/items`
         * @description Mirrors retail `POST /v1/items` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `Item` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffItems"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/items/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/items/{id}`
         * @description Mirrors retail `GET /v1/items/{id}` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again). The body is retail's `Item` (contracts/openapi/retail.yaml).
         */
        get: operations["getStaffItemsById"];
        /**
         * retail `PUT /v1/items/{id}`
         * @description Mirrors retail `PUT /v1/items/{id}` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `Item` (contracts/openapi/retail.yaml).
         */
        put: operations["putStaffItemsById"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/items/{id}/atin": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * retail `PUT /v1/items/{id}/atin`
         * @description Mirrors retail `PUT /v1/items/{id}/atin` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `Item` (contracts/openapi/retail.yaml).
         */
        put: operations["putStaffItemsAtin"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/items/{id}/listing": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/items/{id}/listing`
         * @description Mirrors retail `POST /v1/items/{id}/listing` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `Item` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffItemsListing"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/suppliers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/suppliers`
         * @description Mirrors retail `GET /v1/suppliers` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffSuppliers"];
        put?: never;
        /**
         * retail `POST /v1/suppliers`
         * @description Mirrors retail `POST /v1/suppliers` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again).
         */
        post: operations["postStaffSuppliers"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/suppliers/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/suppliers/{id}`
         * @description Mirrors retail `GET /v1/suppliers/{id}` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffSuppliersById"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/suppliers/{id}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/suppliers/{id}/status`
         * @description Mirrors retail `POST /v1/suppliers/{id}/status` 1:1 with the staff member's token (module `retail` at APPROVE or above; retail checks its own permissions again). The body is retail's `Supplier` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffSuppliersStatus"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/purchase-orders`
         * @description Mirrors retail `GET /v1/purchase-orders` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffPurchaseOrders"];
        put?: never;
        /**
         * retail `POST /v1/purchase-orders`
         * @description Mirrors retail `POST /v1/purchase-orders` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `PurchaseOrder` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffPurchaseOrders"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/purchase-orders/{id}`
         * @description Mirrors retail `GET /v1/purchase-orders/{id}` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again). The body is retail's `PurchaseOrder` (contracts/openapi/retail.yaml).
         */
        get: operations["getStaffPurchaseOrdersById"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/lines": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * retail `PUT /v1/purchase-orders/{id}/lines`
         * @description Mirrors retail `PUT /v1/purchase-orders/{id}/lines` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `PurchaseOrder` (contracts/openapi/retail.yaml).
         */
        put: operations["putStaffPurchaseOrdersLines"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/submit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/purchase-orders/{id}/submit`
         * @description Mirrors retail `POST /v1/purchase-orders/{id}/submit` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `PurchaseOrder` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffPurchaseOrdersSubmit"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/purchase-orders/{id}/approve`
         * @description Mirrors retail `POST /v1/purchase-orders/{id}/approve` 1:1 with the staff member's token (module `retail` at APPROVE or above; retail checks its own permissions again). The body is retail's `PurchaseOrder` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffPurchaseOrdersApprove"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/send-back": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/purchase-orders/{id}/send-back`
         * @description Mirrors retail `POST /v1/purchase-orders/{id}/send-back` 1:1 with the staff member's token (module `retail` at APPROVE or above; retail checks its own permissions again). The body is retail's `PurchaseOrder` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffPurchaseOrdersSendBack"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/send": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/purchase-orders/{id}/send`
         * @description Mirrors retail `POST /v1/purchase-orders/{id}/send` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `PurchaseOrder` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffPurchaseOrdersSend"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/purchase-orders/{id}/confirm`
         * @description Mirrors retail `POST /v1/purchase-orders/{id}/confirm` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `PurchaseOrder` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffPurchaseOrdersConfirm"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/close": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/purchase-orders/{id}/close`
         * @description Mirrors retail `POST /v1/purchase-orders/{id}/close` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `PurchaseOrder` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffPurchaseOrdersClose"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/purchase-orders/{id}/cancel`
         * @description Mirrors retail `POST /v1/purchase-orders/{id}/cancel` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `PurchaseOrder` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffPurchaseOrdersCancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/dispatches": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/purchase-orders/{id}/dispatches`
         * @description Mirrors retail `GET /v1/purchase-orders/{id}/dispatches` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffPurchaseOrdersDispatches"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/dispatches/retry": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/purchase-orders/{id}/dispatches/retry`
         * @description Mirrors retail `POST /v1/purchase-orders/{id}/dispatches/retry` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again).
         */
        post: operations["postStaffPurchaseOrdersDispatchesRetry"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/pdf": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/purchase-orders/{id}/pdf`
         * @description Mirrors retail `GET /v1/purchase-orders/{id}/pdf` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffPurchaseOrdersPdf"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/purchase-orders/{id}/edi850": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/purchase-orders/{id}/edi850`
         * @description Mirrors retail `GET /v1/purchase-orders/{id}/edi850` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffPurchaseOrdersEdi850"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/shipments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/shipments`
         * @description Mirrors retail `GET /v1/shipments` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffShipments"];
        put?: never;
        /**
         * retail `POST /v1/shipments`
         * @description Mirrors retail `POST /v1/shipments` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again).
         */
        post: operations["postStaffShipments"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/shipments/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/shipments/{id}`
         * @description Mirrors retail `GET /v1/shipments/{id}` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffShipmentsById"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/shipments/{id}/asn": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * retail `PUT /v1/shipments/{id}/asn`
         * @description Mirrors retail `PUT /v1/shipments/{id}/asn` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again).
         */
        put: operations["putStaffShipmentsAsn"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/shipments/{id}/documents": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/shipments/{id}/documents`
         * @description Mirrors retail `POST /v1/shipments/{id}/documents` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again).
         */
        post: operations["postStaffShipmentsDocuments"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/shipments/{id}/charges": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * retail `PUT /v1/shipments/{id}/charges`
         * @description Mirrors retail `PUT /v1/shipments/{id}/charges` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again).
         */
        put: operations["putStaffShipmentsCharges"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/shipments/{id}/advance": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/shipments/{id}/advance`
         * @description Mirrors retail `POST /v1/shipments/{id}/advance` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again).
         */
        post: operations["postStaffShipmentsAdvance"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/shipments/{id}/landed-cost": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/shipments/{id}/landed-cost`
         * @description Mirrors retail `GET /v1/shipments/{id}/landed-cost` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffShipmentsLandedCost"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/receipts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/receipts`
         * @description Mirrors retail `GET /v1/receipts` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffReceipts"];
        put?: never;
        /**
         * retail `POST /v1/receipts`
         * @description Mirrors retail `POST /v1/receipts` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `GoodsReceipt` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffReceipts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/receipts/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/receipts/{id}`
         * @description Mirrors retail `GET /v1/receipts/{id}` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again). The body is retail's `GoodsReceipt` (contracts/openapi/retail.yaml).
         */
        get: operations["getStaffReceiptsById"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/receipts/{id}/lines": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * retail `PUT /v1/receipts/{id}/lines`
         * @description Mirrors retail `PUT /v1/receipts/{id}/lines` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `GoodsReceipt` (contracts/openapi/retail.yaml).
         */
        put: operations["putStaffReceiptsLines"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/receipts/{id}/charges": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * retail `PUT /v1/receipts/{id}/charges`
         * @description Mirrors retail `PUT /v1/receipts/{id}/charges` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `GoodsReceipt` (contracts/openapi/retail.yaml).
         */
        put: operations["putStaffReceiptsCharges"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/receipts/{id}/approve-over-receipt": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/receipts/{id}/approve-over-receipt`
         * @description Mirrors retail `POST /v1/receipts/{id}/approve-over-receipt` 1:1 with the staff member's token (module `retail` at APPROVE or above; retail checks its own permissions again). The body is retail's `GoodsReceipt` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffReceiptsApproveOverReceipt"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/receipts/{id}/validate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/receipts/{id}/validate`
         * @description Mirrors retail `POST /v1/receipts/{id}/validate` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        post: operations["postStaffReceiptsValidate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/receipts/{id}/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/receipts/{id}/complete`
         * @description Mirrors retail `POST /v1/receipts/{id}/complete` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `GoodsReceipt` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffReceiptsComplete"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/receipts/{id}/claims/{claimRef}/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/receipts/{id}/claims/{claimRef}/resolve`
         * @description Mirrors retail `POST /v1/receipts/{id}/claims/{claimRef}/resolve` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again). The body is retail's `GoodsReceipt` (contracts/openapi/retail.yaml).
         */
        post: operations["postStaffReceiptsClaimsResolve"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/invoices": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/invoices`
         * @description Mirrors retail `GET /v1/invoices` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffInvoices"];
        put?: never;
        /**
         * retail `POST /v1/invoices`
         * @description Mirrors retail `POST /v1/invoices` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again).
         */
        post: operations["postStaffInvoices"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/invoices/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/invoices/{id}`
         * @description Mirrors retail `GET /v1/invoices/{id}` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffInvoicesById"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/invoices/{id}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/invoices/{id}/approve`
         * @description Mirrors retail `POST /v1/invoices/{id}/approve` 1:1 with the staff member's token (module `retail` at APPROVE or above; retail checks its own permissions again).
         */
        post: operations["postStaffInvoicesApprove"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/invoices/{id}/reject": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/invoices/{id}/reject`
         * @description Mirrors retail `POST /v1/invoices/{id}/reject` 1:1 with the staff member's token (module `retail` at APPROVE or above; retail checks its own permissions again).
         */
        post: operations["postStaffInvoicesReject"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/invoices/{id}/rematch": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * retail `POST /v1/invoices/{id}/rematch`
         * @description Mirrors retail `POST /v1/invoices/{id}/rematch` 1:1 with the staff member's token (module `retail` at EDIT or above; retail checks its own permissions again).
         */
        post: operations["postStaffInvoicesRematch"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/gl/accounts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/gl/accounts`
         * @description Mirrors retail `GET /v1/gl/accounts` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffGlAccounts"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/staff/retail/gl/journal": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * retail `GET /v1/gl/journal`
         * @description Mirrors retail `GET /v1/gl/journal` 1:1 with the staff member's token (module `retail` at VIEW or above; retail checks its own permissions again).
         */
        get: operations["getStaffGlJournal"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        RunDispatchList: {
            items: components["schemas"]["RunDispatch"][];
            /** @description The caller may plan a run and move it by hand */
            canManage: boolean;
        };
        /** @description A delivery run in dispatch (S264) */
        RunDispatch: {
            runId: string;
            /** @example Bastrop */
            town: string;
            /** @example TX */
            state: string;
            /** @example America/Chicago */
            timeZone: string;
            /** Format: date */
            serviceDate: string;
            /** Format: date-time */
            windowStart: string;
            /** Format: date-time */
            windowEnd: string;
            /**
             * Format: date-time
             * @description When the driver collects from the sellers
             */
            sellerPickupAt: string | null;
            /** @enum {string} */
            dropMode: "DOORSTEP" | "PICKUP_POINT";
            pickupPoint: components["schemas"]["RunPickupPoint"] | null;
            /**
             * @description Dispatch status: PENDING = not planned (see planError)
             * @enum {string}
             */
            status: "PENDING" | "PLANNED" | "CANCELLED";
            /**
             * @description The run's lifecycle in delivery-runs; null when it could not be read
             * @enum {string|null}
             */
            runStatus: "CONFIRMED" | "COLLECTING" | "OUT_FOR_DELIVERY" | "COMPLETED" | "CANCELLED" | null;
            /** @description Why the routes could not be planned */
            planError: string | null;
            /** Format: date-time */
            plannedAt: string | null;
            progress: components["schemas"]["RunDispatchProgress"];
            /** @description The run's orders per stage from the drivers' shipment events (detail only; null when delivery-runs could not be read) */
            orders: components["schemas"]["RunOrderProgress"] | null;
            routes: components["schemas"]["RunDispatchRoute"][];
            canManage: boolean;
        };
        RunPickupPoint: {
            name: string;
            line1: string;
            city: string;
            state: string;
            postalCode: string;
            hours: string;
            notes: string;
        };
        /** @description What has happened on the run's routes (bags are one seller's package of an order) */
        RunDispatchProgress: {
            orders: number;
            bags: number;
            pickupStops: number;
            pickupsDone: number;
            bagsCollected: number;
            dropStops: number;
            dropsDone: number;
            dropsFailed: number;
            bagsDelivered: number;
            /** @description Run shipments waiting for a route */
            unrouted: number;
        };
        /** @description Orders on the run per stage (from orders-service shipment events) */
        RunOrderProgress: {
            orders: number;
            pending: number;
            collected: number;
            outForDelivery: number;
            failed: number;
            delivered: number;
            returned: number;
            /** Format: date-time */
            lastDeliveredAt: string | null;
        };
        RunDispatchRoute: {
            id: string;
            name: string;
            /** @enum {string} */
            status: "PLANNED" | "ASSIGNED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
            driverId: string | null;
            vehicleId: string | null;
            blocked: boolean;
            packages: number;
            bagsCollected: number;
            bagsDelivered: number;
            pickupStops: number;
            pickupsDone: number;
            dropStops: number;
            dropsDone: number;
            dropsFailed: number;
            /** @description In visiting order; empty in the list of runs */
            stops: components["schemas"]["RunDispatchStop"][];
        };
        RunDispatchStop: {
            id: string;
            sequence: number;
            /** @enum {string} */
            kind: "PICKUP" | "DROPOFF";
            name: string;
            /** @enum {string} */
            status: "PENDING" | "ARRIVED" | "DONE" | "FAILED" | "RETRY" | "SKIPPED" | "REASSIGNED" | "CANCELLED";
            orderNumber: string | null;
            /** @description The pickup point of a pickup-point run */
            addressLine: string | null;
            /** @description The customer's code is needed */
            handoverRequired: boolean;
            note: string | null;
        };
        RunAdvanceRequest: {
            /** @enum {string} */
            to: "COLLECTING" | "OUT_FOR_DELIVERY" | "COMPLETED";
        };
        IdentityTokenInspectionRequest: {
            /** @description The JWT to inspect */
            token: string;
        };
        IdentityTokenInspection: {
            /** @description The text is a signed JWT */
            wellFormed: boolean;
            header?: {
                [key: string]: unknown;
            };
            claims?: {
                [key: string]: unknown;
            };
            scopes?: string[];
            keyId?: string | null;
            /** Format: date-time */
            issuedAt?: string | null;
            /** Format: date-time */
            expiresAt?: string | null;
            expired?: boolean | null;
            /** @description The signature verifies against a key identity publishes */
            signatureValid: boolean;
            issuerValid: boolean;
            /** @description Well formed, signature and issuer valid, and not expired */
            valid: boolean;
            problems: string[];
        };
        IdentityDocuments: {
            discovery: {
                [key: string]: unknown;
            };
            jwks: {
                [key: string]: unknown;
            };
        };
        /** @description What the plan lets an organisation do (read from `limits`). A capability that is off is not offered to the plan's members. */
        StaffPlanEntitlements: {
            /**
             * Format: int64
             * @description The most live listings; null = unlimited
             */
            listings: number | null;
            listingsUnlimited: boolean;
            aiListingWriter: boolean;
            /**
             * @description NONE = Lola is not included
             * @enum {string}
             */
            lolaTier: "NONE" | "ESSENTIALS" | "GROWTH" | "PRO";
            bulkUpload: boolean;
            imageLibrary: boolean;
            coupons: boolean;
        };
        StaffPlanVersionPrice: {
            /** @enum {string} */
            cadence: "MONTHLY" | "QUARTERLY" | "ANNUAL";
            /** Format: int64 */
            amountCents: number;
        };
        StaffPlanVersion: {
            version: number;
            /** @enum {string} */
            status: "DRAFT" | "PUBLISHED" | "SUPERSEDED";
            trialDays: number;
            note?: string | null;
            createdBy?: string | null;
            /** Format: date-time */
            createdAt?: string | null;
            /** Format: date-time */
            publishedAt?: string | null;
            /** Format: int64 */
            subscribers: number;
            prices: components["schemas"]["StaffPlanVersionPrice"][];
        };
        /** @description A plan AfroTransact owns; prices are those of the current version (0 before the first publish). */
        StaffPlan: {
            code: string;
            /** @enum {string} */
            kind: "SELLER" | "PROVIDER";
            name: string;
            tagline?: string | null;
            features: string[];
            /** @description The plan's settings. `listings` (a number or `unlimited`), `bulkUpload`, `imageLibrary`, `coupons` and `aiListingWriter` (`true`/`false`) and `lolaTier` (`NONE`, `ESSENTIALS`, `GROWTH`, `PRO`) are the capabilities the platform enforces; any other key is shown as plan information. The plan's `features` list is derived from them (only what the plan allows is listed), whatever is sent. Charge terms are the keys `chargeFreeMonths` (months free first), `chargeThen` (`CHARGE`, `CHARGE_WHEN`: start charging once the condition is met, `WAIVE_WHILE`: free while it holds), `chargeMatch` (`ALL`/`ANY`), `chargeClauses` (`METRIC:amount,…` with METRIC one of GROSS_SALES (cents), COMPLETED_ORDERS, COMPLETED_BOOKINGS, ACTIVE_LISTINGS) and `chargeWindowMonths` (sales and orders counted over the last N months). */
            limits?: {
                [key: string]: string;
            };
            entitlements?: components["schemas"]["StaffPlanEntitlements"];
            /** @description What the plan charges, in words (free months and conditions), from its charge* limits */
            chargeTerms?: string;
            /** @enum {string} */
            visibility: "PUBLIC" | "HIDDEN" | "INVITE_ONLY";
            /** @enum {string} */
            status: "DRAFT" | "ACTIVE" | "RETIRED";
            sortOrder?: number;
            currentVersion: number;
            /** Format: int64 */
            monthlyCents: number;
            /** Format: int64 */
            quarterlyCents: number;
            /** Format: int64 */
            annualCents: number;
            trialDays: number;
            /**
             * Format: int64
             * @description Send back as expectedVersion to edit the metadata
             */
            rowVersion: number;
            /** Format: int64 */
            subscribers: number;
            invites: string[];
            versions: components["schemas"]["StaffPlanVersion"][];
        };
        /** @description A version's price per cadence in cents and its default trial. */
        StaffPlanPrices: {
            /** Format: int64 */
            monthlyCents?: number;
            /** Format: int64 */
            quarterlyCents?: number;
            /** Format: int64 */
            annualCents?: number;
            trialDays?: number;
            note?: string | null;
        };
        StaffCreatePlanRequest: {
            code: string;
            /** @enum {string} */
            kind: "SELLER" | "PROVIDER";
            name: string;
            tagline?: string | null;
            features?: string[];
            limits?: {
                [key: string]: string;
            };
            /** @enum {string} */
            visibility?: "PUBLIC" | "HIDDEN" | "INVITE_ONLY";
            sortOrder?: number;
            prices?: components["schemas"]["StaffPlanPrices"];
        };
        StaffUpdatePlanRequest: {
            name: string;
            tagline?: string | null;
            features?: string[];
            limits?: {
                [key: string]: string;
            };
            /** @enum {string} */
            visibility?: "PUBLIC" | "HIDDEN" | "INVITE_ONLY";
            sortOrder?: number;
            /** Format: int64 */
            expectedVersion: number;
        };
        StaffPlanInvitesRequest: {
            orgIds: string[];
        };
        StaffPlanMigrationRow: {
            orgId: string;
            orgName?: string | null;
            fromVersion: number;
            /** @enum {string} */
            cadence: "MONTHLY" | "QUARTERLY" | "ANNUAL";
            /** Format: int64 */
            fromCents: number;
            /** Format: int64 */
            toCents: number;
            status: string;
            /**
             * Format: date-time
             * @description Their next renewal, when the new price first applies
             */
            effectiveAt?: string | null;
        };
        StaffPlanMigrationPreview: {
            planCode: string;
            toVersion: number;
            rows: components["schemas"]["StaffPlanMigrationRow"][];
        };
        StaffPlanMigrationRequest: {
            /** @description Empty or missing = every subscriber in the preview */
            orgIds?: string[];
        };
        StaffPlanMigrationResult: {
            planCode: string;
            toVersion: number;
            migrated: string[];
            failed: {
                orgId: string;
                error?: string | null;
            }[];
        };
        StaffTrialCampaign: {
            /** Format: uuid */
            id: string;
            name: string;
            promoCode?: string | null;
            trialDays: number;
            /** Format: date-time */
            signupFrom?: string | null;
            /** Format: date-time */
            signupTo?: string | null;
            /** @enum {string|null} */
            kind?: "SELLER" | "PROVIDER" | null;
            eligiblePlans: string[];
            maxRedemptions?: number | null;
            redemptions: number;
            /** @enum {string} */
            status: "ACTIVE" | "PAUSED" | "ENDED";
            createdBy?: string | null;
            /** Format: date-time */
            createdAt?: string | null;
            updatedBy?: string | null;
            /** Format: date-time */
            updatedAt?: string | null;
            /** Format: int64 */
            version: number;
        };
        /** @description A promo code, a sign-up window, or both. */
        StaffTrialCampaignRequest: {
            name: string;
            promoCode?: string | null;
            trialDays: number;
            /** Format: date-time */
            signupFrom?: string | null;
            /** Format: date-time */
            signupTo?: string | null;
            /** @enum {string|null} */
            kind?: "SELLER" | "PROVIDER" | null;
            eligiblePlans?: string[];
            maxRedemptions?: number | null;
            /** @enum {string|null} */
            status?: "ACTIVE" | "PAUSED" | "ENDED" | null;
            /** Format: int64 */
            expectedVersion?: number | null;
        };
        StaffBillingStatusRequest: {
            status: string;
        };
        /** @description Reach (AT_LEAST) or stay under (LESS_THAN, S299) the threshold of a metric and get the reward, once per seller. A LESS_THAN rule, or an AT_LEAST rule with checkAtWindowEnd, is decided once, checkLeadHours before its window (the first withinDaysOfApproval days from windowAnchor) or the trial ends. */
        StaffRewardRule: {
            /** Format: uuid */
            id: string;
            name: string;
            description?: string | null;
            /** @enum {string} */
            status: "DRAFT" | "ACTIVE" | "PAUSED" | "ENDED";
            /** @enum {string|null} */
            kind?: "SELLER" | "PROVIDER" | null;
            /** @enum {string} */
            metric: "GROSS_SALES" | "ACTIVE_LISTINGS" | "COMPLETED_ORDERS" | "COMPLETED_BOOKINGS" | "DAYS_SINCE_APPROVAL";
            /**
             * Format: int64
             * @description Cents for GROSS_SALES, a count otherwise
             */
            threshold: number;
            windowDays?: number | null;
            /** @description The first N days from windowAnchor */
            withinDaysOfApproval?: number | null;
            /** @enum {string} */
            comparison: "AT_LEAST" | "LESS_THAN";
            /** @enum {string} */
            windowAnchor: "APPROVAL" | "SUBSCRIPTION_START";
            /** @description Decided once when the window closes (always for LESS_THAN) */
            checkAtWindowEnd: boolean;
            /** @description Hours before the window (or the trial) ends that the check runs */
            checkLeadHours: number;
            /** @enum {string} */
            rewardType: "FREE_DAYS" | "CREDIT" | "PERCENT_OFF";
            freeDays?: number | null;
            /** Format: int64 */
            creditCents?: number | null;
            percentOff?: number | null;
            durationInvoices?: number | null;
            eligiblePlans: string[];
            /** Format: date-time */
            startsAt?: string | null;
            /** Format: date-time */
            endsAt?: string | null;
            maxGrants?: number | null;
            grantsCount: number;
            /** @example 1 month free */
            rewardLabel: string;
            /**
             * @example $1,000 in sales within 60 days of approval
             * @example less than $1,000 in sales in the first 30 days of the subscription
             */
            conditionLabel: string;
            createdBy?: string | null;
            /** Format: date-time */
            createdAt?: string | null;
            updatedBy?: string | null;
            /** Format: date-time */
            updatedAt?: string | null;
            /** Format: int64 */
            version: number;
        };
        StaffRewardRuleRequest: {
            name: string;
            description?: string | null;
            /** @enum {string|null} */
            kind?: "SELLER" | "PROVIDER" | null;
            /** @enum {string} */
            metric: "GROSS_SALES" | "ACTIVE_LISTINGS" | "COMPLETED_ORDERS" | "COMPLETED_BOOKINGS" | "DAYS_SINCE_APPROVAL";
            /** Format: int64 */
            threshold: number;
            windowDays?: number | null;
            /** @description The first N days from windowAnchor (required for a rule checked at window end) */
            withinDaysOfApproval?: number | null;
            /**
             * @description Default AT_LEAST
             * @enum {string|null}
             */
            comparison?: "AT_LEAST" | "LESS_THAN" | null;
            /**
             * @description Default APPROVAL
             * @enum {string|null}
             */
            windowAnchor?: "APPROVAL" | "SUBSCRIPTION_START" | null;
            /** @description AT_LEAST only: decide once when the window closes */
            checkAtWindowEnd?: boolean | null;
            /** @description Default 24 */
            checkLeadHours?: number | null;
            /** @enum {string} */
            rewardType: "FREE_DAYS" | "CREDIT" | "PERCENT_OFF";
            freeDays?: number | null;
            /** Format: int64 */
            creditCents?: number | null;
            percentOff?: number | null;
            durationInvoices?: number | null;
            eligiblePlans?: string[];
            /** Format: date-time */
            startsAt?: string | null;
            /** Format: date-time */
            endsAt?: string | null;
            maxGrants?: number | null;
            /** @enum {string|null} */
            status?: "DRAFT" | "ACTIVE" | "PAUSED" | "ENDED" | null;
            /** Format: int64 */
            expectedVersion?: number | null;
            /** @description Preview only - marks who already got this rule */
            ruleId?: string | null;
        };
        StaffRewardPreviewRow: {
            orgId: string;
            orgName?: string | null;
            planCode?: string | null;
            /** Format: int64 */
            value: number;
            /** Format: int64 */
            threshold: number;
            percent: number;
            qualifies: boolean;
            alreadyGranted: boolean;
            reason?: string | null;
            /**
             * @description PENDING - in the window of a rule checked at window end (decided when the window closes)
             * @enum {string}
             */
            status: "GRANTED" | "QUALIFIES" | "PENDING" | "CHECKED" | "NOT_YET" | "INELIGIBLE";
            /**
             * Format: date-time
             * @description Rules checked at window end - when this organisation is checked
             */
            checkAt?: string | null;
        };
        StaffRewardPreview: {
            evaluated: number;
            qualifying: number;
            /** @description Rules checked at window end - organisations still in their window */
            pending: number;
            rows: components["schemas"]["StaffRewardPreviewRow"][];
        };
        /** @description A reward or staff adjustment and what it changed. */
        StaffBillingGrant: {
            /** Format: uuid */
            id: string;
            orgId: string;
            /** @enum {string} */
            source: "RULE" | "MANUAL";
            ruleId?: string | null;
            /** @enum {string} */
            type: "FREE_DAYS" | "FREE_MONTHS" | "CREDIT" | "PERCENT_OFF" | "WAIVE_INVOICE" | "TRIAL_END" | "CHARGE_GATE";
            /** Format: int64 */
            creditCents?: number | null;
            freeDays?: number | null;
            freeMonths?: number | null;
            percentOff?: number | null;
            durationInvoices?: number | null;
            /** @description Coupons: invoices still to discount */
            remainingInvoices?: number | null;
            /** @enum {string|null} */
            gateMode?: "START_AFTER" | "WAIVE_WHILE" | "WAIVE_UNTIL" | null;
            invoiceId?: string | null;
            /** @enum {string} */
            status: "PENDING" | "APPLIED" | "FAILED";
            reasonCode?: string | null;
            note?: string | null;
            summary: string;
            actorId: string;
            metric?: string | null;
            /** Format: int64 */
            metricValue?: number | null;
            reference?: string | null;
            /** Format: date-time */
            previousTrialEnd?: string | null;
            /** Format: date-time */
            newTrialEnd?: string | null;
            attempts: number;
            lastError?: string | null;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            appliedAt?: string | null;
        };
        StaffBillingGrantPage: {
            items: components["schemas"]["StaffBillingGrant"][];
            page: number;
            size: number;
            /** Format: int64 */
            total: number;
        };
        StaffBillingReasonCode: {
            code: string;
            label: string;
        };
        StaffBillingOrgSearch: {
            items: {
                orgId: string;
                name: string;
                kind: string;
                status: string;
            }[];
        };
        /** @description seller's Billing (cents). */
        StaffBillingSubscription: {
            orgId: string;
            planCode: string;
            planName: string;
            /** @enum {string} */
            cadence: "MONTHLY" | "QUARTERLY" | "ANNUAL";
            status: string;
            /** Format: date-time */
            trialEndsAt?: string | null;
            /** Format: date-time */
            currentPeriodEnd?: string | null;
            /** Format: date-time */
            nextChargeAt?: string | null;
            /** Format: int64 */
            nextChargeCents?: number;
            nextChargeLabel: string;
            cancelAtPeriodEnd: boolean;
            dunning?: string | null;
            failedPaymentAttempts?: number;
            planVersion: number;
            /**
             * Format: int64
             * @description Platform credit the next invoices use first
             */
            creditBalanceCents: number;
            /** @enum {string|null} */
            trialSource?: "PLAN" | "CAMPAIGN" | "OVERRIDE" | "NONE" | null;
            trialDaysOverride?: number | null;
            planFeeCollection?: components["schemas"]["StaffPlanFeeCollectionMode"];
            /** @description The condition on the plan charges, with the seller's progress; null when charges apply as normal */
            chargeGate?: components["schemas"]["StaffChargeGate"] | null;
            entitlements?: components["schemas"]["StaffPlanEntitlements"] | null;
        };
        StaffChargeGate: {
            /** @enum {string} */
            mode: "START_AFTER" | "WAIVE_WHILE" | "WAIVE_UNTIL";
            /** @enum {string|null} */
            metric?: "GROSS_SALES" | "COMPLETED_ORDERS" | "COMPLETED_BOOKINGS" | "ACTIVE_LISTINGS" | null;
            /** Format: int64 */
            threshold?: number | null;
            /** Format: int64 */
            progress: number;
            /** Format: date-time */
            since?: string | null;
            /** Format: date-time */
            until?: string | null;
            reasonCode?: string | null;
            note?: string | null;
            /** Format: date-time */
            setAt?: string | null;
            /** Format: date-time */
            metAt?: string | null;
            label: string;
        };
        StaffBillingInvoiceLine: {
            /** @enum {string} */
            kind: "PLAN" | "USAGE" | "COUPON" | "PRORATION" | "WAIVER";
            description: string;
            /** Format: int64 */
            amountCents: number;
            /** Format: int64 */
            quantity?: number | null;
            meter?: string | null;
        };
        StaffBillingInvoicePayment: {
            /** @enum {string} */
            source: "CREDIT" | "EARNINGS" | "CARD" | "WAIVER";
            /** Format: int64 */
            amountCents: number;
            /** @enum {string} */
            status: "APPLIED" | "PENDING" | "FAILED" | "REVERSED";
            note?: string | null;
            /** Format: date-time */
            createdAt?: string | null;
        };
        /** @description A plan invoice AfroTransact issued. Settled in this order: platform credit (coupons are already off the invoice), the seller's sales if they chose that, then the saved card or bank account. */
        StaffBillingInvoice: {
            id: string;
            number?: string | null;
            /** Format: date-time */
            periodStart?: string | null;
            /** Format: date-time */
            periodEnd?: string | null;
            /** Format: int64 */
            subtotalCents: number;
            /** Format: int64 */
            discountCents: number;
            /** Format: int64 */
            amountDueCents: number;
            /** Format: int64 */
            amountPaidCents: number;
            currency: string;
            /** @enum {string|null} */
            status?: "open" | "paid" | "void" | "uncollectible" | null;
            billingReason?: string | null;
            planCode?: string | null;
            attempts?: number;
            /** Format: date-time */
            nextAttemptAt?: string | null;
            lastError?: string | null;
            /** Format: date-time */
            paidAt?: string | null;
            /** Format: date-time */
            createdAt: string;
            lines?: components["schemas"]["StaffBillingInvoiceLine"][];
            payments?: components["schemas"]["StaffBillingInvoicePayment"][];
            /** Format: int64 */
            paidFromCreditCents: number;
            /**
             * Format: int64
             * @description Withheld from the organisation's sales (S298), only if it chose that
             */
            paidFromSalesCents: number;
            /** Format: int64 */
            paidByCardCents: number;
            /** Format: int64 */
            waivedCents: number;
        };
        /** @description One plan invoice paid (or asked to be paid) from earnings (S298). */
        StaffEarningsCollection: {
            invoiceId: string;
            invoiceNumber?: string | null;
            /**
             * Format: int64
             * @description The invoice amount when it was issued
             */
            invoiceAmountCents?: number | null;
            /**
             * Format: int64
             * @description Asked from earnings (the invoice less the credit balance)
             */
            amountCents: number;
            /** Format: int64 */
            withheldCents: number;
            /** Format: int64 */
            creditedCents: number;
            /** Format: int64 */
            releasedCents: number;
            /** Format: int64 */
            paidFromSalesCents: number;
            /** @enum {string} */
            status: "REQUESTED" | "WITHHELD" | "CREDITED" | "DECLINED" | "MISSED" | "FAILED" | "RELEASED";
            paymentsReference?: string | null;
            reason?: string | null;
            attempts: number;
            lastError?: string | null;
            /** Format: date-time */
            createdAt?: string | null;
            /** Format: date-time */
            updatedAt?: string | null;
        };
        /**
         * @description EARNINGS_FIRST: each plan invoice is taken from pending earnings and the card pays only the rest. CARD: always charged to the card.
         * @enum {string}
         */
        StaffPlanFeeCollectionMode: "EARNINGS_FIRST" | "CARD";
        StaffPlanFeeCollectionChange: {
            id: string;
            previous?: components["schemas"]["StaffPlanFeeCollectionMode"] | null;
            mode: components["schemas"]["StaffPlanFeeCollectionMode"];
            /** @enum {string} */
            source: "ONBOARDING" | "SELLER" | "STAFF";
            actorId: string;
            reasonCode?: string | null;
            note?: string | null;
            /** Format: date-time */
            appliesFrom?: string | null;
            /** Format: date-time */
            createdAt: string;
        };
        /** @description seller's PlanFeeCollection (S298) */
        StaffPlanFeeCollection: {
            orgId: string;
            /** @description The kill switch is on; the choice is offered and honoured */
            available: boolean;
            mode: components["schemas"]["StaffPlanFeeCollectionMode"];
            effective: components["schemas"]["StaffPlanFeeCollectionMode"];
            /** @enum {string} */
            source: "DEFAULT" | "ONBOARDING" | "SELLER" | "STAFF";
            /** Format: date-time */
            changedAt?: string | null;
            changedBy?: string | null;
            platformDefault: components["schemas"]["StaffPlanFeeCollectionMode"];
            /**
             * Format: date-time
             * @description A change applies from the invoice of this date (the next renewal)
             */
            appliesFromInvoiceAt?: string | null;
            appliesFromLabel: string;
            /** @description An invoice being collected now keeps the choice it was created with */
            inProgressInvoiceId?: string | null;
            history: components["schemas"]["StaffPlanFeeCollectionChange"][];
        };
        StaffPlanFeeCollectionRequest: {
            mode: components["schemas"]["StaffPlanFeeCollectionMode"];
            reasonCode: string;
            note?: string | null;
        };
        StaffBillingSettings: {
            /** @description The kill switch COLLECT_PLAN_FEES_FROM_EARNINGS (deployment configuration) */
            earningsCollectionEnabled: boolean;
            planFeeCollectionDefault: components["schemas"]["StaffPlanFeeCollectionMode"];
            updatedBy?: string | null;
            /** Format: date-time */
            updatedAt?: string | null;
        };
        StaffBillingSettingsRequest: {
            planFeeCollectionDefault: components["schemas"]["StaffPlanFeeCollectionMode"];
        };
        StaffBillingPanel: {
            orgId: string;
            orgName: string | null;
            /** @enum {string} */
            kind: "SELLER" | "PROVIDER";
            orgStatus: string;
            /** @description null before the organisation has a subscription */
            billing?: components["schemas"]["StaffBillingSubscription"] | null;
            invoices: components["schemas"]["StaffBillingInvoice"][];
            grants: components["schemas"]["StaffBillingGrant"][];
            collections: components["schemas"]["StaffEarningsCollection"][];
            earningsCollectionEnabled: boolean;
            /** @description How the plan is paid (S298); null before the organisation has a subscription */
            planFeeCollection?: components["schemas"]["StaffPlanFeeCollection"] | null;
            /** @description Plans staff assigned to the organisation, newest first (S306) */
            planAssignments?: components["schemas"]["StaffPlanAssignment"][];
        };
        StaffPlanAssignment: {
            /** Format: uuid */
            id: string;
            fromPlan?: string | null;
            toPlan: string;
            /** @enum {string} */
            cadence: "MONTHLY" | "QUARTERLY" | "ANNUAL";
            /** @enum {string} */
            applied: "IMMEDIATE_UPGRADE" | "AT_RENEWAL" | "NO_CHANGE";
            reason: string;
            assignedBy: string;
            /** Format: date-time */
            assignedAt: string;
        };
        StaffAssignPlanRequest: {
            planCode: string;
            /** @enum {string} */
            cadence?: "MONTHLY" | "QUARTERLY" | "ANNUAL";
            reason: string;
        };
        StaffPlanChange: {
            /** @enum {string} */
            applied: "IMMEDIATE_UPGRADE" | "AT_RENEWAL" | "NO_CHANGE";
            /** @description The organisation billing overview after the change */
            billing?: {
                [key: string]: unknown;
            };
        };
        StaffCreditRequest: {
            /** Format: int64 */
            amountCents: number;
            reasonCode: string;
            note?: string | null;
        };
        StaffDaysRequest: {
            days: number;
            reasonCode: string;
            note?: string | null;
        };
        StaffMonthsRequest: {
            months: number;
            reasonCode: string;
            note?: string | null;
        };
        /** @description A condition on the plan charges. START_AFTER: no charge until the seller reaches `threshold` of `metric`; WAIVE_WHILE: free while they keep it; WAIVE_UNTIL: free until `until`. `threshold` is in cents for GROSS_SALES, else a count. Replaces any earlier condition. */
        StaffChargeGateRequest: {
            /** @enum {string} */
            mode: "START_AFTER" | "WAIVE_WHILE" | "WAIVE_UNTIL";
            /** @enum {string|null} */
            metric?: "GROSS_SALES" | "COMPLETED_ORDERS" | "COMPLETED_BOOKINGS" | "ACTIVE_LISTINGS" | null;
            /** Format: int64 */
            threshold?: number | null;
            /** Format: date-time */
            until?: string | null;
            reasonCode: string;
            note?: string | null;
        };
        StaffWaiveRequest: {
            reasonCode: string;
            note?: string | null;
        };
        /**
         * @description LIVE serves orders; PLANNED is on the roadmap (shown, not serving); OFF is switched off (hidden by default).
         * @enum {string}
         */
        StaffPlaceStatus: "LIVE" | "PLANNED" | "OFF";
        StaffMarketCity: {
            name: string;
            status: components["schemas"]["StaffPlaceStatus"];
            /** @description Open pre-orders selling here (removal is refused while any do) */
            preorders?: string[];
        };
        StaffMarketRegion: {
            code: string;
            name: string;
            kind?: string;
            status: components["schemas"]["StaffPlaceStatus"];
            /** @description Every city is covered (listed cities are overrides); false = only cities listed LIVE */
            everyCity: boolean;
            /** @description Send back as If-Match */
            version: number;
            cityCount?: number;
            liveCities?: number;
            /** @description Open pre-orders targeting this state or its cities */
            preorders?: number;
            capabilities?: {
                marketplace?: boolean;
                services?: boolean;
                food?: boolean;
                pickup?: boolean;
                taxRegistration?: boolean;
            };
            cities: components["schemas"]["StaffMarketCity"][];
        };
        StaffMarketCountry: {
            code: string;
            name: string;
            /** @enum {string} */
            status: "LIVE" | "PAUSED" | "COMING_SOON" | "PLANNED";
            launchNote?: string | null;
            currency?: string | null;
            liveRegions?: number;
            plannedRegions?: number;
            /** @description Send back as If-Match when changing the country's status */
            version: number;
            canEdit?: boolean;
            regions: components["schemas"]["StaffMarketRegion"][];
        } & {
            [key: string]: unknown;
        };
        StaffMarketCountrySummary: {
            code: string;
            name: string;
            /** @enum {string} */
            status: "LIVE" | "PAUSED" | "COMING_SOON" | "PLANNED";
            regionCount?: number;
            liveRegions?: number;
            plannedRegions?: number;
            cityCount?: number;
            liveCities?: number;
            version?: number;
        } & {
            [key: string]: unknown;
        };
        StaffMarketChanges: {
            pending?: boolean;
            changedCountries?: string[];
            newlyLive?: string[];
            disabledRegions?: string[];
            enabledRegions?: string[];
        };
        StaffMarketCountries: {
            items: components["schemas"]["StaffMarketCountrySummary"][];
            changes: components["schemas"]["StaffMarketChanges"];
            canEdit: boolean;
        };
        StaffMarketPublication: {
            version: number;
            changes?: components["schemas"]["StaffMarketChanges"];
        };
        StaffPlaceList: {
            /** @description Country, what the country calls its regions (State, Province…), or City */
            label: string;
            total: number;
            items: {
                /** @description ISO code, subdivision code, or the city name */
                code: string;
                name: string;
                label?: string;
                cityCount?: number;
            }[];
        };
        Problem: {
            /** Format: uri */
            type: string;
            title: string;
            status: number;
            detail?: string;
            instance?: string;
            /**
             * @description Stable machine code: VALIDATION_FAILED, UNAUTHENTICATED, NOT_STAFF, FORBIDDEN, NOT_FOUND, CONFLICT, ALREADY_DONE,
             *     REFUND_LIMIT, EXPORT_NOT_READY, EXPORT_EXPIRED, RATE_LIMITED, UPSTREAM_UNAVAILABLE, UPSTREAM_ERROR.
             */
            code?: string;
            errors?: {
                [key: string]: string;
            };
        } & {
            [key: string]: unknown;
        };
        /** @enum {string} */
        AccessLevel: "VIEW" | "EDIT" | "APPROVE" | "FULL";
        /**
         * @description Sidebar modules; keys follow identity's platform module matrix (support, exports, notifications and identity are derived; identity needs the identity:admin permission; preorders follows the Pre-orders matrix column).
         * @enum {string}
         */
        StaffModuleKey: "dash" | "exports" | "orders" | "catalog" | "inventory" | "retail" | "dispatch" | "orgs" | "apps" | "support" | "rbac" | "payouts" | "fees" | "marketing" | "disputes" | "audit" | "markets" | "siteCfg" | "dev" | "identity" | "notifications" | "preorders" | "plans";
        StaffUser: {
            id: string;
            firstName: string;
            lastName: string;
            email: string;
            initials: string;
        };
        StaffRole: {
            /** @description Platform role, e.g. platform.customer_support */
            key: string;
            label: string;
        };
        StaffNavModule: {
            key: components["schemas"]["StaffModuleKey"];
            label: string;
            description: string;
            level: components["schemas"]["AccessLevel"];
            badge: number | null;
        };
        StaffNavGroup: {
            /** @enum {string} */
            key: "overview" | "commerce" | "partners" | "finance" | "growth" | "trust" | "settings";
            label: string;
            modules: components["schemas"]["StaffNavModule"][];
        };
        StaffSession: {
            user: components["schemas"]["StaffUser"];
            roles: components["schemas"]["StaffRole"][];
            roleLabel: string;
            permissions: string[];
            navGroups: components["schemas"]["StaffNavGroup"][];
            landing: components["schemas"]["StaffModuleKey"];
            accessNote: string;
            /**
             * Format: int64
             * @description Largest refund the roles allow (null = no limit, 0 = may not refund)
             */
            refundLimitMinor: number | null;
        };
        /** @enum {string} */
        ChartTone: "ink" | "gold" | "green" | "blue" | "red" | "grey";
        /** @enum {string} */
        ValueFormat: "money" | "count";
        ChartSeries: {
            key: string;
            label: string;
            tone: components["schemas"]["ChartTone"];
            format?: components["schemas"]["ValueFormat"];
        };
        ChartRow: {
            category: string;
            values: {
                [key: string]: number;
            };
        };
        ChartSpec: {
            id: string;
            title: string;
            /** @enum {string} */
            kind: "trend" | "hbar" | "donut" | "stack";
            categoryLabel: string;
            valueFormat: components["schemas"]["ValueFormat"];
            series: components["schemas"]["ChartSeries"][];
            rows: components["schemas"]["ChartRow"][];
            available: boolean;
        };
        Kpi: {
            label: string;
            value: string;
            detail: string;
            /** @enum {string} */
            tone: "positive" | "attention" | "neutral";
        };
        StaffAssistantStatus: {
            dailyMessages: number;
            used: number;
            remaining: number;
            tools: string[];
        };
        StaffAssistantReply: {
            answer: string;
            /** @description What she looked up ("Checking the dashboard") */
            steps: {
                tool: string;
                label: string;
            }[];
            /** @description Records she points at; the console opens one when the member taps its button */
            links: {
                label: string;
                resource: string;
                id: string;
            }[];
            status: components["schemas"]["StaffAssistantStatus"];
        };
        StaffDashboard: {
            rangeDays: number;
            /** Format: date-time */
            generatedAt: string;
            kpis: components["schemas"]["Kpi"][];
            charts: components["schemas"]["ChartSpec"][];
        };
        /** @enum {string} */
        ResourceKey: "users" | "roles" | "applications" | "organisations" | "connect-accounts" | "orders" | "products" | "moderation" | "claims" | "returns" | "payouts" | "inventory" | "purchase-orders" | "routes" | "support-cases" | "audit" | "markets" | "site-settings" | "legal-documents" | "help-articles" | "privacy-requests" | "announcements" | "placements" | "identity-providers" | "fee-schedules" | "tax-rates" | "tax-codes" | "tax-rules" | "tax-review" | "dispatch-shipments" | "sos-alerts" | "vehicle-defects" | "driver-events" | "pickup-locations" | "coupons" | "reconciliation" | "reconciliation-runs" | "ledger-adjustments" | "stripe-lookup" | "payment-settings" | "tax-settings" | "maintenance" | "tax-filings" | "tax-nexus" | "oauth-clients" | "identity-sessions" | "signing-keys" | "auth-settings" | "exchange-rates";
        /** @enum {string} */
        Tone: "good" | "warn" | "bad" | "info" | "brand";
        CellValue: string | number | null;
        /** @enum {string} */
        ColumnKind: "text" | "strong" | "mono" | "money" | "number" | "date" | "datetime" | "status" | "percent";
        Column: {
            key: string;
            label: string;
            kind: components["schemas"]["ColumnKind"];
            /** @description Share of the table width in percent (the table sizes columns from it; people may resize them) */
            width: number;
            /** @description The column accepts `sort=<key>,asc|desc` (every column of a server-driven table) */
            sortable?: boolean;
        };
        Facet: {
            value: string;
            label: string;
        };
        ResourceRow: {
            id: string;
            cells: {
                [key: string]: components["schemas"]["CellValue"];
            };
            tone: components["schemas"]["Tone"] | null;
        };
        FieldOption: {
            value: string;
            label: string;
        };
        FieldValue: string | number | boolean | string[] | null;
        FormField: {
            key: string;
            label: string;
            /** @enum {string} */
            type: "text" | "textarea" | "number" | "money" | "select" | "multiselect" | "toggle" | "date" | "datetime" | "email" | "password";
            required: boolean;
            options?: components["schemas"]["FieldOption"][];
            min?: number;
            max?: number;
            maxLength?: number;
            /** @description ECMAScript regular expression the value must match */
            pattern?: string;
            help?: string;
            placeholder?: string;
            value?: components["schemas"]["FieldValue"];
        };
        Form: {
            title: string;
            submitLabel: string;
            /** @description What submitting does, in a sentence or two (shown above the fields) */
            intro?: string;
            fields: components["schemas"]["FormField"][];
            /** @description Fields whose change makes the form worth saving: Save stays disabled until one differs from its initial value, and the server refuses a save in which none does */
            changeKeys?: string[];
        };
        ResourcePage: {
            resource: components["schemas"]["ResourceKey"];
            title: string;
            columns: components["schemas"]["Column"][];
            rows: components["schemas"]["ResourceRow"][];
            page: number;
            size: number;
            /** @description Rows matching the filters (null when the upstream pages with cursors) */
            total: number | null;
            nextCursor: string | null;
            hasNext: boolean;
            facets: components["schemas"]["Facet"][];
            searchable: boolean;
            searchPlaceholder?: string;
            canCreate: boolean;
            createForm: components["schemas"]["Form"] | null;
            exportable: boolean;
            /** @description The applied sort (`<column>,asc|desc`); empty for the upstream order */
            sort: string[];
            /** @description The search or sort covered the first 5,000 rows of a bigger table */
            partial: boolean;
        };
        DetailField: {
            label: string;
            value: string;
            /** @enum {string} */
            kind: "text" | "mono" | "money" | "date" | "datetime" | "status" | "link" | "image";
            href?: string;
        };
        DetailSection: {
            title: string;
            fields: components["schemas"]["DetailField"][];
        };
        DetailTable: {
            title: string;
            columns: components["schemas"]["Column"][];
            rows: components["schemas"]["ResourceRow"][];
        };
        ActivityEntry: {
            /** Format: date-time */
            at: string;
            actor: string;
            text: string;
        };
        Note: {
            id: string;
            /** Format: date-time */
            at: string;
            author: string;
            text: string;
        };
        RecordAction: {
            key: string;
            label: string;
            /** @enum {string} */
            tone: "primary" | "neutral" | "danger";
            allowed: boolean;
            /** @description Why the action is disabled: missing permission, wrong state or the step-one reviewer */
            reason: string | null;
            /** @description Permission the upstream checks */
            permission: string;
            /** @description Confirmation question for actions without a form */
            confirm: string | null;
            form: components["schemas"]["Form"] | null;
        };
        RecordLink: {
            label: string;
            /** @description Console path, for example /support?customer=… or /orders?open=AT-…, or (external) a same-origin BFF path */
            href: string;
            /** @description Open in a new tab (an uploaded document the reviewer reads) */
            external?: boolean;
        };
        RecordDetail: {
            resource: components["schemas"]["ResourceKey"];
            id: string;
            title: string;
            subtitle: string;
            status: string | null;
            tone: components["schemas"]["Tone"] | null;
            sections: components["schemas"]["DetailSection"][];
            tables: components["schemas"]["DetailTable"][];
            activity: components["schemas"]["ActivityEntry"][];
            notes: components["schemas"]["Note"][];
            notesEnabled: boolean;
            actions: components["schemas"]["RecordAction"][];
            editForm: components["schemas"]["Form"] | null;
            links: components["schemas"]["RecordLink"][];
            /** @description Optimistic-locking version to send back with edits */
            version: string | null;
            /** @description Set when an action deleted the record; the console closes the sheet */
            removed?: boolean;
            reveal?: components["schemas"]["OneTimeSecret"];
        };
        /** @description A secret shown once, right after it was created (a new OAuth client secret). It is in this answer only; the BFF keeps nothing and the upstream cannot return it again. The console shows it in a dialog and never stores it. */
        OneTimeSecret: {
            label: string;
            value: string;
            note: string;
        };
        RecordValues: {
            values?: {
                [key: string]: components["schemas"]["FieldValue"];
            };
            version?: string | null;
        };
        NoteRequest: {
            text: string;
        };
        /**
         * @description csv: the data only (CSV cannot carry charts). xlsx: a "Summary" sheet (first) with key totals and native Excel
         *     charts over small aggregate tables, plus a "Data" sheet (typed cells, frozen header, an Excel table with
         *     filters). pdf: a summary page with the same charts and totals, then the table in landscape with the header
         *     repeated on every page (at most 10,000 table rows; the summary covers every row). Charts follow the column
         *     kinds: rows by status (pie, bar past six values), the first amount (or rows) by day or month (line), the top 10
         *     rows by amount (bar), rows by a low-cardinality text column (bar).
         * @enum {string}
         */
        ExportFormat: "csv" | "xlsx" | "pdf";
        ExportColumn: {
            key: string;
            label: string;
            kind: components["schemas"]["ColumnKind"];
        };
        /** @description A client-side table (small lists the console holds in full) exported as shown, through the same pipeline. */
        ExportDataset: {
            /** @description The table's key (file name) */
            table: string;
            title?: string;
            /** @description Which rows are in the file, e.g. "Search “lagos” · 3 selected rows" */
            scope?: string;
            columns: components["schemas"]["ExportColumn"][];
            /** @description One array per row, one cell per column; money in currency units, dates as ISO strings. */
            rows: (string | number | boolean | null)[][];
        };
        /**
         * @description A server table (`resource`, or `table` + `parent`) with its search, sort and visible columns, optionally only the
         *     selected rows (`ids`); or a client-side table (`dataset`). One of `resource`, `table` and `dataset` is required.
         */
        ExportRequest: {
            resource?: components["schemas"]["ResourceKey"];
            /**
             * @description A staff table outside the resources (with `parent`)
             * @enum {string}
             */
            table?: "preorder-orders";
            /** @description The record the table belongs to (the pre-order window of `preorder-orders`) */
            parent?: string;
            format: components["schemas"]["ExportFormat"];
            /** @description The table's search (every column) */
            q?: string;
            status?: string;
            /** @description The table's sort (`<column>,asc|desc`) */
            sort?: string[];
            /** @description Only these rows (the selection); search and sort still apply */
            ids?: string[];
            /** @description The visible columns in order (default every column) */
            columns?: string[];
            dataset?: components["schemas"]["ExportDataset"];
        };
        ExportJob: {
            id: string;
            /** @description The table exported (a resource key or a client-side table's key) */
            resource: string;
            /** @description The table's name */
            title: string;
            format: components["schemas"]["ExportFormat"];
            /** @enum {string} */
            status: "QUEUED" | "RUNNING" | "READY" | "FAILED" | "EXPIRED";
            /** @description Rows written so far */
            rows: number;
            /** @description Built in the background (more than 5,000 rows) */
            async: boolean;
            fileName: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            finishedAt: string | null;
            /**
             * Format: date-time
             * @description The download link stops working at this time (24 hours after READY)
             */
            expiresAt: string | null;
            /** @description Console-relative download path (redirects to the signed URL) */
            downloadUrl: string | null;
            error: string | null;
        };
        ExportJobList: {
            items: components["schemas"]["ExportJob"][];
        };
        CustomerHit: {
            id: string;
            name: string;
            email: string;
            enabled: boolean;
            principalType: string;
        };
        CustomerSearch: {
            items: components["schemas"]["CustomerHit"][];
        };
        SupportShipment: {
            id: string;
            label: string;
            status: string;
            sellerName: string;
            carrier: string | null;
            trackingNumber: string | null;
            cancellable: boolean;
            /** Format: int64 */
            totalMinor: number;
        };
        SupportOrder: {
            id: string;
            orderNumber: string;
            status: string;
            /** Format: date-time */
            placedAt: string;
            /** Format: int64 */
            totalMinor: number;
            currency: string;
            shipments: components["schemas"]["SupportShipment"][];
        };
        SupportClaim: {
            id: string;
            reference: string;
            type: string;
            status: string;
            orderNumber: string;
            /** Format: int64 */
            amountMinor: number;
            /** Format: int64 */
            refundMinor: number | null;
            open: boolean;
        };
        SupportReturn: {
            id: string;
            number: string;
            status: string;
            orderNumber: string;
            refundState: string | null;
            /** Format: int64 */
            estimateMinor: number;
        };
        SupportNotification: {
            id: string;
            channel: string;
            templateKey: string;
            subject: string | null;
            status: string;
            address: string;
            /** Format: date-time */
            createdAt: string;
        };
        SupportCaseSummary: {
            id: string;
            reference: string;
            reason: string;
            summary: string;
            status: string;
            /** Format: date-time */
            openedAt: string;
        };
        SupportBooking: {
            id: string;
            service: string;
            status: string;
            /** Format: date-time */
            startsAt: string;
        };
        AuditLine: {
            /** Format: date-time */
            at: string;
            actor: string;
            action: string;
            summary: string;
        };
        SupportCustomer: {
            id: string;
            name: string;
            email: string;
            enabled: boolean;
            emailVerified: boolean;
            roles: string[];
        };
        SupportPermissions: {
            refund: boolean;
            resend: boolean;
            cancelShipment: boolean;
        };
        Customer360: {
            customer: components["schemas"]["SupportCustomer"];
            orders: components["schemas"]["SupportOrder"][];
            bookings: components["schemas"]["SupportBooking"][];
            claims: components["schemas"]["SupportClaim"][];
            returns: components["schemas"]["SupportReturn"][];
            notifications: components["schemas"]["SupportNotification"][];
            cases: components["schemas"]["SupportCaseSummary"][];
            audit: components["schemas"]["AuditLine"][];
            /** Format: int64 */
            refundLimitMinor: number | null;
            can: components["schemas"]["SupportPermissions"];
            /** @description Upstreams that could not answer (their sections are empty) */
            unavailable: string[];
        };
        SupportRefundRequest: {
            customerId: string;
            orderNumber: string;
            /** Format: int64 */
            amountMinor: number;
            reason: string;
            /** @enum {string} */
            via: "CLAIM" | "ORDER";
            /** @description Required with via CLAIM */
            claimId?: string;
            /** @description Support case the refund resolves (optional) */
            caseId?: string;
        };
        SupportResendRequest: {
            customerId: string;
            caseId?: string;
        };
        SupportCancelRequest: {
            customerId: string;
            orderNumber: string;
            reason: string;
            caseId?: string;
        };
        SupportActionResult: {
            ok: boolean;
            message: string;
            /** @description Refund, notification or shipment reference */
            reference: string | null;
            /** @description The upstream records the action in the trust-ops audit log */
            audited: boolean;
        };
        /** @description Bounds the editor enforces (rates in basis points, amounts in minor units) */
        FeeLimits: {
            /** @example 5000 */
            commissionMaxBps: number;
            /** @example 1000 */
            cardPctMaxBps: number;
            /**
             * Format: int64
             * @example 1000
             */
            cardFixedMaxMinor: number;
            /**
             * Format: int64
             * @example 10000
             */
            fbaPerUnitMaxMinor: number;
            noteMinLength: number;
            noteMaxLength: number;
            maxCategoryRates: number;
            maxOverrides: number;
            /**
             * @description Largest AfroTransact Delivery service radius payments accepts
             * @example 200
             */
            deliveryMaxRadiusMiles: number;
            /**
             * Format: int64
             * @description Largest delivery amount: fee, base, per mile, band, min or max
             * @example 10000
             */
            deliveryAmountMaxMinor: number;
            /** @example 20 */
            deliveryMaxBands: number;
            /** @example 500 */
            maxDeliveryOverrides: number;
        };
        FeeCategory: {
            /** @description payments category key (lower-case), e.g. groceries */
            key: string;
            label: string;
            /**
             * @description TAXONOMY: a catalog department; SCHEDULE: only known from a fee schedule; PLATFORM: services
             * @enum {string}
             */
            source: "TAXONOMY" | "SCHEDULE" | "PLATFORM";
        };
        FeeCategoryRate: {
            category: string;
            label: string;
            commissionBps: number;
        };
        FeeOverride: {
            orgId: string;
            orgName: string | null;
            /** @description null = every category */
            category: string | null;
            categoryLabel: string;
            commissionBps: number;
        };
        FeeScheduleView: {
            id: string;
            version: number;
            /** @enum {string} */
            status: "ACTIVE" | "SCHEDULED" | "SUPERSEDED" | "CANCELLED";
            /** Format: date-time */
            effectiveFrom: string;
            currency: string;
            /** @description Commission of categories without their own rate (basis points) */
            defaultCommissionBps: number;
            categoryRates: components["schemas"]["FeeCategoryRate"][];
            overrides: components["schemas"]["FeeOverride"][];
            /** @description Card fee estimate per charge, percentage part */
            cardPctBps: number;
            /**
             * Format: int64
             * @description Card fee estimate per charge, fixed part
             */
            cardFixedMinor: number;
            /** Format: int64 */
            fbaPerUnitMinor: number;
            reverseCommissionOnRefund: boolean;
            /** @description AfroTransact Delivery from seller stores (S255); null when the version has no delivery pricing (quotes NOT_CONFIGURED) */
            deliveryPricing: components["schemas"]["DeliveryPricing"] | null;
            /** @description Product commission per plan, after negotiated rates and before category rates */
            planRates: components["schemas"]["FeePlanRate"][];
            /** @description FBA fee per unit of the plans that have their own; every other plan pays fbaPerUnitMinor */
            planFba: components["schemas"]["FeePlanFba"][];
            /** @description Connection fees on service bookings; null for versions created before they existed (services paid the 'services' commission) */
            connectionFee: components["schemas"]["FeeConnectionFee"] | null;
            note: string | null;
            createdBy: string;
            /** Format: date-time */
            createdAt: string | null;
            /** Format: date-time */
            cancelledAt: string | null;
            cancelledBy: string | null;
            cancelReason: string | null;
            /** @description Scheduled and the caller may configure fees */
            cancellable: boolean;
        };
        FeeRetail: {
            /** @description Empty when RETAIL_ORG_ID is not configured */
            orgId: string;
            name: string;
            /** @example No commission, no fees (merchant of record) */
            note: string;
        };
        FeesOverview: {
            /** Format: date-time */
            generatedAt: string;
            currency: string;
            /** @description payments:configure and EDIT on fees (create and cancel versions) */
            canConfigure: boolean;
            /** @description The version in force now */
            current: components["schemas"]["FeeScheduleView"] | null;
            /** @description What a new version starts from (the latest scheduled version, else the active one) */
            base: components["schemas"]["FeeScheduleView"] | null;
            /**
             * Format: date-time
             * @description Earliest effective time of a new version
             */
            minEffectiveFrom: string;
            /** @description false while a version is scheduled */
            immediateAllowed: boolean;
            /** @description Every version, newest first */
            schedules: components["schemas"]["FeeScheduleView"][];
            categories: components["schemas"]["FeeCategory"][];
            /** @description false when catalog could not be read (free text still works) */
            taxonomyAvailable: boolean;
            orgNamesAvailable: boolean;
            retail: components["schemas"]["FeeRetail"];
            limits: components["schemas"]["FeeLimits"];
            /**
             * Format: int64
             * @description Sale amount of the impact preview (10000 = $100.00)
             */
            exampleSaleMinor: number;
            reverseOnRefundHelp: string;
            /** @description The plan catalogue (seller service), for the plan-rate matrix */
            plans: components["schemas"]["FeePlan"][];
            /** @description false when the plan catalogue could not be read */
            plansAvailable: boolean;
            /** @description Plans whose feature text advertises a fee the version in force does not charge */
            planWarnings: string[];
            /** @description Service categories the connection-fee sections price */
            serviceCategories: components["schemas"]["FeeCategory"][];
        };
        FeePlanFba: {
            planCode: string;
            planName: string;
            /** Format: int64 */
            feeMinor: number;
        };
        FeeDraftPlanFba: {
            planCode: string;
            /** Format: int64 */
            feeMinor: number;
        };
        FeePlanRate: {
            /** @example seller_growth */
            planCode: string;
            planName: string;
            /** @description '*' = the plan's rate for categories it does not list */
            category: string;
            label: string;
            bps: number;
        };
        FeeRate: {
            category: string;
            label: string;
            bps: number;
        };
        FeeConnectionFee: {
            /** @description Connection fee of service categories without their own rate */
            defaultBps: number;
            categoryRates: components["schemas"]["FeeRate"][];
            planRates: components["schemas"]["FeePlanRate"][];
        };
        FeeRange: {
            minBps: number;
            maxBps: number;
        };
        FeePlan: {
            code: string;
            /** @description SELLER (product commission) or PROVIDER (connection fee) */
            kind: string;
            name: string;
            status: string;
            visibility: string;
            /** @description The feature that mentions a fee, e.g. "8–12% referral fee by category" */
            featureText: string | null;
            advertised: components["schemas"]["FeeRange"] | null;
            /** @description Lowest–highest plan rate of the version in force */
            configured: components["schemas"]["FeeRange"] | null;
            /** @description How the feature text and the configured rates differ */
            warning: string | null;
        };
        NegotiatedRateAuditEntry: {
            /** @enum {string} */
            action: "CREATED" | "ENDED" | "EXPIRED";
            actor: string;
            reason: string | null;
            /** Format: date-time */
            at: string;
        };
        NegotiatedRate: {
            id: string;
            orgId: string;
            orgName: string | null;
            /** @enum {string} */
            type: "COMMISSION" | "CONNECTION_FEE" | "FBA_FEE";
            /** @description '*' = every category */
            category: string;
            categoryLabel: string;
            rateBps: number;
            /**
             * Format: int64
             * @description FBA_FEE: the fee per unit in minor units for every FBA line; null otherwise
             */
            amountMinor: number | null;
            /** Format: date-time */
            startsAt: string;
            /** Format: date-time */
            endsAt: string | null;
            /** @enum {string} */
            status: "SCHEDULED" | "ACTIVE" | "ENDED" | "EXPIRED";
            reason: string;
            negotiatedBy: string;
            reference: string | null;
            createdBy: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            endedAt: string | null;
            endedBy: string | null;
            endReason: string | null;
            /** Format: date-time */
            expiredAt: string | null;
            audit: components["schemas"]["NegotiatedRateAuditEntry"][];
            /** @description The caller configures fees and the rate is scheduled or active */
            canEnd: boolean;
        };
        NegotiatedRatePage: {
            items: components["schemas"]["NegotiatedRate"][];
            total: number;
            page: number;
            size: number;
            canConfigure: boolean;
        };
        NegotiatedRateDraft: {
            orgId: string;
            /** @enum {string} */
            type: "COMMISSION" | "CONNECTION_FEE" | "FBA_FEE";
            /** @description One category; null or '*' = every category */
            category?: string | null;
            /** @description Required for COMMISSION and CONNECTION_FEE */
            rateBps?: number;
            /**
             * Format: int64
             * @description Required for FBA_FEE: the fee per unit in minor units, for every FBA line of the organisation (no category)
             */
            amountMinor?: number;
            /**
             * Format: date-time
             * @description Default now; never in the past
             */
            startsAt?: string | null;
            /**
             * Format: date-time
             * @description Omit for open-ended
             */
            endsAt?: string | null;
            reason: string;
            /** @description Default the caller */
            negotiatedBy?: string | null;
            /** @description Contract or ticket */
            reference?: string | null;
        };
        NegotiatedRateEnd: {
            reason: string;
        };
        FeeRateQuote: {
            orgId: string;
            orgName: string | null;
            /** @enum {string} */
            kind: "PRODUCT" | "SERVICE";
            category: string;
            categoryLabel: string;
            bps: number;
            /** @enum {string} */
            source: "OVERRIDE" | "PLAN" | "CATEGORY" | "DEFAULT" | "RETAIL";
            planCode: string | null;
            planName: string | null;
            negotiatedRateId: string | null;
            /** Format: date-time */
            negotiatedUntil: string | null;
            /** @description Fee-schedule version in force */
            version: number;
            /** Format: int64 */
            exampleSaleMinor: number;
            /**
             * Format: int64
             * @description The rate applied to exampleSaleMinor
             */
            exampleFeeMinor: number;
            /**
             * @example Negotiated commission until 31 Dec 2026
             * @example Plan rate: Seller Growth
             */
            explanation: string;
        };
        NegotiatedPlanDraft: {
            basePlanCode: string;
            /** Format: int64 */
            monthlyCents: number;
            /**
             * Format: int64
             * @description Default 3 months less 5 %
             */
            quarterlyCents?: number | null;
            /**
             * Format: int64
             * @description Default 10 months
             */
            annualCents?: number | null;
            reason: string;
            reference?: string | null;
            /** @description Also move the organisation to the plan now (billing:adjust; S306). Default: it chooses the plan itself. */
            assign?: boolean;
        };
        NegotiatedPlan: {
            planCode: string;
            planName: string;
            basePlanCode: string;
            orgId: string;
            /** @enum {string} */
            visibility: "INVITE_ONLY";
            published: boolean;
            /** Format: int64 */
            monthlyCents: number;
            /** Format: int64 */
            quarterlyCents: number;
            /** Format: int64 */
            annualCents: number;
            /** @description The organisation was moved to the plan in the same step (S306) */
            assigned?: boolean;
            /**
             * @description What the assignment did, when assigned
             * @enum {string}
             */
            assignedAs?: "IMMEDIATE_UPGRADE" | "AT_RENEWAL" | "NO_CHANGE";
            note: string;
        };
        FeeDraftRate: {
            category: string;
            bps: number;
        };
        FeeDraftPlanRate: {
            planCode: string;
            /** @description '*' or empty = the plan's other categories */
            category: string;
            bps: number;
        };
        FeeConnectionDraft: {
            defaultBps: number;
            categoryRates: components["schemas"]["FeeDraftRate"][];
            planRates: components["schemas"]["FeeDraftPlanRate"][];
        };
        FeeOrgHit: {
            orgId: string;
            name: string;
            kind: string;
            status: string;
            retail: boolean;
            selectable: boolean;
            /** @description Why the organisation can't be chosen */
            reason: string | null;
        };
        FeeOrgSearch: {
            items: components["schemas"]["FeeOrgHit"][];
        };
        FeeDraftCategoryRate: {
            category: string;
            commissionBps: number;
        };
        FeeDraftOverride: {
            orgId: string;
            /** @description Omitted or null = every category */
            category?: string | null;
            commissionBps: number;
        };
        FeeScheduleDraft: {
            /**
             * Format: date-time
             * @description Required unless immediate; in the future
             */
            effectiveFrom?: string | null;
            /** @description Take effect now (the console asks for confirmation) */
            immediate?: boolean;
            defaultCommissionBps: number;
            categoryRates: components["schemas"]["FeeDraftCategoryRate"][];
            overrides: components["schemas"]["FeeDraftOverride"][];
            cardPctBps: number;
            /** Format: int64 */
            cardFixedMinor: number;
            /** Format: int64 */
            fbaPerUnitMinor: number;
            reverseCommissionOnRefund: boolean;
            /** @description AfroTransact Delivery pricing (S255). Omitted or null = carried over from the latest live version; enabled=false stops offering it */
            deliveryPricing?: components["schemas"]["DeliveryPricingDraft"] | null;
            /** @description Product commission per plan. Omitted or null = carried over from the latest live version; [] = no plan rates */
            planRates?: components["schemas"]["FeeDraftPlanRate"][] | null;
            /** @description FBA fee per unit per plan; plans left out pay fbaPerUnitMinor. Omitted or null = carried over from the latest live version; [] = every plan pays the default */
            planFba?: components["schemas"]["FeeDraftPlanFba"][] | null;
            /** @description Connection fees on service bookings. Omitted or null = carried over from the latest live version */
            connectionFee?: components["schemas"]["FeeConnectionDraft"] | null;
            /** @description Why the fees change (required) */
            note: string;
        };
        FeeCancelRequest: {
            reason: string;
        };
        DeliveryBand: {
            /** @description From (inclusive) */
            fromMiles: number;
            /** @description To (exclusive); bands never overlap */
            toMiles: number;
            /** Format: int64 */
            feeMinor: number;
        };
        /** @description FLAT: flatPerPackageMinor × packages. PER_WEIGHT: baseMinor + perLbMinor × weightLb. DISTANCE (per trip): the fee of the band covering the distance, else baseMinor + perMileMinor × miles (half up), then at least minMinor and at most maxMinor (null = no cap) */
        DeliveryTariff: {
            /** @enum {string} */
            mode: "FLAT" | "DISTANCE" | "PER_WEIGHT";
            /** Format: int64 */
            flatPerPackageMinor: number | null;
            /** Format: int64 */
            baseMinor: number | null;
            /**
             * Format: int64
             * @description 85 = $0.85 per mile
             */
            perMileMinor: number | null;
            /**
             * Format: int64
             * @description 50 = $0.50 per pound
             */
            perLbMinor?: number | null;
            bands: components["schemas"]["DeliveryBand"][];
            /** Format: int64 */
            minMinor: number | null;
            /** Format: int64 */
            maxMinor: number | null;
        };
        DeliveryFeeOverride: {
            orgId: string;
            orgName: string | null;
            sellerFee: components["schemas"]["DeliveryTariff"];
        };
        /** @description AfroTransact Delivery pricing of a fee-schedule version */
        DeliveryPricing: {
            /** @description false = not offered under this version */
            enabled: boolean;
            /** @description Beyond this road distance the option is unavailable (BEYOND_MAX_RADIUS) */
            maxRadiusMiles: number;
            /** @description What the shopper pays (4200); null = free */
            customerCharge: components["schemas"]["DeliveryTariff"] | null;
            /** @description What the seller pays per pickup-delivery (4250); null = no fee */
            sellerFee: components["schemas"]["DeliveryTariff"] | null;
            /** @description Negotiated seller fees (AfroTransact Retail never pays one) */
            sellerOverrides: components["schemas"]["DeliveryFeeOverride"][];
        };
        DeliveryDraftOverride: {
            orgId: string;
            sellerFee: components["schemas"]["DeliveryTariff"];
        };
        DeliveryPricingDraft: {
            enabled: boolean;
            /** @description 0 < r ≤ limits.deliveryMaxRadiusMiles */
            maxRadiusMiles: number | null;
            customerCharge: components["schemas"]["DeliveryTariff"] | null;
            sellerFee: components["schemas"]["DeliveryTariff"] | null;
            sellerOverrides: components["schemas"]["DeliveryDraftOverride"][];
        };
        DeliveryQuoteRequest: {
            sellerOrgId: string;
            /** @description Road miles */
            distanceMiles: number;
            /** @default 1 */
            packages: number;
            /**
             * Format: date-time
             * @description Version in force at this time; default now
             */
            at?: string | null;
        };
        DeliveryQuote: {
            available: boolean;
            /** @enum {string|null} */
            unavailableReason: "NOT_CONFIGURED" | "BEYOND_MAX_RADIUS" | null;
            /** Format: int64 */
            customerChargeMinor: number;
            /** Format: int64 */
            sellerFeeMinor: number;
            /** @enum {string|null} */
            sellerFeeSource: "DEFAULT" | "OVERRIDE" | "RETAIL" | null;
            scheduleVersion: number | null;
            maxRadiusMiles: number | null;
            currency: string;
        };
        TaxRatesImportRequest: {
            /** @description CSV text including header row */
            csv: string;
        };
        TaxRatesImportResult: {
            statesUpserted: number;
            localsUpserted: number;
            warnings: string[];
        };
        PickupRow: {
            shipmentId: string;
            orderNumber: string;
            sellerOrgId: string;
            sellerName: string;
            /** @description Drop-off city */
            city: string;
            state: string;
            postalCode: string;
            /** @description AWAITING_ROUTE, ROUTED, DELIVERED, FAILED or CANCELLED */
            status: string;
            routeId: string | null;
            perishable: boolean;
            units: number;
            /** Format: date */
            promisedBy: string | null;
            /** Format: date-time */
            createdAt: string | null;
        };
        PickupList: {
            items: components["schemas"]["PickupRow"][];
            /** @description delivery:dispatch and EDIT on dispatch */
            canAssign: boolean;
        };
        DriverSuggestion: {
            driverId: string;
            routeId: string;
            routeName: string;
            zone: string;
            routeStatus: string;
            straightLineMiles: number;
            /** @description null when geo is down (ranked by straight line) */
            roadMiles: number | null;
            durationMinutes: number | null;
            /** @description ROAD_GOOGLE, ROAD_OSRM, GEODESIC_DETOUR or STRAIGHT_LINE */
            distanceMethod: string;
            /** Format: date-time */
            positionAt: string | null;
            packagesOnRoute: number;
            capacityLeft: number;
        };
        DriverSuggestions: {
            shipmentId: string;
            drivers: components["schemas"]["DriverSuggestion"][];
            canAssign: boolean;
        };
        PickupAssignRequest: {
            routeId: string;
            reason: string;
        };
        PickupAssignResult: {
            shipmentId: string;
            routeId: string;
            routeName: string | null;
            driverId: string | null;
            /** @description The route's status */
            status: string;
        };
        PickupAssignment: {
            id: string;
            shipmentId: string;
            routeId: string;
            driverId: string | null;
            /** @enum {string} */
            mode: "AUTO_NEAREST" | "DISPATCH_OVERRIDE";
            reason: string | null;
            previousRouteId: string | null;
            straightMiles: number | null;
            roadMiles: number | null;
            distanceMethod: string | null;
            actor: string | null;
            /** Format: date-time */
            createdAt: string | null;
        };
        PickupAssignmentList: {
            items: components["schemas"]["PickupAssignment"][];
        };
        PickupWindow: {
            /** @enum {string} */
            day: "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";
            opens: string;
            closes: string;
        };
        ShipFromLocation: {
            id: string;
            orgId: string;
            label: string;
            line1: string;
            line2: string;
            city: string;
            state: string;
            postalCode: string;
            country: string;
            position: {
                lat: number;
                lng: number;
                /** @enum {string} */
                precision: "ADDRESS" | "ZIP" | "POINT";
            } | null;
            pickupEnabled: boolean;
            pickupHours: components["schemas"]["PickupWindow"][];
            zoneName: string | null;
        };
        /**
         * @description chat is WhatsApp
         * @enum {string}
         */
        NotificationChannel: "email" | "sms" | "push" | "chat";
        /**
         * @description Consent rules: security always sends, transactional unless the channel is off, reminder respects preferences and quiet hours, marketing needs opt-in
         * @enum {string}
         */
        NotificationCategory: "security" | "transactional" | "reminder" | "marketing";
        NotificationTemplateSummary: {
            /** @example order.confirmed */
            key: string;
            category: components["schemas"]["NotificationCategory"];
            channels: components["schemas"]["NotificationChannel"][];
            locales: string[];
            /** @description Highest version over the key's locales and channels */
            latestVersion: number;
            /** @description Who created the most recent version (system for seeded templates) */
            updatedBy: string | null;
            /** Format: date-time */
            updatedAt: string | null;
        };
        NotificationTemplateList: {
            items: components["schemas"]["NotificationTemplateSummary"][];
            total: number;
            /** @description notifications:templates:write (new versions and test-sends) */
            canEdit: boolean;
        };
        NotificationTemplateVersion: {
            locale: string;
            channel: components["schemas"]["NotificationChannel"];
            version: number;
            category: components["schemas"]["NotificationCategory"];
            /** @description Email subject, push title or WhatsApp template name */
            subject: string;
            /** @description Email HTML (Go html/template); empty on other channels */
            bodyHtml: string;
            /** @description Text part, SMS, push body or WhatsApp parameters (Go text/template) */
            bodyText: string;
            createdBy: string | null;
            /** Format: date-time */
            createdAt: string | null;
        };
        NotificationTemplateVariant: {
            locale: string;
            channel: components["schemas"]["NotificationChannel"];
            latestVersion: number;
            /** @description Newest first */
            versions: components["schemas"]["NotificationTemplateVersion"][];
        };
        NotificationSample: {
            /**
             * @description event: a producer event through notifications' real ingest; request: a producer's notification.requested; sample: fixed data for keys no event maps to; none: no sample
             * @enum {string}
             */
            source: "event" | "request" | "sample" | "none";
            /** @enum {string} */
            area: "account" | "orders" | "seller" | "payouts" | "bookings" | "trust" | "retail" | "marketing" | "security" | "other";
            /** @description Event types that send this template */
            eventTypes: string[];
            data: {
                [key: string]: unknown;
            };
        };
        NotificationTemplateDetail: {
            key: string;
            category: components["schemas"]["NotificationCategory"];
            variants: components["schemas"]["NotificationTemplateVariant"][];
            sample: components["schemas"]["NotificationSample"];
            canEdit: boolean;
            canTestSend: boolean;
        };
        NotificationDraft: {
            category?: components["schemas"]["NotificationCategory"];
            subject?: string;
            bodyHtml?: string;
            bodyText: string;
        };
        NotificationPreviewRequest: {
            channel: components["schemas"]["NotificationChannel"];
            /** @default en */
            locale: string;
            /**
             * @description 0 = latest
             * @default 0
             */
            version: number;
            /** @description Template fields (start from the sample data) */
            data?: {
                [key: string]: unknown;
            };
            draft?: components["schemas"]["NotificationDraft"];
        };
        SmsSegments: {
            /**
             * @description UCS-2 as soon as one character is outside the GSM 03.38 alphabet
             * @enum {string}
             */
            encoding: "GSM-7" | "UCS-2";
            characters: number;
            /** @description GSM-7 septets (extension characters count two) or UTF-16 code units */
            units: number;
            segments: number;
            /** @description 160 / 70 for one segment, 153 / 67 per part when concatenated */
            perSegment: number;
        };
        NotificationPreview: {
            subject: string;
            /** @description Email HTML; show it in a sandboxed iframe */
            html: string;
            text: string;
            /** @description The rendered version (0 for a draft) */
            version: number;
            category: components["schemas"]["NotificationCategory"];
            sms: components["schemas"]["SmsSegments"] | null;
        };
        NotificationVersionDraft: {
            locale: string;
            channel: components["schemas"]["NotificationChannel"];
            category: components["schemas"]["NotificationCategory"];
            subject?: string;
            bodyHtml?: string;
            bodyText: string;
        };
        NotificationTestSendResult: {
            /** @description Your own email or phone */
            to: string;
            channel: components["schemas"]["NotificationChannel"];
            provider: string | null;
        };
        PreorderCan: {
            /** @description EDIT or FULL and preorders:manage: windows, creatives, areas */
            manage: boolean;
            /** @description FULL and preorders:manage: master switch, fallback, placements, payment setup */
            settings: boolean;
            /** @description preorders:export */
            export: boolean;
            /** @description APPROVE, EDIT or FULL and preorders:late-add */
            lateAdd: boolean;
        };
        StaffDeliveryFeeBand: {
            /** @description Inclusive; the first band starts at 0 */
            fromMiles: number;
            /** @description Exclusive; the next band starts here */
            toMiles: number;
            /** @description Flat fee for an address in the band (minor units) */
            feeMinor: number;
        };
        /**
         * @description The delivery fee of one method in one currency; the ETag carries the version for If-Match. Fields a method does
         *     not use are 0 / empty.
         */
        StaffDeliveryFeeRule: {
            id: string;
            /** @enum {string} */
            method: "AFROTRANSACT_DELIVERY" | "CARRIER" | "PICKUP";
            /** @example USD */
            currency: string;
            version: number;
            /** @description AFROTRANSACT_DELIVERY only */
            bands?: components["schemas"]["StaffDeliveryFeeBand"][];
            /** @description PICKUP only */
            feeMinor?: number;
            /** @description CARRIER: added to the carrier-gateway quote */
            handlingMinor?: number;
            /** @description CARRIER: the fee when there is no quote */
            fallbackMinor?: number;
            /** @description CARRIER: goods subtotal from which shipping is free; null = never */
            freeAboveMinor?: number | null;
            /** @description AFROTRANSACT_DELIVERY: how far AfroTransact delivers (end of the last band) */
            coverageMiles?: number;
            /** Format: date-time */
            updatedAt?: string;
            /** @description Staff subject of the last change */
            updatedBy?: string | null;
            /** @description Why the last change was made */
            reason?: string | null;
            can?: components["schemas"]["PreorderCan"];
        };
        StaffDeliveryFeeRules: {
            items: components["schemas"]["StaffDeliveryFeeRule"][];
            can: components["schemas"]["PreorderCan"];
        };
        /** @description The rule's new numbers; a method ignores what it does not use. */
        StaffDeliveryFeeRequest: {
            bands?: components["schemas"]["StaffDeliveryFeeBand"][];
            feeMinor?: number;
            handlingMinor?: number;
            fallbackMinor?: number;
            freeAboveMinor?: number | null;
            /** @description Why; logged with who and when */
            reason: string;
        };
        StaffDeliveryFeeChanges: {
            items: ({
                id?: string;
                /** @enum {string} */
                method: "AFROTRANSACT_DELIVERY" | "CARRIER" | "PICKUP";
                currency: string;
                version: number;
                /** @description Null for the first version */
                before?: components["schemas"]["StaffDeliveryFeeRule"] | null;
                after: components["schemas"]["StaffDeliveryFeeRule"];
                /** @description Staff subject, or migration */
                actor: string;
                reason: string;
                /** Format: date-time */
                at: string;
            } & {
                [key: string]: unknown;
            })[];
        };
        StaffDeliveryFeePreviewRequest: {
            /** @enum {string} */
            method: "AFROTRANSACT_DELIVERY" | "CARRIER" | "PICKUP";
            currency: string;
            /** @description AFROTRANSACT_DELIVERY: miles from the hub (absent: the window distance, else the farthest band) */
            distanceMiles?: number | null;
            /** @description Apply this pre-order's own fee and distance */
            windowId?: string | null;
            /** @description CARRIER: the carrier-gateway quote, if any */
            carrierQuoteMinor?: number | null;
            /** @description CARRIER: goods subtotal (free-shipping threshold) */
            itemsMinor?: number | null;
        };
        StaffDeliveryFeePreview: {
            /** @description False: no rule can price it (no rule, or beyond how far AfroTransact delivers) */
            applies: boolean;
            fee?: null | ({
                /** @enum {string} */
                method: "AFROTRANSACT_DELIVERY" | "CARRIER" | "PICKUP";
                ruleId?: string | null;
                ruleVersion: number;
                currency?: string;
                amountMinor: number;
                /** @enum {string} */
                source?: "RULE" | "WINDOW_OVERRIDE" | "CARRIER_QUOTE" | "CARRIER_FALLBACK";
                /** @example 10–40 mi */
                band?: string | null;
                distanceMiles?: number | null;
            } & {
                [key: string]: unknown;
            });
        };
        StaffEconomicsNumbers: {
            /** @description Loaded driver cost per hour (wage, insurance), minor units */
            driverCostPerHourMinor: number;
            /** @description Vehicle cost per mile (fuel, wear), minor units */
            driverCostPerMileMinor: number;
            /** @description Seller sweep and loading at the origin */
            collectionMinutes: number;
            /** @description Time per doorstep drop, local driving included */
            perStopMinutes: number;
            /** @description Time at a community pickup point */
            pickupPointStopMinutes: number;
            /** @description Time per order handed over at a pickup point */
            handoverMinutesPerOrder: number;
            /** @description Count the drive back to the origin */
            roundTrip: boolean;
        };
        /**
         * @description The driver cost model; the ETag carries the version for If-Match. Version 0 (`source` CONFIG): the environment's
         *     defaults apply because nobody saved yet.
         */
        StaffEconomicsSettings: components["schemas"]["StaffEconomicsNumbers"] & {
            version: number;
            /** @enum {string} */
            source: "CONFIG" | "STAFF";
            /** Format: date-time */
            updatedAt?: string | null;
            /** @description Staff subject of the last save */
            updatedBy?: string | null;
            /** @description Why the last save was made */
            reason?: string | null;
            can: components["schemas"]["PreorderCan"];
        };
        /** @description The cost model's new numbers; the reason is required and is logged with who and when. */
        StaffEconomicsSettingsRequest: components["schemas"]["StaffEconomicsNumbers"] & {
            /** @description Why; logged with who and when */
            reason: string;
        };
        StaffEconomicsChanges: {
            items: {
                id?: string;
                version: number;
                before: components["schemas"]["StaffEconomicsNumbers"];
                after: components["schemas"]["StaffEconomicsNumbers"];
                /** @description Staff subject */
                actor: string;
                reason: string;
                /** Format: date-time */
                at: string;
            }[];
        };
        StaffRunRoute: {
            /** @description One-way road miles from the origin to the location */
            roadMiles: number;
            /** @description One-way drive time */
            driveMinutes: number;
            /** @description How geo measured it */
            method?: string;
        };
        /** @description cost(n) = fixedCostMinor + n x perOrderCostMinor */
        StaffRunCostModel: {
            driveMiles: number;
            fixedMinutes: number;
            perOrderMinutes: number;
            fixedCostMinor: number;
            perOrderCostMinor: number;
        };
        /** @description P&L at a number of orders */
        StaffRunSnapshot: {
            orders: number;
            /** @description Delivery fees collected */
            feeRevenueMinor: number;
            /** @description Commission attributed to the run, when reported */
            commissionShareMinor?: number | null;
            /** @description Fees plus commission share */
            revenueMinor: number;
            /** @description Estimated driver and vehicle cost */
            estimatedCostMinor: number;
            /** @description Revenue minus estimated cost */
            contributionMarginMinor: number;
        };
        StaffRunEconomics: {
            route: components["schemas"]["StaffRunRoute"];
            cost: components["schemas"]["StaffRunCostModel"];
            /** @description Delivery fee per order */
            feeMinor: number;
            currency: string;
            /** @description Fewest orders whose fees cover the cost; null when a fee never covers a stop */
            breakEvenOrders?: number | null;
            atMinimum: components["schemas"]["StaffRunSnapshot"];
            atCapacity?: components["schemas"]["StaffRunSnapshot"] | null;
            atOrders: components["schemas"]["StaffRunSnapshot"];
        };
        StaffRunPlanRequest: {
            /** Format: uuid */
            locationId: string;
            feeMinor?: number | null;
            minOrders?: number | null;
            capacityOrders?: number | null;
            /** @enum {string|null} */
            dropMode?: "DOORSTEP" | "PICKUP_POINT" | null;
            expectedOrders?: number | null;
            commissionShareMinor?: number | null;
        };
        StaffRunPnl: {
            economics: components["schemas"]["StaffRunEconomics"];
            /** @enum {string} */
            status: "DRAFT" | "ANNOUNCED" | "OPEN" | "EXTENDED" | "CUT_OFF" | "CONFIRMED" | "COLLECTING" | "OUT_FOR_DELIVERY" | "COMPLETED" | "CANCELLED";
            /**
             * @description BEFORE the run is driven, AFTER once completed, CANCELLED: nothing earned or spent
             * @enum {string}
             */
            phase: "BEFORE" | "AFTER" | "CANCELLED";
            cancelReason?: string | null;
            /** @description Orders of a cancelled run (each refunded in full) */
            ordersRefunded: number;
            /** @description Delivery fees handed back */
            refundedFeesMinor: number;
        };
        StaffWindowPlanRequest: {
            /**
             * Format: uuid
             * @description The location of the closing run (absent: matched by the first city)
             */
            locationId?: string | null;
            targets?: string[];
            door?: boolean | null;
            pickup?: boolean | null;
            /** @description The window's own door fee */
            doorFeeMinor?: number | null;
            pickupFeeMinor?: number | null;
            /** @description The minimum being weighed */
            minOrders?: number | null;
            /** @description Orders to show a P&L for (default the minimum) */
            expectedOrders?: number | null;
        };
        StaffWindowPlan: {
            locationId?: string | null;
            locationName?: string | null;
            currency: string;
            feeMinor: number;
            /** @enum {string} */
            dropMode: "DOORSTEP" | "PICKUP_POINT";
            minOrders: number;
            /** @description Null when no location serves the targets or the route cannot be measured */
            economics?: components["schemas"]["StaffRunEconomics"] | null;
            /** @description Whether the minimum reaches break-even (null without a break-even or a minimum) */
            minimumCoversBreakEven?: boolean | null;
            can: components["schemas"]["PreorderCan"];
        };
        StaffWindowEconomics: {
            windowId: string;
            code?: string;
            name?: string;
            status: string;
            cancelled: boolean;
            locationId?: string | null;
            locationName?: string | null;
            currency: string;
            minOrders: number;
            /** @description Orders that count (paid and late adds); 0 for a cancelled window */
            orders: number;
            /** @description What customers paid, delivery fees included; 0 for a cancelled window */
            revenueMinor: number;
            deliveryFeesMinor: number;
            /** @description Orders refunded in full by the cancellation */
            ordersRefunded: number;
            /** @description Of those, refunds or released authorisations already confirmed */
            refundsSettled: number;
            /** @description What customers got back, delivery fees included */
            refundedMinor: number;
            /** @description At the actual orders; null when no location serves the window */
            economics?: components["schemas"]["StaffRunEconomics"] | null;
            minimumCoversBreakEven?: boolean | null;
            can: components["schemas"]["PreorderCan"];
        };
        StaffRunLocation: {
            /** Format: uuid */
            id: string;
            /** @example bastrop-tx */
            slug: string;
            /** @example Bastrop */
            name: string;
            market?: string;
            state?: string | null;
            timeZone?: string;
            currency?: string;
            defaultFeeMinor?: number;
            defaultMinOrders?: number;
            /** @enum {string} */
            dropMode?: "DOORSTEP" | "PICKUP_POINT";
            whatsappInviteUrl?: string | null;
            active?: boolean;
        } & {
            [key: string]: unknown;
        };
        StaffRunLocations: {
            items: components["schemas"]["StaffRunLocation"][];
            can: components["schemas"]["PreorderCan"];
        };
        StaffRun: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            locationId: string;
            status: string;
            /** Format: date-time */
            windowStart: string;
            /** Format: date-time */
            windowEnd?: string;
            /** Format: date-time */
            cutoffAt: string;
            feeMinor: number;
            currency: string;
            minOrders: number;
            capacityOrders?: number | null;
            dropMode?: string;
            reservedOrders: number;
            minimumMet?: boolean;
            cancelReason?: string | null;
        } & {
            [key: string]: unknown;
        };
        StaffRuns: {
            items: components["schemas"]["StaffRun"][];
        };
        StaffRunLocationHistory: {
            location: components["schemas"]["StaffRunLocation"];
            runs: {
                /** Format: uuid */
                id: string;
                status: string;
                /** Format: date-time */
                windowStart: string;
                /** Format: date-time */
                cutoffAt: string;
                orders: number;
                packages: number;
                feeMinor: number;
                /** @description Delivery fees earned; 0 for a cancelled run */
                feeRevenueMinor: number;
                /** @description Orders refunded in full (cancelled runs) */
                ordersRefunded: number;
                minOrders: number;
                minimumMet: boolean;
            }[];
            subscribers: {
                locationId?: string;
                total: number;
                active: number;
                pending: number;
                /** @description Active subscriptions with consent per channel (EMAIL, SMS, PUSH, WHATSAPP) */
                byChannel: {
                    [key: string]: number;
                };
            };
            subscriberGrowth: {
                /** @example 2026-10 */
                month: string;
                count: number;
            }[];
        };
        StaffAdvertorialResults: {
            views: number;
            clicks: number;
            /** @description clicks / views x 100 */
            ctrPct?: number | null;
            /** @description Pre-orders whose tracking tag carries the advertorial; null for runs (their orders are not attributed) */
            orders?: number | null;
        };
        StaffAdvertorial: {
            /** Format: uuid */
            id: string;
            /** @enum {string} */
            source: "RUN" | "WINDOW";
            sourceId: string;
            sourceName?: string | null;
            sourceCode?: string | null;
            sourceStatus?: string | null;
            /** Format: date-time */
            sourceCloses?: string | null;
            locationId?: string | null;
            locationName?: string | null;
            /** @example A */
            variant: string;
            weight: number;
            priority: number;
            /** @description The words follow the window or run until staff edit them */
            generated: boolean;
            headline: string;
            body: string;
            ctaLabel: string;
            /** Format: date-time */
            startsAt?: string | null;
            /** Format: date-time */
            endsAt?: string | null;
            /** @enum {string} */
            status: "DRAFT" | "LIVE" | "PAUSED" | "ENDED";
            /** @enum {string|null} */
            ended?: "SOURCE_CLOSED" | "CANCELLED" | "SCHEDULE" | "STAFF" | null;
            /** @description Visitors can see it right now */
            serving: boolean;
            results: components["schemas"]["StaffAdvertorialResults"];
            createdBy?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            updatedAt?: string;
            version: number;
            can?: components["schemas"]["PreorderCan"];
        };
        StaffAdvertorials: {
            items: components["schemas"]["StaffAdvertorial"][];
            can: components["schemas"]["PreorderCan"];
        };
        StaffAdvertorialGenerateRequest: {
            /** @enum {string} */
            source: "RUN" | "WINDOW";
            /** @description Window id or run id */
            sourceId: string;
        };
        StaffAdvertorialRequest: {
            /**
             * @description Required to create; ignored on an edit
             * @enum {string}
             */
            source?: "RUN" | "WINDOW";
            /** @description Required to create; ignored on an edit */
            sourceId?: string;
            /** @example B */
            variant?: string | null;
            weight?: number | null;
            priority?: number | null;
            headline: string;
            body?: string | null;
            ctaLabel: string;
            /** Format: date-time */
            startsAt?: string | null;
            /** Format: date-time */
            endsAt?: string | null;
        };
        StaffPreorderObject: {
            [key: string]: unknown;
        };
        StaffPreorderItems: {
            items: {
                [key: string]: unknown;
            }[];
            total?: number | null;
            page?: number;
            size?: number;
        } & {
            [key: string]: unknown;
        };
        StaffPreorderList: {
            items: {
                [key: string]: unknown;
            }[];
            total?: number | null;
            /** @description Tab counts: all, open, upcoming, decision (needs a staff decision), closed, draft */
            counts?: {
                [key: string]: number;
            };
            /** @description Minimum order count a new window starts with */
            defaultMinOrders?: number | null;
            can: components["schemas"]["PreorderCan"];
        } & {
            [key: string]: unknown;
        };
        StaffPreorderReason: {
            /** @description Why; logged with who and when */
            reason: string;
        };
        StaffPreorderDecisionRequest: {
            /**
             * @description CANCEL refunds every order in full; PROCEED goes ahead below the minimum
             * @enum {string}
             */
            decision: "CANCEL" | "PROCEED";
            /** @description Why; logged with who and when */
            reason: string;
        };
        StaffPreorderDecisions: {
            items: ({
                id?: string;
                /**
                 * @description AWAITING is the system noting the window closed below its minimum
                 * @enum {string}
                 */
                kind: "AWAITING" | "PROCEED" | "CANCEL" | "EARLY_CLOSE";
                reason: string;
                /** @description Staff subject, or system */
                actor: string;
                /** @description Orders that counted */
                orders?: number;
                minimum?: number;
                /** Format: date-time */
                at: string;
            } & {
                [key: string]: unknown;
            })[];
        };
        StaffPreorderRefunds: {
            total: number;
            /** @description Cancelled; orders-service is refunding */
            pending: number;
            /** @description Captured payment refunded in full */
            refunded: number;
            /** @description Nothing had been captured; the authorisation was released */
            voided: number;
            /** @description Could not be refunded automatically; staff must act */
            failed: number;
            /** @description None pending or failed */
            done: boolean;
        } & {
            [key: string]: unknown;
        };
        /**
         * @description Window fields (delivery-runs PreorderWindow): name, targets[] (country/region[/city] keys), opensAt, closesAt,
         *     deliversOn, ribbon (≤ 80), headline (≤ 140), smsCopy (≤ 160, GSM-7), categories[], door, pickup, pickupPoint,
         *     doorFeeOverride / pickupFeeOverride (minor units, null = the settings' fees), showCount, socialBudget, comms[]
         *     (ANNOUNCE, REMIND_48H, LAST_CALL_6H, CLOSED, OUT_FOR_DELIVERY, PAID_SOCIAL).
         */
        StaffPreorderDraft: {
            name?: string;
            targets?: string[];
            /** Format: date-time */
            opensAt?: string;
            /** Format: date-time */
            closesAt?: string;
            /** Format: date */
            deliversOn?: string;
            ribbon?: string;
            headline?: string;
            smsCopy?: string;
            categories?: string[];
            door?: boolean;
            pickup?: boolean;
            pickupPoint?: {
                [key: string]: unknown;
            } | null;
            doorFeeOverride?: number | null;
            pickupFeeOverride?: number | null;
            showCount?: boolean;
            /** @description Fewest orders the window needs (0 = none). Null: the default on create (`defaultMinOrders` of the list), unchanged on edit. Below it at the close date the window waits for a staff decision */
            minOrders?: number | null;
            comms?: string[];
            version?: number;
        } & {
            [key: string]: unknown;
        };
        StaffPreorderWindow: {
            id?: string;
            /** @example LBK-7 */
            code?: string;
            name?: string;
            /** @description DRAFT, PAUSED, UPCOMING, OPEN, CLOSED, DELIVERED, AWAITING_DECISION (closed below its minimum), CANCELLED */
            status?: string;
            /** @description Fewest orders the window needs (0 = none) */
            minOrders?: number;
            /** @description Orders that count (paid and late adds) */
            orderCount?: number;
            /** @description A minimum is set and orderCount has reached it */
            minimumMet?: boolean;
            /** @description OPEN with the minimum met: staff may close it early */
            canCloseEarly?: boolean;
            /** @description AWAITING_DECISION: staff must cancel or proceed */
            decisionNeeded?: boolean;
            /**
             * @description What became of the window beyond its dates
             * @enum {string|null}
             */
            outcome?: "AWAITING_DECISION" | "PROCEEDED" | "CANCELLED" | null;
            /** @description Window override in minor units (null = the settings' fee for its currency) */
            doorFeeOverride?: number | null;
            pickupFeeOverride?: number | null;
            /** @description Door fee shoppers pay (override, else settings), minor units */
            effectiveDoorFee?: number;
            effectivePickupFee?: number;
            currency?: string;
            pickupWindow?: string | null;
            allowedMethods?: ("CARD" | "WALLET" | "ACH")[];
            creatives?: {
                [key: string]: unknown;
            };
            /** @description Publish checks that fail */
            violations?: {
                [key: string]: unknown;
            }[];
            /** @description The window's own switch (never rewritten by the master switch) */
            active?: boolean;
            /** @description Published and not delivered */
            switchable?: boolean;
            switchState?: components["schemas"]["PreorderSwitchState"];
            /** @description switchState is ON */
            live?: boolean;
            version?: number;
            can: components["schemas"]["PreorderCan"];
        } & {
            [key: string]: unknown;
        };
        /**
         * @description Effective state = master switch AND window switch AND an area on. PAUSED = this window is off ("Off");
         *     GLOBAL_OFF = the master switch holds it ("Off (global switch)"); AREA_OFF = every target area is off;
         *     NOT_LIVE = draft or delivered (no switch).
         * @enum {string}
         */
        PreorderSwitchState: "ON" | "PAUSED" | "GLOBAL_OFF" | "AREA_OFF" | "NOT_LIVE";
        StaffPreorderBulkSwitch: {
            items: ({
                id?: string;
                /** @enum {string} */
                outcome?: "SWITCHED" | "UNCHANGED" | "SKIPPED";
                /** @description The list row now */
                window?: {
                    [key: string]: unknown;
                } | null;
                code?: string | null;
                reason?: string | null;
            } & {
                [key: string]: unknown;
            })[];
            switched?: number;
            unchanged?: number;
            skipped?: number;
            can: components["schemas"]["PreorderCan"];
        } & {
            [key: string]: unknown;
        };
        StaffPreorderRegionRow: {
            /** @example US/TX/Lubbock */
            key: string;
            name: string;
            on: boolean;
            version: number;
            /** @description Live pre-orders selling here (they block removal) */
            windows: string[];
        };
        StaffPreorderRegion: {
            country: string;
            region: string;
            countryName?: string;
            regionName?: string;
            regionLabel?: string;
            wholeRegion?: components["schemas"]["StaffPreorderRegionRow"] | null;
            cities: components["schemas"]["StaffPreorderRegionRow"][];
            /** @description The region's optimistic-lock version (ETag) */
            version: number;
            can: components["schemas"]["PreorderCan"];
        } & {
            [key: string]: unknown;
        };
        /**
         * @description Platform settings (delivery-runs): enabled, fallback (SHIP | WAITLIST), placements {ribbon, hero, badge, pdp,
         *     cart, family}, payment {pickupWindow, allowedMethods
         *     [CARD, WALLET, ACH], lateAdd {savedCard, paymentLink, linkExpiryHours}, missedDelivery {offerRefund,
         *     offerNewDate}}. Answers carry `can`.
         */
        StaffPreorderSettings: {
            enabled?: boolean;
            /** @enum {string} */
            fallback?: "SHIP" | "WAITLIST";
            placements?: {
                [key: string]: boolean;
            };
            payment?: {
                /** @example 10am–2pm */
                pickupWindow?: string;
                allowedMethods?: ("CARD" | "WALLET" | "ACH")[];
                lateAdd?: {
                    savedCard?: boolean;
                    paymentLink?: boolean;
                    /** @description Payment link lifetime in hours; orders-service creates links for at most 24 h */
                    linkExpiryHours?: number;
                };
                missedDelivery?: {
                    offerRefund?: boolean;
                    offerNewDate?: boolean;
                };
            } & {
                [key: string]: unknown;
            };
            version?: number;
            can?: components["schemas"]["PreorderCan"];
        } & {
            [key: string]: unknown;
        };
        StaffPreorderAreas: {
            items?: {
                [key: string]: unknown;
            }[];
            total?: number | null;
            can: components["schemas"]["PreorderCan"];
        } & {
            [key: string]: unknown;
        };
        StaffPreorderLateOrder: {
            customerId: string;
            addressId?: string;
            newAddress?: {
                line1?: string;
                zip?: string;
                placeId?: string;
            };
            address?: components["schemas"]["StaffPreorderLateAddress"];
            /** @constant */
            phoneConfirmed: true;
            items: {
                atin: string;
                qty: number;
            }[];
            /** @enum {string} */
            payment: "SAVED_CARD" | "PAYMENT_LINK";
            /** @example Customer called */
            reason: string;
            note?: string;
        };
        /**
         * @description Delivery address snapshot (delivery-runs PreorderLateAddress). With `addressId` the BFF fills every blank field
         *     from the customer's saved address in identity (fields sent here win); delivery-runs answers 422
         *     `delivery-runs.address-details-required` when street, city, state or postal code is still missing. A saved
         *     address id that is not the customer's is a 422 on `addressId`.
         */
        StaffPreorderLateAddress: {
            fullName?: string;
            phone?: string;
            line1?: string;
            line2?: string;
            city?: string;
            /** @description State / region code */
            region?: string;
            zip?: string;
            country?: string;
            instructions?: string;
        };
        LateAddAddress: {
            id: string;
            label: string;
            fullName?: string;
            phone?: string;
            line1: string;
            line2?: string;
            city: string;
            region: string;
            zip: string;
            country?: string;
            instructions?: string;
        };
        LateAddCustomers: {
            items: {
                id: string;
                name: string;
                phone: string;
                email: string;
                city: string;
                region: string;
                country: string;
                addresses: components["schemas"]["LateAddAddress"][];
                /** @description identity could not list the addresses (enter a new one) */
                addressesUnavailable?: boolean;
            }[];
        };
        /** @description The upstream service's answer, relayed as is (see the operation's description for its schema). */
        MirroredObject: {
            [key: string]: unknown;
        };
        /** @description An upstream list answer wrapped as {items}. */
        MirroredList: {
            items: {
                [key: string]: unknown;
            }[];
        };
        /** @description A page of an upstream list. */
        MirroredPage: {
            items?: {
                [key: string]: unknown;
            }[];
            /** Format: int64 */
            total?: number;
            page?: number;
            size?: number;
        } & {
            [key: string]: unknown;
        };
        /** @description The upstream request body (see the operation's description). */
        MirroredBody: {
            [key: string]: unknown;
        };
        InboundShipmentLine: {
            lineNo?: number;
            sku?: string;
            atin?: string | null;
            /** @description AT-FN unit label code */
            unitLabel?: string | null;
            expectedQty?: number;
            receivedGood?: number;
            receivedDamaged?: number;
            shortQty?: number;
            overQty?: number;
            /** @description Units per case */
            casePack?: number | null;
            cases?: number;
            /** @description Batch on the packing list (GS1 AI 10) */
            lotNo?: string | null;
            /** Format: date */
            mfgDate?: string | null;
            /** Format: date */
            expDate?: string | null;
        } & {
            [key: string]: unknown;
        };
        /** @description An FBA inbound plan or a retail ASN (inventory `InboundShipment`) */
        InboundShipment: {
            /** Format: uuid */
            id: string;
            number: string;
            /** @enum {string} */
            source?: "FBA" | "RETAIL_ASN";
            ownerOrg?: string | null;
            nodeId?: string;
            /** @enum {string} */
            status: "WORKING" | "SHIPPED" | "RECEIVING" | "CLOSED" | "CANCELLED";
            carrier?: string | null;
            trackingNumber?: string | null;
            /** Format: date */
            expectedArrival?: string | null;
            expectedUnits?: number;
            receivedUnits?: number;
            lines: components["schemas"]["InboundShipmentLine"][];
            boxes?: {
                [key: string]: unknown;
            }[];
            /** Format: int64 */
            version?: number;
        } & {
            [key: string]: unknown;
        };
        InboundShipmentPage: {
            items: components["schemas"]["InboundShipment"][];
            /** Format: int64 */
            total: number;
            page?: number;
            size?: number;
        };
        InboundReceiptResult: {
            shipment?: components["schemas"]["InboundShipment"];
            receiptRef?: string | null;
            lots?: components["schemas"]["InventoryLot"][];
            replayed?: boolean;
        } & {
            [key: string]: unknown;
        };
        InboundTrackingResult: {
            shipment?: components["schemas"]["InboundShipment"];
            trackingRegistered?: boolean;
        } & {
            [key: string]: unknown;
        };
        /** @description A batch of one SKU at one node (inventory `Lot`) */
        InventoryLot: {
            /** Format: uuid */
            id: string;
            sku: string;
            lotNo: string;
            nodeId: string;
            ownerOrg?: string | null;
            bin?: string | null;
            qtyOnHand?: number;
            qtyReserved?: number;
            qtyAvailable?: number;
            /** Format: date */
            mfgDate?: string | null;
            /** Format: date */
            expDate?: string | null;
            /** @enum {string} */
            status: "SELLABLE" | "QUARANTINE" | "EXPIRED" | "WRITTEN_OFF" | "DAMAGED";
            /** @enum {string} */
            markdownFlag?: "NONE" | "WITHIN_60" | "WITHIN_30";
            receiptRef?: string | null;
            /** Format: int64 */
            version?: number;
        } & {
            [key: string]: unknown;
        };
        InventoryLotPage: {
            items: components["schemas"]["InventoryLot"][];
            /** Format: int64 */
            total: number;
            page?: number;
            size?: number;
        };
        LotTrace: {
            lot?: components["schemas"]["InventoryLot"];
            movements?: {
                [key: string]: unknown;
            }[];
            relatedLots?: components["schemas"]["InventoryLot"][];
            orders?: {
                [key: string]: unknown;
            }[];
        } & {
            [key: string]: unknown;
        };
        CycleCountLine: {
            lineNo?: number;
            /** Format: uuid */
            lotId?: string;
            sku?: string;
            lotNo?: string;
            bin?: string | null;
            /** Format: date */
            expDate?: string | null;
            expectedQty?: number;
            countedQty?: number | null;
            variance?: number;
            /** @enum {string|null} */
            reasonCode?: "FOUND" | "LOST" | "DAMAGED" | "MISPICK" | "RECEIVING_ERROR" | "THEFT" | "DATA_ENTRY" | "OTHER" | null;
            postedDelta?: number | null;
        } & {
            [key: string]: unknown;
        };
        /** @description inventory `CycleCount` (S281) */
        CycleCount: {
            /** Format: uuid */
            id: string;
            number: string;
            nodeId: string;
            sku?: string | null;
            binPrefix?: string | null;
            blind?: boolean;
            /** @enum {string} */
            status: "OPEN" | "COUNTED" | "POSTED" | "CANCELLED";
            netVariance?: number;
            varianceLines?: number;
            lines: components["schemas"]["CycleCountLine"][];
            /** Format: int64 */
            version?: number;
        } & {
            [key: string]: unknown;
        };
        CycleCountPage: {
            items: components["schemas"]["CycleCount"][];
            /** Format: int64 */
            total: number;
            page?: number;
            size?: number;
        };
        /** @description retail `PurchaseOrder` (amounts in the supplier currency's minor units) */
        PurchaseOrder: {
            /** Format: uuid */
            id: string;
            number: string;
            /** Format: uuid */
            supplierId?: string;
            supplierCode?: string;
            currency?: string;
            destinationNodeId?: string;
            status: string;
            /** Format: int64 */
            totalMinor?: number;
            lines?: {
                [key: string]: unknown;
            }[];
            /** Format: int64 */
            version?: number;
        } & {
            [key: string]: unknown;
        };
        /** @description retail `GoodsReceipt` (counted batches, variances, over-receipt approval) */
        GoodsReceipt: {
            /** Format: uuid */
            id: string;
            number: string;
            /** Format: uuid */
            poId?: string;
            status: string;
            lines?: {
                [key: string]: unknown;
            }[];
            /** Format: int64 */
            version?: number;
        } & {
            [key: string]: unknown;
        };
        /** @description retail `Item` (product master with GTIN, ATIN link and listing) */
        RetailItem: {
            /** Format: uuid */
            id: string;
            sku: string;
            gtin?: string;
            atin?: string | null;
            name: string;
            /** @enum {string} */
            listingStatus?: "DRAFT" | "LIVE" | "PAUSED";
            /** Format: int64 */
            version?: number;
        } & {
            [key: string]: unknown;
        };
        InventoryFlowStage: {
            key: string;
            title: string;
            who: string;
            what: string;
            effect: string;
            /**
             * Format: int64
             * @description null when the service behind the step is unreachable
             */
            count: number | null;
            countLabel: string;
            href: string;
            unavailable?: string;
        };
        InventoryFlow: {
            stages: components["schemas"]["InventoryFlowStage"][];
            /** Format: date-time */
            asOf: string;
        };
        InventoryTraceStep: {
            key: string;
            title: string;
            /** @enum {string} */
            status: "DONE" | "WAITING" | "BROKEN";
            detail: string;
            href?: string;
        };
        InventoryTrace: {
            atin?: string;
            sku?: string;
            title?: string;
            steps: components["schemas"]["InventoryTraceStep"][];
            /** Format: int64 */
            availableUnits: number | null;
            storefrontPath?: string;
            badges: string[];
            /** Format: int64 */
            deliveryDays: number | null;
        };
    };
    responses: {
        /** @description RFC 9457 problem */
        Problem: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description Validation failed; `errors` maps request properties to messages */
        ValidationProblem: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
    };
    parameters: {
        /**
         * @description Sort column and direction, `<column>,asc|desc` (the Spring Data convention); repeat for secondary keys (at most
         *     3). Any column of the table. Empty cells sort last in both directions.
         */
        Sort: string[];
        Resource: components["schemas"]["ResourceKey"];
        RecordId: string;
        ExportId: string;
        ShipmentId: string;
        /** @description Delivery run id (delivery-runs) */
        RunId: string;
        /** @description Window id or code */
        PreorderId: string;
        LocationId: string;
        RunId: string;
        AdvertorialId: string;
        DeliveryFeeMethod: "AFROTRANSACT_DELIVERY" | "CARRIER" | "PICKUP";
        /** @description ISO 4217 code, for example USD */
        DeliveryFeeCurrency: string;
        CreativeSlot: "HERO" | "PDP" | "STORY" | "FEED" | "EMAIL";
        /** @example order.confirmed */
        TemplateKey: string;
        /** @description Makes retries safe (same key = same result). */
        IdempotencyKey: string;
        /** @description Same key = same refund; never refunds twice. */
        IdempotencyKeyRequired: string;
        MarketCountryCode: string;
        MarketRegionCode: string;
        /** @description The version from the ETag (optimistic locking). */
        IfMatch: string;
        PlanCode: string;
        BillingId: string;
        BillingOrgId: string;
        BillingInvoiceId: string;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getStaffSession: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Session */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffSession"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffDashboard: {
        parameters: {
            query?: {
                rangeDays?: 7 | 30 | 90;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Dashboard */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffDashboard"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
    getStaffAssistant: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Status */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffAssistantStatus"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    staffAssistantChat: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    message: string;
                    history?: {
                        /** @enum {string} */
                        role: "user" | "assistant";
                        content: string;
                    }[];
                    language?: string;
                };
            };
        };
        responses: {
            /** @description Answer */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffAssistantReply"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffResource: {
        parameters: {
            query?: {
                /** @description Search over every column the table shows */
                q?: string;
                /**
                 * @description Sort column and direction, `<column>,asc|desc` (the Spring Data convention); repeat for secondary keys (at most
                 *     3). Any column of the table. Empty cells sort last in both directions.
                 */
                sort?: components["parameters"]["Sort"];
                /** @description One of the page's facet values (deep links; the console shows no facet chips) */
                status?: string;
                page?: number;
                size?: number;
                /** @description nextCursor of the previous page (cursor-paged upstreams) */
                cursor?: string;
            };
            header?: never;
            path: {
                resource: components["parameters"]["Resource"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Table page */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ResourcePage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createStaffRecord: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                resource: components["parameters"]["Resource"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RecordValues"];
            };
        };
        responses: {
            /** @description Created record */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecordDetail"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                resource: components["parameters"]["Resource"];
                id: components["parameters"]["RecordId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Record */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecordDetail"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                resource: components["parameters"]["Resource"];
                id: components["parameters"]["RecordId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RecordValues"];
            };
        };
        responses: {
            /** @description Saved record */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecordDetail"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    runStaffAction: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                resource: components["parameters"]["Resource"];
                id: components["parameters"]["RecordId"];
                action: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["RecordValues"];
            };
        };
        responses: {
            /** @description Updated record */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecordDetail"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    openStaffRecordFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                resource: string;
                id: string;
                fileId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Redirect to the file */
            302: {
                headers: {
                    Location?: string;
                    [name: string]: unknown;
                };
                content?: never;
            };
            404: components["responses"]["Problem"];
        };
    };
    addStaffNote: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                resource: components["parameters"]["Resource"];
                id: components["parameters"]["RecordId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NoteRequest"];
            };
        };
        responses: {
            /** @description Updated record */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecordDetail"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffExports: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Export jobs */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ExportJobList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
        };
    };
    createStaffExport: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ExportRequest"];
            };
        };
        responses: {
            /** @description Export ready */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ExportJob"];
                };
            };
            /** @description Export queued */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ExportJob"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffExport: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                exportId: components["parameters"]["ExportId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Export job */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ExportJob"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
        };
    };
    downloadStaffExport: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                exportId: components["parameters"]["ExportId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Signed download URL */
            302: {
                headers: {
                    /** @description Signed URL of the file */
                    Location?: string;
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            410: components["responses"]["Problem"];
        };
    };
    getStaffExportFile: {
        parameters: {
            query: {
                /** @description Unix seconds */
                expires: number;
                sig: string;
            };
            header?: never;
            path: {
                exportId: components["parameters"]["ExportId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description File */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "text/csv": string;
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": string;
                    "application/pdf": string;
                };
            };
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            410: components["responses"]["Problem"];
        };
    };
    searchSupportCustomers: {
        parameters: {
            query: {
                q: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Matches */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CustomerSearch"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getSupportCustomer: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                customerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Customer 360 */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Customer360"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    supportRefund: {
        parameters: {
            query?: never;
            header: {
                /** @description Same key = same refund; never refunds twice. */
                "Idempotency-Key": components["parameters"]["IdempotencyKeyRequired"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SupportRefundRequest"];
            };
        };
        responses: {
            /** @description Refund issued */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SupportActionResult"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    supportResendNotification: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                notificationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SupportResendRequest"];
            };
        };
        responses: {
            /** @description Queued again */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SupportActionResult"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    supportCancelShipment: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                shipmentId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SupportCancelRequest"];
            };
        };
        responses: {
            /** @description Cancelled */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SupportActionResult"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffPlans: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlan"][];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createStaffPlan: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffCreatePlanRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlan"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["PlanCode"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlan"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffPlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["PlanCode"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffUpdatePlanRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlan"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    saveStaffPlanDraft: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["PlanCode"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffPlanPrices"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlan"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    publishStaffPlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["PlanCode"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlan"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    retireStaffPlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["PlanCode"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlan"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    setStaffPlanInvites: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["PlanCode"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffPlanInvitesRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlan"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    previewStaffPlanMigration: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["PlanCode"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlanMigrationPreview"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    migrateStaffPlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["PlanCode"];
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["StaffPlanMigrationRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlanMigrationResult"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffTrialCampaigns: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffTrialCampaign"][];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createStaffTrialCampaign: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffTrialCampaignRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffTrialCampaign"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffTrialCampaign: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["BillingId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffTrialCampaignRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffTrialCampaign"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    setStaffTrialCampaignStatus: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["BillingId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffBillingStatusRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffTrialCampaign"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffRewardRules: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffRewardRule"][];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createStaffRewardRule: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffRewardRuleRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffRewardRule"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    previewStaffRewardRule: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffRewardRuleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffRewardPreview"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffRewardRule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["BillingId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffRewardRuleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffRewardRule"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    setStaffRewardRuleStatus: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["BillingId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffBillingStatusRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffRewardRule"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffBillingGrants: {
        parameters: {
            query?: {
                orgId?: string;
                source?: string;
                type?: string;
                ruleId?: string;
                q?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingGrantPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffBillingReasonCodes: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingReasonCode"][];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    searchStaffBillingOrgs: {
        parameters: {
            query: {
                q: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingOrgSearch"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffBillingPanel: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: components["parameters"]["BillingOrgId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingPanel"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    creditStaffBillingOrg: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                orgId: components["parameters"]["BillingOrgId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffCreditRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingGrant"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    giveStaffBillingFreeDays: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                orgId: components["parameters"]["BillingOrgId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffDaysRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingGrant"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    giveStaffBillingFreeMonths: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                orgId: components["parameters"]["BillingOrgId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffMonthsRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingGrant"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    setStaffBillingChargeGate: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                orgId: components["parameters"]["BillingOrgId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffChargeGateRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingGrant"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    clearStaffBillingChargeGate: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                orgId: components["parameters"]["BillingOrgId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffWaiveRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingGrant"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    overrideStaffBillingTrial: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                orgId: components["parameters"]["BillingOrgId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffDaysRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingGrant"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    waiveStaffBillingInvoice: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                orgId: components["parameters"]["BillingOrgId"];
                invoiceId: components["parameters"]["BillingInvoiceId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffWaiveRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingGrant"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    assignStaffPlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: components["parameters"]["BillingOrgId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffAssignPlanRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlanChange"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    overrideStaffPlanFeeCollection: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: components["parameters"]["BillingOrgId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffPlanFeeCollectionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlanFeeCollection"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffBillingSettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingSettings"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffBillingSettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffBillingSettingsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffBillingSettings"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffFees: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Fees overview */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FeesOverview"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    searchFeeOrgs: {
        parameters: {
            query: {
                q: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Matches (at most 20) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FeeOrgSearch"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createFeeSchedule: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["FeeScheduleDraft"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FeeScheduleView"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    cancelFeeSchedule: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                scheduleId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["FeeCancelRequest"];
            };
        };
        responses: {
            /** @description Cancelled */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FeeScheduleView"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    quoteStaffDelivery: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DeliveryQuoteRequest"];
            };
        };
        responses: {
            /** @description Quote */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeliveryQuote"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    importStaffTaxRates: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxRatesImportRequest"];
            };
        };
        responses: {
            /** @description Import result */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaxRatesImportResult"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listNegotiatedRates: {
        parameters: {
            query?: {
                orgId?: string;
                type?: "COMMISSION" | "CONNECTION_FEE" | "FBA_FEE";
                status?: "SCHEDULED" | "ACTIVE" | "ENDED" | "EXPIRED";
                q?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Negotiated rates */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NegotiatedRatePage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createNegotiatedRate: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NegotiatedRateDraft"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NegotiatedRate"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    endNegotiatedRate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                rateId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NegotiatedRateEnd"];
            };
        };
        responses: {
            /** @description Ended */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NegotiatedRate"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    calculateFeeRate: {
        parameters: {
            query: {
                orgId: string;
                category: string;
                kind?: "PRODUCT" | "SERVICE";
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Effective rate */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["FeeRateQuote"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    negotiatePlanPrice: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NegotiatedPlanDraft"];
            };
        };
        responses: {
            /** @description Private plan created and the organisation invited */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NegotiatedPlan"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffPickups: {
        parameters: {
            query?: {
                /** @description Depot / fulfilment node */
                nodeId?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Waiting pickups */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PickupList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getPickupDriverSuggestions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                shipmentId: components["parameters"]["ShipmentId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Suggestions */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DriverSuggestions"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    assignPickup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                shipmentId: components["parameters"]["ShipmentId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PickupAssignRequest"];
            };
        };
        responses: {
            /** @description Assigned */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PickupAssignResult"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listPickupAssignments: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                shipmentId: components["parameters"]["ShipmentId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description History */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PickupAssignmentList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffRuns: {
        parameters: {
            query?: {
                /** @description Dispatch status */
                status?: "PENDING" | "PLANNED" | "CANCELLED";
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Runs */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunDispatchList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffRun: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Delivery run id (delivery-runs) */
                runId: components["parameters"]["RunId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Run */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunDispatch"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    planStaffRun: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Delivery run id (delivery-runs) */
                runId: components["parameters"]["RunId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Planned */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunDispatch"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    advanceStaffRun: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Delivery run id (delivery-runs) */
                runId: components["parameters"]["RunId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RunAdvanceRequest"];
            };
        };
        responses: {
            /** @description Moved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunDispatch"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffShipFromLocation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                locationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Location */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ShipFromLocation"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listNotificationTemplates: {
        parameters: {
            query?: {
                /** @description Part of a key, a category or a channel */
                q?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Templates */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NotificationTemplateList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getNotificationTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @example order.confirmed */
                key: components["parameters"]["TemplateKey"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Template */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NotificationTemplateDetail"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    previewNotificationTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @example order.confirmed */
                key: components["parameters"]["TemplateKey"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NotificationPreviewRequest"];
            };
        };
        responses: {
            /** @description Rendered */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NotificationPreview"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createNotificationTemplateVersion: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @example order.confirmed */
                key: components["parameters"]["TemplateKey"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NotificationVersionDraft"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NotificationTemplateVersion"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    testSendNotificationTemplate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @example order.confirmed */
                key: components["parameters"]["TemplateKey"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NotificationPreviewRequest"];
            };
        };
        responses: {
            /** @description Sent */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NotificationTestSendResult"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    inspectIdentityToken: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["IdentityTokenInspectionRequest"];
            };
        };
        responses: {
            /** @description What the token says and whether it checks out */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["IdentityTokenInspection"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getIdentityDocuments: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The documents */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["IdentityDocuments"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffPreorders: {
        parameters: {
            query?: {
                /** @description OPEN, UPCOMING, CLOSED, DELIVERED, DRAFT, PAUSED */
                status?: string;
                q?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Windows */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    createStaffPreorder: {
        parameters: {
            query?: never;
            header?: {
                "Idempotency-Key"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffPreorderDraft"];
            };
        };
        responses: {
            /** @description Draft created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    switchStaffPreorders: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    ids: string[];
                    active: boolean;
                };
            };
        };
        responses: {
            /** @description Outcomes */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderBulkSwitch"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPreorderSettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Settings */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>"); send it back as If-Match */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderSettings"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffPreorderSettings: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffPreorderSettings"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>"); send it back as If-Match */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderSettings"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffPreorderAreas: {
        parameters: {
            query?: {
                country?: string;
                region?: string;
                q?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Areas */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderAreas"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    addStaffPreorderArea: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** @example US/TX/Lubbock */
                    key: string;
                };
            };
        };
        responses: {
            /** @description Added */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    toggleStaffPreorderAreas: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    keys: string[];
                    on: boolean;
                };
            };
        };
        responses: {
            /** @description Updated */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    deleteStaffPreorderArea: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                key: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Areas after the removal */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderAreas"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPreorderRegion: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                country: string;
                region: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Coverage */
            200: {
                headers: {
                    /** @description The region's version */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderRegion"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffPreorderRegion: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                country: string;
                region: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** @description null = no "Every city" row */
                    everyCity?: boolean | null;
                    cities: {
                        city: string;
                        on: boolean;
                    }[];
                };
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    /** @description The region's new version */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderRegion"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    searchStaffPreorderPlaces: {
        parameters: {
            query?: {
                country?: string;
                region?: string;
                q?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Places */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderItems"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    verifyStaffPreorderAddress: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    line1: string;
                    zip: string;
                    city?: string;
                    region?: string;
                    country?: string;
                };
            };
        };
        responses: {
            /** @description Verification */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        verified: boolean;
                        placeId: string | null;
                        formatted: string | null;
                    };
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    requestStaffPreorderUpload: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    fileName: string;
                    /** @enum {string} */
                    contentType: "image/jpeg" | "image/png" | "image/webp";
                    size: number;
                    width?: number;
                    height?: number;
                };
            };
        };
        responses: {
            /** @description Upload ticket */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        assetId: string;
                        upload: {
                            /** @enum {string} */
                            method: "PUT" | "POST";
                            url: string;
                            headers: {
                                [key: string]: string;
                            };
                        };
                    };
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffPreorderProducts: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Products */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderItems"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPreorder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Window */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>"); send it back as If-Match */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffPreorder: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffPreorderDraft"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>"); send it back as If-Match */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    switchStaffPreorder: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    active: boolean;
                };
            };
        };
        responses: {
            /** @description Switched */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    publishStaffPreorder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Published */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    pauseStaffPreorder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Paused */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    resumeStaffPreorder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Resumed */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    decideStaffPreorder: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffPreorderDecisionRequest"];
            };
        };
        responses: {
            /** @description Decided */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    cancelStaffPreorder: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffPreorderReason"];
            };
        };
        responses: {
            /** @description Cancelled */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    closeEarlyStaffPreorder: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffPreorderReason"];
            };
        };
        responses: {
            /** @description Closing */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffPreorderDecisions: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Decisions */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderDecisions"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPreorderRefunds: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Refund progress */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderRefunds"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffPreorderCreative: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
                slot: components["parameters"]["CreativeSlot"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    assetId: string;
                };
            };
        };
        responses: {
            /** @description Window with the creative */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    deleteStaffPreorderCreative: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
                slot: components["parameters"]["CreativeSlot"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Window without the creative */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderWindow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffPreorderOrders: {
        parameters: {
            query?: {
                /** @description PAID, LATE_ADD, CANCELLED */
                status?: string;
                channel?: string;
                /** @description Search over every column */
                q?: string;
                /**
                 * @description Sort column and direction, `<column>,asc|desc` (the Spring Data convention); repeat for secondary keys (at most
                 *     3). Any column of the table. Empty cells sort last in both directions.
                 */
                sort?: components["parameters"]["Sort"];
                page?: number;
                size?: number;
            };
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Orders */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderItems"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPreorderSummary: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Summary */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    exportStaffPreorder: {
        parameters: {
            query?: {
                status?: string;
                channel?: string;
                q?: string;
            };
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
                file: "orders.csv" | "orders-filtered.csv" | "picking-list.csv" | "advertorial-report.csv";
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description CSV */
            200: {
                headers: {
                    "Content-Disposition"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "text/csv": string;
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    addStaffPreorderLateOrder: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffPreorderLateOrder"];
            };
        };
        responses: {
            /** @description Late order added */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPreorderObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffDeliveryFees: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Rules */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffDeliveryFeeRules"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffDeliveryFeeChanges: {
        parameters: {
            query?: {
                method?: "AFROTRANSACT_DELIVERY" | "CARRIER" | "PICKUP";
                currency?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Changes */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffDeliveryFeeChanges"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    previewStaffDeliveryFee: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffDeliveryFeePreviewRequest"];
            };
        };
        responses: {
            /** @description Resolution */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffDeliveryFeePreview"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffDeliveryFee: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                method: components["parameters"]["DeliveryFeeMethod"];
                /** @description ISO 4217 code, for example USD */
                currency: components["parameters"]["DeliveryFeeCurrency"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffDeliveryFeeRequest"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffDeliveryFeeRule"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffEconomicsSettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Cost model */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffEconomicsSettings"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffEconomicsSettings: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffEconomicsSettingsRequest"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffEconomicsSettings"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffEconomicsChanges: {
        parameters: {
            query?: {
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Changes */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffEconomicsChanges"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    previewStaffWindowEconomics: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffWindowPlanRequest"];
            };
        };
        responses: {
            /** @description Plan */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffWindowPlan"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPreorderEconomics: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description Window id or code */
                preorderId: components["parameters"]["PreorderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Economics */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffWindowEconomics"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffRunLocations: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Locations */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffRunLocations"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffRunLocationHistory: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                locationId: components["parameters"]["LocationId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description History */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffRunLocationHistory"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffRuns: {
        parameters: {
            query?: {
                locationId?: string;
                /** @description One or more run statuses, comma separated (OPEN,CONFIRMED,CANCELLED…) */
                status?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Runs */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffRuns"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffRunPnl: {
        parameters: {
            query?: {
                commissionShareMinor?: number;
            };
            header?: never;
            path: {
                runId: components["parameters"]["RunId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description P&L */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffRunPnl"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    previewStaffRunEconomics: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffRunPlanRequest"];
            };
        };
        responses: {
            /** @description Economics */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffRunEconomics"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffAdvertorials: {
        parameters: {
            query?: {
                source?: "RUN" | "WINDOW";
                sourceId?: string;
                status?: "DRAFT" | "LIVE" | "PAUSED" | "ENDED";
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Advertorials */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffAdvertorials"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    createStaffAdvertorial: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffAdvertorialRequest"];
            };
        };
        responses: {
            /** @description Draft */
            201: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffAdvertorial"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    generateStaffAdvertorial: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffAdvertorialGenerateRequest"];
            };
        };
        responses: {
            /** @description Advertorial */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffAdvertorial"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffAdvertorial: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                advertorialId: components["parameters"]["AdvertorialId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Advertorial */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffAdvertorial"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffAdvertorial: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                advertorialId: components["parameters"]["AdvertorialId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StaffAdvertorialRequest"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffAdvertorial"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    actStaffAdvertorial: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                advertorialId: components["parameters"]["AdvertorialId"];
                action: "publish" | "pause" | "end";
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Advertorial */
            200: {
                headers: {
                    /** @description Optimistic-lock version ("<version>") */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffAdvertorial"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    searchStaffCustomers: {
        parameters: {
            query?: {
                q?: string;
                windowId?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Customers */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["LateAddCustomers"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffMarketCountries: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Countries */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffMarketCountries"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffMarketCountry: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["MarketCountryCode"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Country */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffMarketCountry"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffMarketCountry: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
            };
            path: {
                code: components["parameters"]["MarketCountryCode"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** @enum {string} */
                    status?: "LIVE" | "PAUSED" | "COMING_SOON" | "PLANNED";
                    launchNote?: string;
                };
            };
        };
        responses: {
            /** @description Country */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffMarketCountry"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    addStaffMarketRegion: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: components["parameters"]["MarketCountryCode"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    code: string;
                    name: string;
                };
            };
        };
        responses: {
            /** @description Country */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffMarketCountry"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    updateStaffMarketRegion: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
            };
            path: {
                code: components["parameters"]["MarketCountryCode"];
                region: components["parameters"]["MarketRegionCode"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    status?: components["schemas"]["StaffPlaceStatus"];
                    everyCity?: boolean;
                    marketplace?: boolean;
                    services?: boolean;
                    food?: boolean;
                    pickup?: boolean;
                    taxRegistration?: boolean;
                };
            };
        };
        responses: {
            /** @description Country */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffMarketCountry"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    addStaffMarketCities: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
            };
            path: {
                code: components["parameters"]["MarketCountryCode"];
                region: components["parameters"]["MarketRegionCode"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    cities: {
                        name: string;
                        status?: components["schemas"]["StaffPlaceStatus"];
                    }[];
                };
            };
        };
        responses: {
            /** @description Country */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffMarketCountry"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    setStaffMarketCities: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
            };
            path: {
                code: components["parameters"]["MarketCountryCode"];
                region: components["parameters"]["MarketRegionCode"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    names: string[];
                    status: components["schemas"]["StaffPlaceStatus"];
                };
            };
        };
        responses: {
            /** @description Country */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffMarketCountry"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    removeStaffMarketCity: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
            };
            path: {
                code: components["parameters"]["MarketCountryCode"];
                region: components["parameters"]["MarketRegionCode"];
                city: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Country */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffMarketCountry"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            412: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    publishStaffMarkets: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": {
                    note?: string;
                };
            };
        };
        responses: {
            /** @description Published */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffMarketPublication"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listStaffPlaces: {
        parameters: {
            query?: {
                country?: string;
                region?: string;
                q?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Places */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StaffPlaceList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffInventoryFlow: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Flow */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InventoryFlow"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffInventoryTrace: {
        parameters: {
            query?: {
                atin?: string;
                sku?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Trace */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InventoryTrace"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffOverview: {
        parameters: {
            query?: {
                nodeId?: string;
                ownerOrg?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffNodes: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffCurrencies: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffExchangeRatesCurrent: {
        parameters: {
            query?: {
                asOf?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffInbound: {
        parameters: {
            query?: {
                ownerOrg?: string;
                nodeId?: string;
                status?: string;
                source?: string;
                sku?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboundShipmentPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffInbound: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboundShipment"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffInboundById: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboundShipment"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffInboundById: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboundShipment"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffInboundTracking: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboundTrackingResult"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffInboundShip: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboundShipment"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffInboundReceipts: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboundReceiptResult"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffInboundClose: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboundShipment"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffInboundCancel: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InboundShipment"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffInboundLabels: {
        parameters: {
            query?: {
                kind?: string;
                format?: string;
                lineNo?: number;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The document (PDF, ZPL as text/plain, or EDI X12) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/pdf": string;
                    "text/plain": string;
                    "application/octet-stream": string;
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffLots: {
        parameters: {
            query?: {
                sku?: string;
                nodeId?: string;
                ownerOrg?: string;
                status?: string;
                lotNo?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InventoryLotPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffLotsMarkdown: {
        parameters: {
            query?: {
                within?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffLotsExpiryRuns: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffLotsMarkdownRuns: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffLotsById: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InventoryLot"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffLotsTrace: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["LotTrace"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffLotsQuarantine: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InventoryLot"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffLotsRelease: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InventoryLot"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffLotsAdjust: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InventoryLot"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffLotsTransfer: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffLotsWriteOff: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InventoryLot"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPositions: {
        parameters: {
            query?: {
                sku?: string;
                nodeId?: string;
                ownerOrg?: string;
                lowStock?: boolean;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffMovements: {
        parameters: {
            query?: {
                sku?: string;
                nodeId?: string;
                lotId?: string;
                reference?: string;
                ownerOrg?: string;
                type?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffReservationsById: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffReconciliationLatest: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffReconciliationRuns: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffCycleCounts: {
        parameters: {
            query?: {
                nodeId?: string;
                status?: string;
                sku?: string;
                ownerOrg?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CycleCountPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffCycleCounts: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CycleCount"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffCycleCountsById: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CycleCount"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffCycleCountsCounts: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CycleCount"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffCycleCountsPost: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CycleCount"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffCycleCountsCancel: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The inventory request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CycleCount"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffItems: {
        parameters: {
            query?: {
                q?: string;
                supplierId?: string;
                listing?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffItems: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RetailItem"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffItemsById: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RetailItem"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffItemsById: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RetailItem"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffItemsAtin: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RetailItem"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffItemsListing: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RetailItem"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffSuppliers: {
        parameters: {
            query?: {
                q?: string;
                status?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffSuppliers: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffSuppliersById: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffSuppliersStatus: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPurchaseOrders: {
        parameters: {
            query?: {
                status?: string;
                supplierId?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffPurchaseOrders: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurchaseOrder"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPurchaseOrdersById: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurchaseOrder"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffPurchaseOrdersLines: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurchaseOrder"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffPurchaseOrdersSubmit: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurchaseOrder"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffPurchaseOrdersApprove: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurchaseOrder"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffPurchaseOrdersSendBack: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurchaseOrder"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffPurchaseOrdersSend: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurchaseOrder"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffPurchaseOrdersConfirm: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurchaseOrder"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffPurchaseOrdersClose: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurchaseOrder"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffPurchaseOrdersCancel: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PurchaseOrder"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPurchaseOrdersDispatches: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffPurchaseOrdersDispatchesRetry: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPurchaseOrdersPdf: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The document (PDF, ZPL as text/plain, or EDI X12) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/pdf": string;
                    "text/plain": string;
                    "application/octet-stream": string;
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffPurchaseOrdersEdi850: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The document (PDF, ZPL as text/plain, or EDI X12) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/pdf": string;
                    "text/plain": string;
                    "application/octet-stream": string;
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffShipments: {
        parameters: {
            query?: {
                stage?: string;
                poId?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffShipments: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffShipmentsById: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffShipmentsAsn: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffShipmentsDocuments: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffShipmentsCharges: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffShipmentsAdvance: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffShipmentsLandedCost: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffReceipts: {
        parameters: {
            query?: {
                status?: string;
                poId?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffReceipts: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GoodsReceipt"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffReceiptsById: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GoodsReceipt"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffReceiptsLines: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GoodsReceipt"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    putStaffReceiptsCharges: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GoodsReceipt"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffReceiptsApproveOverReceipt: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GoodsReceipt"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffReceiptsValidate: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffReceiptsComplete: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GoodsReceipt"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffReceiptsClaimsResolve: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
                claimRef: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GoodsReceipt"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffInvoices: {
        parameters: {
            query?: {
                status?: string;
                poId?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffInvoices: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffInvoicesById: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffInvoicesApprove: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffInvoicesReject: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    postStaffInvoicesRematch: {
        parameters: {
            query?: never;
            header?: {
                /** @description The version from the ETag (optimistic locking). */
                "If-Match"?: components["parameters"]["IfMatch"];
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        /** @description The retail request body, relayed as is. */
        requestBody?: {
            content: {
                "application/json": components["schemas"]["MirroredBody"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredObject"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffGlAccounts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getStaffGlJournal: {
        parameters: {
            query?: {
                source?: string;
                sourceRef?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MirroredPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
}
