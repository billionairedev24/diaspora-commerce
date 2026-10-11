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
         * @description seller `GET /v1/applications/me`, with the first two steps' answers (`form`) for the wizard.
         */
        get: operations["getOnboarding"];
        put?: never;
        /**
         * Start selling / offer services — start or resume the application (idempotent, S286)
         * @description "Start selling" and "Offer your services" for a signed-in account, with no second sign-up. Adds the seller
         *     (or provider) applicant role to the account (identity `POST /v1/me/roles/{seller|provider}-applicant`,
         *     idempotent), then starts the application (seller `POST /v1/applications/me`), which creates the DRAFT
         *     organisation with the caller as owner. A second call resumes the same application (200).
         *
         *     The application is prefilled from what the account already has: name and email (identity profile),
         *     phone (verified or not), the default address-book address (business address) and, when the person
         *     already belongs to an organisation, its name as the store name. Prefilled fields are listed in
         *     `form.prefilled`.
         *
         *     403 `STAFF_CANNOT_APPLY`: staff accounts (platform roles) cannot apply; they use a personal account.
         *     409 `ALREADY_APPROVED`: the account already has an approved organisation of that kind (open Seller Center
         *     or Provider Hub instead). 409 `APPLICATION_EXISTS`: the account already has an application of the other
         *     kind.
         */
        post: operations["startOnboarding"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/business": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Autosave the wizard's "Your business" step
         * @description seller `PUT /v1/applications/me/steps/business`; only the fields present are validated.
         */
        put: operations["saveOnboardingBusiness"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/business-details": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Save "About you" (sole proprietor) / "Business details & TIN"
         * @description seller `PUT /v1/applications/me/steps/business-details`; the whole step is sent (the seller service replaces the
         *     section). `taxId` (SSN/ITIN or EIN) is encrypted at rest by the seller service and never returned or logged;
         *     omit it to keep the stored one. Only the fields present are validated; completion is decided by the seller
         *     service.
         */
        put: operations["saveOnboardingDetails"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/owners": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Save "Owners" (beneficial owners and control persons)
         * @description seller `PUT /v1/applications/me/steps/owners` (not for sole proprietors). Everyone with 25%+ and at least one person with significant control; ownership may not exceed 100%.
         */
        put: operations["saveOnboardingOwners"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/plan": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Save "Plan" (plan, billing frequency and, for paid plans, the card)
         * @description seller `PUT /v1/applications/me/steps/plan`. Cards are tokenised by Stripe.js (`pm_…`); only brand, last 4 and expiry are stored. The subscription starts after approval, with the plans free trial.
         */
        put: operations["saveOnboardingPlan"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/catalog-compliance": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Save "Catalog, permits & licences" / "Services & licences"
         * @description seller `PUT /v1/applications/me/steps/catalog-compliance`: categories (lower-cased keys), the food permit for groceries / prepared food, beauty labelling, service licences, insurance, agreements and the W-9 certification.
         */
        put: operations["saveOnboardingCompliance"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/plans": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Plans the applicant can choose (their kind), with prices per billing frequency and the first charge
         * @description seller `GET /v1/plans?kind=` for the application's kind.
         */
        get: operations["getOnboardingPlans"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/stripe/session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Account Session for Stripe's embedded Connect components (verification and the payout bank account)
         * @description seller `POST /v1/applications/me/stripe/account-session`: creates the connected account on first use and an
         *     Account Session for Connect.js, with the BFF's Stripe publishable key. The account is Stripe-collected (Stripe
         *     keeps the liability and KYC), so Stripe's components collect identity, tax ID and the payout bank account:
         *     `account-onboarding` in "Stripe verification", `account-management` for "Change bank account" on "Payouts".
         *     No Stripe-hosted page. `localTest` is true only for the seller service's local stand-in (local profile without
         *     a Stripe key); Seller Center then shows its labelled local verification. 503 `STRIPE_NOT_CONFIGURED` when a real
         *     session can't load for lack of a publishable key.
         */
        post: operations["createOnboardingStripeSession"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/stripe/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Re-read the Stripe verification results (after the embedded onboarding closes)
         * @description seller `POST /v1/applications/me/stripe/refresh`.
         */
        post: operations["refreshOnboardingStripe"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/submit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Submit the application to AfroTransact staff review (or resubmit after a request for information)
         * @description seller `POST /v1/applications/me/submit`; 422 lists every field still missing across the steps.
         */
        post: operations["submitOnboarding"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/uploads": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Direct upload ticket for an application document (permit, licence, insurance, formation document)
         * @description media `POST /v1/uploads` with purpose `kyc-document` (private; PDF, JPG, PNG or WebP, ≤ 15 MB).
         */
        post: operations["requestOnboardingUpload"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/onboarding/uploads/{assetId}/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Finalise an uploaded application document
         * @description media `POST /v1/assets/{id}/complete`.
         */
        post: operations["completeOnboardingUpload"];
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
    "/v1/seller/stripe/session": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Account Session for Stripe's embedded components in Account health (owner / finance)
         * @description seller `POST /v1/orgs/{orgId}/stripe/account-session` for the active organisation. Seller accounts are
         *     Stripe-collected: what Stripe needs and the payout bank account are answered in Stripe's embedded components
         *     (`notification-banner`, `account-management`, `account-onboarding`), never through AfroTransact. 403 for
         *     members other than the owner and finance; 404 before the organisation has a Stripe account; 503
         *     `STRIPE_NOT_CONFIGURED` without a publishable key.
         */
        post: operations["createSellerStripeSession"];
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
         * @description orders `GET /v1/seller/shipments` (newest first, cursor pagination, status filter). `q` searches every column
         *     the table shows (order, placed date, buyer, ship-to, items, units, total, fulfilment, status, tracking) and `sort`
         *     orders by any of them: the BFF then reads the queue (up to 5,000 shipments, cached 20 seconds per member) and
         *     pages it itself; the cursor is then an offset and `partial` says when the queue had more rows than were read.
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
    "/v1/seller/ship-from-addresses/{addressId}/afrotransact-pickup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Turn "AfroTransact picks up from my store" on or off for a ship-from address (S255)
         * @description seller `PUT /v1/orgs/{orgId}/ship-from-addresses/{id}/afrotransact-pickup` (Orders edit). Enabling needs an approved
         *     organisation (409 `PICKUP_ORG_NOT_APPROVED`), 1–14 non-overlapping pickup windows (422 `PICKUP_INVALID_HOURS`,
         *     `errors.hours`), a street-level geocoded position (422 `PICKUP_NOT_GEOCODED`) and a location inside or within 25
         *     miles of an AfroTransact delivery zone (422 `PICKUP_OUTSIDE_DELIVERY_AREA`). Geo down → 503
         *     `UPSTREAM_UNAVAILABLE`. Disabling keeps the hours for next time. Optimistic: 409 `CONFLICT` when the address changed.
         */
        put: operations["setAfrotransactPickup"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/ship-from-addresses/{addressId}/customer-pickup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Turn "customers can pick up their orders here" on or off for a ship-from address (store or pickup place)
         * @description carrier-gateway `PUT /v1/pickup-locations/{addressId}` with the seller's token (Orders edit; carrier-gateway checks
         *     shipping:label and the organisation): a SELLER pickup location with the address's id, name (company · label),
         *     address, position, the state's time zone, the opening hours and how long orders take to be ready. Checkout lists
         *     it (with address, hours, ready time and distance) for this seller's packages; pickup has no shipping charge.
         *     Enabling needs 1–14 non-overlapping windows (422 `PICKUP_INVALID_HOURS`, `errors.hours`). Disabling keeps the
         *     hours. Unknown address → 404. carrier-gateway down → 503 `UPSTREAM_UNAVAILABLE`.
         */
        put: operations["setCustomerPickup"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/offers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The organisation's offers of a product (fulfilment, pickup location, price, stock)
         * @description offers `GET /v1/offers` for the active organisation (Listings view), filtered by catalog product id.
         */
        get: operations["listSellerOffers"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/offers/{offerId}/fulfilment": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Choose who fulfils an offer: I ship it, Fulfilled by AfroTransact (FBA) or AfroTransact picks up from my store
         * @description offers `PATCH /v1/offers/{id}` `{fulfilment, shipFrom: {locationId}}` (Listings edit). `AFROTRANSACT_DELIVERY` needs
         *     `shipFromLocationId`: one of the organisation's ship-from addresses with AfroTransact pickup enabled (422,
         *     `errors.shipFromLocationId`). 409 `CONFLICT` when the offer changed meanwhile.
         */
        put: operations["setOfferFulfilment"];
        post?: never;
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
         * @description catalog `GET /v1/products` (own organisation; status, page, size). `q` searches every column the table shows
         *     (product, ATIN, SKU, variants, price, stock, fulfilment, status, updated) and `sort` orders by any of them: the BFF
         *     then reads the organisation's listings (up to 5,000, cached 20 seconds per member) and pages them itself.
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
    "/v1/seller/exports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** My exports of the active organisation, newest first (kept 24 hours after they finish) */
        get: operations["listSellerExports"];
        put?: never;
        /**
         * Export a table as CSV, Excel (with a Summary sheet of native charts) or PDF (with a summary page of charts)
         * @description Always scoped to the active organisation and the member's token. `table: orders` (needs VIEW on orders) and
         *     `table: listings` (VIEW on listings) are read by the BFF (orders `GET /v1/seller/shipments`, catalog
         *     `GET /v1/products`, up to 50,000 rows) with the table's search, sort and visible columns, or only the selected
         *     rows (`ids`). Client-side tables (inventory, team, payouts, invoices, fees, earnings, services) send their rows
         *     (`dataset`). Up to 5,000 rows finish within the request (201 READY, download right away); larger exports are
         *     queued (202 QUEUED) — poll `GET /v1/seller/exports/{exportId}`.
         */
        post: operations["createSellerExport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/exports/{exportId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Status of one export (poll until READY or FAILED); only its member, in the organisation it was made for */
        get: operations["getSellerExport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/exports/{exportId}/download": {
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
        get: operations["downloadSellerExport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/export-files/{exportId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The export file behind a signed link (no session needed; the signature and expiry are checked) */
        get: operations["getSellerExportFile"];
        put?: never;
        post?: never;
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
    "/v1/seller/fba/nodes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/nodes`
         * @description Mirrors inventory `GET /v1/nodes` with the member's token for the active organisation (Inventory module VIEW).
         */
        get: operations["getSellerFbaNodes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fba/inventory": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/fba/inventory`
         * @description Mirrors inventory `GET /v1/fba/inventory` with the member's token for the active organisation (Inventory module VIEW). Filtered to the active organisation (ownerOrg is set by the BFF).
         */
        get: operations["getSellerFbaInventory"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fba/inbound": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/inbound/shipments`
         * @description Mirrors inventory `GET /v1/inbound/shipments` with the member's token for the active organisation (Inventory module VIEW). Filtered to the active organisation (ownerOrg is set by the BFF). The body is inventory's `InboundShipmentPage` (contracts/openapi/inventory.yaml).
         */
        get: operations["getSellerFbaInbound"];
        put?: never;
        /**
         * inventory `POST /v1/inbound/shipments`
         * @description Mirrors inventory `POST /v1/inbound/shipments` with the member's token for the active organisation (Inventory module EDIT). ownerOrg in the body is ignored. The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        post: operations["postSellerFbaInbound"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fba/inbound/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/inbound/shipments/{id}`
         * @description Mirrors inventory `GET /v1/inbound/shipments/{id}` with the member's token for the active organisation (Inventory module VIEW). The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        get: operations["getSellerFbaInboundById"];
        /**
         * inventory `PUT /v1/inbound/shipments/{id}`
         * @description Mirrors inventory `PUT /v1/inbound/shipments/{id}` with the member's token for the active organisation (Inventory module EDIT). ownerOrg in the body is ignored. The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        put: operations["putSellerFbaInboundById"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fba/inbound/{id}/tracking": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * inventory `PUT /v1/inbound/shipments/{id}/tracking`
         * @description Mirrors inventory `PUT /v1/inbound/shipments/{id}/tracking` with the member's token for the active organisation (Inventory module EDIT). ownerOrg in the body is ignored. The body is inventory's `InboundTrackingResult` (contracts/openapi/inventory.yaml).
         */
        put: operations["putSellerFbaInboundTracking"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fba/inbound/{id}/ship": {
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
         * @description Mirrors inventory `POST /v1/inbound/shipments/{id}/ship` with the member's token for the active organisation (Inventory module EDIT). ownerOrg in the body is ignored. The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        post: operations["postSellerFbaInboundShip"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fba/inbound/{id}/cancel": {
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
         * @description Mirrors inventory `POST /v1/inbound/shipments/{id}/cancel` with the member's token for the active organisation (Inventory module EDIT). ownerOrg in the body is ignored. The body is inventory's `InboundShipment` (contracts/openapi/inventory.yaml).
         */
        post: operations["postSellerFbaInboundCancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fba/inbound/{id}/labels": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/inbound/shipments/{id}/labels`
         * @description Mirrors inventory `GET /v1/inbound/shipments/{id}/labels` with the member's token for the active organisation (Inventory module VIEW).
         */
        get: operations["getSellerFbaInboundLabels"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fba/lots": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/lots`
         * @description Mirrors inventory `GET /v1/lots` with the member's token for the active organisation (Inventory module VIEW). Filtered to the active organisation (ownerOrg is set by the BFF). The body is inventory's `LotPage` (contracts/openapi/inventory.yaml).
         */
        get: operations["getSellerFbaLots"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fba/lots/markdown": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/lots/markdown`
         * @description Mirrors inventory `GET /v1/lots/markdown` with the member's token for the active organisation (Inventory module VIEW). Filtered to the active organisation (ownerOrg is set by the BFF).
         */
        get: operations["getSellerFbaLotsMarkdown"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/seller/fba/lots/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * inventory `GET /v1/lots/{id}`
         * @description Mirrors inventory `GET /v1/lots/{id}` with the member's token for the active organisation (Inventory module VIEW). The body is inventory's `Lot` (contracts/openapi/inventory.yaml).
         */
        get: operations["getSellerFbaLotsById"];
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
        Problem: {
            /** Format: uri */
            type: string;
            title: string;
            status: number;
            detail?: string;
            instance?: string;
            /**
             * @description Stable machine code: VALIDATION_FAILED, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, NOT_APPROVED,
             *     NO_ORGANISATION, RATE_LIMITED, UPSTREAM_UNAVAILABLE, UPSTREAM_ERROR; exports: EXPORT_NOT_READY,
             *     EXPORT_EXPIRED; team: LAST_OWNER, INVALID_ROLE,
             *     OWNER_ONLY, OWN_ROLE, ALREADY_MEMBER, INVITATION_EXPIRED, INVITATION_USED, INVITATION_REVOKED,
             *     INVITATION_EMAIL_MISMATCH; AfroTransact pickup (S255): PICKUP_ORG_NOT_APPROVED, PICKUP_INVALID_HOURS,
             *     PICKUP_NOT_GEOCODED, PICKUP_OUTSIDE_DELIVERY_AREA; start selling (S286): STAFF_CANNOT_APPLY,
             *     ALREADY_APPROVED, APPLICATION_EXISTS.
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
        OnboardingStep: {
            /** @enum {string} */
            key: "BUSINESS" | "BUSINESS_DETAILS" | "OWNERS" | "STRIPE_VERIFICATION" | "PAYOUTS" | "PLAN" | "CATALOG_COMPLIANCE" | "REVIEW";
            /**
             * @description URL segment in Seller Center (/onboarding/<slug>)
             * @enum {string}
             */
            slug: "business" | "business-details" | "owners" | "stripe-verification" | "payouts" | "plan" | "catalog-compliance" | "review";
            /** @description Design title: "About you" for a sole proprietor, "Services & licences" for services only */
            label: string;
            sub: string;
            /** @description DONE only when the seller service finds the step's required data complete and valid (Stripe verification and Payouts from what Stripe reports). REVIEW is WAITING while staff review the application. */
            state: components["schemas"]["OnboardingStepState"];
        };
        /** @enum {string} */
        OnboardingStripeStatus: "NOT_STARTED" | "IN_PROGRESS" | "REQUIREMENTS_DUE" | "PENDING" | "VERIFIED" | "REJECTED";
        OnboardingStripe: {
            status: components["schemas"]["OnboardingStripeStatus"];
            detailsSubmitted: boolean;
            chargesEnabled: boolean;
            payoutsEnabled: boolean;
            identityStatus: string | null;
            taxIdStatus: string | null;
            /** @description Stripe requirement keys still due */
            currentlyDue: string[];
        };
        OnboardingTimelineEntry: {
            action: string;
            status: string;
            note: string | null;
            /** Format: date-time */
            occurredAt: string;
        };
        OnboardingStatus: {
            orgId: string;
            kind: components["schemas"]["OrgKind"];
            status: string;
            reference: string | null;
            steps: components["schemas"]["OnboardingStep"][];
            requestedItems: string[];
            canResume: boolean;
            /** @description Slug of the first incomplete step (review once every step is complete) */
            resumeStep: string;
            /** Format: date-time */
            submittedAt: string | null;
            stripe: components["schemas"]["OnboardingStripe"];
            timeline: components["schemas"]["OnboardingTimelineEntry"][];
            form: components["schemas"]["OnboardingForm"];
        };
        StartOnboardingRequest: {
            kind: components["schemas"]["OrgKind"];
        };
        /** @enum {string|null} */
        OnboardingOffering: "PRODUCTS" | "SERVICES" | "BOTH" | null;
        /** @enum {string|null} */
        OnboardingEntityType: "SOLE_PROPRIETOR" | "SINGLE_MEMBER_LLC" | "MULTI_MEMBER_LLC" | "CORPORATION" | "PARTNERSHIP" | "NONPROFIT" | null;
        OnboardingAddress: {
            line1: string | null;
            line2: string | null;
            city: string | null;
            /** @description Two-letter state code */
            state: string | null;
            postalCode: string | null;
            /** @description ISO 3166-1 alpha-2 */
            country: string | null;
        };
        OnboardingOwner: {
            firstName: string | null;
            lastName: string | null;
            title: string | null;
            email: string | null;
            ownershipPercent: number | null;
            significantControl: boolean;
            /** Format: date */
            dateOfBirth: string | null;
            address: components["schemas"]["OnboardingAddress"] | null;
        };
        OnboardingFirstCharge: {
            /** Format: date */
            chargeOn: string | null;
            /** Format: int64 */
            amountCents: number;
            sentence: string;
        };
        /** @enum {string} */
        OnboardingFoodPermitType: "FOOD_ESTABLISHMENT" | "COTTAGE_FOOD" | "FDA_FACILITY" | "FOOD_HANDLER";
        OnboardingFoodPermit: {
            /** @description OnboardingFoodPermitType */
            permitType: string | null;
            permitNumber: string | null;
            issuingAuthority: string | null;
            /** Format: date */
            expiresOn: string | null;
            documentAssetId: string | null;
        };
        OnboardingServiceLicence: {
            serviceName: string;
            regulated: boolean;
            licenceNumber: string | null;
            issuingState: string | null;
            /** Format: date */
            expiresOn: string | null;
            documentAssetId: string | null;
        };
        /**
         * @description Every answer of the wizard (S286, S289). `prefilled` lists the fields filled in from the account and still
         *     unchanged: business.contactEmail, business.phone, business.storeName, details.legalFirstName,
         *     details.legalLastName, details.address. The tax ID and bank numbers are never returned (masked / last 4).
         */
        OnboardingForm: {
            business: {
                offering: components["schemas"]["OnboardingOffering"];
                entityType: components["schemas"]["OnboardingEntityType"];
                country: string | null;
                storeName: string | null;
                contactEmail: string | null;
                /** @description E.164 */
                phone: string | null;
                website: string | null;
            };
            details: {
                legalFirstName: string | null;
                legalLastName: string | null;
                /** @description Registered business name (not for sole proprietors) */
                legalName: string | null;
                dba: string | null;
                /** @enum {string|null} */
                taxIdType: "SSN" | "ITIN" | "EIN" | null;
                /** @description e.g. •••-••-6789 */
                taxIdMasked: string | null;
                /** Format: date */
                dateOfBirth: string | null;
                stateOfFormation: string | null;
                /** Format: date */
                dateOfFormation: string | null;
                address: components["schemas"]["OnboardingAddress"] | null;
                formationDocumentAssetId: string | null;
                irsDeterminationLetterAssetId: string | null;
            };
            owners: components["schemas"]["OnboardingOwner"][];
            /**
             * @description The payout bank account Stripe holds, read-only: Stripe collects it in its embedded onboarding ("Stripe
             *     verification") and changes it in account management ("Change bank account"). Never the numbers.
             */
            payouts: {
                /**
                 * @description NOT_STARTED (no Stripe account yet), NEEDS_BANK (Stripe holds no bank yet: finish Stripe verification),
                 *     FAILED (Stripe rejected the bank: change it), VERIFYING (bank on file, payouts not on yet), READY (the
                 *     step is complete).
                 * @enum {string}
                 */
                status: "NOT_STARTED" | "NEEDS_BANK" | "FAILED" | "VERIFYING" | "READY";
                bankName: string | null;
                accountLast4: string | null;
                /** @description Stripe's status: new, validated, verified, verification_failed, errored */
                bankStatus: string | null;
                /** @description Stripe holds a usable payout account */
                linked: boolean;
            };
            plan: {
                planCode: string | null;
                planName: string | null;
                /** @enum {string|null} */
                cadence: "MONTHLY" | "QUARTERLY" | "ANNUAL" | null;
                /** @enum {string|null} */
                paymentMethodKind: "CARD" | "US_BANK_ACCOUNT" | null;
                cardBrand: string | null;
                cardLast4: string | null;
                cardExpMonth: number | null;
                cardExpYear: number | null;
                firstCharge: components["schemas"]["OnboardingFirstCharge"] | null;
            };
            compliance: {
                categories: string[];
                foodPermit: components["schemas"]["OnboardingFoodPermit"] | null;
                beautyLabellingConfirmed: boolean | null;
                services: components["schemas"]["OnboardingServiceLicence"][];
                insuranceDocumentAssetId: string | null;
                agreementsAccepted: boolean | null;
                w9Certified: boolean | null;
                w9SignerName: string | null;
            };
            documents: {
                assetId: string;
                kind: string;
                label: string;
            }[];
            prefilled: string[];
            /** @description Whether the applicant can still change answers (DRAFT or INFO_REQUESTED) */
            editable: boolean;
        };
        OnboardingBusinessInput: {
            /** @enum {string} */
            offering?: "PRODUCTS" | "SERVICES" | "BOTH";
            /** @enum {string} */
            entityType?: "SOLE_PROPRIETOR" | "SINGLE_MEMBER_LLC" | "MULTI_MEMBER_LLC" | "CORPORATION" | "PARTNERSHIP" | "NONPROFIT";
            country?: string;
            storeName?: string;
            contactEmail?: string;
            phone?: string;
            website?: string;
        };
        OnboardingAddressInput: {
            line1: string;
            line2?: string;
            city: string;
            state: string;
            postalCode: string;
            country?: string;
        };
        OnboardingDetailsInput: {
            legalFirstName?: string;
            legalLastName?: string;
            legalName?: string;
            dba?: string;
            /** @description SSN/ITIN or EIN (write-only, encrypted at rest; omit to keep the stored one) */
            taxId?: string;
            /** Format: date */
            dateOfBirth?: string;
            stateOfFormation?: string;
            /** Format: date */
            dateOfFormation?: string;
            address?: components["schemas"]["OnboardingAddressInput"];
            formationDocumentAssetId?: string;
            irsDeterminationLetterAssetId?: string;
        };
        OnboardingOwnerInput: {
            firstName?: string;
            lastName?: string;
            title?: string;
            email?: string;
            ownershipPercent?: number;
            significantControl?: boolean;
            /** Format: date */
            dateOfBirth?: string;
            address?: components["schemas"]["OnboardingAddressInput"];
        };
        OnboardingOwnersInput: {
            owners: components["schemas"]["OnboardingOwnerInput"][];
        };
        OnboardingPlanInput: {
            planCode: string;
            /** @enum {string} */
            cadence: "MONTHLY" | "QUARTERLY" | "ANNUAL";
            /** @enum {string} */
            paymentMethodKind?: "CARD";
            /** @description pm_… from Stripe.js */
            stripePaymentMethodId?: string;
            cardBrand?: string;
            cardLast4?: string;
            cardExpMonth?: number;
            cardExpYear?: number;
        };
        OnboardingFoodPermitInput: {
            permitType?: components["schemas"]["OnboardingFoodPermitType"];
            permitNumber?: string;
            issuingAuthority?: string;
            /** Format: date */
            expiresOn?: string;
            documentAssetId?: string;
        };
        OnboardingServiceLicenceInput: {
            serviceName: string;
            regulated?: boolean;
            licenceNumber?: string;
            issuingState?: string;
            /** Format: date */
            expiresOn?: string;
            documentAssetId?: string;
        };
        OnboardingComplianceInput: {
            categories?: string[];
            foodPermit?: components["schemas"]["OnboardingFoodPermitInput"];
            beautyLabellingConfirmed?: boolean;
            services?: components["schemas"]["OnboardingServiceLicenceInput"][];
            insuranceDocumentAssetId?: string;
            agreementsAccepted?: boolean;
            w9Certified?: boolean;
            w9SignerName?: string;
        };
        OnboardingStripeSession: {
            accountId: string;
            /** @description Account Session client secret for Connect.js */
            clientSecret: string;
            publishableKey: string;
            /** Format: date-time */
            expiresAt: string | null;
            /** @description The seller service's local stand-in (local profile without a Stripe key only) */
            localTest: boolean;
        };
        OnboardingPlanPrice: {
            /** @enum {string} */
            cadence: "MONTHLY" | "QUARTERLY" | "ANNUAL";
            months: number;
            /** Format: int64 */
            totalCents: number;
            /** Format: int64 */
            perMonthCents: number;
            /** Format: int64 */
            savingCents: number;
            perMonthLabel: string;
            billedLabel: string;
            savingLabel: string | null;
            firstCharge: components["schemas"]["OnboardingFirstCharge"] | null;
        };
        OnboardingPlans: {
            /** @description Stripe publishable key for Stripe Elements (the card of a paid plan); empty locally without Stripe, where Seller Center shows its labelled test card form */
            publishableKey: string;
            plans: {
                code: string;
                name: string;
                tagline: string | null;
                /** Format: int64 */
                monthlyCents: number;
                trialDays: number;
                features: string[];
                prices: components["schemas"]["OnboardingPlanPrice"][];
            }[];
        };
        OnboardingUploadRequest: {
            fileName: string;
            /** @enum {string} */
            contentType: "application/pdf" | "image/jpeg" | "image/png" | "image/webp";
            size: number;
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
            /** @description The payout bank account Stripe holds (changed in Stripe's account management component) */
            payoutBank: {
                bankName: string | null;
                last4: string | null;
                status: string | null;
            } | null;
            items: {
                requirement: string;
                label: string;
                bucket: string;
                /**
                 * @description The Stripe embedded component that collects it (nothing is answered through AfroTransact)
                 * @enum {string}
                 */
                component: "account_onboarding" | "account_management";
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
        /**
         * @description SELLER = I ship it; AFROTRANSACT = Fulfilled by AfroTransact (FBA); AFROTRANSACT_DELIVERY = AfroTransact picks up from my store (S255)
         * @enum {string}
         */
        Fulfilment: "SELLER" | "AFROTRANSACT" | "AFROTRANSACT_DELIVERY";
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
            /** @description The applied sort (`<column>,asc|desc`); empty for newest first */
            sort: string[];
            /** @description The search or sort covered the first 5,000 shipments only */
            partial: boolean;
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
            /** @description Where geo-service located the address; null until it could (geocoding failed or geo was down) */
            position: components["schemas"]["GeoPosition"] | null;
            afrotransactPickup: components["schemas"]["AfrotransactPickup"];
            customerPickup?: components["schemas"]["CustomerPickup"];
        };
        /** @description Customers can collect their orders at this address (a carrier-gateway SELLER pickup location with the same id) */
        CustomerPickup: {
            enabled: boolean;
            hours: components["schemas"]["PickupWindow"][];
            /** @description Typical hours from order to ready for pickup */
            readyHours: number;
        };
        CustomerPickupRequest: {
            enabled: boolean;
            /** @description At least one window to enable */
            hours: components["schemas"]["PickupWindow"][];
            readyHours?: number;
        };
        GeoPosition: {
            lat: number;
            lng: number;
            /**
             * @description ADDRESS = street level; ZIP = ZIP centroid only (not enough for a driver)
             * @enum {string}
             */
            precision: "ADDRESS" | "ZIP" | "POINT";
            /** Format: date-time */
            geocodedAt: string | null;
        };
        PickupWindow: {
            /** @enum {string} */
            day: "MONDAY" | "TUESDAY" | "WEDNESDAY" | "THURSDAY" | "FRIDAY" | "SATURDAY" | "SUNDAY";
            /** @description 24-hour local time */
            opens: string;
            closes: string;
        };
        /** @description AfroTransact Delivery from this address: the seller packs, the nearest AfroTransact driver collects during the pickup hours */
        AfrotransactPickup: {
            enabled: boolean;
            hours: components["schemas"]["PickupWindow"][];
            /** @description Delivery zone covering (or nearest to) the address */
            zoneName: string | null;
            /** Format: date-time */
            enabledAt: string | null;
        };
        AfrotransactPickupRequest: {
            enabled: boolean;
            /** @description At least one window to enable */
            hours: components["schemas"]["PickupWindow"][];
            /**
             * Format: int64
             * @description Version being edited
             */
            version?: number | null;
        };
        SellerOffer: {
            offerId: string;
            /** @description Variant ATIN */
            atin: string;
            productId: string;
            sellerSku: string;
            condition: string;
            priceMinor: number;
            currency: string;
            fulfilment: components["schemas"]["Fulfilment"];
            /** @description Seller-declared stock (SELLER and AFROTRANSACT_DELIVERY) */
            quantity: number;
            availableQty: number;
            /** @description Pickup location (AFROTRANSACT_DELIVERY) */
            shipFromLocationId: string | null;
            /** @example Houston, TX */
            shipsFrom: string | null;
            /** @enum {string} */
            status: "ACTIVE" | "PAUSED" | "WITHDRAWN";
            /** Format: int64 */
            version: number;
        };
        SellerOfferList: {
            items: components["schemas"]["SellerOffer"][];
        };
        OfferFulfilmentRequest: {
            fulfilment: components["schemas"]["Fulfilment"];
            /** @description Required for AFROTRANSACT_DELIVERY */
            shipFromLocationId?: string | null;
        };
        /** @description AfroTransact Delivery from the seller's store (S255), as quoted at checkout */
        LocalDelivery: {
            pickupLocationId: string | null;
            roadMiles: number | null;
            durationMinutes: number | null;
            /** @description What the shopper paid for delivery (AfroTransact keeps it) */
            customerChargeMinor: number;
            /** @description The seller delivery fee deducted from the payout */
            sellerFeeMinor: number;
        };
        /** @description The organisation's row of the order's payment split (payments); deductions are positive amounts */
        OrderEarnings: {
            itemsMinor: number;
            shippingMinor: number;
            commissionMinor: number;
            fbaFeesMinor: number;
            /** @description AfroTransact Delivery seller fees */
            deliveryFeesMinor: number;
            stripeFeeMinor: number;
            netMinor: number;
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
            /** @description AfroTransact Delivery from the seller's store (S255); null for other shipments */
            localDelivery: components["schemas"]["LocalDelivery"] | null;
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
            /** @description Payout breakdown from the order's payment split; null until the payment is split (or when payments can't be read) */
            earnings: components["schemas"]["OrderEarnings"] | null;
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
            /** @description The applied sort (`<column>,asc|desc`); empty for the catalog order */
            sort: string[];
            /** @description The search or sort covered the first 5,000 listings only */
            partial: boolean;
        };
        /**
         * @description csv: the data only (CSV cannot carry charts). xlsx: a "Summary" sheet (first) with key totals and native Excel
         *     charts, plus a "Data" sheet. pdf: a summary page with the same charts and totals, then the table in landscape
         *     with the header repeated on every page. Charts follow the column kinds: rows by status, the first amount (or rows)
         *     by day or month, the top 10 rows by amount, rows by a low-cardinality text column.
         * @enum {string}
         */
        ExportFormat: "csv" | "xlsx" | "pdf";
        ExportColumn: {
            key: string;
            label: string;
            /** @enum {string} */
            kind: "text" | "strong" | "mono" | "money" | "number" | "date" | "datetime" | "status" | "percent";
        };
        /** @description A client-side table exported as shown, through the same pipeline. */
        ExportDataset: {
            /** @description The table's key (file name) */
            table: string;
            title?: string;
            scope?: string;
            columns: components["schemas"]["ExportColumn"][];
            /** @description One array per row, one cell per column; money in currency units, dates as ISO strings. */
            rows: (string | number | boolean | null)[][];
        };
        /** @description A server table (`table`) with its search, sort, visible columns and optional selection, or a client-side table (`dataset`). */
        SellerExportRequest: {
            /** @enum {string} */
            table?: "orders" | "listings";
            format: components["schemas"]["ExportFormat"];
            q?: string;
            status?: string;
            sort?: string[];
            /** @description Only these rows: shipment ids for orders, product ids for listings */
            ids?: string[];
            /** @description The visible columns in order */
            columns?: string[];
            dataset?: components["schemas"]["ExportDataset"];
        };
        ExportJob: {
            id: string;
            /** @description The table exported */
            resource: string;
            title: string;
            format: components["schemas"]["ExportFormat"];
            /** @enum {string} */
            status: "QUEUED" | "RUNNING" | "READY" | "FAILED" | "EXPIRED";
            rows: number;
            /** @description Built in the background (more than 5,000 rows) */
            async: boolean;
            fileName: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            finishedAt: string | null;
            /** Format: date-time */
            expiresAt: string | null;
            /** @description App-relative download path (redirects to the signed URL) */
            downloadUrl: string | null;
            error: string | null;
        };
        ExportJobList: {
            items: components["schemas"]["ExportJob"][];
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
            /** @description The seller delivery fee of AfroTransact Delivery from your store (S255); null when the version has no delivery pricing */
            deliveryFee: components["schemas"]["SellerDeliveryFee"] | null;
        };
        SellerDeliveryTariff: {
            /** @enum {string} */
            mode: "FLAT" | "DISTANCE";
            flatPerPackageMinor: number | null;
            baseMinor: number | null;
            perMileMinor: number | null;
            bands: {
                fromMiles: number;
                toMiles: number;
                feeMinor: number;
            }[];
            minMinor: number | null;
            maxMinor: number | null;
        };
        SellerDeliveryFee: {
            /** @description AfroTransact Delivery from seller stores is offered */
            enabled: boolean;
            maxRadiusMiles: number;
            /**
             * @description OVERRIDE: negotiated for this organisation; RETAIL: never charged
             * @enum {string}
             */
            source: "DEFAULT" | "OVERRIDE" | "RETAIL";
            /** @description null = no fee */
            tariff: components["schemas"]["SellerDeliveryTariff"] | null;
            /** @example $2.00 per package */
            summary: string;
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
         *     3). Empty cells sort last in both directions.
         */
        Sort: string[];
        ExportId: string;
        OrderNumber: string;
        ShipmentId: string;
        ProductId: string;
        PaymentMethodId: string;
        /** @description Makes retries safe (same key = same result). */
        IdempotencyKey: string;
        /** @description Same key = same label; never buys twice. */
        IdempotencyKeyRequired: string;
        /** @description The version from the ETag (optimistic locking). */
        IfMatch: string;
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
    startOnboarding: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StartOnboardingRequest"];
            };
        };
        responses: {
            /** @description Existing application resumed */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OnboardingStatus"];
                };
            };
            /** @description Application started */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OnboardingStatus"];
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
    saveOnboardingBusiness: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OnboardingBusinessInput"];
            };
        };
        responses: {
            /** @description Saved */
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
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    saveOnboardingDetails: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OnboardingDetailsInput"];
            };
        };
        responses: {
            /** @description Saved */
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
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    saveOnboardingOwners: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OnboardingOwnersInput"];
            };
        };
        responses: {
            /** @description Saved */
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
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    saveOnboardingPlan: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OnboardingPlanInput"];
            };
        };
        responses: {
            /** @description Saved */
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
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    saveOnboardingCompliance: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OnboardingComplianceInput"];
            };
        };
        responses: {
            /** @description Saved */
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
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getOnboardingPlans: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Plans */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OnboardingPlans"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createOnboardingStripeSession: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Account Session */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OnboardingStripeSession"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    refreshOnboardingStripe: {
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
            409: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    submitOnboarding: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Submitted */
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
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    requestOnboardingUpload: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OnboardingUploadRequest"];
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
    completeOnboardingUpload: {
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
    createSellerStripeSession: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Account Session */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OnboardingStripeSession"];
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
                /** @description Search over every column */
                q?: string;
                /**
                 * @description Sort column and direction, `<column>,asc|desc` (the Spring Data convention); repeat for secondary keys (at most
                 *     3). Empty cells sort last in both directions.
                 */
                sort?: components["parameters"]["Sort"];
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
    setAfrotransactPickup: {
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
                "application/json": components["schemas"]["AfrotransactPickupRequest"];
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
            503: components["responses"]["Problem"];
        };
    };
    setCustomerPickup: {
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
                "application/json": components["schemas"]["CustomerPickupRequest"];
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
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listSellerOffers: {
        parameters: {
            query: {
                productId: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Offers */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SellerOfferList"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    setOfferFulfilment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                offerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["OfferFulfilmentRequest"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SellerOffer"];
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
    listListings: {
        parameters: {
            query?: {
                status?: components["schemas"]["ListingStatus"];
                /** @description Search over every column */
                q?: string;
                /**
                 * @description Sort column and direction, `<column>,asc|desc` (the Spring Data convention); repeat for secondary keys (at most
                 *     3). Empty cells sort last in both directions.
                 */
                sort?: components["parameters"]["Sort"];
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
    listSellerExports: {
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
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createSellerExport: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SellerExportRequest"];
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
            422: components["responses"]["ValidationProblem"];
            502: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getSellerExport: {
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
    downloadSellerExport: {
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
    getSellerExportFile: {
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
    getSellerFbaNodes: {
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
    getSellerFbaInventory: {
        parameters: {
            query?: {
                nodeId?: string;
                sku?: string;
                stranded?: boolean;
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
    getSellerFbaInbound: {
        parameters: {
            query?: {
                nodeId?: string;
                status?: string;
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
    postSellerFbaInbound: {
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
    getSellerFbaInboundById: {
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
    putSellerFbaInboundById: {
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
    putSellerFbaInboundTracking: {
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
    postSellerFbaInboundShip: {
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
    postSellerFbaInboundCancel: {
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
    getSellerFbaInboundLabels: {
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
    getSellerFbaLots: {
        parameters: {
            query?: {
                sku?: string;
                nodeId?: string;
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
    getSellerFbaLotsMarkdown: {
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
    getSellerFbaLotsById: {
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
}
