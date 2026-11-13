package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The optional barcode shortcut of adding a product: one exact lookup from a barcode to the product page that already
 * exists (S381). A seller without a barcode searches by name instead ({@link CandidateMatchService}). The seller of a
 * matched item sells on the page; the page content (images, title, attributes) is never theirs to change.
 */
public interface CatalogMatchService {

    /** catalog:write (sellers) or catalog:read (service clients). */
    MatchResult match(MatchCommand command);

    /**
     * The same lookup for an organisation, with no permission check: for catalog's own bulk upload, which has checked who
     * may upload and runs after the request (S393). {@code sellerOrgId} decides the {@code canOffer} answer.
     */
    MatchResult matchForOrganisation(MatchCommand command, String sellerOrgId);

    /**
     * @param gtin        a barcode as typed (spaces and dashes allowed; an ISBN-10 is converted)
     * @param sellerOrgId service and staff tokens only: the organisation asking; a seller always asks as itself
     */
    record MatchCommand(String gtin, String sellerOrgId) {
    }

    enum Result {
        MATCH, NO_MATCH, INVALID, RESTRICTED
    }

    record PageImage(String url, Integer width, Integer height) {
    }

    /** A child of the matched page the seller may offer too. */
    record MatchVariant(String atin, Map<String, String> options, String gtin14) {
    }

    /**
     * @param atin         the matched child (variant)
     * @param canOffer     false with {@code blockers} when the organisation may not sell it
     * @param canCreate    false when a new page for this barcode must not be created, with {@code createBlockers}
     */
    record MatchResult(Result result, String gtin14, String digits, String atin, String parentAtin,
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
