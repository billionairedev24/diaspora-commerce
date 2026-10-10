package com.afrotransact.offers.application;

import java.util.UUID;

import com.afrotransact.offers.domain.OfferStatus;

/** Seller-facing offer use cases (seller center, AfroTransact Retail tooling). */
public interface OfferService {

    /** Attaches an offer to an existing live catalog variant (by ATIN or GTIN) for the caller's organisation. */
    OfferView create(CreateOfferCommand command);

    OfferView update(UUID id, UpdateOfferCommand command);

    OfferView pause(UUID id);

    OfferView resume(UUID id);

    OfferView withdraw(UUID id);

    OfferView get(UUID id);

    /** Offers of the caller's organisation; staff see all and may filter by org. */
    PageView<OfferView> list(String sellerOrgId, String atin, OfferStatus status, int page, int size);
}
