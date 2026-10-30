// GENERATED from contracts/openapi/catalog.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/api-client generate
/* eslint-disable */

export interface paths {
    "/v1/brands": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Brands for the product form's brand combobox
         * @description Approved brands whose scope covers the category or product type (or an ancestor of it), plus the organisation's own pending brands there; the organisation's most used first, then the most popular. Never brands of unrelated departments. 422 when neither category nor productType is sent. q ignores case, accents and punctuation.
         */
        get: operations["listBrands"];
        put?: never;
        /**
         * Add a brand the seller typed (pending staff review)
         * @description Returns the existing brand (created=false) when the registry already has this name in the category, ignoring case, accents and punctuation. Otherwise creates a PENDING brand scoped to the category's department, usable at once by this organisation. 201 when created, 200 for a match.
         */
        post: operations["createBrand"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/categories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Full active category tree (department › category › product type)
         * @description includeRetired=true also lists retired nodes, for consumers that resolve the category of an existing product.
         */
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
    "/v1/category-requests/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One category request with the products waiting on it (catalog:taxonomy) */
        get: operations["requestDetail"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/category-requests/{id}/approval-preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** What approving would create, without creating it (catalog:taxonomy) */
        post: operations["previewApproval"];
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
        /** Approve a category request, creating its category and product type in one step; waiting products move into listing review */
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
        /**
         * Validate a GTIN and find an existing product page that already uses it
         * @description Needs catalog:read (service tokens such as offers-service, any principal type) or catalog:write (sellers in the product builder).
         */
        get: operations["check"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/identifiers/gtin/{code}/prefill": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Prefill the product builder from a GTIN (GS1, Open Food Facts)
         * @description Validates the GTIN-8/12/13/14 check digit (422 with a suggested fix), then asks the product-data providers in order (GS1 Verified when configured, then Open Food Facts for groceries) and returns title, brand, net content, images and category hints in our taxonomy. `importedFrom` marks imported fields (they stay editable); `found=false` means manual entry. When a product page already uses the GTIN, `existingAtin` is set and no provider is called. Answers are cached per GTIN. Needs catalog:write or catalog:read.
         */
        get: operations["prefillGtin"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/image-library/attach": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Attach matching library images to the organisation's listings by SKU
         * @description Looks up the organisation's library images (media) for the SKUs of its listings (all, or those in skus) and applies them: SKU.MAIN and SKU.PT01-PT08 of the first of a product's SKUs that has a MAIN image replace the product images in that order; on products with several variants a variant SKU's MAIN sets that variant's image. Images held by moderation are skipped. Each changed product is validated again like an edit (catalog.product.updated); taken-down listings and products whose new main image is under 1000 px are skipped with a reason. Products without matches are left unchanged. 503 catalog.media-unavailable when media cannot be reached.
         */
        post: operations["attachLibraryImages"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/image-library/usage": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Which of the organisation's SKUs use these image URLs, and which of these SKUs exist
         * @description For the image library page (used by). An image counts for every SKU of a product whose images include it and for the variant whose image or swatch it is.
         */
        post: operations["imageUsage"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/imports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The organisation's uploads, newest first (upload history) */
        get: operations["listImports"];
        put?: never;
        /**
         * Upload and validate a .xlsx or .csv file
         * @description Every row is validated (required fields, allowed values, numbers and units, GTIN check digit, duplicate SKUs in the file and against the organisation's listings, image URL format). 422 catalog.upload-rejected when the file cannot be read (format, 10 MB, 5,000 rows, missing required columns); nothing is stored then. Starts nothing: see POST /v1/imports/{id}/start.
         */
        post: operations["uploadImport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/imports/template": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Download an upload template
         * @description Generated from the product type definitions: a product type's template (productType, or the product type of categoryId) or the generic one. xlsx: Products sheet with required columns marked *, a note per column and dropdowns; Instructions, Example and hidden Lists sheets. csv: "#" comment lines and the header row.
         */
        get: operations["importTemplate"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/imports/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** An upload with its validation counts and import progress */
        get: operations["getImport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/imports/{id}/report": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Download the error report
         * @description The uploaded rows with Row, Row status, Result and Errors columns; cells starting with = + - @ are prefixed with ' (formula injection)
         */
        get: operations["importReport"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/imports/{id}/rows": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Rows of an upload (preview)
         * @description In file order; status filters the preview (ERROR = errors only), outcome the import result
         */
        get: operations["importRows"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/imports/{id}/start": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Start the import
         * @description Runs in the background (poll GET /v1/imports/{id}); resumable and idempotent per row. 409 catalog.import-has-errors when rows have errors and validOnly is false; starting again returns the upload as it is.
         */
        post: operations["startImport"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/labels": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Render one label
         * @description Needs catalog:write or catalog:read. Invalid data (GTIN / SSCC check digits, GS1 AI rules, too long for the label) is a 422 with fieldErrors.
         */
        post: operations["renderLabel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/labels/batch": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Render a batch of labels as one PDF or ZPL
         * @description Up to 500 labels and 2000 printed labels (copies included). Field errors are keyed labels[i].field. Needs catalog:write or catalog:read.
         */
        post: operations["renderLabelBatch"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/labels/units/{atin}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Unit label of a product or variant ATIN
         * @description Platform (AfroTransact Retail) stock with a vendor GTIN keeps the manufacturer barcode; every other unit gets the stock owner's AT-FN code (ATF + 7, Code 128), issued once per owner and variant. Sellers print for their own organisation; staff and service clients may pass orgId (default: the product's owner). Needs catalog:write; service clients (inventory-service) and staff may use catalog:read. The AT-FN code is returned as data in the X-Unit-Label-Code header (absent for manufacturer barcodes).
         */
        get: operations["unitLabel"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-drafts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The organisation's drafts, most recently saved first */
        get: operations["listDrafts"];
        put?: never;
        /** Save a new draft (Save draft, or the first autosave) */
        post: operations["createDraft"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-drafts/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Get a draft with its payload */
        get: operations["getDraft"];
        /**
         * Autosave a draft
         * @description 409 catalog.draft-changed when the draft was saved elsewhere since version
         */
        put: operations["saveDraft"];
        post?: never;
        /** Discard a draft */
        delete: operations["deleteDraft"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-reviews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Product reviews by state, newest first (catalog:moderate) */
        get: operations["listProductReviews"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-reviews/{reviewId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One product review (catalog:moderate) */
        get: operations["getProductReview"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-reviews/{reviewId}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Publish a review
         * @description A pending review becomes public and counts in the rating; also reinstates a rejected or removed one. Approving a published review changes nothing. catalog.review.published.
         */
        post: operations["approveProductReview"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-reviews/{reviewId}/reject": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Reject a review that is waiting for moderation
         * @description The customer sees the reason and may edit the review (which goes back to moderation). 409 catalog.review-not-pending for a published review - remove it instead. 422 without a note for reasonCode OTHER. catalog.review.rejected.
         */
        post: operations["rejectProductReview"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-reviews/{reviewId}/remove": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Take a review down, published or not
         * @description It stops being public and counting in the rating; the customer sees the reason. catalog.review.removed.
         */
        post: operations["removeProductReview"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-reviews/{reviewId}/reply": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Reply to a published review, once (catalog:write, members of the owning organisation)
         * @description 409 catalog.review-not-published before approval, 409 catalog.review-already-replied the second time; catalog.review.replied. The reply is public and cannot be edited.
         */
        post: operations["replyToProductReview"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/product-reviews/{reviewId}/reports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Report a published review (signed-in customer, or a member of the product's seller)
         * @description One report per reporter and review: a repeated report returns 200 with created=false. The first report of a text queues the review in trust-ops (catalog.review.reported); sellers can report but never edit or hide a review.
         */
        post: operations["reportProductReview"];
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
    "/v1/products/{atin}/reviews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Review a product I received (signed-in customer)
         * @description 403 catalog.review-not-verified-purchase without a delivered order line for the product; 409 catalog.review-already-exists the second time. The review is published at once or, when the automatic checks (profanity, links, personal data) flag it, waits for moderation (status PENDING). Publishes catalog.review.published or catalog.review.submitted.
         */
        post: operations["writeProductReview"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/products/{atin}/reviews/mine": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * My standing with a product: may I review it, and my review (signed-in customer)
         * @description eligible is true when orders-service says the customer received the product and they have no live review yet. A review in any state is returned, with the moderation reason when it was refused or removed. 503 catalog.purchase-check-unavailable when orders cannot answer.
         */
        get: operations["myProductReview"];
        /**
         * Edit my review within 30 days (signed-in customer)
         * @description The checks run again. 409 catalog.review-edit-window-closed after the window. An edit of a rejected or removed review always goes back to moderation.
         */
        put: operations["editProductReview"];
        post?: never;
        /**
         * Delete my review (signed-in customer)
         * @description Any time. The rating is recomputed; the customer can review the product again.
         */
        delete: operations["deleteProductReview"];
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
        /**
         * Edit a product (one-step form in edit mode)
         * @description Owner or staff. The whole content is validated again; variants are matched by sellerSku (new SKUs get ATINs; a published listing keeps its category and variants). A rejected listing goes back to review. Publishes catalog.product.updated, plus catalog.product.terms_changed when prices, quantities or listing terms changed and catalog.product.resubmitted when a rejected listing is resubmitted. 409 when version is stale.
         */
        put: operations["updateProduct"];
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
    "/v1/products/{id}/flags": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Flag a live listing for review (catalog:moderate)
         * @description Staff flags are source STAFF; service clients (principal type SERVICE) are AUTOMATED. Publishes catalog.product.flagged; trust-ops queues it as a live-listing moderation item.
         */
        post: operations["flag"];
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
    "/v1/products/{id}/preorder-flags": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Set the pre-order flags of a product (S272)
         * @description preorderEligible: may be sold in a community pre-order window (default true for food). shipsNow: always ships now by carrier, never as a pre-order. Owner or staff; publishes catalog.product.updated.
         */
        put: operations["preorderFlags"];
        post?: never;
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
    "/v1/products/{id}/restore": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Reverse a takedown (catalog:moderate)
         * @description Returns the listing to its status before the takedown (LIVE or PAUSED) and publishes catalog.product.reinstated. Idempotent.
         */
        post: operations["restore"];
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
    "/v1/products/{id}/takedown": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Take a live or paused listing down with a reason (catalog:moderate)
         * @description Status becomes TAKEN_DOWN: hidden from public reads and the owner cannot resume it. Publishes catalog.product.taken_down. Idempotent.
         */
        post: operations["takeDown"];
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
    "/v1/public/products/{atin}/rating": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A live product's rating: average, count and star breakdown */
        get: operations["productRating"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/public/products/{atin}/reports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Report a live listing by product ATIN (any signed-in user)
         * @description One report per user and listing: a repeated report returns 200 with the earlier report (created=false) and publishes nothing. Listings that are not publicly visible return 404.
         */
        post: operations["reportListing"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/public/products/{atin}/reviews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Published reviews of a live product, with its rating
         * @description Sorted NEWEST (default), HIGHEST or LOWEST, optionally only reviews with the given stars. Reviewers show as first name and last initial, always Verified purchase.
         */
        get: operations["publicProductReviews"];
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
    "/v1/seller/reviews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Published reviews of my products, newest first (catalog:write)
         * @description Sellers see their own organisation; staff name it with orgId. The customer's identity is left out. unanswered=true lists reviews without a reply.
         */
        get: operations["listSellerReviews"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/audit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Audit trail of taxonomy changes, newest first */
        get: operations["taxonomyAudit"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/brands": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Brands by status, name and scope with their usage
         * @description status is a comma-separated list (PENDING is the review queue); q ignores case, accents and punctuation; scope narrows to brands applying to a taxonomy node. counts holds brands per status.
         */
        get: operations["listBrandRegistry"];
        put?: never;
        /**
         * Add an approved brand
         * @description 409 catalog.brand-exists when the name exists in an overlapping scope.
         */
        post: operations["createRegistryBrand"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/brands/bulk": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Add many approved brands at once
         * @description Entries that exist or are invalid are reported per entry; the rest are created.
         */
        post: operations["createRegistryBrands"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/brands/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One brand with usage and scope */
        get: operations["getBrandRegistryEntry"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Edit a brand's name and scope
         * @description A rename follows to the listings. 422 Nothing to change when nothing differs. If-Match: the version read with the brand (412 catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason and is audited with the taxonomy changes.
         */
        patch: operations["updateRegistryBrand"];
        trace?: never;
    };
    "/v1/taxonomy/brands/{id}/approve": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Approve a pending brand
         * @description If-Match: the version read with the brand (412 catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason and is audited with the taxonomy changes.
         */
        post: operations["approveRegistryBrand"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/brands/{id}/merge": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Merge a brand into an existing approved brand
         * @description Listings that hold the brand are re-pointed and show the target's name; the target's scope grows by the scope of the merged brand. If-Match: the version read with the brand (412 catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason and is audited with the taxonomy changes.
         */
        post: operations["mergeRegistryBrand"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/brands/{id}/reject": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Reject a pending brand
         * @description If-Match: the version read with the brand (412 catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason and is audited with the taxonomy changes.
         */
        post: operations["rejectRegistryBrand"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/brands/{id}/retire": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Retire an approved brand
         * @description If-Match: the version read with the brand (412 catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason and is audited with the taxonomy changes.
         */
        post: operations["retireRegistryBrand"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/nodes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Create a department, category or product type
         * @description A product type uses an existing schema (productType) or defines a new one (newProductType). 422 for duplicate names or codes, a wrong parent level or a retired parent.
         */
        post: operations["createNode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/nodes/{id}": {
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
         * Rename a node; the paths below it follow
         * @description If-Match: the version read with the record (412 catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason.
         */
        patch: operations["renameNode"];
        trace?: never;
    };
    "/v1/taxonomy/nodes/{id}/move": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Move a category to another department or a product type to another category
         * @description If-Match: the version read with the record (412 catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason.
         */
        post: operations["moveNode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/nodes/{id}/restore": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Restore a retired node
         * @description If-Match: the version read with the record (412 catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason.
         */
        post: operations["restoreNode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/nodes/{id}/retire": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Retire a node (soft)
         * @description 409 catalog.taxonomy-in-use while the node or anything under it has products. A retired node stays on existing products but cannot be chosen for new ones. If-Match: the version read with the record (412 catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason.
         */
        post: operations["retireNode"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/product-types": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Product-type schemas with where they are used */
        get: operations["listTaxonomyProductTypes"];
        put?: never;
        /** Create a product-type schema */
        post: operations["createProductType"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/product-types/{code}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One product-type schema with every attribute, including those that do not apply */
        get: operations["getTaxonomyProductType"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /**
         * Edit a product type: label, identifier rule, traits and attributes
         * @description Only the fields sent change. Sales-tax question (tax_class) wording and choices are owned by tax-returns and cannot be edited. If-Match: the version read with the record (412 catalog.version-mismatch when it changed since, 428 when missing). Every change carries a reason.
         */
        patch: operations["updateProductType"];
        trace?: never;
    };
    "/v1/taxonomy/reorder": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Set the display order of the children of a node (or of the departments)
         * @description ids is every child of the parent, retired ones included; 412 when the list changed.
         */
        post: operations["reorderNodes"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/taxonomy/tree": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The whole tree with retired nodes, product counts and the pending-request count */
        get: operations["taxonomyTree"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/variants/{atin}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * A variant in any status with its product, by variant ATIN
         * @description Service clients and staff with catalog:read (retail-service links draft AfroTransact Retail products before they go live); members of the owning organisation with catalog:write. Unlike GET /v1/public/variants/{atin}, drafts, products in review, paused, rejected, taken-down and hidden products are returned with their status. 404 catalog.variant-not-found.
         */
        get: operations["variantAnyStatus"];
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
        /** @description Description, condition, search keywords, fulfilment, package, handling time and ship-from */
        ListingDetails: {
            description?: string;
            /** @enum {string} */
            condition?: "NEW" | "RENEWED" | "USED_LIKE_NEW" | "USED_VERY_GOOD" | "USED_GOOD" | "USED_ACCEPTABLE";
            keywords?: string[];
            /** @enum {string} */
            fulfilment?: "SELLER" | "AFROTRANSACT";
            packaging?: components["schemas"]["PackageDimensions"];
            /**
             * Format: int32
             * @description Business days from order to dispatch, 1–30 (default 2)
             */
            handlingDays?: number;
            shipFrom?: components["schemas"]["ShipFrom"];
        };
        /** @description Packed size (inches) and weight (pounds) */
        PackageDimensions: {
            /** Format: double */
            lengthIn?: number;
            /** Format: double */
            widthIn?: number;
            /** Format: double */
            heightIn?: number;
            /** Format: double */
            weightLb?: number;
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
            /** @description Optional alternative text (what the picture shows), stored with the image */
            alt?: string;
        };
        /** @description The seller's ship-from location (snapshot) */
        ShipFrom: {
            locationId?: string;
            label?: string;
            city?: string;
            region?: string;
            country?: string;
            postalCode?: string;
        };
        /** @description The whole editable content of a product (one-step form in edit mode). Variants are matched by sellerSku; a published listing keeps its category and variants */
        UpdateProductRequest: {
            /** Format: uuid */
            categoryId?: string;
            title: string;
            attributes?: {
                [key: string]: string;
            };
            images: components["schemas"]["ProductImage"][];
            variationTheme?: string;
            variants: components["schemas"]["VariantRequest"][];
            preorderEligible?: boolean;
            shipsNow?: boolean;
            listing?: components["schemas"]["ListingDetails"];
            /**
             * Format: int64
             * @description The version the edit is based on (409 when the product changed since)
             */
            version?: number;
            /** Format: uuid */
            draftId?: string;
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
            /**
             * Format: int64
             * @description "Was" price shown struck through; above priceMinor
             */
            compareAtMinor?: number;
            /** @description Images of this variant only (e.g. its colour) */
            images?: components["schemas"]["ProductImage"][];
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
            preorderEligible?: boolean;
            shipsNow?: boolean;
            listing?: components["schemas"]["ListingDetails"];
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
            /** Format: int64 */
            compareAtMinor?: number;
            images?: components["schemas"]["ProductImage"][];
        };
        /** @description Pre-order flags of a product (S272) */
        PreorderFlagsRequest: {
            preorderEligible: boolean;
            shipsNow: boolean;
        };
        JsonNode: {
            empty?: boolean;
            array?: boolean;
            null?: boolean;
            object?: boolean;
            float?: boolean;
            valueNode?: boolean;
            container?: boolean;
            missingNode?: boolean;
            /** @enum {string} */
            nodeType?: "ARRAY" | "BINARY" | "BOOLEAN" | "MISSING" | "NULL" | "NUMBER" | "OBJECT" | "POJO" | "STRING";
            string?: boolean;
            integralNumber?: boolean;
            pojo?: boolean;
            floatingPointNumber?: boolean;
            short?: boolean;
            int?: boolean;
            long?: boolean;
            double?: boolean;
            bigDecimal?: boolean;
            bigInteger?: boolean;
            /** @deprecated */
            textual?: boolean;
            boolean?: boolean;
            binary?: boolean;
            number?: boolean;
            embeddedValue?: boolean;
        };
        /** @description Autosave: replaces the payload when version still matches */
        SaveDraftRequest: {
            /** Format: int64 */
            version?: number;
            title?: string;
            categoryPath?: string;
            payload: components["schemas"]["JsonNode"];
        };
        ProductDraft: {
            /** Format: uuid */
            id?: string;
            /** Format: uuid */
            productId?: string;
            title?: string;
            categoryPath?: string;
            payload?: components["schemas"]["JsonNode"];
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            updatedAt?: string;
            /** Format: int64 */
            version?: number;
        };
        /** @description Reason code required; details required for OTHER */
        ReportListingRequest: {
            /** @enum {string} */
            reasonCode: "PROHIBITED_ITEM" | "UNAPPROVED_HEALTH_CLAIM" | "RESTRICTED_INGREDIENT" | "COUNTERFEIT" | "IMAGE_POLICY" | "MISLEADING_CONTENT" | "LABELLING" | "SHIPPING_METHOD" | "DUPLICATE_LISTING" | "WRONG_CATEGORY" | "OTHER";
            details?: string;
        };
        /** @description A report or flag on a live listing (catalog.product.flagged) */
        ListingFlag: {
            /** Format: uuid */
            id?: string;
            /** Format: uuid */
            productId?: string;
            /** @enum {string} */
            source?: "CUSTOMER_REPORT" | "STAFF" | "AUTOMATED";
            /** @enum {string} */
            reasonCode?: "PROHIBITED_ITEM" | "UNAPPROVED_HEALTH_CLAIM" | "RESTRICTED_INGREDIENT" | "COUNTERFEIT" | "IMAGE_POLICY" | "MISLEADING_CONTENT" | "LABELLING" | "SHIPPING_METHOD" | "DUPLICATE_LISTING" | "WRONG_CATEGORY" | "OTHER";
            details?: string;
            riskLevel?: string;
            /** Format: date-time */
            createdAt?: string;
            created?: boolean;
        };
        /** @description Exactly one of categoryId or categoryRequestId. variationTheme empty (or absent) = a product without variations: exactly one variant without options */
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
            variationTheme?: string;
            variants: components["schemas"]["VariantRequest"][];
            currency?: string;
            /** @description May be sold in a pre-order window; default true for food product types */
            preorderEligible?: boolean;
            /** @description Always ships now by carrier (never a pre-order); default false */
            shipsNow?: boolean;
            listing?: components["schemas"]["ListingDetails"];
            /**
             * Format: uuid
             * @description The saved draft this submission comes from; deleted with the submission
             */
            draftId?: string;
        };
        /** @description Reason code required; note required for OTHER */
        TakedownRequest: {
            /** @enum {string} */
            reasonCode: "PROHIBITED_ITEM" | "UNAPPROVED_HEALTH_CLAIM" | "RESTRICTED_INGREDIENT" | "COUNTERFEIT" | "IMAGE_POLICY" | "MISLEADING_CONTENT" | "LABELLING" | "SHIPPING_METHOD" | "DUPLICATE_LISTING" | "WRONG_CATEGORY" | "OTHER";
            note?: string;
        };
        RestoreListingRequest: {
            note?: string;
        };
        ModerationNote: {
            note: string;
        };
        /** @description Reason code required; details required for OTHER */
        FlagListingRequest: {
            /** @enum {string} */
            reasonCode: "PROHIBITED_ITEM" | "UNAPPROVED_HEALTH_CLAIM" | "RESTRICTED_INGREDIENT" | "COUNTERFEIT" | "IMAGE_POLICY" | "MISLEADING_CONTENT" | "LABELLING" | "SHIPPING_METHOD" | "DUPLICATE_LISTING" | "WRONG_CATEGORY" | "OTHER";
            details?: string;
            riskLevel?: string;
        };
        /** @description A new draft; with productId it is the unsaved edit of that product (one per product: saving another replaces it) */
        CreateDraftRequest: {
            /** Format: uuid */
            productId?: string;
            title?: string;
            categoryPath?: string;
            /** @description The form's product input as entered (need not be valid) */
            payload: components["schemas"]["JsonNode"];
        };
        /** @description One label. UNIT/SHELF/CUSTOM: Code 128 of `data` (or EAN-13 of `gtin`); RETAIL: EAN-13 / UPC-A of `gtin` (GS1-128 (01) for GTIN-8/14); CASE: GS1-128 (01) `gtin` + (10) lot, (11) production, (15) best before, (17) expiry, (21) serial; PALLET: GS1-128 (00) `sscc` + optional (02) `gtin` with (37) `count`. `symbology` overrides the default. */
        LabelRequest: {
            /** @enum {string} */
            kind: "UNIT" | "RETAIL" | "SHELF" | "CASE" | "PALLET" | "CUSTOM";
            /** @enum {string} */
            symbology?: "CODE_128" | "GS1_128" | "EAN_13";
            /** @example ATF3XK8P2QL */
            data?: string;
            /** @example 9501101530003 */
            gtin?: string;
            /** @example 106141411234567897 */
            sscc?: string;
            lot?: string;
            /** Format: date */
            productionDate?: string;
            /** Format: date */
            bestBefore?: string;
            /** Format: date */
            expiry?: string;
            serial?: string;
            /** Format: int32 */
            count?: number;
            caption?: string;
            title?: string;
            lines?: string[];
            /**
             * Format: int32
             * @description 1–1000, default 1
             */
            copies?: number;
        };
        /** @description Up to 500 labels rendered as one PDF (pages or sheets) or one ZPL stream. */
        LabelBatchRequest: {
            labels: components["schemas"]["LabelRequest"][];
            /**
             * @description PDF: LABEL = one page per label (label printers, default), SHEET = tiled on paper
             * @enum {string}
             */
            layout?: "LABEL" | "SHEET";
            /**
             * @description PDF sheets: LETTER (default) or A4
             * @enum {string}
             */
            paper?: "LETTER" | "A4";
        };
        /** @description A bulk upload: validation counts and import progress */
        ProductImport: {
            /** Format: uuid */
            id?: string;
            reference?: string;
            fileName?: string;
            /** @enum {string} */
            format?: "XLSX" | "CSV";
            /** @enum {string} */
            mode?: "CREATE" | "UPDATE";
            productType?: string;
            /** @enum {string} */
            status?: "VALIDATED" | "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
            validOnly?: boolean;
            /** Format: int32 */
            totalRows?: number;
            /** Format: int32 */
            validRows?: number;
            /** Format: int32 */
            warningRows?: number;
            /** Format: int32 */
            errorRows?: number;
            /** Format: int32 */
            productCount?: number;
            /** Format: int32 */
            processedRows?: number;
            /** Format: int32 */
            createdProducts?: number;
            /** Format: int32 */
            updatedProducts?: number;
            /** Format: int32 */
            failedRows?: number;
            header?: string[];
            fileWarnings?: string[];
            uploadedBy?: string;
            uploadedByName?: string;
            notifySeller?: boolean;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            startedAt?: string;
            /** Format: date-time */
            finishedAt?: string;
        };
        /** @description validOnly: import the valid and warning rows and skip the rows with errors (required when the upload has errors) */
        StartImportRequest: {
            validOnly?: boolean;
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
            parentPath?: string;
            name?: string;
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
            /** Format: int64 */
            productsWaiting?: number;
        };
        RejectCategoryRequest: {
            note: string;
        };
        /** @description parentCategoryId is a department (a category named categoryName, default the requested name, is created for it) or a category. Give productType (an existing schema) or newProductType (a new one), not both. */
        ApproveCategoryRequest: {
            /** Format: uuid */
            parentCategoryId: string;
            categoryName?: string;
            productType?: string;
            newProductType?: components["schemas"]["TaxonomyNewProductType"];
            note?: string;
        };
        VariantLookup: {
            product?: components["schemas"]["Product"];
            variant?: components["schemas"]["Variant"];
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
            preorderEligible?: boolean;
            shipsNow?: boolean;
            rating?: components["schemas"]["ProductRating"];
        };
        /** @description A review photo: the public https URL media-service gave the uploaded, moderated image */
        ReviewPhoto: {
            url: string;
        };
        /** @description Stars are required; title, text and up to 5 photos are optional. reviewerName is the shopper's account name: only the first name and last initial are kept */
        WriteReviewRequest: {
            reviewerName?: string;
            /** Format: int32 */
            rating: number;
            title?: string;
            text?: string;
            photos?: components["schemas"]["ReviewPhoto"][];
        };
        /** @description The whole review again (stars, title, text, photos) */
        EditReviewRequest: {
            /** Format: int32 */
            rating: number;
            title?: string;
            text?: string;
            photos?: components["schemas"]["ReviewPhoto"][];
        };
        /** @description Report a published review */
        ReviewReportRequest: {
            reasonCode: components["schemas"]["ReviewReasonCode"];
            details?: string;
        };
        /**
         * @description Why a review is reported or removed (OTHER needs a note when removing)
         * @enum {string}
         */
        ReviewReasonCode: "ABUSIVE" | "PERSONAL_INFORMATION" | "SPAM" | "OFF_TOPIC" | "CONFLICT_OF_INTEREST" | "OTHER";
        ReviewReportResult: {
            created: boolean;
            /** Format: int32 */
            reports: number;
        };
        /** @description The product owner's single public reply */
        ReviewReplyRequest: {
            text: string;
        };
        ReviewApproveRequest: {
            note?: string;
        };
        /** @description reasonCode is required; note is required for OTHER */
        ReviewRemoveRequest: {
            reasonCode: components["schemas"]["ReviewReasonCode"];
            note?: string;
        };
        /** @description Average stars over published reviews, the count and how many reviews gave 1, 2, 3, 4 and 5 stars */
        ProductRating: {
            /** @description Two decimals; 0 without reviews */
            average: number;
            /** Format: int32 */
            count: number;
            /** @description Counts for 1, 2, 3, 4 and 5 stars, in that order */
            breakdown: number[];
        };
        ReviewReply: {
            text: string;
            /** Format: date-time */
            at: string;
        };
        /** @description The last moderation decision: why a review was refused or removed */
        ReviewModeration: {
            by?: string;
            /** Format: date-time */
            at?: string;
            reasonCode?: components["schemas"]["ReviewReasonCode"];
            note?: string;
        };
        /** @description A published review as shoppers see it - Verified purchase, the reviewer's first name and last initial only */
        PublicReview: {
            /** Format: uuid */
            id: string;
            reviewerName: string;
            /** Format: int32 */
            rating: number;
            title?: string;
            text?: string;
            photos: components["schemas"]["ReviewPhoto"][];
            verifiedPurchase: boolean;
            /** Format: date-time */
            createdAt: string;
            reply?: components["schemas"]["ReviewReply"];
        };
        /** @description One page of published reviews and the product's rating */
        PublicReviewPage: {
            rating: components["schemas"]["ProductRating"];
            items: components["schemas"]["PublicReview"][];
            /** Format: int64 */
            total: number;
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
        };
        /** @description The customer's own review in any state; moderation says why it was refused */
        OwnReview: {
            /** Format: uuid */
            id: string;
            atin: string;
            productTitle: string;
            reviewerName: string;
            /** Format: int32 */
            rating: number;
            title?: string;
            text?: string;
            photos: components["schemas"]["ReviewPhoto"][];
            /** @enum {string} */
            status: "PENDING" | "PUBLISHED" | "REJECTED" | "REMOVED" | "DELETED";
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
            /** Format: date-time */
            editableUntil: string;
            editable: boolean;
            moderation?: components["schemas"]["ReviewModeration"];
            reply?: components["schemas"]["ReviewReply"];
        };
        /** @description What the signed-in customer can do about a product: eligible means they received it and have not reviewed it yet; reason says why not (not-purchased, already-reviewed) */
        MyReview: {
            eligible: boolean;
            reason?: string;
            review?: components["schemas"]["OwnReview"];
        };
        /** @description A published review of the organisation's product, with the customer's identity left out */
        SellerReview: {
            /** Format: uuid */
            id: string;
            atin: string;
            productTitle: string;
            reviewerName: string;
            /** Format: int32 */
            rating: number;
            title?: string;
            text?: string;
            photos: components["schemas"]["ReviewPhoto"][];
            verifiedPurchase: boolean;
            /** Format: date-time */
            createdAt: string;
            /** Format: int32 */
            reportCount: number;
            reply?: components["schemas"]["ReviewReply"];
        };
        SellerReviewPage: {
            items: components["schemas"]["SellerReview"][];
            /** Format: int64 */
            total: number;
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
        };
        /** @description A review for moderation - the author's id, the delivering order, what the automatic checks found and the decision */
        ProductReview: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            productId: string;
            atin: string;
            productTitle: string;
            sellerOrgId: string;
            customerId: string;
            reviewerName: string;
            orderNumber: string;
            /** Format: int32 */
            rating: number;
            title?: string;
            text?: string;
            photos: components["schemas"]["ReviewPhoto"][];
            /** @enum {string} */
            status: "PENDING" | "PUBLISHED" | "REJECTED" | "REMOVED" | "DELETED";
            flags: ("PROFANITY" | "LINK" | "EMAIL" | "PHONE")[];
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
            /** Format: int32 */
            reportCount: number;
            moderation?: components["schemas"]["ReviewModeration"];
            reply?: components["schemas"]["ReviewReply"];
            /** Format: int64 */
            version: number;
        };
        ProductReviewPage: {
            items: components["schemas"]["ProductReview"][];
            /** Format: int64 */
            total: number;
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
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
            /** @enum {string} */
            kind?: "TEXT" | "MULTILINE" | "ENUM" | "NUMBER" | "BRAND";
            units?: string[];
            /** @enum {string} */
            section?: "DETAILS" | "FOOD" | "REGULATED";
            help?: string;
            applies?: boolean;
        };
        /** @description Attributes carry kind (TEXT, MULTILINE, ENUM, NUMBER), units, the form section (DETAILS, FOOD, REGULATED) and a help line; traits decide the conditional form sections */
        ProductType: {
            code?: string;
            label?: string;
            identifierRule?: string;
            attributes?: components["schemas"]["AttributeDefinition"][];
            themes?: components["schemas"]["VariationTheme"][];
            traits?: components["schemas"]["ProductTypeTraits"];
        };
        ProductTypeTraits: {
            food?: boolean;
            perishable?: boolean;
            regulated?: boolean;
            preorder?: boolean;
        };
        VariationDimension: {
            name?: string;
            options?: string[];
        };
        VariationTheme: {
            name?: string;
            dimensions?: components["schemas"]["VariationDimension"][];
        };
        ProductDraftSummary: {
            /** Format: uuid */
            id?: string;
            /** Format: uuid */
            productId?: string;
            title?: string;
            categoryPath?: string;
            /** Format: date-time */
            updatedAt?: string;
            /** Format: int64 */
            version?: number;
        };
        ProductImportPage: {
            items?: components["schemas"]["ProductImport"][];
            /** Format: int64 */
            total?: number;
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
        };
        /** @description column = the column key (price, attr:material); null for the row */
        ImportRowMessage: {
            column?: string;
            /** Format: int32 */
            columnIndex?: number;
            /** @enum {string} */
            severity?: "ERROR" | "WARNING";
            message?: string;
        };
        ProductImportRow: {
            /** Format: int32 */
            rowNumber?: number;
            groupKey?: string;
            sku?: string;
            /** @enum {string} */
            status?: "VALID" | "WARNING" | "ERROR";
            /** @enum {string} */
            outcome?: "PENDING" | "CREATED" | "UPDATED" | "FAILED" | "SKIPPED";
            outcomeNote?: string;
            /** Format: uuid */
            productId?: string;
            messages?: components["schemas"]["ImportRowMessage"][];
            cells?: string[];
            /** @description The row's images as the preview shows them (S310) */
            images?: components["schemas"]["ImportRowImage"][];
        };
        /** @description One image of an upload row: where it comes from (LINK = an https URL cell, FILE = a library file name in an image cell, SKU = a library file named after the SKU) and what was found (FOUND = a library image, LINK = a link copied when the import runs, HELD = a library image held by moderation, MISSING = no such library image, BAD_LINK = not a usable https link) */
        ImportRowImage: {
            /** @enum {string} */
            slot: "MAIN" | "PT01" | "PT02" | "PT03" | "PT04" | "PT05" | "PT06" | "PT07" | "PT08" | "SWCH";
            /** @enum {string} */
            source: "LINK" | "FILE" | "SKU";
            /** @description The link, the file name or the matched library file name */
            ref: string;
            /** @enum {string} */
            state: "FOUND" | "LINK" | "HELD" | "MISSING" | "BAD_LINK";
            /** @description Thumbnail of a FOUND library image */
            url?: string;
            /** Format: int32 */
            width?: number;
            /** Format: int32 */
            height?: number;
            message?: string;
        };
        ProductImportRowPage: {
            items?: components["schemas"]["ProductImportRow"][];
            /** Format: int64 */
            total?: number;
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
        };
        GtinCheck: {
            digits?: string;
            valid?: boolean;
            suggestion?: string;
            existingAtin?: string;
            message?: string;
        };
        CategoryHint: {
            /** Format: uuid */
            categoryId?: string;
            path?: string;
            level?: string;
            productTypeCode?: string;
            matchedOn?: string;
        };
        GtinPrefill: {
            gtin?: string;
            found?: boolean;
            existingAtin?: string;
            title?: string;
            brand?: string;
            netContent?: string;
            description?: string;
            imageUrls?: string[];
            importedFrom?: {
                [key: string]: string;
            };
            categoryHints?: components["schemas"]["CategoryHint"][];
            sources?: string[];
            cached?: boolean;
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
        AttachLibraryImagesRequest: {
            /** @description Only listings with one of these SKUs (any variant); empty = every listing of the organisation */
            skus?: string[];
        };
        AttachLibraryImagesResult: {
            /** Format: int32 */
            productsUpdated: number;
            /** Format: int32 */
            imagesAttached: number;
            skipped: components["schemas"]["AttachSkipped"][];
        };
        AttachSkipped: {
            sku: string;
            reason: string;
        };
        ImageUsageRequest: {
            urls?: string[];
            skus?: string[];
        };
        ImageUsage: {
            usage: components["schemas"]["ImageUse"][];
            /** @description The requested SKUs that are SKUs of the organisation's listings (as stored) */
            knownSkus: string[];
        };
        ImageUse: {
            url: string;
            skus: string[];
        };
        BrandList: {
            /** Format: uuid */
            departmentId?: string;
            departmentName?: string;
            brands?: components["schemas"]["Brand"][];
        };
        Brand: {
            /** Format: uuid */
            id?: string;
            name?: string;
            /** @enum {string} */
            source?: "OWN" | "CATALOG";
            /** @enum {string} */
            status?: "APPROVED" | "PENDING";
            /** Format: int64 */
            ownProducts?: number;
            /** Format: int64 */
            products?: number;
        };
        /** @description parentId null orders the departments; ids must be every child of the parent, retired ones included */
        ReorderTaxonomyNodes: {
            /** Format: uuid */
            parentId?: string;
            ids: string[];
            reason: string;
        };
        TaxonomyNode: {
            /** Format: uuid */
            id?: string;
            /** Format: uuid */
            parentId?: string;
            code?: string;
            name?: string;
            level?: string;
            productType?: string;
            productTypeLabel?: string;
            path?: string;
            /** Format: int32 */
            position?: number;
            active?: boolean;
            /** Format: date-time */
            retiredAt?: string;
            retiredReason?: string;
            /** Format: int64 */
            version?: number;
            /** Format: int64 */
            productCount?: number;
            /** Format: int64 */
            subtreeProductCount?: number;
            /** Format: int32 */
            childCount?: number;
        };
        CreateTaxonomyProductType: {
            code?: string;
            label: string;
            /** @enum {string} */
            identifierRule: "GTIN" | "ISBN";
            traits?: components["schemas"]["TaxonomyTraits"];
            attributes?: components["schemas"]["TaxonomyAttribute"][];
            basedOn?: string;
            reason: string;
        };
        /** @description One detail a product type asks for. applies=false keeps the definition but stops asking (brand is just an attribute; a Books type has none). */
        TaxonomyAttribute: {
            key: string;
            label: string;
            options?: string[];
            required?: boolean;
            /** @enum {string} */
            kind: "TEXT" | "MULTILINE" | "ENUM" | "NUMBER" | "BRAND";
            units?: string[];
            /** @enum {string} */
            section?: "DETAILS" | "FOOD" | "REGULATED";
            help?: string;
            applies?: boolean;
        };
        TaxonomyTraits: {
            food?: boolean;
            perishable?: boolean;
            regulated?: boolean;
            preorder?: boolean;
        };
        /** @description A product-type schema as staff edit it: every attribute including the ones that do not apply, the version to send as If-Match, and where it is used */
        TaxonomyProductType: {
            code?: string;
            label?: string;
            identifierRule?: string;
            traits?: components["schemas"]["ProductTypeTraits"];
            attributes?: components["schemas"]["AttributeDefinition"][];
            themes?: components["schemas"]["VariationTheme"][];
            /** Format: int64 */
            version?: number;
            /** Format: date-time */
            updatedAt?: string;
            /** Format: int64 */
            productCount?: number;
            usedBy?: components["schemas"]["TaxonomyTypeUse"][];
        };
        TaxonomyTypeUse: {
            /** Format: uuid */
            nodeId?: string;
            path?: string;
            active?: boolean;
        };
        CreateTaxonomyNode: {
            /** @enum {string} */
            level: "DEPARTMENT" | "CATEGORY" | "PRODUCT_TYPE";
            /** Format: uuid */
            parentId?: string;
            name: string;
            code?: string;
            productType?: string;
            newProductType?: components["schemas"]["TaxonomyNewProductType"];
            reason: string;
        };
        TaxonomyNewProductType: {
            code?: string;
            label: string;
            /** @enum {string} */
            identifierRule: "GTIN" | "ISBN";
            traits?: components["schemas"]["TaxonomyTraits"];
            attributes?: components["schemas"]["TaxonomyAttribute"][];
            basedOn?: string;
        };
        TaxonomyReason: {
            reason: string;
        };
        MoveTaxonomyNode: {
            /** Format: uuid */
            parentId: string;
            reason: string;
        };
        /** @description What approving the request would create; nothing is created by asking */
        ApprovalPlan: {
            nodes?: components["schemas"]["PlannedNode"][];
            productType?: components["schemas"]["PlannedProductType"];
            /** Format: int64 */
            productsMoving?: number;
        };
        PlannedNode: {
            level?: string;
            name?: string;
            path?: string;
            created?: boolean;
        };
        PlannedProductType: {
            code?: string;
            label?: string;
            created?: boolean;
            /** Format: int32 */
            attributeCount?: number;
        };
        /** @description Only the fields sent change; attributes, when sent, is the whole new list */
        UpdateTaxonomyProductType: {
            label?: string;
            /** @enum {string} */
            identifierRule?: "GTIN" | "ISBN";
            traits?: components["schemas"]["TaxonomyTraits"];
            attributes?: components["schemas"]["TaxonomyAttribute"][];
            reason: string;
        };
        RenameTaxonomyNode: {
            name: string;
            reason: string;
        };
        TaxonomyTree: {
            nodes?: components["schemas"]["TaxonomyNode"][];
            /** Format: int64 */
            pendingRequests?: number;
        };
        TaxonomyAuditEntry: {
            /** Format: uuid */
            id?: string;
            /** Format: date-time */
            at?: string;
            actorId?: string;
            action?: string;
            subjectKind?: string;
            subjectId?: string;
            subjectLabel?: string;
            reason?: string;
            details?: {
                [key: string]: unknown;
            };
        };
        /** @description A request with the products waiting on it and the department or category its requested path points to (null when the path does not exist) */
        CategoryRequestDetail: {
            request?: components["schemas"]["CategoryRequest"];
            products?: components["schemas"]["WaitingProduct"][];
            suggestedParent?: components["schemas"]["CategoryNode"];
        };
        WaitingProduct: {
            /** Format: uuid */
            id?: string;
            atin?: string;
            title?: string;
        };
        CreateBrandAdmin: {
            name: string;
            scope: string[];
            reason: string;
        };
        BrandAdmin: {
            /** Format: uuid */
            id?: string;
            name?: string;
            /** @enum {string} */
            status?: "APPROVED" | "PENDING" | "REJECTED" | "MERGED_INTO" | "RETIRED";
            /** Format: uuid */
            mergedIntoId?: string;
            mergedIntoName?: string;
            createdByOrg?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            updatedAt?: string;
            /** Format: int64 */
            version?: number;
            decidedBy?: string;
            /** Format: date-time */
            decidedAt?: string;
            decisionReason?: string;
            /** Format: int64 */
            products?: number;
            /** Format: int64 */
            sellers?: number;
            scope?: components["schemas"]["BrandScopeNode"][];
        };
        BrandScopeNode: {
            /** Format: uuid */
            nodeId?: string;
            path?: string;
            /** @enum {string} */
            level?: "DEPARTMENT" | "CATEGORY" | "PRODUCT_TYPE";
        };
        MergeBrand: {
            /** Format: uuid */
            targetId?: string;
            reason: string;
        };
        /** @description One brand per entry (the console splits a pasted list on new lines and commas); duplicates and invalid names are reported per entry */
        CreateBrandsBulk: {
            names: string[];
            scope: string[];
            reason: string;
        };
        BulkBrandOutcome: {
            name?: string;
            /** @enum {string} */
            outcome?: "CREATED" | "EXISTS" | "INVALID";
            /** Format: uuid */
            brandId?: string;
            message?: string;
        };
        BulkBrandResult: {
            results?: components["schemas"]["BulkBrandOutcome"][];
            /** Format: int32 */
            created?: number;
            /** Format: int32 */
            existing?: number;
            /** Format: int32 */
            invalid?: number;
        };
        Usage: {
            usage?: components["schemas"]["ImageUse"][];
            knownSkus?: string[];
        };
        AttachResult: {
            /** Format: int32 */
            productsUpdated?: number;
            /** Format: int32 */
            imagesAttached?: number;
            skipped?: components["schemas"]["Skipped"][];
        };
        Skipped: {
            sku?: string;
            reason?: string;
        };
        CreateBrandRequest: {
            name: string;
            productType?: string;
            category?: string;
        };
        CreatedBrand: {
            brand?: components["schemas"]["Brand"];
            created?: boolean;
        };
        /** @description Only the fields sent change; at least one must differ from the stored value */
        UpdateBrandAdmin: {
            name?: string;
            scope?: string[];
            reason: string;
        };
        BrandAdminList: {
            brands?: components["schemas"]["BrandAdmin"][];
            /** Format: int64 */
            total?: number;
            counts?: {
                [key: string]: number;
            };
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
    listBrands: {
        parameters: {
            query?: {
                productType?: string;
                category?: string;
                q?: string;
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
                    "*/*": components["schemas"]["BrandList"];
                };
            };
        };
    };
    createBrand: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateBrandRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CreatedBrand"];
                };
            };
        };
    };
    tree: {
        parameters: {
            query?: {
                includeRetired?: boolean;
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
    requestDetail: {
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
                    "*/*": components["schemas"]["CategoryRequestDetail"];
                };
            };
        };
    };
    previewApproval: {
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
                    "*/*": components["schemas"]["ApprovalPlan"];
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
    prefillGtin: {
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
                    "*/*": components["schemas"]["GtinPrefill"];
                };
            };
        };
    };
    attachLibraryImages: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["AttachLibraryImagesRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AttachLibraryImagesResult"];
                };
            };
        };
    };
    imageUsage: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ImageUsageRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ImageUsage"];
                };
            };
        };
    };
    listImports: {
        parameters: {
            query?: {
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
                    "*/*": components["schemas"]["ProductImportPage"];
                };
            };
        };
    };
    uploadImport: {
        parameters: {
            query?: {
                mode?: "CREATE" | "UPDATE";
                productType?: string;
                uploadedByName?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
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
                    "*/*": components["schemas"]["ProductImport"];
                };
            };
        };
    };
    importTemplate: {
        parameters: {
            query?: {
                productType?: string;
                categoryId?: string;
                format?: string;
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
                    "*/*": string;
                };
            };
        };
    };
    getImport: {
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
                    "*/*": components["schemas"]["ProductImport"];
                };
            };
        };
    };
    importReport: {
        parameters: {
            query?: {
                format?: string;
            };
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
                    "*/*": string;
                };
            };
        };
    };
    importRows: {
        parameters: {
            query?: {
                status?: "VALID" | "WARNING" | "ERROR";
                outcome?: "PENDING" | "CREATED" | "UPDATED" | "FAILED" | "SKIPPED";
                page?: number;
                size?: number;
            };
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
                    "*/*": components["schemas"]["ProductImportRowPage"];
                };
            };
        };
    };
    startImport: {
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
                "application/json": components["schemas"]["StartImportRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProductImport"];
                };
            };
        };
    };
    renderLabel: {
        parameters: {
            query?: {
                /** @description SVG (vector label), PNG (bare symbol, no text), ZPL (Zebra, 203 dpi), PDF (one page per label, or a sheet) */
                format?: "SVG" | "PNG" | "ZPL" | "PDF";
                layout?: "LABEL" | "SHEET";
                paper?: "LETTER" | "A4";
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LabelRequest"];
            };
        };
        responses: {
            /** @description The label */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "image/svg+xml": string;
                    "image/png": string;
                    "text/plain": string;
                    "application/pdf": string;
                };
            };
        };
    };
    renderLabelBatch: {
        parameters: {
            query?: {
                format?: "PDF" | "ZPL";
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["LabelBatchRequest"];
            };
        };
        responses: {
            /** @description The labels */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/pdf": string;
                    "text/plain": string;
                };
            };
        };
    };
    unitLabel: {
        parameters: {
            query?: {
                /** @description SVG (vector label), PNG (bare symbol, no text), ZPL (Zebra, 203 dpi), PDF (one page per label, or a sheet) */
                format?: "SVG" | "PNG" | "ZPL" | "PDF";
                copies?: number;
                orgId?: string;
            };
            header?: never;
            path: {
                atin: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The unit label */
            200: {
                headers: {
                    /** @description AT-FN code of the unit label (absent for a manufacturer barcode) */
                    "X-Unit-Label-Code"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "image/svg+xml": string;
                    "image/png": string;
                    "text/plain": string;
                    "application/pdf": string;
                };
            };
        };
    };
    listDrafts: {
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
                    "*/*": components["schemas"]["ProductDraftSummary"][];
                };
            };
        };
    };
    createDraft: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateDraftRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProductDraft"];
                };
            };
        };
    };
    getDraft: {
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
                    "*/*": components["schemas"]["ProductDraft"];
                };
            };
        };
    };
    saveDraft: {
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
                "application/json": components["schemas"]["SaveDraftRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProductDraft"];
                };
            };
        };
    };
    deleteDraft: {
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
                content?: never;
            };
        };
    };
    listProductReviews: {
        parameters: {
            query?: {
                status?: "PENDING" | "PUBLISHED" | "REJECTED" | "REMOVED" | "DELETED";
                atin?: string;
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
                    "*/*": components["schemas"]["ProductReviewPage"];
                };
            };
        };
    };
    getProductReview: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                reviewId: string;
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
                    "*/*": components["schemas"]["ProductReview"];
                };
            };
        };
    };
    approveProductReview: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                reviewId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["ReviewApproveRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProductReview"];
                };
            };
        };
    };
    rejectProductReview: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                reviewId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReviewRemoveRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProductReview"];
                };
            };
        };
    };
    removeProductReview: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                reviewId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReviewRemoveRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProductReview"];
                };
            };
        };
    };
    replyToProductReview: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                reviewId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReviewReplyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SellerReview"];
                };
            };
        };
    };
    reportProductReview: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                reviewId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReviewReportRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ReviewReportResult"];
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
                status?: "IN_REVIEW" | "PENDING_CATEGORY_APPROVAL" | "LIVE" | "REJECTED" | "PAUSED" | "TAKEN_DOWN";
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
    writeProductReview: {
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
                "application/json": components["schemas"]["WriteReviewRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OwnReview"];
                };
            };
        };
    };
    myProductReview: {
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
                    "*/*": components["schemas"]["MyReview"];
                };
            };
        };
    };
    editProductReview: {
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
                "application/json": components["schemas"]["EditReviewRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OwnReview"];
                };
            };
        };
    };
    deleteProductReview: {
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
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
    updateProduct: {
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
                "application/json": components["schemas"]["UpdateProductRequest"];
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
    flag: {
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
                "application/json": components["schemas"]["FlagListingRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ListingFlag"];
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
    preorderFlags: {
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
                "application/json": components["schemas"]["PreorderFlagsRequest"];
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
    restore: {
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
                "application/json": components["schemas"]["RestoreListingRequest"];
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
    takeDown: {
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
                "application/json": components["schemas"]["TakedownRequest"];
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
    productRating: {
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
                    "*/*": components["schemas"]["ProductRating"];
                };
            };
        };
    };
    reportListing: {
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
                "application/json": components["schemas"]["ReportListingRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ListingFlag"];
                };
            };
        };
    };
    publicProductReviews: {
        parameters: {
            query?: {
                stars?: number;
                sort?: "NEWEST" | "HIGHEST" | "LOWEST";
                page?: number;
                size?: number;
            };
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
                    "*/*": components["schemas"]["PublicReviewPage"];
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
    listSellerReviews: {
        parameters: {
            query?: {
                orgId?: string;
                unanswered?: boolean;
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
                    "*/*": components["schemas"]["SellerReviewPage"];
                };
            };
        };
    };
    taxonomyAudit: {
        parameters: {
            query?: {
                subject?: string;
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
                    "*/*": components["schemas"]["TaxonomyAuditEntry"][];
                };
            };
        };
    };
    listBrandRegistry: {
        parameters: {
            query?: {
                status?: string;
                q?: string;
                scope?: string;
                limit?: number;
                offset?: number;
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
                    "*/*": components["schemas"]["BrandAdminList"];
                };
            };
        };
    };
    createRegistryBrand: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateBrandAdmin"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BrandAdmin"];
                };
            };
        };
    };
    createRegistryBrands: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateBrandsBulk"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BulkBrandResult"];
                };
            };
        };
    };
    getBrandRegistryEntry: {
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
                    "*/*": components["schemas"]["BrandAdmin"];
                };
            };
        };
    };
    updateRegistryBrand: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateBrandAdmin"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BrandAdmin"];
                };
            };
        };
    };
    approveRegistryBrand: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxonomyReason"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BrandAdmin"];
                };
            };
        };
    };
    mergeRegistryBrand: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MergeBrand"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BrandAdmin"];
                };
            };
        };
    };
    rejectRegistryBrand: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxonomyReason"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BrandAdmin"];
                };
            };
        };
    };
    retireRegistryBrand: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxonomyReason"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BrandAdmin"];
                };
            };
        };
    };
    createNode: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateTaxonomyNode"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxonomyNode"];
                };
            };
        };
    };
    renameNode: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RenameTaxonomyNode"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxonomyNode"];
                };
            };
        };
    };
    moveNode: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MoveTaxonomyNode"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxonomyNode"];
                };
            };
        };
    };
    restoreNode: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxonomyReason"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxonomyNode"];
                };
            };
        };
    };
    retireNode: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TaxonomyReason"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxonomyNode"];
                };
            };
        };
    };
    listTaxonomyProductTypes: {
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
                    "*/*": components["schemas"]["TaxonomyProductType"][];
                };
            };
        };
    };
    createProductType: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateTaxonomyProductType"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxonomyProductType"];
                };
            };
        };
    };
    getTaxonomyProductType: {
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
                    "*/*": components["schemas"]["TaxonomyProductType"];
                };
            };
        };
    };
    updateProductType: {
        parameters: {
            query?: never;
            header?: {
                "If-Match"?: string;
            };
            path: {
                code: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateTaxonomyProductType"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxonomyProductType"];
                };
            };
        };
    };
    reorderNodes: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ReorderTaxonomyNodes"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TaxonomyNode"][];
                };
            };
        };
    };
    taxonomyTree: {
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
                    "*/*": components["schemas"]["TaxonomyTree"];
                };
            };
        };
    };
    variantAnyStatus: {
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
                    "*/*": components["schemas"]["VariantLookup"];
                };
            };
        };
    };
}
