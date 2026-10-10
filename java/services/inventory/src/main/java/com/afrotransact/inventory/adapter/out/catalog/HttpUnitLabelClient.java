package com.afrotransact.inventory.adapter.out.catalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.inventory.port.ServiceTokenProvider;
import com.afrotransact.inventory.port.UnitLabelClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * catalog-service labels with the inventory-service client-credentials token. The AT-FN code is read from the
 * {@code X-Unit-Label-Code} header when catalog sends it, otherwise from the human-readable line of the SVG unit
 * label ({@code <title>}); a manufacturer barcode (AfroTransact Retail stock with a GTIN) has no AT-FN code.
 * 5xx, timeouts and auth failures are retryable; other 4xx are rejections.
 */
public class HttpUnitLabelClient implements UnitLabelClient {

    static final String CODE_HEADER = "X-Unit-Label-Code";
    static final Pattern AT_FN = Pattern.compile("(?<![0-9A-Z])ATF[0-9A-Z]{7}(?![0-9A-Z])");
    private static final Pattern TITLE = Pattern.compile("<title>([^<]*)</title>");

    private final RestClient api;
    private final ServiceTokenProvider tokens;
    private final JsonMapper json;

    public HttpUnitLabelClient(RestClient api, ServiceTokenProvider tokens, JsonMapper json) {
        this.api = api;
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    public Optional<String> unitLabelCode(String atin, String ownerOrg) {
        ResponseEntity<String> res = call(() -> api.get()
                .uri(b -> b.path("/v1/labels/units/{atin}").queryParam("format", "SVG").queryParam("copies", 1)
                        .queryParam("orgId", ownerOrg).build(atin))
                .headers(this::auth).retrieve().toEntity(String.class));
        String header = res.getHeaders().getFirst(CODE_HEADER);
        if (header != null && AT_FN.matcher(header.trim().toUpperCase(Locale.ROOT)).matches()) {
            return Optional.of(header.trim().toUpperCase(Locale.ROOT));
        }
        String svg = res.getBody() == null ? "" : res.getBody();
        Matcher title = TITLE.matcher(svg);
        if (title.find()) {
            Matcher code = AT_FN.matcher(title.group(1));
            return code.find() ? Optional.of(code.group()) : Optional.empty();
        }
        return Optional.empty();
    }

    @Override
    public RenderedLabels render(List<LabelSpec> labels, String format) {
        List<Map<String, Object>> body = new ArrayList<>();
        for (LabelSpec l : labels) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", l.kind());
            m.put("symbology", "CODE_128");
            m.put("data", l.data());
            m.put("caption", l.caption());
            m.put("title", l.title());
            m.put("lines", l.lines());
            m.put("copies", l.copies());
            if (l.expiry() != null) {
                m.put("expiry", l.expiry().toString());
            }
            body.add(m);
        }
        String f = format == null ? "PDF" : format.toUpperCase(Locale.ROOT);
        ResponseEntity<byte[]> res = call(() -> api.post()
                .uri(b -> b.path("/v1/labels/batch").queryParam("format", f).build())
                .headers(this::auth).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(Map.of("labels", body, "layout", "LABEL")))
                .retrieve().toEntity(byte[].class));
        MediaType type = res.getHeaders().getContentType();
        return new RenderedLabels(type == null ? ("ZPL".equals(f) ? "text/plain" : "application/pdf")
                : type.toString(), "labels." + f.toLowerCase(Locale.ROOT),
                res.getBody() == null ? new byte[0] : res.getBody());
    }

    private void auth(HttpHeaders headers) {
        tokens.token().ifPresent(headers::setBearerAuth);
    }

    private <T> T call(Call<T> call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean retryable = e.getStatusCode().is5xxServerError() || status == 401 || status == 403
                    || status == 429;
            String detail = new String(e.getResponseBodyAsByteArray(), StandardCharsets.UTF_8);
            throw new LabelServiceException("catalog answered " + status + ": " + (detail.length() > 300
                    ? detail.substring(0, 300) : detail), retryable, e);
        } catch (RestClientException e) {
            throw new LabelServiceException("catalog unreachable: " + e.getMessage(), true, e);
        }
    }

    @FunctionalInterface
    private interface Call<T> {
        T run();
    }
}
