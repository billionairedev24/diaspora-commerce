package com.afrotransact.taxreturns.application;

import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.RmaStatus;

/** Return search: customers see their own, sellers their organisation's, staff filter freely. */
public record ReturnSearch(String sellerOrgId, UUID orderId, RmaStatus status, int limit) {
}
