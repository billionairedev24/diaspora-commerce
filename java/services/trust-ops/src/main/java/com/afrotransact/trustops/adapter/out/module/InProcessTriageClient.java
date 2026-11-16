package com.afrotransact.trustops.adapter.out.module;

import java.util.Map;
import java.util.Optional;

import com.afrotransact.ai.api.TriageApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.domain.AiFlag;
import com.afrotransact.trustops.domain.TriageHint;
import com.afrotransact.trustops.port.TriageClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link TriageClient} through ai-gateway's published {@link TriageApi} inside this JVM (mode {@code in-process}), entered as trust-ops'
 * caller principal (scope {@code ai:triage}). The AI budget, rate limit and model fallback are ai-gateway's own and unchanged; a spent
 * budget or no model is an {@code UpstreamException} (429 / 503) as over HTTP, and the bounded wait of this edge is 10 s, double the
 * HTTP client's 5 s default, because a model call sits behind it. Empty when {@code at.trustops.ai.enabled=false}.
 */
@Component
@ConditionalOnModuleMode(caller = "trust-ops", callee = "ai-gateway", value = ModuleMode.IN_PROCESS)
class InProcessTriageClient implements TriageClient {

    private static final String SERVICE = "ai-gateway";

    private final ObjectProvider<TriageApi> api;
    private final ModuleUpstream upstream;
    private final boolean enabled;

    InProcessTriageClient(ObjectProvider<TriageApi> api, ModuleUpstream upstream, TrustOpsProperties props,
                          ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(TriageApi.class, true, false).length > 0, "trust-ops",
                "ai-gateway", "at.trustops.aigateway.mode");
        this.api = api;
        this.upstream = upstream;
        this.enabled = props.ai().enabled();
    }

    @Override
    public Optional<TriageHint> triage(String subjectType, String subjectId, Map<String, String> fields) {
        if (!enabled) {
            return Optional.empty();
        }
        TriageApi.Report r = upstream.call(SERVICE, () -> api.getObject().triage(subjectType, subjectId, fields));
        return Optional.of(new TriageHint(r.riskLevel(), r.summary(),
                r.flags().stream().map(f -> new AiFlag(f.code(), f.description(), f.citedFields())).toList(), r.model()));
    }
}
