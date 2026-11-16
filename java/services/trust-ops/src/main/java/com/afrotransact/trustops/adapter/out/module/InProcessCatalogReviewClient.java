package com.afrotransact.trustops.adapter.out.module;

import java.util.UUID;

import com.afrotransact.catalog.api.ModerationApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.port.CatalogReviewClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link CatalogReviewClient} through catalog's published {@link ModerationApi} inside this JVM (mode {@code in-process}), entered as
 * trust-ops' caller principal (scope {@code catalog:moderate}); all three decisions are idempotent in catalog, as over HTTP.
 */
@Component
@ConditionalOnModuleMode(caller = "trust-ops", callee = "catalog", value = ModuleMode.IN_PROCESS)
class InProcessCatalogReviewClient implements CatalogReviewClient {

    private static final String SERVICE = "catalog";
    private static final int MAX_NOTE = 500;

    private final ObjectProvider<ModerationApi> api;
    private final ModuleUpstream upstream;

    InProcessCatalogReviewClient(ObjectProvider<ModerationApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ModerationApi.class, true, false).length > 0, "trust-ops",
                "catalog", "at.trustops.catalog.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public void approve(UUID reviewId, String note) {
        upstream.run(SERVICE, () -> api.getObject().approveReview(reviewId, cut(note)));
    }

    @Override
    public void reject(UUID reviewId, ModerationReasonCode reasonCode, String note) {
        upstream.run(SERVICE, () -> api.getObject().rejectReview(reviewId, reasonCode.name(), cut(note)));
    }

    @Override
    public void remove(UUID reviewId, ModerationReasonCode reasonCode, String note) {
        upstream.run(SERVICE, () -> api.getObject().removeReview(reviewId, reasonCode.name(), cut(note)));
    }

    private static String cut(String note) {
        return note == null ? null : note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note;
    }
}
