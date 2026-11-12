package com.afrotransact.catalog.adapter.out.tax;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.port.ServiceTokens;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * tax-returns {@code POST /v1/tax/listing-decisions/status} with catalog's service token (scope tax:classify), in batches
 * of {@value #BATCH}. Connect and read timeouts are short and any failure is {@link Unavailable}: a half answer is never
 * returned, so a caller can not mistake "unknown" for "resolved".
 */
public class HttpTaxDecisionAuthority implements TaxDecisionAuthority {

    static final int BATCH = 200;

    private final RestClient taxReturns;
    private final ServiceTokens tokens;

    public HttpTaxDecisionAuthority(RestClient.Builder builder, ServiceTokens tokens, String baseUrl, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.taxReturns = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnswerJson(String atin, String decisionStatus, String recordStatus, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResponseJson(List<AnswerJson> items) {
    }

    @Override
    public boolean consulted() {
        return true;
    }

    @Override
    public Map<String, Answer> ask(Collection<Ask> asks) {
        Map<String, Ask> distinct = new LinkedHashMap<>();
        asks.forEach(a -> distinct.putIfAbsent(a.atin(), a));
        Map<String, Answer> out = new LinkedHashMap<>();
        List<Ask> all = new ArrayList<>(distinct.values());
        for (int from = 0; from < all.size(); from += BATCH) {
            List<Ask> chunk = all.subList(from, Math.min(all.size(), from + BATCH));
            for (AnswerJson a : post(chunk)) {
                if (a.atin() != null) {
                    out.put(a.atin(), new Answer(a.atin(), a.decisionStatus(), a.recordStatus(), a.reason()));
                }
            }
        }
        return out;
    }

    private List<AnswerJson> post(List<Ask> chunk) {
        String token = tokens.token();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Ask a : chunk) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("atin", a.atin());
            m.put("sellerOrgId", a.sellerOrgId());
            items.add(m);
        }
        try {
            ResponseJson r = taxReturns.post().uri("/v1/tax/listing-decisions/status")
                    .headers(h -> {
                        if (token != null) {
                            h.setBearerAuth(token);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("items", items))
                    .retrieve()
                    .body(ResponseJson.class);
            if (r == null || r.items() == null) {
                throw new Unavailable("tax-returns returned no answer", null);
            }
            return r.items();
        } catch (Unavailable ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new Unavailable("tax-returns could not answer the tax decision check", ex);
        }
    }
}
