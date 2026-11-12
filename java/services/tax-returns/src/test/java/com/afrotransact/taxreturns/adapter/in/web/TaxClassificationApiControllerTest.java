package com.afrotransact.taxreturns.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.application.ClassificationResolveService;
import com.afrotransact.taxreturns.application.LineDecisionService.ListingStatus;
import com.afrotransact.taxreturns.application.ListingDecisionStatusService;
import com.afrotransact.taxreturns.domain.profile.ResolveStatus;
import com.afrotransact.taxreturns.application.ProviderCodeService;
import com.afrotransact.taxreturns.application.TaxProfileService;
import com.afrotransact.taxreturns.application.TaxShadowService;
import com.afrotransact.taxreturns.application.VariantClassificationService;
import com.afrotransact.taxreturns.domain.profile.MissingFact;
import com.afrotransact.taxreturns.domain.profile.Outcome;
import com.afrotransact.taxreturns.domain.profile.ProfileStatus;
import com.afrotransact.taxreturns.domain.profile.ProviderCode;
import com.afrotransact.taxreturns.domain.profile.Resolution;
import com.afrotransact.taxreturns.domain.profile.TaxMapFixture;
import com.afrotransact.taxreturns.domain.profile.TaxProfile;
import com.afrotransact.taxreturns.domain.shadow.ShadowKind;
import com.afrotransact.taxreturns.domain.shadow.ShadowRow;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.ShadowQuoteRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The JSON of the classification endpoints: request binding, enum and null handling, and the contract's field names. */
class TaxClassificationApiControllerTest {

    private final ClassificationResolveService resolve = mock(ClassificationResolveService.class);
    private final TaxProfileService profiles = mock(TaxProfileService.class);
    private final ProviderCodeService codes = mock(ProviderCodeService.class);
    private final VariantClassificationService variants = mock(VariantClassificationService.class);
    private final TaxShadowService shadow = mock(TaxShadowService.class);
    private final ListingDecisionStatusService listingStatus = mock(ListingDecisionStatusService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TaxClassificationApiController(listingStatus, resolve,
            profiles, codes, variants, shadow)).build();

    private static TaxProfile profile(String code) {
        return TaxMapFixture.profiles(TaxMapFixture.read("category_tax_map.json")).stream()
                .filter(p -> p.categoryCode().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void resolveBindsTheRequestAndAnswersWithStatusOutcomeCodesAndMissingFacts() throws Exception {
        when(resolve.resolve(any())).thenReturn(new Resolution(ResolveStatus.MISSING_FACTS, null, null, Map.of(), null,
                null, List.of(new MissingFact("net_weight_oz", "Net weight (oz)", "Net weight (oz) is needed. ...")),
                "tx-1", "Facts are missing; the tax is never guessed.", 0));

        mvc.perform(post("/v1/tax/classifications/resolve").contentType(MediaType.APPLICATION_JSON).content("""
                        {"categoryCode":"nuts-seeds","facts":{"is_candy":false,"net_weight_oz":2.5},
                         "sellerBehaviour":{"sold_heated":false},"shipToState":"TX","saleDate":"2026-11-09"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("MISSING_FACTS")))
                .andExpect(jsonPath("$.outcome", nullValue())).andExpect(jsonPath("$.ruleVersion", is("tx-1")))
                .andExpect(jsonPath("$.missingFacts", hasSize(1)))
                .andExpect(jsonPath("$.missingFacts[0].factId", is("net_weight_oz")));

        ArgumentCaptor<ClassificationResolveService.ResolveCommand> c = ArgumentCaptor.forClass(ClassificationResolveService.ResolveCommand.class);
        verify(resolve).resolve(c.capture());
        assertThat(c.getValue().facts()).containsEntry("is_candy", false)
                .containsEntry("net_weight_oz", 2.5);
        assertThat(c.getValue().saleDate()).isEqualTo(LocalDate.parse("2026-11-09"));
    }

    @Test
    void aResolvedAnswerCarriesTheProviderCodesPerProvider() throws Exception {
        when(resolve.resolve(any())).thenReturn(new Resolution(ResolveStatus.RESOLVED, Outcome.EXEMPT,
                "AT.RICE_GRAINS.STANDARD", Map.of("STRIPE", "txcd_40010002"), "S001", "0%", List.of(), "tx-1",
                "Standard qualifying item", 0));

        mvc.perform(post("/v1/tax/classifications/resolve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryCode\":\"rice-grains\",\"facts\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome", is("EXEMPT")))
                .andExpect(jsonPath("$.internalTaxCode", is("AT.RICE_GRAINS.STANDARD")))
                .andExpect(jsonPath("$.providerCodes.STRIPE", is("txcd_40010002")))
                .andExpect(jsonPath("$.rowId", is("S001"))).andExpect(jsonPath("$.openDecision", nullValue()));
    }

    @Test
    void theListingStatusEndpointAnswersPerAtinAndNeverCallsAResolvedWhatIsNotApproved() throws Exception {
        when(listingStatus.statusOf(any())).thenReturn(Map.of("AT00000001",
                new ListingStatus(ResolveStatus.NEEDS_APPROVAL, "PENDING_REVIEW", "not approved")));

        mvc.perform(post("/v1/tax/listing-decisions/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"atin\":\" at00000001\",\"sellerOrgId\":\"org_a\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].atin", is("AT00000001")))
                .andExpect(jsonPath("$.items[0].decisionStatus", is("NEEDS_APPROVAL")))
                .andExpect(jsonPath("$.items[0].recordStatus", is("PENDING_REVIEW")));

        mvc.perform(post("/v1/tax/listing-decisions/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[]}")).andExpect(status().isBadRequest());
    }

    @Test
    void theProfileDetailShowsFactsConditionsAndTheDecisionTable() throws Exception {
        when(profiles.get("nuts-seeds")).thenReturn(new TaxProfileService.ProfileView(profile("nuts-seeds"), null, "tx-1"));

        mvc.perform(get("/v1/tax/profiles/nuts-seeds")).andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryCode", is("nuts-seeds"))).andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.scenarioCount", is(3)))
                .andExpect(jsonPath("$.facts[?(@.id=='net_weight_oz')].kind", contains("DECIMAL")))
                .andExpect(jsonPath("$.facts[2].requiredWhen.all", hasSize(2)))
                .andExpect(jsonPath("$.decisionTable[0].internalTaxCode").exists())
                .andExpect(jsonPath("$.decisionTable[0].stripeCode").exists());
    }

    @Test
    void theStatusChangeAndTheCodeMapUpdateBindTheirBodies() throws Exception {
        when(profiles.changeStatus(eq("toys-games"), any())).thenReturn(new TaxProfileService.ProfileView(
                profile("toys-games").withStatus(ProfileStatus.ACTIVE), null, "tx-1+1"));
        when(codes.update(eq("AT.X.Y"), any())).thenReturn(new ProviderCode("AT.X.Y", "rice-grains", Outcome.EXEMPT,
                TaxProviderId.AVALARA, "PF050000", true, false, "n", 2, "STAFF:1", Instant.parse("2026-11-09T10:00:00Z")));

        mvc.perform(put("/v1/tax/profiles/toys-games/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"reason\":\"Owner decided\",\"approvedBy\":\"Smith CPA\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("ACTIVE")));
        mvc.perform(put("/v1/tax/provider-code-map/AT.X.Y").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"AVALARA\",\"providerCode\":\"PF050000\",\"reason\":\"From the list\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.providerCode", is("PF050000")))
                .andExpect(jsonPath("$.provider", is("AVALARA"))).andExpect(jsonPath("$.mapVersion", is(2)));
        mvc.perform(put("/v1/tax/profiles/toys-games/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\",\"reason\":\"x\"}")).andExpect(status().is4xxClientError());
    }

    @Test
    void theShadowReportShowsRatesPerGroupAndEachDifferenceWithBothSides() throws Exception {
        when(shadow.report(any(), any(), eq(TaxProviderId.STRIPE), eq(true), eq(0), eq(50))).thenReturn(
                new TaxShadowService.Report(LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"),
                        TaxProviderId.STRIPE, new ShadowQuoteRepository.Totals(10, 2, 300, -100),
                        List.of(new ShadowQuoteRepository.Group("AT.X.Y", "txcd_1", 10, 2, 300)), List.of(), List.of(),
                        List.of(new ShadowQuoteRepository.Group("TX", null, 10, 2, 300)), List.of(),
                        List.of(new ShadowRow(Instant.parse("2026-11-09T10:00:00Z"), "6f1f5c0e-1111-4222-8333-444455556666",
                                "1", "AT1", TaxProviderId.STRIPE, "TX", "nuts-seeds", "AT.X.Y", "txcd_1",
                                ShadowKind.OUTCOME, Outcome.EXEMPT, null, 0L, Outcome.TAXABLE, BigDecimal.ONE,
                                625L, null, 625, "USD")), 0, 50, Map.of("minDays", 30.0)));

        mvc.perform(get("/v1/tax/shadow/report?from=2026-11-01&to=2026-11-30&provider=STRIPE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.linesCompared", is(10))).andExpect(jsonPath("$.linesDiffering", is(2)))
                .andExpect(jsonPath("$.byInternalTaxCode[0].disagreementRate", is(0.2)))
                .andExpect(jsonPath("$.byInternalTaxCode[0].providerCode", is("txcd_1")))
                .andExpect(jsonPath("$.byState[0].key", is("TX")))
                .andExpect(jsonPath("$.differences[0].kind", is("OUTCOME")))
                .andExpect(jsonPath("$.differences[0].orderId", is("6f1f5c0e-1111-4222-8333-444455556666")))
                .andExpect(jsonPath("$.differences[0].inHouse.outcome", is("EXEMPT")))
                .andExpect(jsonPath("$.differences[0].provider.taxMinor", is(625)));
    }
}
