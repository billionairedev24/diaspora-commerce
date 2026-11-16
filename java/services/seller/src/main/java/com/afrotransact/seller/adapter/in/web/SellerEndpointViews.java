package com.afrotransact.seller.adapter.in.web;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON of the seller endpoints the MCP tools read, for the in-process {@code SellerUserApi}: each method calls the controller
 * handler of the HTTP endpoint (same use case, same response record, so the same masking of personal data) and turns the response into
 * a JSON tree. It lives next to the controllers because their handlers and response records are package private; who may see what is
 * decided by the use cases.
 */
@Component
public class SellerEndpointViews {

    private final ReviewController reviews;
    private final ComplianceController compliance;
    private final JsonMapper json;

    SellerEndpointViews(ReviewController reviews, ComplianceController compliance, JsonMapper json) {
        this.reviews = reviews;
        this.compliance = compliance;
        this.json = json;
    }

    public Object accountHealth(String orgId) {
        return tree(compliance.accountHealth(orgId));
    }

    public Object applicationQueue(String status, int page, int size) {
        return tree(reviews.reviewQueue(status, null, null, null, null, false, false, false, page, size));
    }

    public Object application(String applicationId) {
        return tree(reviews.applicationDetail(applicationId));
    }

    private Object tree(Object response) {
        return json.convertValue(response, Object.class);
    }
}
