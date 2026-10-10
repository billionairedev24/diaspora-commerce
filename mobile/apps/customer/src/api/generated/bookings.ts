// GENERATED from contracts/openapi/bookings.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/customer-app generate
/* eslint-disable */

export interface paths {
    "/v1/bookings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Book a slot (customer)
         * @description Holds the slot (a database exclusion constraint makes double booking impossible) and creates the payments-service PaymentIntent for the deposit (manual capture: deposit held now, charged on confirmation, balance on completion). Returns the client secret for Stripe.js / PaymentSheet. 409 bookings.slot-taken when the time was just taken.
         */
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/mine": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Your bookings (customer), active first */
        get: operations["mine"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A booking (its customer, or the provider with bookings:read) */
        get: operations["get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/balance-payment": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The balance and its off-session charge (long-dated bookings)
         * @description When the customer's bank asks for authentication (state ACTION_REQUIRED) the customer gets the charge's clientSecret to confirm with Stripe.js / PaymentSheet, then calls POST /payment/sync. Providers see the state without the secret.
         */
        get: operations["balancePayment"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Cancel (customer: policy refund; provider: full refund, fee + strike under 24 h)
         * @description After the deposit capture the uncaptured balance hold is released (or the open off-session balance charge cancelled).
         */
        post: operations["cancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/cancellation-quote": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * What cancelling now refunds, keeps or releases
         * @description Customers see the exact refund under the policy the booking was made with before they cancel; providers see the late-cancellation fee.
         */
        get: operations["quote"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Mark complete (provider): the balance is charged and the settlement hold starts
         * @description The balance is captured on the original authorisation, or on the off-session charge of a long-dated booking; a balance that could not be collected flags the booking (balance.state NEEDS_ATTENTION, event booking.balance_unpaid).
         */
        post: operations["complete"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Confirm a request (provider): the deposit is captured */
        post: operations["confirm"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/decline": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Decline a request with a reason (provider): the deposit hold is released */
        post: operations["decline"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/history": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** History of a booking (who did what, and the money it moved) */
        get: operations["history"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/new-time/accept": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Accept the pending new time
         * @description Customer accepts the provider's proposal, or the provider accepts the customer's request.
         */
        post: operations["acceptNewTime"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/new-time/decline": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Decline the pending new time
         * @description Provider keeps the original time; a customer declining the provider's proposal cancels with a full refund.
         */
        post: operations["declineNewTime"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/no-show": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Mark no-show (provider, after the start time): deposit kept, or full price per policy
         * @description Without full price the uncaptured balance hold is released.
         */
        post: operations["noShow"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/payment/sync": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Refresh the deposit authorisation (and an off-session balance charge) from payments
         * @description Call after Stripe.js confirmed the card, or after the customer authenticated the off-session balance charge; payments' intent events do the same asynchronously.
         */
        post: operations["syncPayment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/propose-time": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Propose a new time (provider)
         * @description Must be inside the provider's availability; the customer accepts or declines.
         */
        post: operations["proposeTime"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/reschedule": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Customer moves the appointment
         * @description Allowed inside the policy's free-change window and reschedule limit; moves straight away when the provider auto-accepts, otherwise waits for the provider (RESCHEDULE_REQUESTED).
         */
        post: operations["reschedule"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/bookings/{id}/review": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Rate a completed booking (customer, once): 1–5 stars and an optional text
         * @description Ratings are averaged per service in search. 409 bookings.not-reviewable before completion, 409 bookings.already-reviewed the second time.
         */
        post: operations["review"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/bookings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Bookings starting in [from, to) (bookings:read; practitioners: own) */
        get: operations["bookings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/calendar": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Week / month calendar (bookings:read; practitioners: own column)
         * @description Open hours and time off per member and day, what's on each day (appointments, hours booked, expected revenue, requests waiting) and the bookings for the quick view.
         */
        get: operations["calendar"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/cancellation-policy": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Set the cancellation policy: FLEXIBLE, MODERATE, STRICT or CUSTOM (bookings:services:write)
         * @description Existing bookings keep the policy they were made under.
         */
        put: operations["savePolicy"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/earnings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Earnings: collected − commission − card fees − late-cancellation fees (bookings:read)
         * @description Commission is taken only on what the customer actually paid (after refunds).
         */
        get: operations["earnings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/services": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Services & prices with the provider's take-home (bookings:read) */
        get: operations["services"];
        put?: never;
        /** Add a service (bookings:services:write) */
        post: operations["createService"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/services/{serviceId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A service with its take-home (bookings:read) */
        get: operations["service"];
        /**
         * Change a service (bookings:services:write)
         * @description Price changes don't affect existing bookings.
         */
        put: operations["updateService"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/services/{serviceId}/activate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Offer a service again (bookings:services:write) */
        post: operations["activate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/services/{serviceId}/deactivate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Stop offering a service (bookings:services:write) */
        post: operations["deactivate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/services/{serviceId}/members": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Who performs a service (bookings:team:manage); empty = every bookable member */
        put: operations["assign"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/settings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Booking setup (bookings:read) */
        get: operations["settings"];
        /**
         * Create or update the setup: name, time zone, rules (bookings:availability:write; not practitioners)
         * @description Minimum notice, booking horizon, buffer, slot interval, daily limit, instant booking and auto-accepting reschedules. Customers see the change immediately.
         */
        put: operations["saveSettings"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/team": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Team members with their roles and hours (bookings:read) */
        get: operations["team"];
        put?: never;
        /** Add a team member: Owner, Manager, Practitioner or Finance (bookings:team:manage) */
        post: operations["addMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/team/{memberId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Change a member's role, login link, bookability or status (bookings:team:manage) */
        put: operations["updateMember"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/team/{memberId}/hours": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Set weekly working hours with breaks (bookings:availability:write; practitioners: own) */
        put: operations["setHours"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/team/{memberId}/overrides": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Time off and special hours in a date range (bookings:read; practitioners: own) */
        get: operations["overrides"];
        /** Block a date (time off) or set special hours (bookings:availability:write; practitioners: own) */
        put: operations["putOverride"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/providers/{orgId}/team/{memberId}/overrides/{date}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Remove time off / special hours of a date (bookings:availability:write) */
        delete: operations["removeOverride"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/public/providers/{orgId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A provider's booking page: services, policy and bookable team */
        get: operations["provider"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/public/services/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Search services (name, description, category, provider) with the next free time
         * @description zip (5 digits) keeps providers whose service area (geo-service SERVICE_AREA zones) covers the ZIP, plus virtual services; an unknown ZIP finds nothing. rating / ratingCount come from customers' reviews of completed bookings. 422 bookings.invalid-zip, 502 bookings.geo-unavailable.
         */
        get: operations["search"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/public/services/{serviceId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A service: duration, price, deposit, modes, add-ons, policy and who performs it */
        get: operations["service_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/public/services/{serviceId}/days": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Days with free times (service → day) */
        get: operations["days"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/public/services/{serviceId}/slots": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Free start times on a date (day → time); only bookable slots are offered */
        get: operations["slots"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/public/services/{serviceId}/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Booking summary: deposit now, balance later and the provider's policy */
        get: operations["summary"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/services/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Search services (alias used by ai-gateway and mcp-server)
         * @deprecated
         */
        get: operations["searchAlias"];
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
        TeamMemberRequest: {
            /** @description Identity user id of the member's login */
            userId?: string;
            displayName: string;
            /** @enum {string} */
            role: "OWNER" | "MANAGER" | "PRACTITIONER" | "FINANCE";
            /** @description Takes appointments; default: owners and practitioners */
            bookable?: boolean;
            active?: boolean;
            hours?: components["schemas"]["WeeklyHours"];
        };
        /** @description Local wall-clock range, end exclusive */
        TimeRange: {
            /** @example 600 */
            start: string;
            /** @example 1080 */
            end: string;
        };
        /** @description Ranges per weekday (MONDAY…SUNDAY); several ranges = breaks; missing day = closed */
        WeeklyHours: {
            days: {
                [key: string]: components["schemas"]["TimeRange"][];
            };
        };
        TeamMember: {
            /** Format: uuid */
            id?: string;
            orgId?: string;
            userId?: string;
            displayName?: string;
            role?: string;
            bookable?: boolean;
            active?: boolean;
            hours?: components["schemas"]["WeeklyHours"];
            /** Format: int64 */
            version?: number;
        };
        /** @description Time off (closed) or special hours on one date */
        DayOverride: {
            /** Format: date */
            date: string;
            closed?: boolean;
            ranges?: components["schemas"]["TimeRange"][];
            note?: string;
        };
        /** @description Working-hours screen rules */
        BookingRules: {
            /**
             * Format: int32
             * @description Minimum notice in minutes
             * @example 720
             */
            leadMinutes?: number;
            /**
             * Format: int32
             * @description Book up to N days ahead
             * @example 30
             */
            horizonDays?: number;
            /**
             * Format: int32
             * @description Buffer after each booking
             * @example 15
             */
            bufferMinutes?: number;
            /**
             * Format: int32
             * @description Time slot interval
             * @example 30
             */
            stepMinutes?: number;
            /**
             * Format: int32
             * @description Max bookings per team member per day
             * @example 6
             */
            maxPerDay?: number;
            instantBooking?: boolean;
            autoAcceptReschedule?: boolean;
        };
        ProviderSettingsRequest: {
            displayName: string;
            /** @example America/Chicago */
            timeZone: string;
            /** @example USD */
            currency?: string;
            location?: string;
            rules?: components["schemas"]["BookingRules"];
        };
        CancellationPolicy: {
            kind?: string;
            label?: string;
            text?: string;
            /** Format: int32 */
            freeHours?: number;
            tiers?: components["schemas"]["RefundTier"][];
            noShow?: string;
            /** Format: int32 */
            rescheduleHours?: number;
            /** Format: int32 */
            maxReschedules?: number;
        };
        Provider: {
            orgId?: string;
            displayName?: string;
            timeZone?: string;
            currency?: string;
            location?: string;
            rules?: components["schemas"]["BookingRules"];
            policy?: components["schemas"]["CancellationPolicy"];
            /** Format: int32 */
            strikes?: number;
            /** Format: date-time */
            updatedAt?: string;
            /** Format: int64 */
            version?: number;
        };
        RefundTier: {
            /** Format: int32 */
            hoursBefore?: number;
            /** Format: int32 */
            shareBps?: number;
        };
        AddOn: {
            id: string;
            name: string;
            /** Format: int64 */
            priceMinor?: number;
            /** Format: int32 */
            extraMinutes?: number;
        };
        /** @description Services & prices form */
        ServiceRequest: {
            name: string;
            description?: string;
            /** @example hair & beauty */
            category?: string;
            /** Format: int32 */
            durationMinutes?: number;
            /** Format: int64 */
            priceMinor?: number;
            /** Format: int64 */
            depositMinor?: number;
            modes: ("IN_PERSON" | "AT_HOME" | "VIRTUAL")[];
            addOns?: components["schemas"]["AddOn"][];
        };
        Service: {
            /** Format: uuid */
            id?: string;
            orgId?: string;
            name?: string;
            description?: string;
            category?: string;
            /** Format: int32 */
            durationMinutes?: number;
            /** Format: int64 */
            priceMinor?: number;
            /** Format: int64 */
            depositMinor?: number;
            /** Format: int64 */
            balanceMinor?: number;
            currency?: string;
            modes?: ("IN_PERSON" | "AT_HOME" | "VIRTUAL")[];
            addOns?: components["schemas"]["AddOn"][];
            memberIds?: string[];
            active?: boolean;
            takeHome?: components["schemas"]["TakeHome"];
            /** Format: date-time */
            updatedAt?: string;
            /** Format: int64 */
            version?: number;
        };
        /** @description What the provider receives at the listed price */
        TakeHome: {
            /** Format: int64 */
            priceMinor?: number;
            /** Format: int64 */
            depositMinor?: number;
            /** Format: int64 */
            balanceMinor?: number;
            /** Format: int32 */
            commissionBps?: number;
            /** Format: int64 */
            commissionMinor?: number;
            /** Format: int64 */
            cardFeesMinor?: number;
            /** Format: int64 */
            takeHomeMinor?: number;
            currency?: string;
            /** @description Where the commission comes from: payments' OVERRIDE, CATEGORY, DEFAULT, RETAIL, or CONFIG (configured fallback while payments is unavailable) */
            feeSource?: string;
            /**
             * Format: int32
             * @description payments' fee-schedule version (null for the fallback)
             */
            feeVersion?: number;
        };
        AssignMembersRequest: {
            memberIds: string[];
        };
        CancellationPolicyRequest: {
            /** @enum {string} */
            kind: "FLEXIBLE" | "MODERATE" | "STRICT" | "CUSTOM";
            custom?: components["schemas"]["CustomPolicy"];
        };
        CustomPolicy: {
            /** Format: int32 */
            freeHours?: number;
            partialRefund?: boolean;
            /** Format: int32 */
            partialFromHours?: number;
            /** Format: int32 */
            partialPercent?: number;
            /** @enum {string} */
            noShow: "DEPOSIT" | "FULL";
            /** Format: int32 */
            rescheduleHours?: number;
            /** Format: int32 */
            maxReschedules?: number;
        };
        /** @description Service → day → time → summary, then book */
        CreateBookingRequest: {
            /** Format: uuid */
            serviceId: string;
            /**
             * Format: uuid
             * @description Preferred team member; empty = anyone free
             */
            memberId?: string;
            /**
             * Format: date-time
             * @description A start time from the slots endpoint
             */
            startAt: string;
            /** @enum {string} */
            mode: "IN_PERSON" | "AT_HOME" | "VIRTUAL";
            addOnIds?: string[];
            customer: components["schemas"]["CustomerContact"];
            /** @description Required for home visits */
            location?: string;
            notes?: string;
            channel?: string;
        };
        CustomerContact: {
            name: string;
            /** Format: email */
            email?: string;
            /** @description E.164 preferred */
            phone?: string;
        };
        /** @description The service as booked (later price changes don't apply) */
        BookedService: {
            /** Format: uuid */
            serviceId?: string;
            name?: string;
            /** Format: int32 */
            durationMinutes?: number;
            /** Format: int64 */
            priceMinor?: number;
            /** Format: int64 */
            depositMinor?: number;
            /** Format: int64 */
            balanceMinor?: number;
            currency?: string;
            addOns?: string[];
            mode?: string;
            providerName?: string;
            memberName?: string;
        };
        Booking: {
            /** Format: uuid */
            id?: string;
            reference?: string;
            orgId?: string;
            /** Format: uuid */
            memberId?: string;
            customerId?: string;
            customer?: components["schemas"]["CustomerContact"];
            service?: components["schemas"]["BookedService"];
            status?: string;
            statusLabel?: string;
            /** Format: date-time */
            startAt?: string;
            /** Format: date-time */
            endAt?: string;
            /** Format: date-time */
            proposedStartAt?: string;
            location?: string;
            notes?: string;
            channel?: string;
            policy?: components["schemas"]["BookingPolicy"];
            /** Format: int32 */
            reschedules?: number;
            payment?: components["schemas"]["BookingPayment"];
            /** Format: date-time */
            holdExpiresAt?: string;
            /** Format: date-time */
            requestExpiresAt?: string;
            reason?: string;
            cancelledBy?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            updatedAt?: string;
            /** Format: int64 */
            version?: number;
        };
        /** @description The balance (price − deposit). Long-dated bookings: the balance hold is released after the deposit capture and the balance is charged off-session on the saved card before the appointment. The client secret for authentication is served by GET /v1/bookings/{id}/balance-payment to the customer only. */
        BookingBalance: {
            /** @description ON_AUTHORISATION, DUE, PROCESSING, ACTION_REQUIRED (the customer must authenticate), FAILED (retried), AUTHORIZED, CAPTURED, NEEDS_ATTENTION (not collected automatically), RELEASED */
            state?: string;
            /**
             * Format: uuid
             * @description payments' id of the off-session charge
             */
            paymentId?: string;
            /** Format: int32 */
            attempts?: number;
            failureMessage?: string;
            /** Format: date-time */
            nextAttemptAt?: string;
            needsCustomerAction?: boolean;
            needsAttention?: boolean;
        };
        BookingPayment: {
            /** Format: uuid */
            paymentId?: string;
            state?: string;
            /** @description Only in the create response, for Stripe.js / PaymentSheet confirmation */
            clientSecret?: string;
            failureMessage?: string;
            /** Format: int64 */
            authorizedMinor?: number;
            /** Format: int64 */
            depositCapturedMinor?: number;
            /** Format: int64 */
            balanceCapturedMinor?: number;
            /** Format: int64 */
            refundedMinor?: number;
            /** Format: int64 */
            releasedMinor?: number;
            /** Format: int64 */
            providerFeeMinor?: number;
            balance?: components["schemas"]["BookingBalance"];
        };
        /** @description The cancellation policy this booking was made under */
        BookingPolicy: {
            kind?: string;
            label?: string;
            text?: string;
        };
        CreateBookingResponse: {
            booking?: components["schemas"]["Booking"];
            replayed?: boolean;
        };
        /** @description Rate a completed booking once */
        ReviewRequest: {
            /** Format: int32 */
            rating?: number;
            text?: string;
        };
        Review: {
            /** Format: uuid */
            bookingId?: string;
            /** Format: uuid */
            serviceId?: string;
            orgId?: string;
            /** Format: int32 */
            rating?: number;
            text?: string;
            /** Format: date-time */
            createdAt?: string;
        };
        NewTimeRequest: {
            /** Format: date-time */
            startAt: string;
        };
        /** @description Optional reason (cancel, decline) */
        ReasonRequest: {
            reason?: string;
        };
        CancelResponse: {
            booking?: components["schemas"]["Booking"];
            applied?: components["schemas"]["CancellationQuote"];
        };
        /** @description Exactly what cancelling now refunds, keeps or releases */
        CancellationQuote: {
            by?: string;
            /** Format: int32 */
            refundSharePercent?: number;
            /** Format: int64 */
            depositMinor?: number;
            /** Format: int64 */
            refundMinor?: number;
            releaseAuthorisation?: boolean;
            /** Format: int64 */
            releasedMinor?: number;
            /** Format: int64 */
            keptMinor?: number;
            /** Format: int64 */
            providerFeeMinor?: number;
            policy?: string;
            explanation?: string;
            currency?: string;
        };
        PublicServiceListing: {
            /** Format: uuid */
            serviceId?: string;
            orgId?: string;
            name?: string;
            providerName?: string;
            description?: string;
            category?: string;
            /** Format: int32 */
            durationMinutes?: number;
            /** Format: int64 */
            priceFromMinor?: number;
            /** Format: int64 */
            depositMinor?: number;
            currency?: string;
            modes?: ("IN_PERSON" | "AT_HOME" | "VIRTUAL")[];
            /**
             * Format: double
             * @description Average customer rating (1–5, one decimal); 0 without reviews
             */
            rating?: number;
            /**
             * Format: int32
             * @description Number of reviews
             */
            ratingCount?: number;
            nextAvailable?: string;
            location?: string;
        };
        PublicServiceSearch: {
            services?: components["schemas"]["PublicServiceListing"][];
        };
        PublicPolicy: {
            kind?: string;
            label?: string;
            text?: string;
        };
        PublicService: {
            /** Format: uuid */
            id?: string;
            orgId?: string;
            providerName?: string;
            name?: string;
            description?: string;
            category?: string;
            /** Format: int32 */
            durationMinutes?: number;
            /** Format: int64 */
            priceMinor?: number;
            /** Format: int64 */
            depositMinor?: number;
            /** Format: int64 */
            balanceMinor?: number;
            currency?: string;
            modes?: ("IN_PERSON" | "AT_HOME" | "VIRTUAL")[];
            addOns?: components["schemas"]["AddOn"][];
            policy?: components["schemas"]["PublicPolicy"];
            instantBooking?: boolean;
            team?: components["schemas"]["PublicTeamMember"][];
        };
        PublicTeamMember: {
            /** Format: uuid */
            id?: string;
            displayName?: string;
        };
        /** @description Deposit due now, balance after the appointment, the policy */
        BookingSummary: {
            /** Format: uuid */
            serviceId?: string;
            serviceName?: string;
            providerName?: string;
            /** Format: int32 */
            durationMinutes?: number;
            /** Format: int64 */
            priceMinor?: number;
            /** Format: int64 */
            depositNowMinor?: number;
            /** Format: int64 */
            balanceLaterMinor?: number;
            currency?: string;
            mode?: string;
            addOns?: string[];
            policy?: string;
            policyText?: string;
            instantBooking?: boolean;
            /** Format: int64 */
            providerAnswersWithinMinutes?: number;
        };
        AvailableSlot: {
            /** Format: date-time */
            start?: string;
            /** Format: date-time */
            end?: string;
            memberIds?: string[];
        };
        AvailableDay: {
            /** Format: date */
            date?: string;
            /** Format: int32 */
            slots?: number;
        };
        PublicProvider: {
            orgId?: string;
            displayName?: string;
            timeZone?: string;
            location?: string;
            policy?: components["schemas"]["PublicPolicy"];
            instantBooking?: boolean;
            services?: components["schemas"]["PublicService"][];
            team?: components["schemas"]["PublicTeamMember"][];
        };
        BookingEarnings: {
            /** Format: uuid */
            bookingId?: string;
            reference?: string;
            customer?: string;
            /** Format: date-time */
            startAt?: string;
            serviceName?: string;
            status?: string;
            /** Format: int64 */
            heldMinor?: number;
            /** Format: int64 */
            collectedMinor?: number;
            /** Format: int32 */
            commissionBps?: number;
            /** Format: int64 */
            commissionMinor?: number;
            /** Format: int64 */
            cardFeesMinor?: number;
            /** Format: int64 */
            providerFeeMinor?: number;
            /** Format: int64 */
            netMinor?: number;
            /** Format: int64 */
            balanceDueMinor?: number;
        };
        Earnings: {
            orgId?: string;
            currency?: string;
            rows?: components["schemas"]["BookingEarnings"][];
            /** Format: int64 */
            collectedMinor?: number;
            /** Format: int64 */
            commissionMinor?: number;
            /** Format: int64 */
            cardFeesMinor?: number;
            /** Format: int64 */
            providerFeesMinor?: number;
            /** Format: int64 */
            netMinor?: number;
            /** Format: int64 */
            depositsOnHoldMinor?: number;
        };
        Calendar: {
            orgId?: string;
            timeZone?: string;
            /** Format: date */
            from?: string;
            /** Format: date */
            to?: string;
            days?: components["schemas"]["CalendarDay"][];
            bookings?: components["schemas"]["Booking"][];
        };
        CalendarDay: {
            /** Format: uuid */
            memberId?: string;
            memberName?: string;
            /** Format: date */
            date?: string;
            open?: components["schemas"]["TimeRange"][];
            timeOff?: boolean;
            note?: string;
            /** Format: int32 */
            appointments?: number;
            /** Format: int64 */
            bookedMinutes?: number;
            /** Format: int64 */
            expectedRevenueMinor?: number;
            /** Format: int32 */
            awaitingAction?: number;
        };
        BookingActivity: {
            /** Format: date-time */
            at?: string;
            by?: string;
            action?: string;
            moneyKind?: string;
            /** Format: int64 */
            amountMinor?: number;
        };
        /** @description The off-session balance charge; clientSecret only for the customer while their bank asks them to authenticate (confirm with Stripe.js / PaymentSheet, then POST /v1/bookings/{id}/payment/sync) */
        BookingBalancePayment: {
            /** Format: uuid */
            bookingId?: string;
            reference?: string;
            state?: string;
            /** Format: uuid */
            paymentId?: string;
            /** Format: int64 */
            amountMinor?: number;
            currency?: string;
            clientSecret?: string;
            failureMessage?: string;
            /** Format: int32 */
            attempts?: number;
            /** Format: date-time */
            nextAttemptAt?: string;
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
                "application/json": components["schemas"]["CreateBookingRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CreateBookingResponse"];
                };
            };
        };
    };
    mine: {
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
                    "*/*": components["schemas"]["Booking"][];
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
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    balancePayment: {
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
                    "*/*": components["schemas"]["BookingBalancePayment"];
                };
            };
        };
    };
    cancel: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["ReasonRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CancelResponse"];
                };
            };
        };
    };
    quote: {
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
                    "*/*": components["schemas"]["CancellationQuote"];
                };
            };
        };
    };
    complete: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
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
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    confirm: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
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
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    decline: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["ReasonRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    history: {
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
                    "*/*": components["schemas"]["BookingActivity"][];
                };
            };
        };
    };
    acceptNewTime: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
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
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    declineNewTime: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
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
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    noShow: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
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
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    syncPayment: {
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
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    proposeTime: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NewTimeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    reschedule: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NewTimeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Booking"];
                };
            };
        };
    };
    review: {
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
                "application/json": components["schemas"]["ReviewRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Review"];
                };
            };
        };
    };
    bookings: {
        parameters: {
            query: {
                from: string;
                to: string;
                memberId?: string;
                status?: "PENDING_PAYMENT" | "REQUESTED" | "CONFIRMED" | "RESCHEDULE_REQUESTED" | "RESCHEDULE_PROPOSED" | "COMPLETED" | "NO_SHOW" | "DECLINED" | "EXPIRED" | "CANCELLED_CUSTOMER" | "CANCELLED_PROVIDER";
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
                    "*/*": components["schemas"]["Booking"][];
                };
            };
        };
    };
    calendar: {
        parameters: {
            query: {
                from: string;
                to: string;
                memberId?: string;
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
                    "*/*": components["schemas"]["Calendar"];
                };
            };
        };
    };
    savePolicy: {
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
                "application/json": components["schemas"]["CancellationPolicyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Provider"];
                };
            };
        };
    };
    earnings: {
        parameters: {
            query: {
                from: string;
                to: string;
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
                    "*/*": components["schemas"]["Earnings"];
                };
            };
        };
    };
    services: {
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
                    "*/*": components["schemas"]["Service"][];
                };
            };
        };
    };
    createService: {
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
                "application/json": components["schemas"]["ServiceRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Service"];
                };
            };
        };
    };
    service: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                serviceId: string;
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
                    "*/*": components["schemas"]["Service"];
                };
            };
        };
    };
    updateService: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                serviceId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ServiceRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Service"];
                };
            };
        };
    };
    activate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                serviceId: string;
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
                    "*/*": components["schemas"]["Service"];
                };
            };
        };
    };
    deactivate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                serviceId: string;
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
                    "*/*": components["schemas"]["Service"];
                };
            };
        };
    };
    assign: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                serviceId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AssignMembersRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Service"];
                };
            };
        };
    };
    settings: {
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
                    "*/*": components["schemas"]["Provider"];
                };
            };
        };
    };
    saveSettings: {
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
                "application/json": components["schemas"]["ProviderSettingsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Provider"];
                };
            };
        };
    };
    team: {
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
                    "*/*": components["schemas"]["TeamMember"][];
                };
            };
        };
    };
    addMember: {
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
                "application/json": components["schemas"]["TeamMemberRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TeamMember"];
                };
            };
        };
    };
    updateMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                memberId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TeamMemberRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TeamMember"];
                };
            };
        };
    };
    setHours: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                memberId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["WeeklyHours"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TeamMember"];
                };
            };
        };
    };
    overrides: {
        parameters: {
            query: {
                from: string;
                to: string;
            };
            header?: never;
            path: {
                orgId: string;
                memberId: string;
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
                    "*/*": components["schemas"]["DayOverride"][];
                };
            };
        };
    };
    putOverride: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                memberId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DayOverride"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DayOverride"];
                };
            };
        };
    };
    removeOverride: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                memberId: string;
                date: string;
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
                content?: never;
            };
        };
    };
    provider: {
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
                    "*/*": components["schemas"]["PublicProvider"];
                };
            };
        };
    };
    search: {
        parameters: {
            query?: {
                q?: string;
                category?: string;
                zip?: string;
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
                    "*/*": components["schemas"]["PublicServiceSearch"];
                };
            };
        };
    };
    service_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                serviceId: string;
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
                    "*/*": components["schemas"]["PublicService"];
                };
            };
        };
    };
    days: {
        parameters: {
            query?: {
                from?: string;
                days?: number;
                memberId?: string;
                addOnIds?: string[];
            };
            header?: never;
            path: {
                serviceId: string;
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
                    "*/*": components["schemas"]["AvailableDay"][];
                };
            };
        };
    };
    slots: {
        parameters: {
            query: {
                date: string;
                memberId?: string;
                addOnIds?: string[];
            };
            header?: never;
            path: {
                serviceId: string;
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
                    "*/*": components["schemas"]["AvailableSlot"][];
                };
            };
        };
    };
    summary: {
        parameters: {
            query?: {
                addOnIds?: string[];
                mode?: "IN_PERSON" | "AT_HOME" | "VIRTUAL";
            };
            header?: never;
            path: {
                serviceId: string;
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
                    "*/*": components["schemas"]["BookingSummary"];
                };
            };
        };
    };
    searchAlias: {
        parameters: {
            query?: {
                q?: string;
                category?: string;
                zip?: string;
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
                    "*/*": components["schemas"]["PublicServiceSearch"];
                };
            };
        };
    };
}
