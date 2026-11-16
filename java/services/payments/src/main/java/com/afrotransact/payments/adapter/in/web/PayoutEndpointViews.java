package com.afrotransact.payments.adapter.in.web;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON of the payout read endpoints the MCP tools use, for the in-process {@code PayoutReadApi}: each method calls the controller
 * handler of the HTTP endpoint (same use case, same response record) and turns the response into a JSON tree. It lives next to the
 * controller because its handlers and response records are package private; who may see which organisation is decided by the use case.
 */
@Component
public class PayoutEndpointViews {

    private final SettlementPayoutController payouts;
    private final JsonMapper json;

    PayoutEndpointViews(SettlementPayoutController payouts, JsonMapper json) {
        this.payouts = payouts;
        this.json = json;
    }

    public Object balance(String orgId) {
        return json.convertValue(payouts.balance(orgId, null), Object.class);
    }

    public Object requests(String orgId, int limit) {
        return json.convertValue(payouts.queue(null, orgId, limit), Object.class);
    }
}
