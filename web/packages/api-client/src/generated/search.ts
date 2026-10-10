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
            availableQty?: number;
            deliveryDays?: number;
            imageUrl?: string;
            offerCount?: number;
            score?: number;
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
            semantic?: boolean;
            /** Format: int64 */
            tookMs?: number;
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
    parameters: never;
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
