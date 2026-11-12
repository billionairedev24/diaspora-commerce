// GENERATED from contracts/openapi/tax-returns.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/api-client generate
/* eslint-disable */

export interface paths {
    "/v1/refunds": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Refunds of an order or of a return (staff: refunds:issue or returns:manage) */
        get: operations["list"];
        put?: never;
        /**
         * Partial refund without return (Idempotency-Key; staff or the seller of every line)
         * @description Calls payments POST /v1/payments/{id}/refunds with Idempotency-Key = refund id; payments reverses the seller transfer portion and commission. AfroTransact Retail lines have neither (payments debits Retail sales; nothing is transferred). The tax reversal is booked in the ledger.
         */
        post: operations["create_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/refunds/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A refund */
        get: operations["get_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/refunds/{id}/retry": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Send a pending or failed refund to payments again (same Idempotency-Key; refunds:issue) */
        post: operations["retry"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/return-policies/{orgId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A seller's return policy (returns:manage for the organisation, or staff) */
        get: operations["policy"];
        /** Set the seller's return policy: window ≥ 14 days, restocking fee ≤ 20 %, return address */
        put: operations["putPolicy"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Returns: customers see their own, sellers their organisation's, staff filter by seller */
        get: operations["search"];
        put?: never;
        /**
         * Start a return (Idempotency-Key required; same key replays the same RMA)
         * @description Auto-approved returns come back with the label / QR (drop-off), the handover code (driver pickup) or the refund (no return). Seller-fulfilled returns wait for the seller until the SLA.
         */
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/eligibility": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** What can be returned from an order (per shipment and line: window, methods, report a problem) */
        get: operations["eligibility"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/number/{number}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A return by its number (RMA-XXXXXXXX), e.g. scanned from the QR code */
        get: operations["byNumber"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A return */
        get: operations["get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/{id}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Approve (seller for seller-fulfilled, staff otherwise; returns:manage) */
        post: operations["approve"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Cancel a return before drop-off / pickup (customer) */
        post: operations["cancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/{id}/inspect": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Inspection per line: restock or damaged; passed lines are refunded (returns:manage)
         * @description AfroTransact-fulfilled lines post RETURN_RESTOCK / RETURN_DAMAGED to inventory.
         */
        post: operations["inspect"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/{id}/label": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Retry the return label / handover code of an approved return (staff, returns:manage) */
        post: operations["label"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/{id}/pickup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Driver collected the parcel with the customer's handover code (delivery:operate) */
        post: operations["pickup"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/{id}/receive": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Parcel received at the seller or return centre (returns:manage) */
        post: operations["receive"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/{id}/refund-quote": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Refund estimate of the return (items + proportional tax + fault shipping − restocking fee) */
        get: operations["refundQuote"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/returns/{id}/reject": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Reject with a note to the customer (returns:manage) */
        post: operations["reject"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/liability": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Monthly marketplace liability per jurisdiction (tax:read) */
        get: operations["liability"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/liability/export": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Monthly liability as CSV (tax:read) */
        get: operations["liabilityCsv"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/orders/{orderId}/transactions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Tax ledger entries of an order: sales and refund reversals (tax:read) */
        get: operations["transactions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/quotes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Quote tax per line for a cart or order (tax:quote; orders-service at checkout)
         * @description rate = state rate for the category + local rate for the ZIP; tax = taxable × rate rounded half up once per line. Non-facilitator states and non-US addresses get no tax.
         */
        post: operations["quote"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/rates": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The rate table: state rules and local ZIP rates (tax:read) */
        get: operations["rates"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/rates/local/{state}/{zipPrefix}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Create or update a local rate for a 5-digit ZIP or 3-digit prefix (tax:configure) */
        put: operations["putLocal"];
        post?: never;
        /** Delete a local rate (tax:configure) */
        delete: operations["deleteLocal"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/rates/import": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Bulk upsert state rules and local ZIP rates from CSV (tax:configure)
         * @description Header: kind,country,state,name,zipPrefix,generalRate,groceryRate,preparedFoodRate,shippingTaxable,facilitator,localRate,appliesToGrocery. kind=STATE|LOCAL.
         */
        post: operations["importRates"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/rates/states/{state}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Create or update a state rule (tax:configure; version from the last read to update) */
        put: operations["putState"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The tax method in force and its history (tax:read) */
        get: operations["taxSettings"];
        /**
         * Switch who calculates sales tax (tax:configure)
         * @description RULES is our own rules over the rate tables and tax codes; STRIPE_TAX needs Stripe Tax set up (422 tax.stripe-not-set-up otherwise). A reason is required and the change is recorded. It applies to new quotes; placed orders keep the tax they were charged.
         */
        put: operations["changeTaxMethod"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/review-settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The tax review mode, always-review list, audit sample percentage and their history (tax:read) */
        get: operations["taxReviewSettings"];
        /**
         * Set the tax review mode, always-review list and audit sample percentage (tax:classify-admin)
         * @description taxReview.mode is ALL until staff change it (every classification waits for a tax reviewer). RISK_BASED lets the system approve a classification only when the category is ACTIVE, the rules resolve cleanly (RESOLVED), no required evidence is missing and the category is not on the always-review list; everything else, and every category whose status is NEEDS_APPROVAL, REFINE_CODE, CONFLICT_OPEN or BLOCKED_BUNDLE, stays in the review queue. Never switch it on before the Texas tax professional agrees (docs/engineering/listing-rules.md D7). A reason of 10+ characters is required (422 tax.reason-required) and the change is recorded. alwaysReview replaces the list, absent keeps it (422 tax.unknown-category); auditPercent 0 to 100, absent keeps it (422 tax.invalid-audit-percent). It applies to new classification events; records already decided keep their status. Needs tax:classify-admin only (403 otherwise).
         */
        put: operations["changeTaxReviewSettings"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/remittances": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Month by month and state by state, tax due, remitted and outstanding (tax:read)
         * @description The last `months` months (default 6) up to `through` (default this month), newest first. Due is the ledger's net liability now, so a refund or late sale after filing shows as changedSinceFilingMinor and moves the status to OWES_MORE or OVERPAID.
         */
        get: operations["taxObligations"];
        put?: never;
        /**
         * Record a filing and payment to a state (tax:configure)
         * @description 409 tax.remittance-exists when the month and state already have an active record (void it first); 422 for a future month or date. The amount due at the time is stored with it.
         */
        post: operations["recordTaxRemittance"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/remittances/{month}/{jurisdiction}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One month and state (tax:read) */
        get: operations["taxObligation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/remittances/{month}/history": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Every record of a month, voided ones included (tax:read) */
        get: operations["taxRemittanceHistory"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/remittances/{id}/void": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Withdraw a mistaken record (tax:configure)
         * @description The record stays visible as voided, with who and why. Record the correct one afterwards.
         */
        post: operations["voidTaxRemittance"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/nexus": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Nexus tracker, sales into each state over the last 12 months against its thresholds (tax:read)
         * @description Most urgent first: EXCEEDED (past a threshold and not registered), APPROACHING (80 % or more), REGISTERED, BELOW. Thresholds are the defaults until staff set a state's own; confirm them with a tax advisor. This tracks and warns; it registers nobody.
         */
        get: operations["taxNexusTracker"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/nexus/{jurisdiction}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One state (tax:read) */
        get: operations["taxNexusState"];
        /**
         * Record a state's thresholds and registration (tax:configure)
         * @description A registration needs its number and the date it took effect. 422 tax.invalid-nexus otherwise.
         */
        put: operations["updateTaxNexus"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/reversals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Reverse tax for a refund made outside the returns flow (refunds:issue; idempotent per refundRef and line). Refunds published as payments.refunded are reversed automatically. */
        post: operations["reverse"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/sellers/{orgId}/statement": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Tax collected on a seller's behalf per jurisdiction, for the monthly statement (the seller with payouts:read, or staff with tax:read) */
        get: operations["sellerStatement"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/sellers/{orgId}/statement/export": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Seller statement as CSV */
        get: operations["sellerStatementCsv"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/classifications/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Resolve the tax outcome of a category and its facts, without charging anything (tax:classify)
         * @description Pure rules evaluation over the category tax profile (docs/engineering/category-tax-design.md). Never guesses: missing facts give
         *     status MISSING_FACTS with the fact ids and plain messages, and no outcome. A matched decision row gives outcome, internalTaxCode
         *     and the provider codes; the profile's status gate then decides the status (BLOCKED, CONFLICT_OPEN, NEEDS_APPROVAL, REFINE_CODE or RESOLVED).
         *     Only RESOLVED may be published or charged; consumers must act on status, not on outcome. Used by catalog-service (publish gate, seller form),
         *     the staff console and the quote path. DRAFT rules: a Texas tax professional must approve before production use.
         */
        post: operations["resolveClassification"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/profiles": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Category tax profiles with their status (tax:read) */
        get: operations["listTaxProfiles"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/profiles/{categoryCode}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One category tax profile with its facts and the ordered decision table (tax:read) */
        get: operations["getTaxProfile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/profiles/{categoryCode}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Change a profile's status gate, with a reason (tax:classify-admin)
         * @description Lifting a gate (to ACTIVE) records who approved and why, bumps the rule version and publishes tax.classification.changed. Lifting CONFLICT_OPEN needs the open decision's code to be set first (422 tax.profile-conflict-unresolved).
         */
        put: operations["changeTaxProfileStatus"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/provider-code-map": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The internal outcome code to provider code map (tax:read) */
        get: operations["listProviderCodeMap"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/provider-code-map/{internalTaxCode}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Set or clear one provider's code for an internal outcome code (tax:classify-admin)
         * @description Mapping is per outcome, never per category, because a provider code can carry different outcomes in different scenarios. A change is versioned and audited.
         */
        put: operations["updateProviderCode"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/variant-classifications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Variant classification records; the review queue is status=PENDING_REVIEW (tax:read)
         * @description auditSample=true lists the audit sample instead (status is ignored): items the system approved under the risk-based review rule that are live and wait for a reviewer to confirm (POST approve) or reclassify them.
         */
        get: operations["listVariantClassifications"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/variant-classifications/{atin}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The classification record of one variant ATIN with its audit trail (tax:read) */
        get: operations["getVariantClassification"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/variant-classifications/{atin}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Approve a classification after review (tax:classify-admin)
         * @description Fails with 422 unless the profile resolves to a chargeable decision. A REFINE_CODE profile needs the verified provider code in refinedProviderCodes; staff-owned facts (for example exemption_confirmed) are set here.
         */
        post: operations["approveVariantClassification"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/variant-classifications/{atin}/request-information": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Ask the seller for more information before a decision (tax:classify-admin)
         * @description The message is stored on the record (informationRequest), audited as INFORMATION_REQUESTED and published in tax.classification.changed (reason = the message) so catalog and notifications can show or send it. It is cleared when the seller's facts change.
         */
        post: operations["requestVariantInformation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/variant-classifications/{atin}/reclassify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Move a variant to another category or product type, with a reason (tax:classify-admin)
         * @description The previous decision is kept in the audit trail; the record returns to PENDING_REVIEW and tax.classification.changed is published.
         */
        post: operations["reclassifyVariant"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/tax/shadow/report": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Shadow mode report: provider quote vs in-house quote on real orders (tax:read)
         * @description Lines where the two engines disagree on outcome or amount, grouped by internal tax code and provider code. Shadow quotes are logged and never charged.
         */
        get: operations["shadowReport"];
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
        TaxNexusState: {
            /** @example US-TX */
            jurisdiction?: string;
            name?: string;
            facilitator?: boolean;
            /**
             * Format: int64
             * @description Gross sales shipped there over the trailing 12 months
             */
            salesMinor?: number;
            /** Format: int64 */
            orders?: number;
            /** Format: int64 */
            salesThresholdMinor?: number;
            /** Format: int32 */
            transactionsThreshold?: number;
            /**
             * Format: int32
             * @description The higher of the sales and transaction percentages of their thresholds
             */
            percent?: number;
            /** @enum {string} */
            status?: "REGISTERED" | "EXCEEDED" | "APPROACHING" | "BELOW";
            registered?: boolean;
            registrationNumber?: string;
            /** Format: date */
            registeredOn?: string;
            note?: string;
            customised?: boolean;
            updatedBy?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        NexusResponse: {
            through?: string;
            /** Format: int32 */
            windowMonths?: number;
            /** Format: int64 */
            defaultSalesThresholdMinor?: number;
            states?: components["schemas"]["TaxNexusState"][];
        };
        UpdateNexusRequest: {
            /** Format: int64 */
            salesThresholdMinor: number;
            /** Format: int32 */
            transactionsThreshold?: number;
            registered: boolean;
            registrationNumber?: string;
            /** Format: date */
            registeredOn?: string;
            note?: string;
        };
        TaxRemittance: {
            /** Format: uuid */
            id?: string;
            /** @example US-TX */
            jurisdiction?: string;
            /** @example 2026-09 */
            month?: string;
            currency?: string;
            /**
             * Format: int64
             * @description Net liability when it was recorded
             */
            dueMinor?: number;
            /** Format: int64 */
            paidMinor?: number;
            /** Format: date */
            filedOn?: string;
            /** Format: date */
            paidOn?: string;
            confirmationRef?: string;
            paymentRef?: string;
            note?: string;
            recordedBy?: string;
            /** Format: date-time */
            recordedAt?: string;
            /** Format: date-time */
            voidedAt?: string;
            voidedBy?: string;
            voidReason?: string;
            active?: boolean;
        };
        TaxFilingObligation: {
            month?: string;
            jurisdiction?: string;
            currency?: string;
            /**
             * Format: int64
             * @description Net liability now: collected - refunded
             */
            dueMinor?: number;
            /** @enum {string} */
            status?: "NOTHING_DUE" | "NOT_FILED" | "SETTLED" | "OWES_MORE" | "OVERPAID";
            /**
             * Format: int64
             * @description Due now - paid; negative when overpaid
             */
            outstandingMinor?: number;
            /**
             * Format: int64
             * @description Due now - due when it was recorded
             */
            changedSinceFilingMinor?: number;
            remittance?: components["schemas"]["TaxRemittance"];
        };
        ObligationsResponse: {
            obligations?: components["schemas"]["TaxFilingObligation"][];
        };
        RecordRemittanceRequest: {
            /** @example 2026-09 */
            month: string;
            /** @example US-TX */
            jurisdiction: string;
            /** @example USD */
            currency?: string;
            /** Format: int64 */
            paidMinor: number;
            /** Format: date */
            filedOn: string;
            /** Format: date */
            paidOn: string;
            confirmationRef?: string;
            paymentRef?: string;
            note?: string;
        };
        VoidRemittanceRequest: {
            reason: string;
        };
        TaxMethodChange: {
            /** @enum {string} */
            from?: "RULES" | "STRIPE_TAX" | "AVALARA";
            /** @enum {string} */
            to?: "RULES" | "STRIPE_TAX" | "AVALARA";
            reason?: string;
            changedBy?: string;
            /** Format: date-time */
            changedAt?: string;
        };
        TaxSettings: {
            /** @enum {string} */
            method?: "RULES" | "STRIPE_TAX" | "AVALARA";
            /**
             * @description What applies until staff choose (TAX_PROVIDER)
             * @enum {string}
             */
            configuredDefault?: "RULES" | "STRIPE_TAX";
            /** @description Stripe Tax can be chosen (its key is set) */
            stripeAvailable?: boolean;
            /** @description Avalara can be chosen (account id, license key and company code are set) */
            avalaraAvailable?: boolean;
            /**
             * @description SHADOW mode: this provider is quoted next to the in-house engine on real orders; differences are logged (GET /v1/tax/shadow/report) and the shadow quote is never charged. Null = off.
             * @enum {string|null}
             */
            shadowProvider?: "STRIPE" | "AVALARA" | null;
            changedBy?: string;
            /** Format: date-time */
            changedAt?: string;
            history?: components["schemas"]["TaxMethodChange"][];
        };
        TaxMethodChangeRequest: {
            /** @enum {string} */
            method: "RULES" | "STRIPE_TAX" | "AVALARA";
            /**
             * @description Turn SHADOW mode on for this provider, or null/absent to turn it off. A PUT replaces both method and shadowProvider. The provider must be configured and differ from the method's provider (422 tax.shadow-same-as-method); a method that is a provider needs at least one mapped code (422 tax.provider-not-mapped).
             * @enum {string|null}
             */
            shadowProvider?: "STRIPE" | "AVALARA" | null;
            reason: string;
        };
        TaxReviewSettingsValues: {
            /** @enum {string} */
            mode?: "ALL" | "RISK_BASED";
            /** @description Category codes */
            alwaysReview?: string[];
            auditPercent?: number;
        };
        TaxReviewSettingsChange: {
            from?: components["schemas"]["TaxReviewSettingsValues"] | null;
            to?: components["schemas"]["TaxReviewSettingsValues"];
            reason?: string;
            changedBy?: string;
            /** Format: date-time */
            changedAt?: string;
        };
        /** @description ALL until staff change it. RISK_BASED must only be switched on after the Texas tax professional agrees. */
        TaxReviewSettings: {
            /**
             * @description ALL: every classification waits for a tax reviewer (the default). RISK_BASED: only when the category is ACTIVE, the rules resolve, no required evidence is missing and the category is not on the always-review list.
             * @enum {string}
             */
            mode?: "ALL" | "RISK_BASED";
            /** @description Category codes that always go to review in RISK_BASED mode. Default: dietary-supplements, otc-medicines, medicated-skincare, medical-devices, baby-food-formula, gift-cards. */
            alwaysReview?: string[];
            /** @description Category statuses that always go to review whatever the list says: NEEDS_APPROVAL, REFINE_CODE, CONFLICT_OPEN, BLOCKED_BUNDLE. */
            alwaysReviewStatuses?: string[];
            /** @description Share of auto-approved items also queued as audit items (default 5). Deterministic: a hash of the variant ATIN. */
            auditPercent?: number;
            changedBy?: string | null;
            /** Format: date-time */
            changedAt?: string | null;
            history?: components["schemas"]["TaxReviewSettingsChange"][];
        };
        TaxReviewSettingsChangeRequest: {
            /** @enum {string} */
            mode: "ALL" | "RISK_BASED";
            /** @description Replaces the always-review list; absent or null keeps the current list */
            alwaysReview?: string[] | null;
            /** @description Absent or null keeps the current percentage */
            auditPercent?: number | null;
            reason: string;
        };
        /** @description State rates are fractions (0.0625 = 6.25 %) */
        StateTaxRule: {
            country?: string;
            state?: string;
            name: string;
            generalRate: number;
            groceryRate: number;
            preparedFoodRate: number;
            shippingTaxable?: boolean;
            /** @description AfroTransact collects and remits as marketplace facilitator */
            facilitator?: boolean;
            /** Format: int64 */
            version?: number;
        };
        /** @description Local rate added to the state rate for a ZIP or 3-digit ZIP prefix */
        LocalTaxRate: {
            state?: string;
            zipPrefix?: string;
            name: string;
            rate: number;
            appliesToGrocery?: boolean;
        };
        /** @description Postal address (customer origin or seller / FC return address) */
        Address: {
            name?: string;
            line1?: string;
            line2?: string;
            city?: string;
            state?: string;
            postalCode?: string;
            country?: string;
            phone?: string;
        };
        SellerReturnPolicy: {
            /** Format: int32 */
            windowDays?: number;
            /**
             * Format: int32
             * @description Restocking fee for no-fault returns, basis points (max 2000)
             */
            restockingFeeBps?: number;
            acceptsReturns?: boolean;
            refundAtFirstScan?: boolean;
            returnAddress?: components["schemas"]["Address"];
            /**
             * Format: int64
             * @description Optimistic-lock version from the last read; omit to create
             */
            version?: number;
        };
        /** @description Reverse tax for a refund made outside the returns flow */
        TaxReversal: {
            /** Format: uuid */
            orderId: string;
            refundRef: string;
            currency: string;
            lines: components["schemas"]["TaxReversalLine"][];
        };
        TaxReversalLine: {
            lineId: string;
            /** Format: int64 */
            taxableMinor?: number;
            /** Format: int64 */
            taxMinor?: number;
        };
        TaxReversalResult: {
            /** Format: int32 */
            linesBooked?: number;
        };
        TaxQuoteLine: {
            lineId?: string;
            atin?: string;
            sellerOrgId?: string;
            /** @description Legacy: GENERAL | GROCERY | PREPARED_FOOD chosen by the caller. When absent the stored decision of the variant ATIN decides; a line with no definite decision comes back BLOCKED with no rate (there is no default). */
            taxCategory?: string;
            /** @description catalog product type (no longer decides tax) */
            productType?: string;
            /** @description The seller's behaviour facts for this listing (sold_heated, heated_by_seller, utensils_provided, seller_is_bakery ...). The stored decision is per (variant ATIN, behaviour); a required behaviour fact that is missing gives MISSING_FACTS and a blocked line. */
            sellerBehaviour?: {
                [key: string]: unknown;
            };
            /** Format: int32 */
            quantity?: number;
            /** Format: int64 */
            unitPriceMinor?: number;
            /** Format: int64 */
            discountMinor?: number;
            /** Format: int64 */
            shippingMinor?: number;
        };
        /** @description Cart or order lines shipped to one address */
        TaxQuoteRequest: {
            currency: string;
            shipTo: components["schemas"]["TaxShipTo"];
            lines: components["schemas"]["TaxQuoteLine"][];
            /**
             * Format: date
             * @description Sale date; defaults to today in the rules' jurisdiction. Decides date-bound exemptions (none are open until the holiday calendar exists).
             */
            saleDate?: string;
            /** @description Order or cart id. SHADOW mode compares only quotes that carry one (real orders). */
            orderId?: string;
        };
        TaxShipTo: {
            country?: string;
            state: string;
            postalCode: string;
            city?: string;
        };
        /** @description Tax per line, rounded half up to the minor unit once per line */
        TaxQuote: {
            currency?: string;
            jurisdiction?: string;
            collectedBy?: string;
            /** Format: int64 */
            totalTaxMinor?: number;
            /** @description rules, STRIPE_TAX, AVALARA, EXEMPT, or RULES-FALLBACK when a chosen provider could not take the quote and the rules engine answered instead (never hidden; see fallbackLines) */
            provider?: string;
            /** @description Line ids the provider could not take when provider is RULES-FALLBACK */
            fallbackLines?: string[];
            lines?: components["schemas"]["TaxQuoteLineResult"][];
        };
        TaxQuoteLineResult: {
            lineId?: string;
            sellerOrgId?: string;
            taxCategory?: string;
            /** Format: int64 */
            taxableMinor?: number;
            /** @description Combined rate applied (fraction, 0.0825 = 8.25 %); absent on a blocked line */
            rate?: number;
            /** Format: int64 */
            taxMinor?: number;
            jurisdiction?: string;
            localJurisdiction?: string;
            collectedBy?: string;
            /**
             * Format: int64
             * @description Part of taxMinor charged on the line's shipping
             */
            shippingTaxMinor?: number;
            /** @description Status of the stored decision used for this line; anything but RESOLVED means the line carries no tax figure and checkout must block it */
            decisionStatus?: components["schemas"]["ResolveStatus"];
            outcome?: components["schemas"]["TaxOutcome"];
            internalTaxCode?: string;
            /** @description The code sent to the provider for this outcome */
            providerTaxCode?: string;
            ruleVersion?: string;
            /** @description Plain message for the shopper when the line cannot be taxed */
            blockedReason?: string;
            /**
             * @description How exactly the address was resolved: ZIP (rate table, the documented TX-03 launch limitation) or ADDRESS (a provider resolved the exact address)
             * @enum {string}
             */
            addressPrecision?: "ZIP" | "ADDRESS";
            /** @description Why the line carries no tax, in plain words for the shopper (the category and the rule's own wording; no codes or ids; English). Present only for a RESOLVED, EXEMPT decision; never shown for any other status. */
            explanation?: string;
        };
        /** @description Items of one delivered shipment, the reason per item and the method */
        CreateReturn: {
            /** Format: uuid */
            orderId: string;
            /** Format: uuid */
            shipmentId: string;
            /** @enum {string} */
            method: "DROP_OFF" | "DRIVER_PICKUP" | "NO_RETURN";
            lines: components["schemas"]["CreateReturnLine"][];
            /** @description Optional: where the parcel ships from. Defaults to the order's delivery address (orders.order.placed / orders lookup), so apps send nothing; staff may override it (e.g. the customer moved) */
            returnFrom?: components["schemas"]["Address"];
        };
        CreateReturnLine: {
            lineId: string;
            /** Format: int32 */
            quantity?: number;
            /** @enum {string} */
            reason: "DAMAGED" | "DEFECTIVE" | "WRONG_ITEM" | "NOT_AS_DESCRIBED" | "MISSING_PARTS" | "EXPIRED_OR_SPOILED" | "ARRIVED_LATE" | "NO_LONGER_NEEDED" | "ORDERED_BY_MISTAKE" | "BETTER_PRICE_AVAILABLE";
            comment?: string;
            /** @description media-service asset ids of evidence photos (required for damaged, defective, wrong item, expired or spoiled) */
            evidenceAssetIds?: string[];
        };
        /** @description A return (RMA). handoverCode is only shown to the customer and staff. */
        Rma: {
            /** Format: uuid */
            id?: string;
            number?: string;
            /** Format: uuid */
            orderId?: string;
            orderNumber?: string;
            /** Format: uuid */
            shipmentId?: string;
            customerId?: string;
            sellerOrgId?: string;
            fulfilmentType?: string;
            deliveryMethod?: string;
            /** @enum {string} */
            method?: "DROP_OFF" | "DRIVER_PICKUP" | "NO_RETURN";
            status?: string;
            refundState?: string;
            autoApproved?: boolean;
            /** Format: date-time */
            approvalDueAt?: string;
            refundAtFirstScan?: boolean;
            currency?: string;
            /** Format: int64 */
            estimateMinor?: number;
            carrier?: string;
            trackingNumber?: string;
            labelUrl?: string;
            /** @description Payload the app renders as the drop-off / handover QR code */
            qrPayload?: string;
            handoverCode?: string;
            /** Format: date-time */
            dropOffBy?: string;
            decisionNote?: string;
            cancellable?: boolean;
            lines?: components["schemas"]["RmaLine"][];
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        RmaLine: {
            lineId?: string;
            atin?: string;
            title?: string;
            /** Format: int32 */
            quantity?: number;
            /** Format: int64 */
            unitPriceMinor?: number;
            /** @enum {string} */
            reason?: "DAMAGED" | "DEFECTIVE" | "WRONG_ITEM" | "NOT_AS_DESCRIBED" | "MISSING_PARTS" | "EXPIRED_OR_SPOILED" | "ARRIVED_LATE" | "NO_LONGER_NEEDED" | "ORDERED_BY_MISTAKE" | "BETTER_PRICE_AVAILABLE";
            comment?: string;
            evidenceAssetIds?: string[];
            outcome?: string;
            condition?: string;
            stockStatus?: string;
            inspectionNote?: string;
        };
        Decision: {
            note?: string;
        };
        Pickup: {
            /** @description 6-digit code the customer shows the driver */
            handoverCode?: string;
        };
        InspectLine: {
            lineId: string;
            /** @enum {string} */
            outcome: "PASS" | "FAIL";
            /**
             * @description Default: RESTOCK when passed, DAMAGED when failed
             * @enum {string}
             */
            condition?: "RESTOCK" | "DAMAGED";
            note?: string;
        };
        Inspection: {
            lines: components["schemas"]["InspectLine"][];
            note?: string;
        };
        AdjustmentLine: {
            lineId: string;
            /** Format: int64 */
            itemsMinor?: number;
            /** Format: int64 */
            shippingMinor?: number;
        };
        /** @description Partial refund of amounts of an order's lines; tax follows proportionally (refunds:issue) */
        CreateRefund: {
            /** Format: uuid */
            orderId: string;
            lines: components["schemas"]["AdjustmentLine"][];
            reason: string;
        };
        Refund: {
            /** Format: uuid */
            id?: string;
            kind?: string;
            /** Format: uuid */
            rmaId?: string;
            /** Format: uuid */
            orderId?: string;
            /** Format: uuid */
            paymentId?: string;
            currency?: string;
            status?: string;
            /** Format: int64 */
            itemsMinor?: number;
            /** Format: int64 */
            shippingMinor?: number;
            /** Format: int64 */
            taxMinor?: number;
            /** Format: int64 */
            restockingFeeMinor?: number;
            /** Format: int64 */
            totalMinor?: number;
            reason?: string;
            paymentsRefundId?: string;
            failure?: string;
            /** Format: int32 */
            attempts?: number;
            lines?: components["schemas"]["RefundLineAmount"][];
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            issuedAt?: string;
        };
        RefundLineAmount: {
            lineId?: string;
            /** Format: int32 */
            quantity?: number;
            /** Format: int64 */
            itemsMinor?: number;
            /** Format: int64 */
            shippingMinor?: number;
            /** Format: int64 */
            taxMinor?: number;
            /** Format: int64 */
            restockingFeeMinor?: number;
            /** Format: int64 */
            totalMinor?: number;
        };
        /** @description Monthly tax per jurisdiction: collected on sales − reversed by refunds (booked in the month of the refund) = net to remit */
        LiabilityReport: {
            month?: string;
            sellerOrgId?: string;
            rows?: components["schemas"]["LiabilityRow"][];
        };
        LiabilityRow: {
            jurisdiction?: string;
            currency?: string;
            /** Format: int64 */
            orders?: number;
            /** Format: int64 */
            grossSalesMinor?: number;
            /** Format: int64 */
            taxableSalesMinor?: number;
            /** Format: int64 */
            taxCollectedMinor?: number;
            /** Format: int64 */
            taxRefundedMinor?: number;
            /** Format: int64 */
            netTaxMinor?: number;
        };
        TaxRatesImportRequest: {
            csv: string;
        };
        TaxRatesImportResult: {
            statesUpserted: number;
            localsUpserted: number;
            warnings: string[];
        };
        TaxRateTable: {
            states?: components["schemas"]["StateTaxRule"][];
            locals?: components["schemas"]["LocalTaxRate"][];
        };
        TaxTransaction: {
            /** Format: uuid */
            id?: string;
            kind?: string;
            /** Format: uuid */
            orderId?: string;
            orderNumber?: string;
            lineId?: string;
            sourceRef?: string;
            sellerOrgId?: string;
            jurisdiction?: string;
            localJurisdiction?: string;
            currency?: string;
            /** Format: int64 */
            taxableMinor?: number;
            /** Format: int64 */
            taxMinor?: number;
            /** Format: date-time */
            occurredAt?: string;
        };
        /** @description Refund estimate: items + proportional tax + shipping when the seller is at fault − restocking fee */
        RefundQuote: {
            /** Format: int64 */
            itemsMinor?: number;
            /** Format: int64 */
            shippingMinor?: number;
            /** Format: int64 */
            taxMinor?: number;
            /** Format: int64 */
            restockingFeeMinor?: number;
            /** Format: int64 */
            totalMinor?: number;
            lines?: components["schemas"]["RefundLineAmount"][];
        };
        LineEligibility: {
            lineId?: string;
            atin?: string;
            title?: string;
            /** Format: int32 */
            quantity?: number;
            /** Format: int64 */
            unitPriceMinor?: number;
            eligible?: boolean;
            /** @description Food / perishables: report a problem, no return */
            reportProblemOnly?: boolean;
            sellerFaultOnly?: boolean;
            /** Format: int32 */
            maxQuantity?: number;
            /** Format: date-time */
            windowEndsAt?: string;
            methods?: ("DROP_OFF" | "DRIVER_PICKUP" | "NO_RETURN")[];
            ineligibleCode?: string;
        };
        ReturnEligibility: {
            /** Format: uuid */
            orderId?: string;
            orderNumber?: string;
            currency?: string;
            shipments?: components["schemas"]["ShipmentEligibility"][];
        };
        ShipmentEligibility: {
            /** Format: uuid */
            shipmentId?: string;
            sellerOrgId?: string;
            sellerName?: string;
            fulfilmentType?: string;
            deliveryMethod?: string;
            /** Format: date-time */
            deliveredAt?: string;
            lines?: components["schemas"]["LineEligibility"][];
        };
        /** @enum {string} */
        TaxProvider: "STRIPE" | "AVALARA";
        /**
         * @description The gate of a category profile. Precedence when several apply CONFLICT_OPEN, BLOCKED_BUNDLE, NEEDS_APPROVAL, REFINE_CODE.
         * @enum {string}
         */
        ProfileStatus: "ACTIVE" | "NEEDS_APPROVAL" | "BLOCKED_BUNDLE" | "REFINE_CODE" | "CONFLICT_OPEN";
        /** @enum {string} */
        ResolveStatus: "RESOLVED" | "MISSING_FACTS" | "BLOCKED" | "NEEDS_APPROVAL" | "REFINE_CODE" | "CONFLICT_OPEN";
        /** @enum {string} */
        TaxOutcome: "EXEMPT" | "TAXABLE" | "BLOCKED";
        /**
         * @description VARIANT product facts, OFFER and SELLER behaviour facts, STAFF set by tax review, CONTEXT worked out by the system
         * @enum {string}
         */
        FactOwner: "VARIANT" | "OFFER" | "SELLER" | "STAFF" | "CONTEXT";
        /** @enum {string} */
        ClassificationRecordStatus: "DRAFT" | "MISSING_FACTS" | "PENDING_REVIEW" | "APPROVED" | "REJECTED" | "STALE";
        ClassificationResolveRequest: {
            /** @description Stable category code, e.g. nuts-seeds */
            categoryCode: string;
            productTypeCode?: string;
            /** @description Product facts by fact id (VARIANT and STAFF owned) */
            facts: {
                [key: string]: unknown;
            };
            /** @description Seller behaviour facts by fact id (OFFER and SELLER owned) */
            sellerBehaviour?: {
                [key: string]: unknown;
            };
            /** @description System facts (CONTEXT owned), e.g. gift_card_event. exemption_window_active is derived from saleDate when absent. */
            context?: {
                [key: string]: unknown;
            };
            /** @description Two-letter state; omitted means the launch state (TX) */
            shipToState?: string;
            /**
             * Format: date
             * @description Defaults to today in the jurisdiction's time zone
             */
            saleDate?: string;
        };
        ClassificationResolveResponse: {
            status: components["schemas"]["ResolveStatus"];
            outcome?: components["schemas"]["TaxOutcome"];
            /** @description Outcome-level internal code, e.g. AT.NUTS_SEEDS.PLAIN_MULTI_SERVING_2_5OZ_PLUS */
            internalTaxCode?: string;
            /** @description Provider code per provider for this outcome; a provider with no mapping is absent */
            providerCodes?: {
                [key: string]: string;
            };
            /** @description Decision row that matched, e.g. S007 */
            rowId?: string;
            /** @description Display only: 0%, 6.25%-8.25% or Not set. Never used to charge. */
            rateDisplay?: string;
            missingFacts: components["schemas"]["MissingFact"][];
            ruleVersion: string;
            /** @description Plain explanation for staff and sellers */
            reason?: string;
            /** @description Open decision number (1 to 5) behind a CONFLICT_OPEN, NEEDS_APPROVAL or REFINE_CODE status */
            openDecision?: number;
        };
        MissingFact: {
            factId: string;
            label?: string;
            /** @description Plain words for the seller */
            message: string;
        };
        FactDefinition: {
            id: string;
            label: string;
            /** @enum {string} */
            kind: "BOOLEAN" | "DECIMAL" | "INTEGER" | "PERCENT" | "CHOICE" | "FILE";
            unit?: string;
            owner: components["schemas"]["FactOwner"];
            help?: string;
            /** @description A FILE fact that is proof for an exemption claim */
            evidence?: boolean;
            choices?: {
                value?: string;
                label?: string;
            }[];
        };
        /** @description A fact as a category uses it. required with a null requiredWhen means always required. */
        FactRequirement: {
            fact?: string;
            required?: boolean;
            requiredWhen?: components["schemas"]["Condition"];
            /** @description Narrowed choice list for this category */
            choices?: string[];
        };
        /** @description Expression tree: {all:[..]}, {any:[..]}, {not:..} or a leaf {op: isTrue|isFalse|eq|in|gt|gte|lt|lte, fact, value|values}. Null means always. */
        Condition: {
            [key: string]: unknown;
        } | null;
        DecisionRow: {
            /** @description S001 to S105, the Appendix A scenario number */
            id?: string;
            scenario?: number;
            when?: components["schemas"]["Condition"];
            /** @description The document's wording */
            condition?: string;
            outcome?: components["schemas"]["TaxOutcome"];
            internalTaxCode?: string;
            stripeCode?: string;
            providerCodes?: {
                [key: string]: string | null;
            };
            rateDisplay?: string;
            note?: string;
        };
        TaxProfileSummary: {
            categoryCode?: string;
            name?: string;
            /** @description Department code */
            department?: string;
            departmentName?: string;
            /** @description Stripe codes the category uses (also in the list */
            stripeCodes?: string[];
            status?: components["schemas"]["ProfileStatus"];
            /** @enum {string} */
            defaultOutcome?: "EXEMPT" | "TAXABLE" | "BLOCKED" | "CONDITIONAL";
            scenarioCount?: number;
            openDecision?: number;
            ruleVersion?: string;
        };
        TaxProfile: components["schemas"]["TaxProfileSummary"] & {
            stripeCodes?: string[];
            conflictingCodes?: string[];
            statusReasons?: {
                status?: components["schemas"]["ProfileStatus"];
                decision?: number;
                text?: string;
            }[];
            factGroups?: string[];
            facts?: (components["schemas"]["FactDefinition"] & components["schemas"]["FactRequirement"])[];
            decisionTable?: components["schemas"]["DecisionRow"][];
            changedBy?: string;
            /** Format: date-time */
            changedAt?: string;
            note?: string;
            /** @description Status changes of the profile, newest first (detail only) */
            history?: {
                /** Format: date-time */
                at?: string;
                actor?: string;
                kind?: string;
                reason?: string;
                approvedBy?: string;
                decisionRef?: string;
                ruleVersion?: string;
                before?: {
                    [key: string]: unknown;
                };
                after?: {
                    [key: string]: unknown;
                };
            }[];
        };
        TaxProfileStatusChange: {
            status: components["schemas"]["ProfileStatus"];
            reason: string;
            /** @description Name or firm of the tax professional who approved, required when lifting NEEDS_APPROVAL, CONFLICT_OPEN or BLOCKED_BUNDLE */
            approvedBy?: string;
            /** @description Reference to the signed-off open decision or opinion letter */
            decisionRef?: string;
        };
        ProviderCodeMapEntry: {
            internalTaxCode?: string;
            categoryCode?: string;
            outcome?: components["schemas"]["TaxOutcome"];
            provider?: components["schemas"]["TaxProvider"];
            providerCode?: string | null;
            /** @description A person confirmed the provider treats this code as this outcome */
            verified?: boolean;
            /** @description The mapping may be used for a quote */
            active?: boolean;
            verifiedNote?: string;
            /** @description Same as verifiedNote (why the entry was set or verified) */
            note?: string;
            mapVersion?: number;
            changedBy?: string;
            /** Format: date-time */
            changedAt?: string;
        };
        ProviderCodeMapUpdate: {
            provider: components["schemas"]["TaxProvider"];
            /** @description Null clears the mapping */
            providerCode?: string | null;
            verified?: boolean;
            reason: string;
        };
        VariantClassification: {
            /** @description The variant ATIN (the document's SKU) */
            atin?: string;
            gtin?: string;
            /** Format: uuid */
            productId?: string;
            title?: string;
            categoryCode?: string;
            productTypeCode?: string;
            status?: components["schemas"]["ClassificationRecordStatus"];
            facts?: {
                [key: string]: unknown;
            };
            /** @description Facts set by tax review (owner STAFF) */
            staffFacts?: {
                [key: string]: unknown;
            };
            /**
             * Format: int64
             * @description Orders placed with this variant (detail only)
             */
            ordersAffected?: number;
            /** @description Approved by the system under the risk-based rule and waiting in the audit sample for a reviewer to confirm or reclassify it. The item is live meanwhile. */
            auditSample?: boolean;
            /** Format: date-time */
            auditSampledAt?: string | null;
            /** @description What tax review asked the seller to provide; cleared when the seller's facts change */
            informationRequest?: {
                message?: string;
                requestedBy?: string;
                /** Format: date-time */
                requestedAt?: string;
            } | null;
            ownerOrgId?: string;
            evidence?: {
                factId?: string;
                fileName?: string;
                url?: string;
                uploadedBy?: string;
                /** Format: date-time */
                uploadedAt?: string;
            }[];
            /** @description Verified SKU-level code per provider for REFINE_CODE profiles */
            refinedProviderCodes?: {
                [key: string]: string;
            };
            resolution?: components["schemas"]["ClassificationResolveResponse"];
            /** @description The stored decision per selling organisation (variant facts plus that seller's behaviour facts) */
            decisions?: {
                sellerOrgId?: string;
                sellerBehaviour?: {
                    [key: string]: unknown;
                };
                resolution?: components["schemas"]["ClassificationResolveResponse"];
            }[];
            classifiedBy?: string;
            /** Format: date-time */
            classifiedAt?: string;
            reviewedBy?: string;
            /** Format: date-time */
            reviewedAt?: string;
            ruleVersion?: string;
            audit?: {
                /** Format: date-time */
                at?: string;
                actor?: string;
                /** @enum {string} */
                action?: "FACTS_CHANGED" | "SUBMITTED" | "APPROVED" | "REJECTED" | "RECLASSIFIED" | "MARKED_STALE" | "EVIDENCE_ADDED" | "INFORMATION_REQUESTED" | "AUDIT_SAMPLED" | "AUDIT_CONFIRMED";
                reason?: string;
                ruleVersion?: string;
                before?: {
                    [key: string]: unknown;
                };
                after?: {
                    [key: string]: unknown;
                };
            }[];
        };
        VariantClassificationPage: {
            items?: components["schemas"]["VariantClassification"][];
            page?: number;
            size?: number;
            /** Format: int64 */
            total?: number;
        };
        ClassificationApproveRequest: {
            reason?: string;
            /** @description Facts owned by STAFF */
            staffFacts?: {
                [key: string]: unknown;
            };
            refinedProviderCodes?: {
                [key: string]: string;
            };
            /** @description Reference of the tax professional's sign-off */
            decisionRef?: string;
        };
        ClassificationReclassifyRequest: {
            categoryCode: string;
            productTypeCode?: string;
            reason: string;
        };
        ShadowReport: {
            /** Format: date */
            from?: string;
            /** Format: date */
            to?: string;
            provider?: components["schemas"]["TaxProvider"];
            /** Format: int64 */
            linesCompared?: number;
            /** Format: int64 */
            linesDiffering?: number;
            /** Format: int64 */
            absoluteDifferenceMinor?: number;
            /**
             * Format: int64
             * @description provider minus in-house
             */
            netDifferenceMinor?: number;
            byInternalTaxCode?: components["schemas"]["ShadowGroup"][];
            /** @description "Ready to cut over?" thresholds (placeholders to agree with the tax adviser): minDays, minLines, minLinesPerCode, maxOutcomeDisagreementPct, maxProviderErrorPct */
            thresholds?: {
                [key: string]: number;
            };
            byCategory?: components["schemas"]["ShadowGroup"][];
            /** @description Per in-house outcome (EXEMPT, TAXABLE or NONE) */
            byOutcome?: components["schemas"]["ShadowGroup"][];
            byState?: components["schemas"]["ShadowGroup"][];
            /** @description MATCH, OUTCOME, RATE, PROVIDER_ERROR, NO_DECISION */
            byKind?: components["schemas"]["ShadowGroup"][];
            differences?: components["schemas"]["ShadowDifference"][];
            page?: number;
            size?: number;
        };
        /** @description A group of compared lines and how many of them disagree */
        ShadowGroup: {
            /** @description The internal tax code, category, outcome, state or kind */
            key?: string;
            /** @description Set for byInternalTaxCode groups */
            internalTaxCode?: string;
            providerCode?: string;
            /** Format: int64 */
            lines?: number;
            /** Format: int64 */
            differing?: number;
            /** @description differing divided by lines */
            disagreementRate?: number;
            /** Format: int64 */
            absoluteDifferenceMinor?: number;
        };
        ShadowDifference: {
            /** @description The order or cart id the quote carried */
            orderRef?: string;
            categoryCode?: string;
            state?: string;
            /** @description Same as state */
            shipToState?: string;
            /** Format: uuid */
            orderId?: string;
            lineId?: string;
            atin?: string;
            internalTaxCode?: string;
            providerCode?: string;
            /** @enum {string} */
            kind?: "OUTCOME" | "RATE" | "BOTH" | "PROVIDER_ERROR" | "NO_DECISION";
            inHouse?: components["schemas"]["ShadowQuoteSide"];
            provider?: components["schemas"]["ShadowQuoteSide"];
            /** Format: int64 */
            differenceMinor?: number;
            /** Format: date-time */
            quotedAt?: string;
        };
        ShadowQuoteSide: {
            outcome?: components["schemas"]["TaxOutcome"];
            /** @description Fraction */
            rate?: number;
            /** Format: int64 */
            taxMinor?: number;
            error?: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    list: {
        parameters: {
            query?: {
                orderId?: string;
                rmaId?: string;
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
                    "*/*": components["schemas"]["Refund"][];
                };
            };
        };
    };
    create_1: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateRefund"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Refund"];
                };
            };
        };
    };
    get_1: {
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
                    "*/*": components["schemas"]["Refund"];
                };
            };
        };
    };
    retry: {
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
                    "*/*": components["schemas"]["Refund"];
                };
            };
        };
    };
    policy: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
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
                    "*/*": components["schemas"]["SellerReturnPolicy"];
                };
            };
        };
    };
    putPolicy: {
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
                "application/json": components["schemas"]["SellerReturnPolicy"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SellerReturnPolicy"];
                };
            };
        };
    };
    search: {
        parameters: {
            query?: {
                orderId?: string;
                status?: "REQUESTED" | "APPROVED" | "AUTO_APPROVED" | "LABEL_ISSUED" | "IN_TRANSIT" | "RECEIVED" | "INSPECTED" | "REFUNDED" | "REJECTED" | "CANCELLED";
                sellerOrgId?: string;
                limit?: number;
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
                    "*/*": components["schemas"]["Rma"][];
                };
            };
        };
    };
    create: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateReturn"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Rma"];
                };
            };
        };
    };
    eligibility: {
        parameters: {
            query: {
                orderId: string;
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
                    "*/*": components["schemas"]["ReturnEligibility"];
                };
            };
        };
    };
    byNumber: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                number: string;
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
                    "*/*": components["schemas"]["Rma"];
                };
            };
        };
    };
    get: {
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
                    "*/*": components["schemas"]["Rma"];
                };
            };
        };
    };
    approve: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["Decision"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Rma"];
                };
            };
        };
    };
    cancel: {
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
                    "*/*": components["schemas"]["Rma"];
                };
            };
        };
    };
    inspect: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Inspection"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Rma"];
                };
            };
        };
    };
    label: {
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
                    "*/*": components["schemas"]["Rma"];
                };
            };
        };
    };
    pickup: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["Pickup"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Rma"];
                };
            };
        };
    };
    receive: {
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
                    "*/*": components["schemas"]["Rma"];
                };
            };
        };
    };
    refundQuote: {
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
                    "*/*": components["schemas"]["RefundQuote"];
                };
            };
        };
    };
    reject: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Decision"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Rma"];
                };
            };
        };
    };
    liability: {
        parameters: {
            query: {
                month: string;
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
                    "*/*": components["schemas"]["LiabilityReport"];
                };
            };
        };
    };
    liabilityCsv: {
        parameters: {
            query: {
                month: string;
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
                    "text/csv": string;
                };
            };
        };
    };
    transactions: {
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
                    "*/*": components["schemas"]["TaxTransaction"][];
                };
            };
        };
    };
    quote: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxQuoteRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxQuote"];
                };
            };
        };
    };
    rates: {
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
                    "*/*": components["schemas"]["TaxRateTable"];
                };
            };
        };
    };
    putLocal: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                state: string;
                zipPrefix: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LocalTaxRate"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LocalTaxRate"];
                };
            };
        };
    };
    deleteLocal: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                state: string;
                zipPrefix: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    importRates: {
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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxRatesImportResult"];
                };
            };
        };
    };
    putState: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                state: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StateTaxRule"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["StateTaxRule"];
                };
            };
        };
    };
    taxSettings: {
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
                    "*/*": components["schemas"]["TaxSettings"];
                };
            };
        };
    };
    changeTaxMethod: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxMethodChangeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxSettings"];
                };
            };
        };
    };
    taxReviewSettings: {
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
                    "application/json": components["schemas"]["TaxReviewSettings"];
                };
            };
        };
    };
    changeTaxReviewSettings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxReviewSettingsChangeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaxReviewSettings"];
                };
            };
            /** @description Needs tax:classify-admin */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description tax.reason-required, tax.unknown-category or tax.invalid-audit-percent */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    taxObligations: {
        parameters: {
            query?: {
                through?: string;
                months?: number;
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
                    "*/*": components["schemas"]["ObligationsResponse"];
                };
            };
        };
    };
    recordTaxRemittance: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RecordRemittanceRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxRemittance"];
                };
            };
        };
    };
    taxObligation: {
        parameters: {
            query?: {
                currency?: string;
            };
            header?: never;
            path: {
                month: string;
                jurisdiction: string;
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
                    "*/*": components["schemas"]["TaxFilingObligation"];
                };
            };
        };
    };
    taxRemittanceHistory: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                month: string;
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
                    "*/*": components["schemas"]["TaxRemittance"][];
                };
            };
        };
    };
    voidTaxRemittance: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["VoidRemittanceRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxRemittance"];
                };
            };
        };
    };
    taxNexusTracker: {
        parameters: {
            query?: {
                through?: string;
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
                    "*/*": components["schemas"]["NexusResponse"];
                };
            };
        };
    };
    taxNexusState: {
        parameters: {
            query?: {
                through?: string;
            };
            header?: never;
            path: {
                jurisdiction: string;
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
                    "*/*": components["schemas"]["TaxNexusState"];
                };
            };
        };
    };
    updateTaxNexus: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                jurisdiction: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateNexusRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxNexusState"];
                };
            };
        };
    };
    reverse: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxReversal"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxReversalResult"];
                };
            };
        };
    };
    sellerStatement: {
        parameters: {
            query: {
                month: string;
            };
            header?: never;
            path: {
                orgId: string;
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
                    "*/*": components["schemas"]["LiabilityReport"];
                };
            };
        };
    };
    sellerStatementCsv: {
        parameters: {
            query: {
                month: string;
            };
            header?: never;
            path: {
                orgId: string;
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
                    "text/csv": string;
                };
            };
        };
    };
    resolveClassification: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ClassificationResolveRequest"];
            };
        };
        responses: {
            /** @description The resolution (also 200 for MISSING_FACTS, BLOCKED and the other gates) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ClassificationResolveResponse"];
                };
            };
            /** @description Unknown category or product type (tax.profile-not-found) */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listTaxProfiles: {
        parameters: {
            query?: {
                department?: string;
                status?: components["schemas"]["ProfileStatus"];
                q?: string;
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
                    "application/json": components["schemas"]["TaxProfileSummary"][];
                };
            };
        };
    };
    getTaxProfile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                categoryCode: string;
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
                    "application/json": components["schemas"]["TaxProfile"];
                };
            };
            /** @description tax.profile-not-found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    changeTaxProfileStatus: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                categoryCode: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxProfileStatusChange"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TaxProfile"];
                };
            };
            /** @description tax.profile-conflict-unresolved */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listProviderCodeMap: {
        parameters: {
            query?: {
                provider?: components["schemas"]["TaxProvider"];
                categoryCode?: string;
                unmappedOnly?: boolean;
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
                    "application/json": components["schemas"]["ProviderCodeMapEntry"][];
                };
            };
        };
    };
    updateProviderCode: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                internalTaxCode: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ProviderCodeMapUpdate"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProviderCodeMapEntry"];
                };
            };
        };
    };
    listVariantClassifications: {
        parameters: {
            query?: {
                /** @description Only the audit sample, oldest first */
                auditSample?: boolean;
                status?: components["schemas"]["ClassificationRecordStatus"];
                categoryCode?: string;
                sellerOrgId?: string;
                /** @description ATIN */
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
                    "application/json": components["schemas"]["VariantClassificationPage"];
                };
            };
        };
    };
    getVariantClassification: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                atin: string;
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
                    "application/json": components["schemas"]["VariantClassification"];
                };
            };
            /** @description tax.classification-not-found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    approveVariantClassification: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                atin: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ClassificationApproveRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VariantClassification"];
                };
            };
            /** @description tax.classification-not-resolvable */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    requestVariantInformation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                atin: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    message: string;
                    reason?: string;
                };
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VariantClassification"];
                };
            };
            /** @description tax.classification-not-found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description tax.invalid-information-request */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    reclassifyVariant: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                atin: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ClassificationReclassifyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["VariantClassification"];
                };
            };
        };
    };
    shadowReport: {
        parameters: {
            query: {
                from: string;
                to: string;
                provider?: components["schemas"]["TaxProvider"];
                onlyDifferences?: boolean;
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
                    "application/json": components["schemas"]["ShadowReport"];
                };
            };
        };
    };
}
