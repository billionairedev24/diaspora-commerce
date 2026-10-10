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
         *     `GET /v1/lots`; purchase-orders: retail `GET /v1/purchase-orders`; routes: fulfilment `GET /v1/dispatch/routes`;
         *     support-cases: trust-ops `GET /v1/support-cases`; audit: trust-ops `GET /v1/audit/entries`; markets: content
         *     `GET /v1/markets/draft/countries`; site-settings: content `GET /v1/site-config/settings`; legal-documents: content
         *     `GET /v1/legal`; announcements and placements: content `GET /v1/placements`; identity-providers: identity
         *     `GET /v1/admin/identity-providers`; fee-schedules: payments `GET /v1/fee-schedules`; tax-rates: tax-returns
         *     `GET /v1/tax/rates`. Needs VIEW on the resource's module.
         */
        get: operations["listStaffResource"];
        put?: never;
        /**
         * Create a record from the table's create form (New)
         * @description users: identity `POST /v1/admin/users` (access:admin); announcements and placements: content `POST /v1/placements`
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
         *     `PATCH /v1/legal/{type}/current` (content:legal.edit); announcements and placements: content `PUT /v1/placements/{id}`
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
         *     applications:review) and `final-approve` / `send-back` / `reject` (seller `POST /v1/applications/{id}/decision`,
         *     applications:approve, never by the step-one reviewer); claims `refund` / `deny` (trust-ops
         *     `POST /v1/claims/{id}/decision`, disputes:manage, refunds capped by the role's refund limit); payouts `approve` /
         *     `hold` / `release` (payments, payouts:approve); orders `shipment-status` (orders `POST /v1/shipments/{id}/transitions`).
         *     `Idempotency-Key` is relayed to upstreams that accept one.
         */
        post: operations["runStaffAction"];
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
         * Export a whole table (all pages matching the filters) as CSV or XLSX
         * @description Streams every page of the resource's upstream list with the caller's token and writes the file on the server.
         *     Up to 5,000 rows finish within the request (201 READY); larger exports are queued (202 QUEUED) and built in the
         *     background — poll `GET /v1/staff/exports/{id}`. Capped at 200,000 rows. Needs VIEW on the resource's module.
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
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        Problem: {
            /** Format: uri */
            type: string;
            title: string;
            status: number;
            detail?: string;
            instance?: string;
            /**
             * @description Stable machine code: VALIDATION_FAILED, UNAUTHENTICATED, NOT_STAFF, FORBIDDEN, NOT_FOUND, CONFLICT,
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
         * @description Sidebar modules; keys follow identity's platform module matrix (support and exports are derived).
         * @enum {string}
         */
        StaffModuleKey: "dash" | "exports" | "orders" | "catalog" | "inventory" | "retail" | "dispatch" | "apps" | "support" | "rbac" | "payouts" | "fees" | "marketing" | "disputes" | "audit" | "markets" | "siteCfg" | "dev";
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
        StaffDashboard: {
            rangeDays: number;
            /** Format: date-time */
            generatedAt: string;
            kpis: components["schemas"]["Kpi"][];
            charts: components["schemas"]["ChartSpec"][];
        };
        /** @enum {string} */
        ResourceKey: "users" | "roles" | "applications" | "orders" | "products" | "moderation" | "claims" | "returns" | "payouts" | "inventory" | "purchase-orders" | "routes" | "support-cases" | "audit" | "markets" | "site-settings" | "legal-documents" | "announcements" | "placements" | "identity-providers" | "fee-schedules" | "tax-rates";
        /** @enum {string} */
        Tone: "good" | "warn" | "bad" | "info" | "brand";
        CellValue: string | number | null;
        /** @enum {string} */
        ColumnKind: "text" | "strong" | "mono" | "money" | "number" | "date" | "datetime" | "status" | "percent";
        Column: {
            key: string;
            label: string;
            kind: components["schemas"]["ColumnKind"];
            /** @description Share of the table width in percent (tables never scroll sideways) */
            width: number;
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
            fields: components["schemas"]["FormField"][];
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
        };
        DetailField: {
            label: string;
            value: string;
            /** @enum {string} */
            kind: "text" | "mono" | "money" | "date" | "datetime" | "status" | "link";
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
            /** @description Console path, for example /support?customer=… or /orders?open=AT-… */
            href: string;
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
        /** @enum {string} */
        ExportFormat: "csv" | "xlsx";
        ExportRequest: {
            resource: components["schemas"]["ResourceKey"];
            format: components["schemas"]["ExportFormat"];
            q?: string;
            status?: string;
        };
        ExportJob: {
            id: string;
            resource: components["schemas"]["ResourceKey"];
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
            /** @description Why the fees change (required) */
            note: string;
        };
        FeeCancelRequest: {
            reason: string;
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
        Resource: components["schemas"]["ResourceKey"];
        RecordId: string;
        ExportId: string;
        /** @description Makes retries safe (same key = same result). */
        IdempotencyKey: string;
        /** @description Same key = same refund; never refunds twice. */
        IdempotencyKeyRequired: string;
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
    listStaffResource: {
        parameters: {
            query?: {
                /** @description Free-text search where the upstream supports it */
                q?: string;
                /** @description One of the page's facet values */
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
}
