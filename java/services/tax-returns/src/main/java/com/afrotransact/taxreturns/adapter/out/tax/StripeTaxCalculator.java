package com.afrotransact.taxreturns.adapter.out.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.Jurisdiction;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.ProviderTaxCalculator;
import com.afrotransact.taxreturns.port.TaxProviderException;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * {@link ProviderTaxCalculator} over Stripe Tax ({@code POST /v1/tax/calculations}). Stripe taxes each line by the
 * provider code the code map holds for the line's OUTCOME code. When Stripe is unreachable, not set up, or any line has no
 * provider code, the rules engine answers instead and the quote says so: provider {@code RULES-FALLBACK}, the lines
 * listed, a counter and a warning. SHADOW mode asks strictly ({@link #quoteStrict}) so a fallback never hides Stripe's
 * answer.
 *
 * <p>This adapter only calculates. Recording the sale with Stripe (tax transactions) and refunds is a separate step.
 */
@Component
class StripeTaxCalculator implements ProviderTaxCalculator {

    static final String PROVIDER = "STRIPE_TAX";

    private final RestClient stripe;
    private final String shippingTaxCode;
    private final ProviderFallback fallback;
    private final boolean configured;

    StripeTaxCalculator(RestClient.Builder builder, TaxReturnsProperties props, InHouseTaxCalculator rules,
                        TaxTelemetry telemetry) {
        TaxReturnsProperties.StripeTax cfg = props.tax().stripe();
        this.configured = cfg.secretKey() != null && !cfg.secretKey().isBlank();
        this.stripe = builder.clone().baseUrl(cfg.baseUrl()).defaultHeaders(h -> {
            if (configured) {
                h.setBearerAuth(cfg.secretKey());
            }
        }).build();
        this.shippingTaxCode = cfg.shippingTaxCode();
        this.fallback = new ProviderFallback(rules, telemetry);
    }

    @Override
    public TaxProviderId provider() {
        return TaxProviderId.STRIPE;
    }

    /** Whether Stripe Tax can be asked at all (its key is set). */
    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public TaxQuote quote(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        List<String> all = lines.stream().map(TaxLineInput::lineId).toList();
        if (!configured) {
            return fallback.quote(PROVIDER, "not-configured", "no Stripe key", shipTo, currency, lines, all);
        }
        if (!"US".equals(shipTo.country())) {
            return fallback.quote(PROVIDER, "non-us", shipTo.country(), shipTo, currency, lines, all);
        }
        List<String> uncoded = lines.stream().filter(l -> l.codeFor(TaxProviderId.STRIPE) == null)
                .map(TaxLineInput::lineId).toList();
        if (!uncoded.isEmpty()) {
            return fallback.quote(PROVIDER, "no-code", "no Stripe code for the outcome of " + uncoded, shipTo, currency,
                    lines, uncoded);
        }
        try {
            return call(shipTo, currency, lines);
        } catch (RestClientException | IllegalStateException ex) {
            return fallback.quote(PROVIDER, "unavailable", ex.getClass().getSimpleName(), shipTo, currency, lines, all);
        }
    }

    @Override
    public TaxQuote quoteStrict(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        if (!configured) {
            throw new TaxProviderException("Stripe Tax is not configured (no key)");
        }
        if (!"US".equals(shipTo.country())) {
            throw new TaxProviderException("Stripe Tax is asked for US addresses only");
        }
        List<String> uncoded = lines.stream().filter(l -> l.codeFor(TaxProviderId.STRIPE) == null)
                .map(TaxLineInput::lineId).toList();
        if (!uncoded.isEmpty()) {
            throw new TaxProviderException("No Stripe code for lines " + uncoded);
        }
        try {
            return call(shipTo, currency, lines);
        } catch (RestClientException | IllegalStateException ex) {
            throw new TaxProviderException("Stripe Tax failed: " + ex.getClass().getSimpleName() + ": "
                    + ex.getMessage(), ex);
        }
    }

    private TaxQuote call(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        JsonNode response = stripe.post().uri("/v1/tax/calculations").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form(shipTo, currency, lines)).retrieve().body(JsonNode.class);
        return parse(response, shipTo, currency, lines);
    }

    MultiValueMap<String, String> form(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        MultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        f.add("currency", currency.toLowerCase(Locale.ROOT));
        f.add("customer_details[address][country]", shipTo.country());
        f.add("customer_details[address][state]", shipTo.state());
        f.add("customer_details[address][postal_code]", shipTo.postalCode());
        if (shipTo.city() != null && !shipTo.city().isBlank()) {
            f.add("customer_details[address][city]", shipTo.city());
        }
        f.add("customer_details[address_source]", "shipping");
        int i = 0;
        for (TaxLineInput l : lines) {
            line(f, i++, l.lineId(), l.taxableItemsMinor(), l.quantity(), l.codeFor(TaxProviderId.STRIPE));
            if (l.shippingMinor() > 0) {
                line(f, i++, "ship:" + l.lineId(), l.shippingMinor(), 1, shippingTaxCode);
            }
        }
        return f;
    }

    private static void line(MultiValueMap<String, String> f, int index, String reference, long amountMinor,
                             int quantity, String taxCode) {
        String p = "line_items[" + index + "]";
        f.add(p + "[amount]", Long.toString(amountMinor));
        f.add(p + "[quantity]", Integer.toString(quantity));
        f.add(p + "[reference]", reference);
        f.add(p + "[tax_code]", taxCode);
        f.add(p + "[tax_behavior]", "exclusive");
    }

    /** Reads Stripe's calculation: tax per line item (the shipping item's tax is folded into its line). */
    TaxQuote parse(JsonNode response, TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        Map<String, Long> tax = new HashMap<>();
        Map<String, BigDecimal> percent = new HashMap<>();
        for (JsonNode item : response.path("line_items").path("data")) {
            String ref = item.path("reference").asString("");
            tax.put(ref, item.path("amount_tax").asLong(0));
            BigDecimal pct = BigDecimal.ZERO;
            for (JsonNode b : item.path("tax_breakdown")) {
                String p = b.path("tax_rate_details").path("percentage_decimal").asString("0");
                pct = pct.add(new BigDecimal(p));
            }
            percent.put(ref, pct);
        }
        if (tax.isEmpty() && !lines.isEmpty()) {
            throw new IllegalStateException("Stripe returned no line items");
        }
        List<TaxLineResult> out = new ArrayList<>(lines.size());
        boolean collected = false;
        for (TaxLineInput l : lines) {
            long itemTax = tax.getOrDefault(l.lineId(), 0L);
            long shipTax = tax.getOrDefault("ship:" + l.lineId(), 0L);
            long total = Math.addExact(itemTax, shipTax);
            collected |= total > 0;
            BigDecimal rate = percent.getOrDefault(l.lineId(), BigDecimal.ZERO)
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP).stripTrailingZeros();
            long taxable = l.taxableItemsMinor() + (shipTax > 0 ? l.shippingMinor() : 0L);
            out.add(new TaxLineResult(l.lineId(), l.sellerOrgId(), l.category(), taxable, rate, total,
                    shipTo.stateJurisdiction(), null, total > 0 ? CollectedBy.MARKETPLACE : CollectedBy.NOT_COLLECTED,
                    shipTax, l.taxCode(), null, Jurisdiction.ADDRESS));
        }
        return new TaxQuote(currency, shipTo.stateJurisdiction(),
                collected ? CollectedBy.MARKETPLACE : CollectedBy.NOT_COLLECTED, out, PROVIDER);
    }
}
