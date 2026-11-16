package com.afrotransact.catalog.api;

import java.util.Map;

import com.afrotransact.module.EndUserActor;

/**
 * Catalog reads and the one write the MCP tools make, for the signed-in person (the in-process equivalent of relaying the person's bearer
 * token to catalog-service). Every method takes the person explicitly ({@link EndUserActor}, built from the request's authenticated
 * security context by the caller, never from a parameter) and runs catalog's own use case with that person as the actor in force, so
 * catalog's permission, organisation and ownership checks decide exactly as they do for the HTTP request: a seller sees its own
 * products, a customer only live ones, the staff-only queue is refused for anyone else (403 {@code auth.staff-only}).
 *
 * <p>The calling module needs the delegation scope {@value #READ_SCOPE} (reads) or {@value #WRITE_SCOPE} (the category request) in its
 * caller scopes; without it the call is refused with 403 {@code auth.missing-permission}. Results are the JSON tree of the same response
 * the HTTP endpoint answers with (maps, lists and scalars); failures are {@code ModuleApiException} with the status and code of the
 * RFC 9457 problem the HTTP endpoint gives.
 */
public interface CatalogUserApi {

    /** Delegation scope for the read methods. */
    String READ_SCOPE = "catalog:user.read";

    /** Delegation scope for {@link #requestCategory}. */
    String WRITE_SCOPE = "catalog:user.write";

    /** {@code GET /v1/public/products/{atin}}: a live product page. */
    Object publicProduct(EndUserActor user, String atin);

    /** {@code GET /v1/public/variants/{atin}}: a live variant with its product. */
    Object publicVariant(EndUserActor user, String atin);

    /** {@code GET /v1/products}: the products the person may list (a seller's own organisation; staff may name one). */
    Object products(EndUserActor user, String orgId, String status, String query, int page, int size);

    /** {@code GET /v1/products/{id}} or {@code /v1/products/atin/{atin}}: by product id (UUID) or ATIN. */
    Object product(EndUserActor user, String idOrAtin);

    /** {@code POST /v1/category-requests}: the body is validated as the HTTP request body is (400 on a missing or too short field). */
    Object requestCategory(EndUserActor user, Map<String, Object> request);

    /** {@code GET /v1/category-requests}: the review queue of category requests (staff only); a null status means the default. */
    Object categoryRequests(EndUserActor user, String status);
}
