package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One lookup from a barcode, or a brand with a part number, to the product page that already exists (S381). The seller
 * of a matched item sells an offer on the page; the page content (images, title, attributes) is never theirs to change.
 */
public interface CatalogMatchService {

    /** catalog:write (sellers) or catalog:read (service clients). */
    MatchResult match(MatchCommand command);

    /**
     * @param gtin        a barcode as typed (spaces and dashes allowed; an ISBN-10 is converted), or null
     * @param brand       brand name or registry id, for items without a barcode
     * @param mpn         manufacturer part number
     * @param categoryId  narrows a brand plus part number match
     * @param sellerOrgId service and staff tokens only: the organisation asking; a seller always asks as itself
     */
    record MatchCommand(String gtin, String brand, String mpn, UUID categoryId, String sellerOrgId) {
    }

    enum Result {
        MATCH, NO_MATCH, INVALID, RESTRICTED
    }

    enum MatchedOn {
        GTIN, BRAND_MPN
    }

    record PageImage(String url, Integer width, Integer height) {
    }

    /** A child of the matched page the seller may offer too. */
    record MatchVariant(String atin, Map<String, String> options, String gtin14) {
    }

    /**
     * @param atin         the matched child (variant); null when a brand plus part number match names a page with several
     *                     children and the seller must pick one of {@code variants}
     * @param canOffer     false with {@code blockers} when the organisation may not sell it
     * @param canCreate    false when a new page for this barcode must not be created, with {@code createBlockers}
     */
    record MatchResult(Result result, MatchedOn matchedOn, String gtin14, String digits, String atin, String parentAtin,
                       String productStatus, String title, String brand, PageImage image, String category,
                       UUID categoryId, Map<String, String> variantOptions, List<MatchVariant> variants,
                       boolean canOffer, List<Blocker> blockers, boolean canCreate, List<Blocker> createBlockers,
                       String suggestion, String message) {

        public MatchResult {
            variantOptions = variantOptions == null ? Map.of() : Map.copyOf(variantOptions);
            variants = variants == null ? List.of() : List.copyOf(variants);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            createBlockers = createBlockers == null ? List.of() : List.copyOf(createBlockers);
        }
    }
}
