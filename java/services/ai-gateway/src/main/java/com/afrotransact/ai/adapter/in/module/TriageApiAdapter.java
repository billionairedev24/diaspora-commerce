package com.afrotransact.ai.adapter.in.module;

import java.util.Map;

import com.afrotransact.ai.api.TriageApi;
import com.afrotransact.ai.application.Caller;
import com.afrotransact.ai.application.TriageService;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link TriageApi}: the use case behind {@code POST /v1/ai/triage}, entered under the calling module's
 * principal. {@link TriageService} is untouched, so its permission check ({@code ai:triage}), the per-feature budget and rate limit and
 * the model fallback decide exactly as for a token; the caller is built from the actor in force (the calling module, no token to relay).
 */
@Component
class TriageApiAdapter implements TriageApi {

    private final TriageService triage;
    private final ActorResolver actors;
    private final ModuleEntry entry;

    TriageApiAdapter(TriageService triage, ActorResolver actors) {
        this.triage = triage;
        this.actors = actors;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Report triage(String subjectType, String subjectId, Map<String, String> fields) {
        return entry.run(() -> {
            Actor actor = actors.current();
            Caller caller = new Caller(actor.subject(), actor.type().name(), actor.orgId().orElse(null), actor.permissions(), null);
            TriageService.Report r = triage.triage(caller, new TriageService.Request(subjectType, subjectId, fields));
            return new Report(r.riskLevel(), r.summary(), r.flags().stream()
                    .map(f -> new Flag(f.code(), f.description(), f.citedFields())).toList(), r.model());
        }, AiFailures::map);
    }
}
