package com.afrotransact.trustops.adapter.out.module;

import java.util.UUID;

import com.afrotransact.catalog.api.ModerationApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.port.CatalogModerationClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link CatalogModerationClient} through catalog's published {@link ModerationApi} inside this JVM (mode {@code in-process}), entered as
 * trust-ops' caller principal (scope {@code catalog:moderate}). Notes are cut to the lengths the HTTP client cuts them to; a refusal
 * (409 wrong state, 422 tax gate, 503 checks unavailable) keeps its status and code as an {@code UpstreamException}.
 */
@Component
@ConditionalOnModuleMode(caller = "trust-ops", callee = "catalog", value = ModuleMode.IN_PROCESS)
class InProcessCatalogModerationClient implements CatalogModerationClient {

    private static final String SERVICE = "catalog";
    private static final int MAX_NOTE = 1000;

    private final ObjectProvider<ModerationApi> api;
    private final ModuleUpstream upstream;

    InProcessCatalogModerationClient(ObjectProvider<ModerationApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ModerationApi.class, true, false).length > 0, "trust-ops",
                "catalog", "at.trustops.catalog.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public void approve(UUID productId) {
        upstream.run(SERVICE, () -> api.getObject().approveProduct(productId));
    }

    @Override
    public void reject(UUID productId, String note) {
        upstream.run(SERVICE, () -> api.getObject().rejectProduct(productId, cut(note)));
    }

    @Override
    public void takeDown(UUID productId, ModerationReasonCode reasonCode, String note) {
        upstream.run(SERVICE, () -> api.getObject().takeDownProduct(productId, reasonCode.name(), cut(note)));
    }

    private static String cut(String note) {
        return note == null ? "" : note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note;
    }
}
