package com.afrotransact.catalog.adapter.in.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.adapter.in.web.MatchDtos.BlockerDto;
import com.afrotransact.catalog.application.CandidateMatchService.Candidate;
import com.afrotransact.catalog.application.CandidateMatchService.CandidateCommand;
import com.afrotransact.catalog.application.CandidateMatchService.CandidateImage;
import com.afrotransact.catalog.application.CandidateMatchService.CandidateResult;
import com.afrotransact.catalog.application.CandidateMatchService.CandidateVariant;
import com.afrotransact.catalog.application.CandidateMatchService.Category;
import com.afrotransact.catalog.application.CandidateMatchService.Choice;
import com.afrotransact.catalog.application.CandidateMatchService.DecisionCommand;
import com.afrotransact.catalog.application.CandidateMatchService.DecisionResult;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the search-first candidate lookup and the seller's decision. */
final class CandidateDtos {

    private CandidateDtos() {
    }

    @Schema(name = "CandidateSearchRequest", description = "What the seller is selling, as they would type it: the name, "
            + "with the size and the brand inside it. The category is optional.")
    record CandidateRequest(
            @NotBlank @Size(max = 200) @Schema(example = "Tilda basmati rice 5kg") String query,
            @Schema(description = "The category chip, when the seller set it; omitted lets catalog suggest one")
            UUID categoryId,
            @Schema(description = "Service and staff tokens only") String sellerOrgId) {

        CandidateCommand toCommand() {
            return new CandidateCommand(query, categoryId, sellerOrgId);
        }
    }

    @Schema(name = "CandidateImage")
    record CandidateImageDto(String url, Integer width, Integer height) {

        static CandidateImageDto of(CandidateImage i) {
            return new CandidateImageDto(i.url(), i.width(), i.height());
        }
    }

    @Schema(name = "CandidateVariant")
    record CandidateVariantDto(String atin, Map<String, String> options) {

        static CandidateVariantDto of(CandidateVariant v) {
            return new CandidateVariantDto(v.atin(), v.options());
        }
    }

    @Schema(name = "MatchCategory")
    record CategoryDto(UUID id, String path) {

        static CategoryDto of(Category c) {
            return c == null ? null : new CategoryDto(c.id(), c.path());
        }
    }

    @Schema(name = "MatchCandidate", description = "One existing product page. Nothing about any seller: no name, price, "
            + "stock or cost.")
    record CandidateDto(
            @Schema(description = "ATIN of the page") String parentAtin,
            @Schema(description = "The variant to sell when the page has exactly one; else the seller picks from variants")
            String atin,
            String title, String brand, CandidateImageDto image, String category,
            @Schema(description = "The page's size in words, for the one-tap question; null when it states none")
            String sizeLabel,
            List<CandidateVariantDto> variants,
            @Schema(allowableValues = {"SAME_NAME", "SIMILAR"}, description = "SAME_NAME: the normalised name, brand and "
                    + "size are equal (exact). SIMILAR: a suggestion by name (fuzzy). Both need the seller's click; a "
                    + "page that states a size also needs the size confirmed.") String kind,
            @Schema(description = "High enough to say 'likely your product'") boolean likely,
            boolean canOffer, List<BlockerDto> blockers) {

        static CandidateDto of(Candidate c) {
            return new CandidateDto(c.parentAtin(), c.atin(), c.title(), c.brand(), CandidateImageDto.of(c.image()),
                    c.category(), c.sizeLabel(), c.variants().stream().map(CandidateVariantDto::of).toList(),
                    c.kind().name(), c.likely(), c.canOffer(), c.blockers().stream().map(BlockerDto::of).toList());
        }
    }

    @Schema(name = "CandidateSearchResult")
    record CandidateResponse(List<CandidateDto> candidates,
                             @Schema(description = "When the request had no category: the one most of the best pages share")
                             CategoryDto suggestedCategory,
                             @Schema(description = "The category of the request, when it had one") CategoryDto category,
                             @Schema(description = "The name is too short to search yet") boolean tooShort,
                             @Schema(description = "The search index did not answer: no suggestions; the seller may still "
                                     + "add the product") boolean searchUnavailable) {

        static CandidateResponse of(CandidateResult r) {
            return new CandidateResponse(r.candidates().stream().map(CandidateDto::of).toList(),
                    CategoryDto.of(r.suggestedCategory()), CategoryDto.of(r.category()), r.tooShort(),
                    r.searchUnavailable());
        }
    }

    @Schema(name = "MatchDecisionRequest", description = "The seller's answer to the candidate list, sent with the same "
            + "query and category the list was searched with")
    record DecisionRequest(
            @NotNull @Schema(allowableValues = {"SELL_EXISTING", "NONE_OF_THESE"}) String choice,
            @NotBlank @Size(max = 200) String query, UUID categoryId,
            @Schema(description = "SELL_EXISTING: the variant ATIN of a page that was shown") String atin,
            @Schema(description = "SELL_EXISTING: the seller tapped 'yes, the same size'; required when the page states a "
                    + "size") boolean sizeConfirmed,
            @Schema(description = "Service and staff tokens only") String sellerOrgId) {

        DecisionCommand toCommand() {
            return new DecisionCommand(Choice.valueOf(choice), query, categoryId, atin, sizeConfirmed, sellerOrgId);
        }
    }

    @Schema(name = "MatchDecisionResult")
    record DecisionResponse(UUID decisionId, String choice, String atin, boolean canOffer, List<BlockerDto> blockers,
                            @Schema(description = "NONE_OF_THESE: a likely page was shown, so staff will compare the new "
                                    + "page with it") boolean possibleDuplicateQueued) {

        static DecisionResponse of(DecisionResult r) {
            return new DecisionResponse(r.decisionId(), r.choice().name(), r.atin(), r.canOffer(),
                    r.blockers().stream().map(BlockerDto::of).toList(), r.possibleDuplicateQueued());
        }
    }
}
