package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The audit behind the owner's rule "never live without a tax decision": the live listings that have a variant without a
 * stored, resolved tax decision. It is always empty when the gate holds; the staff console shows a banner when it is not,
 * and {@code catalog_live_without_tax_decision} pages. Needs the staff tax permission ({@code tax:read}).
 */
public interface LiveTaxAuditService {

    /** One page of the live listings that fail the gate, with what is missing per variant in plain words. */
    Audit liveWithoutTaxDecision(int page, int size);

    /** How many live listings fail the gate (the banner count). */
    long count();

    /**
     * @param total the number of live listings that fail the gate
     */
    record Audit(List<Item> items, long total, int page, int size) {

        public Audit {
            items = List.copyOf(items);
        }
    }

    /**
     * @param ownerOrgId the selling organisation (the staff API shows its name, never this id)
     * @param categoryName the category's path in words, e.g. "Groceries and food > Rice and grains" (null without one)
     */
    record Item(UUID productId, String atin, String title, String ownerOrgId, String categoryName, String status,
                Instant updatedAt, List<VariantItem> variants) {

        public Item {
            variants = List.copyOf(variants);
        }
    }

    /**
     * @param taxStatus the gate status of the variant (READY here means it has no stored decision)
     * @param reason    what is missing, in plain words
     */
    record VariantItem(String atin, String sellerSku, String taxStatus, String reason) {
    }
}
