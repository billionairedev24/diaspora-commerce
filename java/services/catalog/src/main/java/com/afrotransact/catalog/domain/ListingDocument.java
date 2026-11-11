package com.afrotransact.catalog.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A document the seller uploaded on the product form (permit, brand authorization, safety certificate, insurance,
 * purchase invoice). Catalog only collects and marks it; the approval engine reviews it.
 *
 * @param kind      the rule field id ({@code permit}, {@code brand_authorization} ...)
 * @param url       the stored file
 * @param expiresOn the expiry date the document states (insurance, permits); null when none
 * @param status    {@link #PENDING_REVIEW} until the approval engine decides
 */
public record ListingDocument(String kind, String url, String fileName, String contentType, long bytes,
                              LocalDate expiresOn, String status) {

    public static final String PENDING_REVIEW = "PENDING_REVIEW";

    public ListingDocument {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(url, "url");
        fileName = fileName == null || fileName.isBlank() ? null : fileName.strip();
        status = status == null || status.isBlank() ? PENDING_REVIEW : status;
    }

    /** A freshly uploaded document waiting for review. */
    public ListingDocument(String kind, String url, String fileName, String contentType, long bytes,
                           LocalDate expiresOn) {
        this(kind, url, fileName, contentType, bytes, expiresOn, PENDING_REVIEW);
    }
}
