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
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.TaxCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * {@link TaxCalculator} over Stripe Tax ({@code POST /v1/tax/calculations}). Stripe taxes each line by its provider tax
 * code in the places the account is registered. When Stripe is unreachable, or any line has no provider tax code yet,
 * the rules engine answers instead and the quote says so in {@code provider}; checkout is never blocked by tax.
 *
 * <p>This adapter only calculates. Recording the sale with Stripe (tax transactions) and refunds is a separate step.
 */
@Component
class StripeTaxCalculator implements TaxCalculator {

    static final String PROVIDER = "STRIPE_TAX";
    static final String FALLBACK = "RULES-FALLBACK";

    private static final Logger LOG = LoggerFactory.getLogger(StripeTaxCalculator.class);

    private final RestClient stripe;
    private final String shippingTaxCode;
    private final RulesTaxCalculator fallback;
    private final boolean configured;

    StripeTaxCalculator(RestClient.Builder builder, TaxReturnsProperties props, RulesTaxCalculator fallback) {
        TaxReturnsProperties.StripeTax cfg = props.tax().stripe();
        this.configured = cfg.secretKey() != null && !cfg.secretKey().isBlank();
        this.stripe = builder.clone().baseUrl(cfg.baseUrl()).defaultHeaders(h -> {
            if (configured) {
                h.setBearerAuth(cfg.secretKey());
            }
        }).build();
        this.shippingTaxCode = cfg.shippingTaxCode();
        this.fallback = fallback;
    }

    /** Whether Stripe Tax can be asked at all (its key is set). */
    boolean configured() {
        return configured;
    }

    @Override
    public TaxQuote quote(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        if (!"US".equals(shipTo.country())) {
            return fallback.quote(shipTo, currency, lines);
        }
        for (TaxLineInput l : lines) {
            if (l.providerTaxCode() == null || l.providerTaxCode().isBlank()) {
                LOG.warn("Line {} has no Stripe tax code ({}); the rules engine quotes this order", l.lineId(),
                        l.taxCode());
                return withProvider(fallback.quote(shipTo, currency, lines), FALLBACK);
            }
        }
        try {
            JsonNode response = stripe.post().uri("/v1/tax/calculations").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form(shipTo, currency, lines)).retrieve().body(JsonNode.class);
            return parse(response, shipTo, currency, lines);
        } catch (RestClientException | IllegalStateException ex) {
            LOG.warn("Stripe Tax unavailable ({}); the rules engine quotes this order", ex.getClass().getSimpleName());
            return withProvider(fallback.quote(shipTo, currency, lines), FALLBACK);
        }
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
            line(f, i++, l.lineId(), l.taxableItemsMinor(), l.quantity(), l.providerTaxCode());
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
                    shipTax, l.taxCode()));
        }
        return new TaxQuote(currency, shipTo.stateJurisdiction(),
                collected ? CollectedBy.MARKETPLACE : CollectedBy.NOT_COLLECTED, out, PROVIDER);
    }

    private static TaxQuote withProvider(TaxQuote q, String provider) {
        return new TaxQuote(q.currency(), q.jurisdiction(), q.collectedBy(), q.lines(), provider);
    }
}
