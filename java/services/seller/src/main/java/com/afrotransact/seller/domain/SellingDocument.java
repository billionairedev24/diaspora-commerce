package com.afrotransact.seller.domain;

import java.time.LocalDate;

/**
 * An uploaded document of an application or approval. The file is in media-service ({@code assetId}); the dates are
 * what the seller read off it: {@code issuedOn} for an invoice, {@code expiresOn} for a permit or insurance policy.
 *
 * @param namesAfroTransact the seller confirms the policy names AfroTransact as additional insured
 */
public record SellingDocument(String id, SellingDocumentKind kind, String assetId, String fileName, LocalDate issuedOn,
                              LocalDate expiresOn, boolean namesAfroTransact, String note) {
}
