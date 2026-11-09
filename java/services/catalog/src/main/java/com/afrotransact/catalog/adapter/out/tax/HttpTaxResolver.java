package com.afrotransact.catalog.adapter.out.tax;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.tax.MissingTaxFact;
import com.afrotransact.catalog.port.ServiceTokens;
import com.afrotransact.catalog.port.TaxResolver;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * tax-returns {@code POST /v1/tax/classifications/resolve} with catalog's service token (scope tax:classify). The
 * resolution is a pure rules evaluation: a 200 is also returned for MISSING_FACTS and the gates; a 404 means tax-returns
 * has no profile for the category. Anything else is {@link TaxServiceUnavailable}: never guessed.
 */
public class HttpTaxResolver implements TaxResolver {

    private final RestClient taxReturns;
    private final ServiceTokens tokens;

    public HttpTaxResolver(RestClient.Builder builder, ServiceTokens tokens, String baseUrl, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.taxReturns = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MissingJson(String factId, String label, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResolutionJson(String status, String outcome, String internalTaxCode, String rowId, String rateDisplay,
                          List<MissingJson> missingFacts, String ruleVersion, String reason) {
    }

    @Override
    public Resolution resolve(Request request) {
        String token = tokens.token();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryCode", request.categoryCode());
        if (request.productTypeCode() != null) {
            body.put("productTypeCode", request.productTypeCode());
        }
        body.put("facts", request.facts());
        body.put("sellerBehaviour", request.sellerBehaviour());
        try {
            ResolutionJson r = taxReturns.post().uri("/v1/tax/classifications/resolve")
                    .headers(h -> {
                        if (token != null) {
                            h.setBearerAuth(token);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ResolutionJson.class);
            if (r == null || r.status() == null) {
                throw new TaxServiceUnavailable("tax-returns returned no resolution", null);
            }
            List<MissingTaxFact> missing = r.missingFacts() == null ? List.of()
                    : r.missingFacts().stream().map(m -> new MissingTaxFact(m.factId(), m.label(), m.message())).toList();
            return new Resolution(r.status(), r.outcome(), r.internalTaxCode(), r.rowId(), r.rateDisplay(), missing,
                    r.ruleVersion(), r.reason());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new TaxProfileNotFound(request.categoryCode());
            }
            throw new TaxServiceUnavailable("tax-returns answered " + ex.getStatusCode().value(), ex);
        } catch (RuntimeException ex) {
            if (ex instanceof TaxServiceUnavailable || ex instanceof TaxProfileNotFound) {
                throw ex;
            }
            throw new TaxServiceUnavailable("tax-returns could not be reached", ex);
        }
    }
}
