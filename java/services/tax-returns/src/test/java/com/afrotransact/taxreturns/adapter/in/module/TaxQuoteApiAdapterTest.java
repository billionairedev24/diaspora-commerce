package com.afrotransact.taxreturns.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.taxreturns.api.TaxQuoteApi;
import com.afrotransact.taxreturns.application.TaxService;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import org.junit.jupiter.api.Test;

/** S6 for the tax quote API: a caller without {@code tax:quote} is refused with 403, like a token without the scope. */
class TaxQuoteApiAdapterTest {

    private final TaxQuoteApi api = new TaxQuoteApiAdapter(InProcessKit.guarded(TaxService.class, "tax:quote"),
            InProcessKit.ACTORS);

    private static TaxQuoteApi.QuoteRequest request() {
        return new TaxQuoteApi.QuoteRequest("USD", new TaxQuoteApi.Place("US", "TX", "75001", "Addison", null),
                List.of(new TaxQuoteApi.Line("l1", "ATIN1", "org", "food", 1, 1000, 0, 0, Map.of(), null)), "o1");
    }

    @Test
    void quoteWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("orders"), () -> api.quote(request()));
    }

    @Test
    void ruleViolationsKeepTheirStatusAndCode() {
        assertThat(TaxReturnsFailures.map(new ReturnRuleViolation("tax.bad", "bad", Map.of())))
                .get().extracting(ModuleApiException::status, ModuleApiException::code).containsExactly(422, "tax.bad");
    }
}
