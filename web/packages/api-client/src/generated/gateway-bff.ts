// GENERATED from contracts/openapi/gateway-bff.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/api-client generate
/* eslint-disable */

export interface paths {
    "/v1/maintenance": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Whether the platform is in maintenance, and what to tell customers
         * @description Always answered, even in maintenance. Web and mobile apps read it to show the maintenance page. While `active`, every other customer route (and Seller Center's when `scope` is ALL) answers 503 `MAINTENANCE` with `Retry-After`; sign-in and staff routes keep working. Switched in the staff console under Settings → General → Maintenance mode.
         */
        get: operations["getMaintenance"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
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
    "/v1/legal/{key}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * A legal document (current version, or an archived one with `version`), placeholders filled in
         * @description Keys: conditions-of-use, privacy, cookies, privacy-choices, returns, seller-terms, prohibited-items,
         *     accessibility, sms-terms. English is binding; other locales fall back to English with
         *     `translationFallback: true`. Public and cacheable.
         */
        get: operations["getLegalDocument"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/help/articles": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Published help articles (the hub excluded), by topic and text, a page at a time
         * @description Load the next page with `cursor` = the previous page's `nextCursor` (infinite scroll). Public and cacheable.
         */
        get: operations["listHelpArticles"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/help/articles/{slug}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A published help page (`help-center` is the hub at /help) */
        get: operations["getHelpArticle"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/consent/cookie-policy": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Cookie categories and the cookie table (banner, preferences and the Cookie Notice) */
        get: operations["getCookiePolicy"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/consent/records": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Store the visitor's cookie choice (auditable; GPC and "Do not sell or share" force advertising off)
         * @description The subject is the signed-in user or the session's anonymous id (never chosen by the client). The browser's
         *     `Sec-GPC: 1` header is forwarded; required categories are always on.
         */
        post: operations["recordConsent"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/privacy/requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Make a privacy request (access, delete, correct, opt out); answered within 45 days
         * @description A signed-in customer's account is attached to the request; identity is verified by email.
         */
        post: operations["submitPrivacyRequest"];
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
        /**
         * Address autocomplete (geo service — Google Places (New) with a key, else OpenStreetMap Nominatim)
         * @description One call per keystroke of an address field. The first answer carries a `sessionToken`; send it with every following keystroke of the same address entry and with `GET /v1/geo/address-suggestions/{id}` for the picked suggestion, then start a new entry without one (Google Places bills the keystrokes and the Place Details call as one session). Google suggestions come back with `resolved: false` (street line only) and are resolved when picked; Nominatim and fixture suggestions are resolved already. Suggestions are restricted to `country`, by default the market's.
         */
        get: operations["suggestAddresses"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/geo/address-suggestions/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Structured address of a picked suggestion (Google Place Details, closing the autocomplete session) */
        get: operations["resolveAddressSuggestion"];
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
         *     with every other active filter applied (disjunctive faceting). Sponsored products (S240) take fixed slots
         *     and carry `sponsor` (advertiser, reason and a click token); a page with ads is `Cache-Control: private,
         *     no-store`. Report clicks with POST /v1/search/clicks using `searchId`.
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
    "/v1/search/clicks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Report a click on a search result (organic or sponsored)
         * @description Counted once per search and product; a sponsored click with a valid, unexpired adToken is charged once per token. Clients fire and forget (navigation never waits on it).
         */
        post: operations["recordSearchClick"];
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
         * @description Arms a back-in-stock alert at the notifications service for the requested variant (the product's first
         *     variant for a product ATIN), with the shopper's own token: signing in is required (401 for guests). The
         *     email defaults to the account's; a typed one overrides it. Subscribing again while the alert is active is a
         *     no-op (still 201).
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
        /**
         * Redirect to identity's hosted sign-in (authorization code + PKCE)
         * @description Every web app (storefront, Seller Center, staff console) signs in here: credentials, two-step verification,
         *     passkeys, social sign-in and account creation all happen on identity's hosted pages, so one identity
         *     session signs the person in to every app (SSO). The callback is the calling app's own
         *     `<origin>/api/bff/v1/auth/oidc/callback` (the session cookie stays on that app's host), chosen from
         *     `returnTo`.
         */
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
         *     `{identity}/oauth2/authorize?response_type=code&client_id=gateway-bff&scope=openid%20email%20profile&code_challenge=…&code_challenge_method=S256&state=…&nonce=…&idp_hint={provider}`.
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
    "/v1/auth/native/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Sign in inside the customer or driver app with email and password
         * @description The native apps show their own sign-in form instead of identity's hosted page. The BFF checks the credentials
         *     with identity's first-party sign-in API and either asks for a second factor (`MFA_REQUIRED`, continue at
         *     `/v1/auth/native/mfa/verify`) or redeems the one-time login ticket for the app's own authorization-code + PKCE
         *     request and returns the authorization `code` (`AUTHENTICATED`). The app exchanges the code with its PKCE
         *     verifier at identity's `/oauth2/token` and receives its own access and refresh tokens. A wrong password is 401
         *     `INVALID_CREDENTIALS` (no token was sent, so the app shows it on the form), repeated failures 429 `RATE_LIMITED`.
         */
        post: operations["nativePasswordSignIn"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/native/mfa/sms": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Text a sign-in code for a second-factor step */
        post: operations["nativeSendSmsCode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/native/mfa/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Verify the second factor and get the authorization code */
        post: operations["nativeVerifySecondFactor"];
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
        /**
         * End the session (revokes the tokens at identity) and get identity's end-session URL
         * @description Clears the `at_session` cookie and revokes the refresh and access tokens. For a web session `redirectTo` is
         *     identity's RP-initiated logout endpoint (`/connect/logout` with `id_token_hint`, `client_id` and the calling
         *     app's registered `post_logout_redirect_uri`, `<origin>/api/bff/v1/auth/signed-out/<app>`, chosen from the
         *     request's `Origin`): navigating there ends the identity session too, so every app is signed out (single
         *     sign-out), and identity then sends the browser to `GET /v1/auth/signed-out/{app}`. Without an ID token to
         *     end (the session had already expired) `redirectTo` is that signed-out URL directly. `redirectTo` is null for
         *     native apps (bearer token).
         */
        post: operations["signOut"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/signed-out/{app}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Where identity returns the browser after single sign-out (each app's registered post-logout URI)
         * @description Registered at identity as the `gateway-bff` client's `post_logout_redirect_uri` for each web app
         *     (`<app origin>/api/bff/v1/auth/signed-out/<app>`). `state` carries the path the person signed out on
         *     (from `SignOutRequest.returnTo`).
         *
         *     - `storefront`: back to that page (or the home page) with `signedOut=1`, where the storefront shows a
         *       "You're signed out" toast. No sign-in wall.
         *     - `seller-center`, `staff-console`: straight to that app's own sign-in, i.e. identity's hosted sign-in page
         *       branded for the app (`prompt=login`, `notice=signed_out`), which returns to the app (that page, or its
         *       home page) after signing in.
         */
        get: operations["signedOut"];
        put?: never;
        post?: never;
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
    "/v1/account/phone/verification": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Text a 6-digit code to the account phone number
         * @description identity `POST /v1/me/phone/verification`. 10-minute expiry; at most one code per 30 seconds and five per hour (429 with `Retry-After`). 422 `PHONE_MISSING` without a phone; 409 `CONFLICT` when already verified.
         */
        post: operations["startPhoneVerification"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/phone/verification/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Confirm the account phone number with the SMS code
         * @description identity `POST /v1/me/phone/verification/confirm`. Wrong code: 422 `INVALID_CODE` on `code`; expired, used, too many attempts or the phone changed since: 410 `CHALLENGE_EXPIRED` (send a new code).
         */
        post: operations["confirmPhoneVerification"];
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
        /**
         * Stripe SetupIntent client secret for the Payment Element (card, US bank account, Cash App Pay)
         * @description payments `POST /v1/customers/me/setup-intents`; `types` defaults to card and US bank account (ACH).
         */
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
        /** Detach a payment method from the Stripe customer (409 for the default method — choose another first) */
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
        /**
         * Cancel a shipment that has not shipped yet (releases the payment authorisation for it)
         * @description orders `POST /v1/orders/{number}/shipments/{id}/cancel` — until the shipment is packed; later 409.
         */
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
        /** Returns of the signed-in customer (all, or one order's) */
        get: operations["listReturns"];
        put?: never;
        /** Start a return for delivered items (tax-returns RMA with label / QR, handover code or refund without return) */
        post: operations["startReturn"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/stock-alerts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** My back-in-stock alerts (active and already sent), newest first */
        get: operations["listStockAlerts"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/stock-alerts/{atin}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Stop a back-in-stock alert for a variant */
        delete: operations["removeStockAlert"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/delivery-runs/locations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Locations with delivery runs (active ones)
         * @description delivery-runs `GET /v1/public/locations`. Public data, cached for a minute.
         */
        get: operations["listRunLocations"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/delivery-runs/locations/{slug}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Location page data (upcoming runs, WhatsApp community link, social and share links)
         * @description delivery-runs `GET /v1/public/locations/{slug}`. `shareLinks` and the run links carry UTM attribution
         *     (`utm_source`, `utm_medium`, `utm_campaign`) so orders and sign-ups can be traced to the channel that shared them.
         */
        get: operations["getRunLocation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/delivery-runs/subscriptions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Get run alerts for a location by email (double opt-in)
         * @description delivery-runs `POST /v1/public/subscriptions {locationId, email, source}`. Always 202 for a valid email (whether
         *     or not it was known). Nothing else is sent until the link in the confirmation email is used
         *     (`POST /v1/delivery-runs/subscriptions/confirm`); only the email channel is consented to this way. A signed-in
         *     shopper chooses channels from the account screen instead (`POST /v1/account/run-subscriptions`). Rate limited
         *     like other lookups.
         */
        post: operations["subscribeRunAlerts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/delivery-runs/subscriptions/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Confirm an email subscription (token of the confirmation link)
         * @description delivery-runs `POST /v1/public/subscriptions/confirm {token}`; the storefront page
         *     `/delivery-runs/subscriptions/confirm?token=` calls it. Confirming twice is fine; an unknown token is a 422 on
         *     `token`. Records the email consent. Rate limited like other lookups.
         */
        post: operations["confirmRunAlerts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/delivery-runs/subscriptions/unsubscribe": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Unsubscribe from a message link (token), per channel or all
         * @description delivery-runs `POST /v1/public/subscriptions/unsubscribe {token, channels}`; the storefront page
         *     `/delivery-runs/subscriptions/unsubscribe?token=` calls it, and every run alert carries such a link. No
         *     channels withdraws all of them. Withdrawal is recorded with its time on the consent record. Rate limited like
         *     other lookups.
         */
        post: operations["unsubscribeRunAlerts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/run-subscriptions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** My delivery alerts (per location and channel, with consent records) */
        get: operations["listMyRunSubscriptions"];
        put?: never;
        /**
         * Turn on delivery alerts for a location and channels (consent recorded, active at once)
         * @description delivery-runs `POST /v1/me/run-subscriptions`. Adds the channels to an existing subscription. SMS and WhatsApp
         *     also need a verified phone number on the account; WhatsApp messages are only sent from approved templates to
         *     shoppers who opted in here.
         */
        post: operations["subscribeMyRunAlerts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/run-subscriptions/{locationId}": {
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
         * Turn off delivery alerts for a location (some channels, or all)
         * @description delivery-runs `DELETE /v1/me/run-subscriptions/{locationId}`. No `channel` withdraws all channels.
         */
        delete: operations["unsubscribeMyRunAlerts"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/delivery-runs/advertorials": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Advertorials for the shopper's deliver-to place (windows and runs that take orders now)
         * @description delivery-runs `GET /v1/public/advertorials`. The place is the explicit `zip`, `lat`/`lng` or
         *     `country`+`region`(+`city`) when given, else the session's delivery location; with no place at all the list is
         *     empty. One variant per advertorial (the same `visitor` keeps theirs), higher priority first. Each carries the
         *     countdown target (the close or cutoff) and a link with UTM and order attribution (`src=page&cr=adv-...`); the
         *     storefront counts a `VIEW` once per visit and a `CLICK` on the button
         *     (`POST /v1/delivery-runs/advertorials/{advertorialId}/events`) and expires the card at `expiresAt`.
         */
        get: operations["listRunAdvertorials"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/delivery-runs/advertorials/{advertorialId}/events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Count a view or a click on an advertorial
         * @description delivery-runs `POST /v1/public/advertorials/{id}/events {type}`. Best effort: the storefront sends `VIEW` once
         *     per visit and `CLICK` on the button, and ignores a failure. Rate limited like other lookups.
         */
        post: operations["recordRunAdvertorialEvent"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/credit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * My credit balance and history, newest first
         * @description payments `GET /v1/credit/me` and `/v1/credit/me/entries` with the shopper's token. The balance is the sum of the
         *     ledger. Each entry has a `kind` (GRANT, SPEND, REFUND, ADJUSTMENT) and a `source` (REFERRAL, GIFT_CARD, MEMBERSHIP,
         *     REFUND, STAFF, PROMO, ORDER); the storefront words them in the shopper's language. Page backwards with
         *     `before` = the previous page's `nextBefore`.
         */
        get: operations["getAccountCredit"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/orders/{orderNumber}/credit-refunds": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Take the card part of my order's refunds as credit (one way)
         * @description A refund goes back to what paid each part: the part paid with credit comes back as credit and the part paid
         *     with the card to the card. This chooses credit for the card part of every later refund of the order. It is one
         *     way: credit never goes back to a card or to cash, and refunds already sent to the card stay there.
         *     409 `NO_CARD_PORTION` when the order was paid with credit only.
         */
        post: operations["chooseCreditRefunds"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/gift-cards/config": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Gift card amount limits, preset amounts and terms (staff settings)
         * @description Default limits are 10.00 to 500.00; gift cards never expire. Staff change them without a release.
         */
        get: operations["getGiftCardConfig"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/gift-cards": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The gift cards I bought, newest first (never with the code) */
        get: operations["listMyGiftCards"];
        put?: never;
        /**
         * Start buying a gift card; confirm the card payment with the client secret
         * @description Requires `Idempotency-Key`. payments creates the card (PENDING_PAYMENT) and a PaymentIntent for it: confirm
         *     `clientSecret` with Stripe Elements (a card; credit cannot pay for a gift card), then
         *     `POST /v1/account/gift-cards/{giftCardId}/confirm`. The code is generated and emailed on `sendAt` (now when
         *     omitted). 422 `errors.amountMinor` when the amount is outside the staff-set limits.
         */
        post: operations["buyGiftCard"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/gift-cards/{giftCardId}/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * After the payment was confirmed, capture it and issue the card
         * @description Idempotent; the payments webhook does the same, so either order works.
         */
        post: operations["confirmGiftCard"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/gift-cards/redeem": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Redeem a gift card code into my credit, once
         * @description 404 `GIFT_CARD_INVALID` for a wrong code; 409 `GIFT_CARD_REDEEMED` when it was redeemed already. Wrong codes
         *     are counted per shopper and per network address: over the limit the answer is 429 `RATE_LIMITED` with
         *     `Retry-After`, even for a right code.
         */
        post: operations["redeemGiftCard"];
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
    "/v1/cart/preorder-fulfilment": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Choose door delivery or pickup for the cart's pre-order package of a window
         * @description Relayed to the cart service (`PUT /v1/cart/preorder-fulfilment {windowId, fulfilment}`). The fee is the
         *     window's effective door fee (Back office → Pre-orders → Settings → payment, or the window's override) or the
         *     pickup fee; only options the window offers (`preorder.options[]`) are accepted.
         */
        put: operations["setPreorderFulfilment"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/preorders/resolve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The pre-order window covering the shopper's delivery location (or an explicit place)
         * @description Without `country`/`region` the BFF uses the session's delivery location (`/v1/delivery-location`: market
         *     country, state, city); `place.source` says which (`session`, `default` or `query`). delivery-runs
         *     `GET /v1/public/preorders/resolve` answers: the first OPEN, else UPCOMING, else CLOSED window that covers the
         *     place, whether pre-order is `on` (master switch, window active, area on; metro cities never), the countdown
         *     target, creatives, live count and the fallback when off. Cached 30 seconds.
         */
        get: operations["resolvePreorder"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/preorders/open": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Open and upcoming windows for the /pre-orders page
         * @description delivery-runs `GET /v1/public/preorders/open`. Cached 30 seconds.
         */
        get: operations["listOpenPreorders"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/preorders/waitlist": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Hear when a pre-order opens in an area (email, double opt-in)
         * @description delivery-runs `POST /v1/public/subscriptions {email, area, country, region, city, source: preorder-waitlist}`.
         *     The place defaults to the session's delivery location. Always 202 for a valid email (whether or not it was
         *     known); notifications sends the confirmation link, then `preorder.waitlist.opened` when a window opens.
         *     Rate limited like other lookups.
         */
        post: operations["joinPreorderWaitlist"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/preorders/waitlist/unsubscribe": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Leave a pre-order waitlist (token from the waitlist email's unsubscribe link)
         * @description delivery-runs `POST /v1/public/preorders/waitlist/unsubscribe {token}` (no sign-in). The storefront page
         *     `/pre-orders/waitlist/unsubscribe?token=` calls it. Leaving twice is fine (200 again); an unknown token is a 422
         *     on `token`. Rate limited like other lookups.
         */
        post: operations["leavePreorderWaitlist"];
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
         *
         *     orders `POST /v1/checkout` creates the PaymentIntent and always returns its `paymentClientSecret`
         *     (`status: awaiting_payment`): confirm it with Stripe.js / PaymentSheet (a saved method, a wallet or a new
         *     method from PaymentSheet — `paymentMethodId` may be any Stripe PaymentMethod id), then call
         *     `POST /v1/checkout/orders/{orderNumber}/confirm`. 202 means the saga is still preparing — poll
         *     `GET /v1/checkout/orders/{orderNumber}`.
         */
        post: operations["placeOrder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/payment-methods/{paymentMethodId}/default": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Make a saved payment method the default (payments POST /v1/customers/me/payment-methods/{id}/default) */
        post: operations["setDefaultPaymentMethod"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/payment-sheet": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Stripe customer id and ephemeral key so PaymentSheet (customer app) can list and reuse saved methods
         * @description payments `POST /v1/customers/me/ephemeral-keys` (planned: payments does not issue ephemeral keys yet, so this answers 503 `UPSTREAM_UNAVAILABLE` until it does).
         */
        post: operations["createPaymentSheetKeys"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/orders/{orderNumber}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One of my orders with shipments, lines, tracking numbers, timelines and handover codes */
        get: operations["getOrder"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/orders/{orderNumber}/tracking": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Shipment tracking (orders timeline + carrier-gateway scans) — the customer app's tracking screen */
        get: operations["trackOrder"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/orders/{orderNumber}/returnable": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** What can be returned from a delivered order (tax-returns eligibility per shipment and line) */
        get: operations["getReturnEligibility"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/returns/{rmaId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One of my returns with its lines and the refund estimate (tax-returns refund quote) */
        get: operations["getReturn"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/returns/{rmaId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Cancel a return before drop-off or pickup */
        post: operations["cancelReturn"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/security": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Login & security — second factors, passkeys, signed-in sessions, push devices and linked accounts */
        get: operations["getSecurity"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Change the password (identity PUT /v1/me/password; the current password is required when one is set) */
        put: operations["changePassword"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/mfa/totp": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Start authenticator-app enrolment (render otpauthUri as a QR code) */
        post: operations["startTotp"];
        /** Turn off the authenticator app */
        delete: operations["disableTotp"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/mfa/totp/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Confirm the authenticator app with a code; returns recovery codes the first time */
        post: operations["confirmTotp"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/mfa/sms": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Text a code to the account phone to turn on SMS sign-in codes */
        post: operations["startSmsMfa"];
        /** Turn off SMS sign-in codes */
        delete: operations["disableSmsMfa"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/mfa/sms/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Confirm SMS sign-in codes; returns recovery codes the first time */
        post: operations["confirmSmsMfa"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/mfa/recovery-codes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** New recovery codes (the old ones stop working) */
        post: operations["regenerateRecoveryCodes"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/passkeys/{credentialId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Remove a passkey (409 for the last sign-in method) */
        delete: operations["removePasskey"];
        options?: never;
        head?: never;
        /** Rename a passkey (identity PATCH /v1/me/passkeys/{credentialId}) */
        patch: operations["renamePasskey"];
        trace?: never;
    };
    "/v1/account/sessions/{sessionId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Sign out one device */
        delete: operations["revokeSession"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/sessions/revoke-others": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Sign out every other device */
        post: operations["revokeOtherSessions"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/devices/{deviceId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Stop push notifications to a device */
        delete: operations["removeDevice"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/linked-accounts/{provider}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Unlink a Google, Apple or Facebook sign-in (409 for the last sign-in method) */
        delete: operations["unlinkAccount"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/trusted-browsers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Forget every browser trusted to skip two-step verification */
        delete: operations["forgetTrustedBrowsers"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/trusted-browsers/{browserId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Forget one trusted browser (it asks for the second factor at the next sign-in) */
        delete: operations["forgetTrustedBrowser"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/sign-in-activity": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Recent sign-ins of the account across every app (identity's sign-in audit trail, newest first) */
        get: operations["getSignInActivity"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/profile": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * The signed-in person's profile with organisation memberships, platform roles and job title
         * @description `kind` says which settings apply: `customer` (storefront), `seller` (Seller Center / Provider Hub member) or
         *     `staff`. Email is read-only (identity has no change-email flow); `emailManaged` marks staff company addresses.
         */
        get: operations["getAccountProfile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Change name, mobile number or job title (identity PATCH /v1/me and PUT /v1/me/work-profile)
         * @description A new mobile number must be verified again by SMS. Job title is for back-office members only.
         */
        patch: operations["updateAccountProfile"];
        trace?: never;
    };
    "/v1/account/preferences": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Language, time zone, date and number format and default table density */
        get: operations["getAccountPreferences"];
        /** Replace the display preferences (notification switches are kept) */
        put: operations["saveAccountPreferences"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/alerts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Back-office alert topics per channel and personal quiet hours (notifications /v1/preferences/{id}/alerts)
         * @description Seller Center and Provider Hub members get the seller topics (new orders, low stock, payouts, compliance,
         *     claims); staff get theirs (assigned queues, escalations, approvals). 403 for accounts with neither.
         */
        get: operations["getAlertPreferences"];
        /** Change alert topics and quiet hours */
        put: operations["saveAlertPreferences"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/orgs/{orgId}/leave": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Leave an organisation (members who are not an owner) */
        post: operations["leaveOrganisation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/notification-preferences": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Notification switches */
        get: operations["getNotificationPreferences"];
        /** Replace the notification switches */
        put: operations["saveNotificationPreferences"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/bookings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** My service bookings (bookings GET /v1/bookings/mine) */
        get: operations["listMyBookings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/bookings/{bookingId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One of my bookings */
        get: operations["getMyBooking"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/bookings/{bookingId}/cancellation-quote": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Exactly what cancelling now refunds or keeps (shown before cancelling) */
        get: operations["getBookingCancellationQuote"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/bookings/{bookingId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Cancel a booking; the refund follows the policy it was booked under */
        post: operations["cancelMyBooking"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/bookings/{bookingId}/reschedule": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Ask for a new time (a start time from the slots endpoint) */
        post: operations["rescheduleMyBooking"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/bookings/{bookingId}/new-time/{decision}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Accept or decline the time the provider proposed */
        post: operations["answerProposedTime"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/bookings/{bookingId}/review": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Rate a completed booking once (1–5 stars) */
        post: operations["reviewMyBooking"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/account/bookings/{bookingId}/payment/sync": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** I confirmed the deposit with Stripe.js / PaymentSheet — refresh the booking */
        post: operations["syncBookingPayment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/checkout/orders/{orderNumber}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Checkout status of one of my orders (poll after 202 or while the payment is processing) */
        get: operations["getCheckoutStatus"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/checkout/orders/{orderNumber}/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** I confirmed the payment with Stripe.js / PaymentSheet — continue the saga without waiting for the webhook */
        post: operations["confirmCheckoutPayment"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/pay-links/{orderNumber}/lookup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * The pay-now page of a late pre-order add (no session; the SMS link's signed token is the credential)
         * @description Customer service texts a pay-now link to AfroTransact's own page (/orders/{orderNumber}/pay?token=…); customers never get a Stripe-hosted page. While the order awaits payment the answer carries the client secret of its existing manual-capture PaymentIntent, which the page confirms with Stripe Elements (stripe.confirmPayment, redirect if_required). A paid order is status paid without a client secret; an unknown order or a wrong token is 404 PAY_LINK_NOT_FOUND; an expired or voided one is 410 PAY_LINK_EXPIRED. Rate-limited per IP.
         */
        post: operations["lookupPayLink"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/pay-links/{orderNumber}/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * After stripe.confirmPayment on the pay-now page — place the order once the payment is authorised
         * @description Orders reads the PaymentIntent from Stripe and places the order when it is authorised (idempotent; the webhook does the same). Answers like lookup; failureMessage after a decline (the customer may try again on the same PaymentIntent until the link expires).
         */
        post: operations["confirmPayLink"];
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
        /** Services near a ZIP (bookings public search; the deliver-to ZIP when omitted) */
        get: operations["searchServices"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/services/{serviceId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A bookable service with add-ons, team and cancellation policy */
        get: operations["getService"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/services/{serviceId}/days": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Days with open slots (live from the provider's hours and bookings) */
        get: operations["getServiceDays"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/services/{serviceId}/slots": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Open start times on a day */
        get: operations["getServiceSlots"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/services/{serviceId}/summary": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Deposit now, balance later and the cancellation policy for the chosen options */
        get: operations["getBookingQuote"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/service-providers/{orgId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A provider's page — bookings profile and services */
        get: operations["getProviderPage"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/business-pages/{slug}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * A business's plain public page (what Stripe is given as its website)
         * @description Public, read-only. Name, description, logo and one brand colour from the business's application; providers also list their services. `indexable` is false until the business is approved, so search engines are kept off it.
         */
        get: operations["getBusinessPage"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/services/bookings": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Book a service; the deposit is held with Stripe (confirm `payment.clientSecret`, then sync the booking) */
        post: operations["bookService"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/lola/chat": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * One Lola turn as Server-Sent Events, relayed from ai-gateway as it streams
         * @description Events in order: `tool_call` {id, tool, label, arguments}, `tool_result` {id, tool, kind, data},
         *     `action_proposal` {proposal: {id, type: ADD_TO_CART|CHECKOUT|BOOK_SERVICE|START_RETURN, label, payload,
         *     requiresConfirmation}}, `token` {text} (one per fragment), `handoff` {handoffId, reason, summary}, then `done`
         *     {sessionId, message, products, proposals, suggestions, …}; a failure mid-stream sends `error` {code, detail}.
         *     Proposals are never executed by Lola: the client confirms them through the normal BFF routes. The deliver-to
         *     ZIP and Accept-Language are added when the request omits them. Requires a signed-in customer.
         */
        post: operations["chatWithLola"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/lola/sessions/{sessionId}/messages": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Stored (redacted) turns of one of my Lola conversations */
        get: operations["getLolaHistory"];
        put?: never;
        post?: never;
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
    "/v1/careers/uploads": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Signed direct upload for a résumé (PDF, at most 5 MB)
         * @description Returns a short-lived URL from the media service (private storage). PUT the file there with the given
         *     headers, then call `…/uploads/{assetId}/complete` and apply with `resumeAssetId`.
         */
        post: operations["requestResumeUpload"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/careers/uploads/{assetId}/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Check the uploaded résumé (real type and size) and finalise it */
        post: operations["completeResumeUpload"];
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
        /** @enum {string} */
        NativeApp: "customer" | "driver";
        NativePasswordRequest: {
            app: components["schemas"]["NativeApp"];
            email: string;
            password: string;
            /** @description From an earlier verification with trustDevice=true */
            trustedDeviceToken?: string;
            /** @description PKCE S256 challenge of the app's verifier */
            codeChallenge: string;
            /** @description The app's redirect URI registered at identity */
            redirectUri: string;
        };
        NativeSmsRequest: {
            app: components["schemas"]["NativeApp"];
            /** Format: uuid */
            challengeId: string;
            codeChallenge: string;
            redirectUri: string;
        };
        NativeVerifyRequest: {
            app: components["schemas"]["NativeApp"];
            /** Format: uuid */
            challengeId: string;
            /** @enum {string} */
            factor: "TOTP" | "SMS" | "RECOVERY_CODE";
            code: string;
            /** @description Return a trustedDeviceToken so this phone skips the second factor for 30 days */
            trustDevice?: boolean;
            codeChallenge: string;
            redirectUri: string;
        };
        NativeSignInResult: {
            /** @enum {string} */
            status: "MFA_REQUIRED" | "AUTHENTICATED";
            /**
             * Format: uuid
             * @description MFA_REQUIRED only
             */
            challengeId?: string;
            /** Format: date-time */
            challengeExpiresAt?: string;
            factors?: {
                totp?: boolean;
                sms?: boolean;
                smsHint?: string;
                recoveryCode?: boolean;
            };
            /** @description AUTHENTICATED only: authorization code to exchange at identity /oauth2/token with the PKCE verifier */
            code?: string;
            trustedDeviceToken?: string;
        };
        PayLinkToken: {
            /** @description The token of the SMS pay-now link */
            token: string;
        };
        /** @description The pay-now page of a late pre-order add (AfroTransact's own page; Stripe Elements) */
        PayLink: {
            orderNumber: string;
            /** @enum {string} */
            status: "awaiting_payment" | "paid";
            /** Format: int64 */
            totalMinor: number;
            /** Format: int64 */
            deliveryFeeMinor: number;
            currency: string;
            /** @description The order's PaymentIntent client secret; null once paid */
            clientSecret: string | null;
            /** @description Stripe publishable key for Stripe.js (empty locally = test form) */
            publishableKey: string;
            /** @description The last decline; the customer may try again */
            failureMessage?: string | null;
            /** Format: date-time */
            expiresAt?: string | null;
            windowName?: string | null;
            /** Format: date */
            deliversOn?: string | null;
            fulfilment?: string | null;
            lines: {
                title: string;
                quantity: number;
                /** Format: int64 */
                totalMinor: number;
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
             * @description Stable machine code: VALIDATION_FAILED, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, PRODUCT_NOT_FOUND, OFFER_NOT_FOUND,
             *     LINE_NOT_FOUND, SAVED_NOT_FOUND, STOCK_LIMIT, OUT_OF_STOCK, INVALID_CREDENTIALS, INVALID_CODE, CHALLENGE_EXPIRED,
             *     EMAIL_TAKEN, WEAK_PASSWORD, PROVIDER_DISABLED, ZIP_NOT_SERVICEABLE, PAYMENT_DECLINED, IDEMPOTENCY_CONFLICT,
             *     STOCK_CHANGED, RATE_LIMITED, UPSTREAM_UNAVAILABLE, PHONE_MISSING; AfroTransact Delivery from the seller's store
             *     (S255, 422 on place order, `errors.deliverySelections` explains it): DELIVERY_OUT_OF_RANGE, DELIVERY_UNAVAILABLE,
             *     DELIVERY_PICKUP_UNAVAILABLE, DELIVERY_ADDRESS_NOT_LOCATED.
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
         * @description AFROTRANSACT = "Fulfilled by AfroTransact"; SELLER = "Ships from seller"; AFROTRANSACT_DELIVERY = "AfroTransact Delivery from <store city>" (the seller packs, an AfroTransact driver collects at the seller's store and delivers; priced by road distance at checkout, S255). The offer's `seller.city` is the store city.
         * @enum {string}
         */
        Fulfilment: "AFROTRANSACT" | "SELLER" | "AFROTRANSACT_DELIVERY";
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
            /** @description offers-service offer id (add it to the cart as offerId) */
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
            /** @description Present on sponsored products — render "Sponsored · {advertiser}" with the reason. */
            sponsor: {
                advertiser: string;
                campaignId?: string;
                /** @description 1-based position the ad won */
                slot?: number;
                /** @description Send back as adToken with POST /v1/search/clicks */
                adToken?: string;
                reason?: components["schemas"]["SponsorReason"];
            } | null;
        };
        /** @description Why this ad was shown (no bids or prices are disclosed) */
        SponsorReason: {
            /** @enum {string} */
            targeting: "KEYWORD" | "CATEGORY";
            matched: string;
            qualityScore: number;
            auctionRank: number;
            competitors: number;
            eligibility: string[];
            summary: string;
        };
        SearchClickRequest: {
            searchId: string;
            q?: string;
            atin: string;
            /** @description 1-based position on the page */
            position: number;
            /** @description sponsor.adToken of a sponsored product */
            adToken?: string;
        };
        SearchClickResult: {
            recorded: boolean;
            duplicate: boolean;
            sponsored: boolean;
            /** @enum {string} */
            adStatus?: "BILLABLE" | "DUPLICATE" | "EXPIRED";
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
        /**
         * @description A way to reach a subscriber; each is consented to separately (WhatsApp only from approved templates)
         * @enum {string}
         */
        RunChannel: "EMAIL" | "SMS" | "PUSH" | "WHATSAPP";
        /** @description Where orders are collected when the drop mode is PICKUP_POINT */
        RunPickupPoint: {
            [key: string]: unknown;
        } | null;
        RunLocation: {
            /** Format: uuid */
            id: string;
            /** @example lubbock */
            slug: string;
            /** @example Lubbock */
            name: string;
            /** @example US */
            market: string;
            /** @example TX */
            state?: string | null;
            /** @example America/Chicago */
            timeZone: string;
            /** @description Coverage as delivery-runs keeps it (ZIP list, polygon or radius) */
            area?: {
                [key: string]: unknown;
            };
            /** @description Delivery fee when the run sets none */
            defaultFeeMinor: number;
            /** @example USD */
            currency: string;
            /** @enum {string} */
            dropMode: "DOORSTEP" | "PICKUP_POINT";
            pickupPoint?: components["schemas"]["RunPickupPoint"];
            /** @description The location's WhatsApp community invite */
            whatsappInviteUrl?: string | null;
            socialLinks?: {
                [key: string]: string;
            };
        };
        /** @description A delivery run as shoppers see it */
        RunPublic: {
            /** Format: uuid */
            runId: string;
            /** Format: uuid */
            locationId: string;
            locationSlug?: string;
            town?: string;
            status: string;
            /** Format: date-time */
            windowStart: string;
            /** Format: date-time */
            windowEnd: string;
            /**
             * Format: date-time
             * @description Orders close at this time
             */
            cutoffAt: string;
            feeMinor: number;
            currency: string;
            /** @enum {string} */
            dropMode?: "DOORSTEP" | "PICKUP_POINT";
            pickupPoint?: components["schemas"]["RunPickupPoint"];
            acceptingOrders: boolean;
            /** @description Orders left (null = not limited) */
            ordersLeft?: number | null;
            /** @description No room left; checkout offers the next run */
            full: boolean;
            /** @description Link to the run on the location page */
            runUrl?: string;
        };
        RunLocationPage: {
            location: components["schemas"]["RunLocation"];
            runs: components["schemas"]["RunPublic"][];
            /** @description The location page URL */
            locationUrl: string;
            /** @description Share URLs per channel, each with UTM attribution */
            shareLinks?: {
                [key: string]: string;
            };
        };
        /** @description Consent for one channel with its record */
        RunConsent: {
            channel: components["schemas"]["RunChannel"];
            granted: boolean;
            /** Format: date-time */
            consentedAt?: string | null;
            /** @description Where the consent was given (location-page */
            source?: string | null;
            /** Format: date-time */
            withdrawnAt?: string | null;
        };
        RunSubscription: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            locationId: string;
            /** @description PENDING_CONFIRMATION, ACTIVE or UNSUBSCRIBED */
            status: string;
            consents: components["schemas"]["RunConsent"][];
            /** Format: date-time */
            createdAt?: string | null;
            /** Format: date-time */
            confirmedAt?: string | null;
        };
        /** @description An advertorial served to the shopper's place, one variant */
        RunAdvertorial: {
            /** Format: uuid */
            id: string;
            /** @enum {string} */
            source: "WINDOW" | "RUN";
            /** @description The pre-order window or delivery run it promotes */
            sourceId: string;
            /** @example A */
            variant?: string;
            /** @description Window code; null for runs */
            code?: string | null;
            /** @example Lubbock, TX */
            place?: string | null;
            headline: string;
            body?: string;
            ctaLabel: string;
            /** @description With UTM and order attribution (src=page&cr=adv-...) */
            ctaUrl: string;
            imageUrl?: string | null;
            /**
             * Format: date-time
             * @description The live countdown counts down to this
             */
            countdownTarget?: string | null;
            /** Format: date-time */
            expiresAt: string;
            deliveryDisplay?: string | null;
            feeDisplay?: string | null;
        };
        StockAlertItem: {
            atin: components["schemas"]["Atin"];
            productTitle: string;
            /**
             * @description notified = the back-in-stock email was sent
             * @enum {string}
             */
            status: "active" | "notified";
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            notifiedAt: string | null;
        };
        ResumeUpload: {
            assetId: string;
            /** Format: int64 */
            maxBytes: number;
            upload: {
                method: string;
                url: string;
                headers: {
                    [key: string]: string;
                };
                /** Format: date-time */
                expiresAt?: string | null;
            };
        };
        ResumeAsset: {
            assetId: string;
            contentType: string;
            /** Format: int64 */
            bytes: number;
            /** @enum {string} */
            status: "READY";
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
            /** @description Services matching the typed words (first page of a text search only; at most four, best match first) */
            services: {
                serviceId: string;
                orgId: string;
                name: string;
                providerName: string;
                category: string;
                durationMinutes: number;
                /** Format: int64 */
                priceFromMinor: number;
                currency: string;
                location: string;
            }[];
            /** @description Identifies this search for click reporting (POST /v1/search/clicks) */
            searchId?: string;
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
            /** @description Catalog product type or attribute perishable: hand delivery with a code, no safe place */
            perishable?: boolean;
            /** @description Catalog product type code for sales-tax category mapping */
            productType?: string;
        };
        DeliveryOption: {
            /** @enum {string} */
            key: "carrier" | "express" | "afrotransact_delivery" | "pickup";
            label: string;
            eta: string;
            feeMinor: number;
            free: boolean;
            /**
             * Format: date
             * @description Checkout only — promise service date range
             */
            earliestDate?: string;
            /** Format: date */
            latestDate?: string;
            /** @example Order within 3 h 12 min */
            cutoffLabel?: string;
            feeRule?: components["schemas"]["DeliveryFeeRuleRef"];
        };
        /**
         * @description The staff delivery fee rule behind a fee (ADR 0005 §3, staff manage them under Settings → Delivery fees): the
         *     delivery method, the rule and its version, the amount and where it came from. The cart and checkout show it next
         *     to the fee; the order keeps the rule it was charged by, so later edits never change it.
         */
        DeliveryFeeRuleRef: {
            /** @enum {string} */
            method: "AFROTRANSACT_DELIVERY" | "CARRIER" | "PICKUP";
            ruleId?: string;
            ruleVersion: number;
            currency?: string;
            amountMinor: number;
            /** @enum {string} */
            source?: "RULE" | "WINDOW_OVERRIDE" | "CARRIER_QUOTE" | "CARRIER_FALLBACK";
            /** @example 10–40 mi */
            band?: string;
            distanceMiles?: number;
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
            /** @description Pre-order block (packageId ends in `:preorder`); null for packages that ship now */
            preorder?: components["schemas"]["CartPreorder"] | null;
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
            /** @description The offers-service offer id (buy box or Other sellers; validated as buyable), or a product / variant ATIN when offers has none */
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
                /** @description false: `address` holds the street line only; resolve the suggestion when picked */
                resolved: boolean;
            }[];
            /** @description Required attribution text (e.g. © OpenStreetMap contributors) */
            attribution: string;
            /** @description Autocomplete session of this address entry (Google Places billing) */
            sessionToken: string;
        };
        MaintenanceStatus: {
            active: boolean;
            /**
             * @description SHOPPERS pauses the storefront and apps; ALL also pauses Seller Center.
             * @enum {string}
             */
            scope?: "SHOPPERS" | "ALL";
            title?: string;
            /** @description What customers are told. */
            message?: string;
            /**
             * Format: date-time
             * @description Omitted when unknown or already past.
             */
            expectedBackAt?: string;
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
                /** @description The support phone in E.164 (+18335550199); also the number of the WhatsApp chat button in the storefront and Seller Center. Empty when not set (the button is then hidden). */
                phoneE164: string;
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
        /** @description A rendered legal document (Markdown body with `##` sections; `outline` lists their anchors) */
        LegalPage: {
            /** @example conditions-of-use */
            key: string;
            /** @example Conditions of Use */
            title: string;
            /** @example 3.2 */
            version: string;
            /** Format: date */
            effectiveDate: string;
            /** @enum {string} */
            status: "CURRENT" | "ARCHIVED";
            /** @description Show "Draft for legal counsel review" until counsel signs off */
            draftBanner: boolean;
            /** @description Locale of the body (en when the translation falls back) */
            locale: string;
            /** @description The requested locale has no reviewed translation; the binding English text is served */
            translationFallback: boolean;
            /** @description Markdown */
            body: string;
            outline: components["schemas"]["LegalHeading"][];
            /** @description Every version, newest first */
            versions: components["schemas"]["LegalVersionRef"][];
            /** @example /legal/conditions-of-use */
            href: string;
        };
        /** @enum {string} */
        HelpTopicKey: "ORDERS_DELIVERY" | "RETURNS_REFUNDS" | "ACCOUNT_PAYMENTS" | "SELLING" | "COMPANY";
        HelpSection: {
            /** @example Pickup */
            heading: string;
            /** @description Markdown (paragraphs, `-` lists, **bold**, [links](…)); email addresses become mailto links */
            body: string;
        };
        /** @description A help page — a title and its sections (Shop design info page) */
        HelpArticle: {
            /** @example shipping */
            slug: string;
            topic: components["schemas"]["HelpTopicKey"];
            /** @example Orders & delivery */
            topicTitle: string;
            /** @example Shipping & delivery */
            title: string;
            summary: string;
            sections: components["schemas"]["HelpSection"][];
            /** Format: date-time */
            updatedAt: string;
        };
        HelpArticleSummary: {
            slug: string;
            topic: components["schemas"]["HelpTopicKey"];
            topicTitle: string;
            title: string;
            summary: string;
            /** Format: date-time */
            updatedAt: string;
        };
        HelpTopicCount: {
            key: components["schemas"]["HelpTopicKey"];
            /** @example Orders & delivery */
            title: string;
            count: number;
        };
        HelpArticlePage: {
            items: components["schemas"]["HelpArticleSummary"][];
            /** @description Topics with at least one published article, in display order */
            topics: components["schemas"]["HelpTopicCount"][];
            /** @description Cursor of the next page; null on the last page */
            nextCursor: string | null;
        };
        LegalHeading: {
            /** @example notifications-email-and-text-messages */
            id: string;
            title: string;
        };
        LegalVersionRef: {
            version: string;
            /** Format: date */
            effectiveDate: string;
            /** @enum {string} */
            status: "CURRENT" | "ARCHIVED";
            changeSummary?: string | null;
        };
        CookiePolicy: {
            /** @example 2.1 */
            noticeVersion: string;
            /** Format: date */
            effectiveDate: string;
            /** @example /legal/cookies */
            noticeHref: string;
            categories: components["schemas"]["CookieCategory"][];
            cookies: components["schemas"]["CookieDefinition"][];
        };
        CookieCategory: {
            /** @example analytics */
            key: string;
            name: string;
            description: string;
            /** @description Always on (strictly necessary) */
            required: boolean;
            /** @description Switched off and locked by Global Privacy Control or a sale/sharing opt-out */
            gpcOptOut: boolean;
        };
        CookieDefinition: {
            /** @example _gcl_au */
            name: string;
            provider: string;
            purpose: string;
            duration: string;
            category: string;
        };
        ConsentRequest: {
            /** @description Choice per category key (unknown keys are rejected) */
            categories: {
                [key: string]: boolean;
            };
            /** @description Do not sell or share my personal information */
            saleOptOut?: boolean;
            /** @description navigator.globalPrivacyControl (the Sec-GPC header is also read) */
            gpc?: boolean;
            /**
             * @default banner
             * @enum {string}
             */
            source: "banner" | "preferences" | "privacy-choices";
        };
        ConsentRecord: {
            /** Format: uuid */
            id: string;
            categories: {
                [key: string]: boolean;
            };
            gpc: boolean;
            saleOptOut: boolean;
            /** @description Cookie Notice version the choice was made under */
            policyVersion: string;
            /** Format: date-time */
            recordedAt: string;
        };
        PrivacyRequest: {
            /** @enum {string} */
            type: "access" | "delete" | "correct" | "optout";
            /**
             * Format: email
             * @description Email on the requester's account
             */
            email: string;
            /**
             * @description US state of residence (DC included)
             * @example TX
             */
            state: string;
            details?: string;
            /** @example en */
            locale?: string;
        };
        PrivacyRequestReceipt: {
            /** @example PR-100001 */
            reference: string;
            /** @enum {string} */
            type: "access" | "delete" | "correct" | "optout";
            /** @enum {string} */
            status: "RECEIVED" | "COMPLETED" | "DENIED";
            /** Format: date-time */
            submittedAt: string;
            /**
             * Format: date-time
             * @description Response deadline (45 days)
             */
            dueAt: string;
        };
        /** @description A back office the signed-in person can open from the storefront (links use each app's configured URL). */
        BackOffice: {
            /**
             * @description Seller Center and Provider Hub are the Seller Center app (SELLER_CENTER_URL); the staff console is STAFF_CONSOLE_URL.
             * @enum {string}
             */
            app: "seller-center" | "provider-hub" | "staff-console";
            /**
             * @description `active`: an approved (or suspended) organisation, or staff. `application`: a seller or provider application still in progress ("Continue your seller application"), which opens Seller Center onboarding.
             * @enum {string}
             */
            state: "active" | "application";
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
                /** @description Masked account phone (•••• 0142); absent when identity is unavailable or no phone is set */
                phone?: string;
                /** @description Whether the account phone passed SMS verification */
                phoneVerified?: boolean;
                /**
                 * @description The person's back offices, for the storefront account menu and the customer app's account screen:
                 *     Seller Center for a seller organisation, Provider Hub for a provider organisation, the staff console
                 *     for staff. A person is always also a shopper; this only adds links (the storefront acts as the person,
                 *     never as their organisation). Empty for shoppers only.
                 */
                backOffices: components["schemas"]["BackOffice"][];
            } | null;
            /** @description Badges for the account menu. */
            counts: {
                orders: number;
                returns: number;
                addresses: number;
                paymentMethods: number;
            } | null;
        };
        SignOutRequest: {
            /** @description The path to come back to on the calling app: the storefront returns there signed out (send it only for pages a guest may see); Seller Center and the staff console return there after signing in again. Anything but a relative path is ignored. */
            returnTo?: string;
        };
        SignOutResult: {
            /** @description Where the browser goes next: identity's end-session URL (ends the identity session, then `GET /v1/auth/signed-out/{app}` on the calling app), or that signed-out URL directly when there is no identity session to end; null for native apps. */
            redirectTo: string | null;
        };
        PhoneCodeSent: {
            /** @description Masked phone, e.g. •••• 0142 */
            sentTo: string;
            /** Format: date-time */
            expiresAt: string;
        };
        PhoneVerification: {
            phoneVerified: boolean;
            /** Format: date-time */
            verifiedAt?: string;
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
            type: "card" | "us_bank_account" | "cashapp" | "link" | "amazon_pay";
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
                /** @description Who sells and ships this shipment */
                sellerName?: string;
                /** @description What is in the shipment (name, photo, quantity) */
                lines?: {
                    title: string;
                    quantity: number;
                    imageUrl?: string | null;
                    productAtin?: string;
                }[];
                /** @description The customer can still cancel it: before the parcel is packed and inside the cancellation window */
                cancellable?: boolean;
                /**
                 * Format: date-time
                 * @description When Cancel stops being offered; null when it is not offered now
                 */
                cancellableUntil?: string | null;
                carrier?: string | null;
                trackingNumber?: string | null;
                promisedBy?: string | null;
                deliveredAt?: string | null;
                /** @description One-time handover code (hand delivery or perishables) */
                deliveryCode?: string | null;
                /** @description A return can be started (show "Return items") */
                returnable?: boolean;
                /** Format: date-time */
                returnWindowEndsAt?: string | null;
                /** @description Only "Report a problem" is possible (e.g. perishables, window closed) */
                problemReportOnly?: boolean;
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
            /** @description requested, approved, auto_approved, label_issued, in_transit, received, inspected, refunded, rejected, cancelled */
            status: string;
            /** @description Refund estimate (tax-returns), then the refunded amount */
            refundMinor: number;
            /** @example RMA-0000ABCD */
            number?: string;
            method?: components["schemas"]["ReturnMethod"];
            reason?: string;
            refundState?: string;
            currency?: string;
            cancellable?: boolean;
            createdAt?: string | null;
            /** @description Drop-off return label */
            labelUrl?: string | null;
            /** @description Render as the drop-off QR code */
            qrPayload?: string | null;
            /** @description Driver pickup code (DRIVER_PICKUP) */
            handoverCode?: string | null;
            dropOffBy?: string | null;
            carrier?: string | null;
            trackingNumber?: string | null;
        };
        ReturnPage: {
            items: components["schemas"]["ReturnSummary"][];
            nextCursor: string | null;
        };
        CheckoutCouponSellerShare: {
            sellerOrgId: string;
            sellerName?: string;
            discountMinor: number;
            /**
             * @description Who pays this slice at settlement
             * @enum {string}
             */
            funding: "platform" | "seller";
        };
        CheckoutCouponOfferDiscount: {
            offerId: string;
            sellerOrgId?: string;
            discountMinor: number;
        };
        CheckoutCoupon: {
            code: string;
            applicable: boolean;
            rejection?: string | null;
            message?: string;
            discountMinor: number;
            shippingDiscountMinor: number;
            /** @enum {string} */
            funding?: "platform" | "seller";
            /** @description Seller org when funding is seller */
            fundedByOrg?: string;
            offerDiscounts?: components["schemas"]["CheckoutCouponOfferDiscount"][];
            /** @description Per-seller discount totals for settlement preview */
            sellerShares?: components["schemas"]["CheckoutCouponSellerShare"][];
        };
        CheckoutSummary: {
            packages: (components["schemas"]["CartPackage"] & {
                localDelivery?: components["schemas"]["CheckoutLocalDelivery"];
                pickupPoints?: {
                    id: string;
                    name: string;
                    address: string;
                    hours: string;
                    ready: string;
                    /** @enum {string} */
                    setBy: "platform" | "seller";
                    /**
                     * @description carrier-gateway pickup location kind
                     * @enum {string}
                     */
                    kind?: "PLATFORM_COUNTER" | "LOCKER" | "SELLER";
                    /** @description Straight-line miles from the delivery address; null when either isn't located */
                    distanceMiles?: number | null;
                }[];
            })[];
            addresses: components["schemas"]["Address"][];
            paymentMethods: components["schemas"]["PaymentMethod"][];
            subtotalMinor: number;
            shippingMinor: number;
            /** @description Item discount from coupon */
            discountMinor: number;
            /** @description Shipping discount from coupon (e.g. free shipping) */
            shippingDiscountMinor: number;
            coupon?: components["schemas"]["CheckoutCoupon"];
            taxMinor: number;
            /** @example 8.25% TX */
            taxRateLabel: string;
            totalMinor: number;
            currency: string;
            sellerCount: number;
            /** @description Stripe publishable key for Stripe.js (Payment / Express Checkout Elements) */
            publishableKey?: string;
            /** @description Optional checkout_cross_sell placement from content (product rail) */
            crossSell?: components["schemas"]["Rail"];
            /** @description What my AfroTransact credit would pay of this order (S320); absent when I have no credit */
            credit?: components["schemas"]["CheckoutCredit"];
        };
        /**
         * @description AfroTransact Delivery from the seller's store (S255), quoted for the chosen address: geo road distance from the
         *     store's pickup location, payments' delivery pricing. Present on AFROTRANSACT_DELIVERY packages only. When
         *     `available` is false the package can't be delivered to this address: remove it or choose another address.
         */
        CheckoutLocalDelivery: {
            available: boolean;
            /** @description The delivery charge (the package's shipping); null when not available or not quoted */
            chargeMinor: number | null;
            roadMiles: number | null;
            durationMinutes: number | null;
            /** @example Houston, TX */
            fromCity: string;
            /**
             * @description QUOTE_UNAVAILABLE: geo or payments could not answer now; the price is set when you place the order
             * @enum {string|null}
             */
            unavailableReason: "OUT_OF_RANGE" | "UNAVAILABLE" | "PICKUP_UNAVAILABLE" | "ADDRESS_NOT_LOCATED" | "QUOTE_UNAVAILABLE" | null;
            /** @example Ama's Market is 31 miles from Houston, TX: too far for AfroTransact Delivery. */
            message: string | null;
            maxRadiusMiles: number | null;
            /** @description The package's offers (remove them to check out without this package) */
            offerIds: string[];
        };
        /** @description AfroTransact Delivery from the seller's store (S255), as charged at checkout */
        OrderLocalDelivery: {
            roadMiles: number | null;
            durationMinutes: number | null;
            chargeMinor: number;
        };
        /**
         * @description Last-touch marketing source (`src` = ig | fb | tt | wa | email | sms | app | hero | ribbon | pdp | page, `cr`
         *     creative or comm slot, `po` window code). Stored on the order and echoed in `orders.order.placed`. Malformed
         *     values are dropped; without a body value the BFF reads the `at_attr` cookie.
         */
        Attribution: {
            /** @example ig */
            src?: string;
            /** @example story */
            cr?: string;
            /** @example LBK-7 */
            po?: string;
        };
        /** @description Settings placements (all on by default) */
        PreorderPlacements: {
            ribbon?: boolean;
            hero?: boolean;
            badge?: boolean;
            pdp?: boolean;
            cart?: boolean;
            family?: boolean;
        };
        PreorderPlace: {
            /** @example US */
            country: string;
            /** @example TX */
            region: string;
            /** @example Lubbock */
            city: string;
            /**
             * @description Where the BFF took the place from
             * @enum {string}
             */
            source?: "query" | "session" | "default";
        };
        PreorderCreative: {
            url?: string;
            width?: number;
            height?: number;
        } | null;
        /**
         * @description Money and payment setup of a window, all money in minor units: the window's doorFeeOverride / pickupFeeOverride,
         *     else the staff delivery fee rules (Settings → Delivery fees: AfroTransact delivery by distance band, pickup fee).
         */
        PreorderTerms: {
            door?: boolean;
            pickup?: boolean;
            /** @example 799 */
            doorFeeMinor?: number;
            /** @example 0 */
            pickupFeeMinor?: number;
            /** @example USD */
            currency?: string;
            /** @example 10am–2pm */
            pickupWindow?: string;
            allowedMethods?: ("CARD" | "WALLET" | "ACH")[];
        };
        /** @description A window as the storefront shows it (no PII); relayed from delivery-runs. */
        PreorderWindowCard: components["schemas"]["PreorderTerms"] & {
            id?: string;
            /** @example LBK-7 */
            code?: string;
            /** @example Lubbock Pre-order #7 */
            name?: string;
            ribbon?: string;
            headline?: string;
            categories?: string[];
            departments?: string[];
            /** Format: date-time */
            opensAt?: string;
            /** Format: date-time */
            closesAt?: string;
            /** Format: date */
            deliversOn?: string;
            /** @example America/Chicago */
            timeZone?: string;
            showCount?: boolean;
            /** @description Paid pre-orders so far; null when hidden or not open yet */
            count?: number | null;
            creatives?: {
                hero?: components["schemas"]["PreorderCreative"];
                pdp?: components["schemas"]["PreorderCreative"];
            };
        };
        PreorderListItem: {
            id?: string;
            code?: string;
            name?: string;
            /** @example Lubbock, TX */
            where?: string;
            /** @enum {string} */
            stage?: "UPCOMING" | "OPEN";
            /** Format: date-time */
            opensAt?: string;
            /** Format: date-time */
            closesAt?: string;
            /** Format: date */
            deliversOn?: string;
            timeZone?: string;
            place?: components["schemas"]["PreorderPlace"];
            zip?: string | null;
            heroUrl?: string | null;
            doorFeeMinor?: number;
            currency?: string;
        };
        PreorderResolution: {
            place: components["schemas"]["PreorderPlace"];
            /** @description Next-day metro city (geo zones) — never pre-order */
            metro: boolean;
            /** @description A published window covers the place (even when switched off) */
            covered: boolean;
            /** @description Window found, master switch on, window active and the area switched on */
            on: boolean;
            /** @description UPCOMING, OPEN or CLOSED; null without a window */
            stage?: string | null;
            /** @description closesAt when open, opensAt when upcoming, the delivery day when closed */
            countdownTo?: string | null;
            window?: components["schemas"]["PreorderWindowCard"] | null;
            /**
             * @description What the storefront does when `on` is false
             * @enum {string}
             */
            fallback: "SHIP" | "WAITLIST";
            placements: components["schemas"]["PreorderPlacements"];
            /** @description "Missed it? Call …" */
            supportPhone?: string;
        };
        /** @description Pre-order block of a PREORDER package (relayed from the cart service), money in minor units. */
        CartPreorder: components["schemas"]["PreorderTerms"] & {
            windowId?: string;
            code?: string;
            name?: string;
            /** @enum {string} */
            stage?: "UPCOMING" | "OPEN" | "CLOSED";
            /** Format: date-time */
            opensAt?: string;
            /** Format: date-time */
            closesAt?: string;
            /** Format: date */
            deliversOn?: string;
            timeZone?: string;
            /** @enum {string} */
            fulfilment?: "DOOR" | "PICKUP";
            /** @description Fee of the chosen fulfilment */
            feeMinor?: number;
            feeRule?: components["schemas"]["DeliveryFeeRuleRef"];
            options?: {
                /** @enum {string} */
                fulfilment: "DOOR" | "PICKUP";
                feeMinor: number;
                rule?: components["schemas"]["DeliveryFeeRuleRef"];
            }[];
        };
        PlaceOrderRequest: {
            attribution?: components["schemas"]["Attribution"];
            addressId: string;
            /** @description A saved Stripe PaymentMethod id (or a new one from PaymentSheet); omit when paying with a wallet or newPaymentMethod */
            paymentMethodId?: string;
            /** @enum {string} */
            wallet?: "apple_pay" | "google_pay" | "link";
            /** @description Pay with a new method entered in Stripe's Payment Element (deferred-intent flow). Send neither paymentMethodId nor wallet; the storefront calls elements.submit() first, then confirms paymentClientSecret with stripe.confirmPayment({elements}) */
            newPaymentMethod?: boolean;
            /** @description With newPaymentMethod, save the method on the shopper's Stripe Customer for future purchases (the order's PaymentIntent gets setup_future_usage=off_session, so the Payment Element is created with setupFutureUsage off_session) */
            savePaymentMethod?: boolean;
            gift?: boolean;
            /**
             * @description Pay with my AfroTransact credit first (S320). Credit is spent, up to the order total, when the order is placed
             *     (once per order, never more than the balance); the card pays only the rest. When credit pays everything
             *     (`summary.credit.cardDueMinor` is 0) no payment method is needed: send none, the answer has no
             *     `paymentClientSecret`, and the order is placed without a card (then `POST …/confirm` returns `placed`).
             */
            useCredit?: boolean;
            /** @description Coupon validated and redeemed by orders with the offers service (released if the order fails or is cancelled); 422 with errors.couponCode when it does not apply */
            couponCode?: string;
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
        /** @description One line of the credit history; the balance is the sum of the lines. The storefront words it from kind and source. */
        CreditEntry: {
            /** Format: uuid */
            id: string;
            /** @enum {string} */
            kind: "GRANT" | "SPEND" | "REFUND" | "ADJUSTMENT";
            /** @enum {string} */
            source: "REFERRAL" | "GIFT_CARD" | "MEMBERSHIP" | "REFUND" | "STAFF" | "PROMO" | "ORDER";
            /**
             * Format: int64
             * @description Signed: credit added is positive, credit spent negative
             */
            amountMinor: number;
            currency: string;
            /** Format: int64 */
            balanceAfterMinor: number;
            /** Format: date-time */
            createdAt: string;
        };
        CreditAccount: {
            /** Format: int64 */
            balanceMinor: number;
            currency: string;
            entries: components["schemas"]["CreditEntry"][];
            /** @description Pass as `before` for older entries; null on the last page */
            nextBefore: string | null;
        };
        /** @description What the shopper's credit would pay of this order if used (payments decides when the order is placed) */
        CheckoutCredit: {
            /** Format: int64 */
            availableMinor: number;
            /**
             * Format: int64
             * @description The balance up to the total
             */
            appliedMinor: number;
            /**
             * Format: int64
             * @description What the card pays then; 0 means no payment method is needed
             */
            cardDueMinor: number;
        };
        CreditRefundChoice: {
            orderNumber: string;
            cardRefundsAsCredit: boolean;
            /**
             * Format: int64
             * @description What the card paid for the order
             */
            cardPartMinor: number;
            currency: string;
        };
        GiftCardConfig: {
            /** Format: int64 */
            minMinor: number;
            /** Format: int64 */
            maxMinor: number;
            currency: string;
            presetsMinor: number[];
            /** @description Staff-written terms by language (en, fr, es, ar, yo); empty means the page shows its built-in terms */
            terms: {
                [key: string]: string;
            };
        };
        /** @description A gift card as its buyer sees it. The code is never returned; the recipient gets it by email on the send date. */
        GiftCard: {
            /** Format: uuid */
            id: string;
            /** @enum {string} */
            status: "PENDING_PAYMENT" | "ACTIVE" | "REDEEMED" | "VOID" | "PAYMENT_FAILED";
            /** Format: int64 */
            amountMinor: number;
            currency: string;
            recipientEmail: string;
            recipientName?: string | null;
            senderName?: string | null;
            message?: string | null;
            /** Format: date-time */
            sendAt: string;
            /** @enum {string} */
            deliveryStatus: "SCHEDULED" | "SENT";
            /** Format: date-time */
            sentAt?: string | null;
            redeemed: boolean;
            /** Format: date-time */
            createdAt: string;
        };
        GiftCardPage: {
            items: components["schemas"]["GiftCard"][];
            nextCursor: string | null;
        };
        GiftCardPurchase: components["schemas"]["GiftCard"] & {
            /** @description Confirm the payment with Stripe Elements; null when it was already paid */
            clientSecret: string | null;
            publishableKey: string;
        };
        GiftCardPurchaseRequest: {
            /**
             * Format: int64
             * @description Within the staff-set limits (default 10.00 to 500.00)
             */
            amountMinor: number;
            recipientEmail: string;
            recipientName?: string;
            senderName?: string;
            message?: string;
            /**
             * Format: date-time
             * @description When to email the card; omit to send it now (at most a year ahead)
             */
            sendAt?: string;
            /**
             * @description Language of the email
             * @enum {string}
             */
            locale?: "en" | "fr" | "es" | "ar" | "yo";
        };
        GiftCardRedeemRequest: {
            /** @example ABCD-EFGH-JKLM-2345 */
            code: string;
        };
        GiftCardRedemption: {
            /** Format: int64 */
            redeemedMinor: number;
            /**
             * Format: int64
             * @description The credit balance after redeeming
             */
            balanceMinor: number;
            currency: string;
        };
        OrderConfirmation: {
            orderNumber: string;
            /**
             * @description awaiting_payment: confirm paymentClientSecret with Stripe, then POST …/confirm; processing: poll
             * @enum {string}
             */
            status: "placed" | "awaiting_payment" | "processing" | "requires_action";
            /** @description The order's PaymentIntent client secret (always present once orders created it) */
            paymentClientSecret?: string | null;
            totalMinor: number;
            currency: string;
            /**
             * Format: int64
             * @description The part of the total paid with AfroTransact credit (S320); the card pays the rest
             */
            creditMinor?: number;
            /** @description The "You're in" facts when the order holds a pre-order package (relayed from orders) */
            preorder?: {
                /** @example LBK-1042 */
                number?: string;
                windowName?: string;
                /** Format: date */
                deliversOn?: string;
                /** Format: date-time */
                closesAt?: string;
                timeZone?: string;
                /** @description Units in the same order that ship now */
                otherItemCount?: number;
            } | null;
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
        PaymentSheetKeys: {
            customerId: string;
            ephemeralKey: string;
            publishableKey: string;
        };
        /**
         * @description How our driver handed the parcel over, shown on a delivered shipment. Null for carrier deliveries (the carrier's
         *     tracking is the record), pickups and deliveries staff confirmed by hand. The photo and signature links are
         *     short-lived (about 5 minutes): read the order again for fresh ones. The driver's GPS position is for staff only.
         */
        DeliveryProof: {
            /**
             * @description To the customer (who gave their code), to someone else (name, relationship, signature), or left in a safe place
             * @enum {string}
             */
            method: "CUSTOMER" | "SOMEONE_ELSE" | "SAFE_PLACE";
            recipientName: string | null;
            relationship: string | null;
            safePlace: string | null;
            /**
             * Format: uri
             * @description Proof photo; null when none or it cannot be shown right now
             */
            photoUrl: string | null;
            /**
             * Format: uri
             * @description Recipient's signature (someone-else handovers)
             */
            signatureUrl: string | null;
            /**
             * Format: date-time
             * @description When it was delivered
             */
            at: string;
        } | null;
        TimelineEvent: {
            status: string;
            label: string;
            at: string;
        };
        OrderLineView: {
            lineId: string;
            atin: string;
            title: string;
            imageUrl: string | null;
            quantity: number;
            unitPriceMinor: number;
            itemsMinor: number;
            /** @description Refunded on this line so far (returns, cancellations, claims) */
            refundedMinor?: number;
        };
        /** @description What was charged and refunded (payments); shippingTaxMinor is the part of the tax charged on delivery */
        OrderPayment: {
            status: string;
            capturedMinor: number;
            refundedMinor: number;
            refundableMinor: number;
            shippingTaxMinor: number;
            /**
             * Format: int64
             * @description The part of the order paid with AfroTransact credit (S320); the card paid the rest
             */
            creditMinor: number;
            /** @description The card part of this order's refunds comes back as credit (the shopper's one-way choice) */
            cardRefundsAsCredit: boolean;
        };
        OrderDetail: {
            orderNumber: string;
            placedAt: string;
            status: string;
            currency: string;
            itemsMinor: number;
            shippingMinor: number;
            taxMinor: number;
            discountMinor: number;
            totalMinor: number;
            shipTo: string;
            shippingAddress: {
                name: string;
                line1: string;
                line2: string;
                city: string;
                state: string;
                zip: string;
            } | null;
            shipments: {
                shipmentId: string;
                label: string;
                /** @description placed, confirmed, packed, ready, picked_up, in_transit, out_for_delivery, delivered, delivery_failed, returned, cancelled */
                status: string;
                statusLabel: string;
                items: string;
                sellerName: string;
                /** @enum {string} */
                deliveryMethod: "carrier" | "express" | "afrotransact_delivery" | "pickup";
                /** @description Cancel is offered: before the parcel is packed and inside the cancellation window */
                cancellable: boolean;
                /**
                 * Format: date-time
                 * @description When Cancel stops being offered (the window's end); null when it is not offered now
                 */
                cancellableUntil?: string | null;
                carrier: string | null;
                trackingNumber: string | null;
                promisedFrom: string | null;
                promisedBy: string | null;
                deliveredAt: string | null;
                proof?: components["schemas"]["DeliveryProof"];
                /** @description One-time handover code (hand delivery or perishables) */
                deliveryCode: string | null;
                lines: components["schemas"]["OrderLineView"][];
                timeline: components["schemas"]["TimelineEvent"][];
                /** @description A return can be started (show "Return items") */
                returnable?: boolean;
                /** Format: date-time */
                returnWindowEndsAt?: string | null;
                /** @description Only "Report a problem" is possible */
                problemReportOnly?: boolean;
                /** @description AfroTransact Delivery from the seller's store (S255); null for other shipments */
                localDelivery?: components["schemas"]["OrderLocalDelivery"] | null;
                /**
                 * @description The delivery fee rule the shipping was charged by, as it was when the order was placed (ADR 0005 §3):
                 *     later edits of the rule never change it. Null for flat rates and for AfroTransact Delivery from a
                 *     seller's store (see localDelivery).
                 */
                deliveryFeeRule?: components["schemas"]["DeliveryFeeRuleRef"] | null;
                /** @description Where a pickup shipment is collected (carrier-gateway pickup location); null for other shipments */
                pickup?: null | {
                    id: string;
                    name: string;
                    address: string;
                    hours: string;
                    ready: string;
                    /** @enum {string} */
                    setBy: "platform" | "seller";
                };
            }[];
            payment?: components["schemas"]["OrderPayment"];
        };
        OrderTracking: {
            orderNumber: string;
            placedAt: string;
            totalMinor: number;
            currency: string;
            shipments: {
                shipmentId: string;
                label: string;
                status: string;
                eta: string | null;
                /** @enum {string} */
                method: "carrier" | "express" | "afrotransact_delivery" | "pickup";
                /** @enum {string|null} */
                handover: "hand" | "door" | "desk" | null;
                perishable: boolean;
                deliveryCode: string | null;
                driver: {
                    name: string;
                    initials: string;
                } | null;
                carrier: string | null;
                trackingNumber: string | null;
                items: {
                    atin: string;
                    title: string;
                    quantity: number;
                    priceMinor: number;
                    returnable: boolean;
                }[];
                timeline: components["schemas"]["TimelineEvent"][];
            }[];
        };
        /**
         * @description Seller fault (photos required for the first four): DAMAGED, DEFECTIVE, WRONG_ITEM, EXPIRED_OR_SPOILED, NOT_AS_DESCRIBED, MISSING_PARTS, ARRIVED_LATE. No fault: the rest.
         * @enum {string}
         */
        ReturnReason: "DAMAGED" | "DEFECTIVE" | "WRONG_ITEM" | "NOT_AS_DESCRIBED" | "MISSING_PARTS" | "EXPIRED_OR_SPOILED" | "ARRIVED_LATE" | "NO_LONGER_NEEDED" | "ORDERED_BY_MISTAKE" | "BETTER_PRICE_AVAILABLE";
        /** @enum {string} */
        ReturnMethod: "DROP_OFF" | "DRIVER_PICKUP" | "NO_RETURN";
        /**
         * @description One delivered shipment, a method and a reason per line (`shipmentId`, `method`, `lines`). The customer app's
         *     short form `{orderNumber, atin, reason}` returns one unit of that item with its first allowed method.
         *     Send an `Idempotency-Key` header to make retries safe (one is derived otherwise).
         */
        StartReturnRequest: {
            orderNumber: string;
            shipmentId?: string;
            method?: components["schemas"]["ReturnMethod"];
            lines?: {
                lineId: string;
                quantity: number;
                reason: components["schemas"]["ReturnReason"];
                comment?: string;
            }[];
            atin?: components["schemas"]["Atin"];
            reason?: components["schemas"]["ReturnReason"];
        };
        ReturnEligibility: {
            orderNumber: string;
            currency: string;
            shipments: {
                shipmentId: string;
                sellerName: string;
                deliveredAt: string | null;
                lines: {
                    lineId: string;
                    atin: string;
                    title: string;
                    quantity: number;
                    maxQuantity: number;
                    unitPriceMinor: number;
                    eligible: boolean;
                    /** @description Food / perishables: report a problem (seller-fault reasons, refund without return) */
                    reportProblemOnly: boolean;
                    sellerFaultOnly: boolean;
                    windowEndsAt: string | null;
                    methods: components["schemas"]["ReturnMethod"][];
                    ineligibleReason: string | null;
                }[];
            }[];
        };
        ReturnDetail: components["schemas"]["ReturnSummary"] & {
            lines: {
                lineId: string;
                atin: string;
                title: string;
                quantity: number;
                unitPriceMinor: number;
                reason: string;
                outcome: string | null;
            }[];
            quote: {
                itemsMinor: number;
                shippingMinor: number;
                taxMinor: number;
                restockingFeeMinor: number;
                totalMinor: number;
            } | null;
        };
        CodeInput: {
            code: string;
        };
        TotpSetup: {
            secret: string;
            otpauthUri: string;
        };
        RecoveryCodes: {
            /** @description Shown once; empty when existing codes were kept */
            recoveryCodes: string[];
        };
        LinkedAccounts: {
            accounts: {
                provider: string;
                email: string | null;
                canUnlink: boolean;
            }[];
            hasPassword: boolean;
        };
        SecurityOverview: {
            mfa: {
                totp: boolean;
                sms: boolean;
                smsPhone: string | null;
                recoveryCodesLeft: number;
                /** @description Two-step verification is required at sign-in (always for staff) */
                required: boolean;
                /** @description Two-step verification may be turned off altogether (never for staff) */
                mayDisable: boolean;
                /** @description The authenticator app may be turned off now (staff only while SMS codes stay on) */
                mayRemoveTotp: boolean;
                /** @description SMS codes may be turned off now (staff only while the authenticator app stays on) */
                mayRemoveSms: boolean;
            };
            passkeys: components["schemas"]["Passkey"][];
            /** @description Browsers that skip the second factor until expiresAt ("Trust this browser") */
            trustedBrowsers: {
                id: string;
                /** @description Browser and system from the user agent, e.g. "Chrome on macOS" */
                label: string;
                createdAt: string | null;
                expiresAt: string | null;
            }[];
            sessions: {
                id: string;
                clientName: string;
                signedInAt: string | null;
                lastActiveAt: string | null;
                current: boolean;
            }[];
            devices: {
                id: string;
                platform: string;
                deviceName: string;
                lastSeenAt: string | null;
            }[];
            linkedAccounts: components["schemas"]["LinkedAccounts"];
            /** @description identity's hosted passkey registration page (/webauthn/register) */
            passkeyRegistrationUrl: string;
        };
        Passkey: {
            credentialId: string;
            label: string;
            createdAt: string | null;
            lastUsedAt: string | null;
        };
        SignInEvent: {
            /** Format: date-time */
            at: string;
            /** @description PASSWORD, MFA_TOTP, MFA_SMS, MFA_RECOVERY_CODE, PASSKEY, LOGIN_TICKET */
            method: string;
            /** @description SUCCEEDED, FAILED, MFA_REQUIRED, LOCKED, BLOCKED, … */
            outcome: string;
            /** @description Which app signed in, e.g. "AfroTransact web" or "Driver app" */
            app: string;
            /** @description Browser and system from the user agent */
            device: string;
            ip: string | null;
        };
        AccountProfile: {
            id: string;
            /** @enum {string} */
            kind: "customer" | "seller" | "staff";
            firstName: string;
            lastName: string;
            email: string;
            emailVerified: boolean;
            /** @description A company (staff) address managed by AfroTransact IT */
            emailManaged: boolean;
            initials: string;
            /** @description The account's mobile number (E.164) */
            phone: string | null;
            phoneVerified: boolean;
            jobTitle: string | null;
            /** @description Seller and provider organisations with this person's role (read-only) */
            memberships: {
                orgId: string;
                name: string;
                /** @enum {string} */
                kind: "SELLER" | "PROVIDER" | "BOTH";
                status: string;
                roleKey: string;
                roleLabel: string;
                owner: boolean;
                /** @description The organisation this person currently acts for */
                active: boolean;
            }[];
            /** @description Platform (staff) roles (read-only) */
            roles: {
                key: string;
                label: string;
            }[];
        };
        AccountProfileInput: {
            firstName?: string;
            lastName?: string;
            phone?: string;
            jobTitle?: string | null;
        };
        AccountPreferences: {
            /** @description BCP 47 tag, e.g. en-US or fr-CA; null = platform default */
            locale: string | null;
            /** @description IANA zone, e.g. America/Chicago; null = market zone */
            timeZone: string | null;
            /** @enum {string} */
            dateFormat: "AUTO" | "MDY" | "DMY" | "YMD";
            /** @enum {string} */
            numberFormat: "AUTO" | "COMMA_DOT" | "DOT_COMMA" | "SPACE_COMMA";
            /** @enum {string} */
            tableDensity: "COMFORTABLE" | "COMPACT";
        };
        AlertPreference: {
            /** @enum {string} */
            topic: "seller.orders" | "seller.inventory" | "seller.payouts" | "seller.compliance" | "seller.claims" | "staff.assigned" | "staff.escalations" | "staff.approvals";
            /** @enum {string} */
            channel: "email" | "sms";
            enabled: boolean;
            /** @description Always on (the platform must reach you on this channel) */
            locked: boolean;
        };
        QuietHours: {
            enabled: boolean;
            start: string;
            end: string;
        };
        AlertPreferences: {
            /** @enum {string} */
            audience: "seller" | "staff";
            preferences: components["schemas"]["AlertPreference"][];
            quietHours: components["schemas"]["QuietHours"];
        };
        AlertPreferencesInput: {
            preferences: {
                topic: string;
                /** @enum {string} */
                channel: "email" | "sms";
                enabled: boolean;
            }[];
            quietHours?: components["schemas"]["QuietHours"];
        };
        NotificationPreferences: {
            email: boolean;
            sms: boolean;
            push: boolean;
            marketingOptIn: boolean;
        };
        ServicePolicy: {
            /**
             * @example FLEXIBLE
             * @example MODERATE
             * @example STRICT
             * @example CUSTOM
             */
            kind: string;
            label: string;
            text: string;
        };
        ServiceMember: {
            id: string;
            displayName: string;
        };
        /** @enum {string} */
        ServiceMode: "IN_PERSON" | "AT_HOME" | "VIRTUAL";
        ServiceSearch: {
            zip: string;
            city: string;
            services: {
                serviceId: string;
                orgId: string;
                name: string;
                providerName: string;
                description: string;
                category: string;
                durationMinutes: number;
                priceFromMinor: number;
                depositMinor: number;
                currency: string;
                modes: components["schemas"]["ServiceMode"][];
                rating: number;
                ratingCount: number;
                nextAvailable: string | null;
                location: string;
            }[];
        };
        ServiceDetail: {
            id: string;
            orgId: string;
            providerName: string;
            name: string;
            description: string;
            category: string;
            durationMinutes: number;
            priceMinor: number;
            depositMinor: number;
            balanceMinor: number;
            currency: string;
            modes: components["schemas"]["ServiceMode"][];
            addOns: {
                id: string;
                name: string;
                priceMinor: number;
                extraMinutes: number;
            }[];
            policy: components["schemas"]["ServicePolicy"];
            instantBooking: boolean;
            team: components["schemas"]["ServiceMember"][];
        };
        AvailableDay: {
            /** Format: date */
            date: string;
            slots: number;
        };
        AvailableSlot: {
            /** Format: date-time */
            start: string;
            /** Format: date-time */
            end: string;
            memberIds: string[];
        };
        BookingQuote: {
            serviceId: string;
            serviceName: string;
            providerName: string;
            durationMinutes: number;
            priceMinor: number;
            depositNowMinor: number;
            balanceLaterMinor: number;
            currency: string;
            mode: string;
            addOns: string[];
            policy: string;
            policyText: string;
            instantBooking: boolean;
            providerAnswersWithinMinutes: number | null;
        };
        BusinessPage: {
            slug: string;
            /** @enum {string} */
            kind: "SELLER" | "PROVIDER";
            name: string;
            description: string | null;
            /** @description #rrggbb, or null for the platform colour */
            brandColor: string | null;
            logoUrl: string | null;
            contactEmail: string | null;
            /** @description False until the business is approved */
            indexable: boolean;
            /** @description A seller's live products (at most twelve, best match first); empty for providers */
            products: components["schemas"]["ProductSummary"][];
            services: {
                serviceId: string;
                name: string;
                durationMinutes: number;
                /** Format: int64 */
                priceMinor: number;
                currency: string;
            }[];
        };
        ProviderPage: {
            orgId: string;
            displayName: string;
            timeZone: string;
            location: string;
            policy: components["schemas"]["ServicePolicy"];
            instantBooking: boolean;
            team: components["schemas"]["ServiceMember"][];
            services: components["schemas"]["ServiceDetail"][];
        };
        CancellationQuote: {
            refundSharePercent: number;
            depositMinor: number;
            refundMinor: number;
            keptMinor: number;
            /** @description The balance hold on the card is released */
            releaseHold: boolean;
            policy: string;
            explanation: string;
            currency: string;
        };
        CustomerBooking: {
            id: string;
            reference: string;
            orgId: string;
            /** @description PENDING_PAYMENT, REQUESTED, CONFIRMED, COMPLETED, NO_SHOW, DECLINED, EXPIRED, RESCHEDULE_REQUESTED, RESCHEDULE_PROPOSED, CANCELLED_CUSTOMER, CANCELLED_PROVIDER */
            status: string;
            statusLabel: string;
            startAt: string;
            endAt: string;
            proposedStartAt: string | null;
            location: string;
            notes: string;
            service: {
                serviceId: string;
                name: string;
                durationMinutes: number;
                priceMinor: number;
                depositMinor: number;
                balanceMinor: number;
                currency: string;
                mode: string;
                providerName: string;
                memberName: string;
                addOns: string[];
            };
            policy: components["schemas"]["ServicePolicy"];
            payment: {
                state: string;
                depositCapturedMinor: number;
                refundedMinor: number;
                balanceState: string;
            };
            canCancel: boolean;
            canReschedule: boolean;
            canReview: boolean;
            awaitingPayment: boolean;
        };
        BookingRequest: {
            serviceId: string;
            /** @description Preferred team member; empty = anyone free */
            memberId?: string;
            /**
             * Format: date-time
             * @description A start time from the slots endpoint
             */
            startAt: string;
            mode: components["schemas"]["ServiceMode"];
            addOnIds?: string[];
            /** @description Required for home visits */
            location?: string;
            notes?: string;
        };
        BookingCreated: {
            booking: components["schemas"]["CustomerBooking"];
            /** @description Present when a deposit must be confirmed with Stripe.js / PaymentSheet */
            payment: {
                clientSecret: string;
                publishableKey: string;
            } | null;
        };
        BookingReview: {
            bookingId: string;
            rating: number;
            text: string;
        };
        LolaChatRequest: {
            /** @description From a previous turn's done event; omit to start a conversation */
            sessionId?: string;
            message: string;
            zip?: string;
            language?: string;
        };
        LolaTurn: {
            /**
             * @example user
             * @example assistant
             */
            role: string;
            content: string;
            at: string;
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
                 *       "type": "https://developers.afrotransact.com/problems/bff.validation-failed",
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
                 *       "type": "https://developers.afrotransact.com/problems/bff.stock-limit",
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
        BookingId: string;
        ServiceId: string;
        /** @description Makes retries safe (the same key replays the same result); derived per customer and minute when omitted. */
        OptionalIdempotencyKey: string;
        /** @description Where to return after sign-in: a path on the storefront, or an absolute URL on Seller Center's or the staff console's origin (sign-in then completes on that app's own callback). Any other absolute or protocol-relative URL is replaced by the storefront home page. */
        ReturnTo: string;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getMaintenance: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Maintenance status */
            200: {
                headers: {
                    /** @example no-store */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["MaintenanceStatus"];
                };
            };
        };
    };
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
    getLegalDocument: {
        parameters: {
            query?: {
                /**
                 * @description An archived or the current version
                 * @example 3.1
                 */
                version?: string;
                locale?: string;
            };
            header?: never;
            path: {
                /** @example sms-terms */
                key: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The document */
            200: {
                headers: {
                    /** @example public, max-age=60 */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["LegalPage"];
                };
            };
            404: components["responses"]["Problem"];
        };
    };
    listHelpArticles: {
        parameters: {
            query?: {
                topic?: components["schemas"]["HelpTopicKey"];
                /** @description Matches titles, summaries and section text */
                q?: string;
                /** @description The previous page's nextCursor */
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One page of articles and the topics with published articles */
            200: {
                headers: {
                    /** @example public, max-age=60 */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HelpArticlePage"];
                };
            };
            422: components["responses"]["Problem"];
        };
    };
    getHelpArticle: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @example shipping */
                slug: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The page */
            200: {
                headers: {
                    /** @example public, max-age=60 */
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["HelpArticle"];
                };
            };
            404: components["responses"]["Problem"];
        };
    };
    getCookiePolicy: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Categories in display order and every cookie we use */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CookiePolicy"];
                };
            };
        };
    };
    recordConsent: {
        parameters: {
            query?: never;
            header?: {
                "Sec-GPC"?: "1";
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ConsentRequest"];
            };
        };
        responses: {
            /** @description Stored decision after normalisation */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ConsentRecord"];
                };
            };
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    submitPrivacyRequest: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PrivacyRequest"];
            };
        };
        responses: {
            /** @description Request received */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PrivacyRequestReceipt"];
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
                /** @description ISO 3166-1 alpha-2 country; default: the market's (US) */
                country?: string;
                /** @description Session token of the previous answer of this address entry (omit on the first keystroke) */
                sessionToken?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Up to 5 suggestions and the session token */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AddressSuggestions"];
                };
            };
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    resolveAddressSuggestion: {
        parameters: {
            query?: {
                /** @description The session token of the suggestions */
                sessionToken?: string;
            };
            header?: never;
            path: {
                /** @description Suggestion id (g:…, o:…, f:…) */
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Street, unit, city, state, ZIP, country and coordinates */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PostalAddress"];
                };
            };
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
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
                /** @description Blend sponsored products into the page (results pages). Send false for machine-made queries. */
                ads?: boolean;
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
    recordSearchClick: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SearchClickRequest"];
            };
        };
        responses: {
            /** @description Recorded, or recognised as a duplicate */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SearchClickResult"];
                };
            };
            400: components["responses"]["Problem"];
            422: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
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
            401: components["responses"]["Problem"];
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
                /** @description Where to return after sign-in: a path on the storefront, or an absolute URL on Seller Center's or the staff console's origin (sign-in then completes on that app's own callback). Any other absolute or protocol-relative URL is replaced by the storefront home page. */
                returnTo?: components["parameters"]["ReturnTo"];
                /** @description OIDC prompt. `create` opens identity's sign-up page ("Create account"); `login` asks to sign in again. */
                prompt?: "login" | "create";
                /** @description Email to prefill on identity's pages (OIDC login_hint). */
                login_hint?: string;
                /** @description Organisation invitation token from an invite link; the account created on identity's sign-up page joins the organisation without an email code. */
                invitation?: string;
                /**
                 * @description Entry point the person came from (S286). `sell` ("Start selling") and `provide` ("Offer your services")
                 *     brand identity's sign-up page for it ("Create your account to start selling") and add the seller or
                 *     provider applicant role to the account it creates. Other values are dropped.
                 */
                intent?: "sell" | "provide";
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
                /** @description Where to return after sign-in: a path on the storefront, or an absolute URL on Seller Center's or the staff console's origin (sign-in then completes on that app's own callback). Any other absolute or protocol-relative URL is replaced by the storefront home page. */
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
            /** @description Signed in; redirect to the original returnTo on the app that started sign-in (or the storefront's /signin?error=… on failure) */
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
    nativePasswordSignIn: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NativePasswordRequest"];
            };
        };
        responses: {
            /** @description Second factor needed, or signed in */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NativeSignInResult"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["Problem"];
            429: components["responses"]["Problem"];
        };
    };
    nativeSendSmsCode: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NativeSmsRequest"];
            };
        };
        responses: {
            /** @description Code sent */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        /**
                         * @description Masked phone
                         * @example •••• 0142
                         */
                        sentTo?: string;
                        /** Format: date-time */
                        expiresAt?: string;
                    };
                };
            };
            410: components["responses"]["Problem"];
            429: components["responses"]["Problem"];
        };
    };
    nativeVerifySecondFactor: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NativeVerifyRequest"];
            };
        };
        responses: {
            /** @description Signed in */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NativeSignInResult"];
                };
            };
            410: components["responses"]["Problem"];
            422: components["responses"]["Problem"];
            429: components["responses"]["Problem"];
        };
    };
    signOut: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["SignOutRequest"];
            };
        };
        responses: {
            /** @description Signed out */
            200: {
                headers: {
                    "Set-Cookie"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SignOutResult"];
                };
            };
        };
    };
    signedOut: {
        parameters: {
            query?: {
                /** @description The path on the app to come back to (a relative path; anything else is ignored). */
                state?: string;
            };
            header?: never;
            path: {
                app: "storefront" | "seller-center" | "staff-console";
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description To the storefront page with `signedOut=1`, or to identity's sign-in for Seller Center and the staff console */
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
    startPhoneVerification: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Code sent */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PhoneCodeSent"];
                };
            };
            401: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["Problem"];
            429: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    confirmPhoneVerification: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    code: string;
                };
            };
        };
        responses: {
            /** @description Verified */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PhoneVerification"];
                };
            };
            401: components["responses"]["Problem"];
            410: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
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
            503: components["responses"]["Problem"];
        };
    };
    createSetupIntent: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": {
                    types?: ("card" | "us_bank_account" | "cashapp" | "link")[];
                };
            };
        };
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
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
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
            409: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
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
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
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
        requestBody?: {
            content: {
                "application/json": {
                    reason?: string;
                };
            };
        };
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
            query?: {
                /** @description Only this order's returns */
                orderNumber?: string;
            };
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
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    startReturn: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (the same key replays the same result); derived per customer and minute when omitted. */
                "Idempotency-Key"?: components["parameters"]["OptionalIdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StartReturnRequest"];
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
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listStockAlerts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Alerts */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items: components["schemas"]["StockAlertItem"][];
                    };
                };
            };
            401: components["responses"]["Problem"];
        };
    };
    removeStockAlert: {
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
            /** @description Removed */
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
    listRunLocations: {
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
                    "application/json": {
                        items: components["schemas"]["RunLocation"][];
                    };
                };
            };
            503: components["responses"]["Problem"];
        };
    };
    getRunLocation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                slug: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Location page */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunLocationPage"];
                };
            };
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    subscribeRunAlerts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** Format: uuid */
                    locationId: string;
                    /** Format: email */
                    email: string;
                    /**
                     * @description Where the sign-up happened, kept on the consent record (default storefront)
                     * @example location-page
                     */
                    source?: string;
                };
            };
        };
        responses: {
            /** @description Accepted; the confirmation link is on its way */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        /** @enum {string} */
                        status: "CONFIRMATION_SENT";
                    };
                };
            };
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    confirmRunAlerts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    token: string;
                };
            };
        };
        responses: {
            /** @description Confirmed */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunSubscription"];
                };
            };
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    unsubscribeRunAlerts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    token: string;
                    channels?: components["schemas"]["RunChannel"][];
                };
            };
        };
        responses: {
            /** @description Withdrawn */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunSubscription"];
                };
            };
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    listMyRunSubscriptions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Subscriptions */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items: components["schemas"]["RunSubscription"][];
                    };
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    subscribeMyRunAlerts: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** Format: uuid */
                    locationId: string;
                    channels: components["schemas"]["RunChannel"][];
                    /** @example account */
                    source?: string;
                };
            };
        };
        responses: {
            /** @description Subscription */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunSubscription"];
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    unsubscribeMyRunAlerts: {
        parameters: {
            query?: {
                channel?: components["schemas"]["RunChannel"][];
            };
            header?: never;
            path: {
                locationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Subscription with the withdrawals recorded */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunSubscription"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listRunAdvertorials: {
        parameters: {
            query?: {
                zip?: string;
                lat?: number;
                lng?: number;
                country?: string;
                region?: string;
                city?: string;
                /** @description An anonymous id the storefront keeps per browser */
                visitor?: string;
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
                    "Cache-Control"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items: components["schemas"]["RunAdvertorial"][];
                    };
                };
            };
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    recordRunAdvertorialEvent: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                advertorialId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** @enum {string} */
                    type: "VIEW" | "CLICK";
                };
            };
        };
        responses: {
            /** @description Counted */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    getAccountCredit: {
        parameters: {
            query?: {
                /** @description Entries older than this */
                before?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Balance and a page of history */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CreditAccount"];
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    chooseCreditRefunds: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Chosen */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CreditRefundChoice"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getGiftCardConfig: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Limits and terms */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GiftCardConfig"];
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    listMyGiftCards: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description My gift cards */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GiftCardPage"];
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    buyGiftCard: {
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
                "application/json": components["schemas"]["GiftCardPurchaseRequest"];
            };
        };
        responses: {
            /** @description Card created, payment pending */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GiftCardPurchase"];
                };
            };
            401: components["responses"]["Problem"];
            402: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    confirmGiftCard: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                giftCardId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The card (ACTIVE once paid) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GiftCard"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    redeemGiftCard: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["GiftCardRedeemRequest"];
            };
        };
        responses: {
            /** @description Redeemed */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["GiftCardRedemption"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getCheckoutSummary: {
        parameters: {
            query?: {
                addressId?: string;
                /**
                 * @description Per-package delivery option keys for shipping and estimated tax (repeat the parameter or comma-separate
                 *     entries). Each value is `packageId:optionKey` (for example `pkg_1:ground,pkg_2:pickup`).
                 */
                packageDelivery?: string[];
                /** @description Optional coupon to price against the cart (offers validate; does not redeem until place order). */
                couponCode?: string;
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
            503: components["responses"]["Problem"];
        };
    };
    setPreorderFulfilment: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    windowId: string;
                    /** @enum {string} */
                    fulfilment: "DOOR" | "PICKUP";
                };
            };
        };
        responses: {
            200: components["responses"]["Cart"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    resolvePreorder: {
        parameters: {
            query?: {
                /** @description ISO 3166-1 alpha-2 (default the market) */
                country?: string;
                /** @description State / region code */
                region?: string;
                city?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Resolution */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PreorderResolution"];
                };
            };
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listOpenPreorders: {
        parameters: {
            query?: never;
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
                    "application/json": {
                        windows: components["schemas"]["PreorderListItem"][];
                        placements: components["schemas"]["PreorderPlacements"];
                    };
                };
            };
            503: components["responses"]["Problem"];
        };
    };
    joinPreorderWaitlist: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** Format: email */
                    email: string;
                    place?: components["schemas"]["PreorderPlace"];
                };
            };
        };
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        /** @enum {string} */
                        status: "subscribed";
                        /** @example Lubbock */
                        city: string;
                        /** @example US/TX/Lubbock */
                        area?: string;
                    };
                };
            };
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    leavePreorderWaitlist: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    token: string;
                };
            };
        };
        responses: {
            /** @description Off the waitlist */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        /** @enum {string} */
                        status: "unsubscribed";
                    };
                };
            };
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
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
            503: components["responses"]["Problem"];
        };
    };
    setDefaultPaymentMethod: {
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
            /** @description Now the default */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PaymentMethod"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    createPaymentSheetKeys: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Keys */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PaymentSheetKeys"];
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getOrder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: string;
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
                    "application/json": components["schemas"]["OrderDetail"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    trackOrder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Tracking */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["OrderTracking"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getReturnEligibility: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Eligibility */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReturnEligibility"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getReturn: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                /** @description The return id, or its number (RMA-XXXXXXXX, e.g. scanned from the QR code) */
                rmaId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Return */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReturnDetail"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    cancelReturn: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                rmaId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Cancelled */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ReturnSummary"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getSecurity: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Overview */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SecurityOverview"];
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    changePassword: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    currentPassword?: string;
                    newPassword: string;
                };
            };
        };
        responses: {
            /** @description Changed */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    startTotp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Secret */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["TotpSetup"];
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    disableTotp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Off */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    confirmTotp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CodeInput"];
            };
        };
        responses: {
            /** @description On */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecoveryCodes"];
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    startSmsMfa: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Code sent */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        /** Format: date-time */
                        codeExpiresAt: string | null;
                    };
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    disableSmsMfa: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Off */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    confirmSmsMfa: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CodeInput"];
            };
        };
        responses: {
            /** @description On */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecoveryCodes"];
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    regenerateRecoveryCodes: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Codes, shown once */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RecoveryCodes"];
                };
            };
            401: components["responses"]["Problem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    removePasskey: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                credentialId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Removed */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    renamePasskey: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                credentialId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    label: string;
                };
            };
        };
        responses: {
            /** @description Renamed */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Passkey"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    revokeSession: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                sessionId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Signed out */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    revokeOtherSessions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description How many were signed out */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        revoked: number;
                    };
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    removeDevice: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                deviceId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Removed */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    unlinkAccount: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                provider: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Linked accounts */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["LinkedAccounts"];
                };
            };
            401: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    forgetTrustedBrowsers: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Forgotten */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    forgetTrustedBrowser: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                browserId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Forgotten */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getSignInActivity: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Up to 20 recent sign-in events */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items: components["schemas"]["SignInEvent"][];
                    };
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getAccountProfile: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Profile */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccountProfile"];
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    updateAccountProfile: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AccountProfileInput"];
            };
        };
        responses: {
            /** @description Profile */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccountProfile"];
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getAccountPreferences: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Preferences */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccountPreferences"];
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    saveAccountPreferences: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AccountPreferences"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AccountPreferences"];
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getAlertPreferences: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Alert preferences */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AlertPreferences"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    saveAlertPreferences: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AlertPreferencesInput"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["AlertPreferences"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    leaveOrganisation: {
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
            /** @description Left */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getNotificationPreferences: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Switches */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NotificationPreferences"];
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    saveNotificationPreferences: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NotificationPreferences"];
            };
        };
        responses: {
            /** @description Saved */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["NotificationPreferences"];
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    listMyBookings: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Bookings */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items: components["schemas"]["CustomerBooking"][];
                    };
                };
            };
            401: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getMyBooking: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookingId: components["parameters"]["BookingId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Booking */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CustomerBooking"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getBookingCancellationQuote: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookingId: components["parameters"]["BookingId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Quote */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CancellationQuote"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    cancelMyBooking: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (the same key replays the same result); derived per customer and minute when omitted. */
                "Idempotency-Key"?: components["parameters"]["OptionalIdempotencyKey"];
            };
            path: {
                bookingId: components["parameters"]["BookingId"];
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": {
                    reason?: string;
                };
            };
        };
        responses: {
            /** @description Cancelled */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        booking: components["schemas"]["CustomerBooking"];
                        applied: components["schemas"]["CancellationQuote"];
                    };
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    rescheduleMyBooking: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (the same key replays the same result); derived per customer and minute when omitted. */
                "Idempotency-Key"?: components["parameters"]["OptionalIdempotencyKey"];
            };
            path: {
                bookingId: components["parameters"]["BookingId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** Format: date-time */
                    startAt: string;
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
                    "application/json": components["schemas"]["CustomerBooking"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    answerProposedTime: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (the same key replays the same result); derived per customer and minute when omitted. */
                "Idempotency-Key"?: components["parameters"]["OptionalIdempotencyKey"];
            };
            path: {
                bookingId: components["parameters"]["BookingId"];
                decision: "accept" | "decline";
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Updated */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CustomerBooking"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    reviewMyBooking: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookingId: components["parameters"]["BookingId"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    rating: number;
                    text?: string;
                };
            };
        };
        responses: {
            /** @description Thanks */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BookingReview"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    syncBookingPayment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bookingId: components["parameters"]["BookingId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Booking */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CustomerBooking"];
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getCheckoutStatus: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: string;
            };
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
                    "application/json": components["schemas"]["OrderConfirmation"];
                };
            };
            401: components["responses"]["Problem"];
            402: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    confirmCheckoutPayment: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: string;
            };
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
                    "application/json": components["schemas"]["OrderConfirmation"];
                };
            };
            401: components["responses"]["Problem"];
            402: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    lookupPayLink: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PayLinkToken"];
            };
        };
        responses: {
            /** @description The order and, while awaiting payment, its client secret */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PayLink"];
                };
            };
            404: components["responses"]["Problem"];
            410: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    confirmPayLink: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orderNumber: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PayLinkToken"];
            };
        };
        responses: {
            /** @description awaiting_payment or paid */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["PayLink"];
                };
            };
            404: components["responses"]["Problem"];
            410: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    searchServices: {
        parameters: {
            query?: {
                q?: string;
                category?: string;
                zip?: string;
            };
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
                    "application/json": components["schemas"]["ServiceSearch"];
                };
            };
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getService: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                serviceId: components["parameters"]["ServiceId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Service */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ServiceDetail"];
                };
            };
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getServiceDays: {
        parameters: {
            query?: {
                from?: string;
                days?: number;
                memberId?: string;
                /** @description Comma-separated add-on ids */
                addOnIds?: string;
            };
            header?: never;
            path: {
                serviceId: components["parameters"]["ServiceId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Days */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        days: components["schemas"]["AvailableDay"][];
                    };
                };
            };
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getServiceSlots: {
        parameters: {
            query: {
                date: string;
                memberId?: string;
                /** @description Comma-separated add-on ids */
                addOnIds?: string;
            };
            header?: never;
            path: {
                serviceId: components["parameters"]["ServiceId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Slots */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        slots: components["schemas"]["AvailableSlot"][];
                    };
                };
            };
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getBookingQuote: {
        parameters: {
            query?: {
                /** @description Comma-separated add-on ids */
                addOnIds?: string;
                mode?: "IN_PERSON" | "AT_HOME" | "VIRTUAL";
            };
            header?: never;
            path: {
                serviceId: components["parameters"]["ServiceId"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Quote */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BookingQuote"];
                };
            };
            404: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    getProviderPage: {
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
            /** @description Provider */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ProviderPage"];
                };
            };
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    getBusinessPage: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                slug: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Page */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BusinessPage"];
                };
            };
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
        };
    };
    bookService: {
        parameters: {
            query?: never;
            header?: {
                /** @description Makes retries safe (the same key replays the same result); derived per customer and minute when omitted. */
                "Idempotency-Key"?: components["parameters"]["OptionalIdempotencyKey"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["BookingRequest"];
            };
        };
        responses: {
            /** @description Booked (awaiting the deposit) */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["BookingCreated"];
                };
            };
            401: components["responses"]["Problem"];
            402: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            503: components["responses"]["Problem"];
        };
    };
    chatWithLola: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LolaChatRequest"];
            };
        };
        responses: {
            /** @description Event stream */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "text/event-stream": string;
                };
            };
            401: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
            503: components["responses"]["Problem"];
        };
    };
    getLolaHistory: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                sessionId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Turns */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items: components["schemas"]["LolaTurn"][];
                    };
                };
            };
            401: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
            503: components["responses"]["Problem"];
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
    requestResumeUpload: {
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
                    contentType: "application/pdf";
                    /** Format: int64 */
                    size: number;
                };
            };
        };
        responses: {
            /** @description Upload instructions */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ResumeUpload"];
                };
            };
            422: components["responses"]["ValidationProblem"];
            429: components["responses"]["TooManyRequests"];
        };
    };
    completeResumeUpload: {
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
            /** @description Résumé ready; use assetId as resumeAssetId */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ResumeAsset"];
                };
            };
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
            422: components["responses"]["ValidationProblem"];
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
