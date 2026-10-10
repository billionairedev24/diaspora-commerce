// GENERATED from contracts/openapi/catalog.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/api-client generate
/* eslint-disable */

export interface paths {
    "/v1/categories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Full active category tree (department › category › product type) */
        get: operations["tree"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/category-requests": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Review queue of category requests (catalog:taxonomy) */
        get: operations["queue"];
        put?: never;
        /** Request a new category (products can be built against it right away) */
        post: operations["request"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/category-requests/mine": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** My organisation's category requests */
        get: operations["mine"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/category-requests/{id}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Approve a category request; waiting products move into listing review */
        post: operations["approve_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/category-requests/{id}/reject": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Reject a category request; waiting products are rejected with the note */
        post: operations["reject_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/identifiers/gtin/{code}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Validate a GTIN and find an existing product page that already uses it */
        get: operations["check"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-types": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** All product-type schemas */
        get: operations["productTypes"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-types/{code}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Product-type schema: required details, variation themes, identifier rule */
        get: operations["productType"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/products": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List products (own organisation; staff may filter by org) */
        get: operations["list"];
        put?: never;
        /**
         * Create a product and submit it for review
         * @description The listing owner (seller org or AfroTransact Retail) is derived from the token.
         */
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/products/atin/{atin}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get a product by ATIN */
        get: operations["byAtin"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/products/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get a product by id */
        get: operations["get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/products/{id}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Approve a product in review (catalog:moderate) */
        post: operations["approve"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/products/{id}/pause": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Pause a live product */
        post: operations["pause"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/products/{id}/reject": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Reject a product with a note (catalog:moderate) */
        post: operations["reject"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/products/{id}/resume": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Resume a paused product */
        post: operations["resume"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/public/products/{atin}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A live product page by product ATIN */
        get: operations["product"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/public/variants/{atin}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A live variant (with its product) by variant ATIN — used by cart and checkout */
        get: operations["variant"];
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
        /** @description Exactly one of categoryId or categoryRequestId */
        CreateProductRequest: {
            /** Format: uuid */
            categoryId?: string;
            /** Format: uuid */
            categoryRequestId?: string;
            title: string;
            attributes?: {
                [key: string]: string;
            };
            images: components["schemas"]["ProductImage"][];
            variationTheme: string;
            variants: components["schemas"]["VariantRequest"][];
            currency?: string;
        };
        ProductImage: {
            url: string;
            /** Format: int32 */
            width?: number;
            /** Format: int32 */
            height?: number;
            /** Format: int64 */
            bytes?: number;
            contentType: string;
        };
        VariantRequest: {
            options: {
                [key: string]: string;
            };
            sellerSku: string;
            gtin?: string;
            gtinExempt?: boolean;
            /** Format: int64 */
            priceMinor?: number;
            /** Format: int32 */
            quantity?: number;
        };
        Product: {
            /** Format: uuid */
            id?: string;
            atin?: string;
            ownerKind?: string;
            ownerOrgId?: string;
            /** Format: uuid */
            categoryId?: string;
            /** Format: uuid */
            categoryRequestId?: string;
            productType?: string;
            title?: string;
            attributes?: {
                [key: string]: string;
            };
            images?: components["schemas"]["ProductImage"][];
            variationTheme?: string;
            variants?: components["schemas"]["Variant"][];
            status?: string;
            moderationNote?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            updatedAt?: string;
            /** Format: int64 */
            version?: number;
        };
        Variant: {
            /** Format: uuid */
            id?: string;
            atin?: string;
            sellerSku?: string;
            gtin?: string;
            gtinExempt?: boolean;
            options?: {
                [key: string]: string;
            };
            /** Format: int64 */
            priceMinor?: number;
            currency?: string;
            /** Format: int32 */
            quantity?: number;
        };
        ModerationNote: {
            note: string;
        };
        CreateCategoryRequest: {
            parentPath: string;
            name: string;
            exampleProduct: string;
            detailsNeeded: string;
        };
        CategoryRequest: {
            /** Format: uuid */
            id?: string;
            reference?: string;
            orgId?: string;
            path?: string;
            exampleProduct?: string;
            detailsNeeded?: string;
            status?: string;
            /** Format: uuid */
            resolvedCategoryId?: string;
            decisionNote?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            decidedAt?: string;
        };
        RejectCategoryRequest: {
            note: string;
        };
        ApproveCategoryRequest: {
            /** Format: uuid */
            parentCategoryId: string;
            productType: string;
            note?: string;
        };
        PublicProduct: {
            /** Format: uuid */
            id?: string;
            atin?: string;
            ownerKind?: string;
            sellerOrgId?: string;
            /** Format: uuid */
            categoryId?: string;
            productType?: string;
            title?: string;
            attributes?: {
                [key: string]: string;
            };
            images?: components["schemas"]["ProductImage"][];
            variationTheme?: string;
            variants?: components["schemas"]["Variant"][];
        };
        PublicVariant: {
            product?: components["schemas"]["PublicProduct"];
            variant?: components["schemas"]["Variant"];
        };
        ProductPage: {
            items?: components["schemas"]["Product"][];
            /** Format: int64 */
            total?: number;
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
        };
        AttributeDefinition: {
            key?: string;
            label?: string;
            options?: string[];
            required?: boolean;
            multiline?: boolean;
        };
        ProductType: {
            code?: string;
            label?: string;
            identifierRule?: string;
            attributes?: components["schemas"]["AttributeDefinition"][];
            themes?: components["schemas"]["VariationTheme"][];
        };
        VariationDimension: {
            name?: string;
            options?: string[];
        };
        VariationTheme: {
            name?: string;
            dimensions?: components["schemas"]["VariationDimension"][];
        };
        GtinCheck: {
            digits?: string;
            valid?: boolean;
            suggestion?: string;
            existingAtin?: string;
            message?: string;
        };
        CategoryNode: {
            /** Format: uuid */
            id?: string;
            /** Format: uuid */
            parentId?: string;
            name?: string;
            level?: string;
            productType?: string;
            path?: string;
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
    tree: {
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
                    "*/*": components["schemas"]["CategoryNode"][];
                };
            };
        };
    };
    queue: {
        parameters: {
            query?: {
                status?: "PENDING_REVIEW" | "APPROVED" | "REJECTED";
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
                    "*/*": components["schemas"]["CategoryRequest"][];
                };
            };
        };
    };
    request: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateCategoryRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CategoryRequest"];
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
                    "*/*": components["schemas"]["CategoryRequest"][];
                };
            };
        };
    };
    approve_1: {
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
                "application/json": components["schemas"]["ApproveCategoryRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CategoryRequest"];
                };
            };
        };
    };
    reject_1: {
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
                "application/json": components["schemas"]["RejectCategoryRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CategoryRequest"];
                };
            };
        };
    };
    check: {
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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["GtinCheck"];
                };
            };
        };
    };
    productTypes: {
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
                    "*/*": components["schemas"]["ProductType"][];
                };
            };
        };
    };
    productType: {
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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProductType"];
                };
            };
        };
    };
    list: {
        parameters: {
            query?: {
                orgId?: string;
                status?: "IN_REVIEW" | "PENDING_CATEGORY_APPROVAL" | "LIVE" | "REJECTED" | "PAUSED";
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
                    "*/*": components["schemas"]["ProductPage"];
                };
            };
        };
    };
    create: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateProductRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Product"];
                };
            };
        };
    };
    byAtin: {
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
                    "*/*": components["schemas"]["Product"];
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
                    "*/*": components["schemas"]["Product"];
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
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Product"];
                };
            };
        };
    };
    pause: {
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
                    "*/*": components["schemas"]["Product"];
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
                "application/json": components["schemas"]["ModerationNote"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Product"];
                };
            };
        };
    };
    resume: {
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
                    "*/*": components["schemas"]["Product"];
                };
            };
        };
    };
    product: {
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
                    "*/*": components["schemas"]["PublicProduct"];
                };
            };
        };
    };
    variant: {
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
                    "*/*": components["schemas"]["PublicVariant"];
                };
            };
        };
    };
}
