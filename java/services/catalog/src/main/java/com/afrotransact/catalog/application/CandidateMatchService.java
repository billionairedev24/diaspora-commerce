package com.afrotransact.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.matching.CandidateRanker.Kind;

/**
 * "Is your product already on AfroTransact?" without a barcode: the seller types what they are selling and gets up to six
 * existing pages to choose from, or says "None of these". Search-first, like the Amazon flow; the barcode lookup stays as
 * an optional exact shortcut ({@link CatalogMatchService}). Nothing here is attached for the seller: a page is only sold
 * after the seller picks it and confirms the size.
 */
public interface CandidateMatchService {

    /** catalog:write (sellers) or catalog:read (service clients); limited per organisation. */
    CandidateResult candidates(CandidateCommand command);

    /** Records the seller's choice (and checks it): the page they sell, or "None of these". */
    DecisionResult decide(DecisionCommand command);

    /**
     * The match step of a bulk upload for one row, for an organisation, with no permission check (the upload did it) and
     * no rate limit: exact normalised name, brand and size inside the category links the row; anything else is never
     * linked, and up to three candidates are returned for the seller to choose.
     */
    RowMatch matchRow(String sellerOrgId, String name, String brand, UUID categoryId);

    /**
     * @param query         what the seller typed: the name, with the size and brand inside it
     * @param categoryId    the category chip, when the seller (or an earlier answer) set it; null lets catalog suggest
     * @param sellerOrgId   service and staff tokens only
     */
    record CandidateCommand(String query, UUID categoryId, String sellerOrgId) {
    }

    /** A child of the page the seller sells (size, colour). */
    record CandidateVariant(String atin, Map<String, String> options) {
    }

    record CandidateImage(String url, Integer width, Integer height) {
    }

    /**
     * One page.
     *
     * @param parentAtin the page; offers attach to a variant
     * @param kind       SAME_NAME is exact; SIMILAR must be confirmed by the seller (both are a click)
     * @param likely     the score is high enough to say "likely your product"
     * @param sizeLabel  the page's size in words ("5 kg"), asked as one tap: null when the page has none
     * @param atin       the variant when the page has exactly one; else the seller picks from {@code variants}
     * @param canOffer   false with {@code blockers} when the organisation may not sell it
     */
    record Candidate(String parentAtin, String atin, String title, String brand, CandidateImage image, String category,
                     String sizeLabel, List<CandidateVariant> variants, Kind kind, boolean likely, boolean canOffer,
                     List<Blocker> blockers) {

        public Candidate {
            variants = variants == null ? List.of() : List.copyOf(variants);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    /**
     * @param candidates        best first, at most six
     * @param suggestedCategory the category most of the best pages share, when the seller gave none; null otherwise
     * @param tooShort          the name is too short to search yet
     * @param searchUnavailable the search index did not answer: no suggestions, the seller may still add the product
     */
    record CandidateResult(List<Candidate> candidates, Category suggestedCategory, Category category, boolean tooShort,
                           boolean searchUnavailable) {

        public CandidateResult {
            candidates = List.copyOf(candidates);
        }
    }

    record Category(UUID id, String path) {
    }

    enum Choice {
        SELL_EXISTING, NONE_OF_THESE
    }

    /**
     * @param query         the same name the candidates were searched with
     * @param categoryId    the same category chip
     * @param atin          SELL_EXISTING: the variant ATIN of a page that was shown
     * @param sizeConfirmed SELL_EXISTING: the seller tapped "yes, the same size" (required when the page states a size)
     */
    record DecisionCommand(Choice choice, String query, UUID categoryId, String atin, boolean sizeConfirmed,
                           String sellerOrgId) {
    }

    /**
     * @param decisionId     the stored answer
     * @param atin           SELL_EXISTING: the variant to sell; the next step is the price and quantity form
     * @param possibleDuplicateQueued NONE_OF_THESE: a likely page was shown, so staff will compare the new page with it
     */
    record DecisionResult(UUID decisionId, Choice choice, String atin, boolean canOffer, List<Blocker> blockers,
                          boolean possibleDuplicateQueued) {

        public DecisionResult {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }
    }

    enum RowKind {
        /** One page, exact: the row links to it. */
        LINKED,
        /** Several pages, or one that is only similar: the row is held for the seller to choose. */
        HELD,
        /** Nothing close: the row creates a new page. */
        NONE
    }

    /**
     * @param atin       LINKED: the variant (set only when the page has a single variant; otherwise the row is HELD)
     * @param candidates HELD: two or three pages to choose from
     */
    record RowMatch(RowKind kind, String atin, String parentAtin, String title, boolean canOffer, List<Blocker> blockers,
                    List<Candidate> candidates) {

        public RowMatch {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }
}
