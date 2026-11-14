package com.afrotransact.taxreturns.adapter.out.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.Jurisdiction;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.ProviderTaxCalculator;
import com.afrotransact.taxreturns.port.TaxProviderException;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ProviderTaxCalculator} over Avalara AvaTax REST v2 ({@code POST /api/v2/transactions/create}). Every call is a
 * {@code SalesOrder} with {@code commit=false}: AvaTax prices it and records nothing, so it is safe in SHADOW mode and
 * for a charging quote alike. Each line is sent with the AvaTax tax code the provider code map holds for the line's
 * OUTCOME code; a line without one is never sent and never priced at zero: strict calls refuse (the shadow report shows
 * "no code"), a charging quote falls back visibly to the rules engine (provider {@code RULES-FALLBACK}).
 *
 * <p>Credentials come from the environment only (AVALARA_ACCOUNT_ID, AVALARA_LICENSE_KEY, AVALARA_COMPANY_CODE,
 * AVALARA_ENVIRONMENT) and are sent as HTTP Basic auth; they are never logged or put in an error message.
 *
 * <p>This adapter only calculates: nothing is committed to AvaTax, and returns are not reported to it.
 */
@Component
class AvalaraTaxCalculator implements ProviderTaxCalculator {

    static final String PROVIDER = "AVALARA";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CLIENT_HEADER = "AfroTransact; tax-returns; REST; v2; shadow";

    private final TaxReturnsProperties.AvalaraTax config;
    private final RestClient avatax;
    private final ProviderFallback fallback;
    private final Clock clock;

    AvalaraTaxCalculator(RestClient.Builder builder, TaxReturnsProperties props, InHouseTaxCalculator rules,
                         TaxTelemetry telemetry, Clock clock) {
        this.config = props.tax().avalara();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(config.timeout()).build());
        factory.setReadTimeout(config.timeout());
        this.avatax = builder.clone().requestFactory(factory).baseUrl(config.baseUrl()).defaultHeaders(h -> {
            h.set("X-Avalara-Client", CLIENT_HEADER);
            if (config.configured()) {
                h.setBasicAuth(config.accountId(), config.licenseKey());
            }
        }).build();
        this.fallback = new ProviderFallback(rules, telemetry);
        this.clock = clock;
    }

    @Override
    public TaxProviderId provider() {
        return TaxProviderId.AVALARA;
    }

    @Override
    public boolean configured() {
        return config.configured();
    }

    @Override
    public TaxQuote quote(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        List<String> all = lines.stream().map(TaxLineInput::lineId).toList();
        if (!configured()) {
            return fallback.quote(PROVIDER, "not-configured", notConfigured(), shipTo, currency, lines, all);
        }
        List<String> uncoded = uncoded(lines);
        if (!uncoded.isEmpty()) {
            return fallback.quote(PROVIDER, "no-code", "no Avalara code for the outcome of " + uncoded, shipTo, currency,
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
        if (!configured()) {
            throw new TaxProviderException(notConfigured());
        }
        List<String> uncoded = uncoded(lines);
        if (!uncoded.isEmpty()) {
            throw new TaxProviderException("No Avalara code for lines " + uncoded);
        }
        try {
            return call(shipTo, currency, lines);
        } catch (RestClientResponseException ex) {
            throw new TaxProviderException("Avalara AvaTax refused the request (HTTP " + ex.getStatusCode().value() + "): "
                    + avataxMessage(ex.getResponseBodyAsString()), ex);
        } catch (RestClientException | IllegalStateException ex) {
            throw new TaxProviderException("Avalara AvaTax failed: " + ex.getClass().getSimpleName() + ": "
                    + ex.getMessage(), ex);
        }
    }

    private static String notConfigured() {
        return "Avalara is not configured: set AVALARA_ACCOUNT_ID, AVALARA_LICENSE_KEY and AVALARA_COMPANY_CODE";
    }

    private static List<String> uncoded(List<TaxLineInput> lines) {
        return lines.stream().filter(l -> l.codeFor(TaxProviderId.AVALARA) == null).map(TaxLineInput::lineId).toList();
    }

    private TaxQuote call(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        Prepared prepared = prepare(shipTo, currency, lines, null);
        JsonNode response = avatax.post().uri("/api/v2/transactions/create").contentType(MediaType.APPLICATION_JSON)
                .body(prepared.body()).retrieve().body(JsonNode.class);
        return parse(response, prepared, shipTo, currency, lines);
    }

    /** A request body and which of the caller's line ids each AvaTax line number stands for. */
    record Prepared(Map<String, Object> body, Map<String, String> lineByNumber) {
    }

    /**
     * Maps a quote to an AvaTax {@code CreateTransactionModel} (a SalesOrder that is not committed). Lines use the
     * provider code of the code map for the line's outcome; a line without one is left out (callers refuse such lines
     * beforehand, so nothing is ever sent as a guess).
     */
    Map<String, Object> request(TaxAddress shipTo, String currency, List<TaxLineInput> lines, String reference) {
        return prepare(shipTo, currency, lines, reference).body();
    }

    Prepared prepare(TaxAddress shipTo, String currency, List<TaxLineInput> lines, String reference) {
        Map<String, Object> ship = new LinkedHashMap<>();
        if (shipTo.city() != null && !shipTo.city().isBlank()) {
            ship.put("city", shipTo.city());
        }
        ship.put("region", shipTo.state());
        ship.put("country", shipTo.country());
        ship.put("postalCode", shipTo.postalCode());
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, String> byNumber = new HashMap<>();
        int n = 1;
        for (TaxLineInput l : lines) {
            String code = l.codeFor(TaxProviderId.AVALARA);
            if (code == null) {
                continue;
            }
            String number = Integer.toString(n++);
            byNumber.put(number, l.lineId());
            items.add(item(number, l.quantity(), l.taxableItemsMinor(), code, l.atin() == null ? l.lineId() : l.atin(),
                    l.lineId()));
            if (l.shippingMinor() > 0) {
                String shipNumber = Integer.toString(n++);
                byNumber.put(shipNumber, "ship:" + l.lineId());
                items.add(item(shipNumber, 1, l.shippingMinor(), config.shippingTaxCode(), "shipping",
                        "ship:" + l.lineId()));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "SalesOrder");
        body.put("companyCode", config.companyCode());
        body.put("date", LocalDate.now(clock).toString());
        body.put("customerCode", reference == null ? "guest" : reference);
        body.put("currencyCode", currency);
        body.put("commit", false);
        body.put("addresses", Map.of("shipTo", ship));
        body.put("lines", items);
        return new Prepared(body, byNumber);
    }

    private static Map<String, Object> item(String number, int quantity, long amountMinor, String taxCode,
                                            String itemCode, String ref) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("number", number);
        item.put("quantity", quantity);
        item.put("amount", minorToMajor(amountMinor));
        item.put("taxCode", taxCode);
        item.put("itemCode", itemCode);
        item.put("ref1", ref);
        item.put("taxIncluded", false);
        return item;
    }

    /** Reads AvaTax's transaction: tax and combined rate per line (the shipping line's tax is folded into its line). */
    TaxQuote parse(JsonNode response, Prepared prepared, TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        Map<String, Long> tax = new HashMap<>();
        Map<String, BigDecimal> rate = new HashMap<>();
        for (JsonNode l : response.path("lines")) {
            String id = prepared.lineByNumber().get(l.path("lineNumber").asString(""));
            if (id == null) {
                continue;
            }
            tax.put(id, minor(decimal(l.path("tax"))));
            BigDecimal combined = BigDecimal.ZERO;
            for (JsonNode d : l.path("details")) {
                combined = combined.add(decimal(d.path("rate")));
            }
            rate.put(id, combined);
        }
        if (tax.isEmpty() && !prepared.lineByNumber().isEmpty()) {
            throw new IllegalStateException("AvaTax returned no lines");
        }
        List<TaxLineResult> out = new ArrayList<>(lines.size());
        boolean collected = false;
        for (TaxLineInput l : lines) {
            Long itemTax = tax.get(l.lineId());
            if (itemTax == null) {
                throw new IllegalStateException("AvaTax returned no result for line " + l.lineId());
            }
            long shipTax = tax.getOrDefault("ship:" + l.lineId(), 0L);
            long total = Math.addExact(itemTax, shipTax);
            collected |= total > 0;
            BigDecimal combined = rate.getOrDefault(l.lineId(), BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            long taxable = l.taxableItemsMinor() + (shipTax > 0 ? l.shippingMinor() : 0L);
            out.add(new TaxLineResult(l.lineId(), l.sellerOrgId(), l.category(), taxable, combined, total,
                    shipTo.stateJurisdiction(), null, total > 0 ? CollectedBy.MARKETPLACE : CollectedBy.NOT_COLLECTED,
                    shipTax, l.taxCode(), null, Jurisdiction.ADDRESS));
        }
        return new TaxQuote(currency, shipTo.stateJurisdiction(),
                collected ? CollectedBy.MARKETPLACE : CollectedBy.NOT_COLLECTED, out, PROVIDER);
    }

    private static BigDecimal decimal(JsonNode n) {
        String text = n.asString("0");
        return text.isBlank() ? BigDecimal.ZERO : new BigDecimal(text);
    }

    private static long minor(BigDecimal major) {
        return major.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** The message AvaTax gave for a refusal ({@code {"error":{"message":...}}}), or a fixed text; never the request. */
    private static String avataxMessage(String body) {
        if (body == null || body.isBlank()) {
            return "no message";
        }
        try {
            String m = JSON.readTree(body).path("error").path("message")
                    .asString("");
            return m.isBlank() ? "no message" : m.length() <= 200 ? m : m.substring(0, 200);
        } catch (RuntimeException ex) {
            return "unreadable response";
        }
    }

    /** Two-decimal currencies only (USD at launch); other currencies need the currency's exponent. */
    private static String minorToMajor(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}
