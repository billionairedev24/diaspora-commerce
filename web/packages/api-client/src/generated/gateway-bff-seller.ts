// GENERATED from contracts/openapi/gateway-bff-seller.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/api-client generate
/* eslint-disable */

export interface paths {
    "/v1/seller/session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Signed-in member, organisations, active organisation and role-aware navigation
         * @description Composes identity `GET /v1/me` (profile, organisations with the member's role, active org), seller
         *     `GET /v1/orgs/{id}` (approval status) and identity's module matrix for the portal (falls back to the
         *     platform default matrix when the member may not read it).
         */
        get: operations["getSellerSession"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/session/active-org": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Switch the organisation to act for (org switcher) */
        put: operations["setActiveOrg"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Onboarding application status (the only screen before approval)
         * @description seller `GET /v1/applications/me`.
         */
        get: operations["getOnboarding"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/compliance": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Stripe compliance status for the banner shown on every page while something is outstanding
         * @description seller `GET /v1/orgs/{orgId}/account-health`.
         */
        get: operations["getCompliance"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/dashboard": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Home KPIs and charts (seller or provider, by the active organisation's kind)
         * @description Seller: sales and units (orders), top products (orders), orders by fulfilment (orders), stock by warehouse
         *     (inventory positions). Provider: bookings and deposits, bookings by service, status donut and earnings by
         *     week (bookings). Each chart reports `available: false` when its upstream cannot answer, so the rest of the
         *     page still renders. Exports (PDF, Excel with a native chart, CSV) are produced from these rows.
         */
        get: operations["getDashboard"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Shipment queue of the active organisation (server-driven table)
         * @description orders `GET /v1/seller/shipments` (newest first, cursor pagination, status filter).
         */
        get: operations["listSellerOrders"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/orders/{orderNumber}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Order detail with the organisation's shipments (opened in a sheet)
         * @description orders `GET /v1/orders/{number}` (sellers see only their own shipments).
         */
        get: operations["getSellerOrder"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/orders/{orderNumber}/shipments/{shipmentId}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Confirm, pack or mark ready (the only statuses a seller may set)
         * @description orders `POST /v1/shipments/{id}/transitions`. Anything but CONFIRMED, PACKED or READY is refused with 422
         *     before calling orders; orders answers 409 `CONFLICT` for a transition that is not allowed from the current
         *     status and 403 for a step that belongs to AfroTransact (AfroTransact-fulfilled shipments).
         */
        post: operations["updateShipmentStatus"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/orders/{orderNumber}/shipments/{shipmentId}/rates": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Carrier rates for a seller-fulfilled shipment (cheapest first)
         * @description orders `GET /v1/orders/{number}` (ship-to) + carrier-gateway `POST /v1/rates`.
         */
        post: operations["quoteShippingRates"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/orders/{orderNumber}/shipments/{shipmentId}/labels": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Buy a label for a quoted rate and attach its tracking number to the shipment
         * @description carrier-gateway `POST /v1/labels` (Idempotency-Key relayed; the same key returns the same label) then orders
         *     `POST /v1/shipments/{id}/tracking`.
         */
        post: operations["buyShippingLabel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/ship-from-addresses": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The organisation's ship-from addresses, default first (label form)
         * @description seller `GET /v1/orgs/{orgId}/ship-from-addresses` for the active organisation (Orders view).
         */
        get: operations["listShipFromAddresses"];
        put?: never;
        /**
         * Add a ship-from address (the first one becomes the default)
         * @description seller `POST /v1/orgs/{orgId}/ship-from-addresses` (Orders edit; at most 20 per organisation).
         */
        post: operations["createShipFromAddress"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/ship-from-addresses/{addressId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                addressId: string;
            };
            cookie?: never;
        };
        get?: never;
        /**
         * Change a ship-from address
         * @description Optimistic — send the `version` you edited; 409 `CONFLICT` when someone changed it meanwhile.
         */
        put: operations["updateShipFromAddress"];
        post?: never;
        /** Delete a ship-from address; returns the rest (a new default is chosen when needed) */
        delete: operations["deleteShipFromAddress"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/ship-from-addresses/{addressId}/default": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Make an address the default */
        post: operations["defaultShipFromAddress"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/listings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The organisation's listings (server-driven table)
         * @description catalog `GET /v1/products` (own organisation; status, q, page, size).
         */
        get: operations["listListings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/listings/{productId}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Pause or resume a live listing
         * @description catalog `POST /v1/products/{id}/pause` or `/resume`.
         */
        post: operations["setListingActive"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/products": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Submit the product builder (step 4) for review, or save it pending category approval
         * @description catalog `POST /v1/products`. With `categoryId` the product goes to listing review (IN_REVIEW); with
         *     `categoryRequestId` it waits for the requested category (PENDING_CATEGORY_APPROVAL) and moves to review when
         *     the catalog team approves the category.
         */
        post: operations["submitProduct"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/catalog/categories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Category tree (department › category › product type) for step 1
         * @description catalog `GET /v1/categories`, nested.
         */
        get: operations["getCategoryTree"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/catalog/product-types/{code}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Required details, variation themes and identifier rule of a product type (steps 3 and 4)
         * @description catalog `GET /v1/product-types/{code}`.
         */
        get: operations["getProductType"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/catalog/gtin/{code}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Validate a GTIN/ISBN and find an existing product page that uses it
         * @description catalog `GET /v1/identifiers/gtin/{code}`.
         */
        get: operations["checkGtin"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/category-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The organisation's category requests
         * @description catalog `GET /v1/category-requests/mine`.
         */
        get: operations["listCategoryRequests"];
        put?: never;
        /**
         * Request a new category; the builder switches to the requested category right away
         * @description catalog `POST /v1/category-requests`.
         */
        post: operations["requestCategory"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/uploads": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Direct upload ticket for a product or storefront image
         * @description media `POST /v1/uploads` (JPG, PNG or WebP, ≤ 8 MB).
         */
        post: operations["requestUpload"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/uploads/{assetId}/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Finalise an uploaded image (size and dimensions are read by media)
         * @description media `POST /v1/assets/{id}/complete`.
         */
        post: operations["completeUpload"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/inventory": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Stock by SKU and warehouse (Fulfilled by AfroTransact positions)
         * @description inventory `GET /v1/positions?ownerOrg=` (sellers see only their own stock).
         */
        get: operations["getInventory"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/inventory/adjustments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Set the quantity of a seller-fulfilled SKU (count after a stock take)
         * @description offers `PUT /v1/offers/stock/{sellerSku}` (planned — offers is being built).
         */
        post: operations["adjustStock"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/payouts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Balance (available, on hold per order, on the way) and payout history
         * @description payments `GET /v1/payouts/balance` (incl. payoutsPaused, payoutsPausedReason, providerFeesMinor) + `GET /v1/payouts/requests?orgId=` (history is omitted with `historyAvailable=false` when the member may not read it).
         */
        get: operations["getPayouts"];
        put?: never;
        /**
         * Request a payout (minimum USD 25, once a day, never more than available)
         * @description payments `POST /v1/payouts/requests` (Idempotency-Key relayed).
         */
        post: operations["requestPayout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fees": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Read-only fees of the active organisation — commission per category (own overrides included), FBA and card fees, upcoming change
         * @description payments `GET /v1/fee-schedules` (to find the version in force and the next scheduled one; cancelled versions are
         *     skipped) and `GET /v1/fee-schedules/effective?category=&orgId=&at=` per category, for now and for the upcoming
         *     version's effective date — one batch for the page. payments lets only staff and services read fee schedules, so the
         *     BFF reads them with its own client-credentials token (scope `payments:service`) and always passes the active
         *     organisation: members never see other sellers' overrides. Shares the payouts module level (VIEW). Rates are basis
         *     points (1250 = 12.50 %). AfroTransact Retail reads `retail: true` (no commission, no fees).
         */
        get: operations["getSellerFees"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/billing": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Plan, cadence, next charge, payment methods, invoices and the plan catalogue
         * @description seller `GET /v1/orgs/{orgId}/billing`, `GET /v1/orgs/{orgId}/billing/invoices` and `GET /v1/plans?kind=`.
         */
        get: operations["getBilling"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/billing/plan": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Change plan or cadence (owner only; upgrades prorate now, the rest apply at renewal)
         * @description seller `POST /v1/orgs/{orgId}/billing/plan`.
         */
        post: operations["changePlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/billing/payment-methods/{paymentMethodId}/default": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Make a card or bank account the default (owner only)
         * @description seller `POST /v1/orgs/{orgId}/billing/payment-methods/{id}/default`.
         */
        post: operations["makeDefaultPaymentMethod"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/billing/payment-methods/{paymentMethodId}": {
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
         * Remove a payment method (owner only; the default cannot be removed — 409)
         * @description seller `DELETE /v1/orgs/{orgId}/billing/payment-methods/{id}`.
         */
        delete: operations["removePaymentMethod"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/team": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Members with their roles plus one capability card per role (what it can change, view, can't access)
         * @description identity `GET /v1/orgs/{orgId}/members` (names, emails, roles, join dates) plus, for members who can manage the team, pending invitations from `GET /v1/orgs/{orgId}/invitations` as INVITED rows; identity module matrix for the capability cards (default matrix as fallback).
         */
        get: operations["getTeam"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/team/invitations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Invite a member by email with a role
         * @description identity `POST /v1/orgs/{orgId}/invitations`; identity emails a single-use link (7 days). Inviting the same email again replaces the open invitation. 409 ALREADY_MEMBER when the email belongs to a member; 403 OWNER_ONLY when a non-owner invites an owner; 422 INVALID_ROLE.
         */
        post: operations["inviteMember"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/team/members/{userId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Change a member's role
         * @description identity `PUT /v1/orgs/{orgId}/members/{userId}`. 403 OWN_ROLE for your own role, OWNER_ONLY when a non-owner grants or changes the owner role; 422 LAST_OWNER when the last owner would be demoted, INVALID_ROLE.
         */
        put: operations["assignRole"];
        post?: never;
        /**
         * Remove a member from the organisation
         * @description identity `DELETE /v1/orgs/{orgId}/members/{userId}`. 422 LAST_OWNER for the last owner; 403 OWNER_ONLY when a non-owner removes an owner.
         */
        delete: operations["removeMember"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/team/invitations/{invitationId}": {
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
         * Revoke a pending invitation
         * @description identity `DELETE /v1/orgs/{orgId}/invitations/{invitationId}`; the link stops working.
         */
        delete: operations["revokeInvitation"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/invitations/lookup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * What an invitation link is for (public; shown before signing in or up)
         * @description identity `POST /v1/invitations/lookup`. The token travels in the body so it stays out of access logs.
         */
        post: operations["lookupInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/invitations/accept": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Accept an invitation as the signed-in person
         * @description identity `POST /v1/invitations/accept`, then makes the organisation the active one (best effort). 422 INVITATION_EXPIRED, INVITATION_USED, INVITATION_REVOKED or INVITATION_EMAIL_MISMATCH (signed in with another email). People without an account sign up with `invitationToken` instead (`POST /v1/auth/sign-up`).
         */
        post: operations["acceptInvitation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/bookings/calendar": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Appointments, open hours and day summaries for a date range (week or month view)
         * @description bookings `GET /v1/providers/me/calendar?from=&to=` (planned — bookings is being built).
         */
        get: operations["getCalendar"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/bookings/requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Requests and reschedules waiting on the provider (expire after 4 hours)
         * @description bookings `GET /v1/providers/me/bookings?status=REQUESTED,RESCHEDULE_REQUESTED` (planned).
         */
        get: operations["listBookingRequests"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/bookings/{bookingId}/actions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Confirm, decline (with a reason), propose a new time, complete, mark no-show or cancel
         * @description bookings `POST /v1/bookings/{id}/{confirm|decline|propose|complete|no-show|cancel}` (planned).
         */
        post: operations["actOnBooking"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/provider/hours": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Weekly hours with breaks, blocked dates and booking rules
         * @description bookings `GET /v1/providers/me/availability` (planned).
         */
        get: operations["getWorkingHours"];
        /**
         * Save weekly hours, blocked dates and booking rules
         * @description bookings `PUT /v1/providers/me/availability` (planned).
         */
        put: operations["saveWorkingHours"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/provider/services": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Services with price, deposit and what the provider takes home after commission and card fees
         * @description bookings `GET /v1/providers/me/services` (planned).
         */
        get: operations["listServices"];
        put?: never;
        /**
         * Add a service
         * @description bookings `POST /v1/providers/me/services` (planned).
         */
        post: operations["createService"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/provider/services/{serviceId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Change a service (price changes apply to new bookings only)
         * @description bookings `PATCH /v1/providers/me/services/{id}` (planned).
         */
        patch: operations["updateService"];
        trace?: never;
    };
    "/v1/seller/provider/cancellation-policy": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Cancellation, late and no-show rules shown before customers book
         * @description bookings `GET /v1/providers/me/cancellation-policy` (planned).
         */
        get: operations["getCancellationPolicy"];
        /**
         * Pick Flexible, Moderate or Strict, or build a Custom policy (existing bookings keep theirs)
         * @description bookings `PUT /v1/providers/me/cancellation-policy` (planned).
         */
        put: operations["saveCancellationPolicy"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/provider/earnings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Per booking what was collected minus commission, Stripe fees and late-cancellation fees
         * @description bookings `GET /v1/providers/me/earnings?from=&to=` (planned) + payments `GET /v1/payouts/provider-fees` (outstanding provider fees).
         */
        get: operations["getEarnings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/storefront": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Draft of the seller storefront or provider public page (layout, colours, gallery, featured work, social links)
         * @description storefront `GET /v1/storefronts/me/draft`.
         */
        get: operations["getPublicPage"];
        /**
         * Save the draft (edited social links go back to pending approval)
         * @description storefront `GET /v1/storefronts/me/draft` merged with the changes, then `PUT /v1/storefronts/me/draft`.
         */
        put: operations["savePublicPage"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/storefront/publish": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Publish the draft (blocked with the failing contrast checks)
         * @description storefront `POST /v1/storefronts/me/publish` then the draft is read back.
         */
        post: operations["publishPublicPage"];
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
             * @description Stable machine code: VALIDATION_FAILED, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, NOT_APPROVED,
             *     NO_ORGANISATION, RATE_LIMITED, UPSTREAM_UNAVAILABLE, UPSTREAM_ERROR; team: LAST_OWNER, INVALID_ROLE,
             *     OWNER_ONLY, OWN_ROLE, ALREADY_MEMBER, INVITATION_EXPIRED, INVITATION_USED, INVITATION_REVOKED,
             *     INVITATION_EMAIL_MISMATCH.
             */
            code?: string;
            errors?: {
                [key: string]: string;
            };
        } & {
            [key: string]: unknown;
        };
        /** @enum {string} */
        OrgKind: "SELLER" | "PROVIDER";
        /** @enum {string} */
        OrgStatus: "DRAFT" | "PENDING_REVIEW" | "FLAGGED" | "AWAITING_FINAL_APPROVAL" | "INFO_REQUESTED" | "APPROVED" | "REJECTED" | "SUSPENDED";
        /** @enum {string} */
        Portal: "SELLER_CENTER" | "PROVIDER_HUB";
        /** @enum {string} */
        AccessLevel: "VIEW" | "EDIT" | "APPROVE" | "FULL";
        /** @enum {string} */
        ModuleKey: "onboarding" | "home" | "orders" | "listings" | "inventory" | "storefront" | "calendar" | "requests" | "hours" | "services" | "policy" | "public-page" | "earnings" | "payouts" | "fees" | "billing" | "account-health" | "team";
        SellerUser: {
            id: string;
            firstName: string;
            lastName: string;
            email: string;
            initials: string;
        };
        OrgSummary: {
            id: string;
            kind: components["schemas"]["OrgKind"];
            name: string;
            status: components["schemas"]["OrgStatus"];
            approved: boolean;
            /** @description Organisation role, e.g. seller.owner or provider.practitioner */
            roleKey: string;
            roleLabel: string;
        };
        NavModule: {
            key: components["schemas"]["ModuleKey"];
            label: string;
            level: components["schemas"]["AccessLevel"];
            /** @description Count shown next to the item (orders to ship, requests waiting, open compliance items) */
            badge: number | null;
        };
        NavGroup: {
            key: string;
            label: string;
            modules: components["schemas"]["NavModule"][];
        };
        SellerSession: {
            user: components["schemas"]["SellerUser"];
            orgs: components["schemas"]["OrgSummary"][];
            activeOrg: components["schemas"]["OrgSummary"] | null;
            portal: components["schemas"]["Portal"] | null;
            approved: boolean;
            /** @description Sidebar groups in order; only Onboarding until the organisation is approved. */
            navGroups: components["schemas"]["NavGroup"][];
            landing: components["schemas"]["ModuleKey"];
            accessNote: string;
        };
        ActiveOrgRequest: {
            orgId: string;
        };
        /** @enum {string} */
        OnboardingStepState: "DONE" | "TODO" | "ACTION" | "WAITING" | "BLOCKED";
        OnboardingStatus: {
            orgId: string;
            kind: components["schemas"]["OrgKind"];
            status: string;
            reference: string | null;
            steps: {
                key: string;
                label: string;
                state: components["schemas"]["OnboardingStepState"];
            }[];
            requestedItems: string[];
            canResume: boolean;
        };
        /** @enum {string} */
        ComplianceStatus: "COMPLIANT" | "ACTION_NEEDED" | "RESTRICTED_SOON" | "RESTRICTED" | "VERIFYING";
        ComplianceSummary: {
            status: components["schemas"]["ComplianceStatus"];
            showBanner: boolean;
            /** @enum {string} */
            tone: "none" | "info" | "warning" | "danger";
            title: string;
            message: string;
            openItems: number;
            /** Format: date-time */
            deadline: string | null;
            payoutsPaused: boolean;
            chargesEnabled: boolean;
            payoutsEnabled: boolean;
            listingsHidden: boolean;
            items: {
                requirement: string;
                label: string;
                bucket: string;
            }[];
        };
        /** @enum {string} */
        ChartTone: "ink" | "gold" | "green" | "blue" | "red" | "grey";
        /** @enum {string} */
        ChartKind: "trend" | "hbar" | "donut" | "stack";
        /** @description One chart and its rows. Money series are in major currency units (dollars) so exports read naturally. */
        ChartSpec: {
            id: string;
            title: string;
            kind: components["schemas"]["ChartKind"];
            categoryLabel: string;
            /** @enum {string} */
            valueFormat: "money" | "count";
            series: {
                key: string;
                label: string;
                tone: components["schemas"]["ChartTone"];
                /** @enum {string} */
                format?: "money" | "count";
            }[];
            rows: {
                category: string;
                values: {
                    [key: string]: number;
                };
            }[];
            available: boolean;
        };
        Kpi: {
            label: string;
            value: string;
            detail: string;
            /** @enum {string} */
            tone: "positive" | "attention" | "neutral";
        };
        TodoItem: {
            label: string;
            module: components["schemas"]["ModuleKey"];
            /** @enum {string} */
            tone: "info" | "warning" | "success" | "danger";
        };
        Dashboard: {
            portal: components["schemas"]["Portal"];
            rangeDays: number;
            kpis: components["schemas"]["Kpi"][];
            charts: components["schemas"]["ChartSpec"][];
            todo: components["schemas"]["TodoItem"][];
        };
        /** @enum {string} */
        Fulfilment: "SELLER" | "AFROTRANSACT";
        /** @enum {string} */
        ShipmentStatus: "PENDING_PAYMENT" | "PLACED" | "CONFIRMED" | "PACKED" | "READY" | "PICKED_UP" | "IN_TRANSIT" | "OUT_FOR_DELIVERY" | "DELIVERY_FAILED" | "DELIVERED" | "RETURNED" | "CANCELLED";
        /**
         * @description The only statuses a seller may set.
         * @enum {string}
         */
        SellerStatusAction: "CONFIRMED" | "PACKED" | "READY";
        SellerOrderRow: {
            orderNumber: string;
            shipmentId: string;
            /** Format: date-time */
            placedAt: string;
            buyerName: string;
            /** @description "City, ST" */
            shipTo: string;
            itemsSummary: string;
            itemCount: number;
            totalMinor: number;
            currency: string;
            fulfilment: components["schemas"]["Fulfilment"];
            status: components["schemas"]["ShipmentStatus"];
            statusLabel: string;
            carrier: string | null;
            trackingNumber: string | null;
            nextActions: components["schemas"]["SellerStatusAction"][];
            canBuyLabel: boolean;
        };
        SellerOrderPage: {
            items: components["schemas"]["SellerOrderRow"][];
            nextCursor: string | null;
            total: number;
        };
        ShipFromAddress: {
            id: string;
            /** @example Houston warehouse */
            label: string;
            name: string;
            company: string;
            phone: string;
            line1: string;
            line2: string;
            city: string;
            state: string;
            postalCode: string;
            country: string;
            isDefault: boolean;
            /** Format: int64 */
            version: number;
        };
        ShipFromInput: {
            label?: string;
            name: string;
            company?: string;
            phone?: string;
            line1: string;
            line2?: string;
            city: string;
            state: string;
            postalCode: string;
            /** @default US */
            country: string;
            makeDefault?: boolean;
            /**
             * Format: int64
             * @description Version being edited (updates)
             */
            version?: number | null;
        };
        PostalAddress: {
            name: string;
            phone?: string | null;
            line1: string;
            line2?: string | null;
            city: string;
            state: string;
            postalCode: string;
            country: string;
        };
        SellerShipment: {
            shipmentId: string;
            label: string;
            status: components["schemas"]["ShipmentStatus"];
            statusLabel: string;
            fulfilment: components["schemas"]["Fulfilment"];
            perishable: boolean;
            lines: {
                title: string;
                quantity: number;
                unitPriceMinor: number;
                itemsMinor: number;
                variantAtin: string;
                imageUrl: string | null;
            }[];
            itemsMinor: number;
            shippingMinor: number;
            totalMinor: number;
            carrier: string | null;
            trackingNumber: string | null;
            nextActions: components["schemas"]["SellerStatusAction"][];
            canBuyLabel: boolean;
            timeline: {
                status: string;
                /** Format: date-time */
                at: string;
                actor: string;
            }[];
        };
        SellerOrderDetail: {
            orderNumber: string;
            status: string;
            /** Format: date-time */
            placedAt: string;
            currency: string;
            buyerName: string;
            shippingAddress: components["schemas"]["PostalAddress"] | null;
            shipments: components["schemas"]["SellerShipment"][];
            totalMinor: number;
        };
        StatusUpdateRequest: {
            status: components["schemas"]["SellerStatusAction"];
        };
        Parcel: {
            weightLb: number;
            lengthIn?: number;
            widthIn?: number;
            heightIn?: number;
        };
        RatesRequest: {
            parcel: components["schemas"]["Parcel"];
            shipFrom: components["schemas"]["PostalAddress"];
        };
        /** @enum {string} */
        Carrier: "usps" | "ups" | "fedex" | "dhl";
        RateQuote: {
            carrier: components["schemas"]["Carrier"];
            service: string;
            serviceName: string;
            amountMinor: number;
            currency: string;
            transitDays: number | null;
            /** Format: date-time */
            estimatedDelivery: string | null;
        };
        RateQuotes: {
            quotes: components["schemas"]["RateQuote"][];
            /** @description Some carriers did not answer in time */
            partial: boolean;
        };
        BuyLabelRequest: {
            carrier: components["schemas"]["Carrier"];
            service: string;
            parcel: components["schemas"]["Parcel"];
            shipFrom: components["schemas"]["PostalAddress"];
        };
        LabelPurchase: {
            carrier: components["schemas"]["Carrier"];
            service: string;
            trackingNumber: string;
            costMinor: number;
            currency: string;
            documentUrl: string | null;
            order: components["schemas"]["SellerOrderDetail"];
        };
        /** @enum {string} */
        ListingStatus: "IN_REVIEW" | "PENDING_CATEGORY_APPROVAL" | "LIVE" | "REJECTED" | "PAUSED" | "TAKEN_DOWN";
        ListingRow: {
            productId: string;
            atin: string | null;
            title: string;
            sku: string;
            variantCount: number;
            /** @description Lowest variant price */
            priceMinor: number;
            currency: string;
            /** @description Sum of variant quantities */
            stock: number;
            fulfilment: components["schemas"]["Fulfilment"];
            status: components["schemas"]["ListingStatus"];
            statusLabel: string;
            moderationNote: string | null;
            imageUrl: string | null;
            /** Format: date-time */
            updatedAt: string;
        };
        ListingPage: {
            items: components["schemas"]["ListingRow"][];
            total: number;
            page: number;
            size: number;
        };
        ListingStatusRequest: {
            active: boolean;
        };
        CategoryNode: {
            id: string;
            name: string;
            /** @description DEPARTMENT, CATEGORY or PRODUCT_TYPE */
            level: string;
            productType: string | null;
            path: string;
            children: components["schemas"]["CategoryNode"][];
        };
        ProductTypeSchema: {
            code: string;
            label: string;
            /** @enum {string} */
            identifierRule: "gtin" | "isbn" | "none";
            attributes: {
                key: string;
                label: string;
                options: string[];
                required: boolean;
                multiline: boolean;
            }[];
            themes: {
                name: string;
                dimensions: {
                    name: string;
                    options: string[];
                }[];
            }[];
        };
        GtinCheck: {
            digits: string;
            valid: boolean;
            suggestion: string | null;
            existingAtin: string | null;
            message: string;
        };
        CategoryRequestInput: {
            parentPath: string;
            name: string;
            exampleProduct: string;
            detailsNeeded: string;
        };
        CategoryRequest: {
            id: string;
            reference: string;
            path: string;
            /** @enum {string} */
            status: "PENDING_REVIEW" | "APPROVED" | "REJECTED";
            decisionNote: string | null;
            /** Format: date-time */
            createdAt: string;
        };
        ProductImageInput: {
            url: string;
            width?: number;
            height?: number;
            bytes?: number;
            contentType: string;
        };
        VariantInput: {
            options: {
                [key: string]: string;
            };
            sellerSku: string;
            gtin?: string;
            gtinExempt?: boolean;
            priceMinor: number;
            quantity: number;
        };
        /** @description Exactly one of categoryId or categoryRequestId. */
        ProductDraft: {
            categoryId?: string;
            categoryRequestId?: string;
            title: string;
            attributes: {
                [key: string]: string;
            };
            images: components["schemas"]["ProductImageInput"][];
            variationTheme: string;
            variants: components["schemas"]["VariantInput"][];
        };
        UploadRequest: {
            /** @enum {string} */
            purpose: "product-image" | "storefront";
            fileName: string;
            /** @enum {string} */
            contentType: "image/jpeg" | "image/png" | "image/webp";
            size: number;
        };
        UploadTicket: {
            assetId: string;
            method: string;
            url: string;
            headers: {
                [key: string]: string;
            };
            publicUrl: string | null;
            /** Format: date-time */
            expiresAt: string | null;
        };
        Asset: {
            id: string;
            url: string;
            width: number | null;
            height: number | null;
            bytes: number | null;
            contentType: string;
            /** @enum {string} */
            status: "PENDING" | "READY" | "FLAGGED" | "REJECTED";
        };
        StockPosition: {
            sku: string;
            nodeId: string;
            onHand: number;
            reserved: number;
            available: number;
            inbound: number;
            lowStock: boolean;
        };
        InventoryView: {
            positions: components["schemas"]["StockPosition"][];
            totals: {
                available: number;
                reserved: number;
                inbound: number;
                lowStockSkus: number;
            };
        };
        StockAdjustmentRequest: {
            sku: string;
            quantity: number;
            /** @enum {string} */
            reason: "STOCK_TAKE" | "DAMAGED" | "FOUND" | "RETURNED" | "OTHER";
        };
        StockAdjustment: {
            sku: string;
            quantity: number;
        };
        PayoutBalance: {
            currency: string;
            availableMinor: number;
            onHoldMinor: number;
            requestedOrInTransitMinor: number;
            paidMinor: number;
            /** @description Provider fees (late cancellations) debited from or reserved in the balance */
            providerFeesMinor: number;
            /** @description Compliance hold: payout requests fail until it is lifted; the balance keeps accruing */
            payoutsPaused: boolean;
            payoutsPausedReason: ("AUTO_PAST_DUE" | "MANUAL" | "DEAUTHORIZED" | "CHARGES_DISABLED") | null;
            /** @description What the hold means for the member and how it lifts */
            payoutsPausedMessage: string | null;
            minimumPayoutMinor: number;
            canRequestToday: boolean;
            holds: {
                orderId: string;
                amountMinor: number;
                releaseAt: string | null;
            }[];
        };
        Payout: {
            id: string;
            amountMinor: number;
            currency: string;
            /** @enum {string} */
            status: "REQUESTED" | "HELD" | "IN_TRANSIT" | "PAID" | "FAILED";
            requestedAt: string;
            holdReason: string | null;
            failureMessage: string | null;
        };
        PayoutsView: {
            balance: components["schemas"]["PayoutBalance"];
            payouts: components["schemas"]["Payout"][];
            historyAvailable: boolean;
        };
        PayoutRequest: {
            amountMinor?: number;
            all?: boolean;
        };
        /** @enum {string} */
        Cadence: "MONTHLY" | "QUARTERLY" | "ANNUAL";
        PlanPrice: {
            cadence: components["schemas"]["Cadence"];
            months: number;
            totalMinor: number;
            perMonthMinor: number;
            savingMinor: number;
            perMonthLabel: string;
            billedLabel: string;
            savingLabel: string;
        };
        Plan: {
            code: string;
            name: string;
            tagline: string;
            monthlyMinor: number;
            trialDays: number;
            features: string[];
            prices: components["schemas"]["PlanPrice"][];
            current: boolean;
        };
        BillingPaymentMethod: {
            id: string;
            /** @enum {string} */
            kind: "card" | "bank";
            brand: string | null;
            last4: string;
            expMonth: number | null;
            expYear: number | null;
            bankName: string | null;
            accountType: string | null;
            verified: boolean;
            receivesPayouts: boolean;
            isDefault: boolean;
            label: string;
        };
        Invoice: {
            id: string;
            number: string;
            /** Format: date-time */
            periodStart: string | null;
            /** Format: date-time */
            periodEnd: string | null;
            amountDueMinor: number;
            amountPaidMinor: number;
            currency: string;
            status: string;
            pdfUrl: string | null;
            hostedUrl: string | null;
            /** Format: date-time */
            createdAt: string;
        };
        Subscription: {
            planCode: string;
            planName: string;
            cadence: components["schemas"]["Cadence"];
            status: string;
            priceLabel: string;
            billedLabel: string;
            /** Format: date-time */
            trialEndsAt: string | null;
            /** Format: date-time */
            currentPeriodEnd: string | null;
            /** Format: date-time */
            nextChargeAt: string | null;
            nextChargeMinor: number | null;
            nextChargeLabel: string;
            cancelAtPeriodEnd: boolean;
            pendingChange: {
                planCode: string;
                planName: string;
                cadence: components["schemas"]["Cadence"];
                /** Format: date-time */
                effectiveAt: string;
            } | null;
            dunning: string | null;
        };
        BillingView: {
            subscription: components["schemas"]["Subscription"];
            plans: components["schemas"]["Plan"][];
            paymentMethods: components["schemas"]["BillingPaymentMethod"][];
            invoices: components["schemas"]["Invoice"][];
            /** @description Only the owner role changes billing */
            canManage: boolean;
        };
        ChangePlanRequest: {
            planCode: string;
            cadence: components["schemas"]["Cadence"];
        };
        ChangePlanResult: {
            /** @enum {string} */
            applied: "IMMEDIATE_UPGRADE" | "AT_RENEWAL" | "NO_CHANGE";
            billing: components["schemas"]["BillingView"];
        };
        RoleCard: {
            key: string;
            label: string;
            summary: string;
            note: string | null;
            /** @description What the role can change */
            can: string[];
            /** @description What it can only view */
            view: string[];
            /** @description What it can't access */
            cannot: string[];
        };
        TeamMember: {
            userId: string;
            name: string;
            email: string | null;
            roleKey: string;
            roleLabel: string;
            /**
             * @description INVITED rows are pending invitations (userId `invite:<invitationId>`)
             * @enum {string}
             */
            status: "ACTIVE" | "DISABLED" | "INVITED";
            isYou: boolean;
            /**
             * Format: uuid
             * @description INVITED rows only (revoke with it)
             */
            invitationId?: string;
            /** Format: date-time */
            joinedAt?: string;
            /**
             * Format: date-time
             * @description INVITED rows only
             */
            expiresAt?: string;
        };
        InvitationTokenRequest: {
            token: string;
        };
        InvitationPreview: {
            orgName: string;
            /** @enum {string} */
            orgKind: "SELLER" | "PROVIDER";
            /** @description The invited address (sign in or sign up with it) */
            email: string;
            roleKey: string;
            roleLabel: string;
            /** @enum {string} */
            status: "PENDING" | "ACCEPTED" | "REVOKED" | "EXPIRED";
            /** Format: date-time */
            expiresAt: string;
        };
        AcceptedInvitation: {
            orgId: string;
            orgName: string;
            /** @enum {string} */
            orgKind: "SELLER" | "PROVIDER";
            roleKey: string;
            roleLabel: string;
            /** @description Was already a member; the role was left unchanged */
            alreadyMember: boolean;
        };
        TeamView: {
            members: components["schemas"]["TeamMember"][];
            roles: components["schemas"]["RoleCard"][];
            yourRoleKey: string;
            canManage: boolean;
        };
        InviteRequest: {
            /** Format: email */
            email: string;
            roleKey: string;
        };
        RoleChangeRequest: {
            roleKey: string;
        };
        /** @enum {string} */
        BookingStatus: "PENDING_PAYMENT" | "REQUESTED" | "CONFIRMED" | "RESCHEDULE_REQUESTED" | "RESCHEDULE_PROPOSED" | "COMPLETED" | "NO_SHOW" | "DECLINED" | "EXPIRED" | "CANCELLED_CUSTOMER" | "CANCELLED_PROVIDER";
        /** @enum {string} */
        BookingAction: "CONFIRM" | "DECLINE" | "PROPOSE" | "COMPLETE" | "NO_SHOW" | "CANCEL";
        BookingCard: {
            id: string;
            reference: string;
            customerName: string;
            serviceName: string;
            /** Format: date-time */
            startAt: string;
            /** Format: date-time */
            endAt: string;
            durationMinutes: number;
            status: components["schemas"]["BookingStatus"];
            statusLabel: string;
            priceMinor: number;
            depositMinor: number;
            currency: string;
            /** @enum {string} */
            depositState: "none" | "held" | "captured" | "released" | "refunded";
            note: string | null;
            practitioner: string | null;
            /** Format: date-time */
            expiresAt: string | null;
            /** Format: date-time */
            proposedStartAt: string | null;
            allowedActions: components["schemas"]["BookingAction"][];
        };
        DaySummary: {
            /** Format: date */
            date: string;
            appointments: number;
            bookedMinutes: number;
            expectedRevenueMinor: number;
            needsAction: number;
        };
        TimeRange: {
            start: string;
            end: string;
        };
        /** @enum {string} */
        Weekday: "MON" | "TUE" | "WED" | "THU" | "FRI" | "SAT" | "SUN";
        DayHours: {
            day: components["schemas"]["Weekday"];
            /** @description Empty = closed; two ranges = a break */
            ranges: components["schemas"]["TimeRange"][];
        };
        CalendarView: {
            timezone: string;
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            openHours: components["schemas"]["DayHours"][];
            bookings: components["schemas"]["BookingCard"][];
            days: components["schemas"]["DaySummary"][];
        };
        BookingActionRequest: {
            action: components["schemas"]["BookingAction"];
            /** @description Required to decline or cancel */
            reason?: string;
            /**
             * Format: date-time
             * @description Required to propose a new time
             */
            proposedStartAt?: string;
        };
        BookingRules: {
            leadMinutes: number;
            horizonDays: number;
            bufferMinutes: number;
            stepMinutes: number;
            maxPerDay: number;
            instantBooking: boolean;
            autoAcceptReschedule: boolean;
        };
        Availability: {
            timezone: string;
            weekly: components["schemas"]["DayHours"][];
            blockedDates: {
                /** Format: date */
                date: string;
                note: string;
            }[];
            rules: components["schemas"]["BookingRules"];
        };
        TakeHome: {
            commissionPercent: number;
            commissionMinor: number;
            cardFeesMinor: number;
            takeHomeMinor: number;
        };
        ServiceOffer: {
            id: string;
            name: string;
            description: string;
            durationMinutes: number;
            priceMinor: number;
            depositMinor: number;
            currency: string;
            active: boolean;
            takeHome: components["schemas"]["TakeHome"];
        };
        ServiceInput: {
            name: string;
            description?: string;
            durationMinutes: number;
            priceMinor: number;
            depositMinor: number;
            active: boolean;
        };
        /** @enum {string} */
        PolicyKind: "FLEXIBLE" | "MODERATE" | "STRICT" | "CUSTOM";
        CancellationPolicyInput: {
            kind: components["schemas"]["PolicyKind"];
            freeHours?: number;
            partialRefund?: boolean;
            partialFromHours?: number;
            partialPercent?: number;
            /** @enum {string} */
            noShow?: "DEPOSIT" | "FULL";
            rescheduleHours?: number;
            maxReschedules?: number;
        };
        CancellationPolicy: {
            kind: components["schemas"]["PolicyKind"];
            label: string;
            freeHours: number;
            partialRefund: boolean;
            partialFromHours: number;
            partialPercent: number;
            /** @enum {string} */
            noShow: "DEPOSIT" | "FULL";
            rescheduleHours: number;
            maxReschedules: number;
            summary: string;
            /** @description Refund timeline from booking to the appointment */
            timeline: {
                label: string;
                refundPercent: number;
            }[];
        };
        EarningsRow: {
            bookingId: string;
            reference: string;
            customerName: string;
            serviceName: string;
            /** Format: date-time */
            startAt: string;
            status: components["schemas"]["BookingStatus"];
            statusLabel: string;
            collectedMinor: number;
            commissionMinor: number;
            cardFeesMinor: number;
            lateCancelFeeMinor: number;
            netMinor: number;
        };
        ProviderFee: {
            id: string;
            bookingReference: string;
            amountMinor: number;
            currency: string;
            reason: string;
            /** @enum {string} */
            status: "OUTSTANDING" | "COLLECTED";
            /** Format: date-time */
            nextAttemptAt: string | null;
            /** Format: date-time */
            createdAt: string;
        };
        /** @description Late-cancellation fees the provider owes (payments `GET /v1/payouts/provider-fees`); `available` is false when payments cannot answer. */
        ProviderFees: {
            available: boolean;
            outstandingMinor: number;
            collectedMinor: number;
            items: components["schemas"]["ProviderFee"][];
        };
        Earnings: {
            currency: string;
            commissionPercent: number;
            payoutDay: string;
            totals: {
                collectedMinor: number;
                commissionMinor: number;
                cardFeesMinor: number;
                lateCancelFeesMinor: number;
                netMinor: number;
                heldMinor: number;
            };
            rows: components["schemas"]["EarningsRow"][];
            providerFees: components["schemas"]["ProviderFees"];
        };
        SocialLink: {
            /** @enum {string} */
            network: "instagram" | "tiktok" | "facebook" | "youtube" | "whatsapp" | "x" | "linkedin" | "website";
            url: string;
            /** @enum {string} */
            status: "PENDING" | "APPROVED" | "REJECTED";
        };
        PublicPage: {
            kind: components["schemas"]["OrgKind"];
            slug: string;
            name: string;
            tagline: string;
            /** @enum {string} */
            status: "DRAFT" | "PUBLISHED";
            /** Format: date-time */
            publishedAt: string | null;
            primaryColor: string;
            accentColor: string;
            /** @enum {string} */
            layout: "CLASSIC" | "GALLERY_FIRST" | "STORY";
            /** @enum {string} */
            header: "COVER" | "BAND";
            /** @enum {string} */
            buttonShape: "ROUNDED" | "SQUARE";
            gallerySize: number;
            gallery: {
                assetId: string;
                url: string;
                caption: string;
            }[];
            featured: {
                id: string;
                headline: string;
                body: string;
                serviceId: string | null;
                published: boolean;
            }[];
            social: components["schemas"]["SocialLink"][];
            /** @description Path of the page in the shop */
            publicPath: string;
        };
        PublicPageInput: {
            name?: string;
            tagline?: string;
            primaryColor?: string;
            accentColor?: string;
            /** @enum {string} */
            layout?: "CLASSIC" | "GALLERY_FIRST" | "STORY";
            /** @enum {string} */
            header?: "COVER" | "BAND";
            /** @enum {string} */
            buttonShape?: "ROUNDED" | "SQUARE";
            gallerySize?: number;
            featured?: {
                id: string;
                headline: string;
                body: string;
                serviceId?: string | null;
                published: boolean;
            }[];
            social?: {
                /** @enum {string} */
                network: "instagram" | "tiktok" | "facebook" | "youtube" | "whatsapp" | "x" | "linkedin" | "website";
                /** Format: uri */
                url: string;
            }[];
        };
        SellerFeeCategory: {
            /** @description payments category key (lower-case) */
            category: string;
            label: string;
            /** @description 1250 = 12.50 % */
            commissionBps: number;
            /**
             * @description OVERRIDE: negotiated for this organisation
             * @enum {string}
             */
            source: "OVERRIDE" | "CATEGORY" | "DEFAULT" | "RETAIL";
        };
        SellerFeeVersion: {
            version: number;
            /** Format: date-time */
            effectiveFrom: string;
            /** @description Commission in categories not listed (the organisation's all-category override when it has one) */
            defaultCommissionBps: number;
            categories: components["schemas"]["SellerFeeCategory"][];
            /** @description Card fee estimate per charge, percentage part */
            cardPctBps: number;
            /**
             * Format: int64
             * @description Card fee estimate per charge, fixed part
             */
            cardFixedMinor: number;
            /**
             * Format: int64
             * @description FBA pick, pack and ship fee per unit
             */
            fbaPerUnitMinor: number;
            /** @description Refunds give back the commission on the refunded amount */
            reverseCommissionOnRefund: boolean;
        };
        SellerFeeChange: {
            field: string;
            label: string;
            from: string;
            to: string;
        };
        SellerFeesView: {
            currency: string;
            /** @description AfroTransact Retail (merchant of record) — no commission, no fees */
            retail: boolean;
            note: string | null;
            current: components["schemas"]["SellerFeeVersion"];
            /** @description The next scheduled version, if any */
            upcoming: components["schemas"]["SellerFeeVersion"] | null;
            /** @description What changes for this organisation with the upcoming version */
            changes: components["schemas"]["SellerFeeChange"][];
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
        OrderNumber: string;
        ShipmentId: string;
        ProductId: string;
        PaymentMethodId: string;
        /** @description Makes retries safe (same key = same result). */
        IdempotencyKey: string;
        /** @description Same key = same label; never buys twice. */
        IdempotencyKeyRequired: string;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getSellerSession: {
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
                    "application/json": components["schemas"]["SellerSession"];
                };
            };
            401: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    setActiveOrg: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ActiveOrgRequest"];
            };
        };
        responses: {
            /** @description Session for the new organisation */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SellerSession"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getOnboarding: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Application status */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OnboardingStatus"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getCompliance: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Compliance summary */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ComplianceSummary"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getDashboard: {
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
                    "application/json": components["schemas"]["Dashboard"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listSellerOrders: {
        parameters: {
            query?: {
                status?: components["schemas"]["ShipmentStatus"];
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One page */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SellerOrderPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getSellerOrder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: components["parameters"]["OrderNumber"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Order */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SellerOrderDetail"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateShipmentStatus: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                orderNumber: components["parameters"]["OrderNumber"];
                shipmentId: components["parameters"]["ShipmentId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StatusUpdateRequest"];
            };
        };
        responses: {
            /** @description Updated order */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SellerOrderDetail"];
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
    quoteShippingRates: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: components["parameters"]["OrderNumber"];
                shipmentId: components["parameters"]["ShipmentId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RatesRequest"];
            };
        };
        responses: {
            /** @description Quotes */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RateQuotes"];
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
    buyShippingLabel: {
        parameters: {
            query?: never;
            header: {
                /** @description Same key = same label; never buys twice. */
                "Idempotency-Key": components["parameters"]["IdempotencyKeyRequired"];
            };
            path: {
                orderNumber: components["parameters"]["OrderNumber"];
                shipmentId: components["parameters"]["ShipmentId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["BuyLabelRequest"];
            };
        };
        responses: {
            /** @description Label bought */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["LabelPurchase"];
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
    listShipFromAddresses: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Addresses */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items: components["schemas"]["ShipFromAddress"][];
                    };
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createShipFromAddress: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ShipFromInput"];
            };
        };
        responses: {
            /** @description Added */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ShipFromAddress"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    updateShipFromAddress: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                addressId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ShipFromInput"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ShipFromAddress"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
    deleteShipFromAddress: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                addressId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Remaining addresses */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items: components["schemas"]["ShipFromAddress"][];
                    };
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
        };
    };
    defaultShipFromAddress: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                addressId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Every address, default first */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items: components["schemas"]["ShipFromAddress"][];
                    };
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
        };
    };
    listListings: {
        parameters: {
            query?: {
                status?: components["schemas"]["ListingStatus"];
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
            /** @description One page */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ListingPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    setListingActive: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                productId: components["parameters"]["ProductId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ListingStatusRequest"];
            };
        };
        responses: {
            /** @description Updated listing */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ListingRow"];
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
    submitProduct: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ProductDraft"];
            };
        };
        responses: {
            /** @description Listing created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ListingRow"];
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
    getCategoryTree: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Tree */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CategoryNode"][];
                };
            };
            401: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getProductType: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                code: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Schema */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProductTypeSchema"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    checkGtin: {
        parameters: {
            query?: {
                isbn?: boolean;
            };
            header?: never;
            path: {
                code: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Check */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GtinCheck"];
                };
            };
            401: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listCategoryRequests: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Requests */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CategoryRequest"][];
                };
            };
            401: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    requestCategory: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CategoryRequestInput"];
            };
        };
        responses: {
            /** @description Request */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CategoryRequest"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    requestUpload: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UploadRequest"];
            };
        };
        responses: {
            /** @description Upload instructions */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["UploadTicket"];
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    completeUpload: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                assetId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Asset */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Asset"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getInventory: {
        parameters: {
            query?: {
                lowStock?: boolean;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Positions */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InventoryView"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    adjustStock: {
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
                "application/json": components["schemas"]["StockAdjustmentRequest"];
            };
        };
        responses: {
            /** @description Adjusted */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StockAdjustment"];
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
    getPayouts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Payouts */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PayoutsView"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    requestPayout: {
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
                "application/json": components["schemas"]["PayoutRequest"];
            };
        };
        responses: {
            /** @description Requested */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Payout"];
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
    getSellerFees: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Fees */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SellerFeesView"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getBilling: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Billing */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BillingView"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    changePlan: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangePlanRequest"];
            };
        };
        responses: {
            /** @description Result */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ChangePlanResult"];
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
    makeDefaultPaymentMethod: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                paymentMethodId: components["parameters"]["PaymentMethodId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Payment methods */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BillingPaymentMethod"][];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    removePaymentMethod: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                paymentMethodId: components["parameters"]["PaymentMethodId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Payment methods */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BillingPaymentMethod"][];
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
    getTeam: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Team */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TeamView"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    inviteMember: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InviteRequest"];
            };
        };
        responses: {
            /** @description Invited member (an INVITED row) */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TeamMember"];
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
    assignRole: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                userId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RoleChangeRequest"];
            };
        };
        responses: {
            /** @description Team */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TeamView"];
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
    removeMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Team */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TeamView"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    revokeInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                invitationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Team */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TeamView"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    lookupInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvitationTokenRequest"];
            };
        };
        responses: {
            /** @description Invitation */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["InvitationPreview"];
                };
            };
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    acceptInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvitationTokenRequest"];
            };
        };
        responses: {
            /** @description Joined */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AcceptedInvitation"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getCalendar: {
        parameters: {
            query: {
                from: string;
                to: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Calendar */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CalendarView"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listBookingRequests: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Requests */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BookingCard"][];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    actOnBooking: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (same key = same result). */
                "Idempotency-Key"?: components["parameters"]["IdempotencyKey"];
            };
            path: {
                bookingId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["BookingActionRequest"];
            };
        };
        responses: {
            /** @description Updated booking */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BookingCard"];
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
    getWorkingHours: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Availability */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Availability"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    saveWorkingHours: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Availability"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Availability"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listServices: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Services */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ServiceOffer"][];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createService: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ServiceInput"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ServiceOffer"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateService: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                serviceId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ServiceInput"];
            };
        };
        responses: {
            /** @description Updated */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ServiceOffer"];
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
    getCancellationPolicy: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Policy */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CancellationPolicy"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    saveCancellationPolicy: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CancellationPolicyInput"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CancellationPolicy"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getEarnings: {
        parameters: {
            query?: {
                from?: string;
                to?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Earnings */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Earnings"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getPublicPage: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Draft */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PublicPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    savePublicPage: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PublicPageInput"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PublicPage"];
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
    publishPublicPage: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Published page */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PublicPage"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
}
