// GENERATED from contracts/openapi/gateway-bff.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/api-client generate
/* eslint-disable */

export interface paths {
    "/v1/site-config": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Site configuration (social links, contacts, legal versions, identity providers, locales)
         * @description Published from Backoffice → Site content & legal; changes need no deployment. Cached with ETag.
         */
        get: operations["getSiteConfig"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/home": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Home page placements for the session's deliver-to location
         * @description Announcement bar, hero carousel, tiles, sponsored banners and product rails. Nothing is hard-coded in clients.
         */
        get: operations["getHome"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/newsletter/subscriptions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Subscribe to the weekly newsletter (double opt-in) */
        post: operations["subscribeNewsletter"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/delivery-location": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Current deliver-to location of the session */
        get: operations["getDeliveryLocation"];
        /**
         * Change the deliver-to location by ZIP or device coordinates (updates every delivery promise)
         * @description Coordinates come from "Use my current location" and are reverse-geocoded to a ZIP by the geo service; they are not stored.
         */
        put: operations["setDeliveryLocation"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/geo/address-suggestions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Address autocomplete (geo service — Google Places or OpenStreetMap Nominatim) */
        get: operations["suggestAddresses"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Search products with filters, facet counts and delivery promises
         * @description Every typed word must match (prefix, synonym or typo tolerance). Facet counts for a dimension are computed
         *     with every other active filter applied (disjunctive faceting). Sponsored placements that match the query
         *     are labelled with `sponsor`.
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
    "/v1/search/suggestions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Typeahead — completed phrases (first scoped to a department) and up to five products */
        get: operations["suggest"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/products/{atin}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Product detail with buy box, other offers, availability and delivery promise */
        get: operations["getProduct"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/products/{atin}/stock-alerts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * "Email me when available" for an out-of-stock product
         * @description Signed-in customers may omit the email; guests must provide one.
         */
        post: operations["createStockAlert"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/cart": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Current cart (guest or customer), one package per seller and fulfilment method */
        get: operations["getCart"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/cart/items": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Add an offer to the cart (merged into an existing line for the same offer) */
        post: operations["addToCart"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/cart/items/{lineId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                lineId: components["parameters"]["LineId"];
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Remove a line */
        delete: operations["removeCartLine"];
        options?: never;
        head?: never;
        /** Change a line's quantity and/or whether it is selected for checkout */
        patch: operations["updateCartLine"];
        trace?: never;
    };
    "/v1/cart/selection": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Select or deselect every line for checkout */
        put: operations["selectAllCartLines"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/cart/items/{lineId}/save-for-later": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Move a line to "Saved for later" */
        post: operations["saveForLater"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/cart/saved/{savedId}/move-to-cart": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Move a saved item back to the cart (quantity 1, subject to stock) */
        post: operations["moveToCart"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/cart/saved/{savedId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Delete a saved item */
        delete: operations["removeSaved"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Who is signed in (guest sessions return authenticated=false) */
        get: operations["getMe"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Redirect to identity's hosted login (authorization code + PKCE) */
        get: operations["startHostedLogin"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/oidc/{provider}/authorize": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Start sign-in with an external identity provider (only providers enabled in site config)
         * @description Generates `code_verifier`, `state` and `nonce`, stores them in the session and redirects to
         *     `{identity}/oauth2/authorize?response_type=code&client_id=storefront-bff&scope=openid%20email%20profile&code_challenge=…&code_challenge_method=S256&state=…&nonce=…&idp_hint={provider}`.
         */
        get: operations["startSocialLogin"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/oidc/callback": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Authorization-code callback — exchanges the code (with the PKCE verifier), creates the session, redirects to returnTo */
        get: operations["completeLogin"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/sign-in": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Email + password sign-in (step 1); usually continues with two-step verification */
        post: operations["signIn"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/mfa/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Two-step verification code (authenticator app or SMS) */
        post: operations["verifyMfa"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/sign-up": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Create an account (same fields for customers, sellers and providers — role is decided server-side)
         * @description Sends a 6-digit code to the email address; the account is created when the code is verified.
         */
        post: operations["signUp"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/email-verification/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Verify the sign-up email code; signs the new customer in */
        post: operations["verifyEmail"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/email-verification/resend": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Resend the sign-up code */
        post: operations["resendEmailCode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/passkey/options": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** WebAuthn assertion options for "Sign in with a passkey" (discoverable credentials) */
        post: operations["passkeyOptions"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/passkey/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Verify a WebAuthn assertion and sign in */
        post: operations["passkeyVerify"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/sign-out": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** End the session (revokes refresh tokens at identity) */
        post: operations["signOut"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/addresses": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Saved delivery addresses of the signed-in customer */
        get: operations["listAddresses"];
        put?: never;
        /** Add an address (validated and standardised by the geo service) */
        post: operations["createAddress"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/addresses/{addressId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                addressId: string;
            };
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Remove a saved address */
        delete: operations["deleteAddress"];
        options?: never;
        head?: never;
        /** Edit a saved address (re-validated by geo) */
        patch: operations["updateAddress"];
        trace?: never;
    };
    "/v1/account/addresses/{addressId}/default": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Make an address the default for checkout */
        post: operations["setDefaultAddress"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/payment-methods": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Saved Stripe payment methods (brand, last 4, expiry only) */
        get: operations["listPaymentMethods"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/payment-methods/setup-intents": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Stripe SetupIntent client secret for the Payment Element (card, US bank account, Cash App Pay) */
        post: operations["createSetupIntent"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/payment-methods/{paymentMethodId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Detach a payment method from the Stripe customer */
        delete: operations["deletePaymentMethod"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Orders of the signed-in customer, newest first */
        get: operations["listOrders"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/orders/{orderNumber}/shipments/{shipmentId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Cancel a shipment that has not shipped yet (releases the payment authorisation for it) */
        post: operations["cancelShipment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/returns": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Returns of the signed-in customer */
        get: operations["listReturns"];
        put?: never;
        /** Start a return for a delivered, returnable item */
        post: operations["startReturn"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/checkout/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Checkout view of the selected cart lines (addresses, payment methods, per-package delivery options, tax) */
        get: operations["getCheckoutSummary"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/checkout/orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Place the order (orders saga — payment authorisation, stock reservation, shipment split)
         * @description Requires `Idempotency-Key`. Replaying the same key with the same body returns the original result (201);
         *     the same key with a different body fails with 409 `IDEMPOTENCY_CONFLICT`. The payment is authorised now
         *     and captured per shipment when it ships.
         */
        post: operations["placeOrder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/careers/jobs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Open roles (careers page) */
        get: operations["listJobs"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/careers/jobs/{jobId}/applications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Apply for a role (résumé uploaded beforehand through the media service) */
        post: operations["applyToJob"];
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
             * @description Stable machine code: VALIDATION_FAILED, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, PRODUCT_NOT_FOUND, OFFER_NOT_FOUND,
             *     LINE_NOT_FOUND, SAVED_NOT_FOUND, STOCK_LIMIT, OUT_OF_STOCK, INVALID_CREDENTIALS, INVALID_CODE, CHALLENGE_EXPIRED,
             *     EMAIL_TAKEN, WEAK_PASSWORD, PROVIDER_DISABLED, ZIP_NOT_SERVICEABLE, PAYMENT_DECLINED, IDEMPOTENCY_CONFLICT,
             *     STOCK_CHANGED, RATE_LIMITED, UPSTREAM_UNAVAILABLE.
             */
            code?: string;
            errors?: {
                [key: string]: string;
            };
        } & {
            [key: string]: unknown;
        };
        StockProblem: components["schemas"]["Problem"] & {
            available: number;
            inCart: number;
            requested: number;
            maxQuantity?: number;
        };
        /** @description AfroTransact product identifier (one per product and per variant). */
        Atin: string;
        /**
         * @description AFROTRANSACT = "Fulfilled by AfroTransact"; SELLER = "Ships from seller".
         * @enum {string}
         */
        Fulfilment: "AFROTRANSACT" | "SELLER";
        /**
         * @description PLATFORM = sold by AfroTransact Retail (first party); SELLER = independent seller.
         * @enum {string}
         */
        SellerKind: "PLATFORM" | "SELLER";
        /**
         * @description LOW_STOCK when 1–5 units are available.
         * @enum {string}
         */
        StockStatus: "IN_STOCK" | "LOW_STOCK" | "OUT_OF_STOCK";
        /** @enum {string} */
        PriceBucket: "u10" | "10-25" | "25-50" | "o50";
        /**
         * @default relevance
         * @enum {string}
         */
        SearchSort: "relevance" | "price_asc" | "price_desc" | "rating" | "fastest";
        /** @enum {string} */
        IdentityProviderId: "google" | "apple" | "facebook";
        DeliveryPromise: {
            /** @description Business days to the deliver-to ZIP (0 = today) */
            days: number;
            /** @example Tomorrow, Oct 11 */
            label: string;
            /**
             * @example FREE delivery
             * @example Delivery
             */
            prefix: string;
            free: boolean;
            feeMinor: number;
            expressLabel: string | null;
            /** @example Order within 4 hrs 12 mins. */
            cutoff: string | null;
        };
        Seller: {
            id: string;
            name: string;
            kind: components["schemas"]["SellerKind"];
            city: string;
        };
        Offer: {
            offerId: string;
            seller: components["schemas"]["Seller"];
            priceMinor: number;
            listPriceMinor: number | null;
            currency: string;
            fulfilment: components["schemas"]["Fulfilment"];
            availableQty: number;
            stockStatus: components["schemas"]["StockStatus"];
            delivery: components["schemas"]["DeliveryPromise"];
        };
        /** @description Product card data (search hit, rail item, typeahead product). Superset of the search service Hit. */
        ProductSummary: {
            atin: components["schemas"]["Atin"];
            title: string;
            brand: string;
            community: string;
            department: string;
            unit: string;
            rating: number;
            reviewCount: number;
            imageUrl: string | null;
            /** @description Buy-box offer */
            offerId: string;
            priceMinor: number;
            listPriceMinor: number | null;
            currency: string;
            sellerName: string;
            sellerKind: components["schemas"]["SellerKind"];
            sellerCity: string;
            fulfilment: components["schemas"]["Fulfilment"];
            availableQty: number;
            inStock: boolean;
            lowStock: boolean;
            stockStatus: components["schemas"]["StockStatus"];
            delivery: components["schemas"]["DeliveryPromise"];
            offerCount: number;
            minOfferPriceMinor: number;
            sponsor: {
                advertiser: string;
            } | null;
        };
        VariantOption: {
            atin: components["schemas"]["Atin"];
            label: string;
            selected: boolean;
            available: boolean;
            priceMinor: number;
        };
        ProductDetail: {
            product: {
                atin: components["schemas"]["Atin"];
                title: string;
                brand: string;
                community: string;
                department: string;
                unit: string;
                origin: string;
                bullets: string[];
                rating: number;
                reviewCount: number;
                images: {
                    url: string | null;
                    alt: string;
                }[];
                variants: components["schemas"]["VariantOption"][];
            };
            /** @description Winning offer, or null when the product is currently unavailable */
            buyBox: components["schemas"]["Offer"] | null;
            otherOffers: components["schemas"]["Offer"][];
            shipsFrom: string;
            soldBy: string;
            returnsPolicy: string;
            unitPriceLabel: string;
        };
        StockAlertRequest: {
            /** Format: email */
            email?: string;
        };
        StockAlert: {
            atin: components["schemas"]["Atin"];
            /** @enum {string} */
            status: "subscribed";
        };
        FacetBucket: {
            value: string;
            label: string;
            count: number;
        };
        SearchPage: {
            hits: components["schemas"]["ProductSummary"][];
            total: number;
            page: number;
            size: number;
            pageCount: number;
            facets: {
                department: components["schemas"]["FacetBucket"][];
                community: components["schemas"]["FacetBucket"][];
                fulfilment: components["schemas"]["FacetBucket"][];
                sellerKind: components["schemas"]["FacetBucket"][];
                rating: components["schemas"]["FacetBucket"][];
                price: components["schemas"]["FacetBucket"][];
                delivery: components["schemas"]["FacetBucket"][];
            };
            deliverToLabel: string;
            tookMs: number;
        };
        Suggestions: {
            query: string;
            /** @description Completed phrases; the first may be scoped to the top product's department. */
            phrases: {
                text: string;
                department: string | null;
            }[];
            products: components["schemas"]["ProductSummary"][];
        };
        CartLine: {
            lineId: string;
            atin: components["schemas"]["Atin"];
            offerId: string;
            title: string;
            unit: string;
            department: string;
            imageUrl: string | null;
            priceMinor: number;
            lineTotalMinor: number;
            quantity: number;
            /** @description min(available stock, 99) */
            maxQuantity: number;
            availableQty: number;
            stockStatus: components["schemas"]["StockStatus"];
            selected: boolean;
        };
        DeliveryOption: {
            /** @enum {string} */
            key: "carrier" | "express" | "afrotransact_delivery" | "pickup";
            label: string;
            eta: string;
            feeMinor: number;
            free: boolean;
        };
        CartPackage: {
            /** @description {sellerId}:{fulfilment} */
            packageId: string;
            seller: components["schemas"]["Seller"];
            fulfilment: components["schemas"]["Fulfilment"];
            heading: string;
            shipsFrom: string;
            delivery: components["schemas"]["DeliveryPromise"];
            deliveryOptions: components["schemas"]["DeliveryOption"][];
            lines: components["schemas"]["CartLine"][];
            itemCount: number;
            subtotalMinor: number;
            freeShippingNudge: {
                remainingMinor: number;
                thresholdMinor: number;
            } | null;
        };
        SavedItem: {
            savedId: string;
            atin: components["schemas"]["Atin"];
            offerId: string;
            title: string;
            imageUrl: string | null;
            priceMinor: number;
            available: boolean;
        };
        Cart: {
            cartId: string;
            currency: string;
            packages: components["schemas"]["CartPackage"][];
            saved: components["schemas"]["SavedItem"][];
            itemCount: number;
            selectedCount: number;
            subtotalMinor: number;
            allSelected: boolean;
            sellerCount: number;
        };
        AddToCartRequest: {
            offerId: string;
            quantity: number;
        };
        UpdateCartLineRequest: {
            quantity?: number;
            selected?: boolean;
        };
        /** @enum {string} */
        Tone: "ink" | "gold" | "green" | "sand";
        Announcement: {
            id: string;
            tone: components["schemas"]["Tone"];
            icon: string;
            text: string;
            cta: string;
            href: string;
        };
        HeroSlide: {
            id: string;
            theme: components["schemas"]["Tone"];
            kicker: string;
            title: string;
            sub: string;
            cta: string;
            href: string;
            /** @description "AfroTransact" or "Sponsored" */
            label: string;
            imageUrl: string | null;
        };
        Tile: {
            id: string;
            title: string;
            cta: string;
            href: string;
            cells: {
                label: string;
                href: string;
                imageUrl: string | null;
            }[];
        };
        Banner: {
            id: string;
            slot: string;
            advertiser: string;
            title: string;
            sub: string;
            cta: string;
            href: string;
            imageUrl: string | null;
        };
        Rail: {
            id: string;
            title: string;
            href: string;
            items: components["schemas"]["ProductSummary"][];
        };
        Home: {
            announcements: components["schemas"]["Announcement"][];
            hero: components["schemas"]["HeroSlide"][];
            tiles: components["schemas"]["Tile"][];
            banners: components["schemas"]["Banner"][];
            rails: components["schemas"]["Rail"][];
            deliverTo: components["schemas"]["DeliveryLocation"];
        };
        DeliveryLocation: {
            zip: string;
            city: string;
            state: string;
            /** @example Houston 77042 */
            label: string;
            serviceable: boolean;
        };
        PostalAddress: {
            line1: string;
            line2: string;
            city: string;
            state: string;
            zip: string;
            country: string;
            lat: number | null;
            lon: number | null;
        };
        AddressSuggestions: {
            suggestions: {
                id: string;
                label: string;
                secondary: string;
                address: components["schemas"]["PostalAddress"];
                /** @enum {string} */
                source: "google" | "osm" | "fixture";
            }[];
            /** @description Required attribution text (e.g. © OpenStreetMap contributors) */
            attribution: string;
        };
        SiteConfig: {
            revision: number;
            /** @description Enabled social profiles only, in display order. */
            socials: {
                /** @enum {string} */
                network: "tiktok" | "instagram" | "facebook" | "x" | "youtube" | "linkedin" | "whatsapp";
                /** Format: uri */
                url: string;
                label: string;
            }[];
            contacts: {
                phone: string;
                privacyEmail: string;
                accessibilityEmail: string;
                legalEmail: string;
                pressEmail: string;
                partnersEmail: string;
                address: string;
            };
            legal: {
                key: string;
                title: string;
                version: string;
                /** Format: date */
                effective: string;
                href: string;
            }[];
            identityProviders: {
                id: components["schemas"]["IdentityProviderId"];
                name: string;
                enabled: boolean;
            }[];
            locales: {
                code: string;
                name: string;
                /** @enum {string} */
                dir: "ltr" | "rtl";
            }[];
            market: {
                code: string;
                currency: string;
                country: string;
            };
            appLinks: {
                ios: string | null;
                android: string | null;
            };
            paymentMethods: string[];
            smsProgramName: string;
        };
        NewsletterRequest: {
            /** Format: email */
            email: string;
        };
        NewsletterResponse: {
            /** @enum {string} */
            status: "pending_confirmation" | "subscribed";
        };
        Me: {
            authenticated: boolean;
            user: {
                id: string;
                firstName: string;
                lastName: string;
                email: string;
                initials: string;
                roles: string[];
            } | null;
            /** @description Badges for the account menu. */
            counts: {
                orders: number;
                returns: number;
                addresses: number;
                paymentMethods: number;
            } | null;
        };
        SignInRequest: {
            /** Format: email */
            email: string;
            password: string;
            returnTo?: string;
        };
        SignUpRequest: {
            firstName: string;
            lastName: string;
            /** Format: email */
            email: string;
            /** @description E.164 mobile number */
            phone: string;
            /** @description Upper and lower case, a number, not containing the name or email */
            password: string;
            /** @constant */
            termsAccepted: true;
            /** @description Separate, optional SMS opt-in (AfroTransact Alerts) */
            smsConsent: boolean;
            returnTo?: string;
        };
        VerifyCodeRequest: {
            challengeId: string;
            code: string;
            trustDevice?: boolean;
        };
        ResendResponse: {
            resendAfterSeconds: number;
        };
        AuthResult: {
            /** @constant */
            status: "authenticated";
            redirectTo: string;
        } | {
            /** @constant */
            status: "mfa_required";
            challengeId: string;
            methods: ("totp" | "sms" | "email" | "passkey")[];
            /** @description Masked phone or email */
            destination: string;
        } | {
            /** @constant */
            status: "verification_required";
            challengeId: string;
            destination: string;
            resendAfterSeconds: number;
        };
        PasskeyOptions: {
            challengeId: string;
            /** @description base64url */
            challenge: string;
            rpId: string;
            timeout: number;
            /** @enum {string} */
            userVerification: "required" | "preferred" | "discouraged";
            allowCredentials: {
                id: string;
                /** @constant */
                type: "public-key";
            }[];
        };
        PasskeyAssertion: {
            challengeId: string;
            id: string;
            /** @description base64url */
            rawId: string;
            /** @constant */
            type: "public-key";
            response: {
                clientDataJSON: string;
                authenticatorData: string;
                signature: string;
                userHandle: string | null;
            };
        };
        AddressInput: {
            name: string;
            phone: string;
            line1: string;
            line2?: string;
            city: string;
            state: string;
            zip: string;
            isDefault?: boolean;
            /** @description Keep the address as entered when it can't be USPS-verified */
            acceptUnverified?: boolean;
        };
        Address: {
            id: string;
            name: string;
            phone: string;
            line1: string;
            line2: string;
            city: string;
            state: string;
            zip: string;
            isDefault: boolean;
            verified: boolean;
        };
        AddressPage: {
            items: components["schemas"]["Address"][];
            nextCursor: string | null;
        };
        PaymentMethod: {
            id: string;
            /** @enum {string} */
            type: "card" | "us_bank_account" | "cashapp";
            brand: string | null;
            last4: string | null;
            label: string;
            expires: string | null;
            isDefault: boolean;
        };
        PaymentMethodPage: {
            items: components["schemas"]["PaymentMethod"][];
            nextCursor: string | null;
        };
        OrderSummary: {
            orderNumber: string;
            placedAt: string;
            totalMinor: number;
            currency: string;
            shipTo: string;
            status: string;
            shipments: {
                shipmentId: string;
                label: string;
                status: string;
                items: string;
            }[];
        };
        OrderPage: {
            items: components["schemas"]["OrderSummary"][];
            nextCursor: string | null;
        };
        ReturnSummary: {
            rmaId: string;
            orderNumber: string;
            title: string;
            status: string;
            refundMinor: number;
        };
        ReturnPage: {
            items: components["schemas"]["ReturnSummary"][];
            nextCursor: string | null;
        };
        CheckoutSummary: {
            packages: (components["schemas"]["CartPackage"] & {
                pickupPoints?: {
                    id: string;
                    name: string;
                    address: string;
                    hours: string;
                    ready: string;
                    /** @enum {string} */
                    setBy: "platform" | "seller";
                }[];
            })[];
            addresses: components["schemas"]["Address"][];
            paymentMethods: components["schemas"]["PaymentMethod"][];
            subtotalMinor: number;
            shippingMinor: number;
            taxMinor: number;
            /** @example 8.25% TX */
            taxRateLabel: string;
            totalMinor: number;
            currency: string;
            sellerCount: number;
        };
        PlaceOrderRequest: {
            addressId: string;
            /** @description Saved Stripe payment method; omit when paying with a wallet */
            paymentMethodId?: string;
            /** @enum {string} */
            wallet?: "apple_pay" | "google_pay" | "link";
            gift?: boolean;
            deliverySelections: {
                packageId: string;
                /** @enum {string} */
                option: "carrier" | "express" | "afrotransact_delivery" | "pickup";
                pickupPointId?: string;
                preferences?: {
                    /** @enum {string} */
                    handover?: "hand" | "door" | "desk";
                    safePlace?: string;
                    instructions?: string;
                    /** @enum {string} */
                    contact?: "text" | "call" | "none";
                };
            }[];
        };
        OrderConfirmation: {
            orderNumber: string;
            /** @enum {string} */
            status: "placed" | "requires_action";
            /** @description Present when status is requires_action (3-D Secure) */
            paymentClientSecret?: string | null;
            totalMinor: number;
            currency: string;
            shipments: {
                shipmentId: string;
                label: string;
                eta: string;
                /** @description One-time handover code for AfroTransact Delivery */
                deliveryCode?: string | null;
            }[];
        };
        Job: {
            id: string;
            title: string;
            team: string;
            location: string;
            type: string;
            pay: string;
            summary: string;
            responsibilities: string[];
            requirements: string[];
        };
        /**
         * @description The résumé is uploaded beforehand through the media service; send its asset id as `resumeAssetId` (the content
         *     service requires it and answers 422 `errors.resumeAssetId` otherwise). `resumeFileName` is the original file
         *     name, kept for display.
         */
        JobApplication: {
            name: string;
            /** Format: email */
            email: string;
            /** @description Empty or an https://linkedin.com/ URL */
            linkedin: string;
            /** @enum {string} */
            workAuthorization: "Yes" | "Yes, with sponsorship" | "No";
            /** @description Media-service asset id of the uploaded résumé (PDF) */
            resumeAssetId?: string;
            resumeFileName?: string;
            coverNote?: string;
            /** @constant */
            consent: true;
        };
        ApplicationReceipt: {
            /** @description Application reference issued by the content service */
            reference: string;
            jobId: string;
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
                /**
                 * @example {
                 *       "type": "https://errors.afrotransact.com/validation-failed",
                 *       "title": "Validation failed",
                 *       "status": 422,
                 *       "code": "VALIDATION_FAILED",
                 *       "detail": "One or more fields are invalid.",
                 *       "errors": {
                 *         "email": "Enter a valid email address"
                 *       }
                 *     }
                 */
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description Requested quantity exceeds available stock (STOCK_LIMIT) or the item is out of stock (OUT_OF_STOCK) */
        StockProblem: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                /**
                 * @example {
                 *       "type": "https://errors.afrotransact.com/stock-limit",
                 *       "title": "Not enough stock",
                 *       "status": 409,
                 *       "code": "STOCK_LIMIT",
                 *       "detail": "Only 3 available — 3 already in your cart.",
                 *       "available": 3,
                 *       "inCart": 3,
                 *       "requested": 4,
                 *       "maxQuantity": 3
                 *     }
                 */
                "application/problem+json": components["schemas"]["StockProblem"];
            };
        };
        /** @description Rate limited */
        TooManyRequests: {
            headers: {
                "Retry-After"?: number;
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description The updated cart */
        Cart: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["Cart"];
            };
        };
        /** @description Next step of the authentication flow */
        AuthResult: {
            headers: {
                /** @description Rotated session cookie once authenticated */
                "Set-Cookie"?: string;
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["AuthResult"];
            };
        };
        /** @description Redirect to identity /oauth2/authorize (authorization code + PKCE S256, state, nonce) */
        AuthorizeRedirect: {
            headers: {
                Location?: string;
                [name: string]: unknown;
            };
            content?: never;
        };
    };
    parameters: {
        Atin: components["schemas"]["Atin"];
        LineId: string;
        SavedId: string;
        /** @description Relative storefront path to return to after sign-in (absolute URLs are rejected). */
        ReturnTo: string;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getSiteConfig: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Current configuration */
            200: {
                headers: {
                    ETag?: string;
                    /** @example public, max-age=60, stale-while-revalidate=600 */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SiteConfig"];
                };
            };
            /** @description Not modified */
            304: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getHome: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Placements */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Home"];
                };
            };
        };
    };
    subscribeNewsletter: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NewsletterRequest"];
            };
        };
        responses: {
            /** @description Confirmation email sent */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NewsletterResponse"];
                };
            };
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    getDeliveryLocation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
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
                    "application/json": components["schemas"]["DeliveryLocation"];
                };
            };
        };
    };
    setDeliveryLocation: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    zip: string;
                } | {
                    lat: number;
                    lon: number;
                };
            };
        };
        responses: {
            /** @description Updated location */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["DeliveryLocation"];
                };
            };
            422: components["responses"]["ValidationProblem"];
        };
    };
    suggestAddresses: {
        parameters: {
            query: {
                q: string;
                country?: string;
                /** @description Places autocomplete session token for billing */
                sessionToken?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Up to 5 suggestions */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AddressSuggestions"];
                };
            };
            429: components["responses"]["TooManyRequests"];
        };
    };
    search: {
        parameters: {
            query?: {
                q?: string;
                department?: string;
                community?: string;
                fulfilment?: components["schemas"]["Fulfilment"];
                sellerKind?: components["schemas"]["SellerKind"];
                minRating?: number;
                price?: components["schemas"]["PriceBucket"];
                /** @description Delivery within N business days to the deliver-to ZIP */
                maxDays?: number;
                sort?: components["schemas"]["SearchSort"];
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One page of results */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SearchPage"];
                };
            };
            400: components["responses"]["Problem"];
            502: components["responses"]["Problem"];
        };
    };
    suggest: {
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
            /** @description Suggestions */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Suggestions"];
                };
            };
        };
    };
    getProduct: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                atin: components["parameters"]["Atin"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Product detail */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProductDetail"];
                };
            };
            404: components["responses"]["Problem"];
        };
    };
    createStockAlert: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                atin: components["parameters"]["Atin"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StockAlertRequest"];
            };
        };
        responses: {
            /** @description Subscribed */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["StockAlert"];
                };
            };
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
    getCart: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["Cart"];
        };
    };
    addToCart: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddToCartRequest"];
            };
        };
        responses: {
            200: components["responses"]["Cart"];
            404: components["responses"]["Problem"];
            409: components["responses"]["StockProblem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
    removeCartLine: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                lineId: components["parameters"]["LineId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["Cart"];
            404: components["responses"]["Problem"];
        };
    };
    updateCartLine: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                lineId: components["parameters"]["LineId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateCartLineRequest"];
            };
        };
        responses: {
            200: components["responses"]["Cart"];
            404: components["responses"]["Problem"];
            409: components["responses"]["StockProblem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
    selectAllCartLines: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    selected: boolean;
                };
            };
        };
        responses: {
            200: components["responses"]["Cart"];
        };
    };
    saveForLater: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                lineId: components["parameters"]["LineId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["Cart"];
            404: components["responses"]["Problem"];
        };
    };
    moveToCart: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                savedId: components["parameters"]["SavedId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["Cart"];
            404: components["responses"]["Problem"];
            409: components["responses"]["StockProblem"];
        };
    };
    removeSaved: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                savedId: components["parameters"]["SavedId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["Cart"];
            404: components["responses"]["Problem"];
        };
    };
    getMe: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Session principal and account menu counts */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Me"];
                };
            };
        };
    };
    startHostedLogin: {
        parameters: {
            query?: {
                /** @description Relative storefront path to return to after sign-in (absolute URLs are rejected). */
                returnTo?: components["parameters"]["ReturnTo"];
                prompt?: "login" | "create";
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            302: components["responses"]["AuthorizeRedirect"];
        };
    };
    startSocialLogin: {
        parameters: {
            query?: {
                /** @description Relative storefront path to return to after sign-in (absolute URLs are rejected). */
                returnTo?: components["parameters"]["ReturnTo"];
            };
            header?: never;
            path: {
                provider: components["schemas"]["IdentityProviderId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            302: components["responses"]["AuthorizeRedirect"];
            404: components["responses"]["Problem"];
        };
    };
    completeLogin: {
        parameters: {
            query: {
                code: string;
                state: string;
                error?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Signed in; redirect to the original returnTo (or /signin?error=… on failure) */
            302: {
                headers: {
                    Location?: string;
                    /** @description at_session=…; HttpOnly; Secure; SameSite=Lax; Path=/ */
                    "Set-Cookie"?: string;
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    signIn: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SignInRequest"];
            };
        };
        responses: {
            200: components["responses"]["AuthResult"];
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    verifyMfa: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["VerifyCodeRequest"];
            };
        };
        responses: {
            200: components["responses"]["AuthResult"];
            410: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    signUp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SignUpRequest"];
            };
        };
        responses: {
            201: components["responses"]["AuthResult"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    verifyEmail: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["VerifyCodeRequest"];
            };
        };
        responses: {
            200: components["responses"]["AuthResult"];
            410: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
    resendEmailCode: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    challengeId: string;
                };
            };
        };
        responses: {
            /** @description Sent */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ResendResponse"];
                };
            };
            410: components["responses"]["Problem"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    passkeyOptions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": Record<string, never>;
            };
        };
        responses: {
            /** @description PublicKeyCredentialRequestOptions (base64url-encoded binary fields) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PasskeyOptions"];
                };
            };
        };
    };
    passkeyVerify: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasskeyAssertion"];
            };
        };
        responses: {
            200: components["responses"]["AuthResult"];
            401: components["responses"]["Problem"];
            410: components["responses"]["Problem"];
        };
    };
    signOut: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Signed out */
            204: {
                headers: {
                    "Set-Cookie"?: string;
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listAddresses: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Saved addresses */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AddressPage"];
                };
            };
            401: components["responses"]["Problem"];
        };
    };
    createAddress: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddressInput"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Address"];
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
    deleteAddress: {
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
            /** @description Deleted */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
        };
    };
    updateAddress: {
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
                "application/json": components["schemas"]["AddressInput"];
            };
        };
        responses: {
            /** @description Updated */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Address"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
    setDefaultAddress: {
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
            /** @description Now the default */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Address"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
        };
    };
    listPaymentMethods: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
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
                    "application/json": components["schemas"]["PaymentMethodPage"];
                };
            };
            401: components["responses"]["Problem"];
        };
    };
    createSetupIntent: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description SetupIntent */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        clientSecret: string;
                        publishableKey: string;
                    };
                };
            };
            401: components["responses"]["Problem"];
        };
    };
    deletePaymentMethod: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                paymentMethodId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Detached */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
        };
    };
    listOrders: {
        parameters: {
            query?: {
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Orders, newest first */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderPage"];
                };
            };
            401: components["responses"]["Problem"];
        };
    };
    cancelShipment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: string;
                shipmentId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Updated order */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderSummary"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
        };
    };
    listReturns: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Returns */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReturnPage"];
                };
            };
            401: components["responses"]["Problem"];
        };
    };
    startReturn: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    orderNumber: string;
                    atin: components["schemas"]["Atin"];
                    /** @enum {string} */
                    reason: "DAMAGED" | "WRONG_ITEM" | "NOT_AS_DESCRIBED" | "NO_LONGER_NEEDED";
                };
            };
        };
        responses: {
            /** @description Return started */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReturnSummary"];
                };
            };
            401: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
    getCheckoutSummary: {
        parameters: {
            query?: {
                addressId?: string;
            };
            header?: never;
            path?: never;
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
                    "application/json": components["schemas"]["CheckoutSummary"];
                };
            };
            401: components["responses"]["Problem"];
            409: components["responses"]["StockProblem"];
        };
    };
    placeOrder: {
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
                "application/json": components["schemas"]["PlaceOrderRequest"];
            };
        };
        responses: {
            /** @description Order placed */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderConfirmation"];
                };
            };
            /** @description Further action required (3-D Secure); confirm with Stripe.js and poll the order */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderConfirmation"];
                };
            };
            401: components["responses"]["Problem"];
            402: components["responses"]["Problem"];
            409: components["responses"]["StockProblem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
    listJobs: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Open roles */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        jobs: components["schemas"]["Job"][];
                    };
                };
            };
        };
    };
    applyToJob: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                jobId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["JobApplication"];
            };
        };
        responses: {
            /** @description Application received */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ApplicationReceipt"];
                };
            };
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
        };
    };
}
