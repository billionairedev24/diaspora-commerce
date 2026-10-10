package com.afrotransact.offers.application;

import java.util.UUID;

import com.afrotransact.offers.domain.OfferStatus;

/** Seller-facing offer use cases (seller center, AfroTransact Retail tooling). */
public interface OfferService {

    /** Attaches an offer to an existing live catalog variant (by ATIN or GTIN) for the caller's organisation. */
    OfferView create(CreateOfferCommand command);

    /**
     * Changes the terms. {@code expectedVersion} (from If-Match or the body) is the version the caller edited: a newer
     * stored version is refused with 409 {@code offers.version-mismatch} (S259); null skips that check, but the write
     * is still conditional on the version read (409 {@code offers.concurrent-update}).
     */
    OfferView update(UUID id, UpdateOfferCommand command, Long expectedVersion);

    /** Without a version check (the write is still conditional on the version read). */
    OfferView update(UUID id, UpdateOfferCommand command);

    OfferView pause(UUID id, Long expectedVersion);

    /** Without a version check (the write is still conditional on the version read). */
    OfferView pause(UUID id);

    OfferView resume(UUID id, Long expectedVersion);

    /** Without a version check (the write is still conditional on the version read). */
    OfferView resume(UUID id);

    OfferView withdraw(UUID id, Long expectedVersion);

    /** Without a version check (the write is still conditional on the version read). */
    OfferView withdraw(UUID id);

    OfferView get(UUID id);

    /**
     * Sets the declared quantity of the caller's seller-fulfilled offers with this seller SKU (seller center stock
     * adjustments). Only the owning organisation (or staff naming it) can change it; Fulfilled-by-AfroTransact stock
     * comes from inventory and is refused. Setting an absolute quantity is idempotent.
     */
    StockUpdate setSellerStock(String sellerSku, int quantity, String reason, String sellerOrgId);

    /** Offers of the caller's organisation; staff see all and may filter by org. */
    PageView<OfferView> list(String sellerOrgId, String atin, OfferStatus status, int page, int size);
}
