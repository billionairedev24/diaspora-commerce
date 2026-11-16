package com.afrotransact.taxreturns.api;

import java.time.Instant;
import java.util.List;

import com.afrotransact.module.EndUserActor;

/**
 * The signed-in shopper's own returns (RMAs) and the refund that follows each, read in-process for a call made on their behalf (the
 * AI shopping agent's "my returns" tool): the in-process equivalent of {@code GET /v1/returns} with the shopper's token and
 * {@code X-Acting-As: CUSTOMER}. The person is the actor in force in tax-returns' own search, which scopes a customer to their own
 * returns. The calling module needs the delegation scope {@value #CALLER_SCOPE}.
 */
public interface ShopperReturnsApi {

    /** The scope a module needs to read returns on behalf of its signed-in users. */
    String CALLER_SCOPE = "returns:mine.read";

    /** The person's returns, newest first, at most {@code limit}. */
    List<ReturnSummary> mine(EndUserActor user, int limit);

    /** One return; {@code dropOffBy} and {@code createdAt} may be null. */
    record ReturnSummary(String number, String orderNumber, String status, String method, String refundState,
                         long estimateMinor, String currency, Instant dropOffBy, Instant createdAt, List<ReturnLine> lines) {

        public ReturnSummary {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** One item in a return. */
    record ReturnLine(String title, int quantity, String reason, String outcome) {
    }
}
