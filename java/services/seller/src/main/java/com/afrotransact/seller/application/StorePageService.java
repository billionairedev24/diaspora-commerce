package com.afrotransact.seller.application;

import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.StorePage;
import com.afrotransact.seller.domain.StorePageAuditEntry;
import com.afrotransact.seller.port.StorePageRepository;

/**
 * The seller's branded storefront settings: edit a draft, publish it, take it down. Members read; the owner and admins
 * change. Only an approved organisation can publish. Every change is audited.
 */
public interface StorePageService {

    /** The settings as they are now; an organisation that never saved has no row ({@code draft} empty, version 0). */
    StorePageView get(String orgId);

    /** Saves the draft; 409 when {@code expectedVersion} is not the stored one. */
    StorePageView saveDraft(String orgId, String draftJson, long expectedVersion);

    /** Publishes the draft at {@code expectedVersion}; 409 when it is stale, 422 when the organisation is not approved. */
    StorePageView publish(String orgId, long expectedVersion);

    StorePageView unpublish(String orgId);

    List<StorePageAuditEntry> audit(String orgId);

    /** The published settings of an approved organisation (public read); empty when unpublished or not approved. */
    Optional<StorePage> publishedFor(String orgId);

    /** Published storefronts of approved sellers, for the sitemap (public read). */
    List<StorePageRepository.PublishedStore> publishedStores(int limit);

    /** What the settings endpoints answer: the row (or an empty one) and whether the organisation may publish. */
    record StorePageView(StorePage page, boolean approved, boolean canEdit) {
    }
}
