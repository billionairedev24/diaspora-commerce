package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.domain.Invoice;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.port.EarningsCollectionRepository;

/**
 * The staff console's per-seller billing panel: plan, trial, credit balance, grants, invoices and collections from
 * earnings.
 *
 * @param overview null while the organisation has no subscription yet
 */
public record BillingPanel(String orgId, String orgName, OrgKind kind, String orgStatus, BillingOverview overview,
                           List<Invoice> invoices, List<BillingGrant> grants,
                           List<EarningsCollectionRepository.Row> collections, boolean earningsCollectionEnabled) {

    public BillingPanel {
        invoices = List.copyOf(invoices);
        grants = List.copyOf(grants);
        collections = List.copyOf(collections);
    }
}
