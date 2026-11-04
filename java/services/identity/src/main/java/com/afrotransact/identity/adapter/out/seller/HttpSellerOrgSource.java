package com.afrotransact.identity.adapter.out.seller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.afrotransact.identity.port.SellerOrgSource;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.json.JsonMapper;

/** {@link SellerOrgSource} over the seller service's org mirror API, with identity's own service token. */
public class HttpSellerOrgSource implements SellerOrgSource {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String base;
    private final Supplier<String> token;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpSellerOrgSource(String sellerUrl, Supplier<String> token) {
        this(sellerUrl, token, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    HttpSellerOrgSource(String sellerUrl, Supplier<String> token, HttpClient http) {
        this.base = sellerUrl.endsWith("/") ? sellerUrl.substring(0, sellerUrl.length() - 1) : sellerUrl;
        this.token = token;
        this.http = http;
    }

    /** Wire format of the seller service's OrgEventPayload. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrgMirror(String orgId, String kind, String primaryKind, String legalName, String displayName,
                     String country, String status, List<String> ownerUserIds, String applicantUserId,
                     Instant occurredAt) {

        SellerOrgState state() {
            return new SellerOrgState(orgId, kind, primaryKind, legalName, displayName, country, status, ownerUserIds,
                    applicantUserId, occurredAt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrgMirrorPage(List<OrgMirror> items, long total) {
    }

    @Override
    public SellerOrgPage approvedOrSuspended(int page, int size) {
        HttpResponse<String> res = get("/v1/orgs/mirror?status=APPROVED,SUSPENDED&page=" + page + "&size=" + size);
        if (res.statusCode() != 200) {
            throw new SellerOrgSourceException("seller service answered " + res.statusCode() + " to the org mirror");
        }
        OrgMirrorPage p = json.readValue(res.body(), OrgMirrorPage.class);
        List<OrgMirror> items = p.items() == null ? List.of() : p.items();
        return new SellerOrgPage(items.stream().map(OrgMirror::state).toList(), p.total());
    }

    @Override
    public Optional<SellerOrgState> org(String orgId) {
        HttpResponse<String> res = get("/v1/orgs/" + URLEncoder.encode(orgId, StandardCharsets.UTF_8) + "/mirror");
        if (res.statusCode() == 404) {
            return Optional.empty();
        }
        if (res.statusCode() != 200) {
            throw new SellerOrgSourceException("seller service answered " + res.statusCode() + " for " + orgId);
        }
        return Optional.of(json.readValue(res.body(), OrgMirror.class).state());
    }

    private HttpResponse<String> get(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT)
                .header("Accept", "application/json").header("Authorization", "Bearer " + token.get()).GET().build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("seller service unreachable at " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SellerOrgSourceException("interrupted calling the seller service");
        }
    }

    /** The seller service could not answer. */
    static class SellerOrgSourceException extends RuntimeException {

        SellerOrgSourceException(String message) {
            super(message);
        }
    }
}
