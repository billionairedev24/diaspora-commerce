package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanVersion;
import com.afrotransact.seller.port.PlanRepository;

/**
 * A plan for staff: metadata, every version with its prices and subscribers, and the invite list.
 *
 * @param rowVersion optimistic-lock version to send back with a metadata update
 */
public record PlanAdminView(Plan plan, long rowVersion, List<VersionView> versions,
                            List<String> invites, long subscribers) {

    public PlanAdminView {
        versions = List.copyOf(versions);
        invites = List.copyOf(invites);
    }

    /** One version with its prices and how many organisations are on it. */
    public record VersionView(PlanVersion version, List<PlanRepository.PriceRow> prices, long subscribers) {

        public VersionView {
            prices = List.copyOf(prices);
        }
    }
}
