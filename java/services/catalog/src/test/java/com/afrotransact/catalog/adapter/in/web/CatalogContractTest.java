package com.afrotransact.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ChangeRequestInput;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ChangeRequestPageResponse;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ChangeRequestResponse;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.DecisionInput;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.DuplicateHintResponse;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ExemptionDecisionInput;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ExemptionPageResponse;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.ExemptionResponse;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.QueueItemResponse;
import com.afrotransact.catalog.adapter.in.web.ChangeRequestDtos.QueuePageResponse;
import com.afrotransact.catalog.adapter.in.web.CatalogSettingsController.PolicyDto;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.BlockerDto;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.EligibilityRequest;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.EligibilityResponse;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.MatchRequest;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.MatchResponse;
import com.afrotransact.catalog.adapter.in.web.PublicProductController.AtinRedirectResponse;
import com.afrotransact.catalog.application.Blocker;
import com.afrotransact.catalog.application.CandidateMatchService;
import com.afrotransact.catalog.application.CatalogMatchService;
import com.afrotransact.catalog.application.CatalogMatchService.MatchResult;
import com.afrotransact.catalog.application.CatalogMatchService.MatchVariant;
import com.afrotransact.catalog.application.CatalogMatchService.PageImage;
import com.afrotransact.catalog.application.ChangeRequestService.ChangeRequestView;
import com.afrotransact.catalog.application.GtinExemptionService.ExemptionView;
import com.afrotransact.catalog.application.IdentifierService.GtinCheck;
import com.afrotransact.catalog.application.OfferEligibilityService.Eligibility;
import com.afrotransact.catalog.application.PageView;
import com.afrotransact.catalog.application.ReviewQueueService.Kind;
import com.afrotransact.catalog.application.ReviewQueueService.QueueItem;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.DuplicateHints;
import com.afrotransact.catalog.domain.ExemptionRequirement;
import com.afrotransact.catalog.domain.GtinExemptionRequest;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.matching.CandidateRanker;
import com.afrotransact.catalog.testsupport.OpenApiContract;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalog contract (contracts/openapi/catalog.yaml) is written first and the code follows it. For the S381 to S387
 * endpoints: every route a controller serves is in the contract, every field of a response is in its schema, every field the
 * contract requires is present, and every field of a request schema is accepted by the request record.
 */
class CatalogContractTest {

    private static final OpenApiContract CONTRACT = OpenApiContract.of("catalog");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @Test
    void everyRouteTheNewControllersServeIsInTheContract() {
        Set<String> served = OpenApiContract.routesOf(MatchController.class, ChangeRequestController.class,
                CatalogSettingsController.class);
        served = new TreeSet<>(served);
        served.add("GET /v1/public/products/{atin}/redirect");

        assertThat(CONTRACT.routes()).containsAll(served);
    }

    @Test
    void theMatchResponseCarriesExactlyWhatTheContractDescribes() {
        MatchResult result = new MatchResult(CatalogMatchService.Result.MATCH,
                "00012345678905", "012345678905",
                "AT00000002", "AT00000001", "LIVE", "Ankara tote", "Adire House",
                new PageImage("https://cdn.example/a.jpg", 1200, 1200), "Fashion › Bags", UUID.randomUUID(),
                Map.of("Colour", "Blue"), List.of(new MatchVariant("AT00000002", Map.of("Colour", "Blue"), "00012345678905")),
                true, List.of(new Blocker("catalog.page-not-live", "Not on sale")), false,
                List.of(new Blocker("catalog.gtin-exists", "Already there")), "012345678905", "A message");

        assertResponseMatches(MatchResponse.of(result), "MatchResult");
        assertResponseMatches(new BlockerDto("c", "m"), "Blocker");
        assertResponseMatches(MatchDtos.PageImageDto.of(new PageImage("u", 1, 1)), "PageImage");
        assertResponseMatches(MatchDtos.MatchVariantDto.of(new MatchVariant("AT00000002", Map.of(), "00012345678905")),
                "MatchVariant");
        assertResponseMatches(EligibilityResponse.of(new Eligibility("AT00000002", "AT00000001", true, List.of(),
                List.of(ItemCondition.NEW), "org_a")), "OfferEligibility");
    }

    @Test
    void theMatchAndEligibilityRequestsAcceptEveryFieldOfTheirSchemas() {
        assertRequestAccepts(MatchRequest.class, "MatchRequest");
        assertRequestAccepts(EligibilityRequest.class, "OfferEligibilityRequest");
    }

    @Test
    void theCandidateSearchAndDecisionMatchTheContract() {
        CandidateMatchService.Candidate candidate = new CandidateMatchService.Candidate("AT00000001", "AT00000002",
                "Tilda Basmati Rice 5kg", "Tilda", new CandidateMatchService.CandidateImage("https://cdn.example/a.jpg", 1200, 1200),
                "Grocery › Rice", "5 kg", List.of(new CandidateMatchService.CandidateVariant("AT00000002", Map.of())),
                CandidateRanker.Kind.SAME_NAME, true, true, List.of(new Blocker("catalog.page-not-live", "Not on sale")));
        CandidateMatchService.Category category = new CandidateMatchService.Category(UUID.randomUUID(), "Grocery › Rice");
        assertResponseMatches(CandidateDtos.CandidateResponse.of(new CandidateMatchService.CandidateResult(List.of(candidate),
                category, category, false, false)), "CandidateSearchResult");
        assertResponseMatches(CandidateDtos.CandidateDto.of(candidate), "MatchCandidate");
        assertResponseMatches(CandidateDtos.CandidateImageDto.of(candidate.image()), "CandidateImage");
        assertResponseMatches(CandidateDtos.CandidateVariantDto.of(candidate.variants().getFirst()), "CandidateVariant");
        assertResponseMatches(CandidateDtos.CategoryDto.of(category), "MatchCategory");
        assertResponseMatches(CandidateDtos.DecisionResponse.of(new CandidateMatchService.DecisionResult(UUID.randomUUID(),
                CandidateMatchService.Choice.SELL_EXISTING, "AT00000002", true, List.of(), false)), "MatchDecisionResult");
        assertRequestAccepts(CandidateDtos.CandidateRequest.class, "CandidateSearchRequest");
        assertRequestAccepts(CandidateDtos.DecisionRequest.class, "MatchDecisionRequest");
    }

    @Test
    void theGtinCheckAnswerKeepsItsOldFieldsAndAddsGtin14AndResult() {
        JsonNode json = JSON.valueToTree(new GtinCheck("012345678905", "00012345678905", "MATCH", true, null, "AT00000001",
                "Matches"));

        assertThat(OpenApiContract.keys(json)).isSubsetOf(CONTRACT.properties("GtinCheck"));
        assertThat(CONTRACT.properties("GtinCheck")).contains("digits", "valid", "suggestion", "existingAtin", "message",
                "gtin14", "result");
    }

    @Test
    void theChangeRequestResponsesCarryExactlyWhatTheContractDescribes() {
        ProductChangeRequest r = ProductChangeRequest.filed(UUID.randomUUID(), "AT00000001", "AT00000002",
                "00012345678905", ChangeRequestType.WRONG_MATCH, "", Map.of("gtin", "012345678905"), "note", "org_b", "u",
                NOW, NOW.plusSeconds(3600)).applied("staff", "Reason", "CORRECT_GTIN", NOW);
        ChangeRequestView view = new ChangeRequestView(r, "Ankara tote", false, 5);

        assertResponseMatches(ChangeRequestResponse.of(view), "ChangeRequest");
        assertResponseMatches(ChangeRequestPageResponse.of(new PageView<>(List.of(view), 1, 0, 25)), "ChangeRequestPage");
    }

    @Test
    void theChangeRequestAndDecisionRequestsAcceptEveryFieldOfTheirSchemas() {
        assertRequestAccepts(ChangeRequestInput.class, "ChangeRequestInput");
        assertRequestAccepts(DecisionInput.class, "ChangeRequestDecisionInput");
        assertRequestAccepts(ExemptionDecisionInput.class, "ExemptionDecisionInput");
    }

    @Test
    void theExemptionAndQueueResponsesCarryExactlyWhatTheContractDescribes() {
        GtinExemptionRequest request = GtinExemptionRequest.filed(UUID.randomUUID(), "AT00000001", "org_b", "Handmade",
                ExemptionRequirement.KNOWN_GS1_BRAND, NOW, NOW.plusSeconds(3600)).approved("staff", "Checked", NOW);
        ExemptionView view = new ExemptionView(request, "Ankara tote", "Adire House", "AH-1",
                List.of(new DuplicateHints.Hint("AT00000005", "Tote", "Adire House", "AH-1", 1.0, List.of("BRAND_MPN"))),
                false, 3, "We are checking that this item has no barcode.");

        assertResponseMatches(ExemptionResponse.of(view), "GtinExemptionRequest");
        assertResponseMatches(ExemptionPageResponse.of(new PageView<>(List.of(view), 1, 0, 25)), "GtinExemptionPage");
        assertResponseMatches(DuplicateHintResponse.of(view.duplicateHints().getFirst()), "DuplicateHint");
        QueueItem item = new QueueItem(Kind.GTIN_EXEMPTION, UUID.randomUUID(), "AT00000001", "Ankara tote", "No barcode",
                "org_b", NOW, NOW.plusSeconds(3600), false, 3);
        assertResponseMatches(QueueItemResponse.of(item), "ReviewQueueItem");
        assertResponseMatches(QueuePageResponse.of(new PageView<>(List.of(item), 1, 0, 25)), "ReviewQueuePage");
        assertThat(CONTRACT.enumValues("ChangeRequestType")).containsExactlyInAnyOrderElementsOf(
                Stream.of(ChangeRequestType.values()).map(Enum::name).toList());
    }

    @Test
    void theSettingsAndRedirectCarryExactlyWhatTheContractDescribes() {
        CatalogPolicy policy = new CatalogPolicy(List.of("02"), "model", 2, 3, 1000, 1, List.of("bags"), true, "staff", NOW,
                3);
        assertResponseMatches(PolicyDto.of(policy), "CatalogPolicySettings");
        assertRequestAccepts(PolicyDto.class, "CatalogPolicySettings", Set.of("updatedBy", "updatedAt", "version"));
        assertResponseMatches(new AtinRedirectResponse("AT00000001", "AT00000005"), "AtinRedirect");
    }

    private static void assertResponseMatches(Object dto, String schema) {
        JsonNode json = JSON.valueToTree(dto);
        Set<String> sent = OpenApiContract.keys(json);
        assertThat(sent).as("fields of %s that are not in the contract schema %s", dto.getClass().getSimpleName(), schema)
                .isSubsetOf(CONTRACT.properties(schema));
        assertThat(sent).as("fields the contract requires in %s", schema).containsAll(CONTRACT.required(schema));
    }

    private static void assertRequestAccepts(Class<?> request, String schema) {
        assertRequestAccepts(request, schema, Set.of());
    }

    private static void assertRequestAccepts(Class<?> request, String schema, Set<String> optionalElsewhere) {
        Set<String> accepted = new TreeSet<>();
        for (var component : request.getRecordComponents()) {
            accepted.add(component.getName());
        }
        assertThat(accepted).as("fields of the contract's %s that %s does not accept", schema, request.getSimpleName())
                .containsAll(CONTRACT.properties(schema));
        assertThat(accepted).containsAll(CONTRACT.required(schema));
    }
}
