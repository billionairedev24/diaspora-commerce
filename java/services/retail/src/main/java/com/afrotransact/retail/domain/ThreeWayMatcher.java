package com.afrotransact.retail.domain;

import java.util.List;

/** Matches PO price × received quantity against a supplier invoice within a tolerance (S074). */
public interface ThreeWayMatcher {

    /**
     * @param po         the purchase order with received and already-invoiced quantities
     * @param currency   invoice currency
     * @param lines      invoice lines
     * @param openClaims open receiving claims (shortage / damage) on the PO; they hold payment
     */
    MatchResult match(PurchaseOrder po, String currency, List<InvoiceLine> lines, long openClaims,
                      MatchTolerance tolerance);
}
