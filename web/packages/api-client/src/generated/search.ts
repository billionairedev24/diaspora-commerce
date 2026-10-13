// GENERATED from contracts/openapi/search.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/api-client generate
/* eslint-disable */

export interface paths {
    "/v1/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Search products with filters and facets */
        get: operations["searchProducts"];
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
         * @description Publishes search.result.clicked once per search and product, and for a sponsored result with a valid, unexpired adToken ads.click.recorded once per token (content-service charges the campaign). The query and market of a sponsored click come from the signed token. A customerId is taken from a customer token, or from the body when the caller is a service (the BFF's client-credentials token).
         */
        post: operations["recordSearchClick"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/search/analytics/top-queries": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Most searched queries in a window, with zero results, clicks and CTR */
        get: operations["topSearchQueries"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/search/analytics/zero-results": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Queries that returned nothing, for synonym curation */
        get: operations["zeroResultSearchQueries"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/search/analytics/ctr": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Searches, clicks, click-through and zero-result rates by day */
        get: operations["searchClickThroughByDay"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/search/synonyms": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Rules of the diaspora synonym set (search:analytics.view or search:synonyms.edit) */
        get: operations["listSearchSynonyms"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/search/synonyms/{ruleId}": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ruleId: string;
            };
            cookie?: never;
        };
        get?: never;
        /** Create or replace a rule; search analyzers reload immediately (no reindex) */
        put: operations["putSearchSynonym"];
        post?: never;
        /** Delete a rule */
        delete: operations["deleteSearchSynonym"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/suggest": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Typeahead phrases and up to five products */
        get: operations["suggest"];
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
            code?: string;
            errors?: {
                [key: string]: string;
            };
        };
        Hit: {
            atin: string;
            title: string;
            brand?: string;
            categoryPath?: string;
            sellerName?: string;
            /** @enum {string} */
            sellerKind: "PLATFORM" | "SELLER";
            /** @enum {string} */
            fulfilment: "AFROTRANSACT" | "SELLER";
            /** Format: int64 */
            priceMinor: number;
            currency: string;
            rating?: number;
            reviewCount?: number;
            inStock: boolean;
            /** @description True when 1–5 units are left ("Only N left") */
            lowStock?: boolean;
            /** @description Units available across fulfilment nodes (N in "Only N left") */
            availableQty?: number;
            /**
             * @description LOW_STOCK shows "Only N left"; OUT_OF_STOCK shows "Currently unavailable".
             * @enum {string}
             */
            availability?: "IN_STOCK" | "LOW_STOCK" | "OUT_OF_STOCK";
            deliveryDays?: number;
            imageUrl?: string;
            offerCount?: number;
            score?: number;
            sponsored?: components["schemas"]["Sponsored"];
        };
        /** @description Present on ads only. Render the label and advertiser ("Sponsored · Mama Tee"). */
        Sponsored: {
            /** @constant */
            label: "Sponsored";
            advertiser: string;
            campaignId: string;
            /** @description 1-based position the ad won */
            slot: number;
            /** @description Opaque click token; send it back as adToken with the click */
            token: string;
            reason: components["schemas"]["AdReason"];
        };
        /** @description Why this ad was shown (no bids or prices are disclosed) */
        AdReason: {
            /** @enum {string} */
            targeting: "KEYWORD" | "CATEGORY";
            /** @description The keyword or category that matched */
            matched: string;
            /** @description Relevance to the query relative to the best organic result */
            qualityScore: number;
            auctionRank: number;
            /** @description Eligible ads in the auction */
            competitors: number;
            eligibility: string[];
            summary: string;
        };
        ClickRequest: {
            /** @description searchId of the results page */
            searchId: string;
            q?: string;
            /** @default us */
            market: string;
            atin: string;
            /** @description 1-based position on the page */
            position: number;
            /** @description sponsored.token of an ad */
            adToken?: string;
            /** @description Shopper (accepted from service callers only) */
            customerId?: string;
        };
        ClickResult: {
            recorded: boolean;
            duplicate: boolean;
            sponsored: boolean;
            /** @enum {string} */
            adStatus?: "BILLABLE" | "DUPLICATE" | "EXPIRED";
        };
        QueryStat: {
            /** @description Normalised query */
            query: string;
            /** Format: int64 */
            searches: number;
            /** Format: int64 */
            zeroResults: number;
            /** Format: int64 */
            clicks: number;
            ctr: number;
        };
        QueryReport: {
            market: string;
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            items: components["schemas"]["QueryStat"][];
        };
        DailyCTR: {
            /** @description YYYY-MM-DD, or "total" */
            day: string;
            /** Format: int64 */
            searches: number;
            /** Format: int64 */
            clicks: number;
            /** Format: int64 */
            zeroResults: number;
            ctr: number;
            zeroResultRate: number;
        };
        CTRReport: {
            market: string;
            /** Format: date */
            from: string;
            /** Format: date */
            to: string;
            days: components["schemas"]["DailyCTR"][];
            total: components["schemas"]["DailyCTR"];
        };
        SynonymRule: {
            id: string;
            /** @example gari, garri, cassava flakes */
            synonyms: string;
        };
        FacetBucket: {
            value?: string;
            /** Format: int64 */
            count?: number;
        };
        SearchPage: {
            hits: components["schemas"]["Hit"][];
            /** Format: int64 */
            total: number;
            page: number;
            size: number;
            facets: {
                [key: string]: components["schemas"]["FacetBucket"][];
            };
            /** @description True when semantic (kNN) retrieval was fused into the ranking */
            semantic?: boolean;
            /**
             * @description HYBRID = BM25 and kNN fused by reciprocal rank; LEXICAL = BM25 only.
             * @enum {string}
             */
            retrieval?: "HYBRID" | "LEXICAL";
            /**
             * @description Why semantic retrieval was not used (LEXICAL pages only).
             * @enum {string}
             */
            semanticSkipped?: "SHORT_QUERY" | "NOT_CONFIGURED" | "SORTED" | "DEEP_PAGE" | "EMBEDDER_UNAVAILABLE" | "EMBEDDER_TIMEOUT";
            /** Format: int64 */
            tookMs?: number;
            /** @description Identifies this search for click reporting */
            searchId?: string;
        };
        Suggestion: {
            text?: string;
            department?: string;
        };
        Suggestions: {
            phrases?: components["schemas"]["Suggestion"][];
            products?: components["schemas"]["Hit"][];
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
    };
    parameters: {
        Market: string;
        /** @description First day (UTC), default 6 days before to */
        From: string;
        /** @description Last day (UTC), default today */
        To: string;
        Limit: number;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    searchProducts: {
        parameters: {
            query?: {
                /** @description Free text */
                q?: string;
                market?: string;
                department?: string;
                /** @description Comma-separated communities (Nigerian, Ghanaian…) */
                community?: string;
                fulfilment?: "AFROTRANSACT" | "SELLER";
                sellerKind?: "PLATFORM" | "SELLER";
                minRating?: number;
                /** @description Minor units */
                minPrice?: number;
                /** @description Minor units */
                maxPrice?: number;
                /** @description Delivery within N days */
                maxDays?: number;
                inStock?: boolean;
                lat?: number;
                lon?: number;
                withinMiles?: number;
                sort?: "relevance" | "price_asc" | "price_desc" | "rating" | "newest";
                page?: number;
                size?: number;
                /** @description Blend sponsored products into the page at the configured slots (positions 1 and 5). Results pages only; the response is then private (Cache-Control no-store) because each ad carries a click token. */
                ads?: boolean;
                /** @description Count the search in analytics and publish search.query.performed. Send false for home rails, Lola and other machine-made queries. */
                track?: boolean;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description A page of results */
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
                "application/json": components["schemas"]["ClickRequest"];
            };
        };
        responses: {
            /** @description Recorded, or recognised as a duplicate */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["ClickResult"];
                };
            };
            400: components["responses"]["Problem"];
        };
    };
    topSearchQueries: {
        parameters: {
            query?: {
                market?: components["parameters"]["Market"];
                /** @description First day (UTC), default 6 days before to */
                from?: components["parameters"]["From"];
                /** @description Last day (UTC), default today */
                to?: components["parameters"]["To"];
                limit?: components["parameters"]["Limit"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Queries by searches (descending) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["QueryReport"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
        };
    };
    zeroResultSearchQueries: {
        parameters: {
            query?: {
                market?: components["parameters"]["Market"];
                /** @description First day (UTC), default 6 days before to */
                from?: components["parameters"]["From"];
                /** @description Last day (UTC), default today */
                to?: components["parameters"]["To"];
                limit?: components["parameters"]["Limit"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Queries by zero-result searches (descending) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["QueryReport"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
        };
    };
    searchClickThroughByDay: {
        parameters: {
            query?: {
                market?: components["parameters"]["Market"];
                /** @description First day (UTC), default 6 days before to */
                from?: components["parameters"]["From"];
                /** @description Last day (UTC), default today */
                to?: components["parameters"]["To"];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One row per day of the window (days without searches included) */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["CTRReport"];
                };
            };
            400: components["responses"]["Problem"];
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
        };
    };
    listSearchSynonyms: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Synonym rules */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        rules: components["schemas"]["SynonymRule"][];
                    };
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
        };
    };
    putSearchSynonym: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ruleId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** @example plantain chips, kpekere, ipekere */
                    synonyms: string;
                };
            };
        };
        responses: {
            /** @description The normalised rule */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["SynonymRule"];
                };
            };
            401: components["responses"]["Problem"];
            403: components["responses"]["Problem"];
            422: components["responses"]["Problem"];
        };
    };
    deleteSearchSynonym: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                ruleId: string;
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
            403: components["responses"]["Problem"];
            404: components["responses"]["Problem"];
        };
    };
    suggest: {
        parameters: {
            query: {
                q: string;
                market?: string;
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
}
