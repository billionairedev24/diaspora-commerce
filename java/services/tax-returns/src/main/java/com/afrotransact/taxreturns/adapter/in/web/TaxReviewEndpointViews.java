package com.afrotransact.taxreturns.adapter.in.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import com.afrotransact.module.ModuleApiException;
import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON of the two tax review endpoints the MCP tools read, for the in-process {@code TaxReviewReadApi}: each method calls the
 * controller handler of the HTTP endpoint (same use case, same response record) and turns the response into a JSON tree. It lives next
 * to the controller because its handlers and response records are package private; {@code tax:read} is checked by the use case.
 */
@Component
public class TaxReviewEndpointViews {

    private final TaxClassificationApiController controller;
    private final JsonMapper json;

    TaxReviewEndpointViews(TaxClassificationApiController controller, JsonMapper json) {
        this.controller = controller;
        this.json = json;
    }

    public Object reviewQueue(String status, String query, int page, int size) {
        return json.convertValue(controller.listVariants(recordStatus(status), null, null, query, null, null, null, "asc",
                false, page, size), Object.class);
    }

    public Object shadowReport(String from, String to, String provider, int size) {
        return json.convertValue(controller.shadowReport(date(from), date(to), providerId(provider), true, 0, size),
                Object.class);
    }

    private static LocalDate date(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ModuleApiException(400, "invalid-input", "Not an ISO date: " + iso);
        }
    }

    private static RecordStatus recordStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return RecordStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new ModuleApiException(400, "invalid-input", "Unknown record status " + status);
        }
    }

    private static TaxProviderId providerId(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        try {
            return TaxProviderId.valueOf(provider);
        } catch (IllegalArgumentException e) {
            throw new ModuleApiException(400, "invalid-input", "Unknown tax provider " + provider);
        }
    }
}
