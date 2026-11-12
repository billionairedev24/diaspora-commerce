package com.afrotransact.catalog.application;

import java.util.Collection;
import java.util.Map;

import com.afrotransact.catalog.domain.imports.OfferTarget;
import com.afrotransact.catalog.domain.imports.TargetKey;

/**
 * The match step of a bulk upload (S393): which existing product page does each row name? One lookup per distinct key, by the
 * same service the seller form uses, so a barcode, an ATIN or a brand with a part number gives the same answer in a file as
 * on screen. Used at validation and again when the job runs (a page may have changed meanwhile).
 */
public interface ImportMatcher {

    /**
     * The page of every key, for the organisation that sells: its {@code canOffer} answer and blockers (selling approval,
     * department, page not on sale). The map is keyed by {@link TargetKey#id()}.
     */
    Map<String, OfferTarget> resolve(String sellerOrgId, Collection<TargetKey> keys);
}
