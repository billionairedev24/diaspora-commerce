package com.afrotransact.trustops.application;

import java.util.List;

import com.afrotransact.trustops.domain.ClaimType;

/**
 * Opens a claim. Customers open their own ({@code customerId} is taken from the token); staff with disputes:manage
 * may open one on a customer's behalf by passing {@code customerId}.
 */
public record OpenClaimCommand(ClaimType type, String orderId, String orderNumber, String shipmentId,
                               String sellerOrgId, String customerId, long amountMinor, String currency,
                               String description, List<String> evidenceAssetIds) {
}
