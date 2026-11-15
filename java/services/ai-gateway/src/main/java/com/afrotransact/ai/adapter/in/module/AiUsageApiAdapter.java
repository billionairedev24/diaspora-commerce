package com.afrotransact.ai.adapter.in.module;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.ai.api.AiUsageApi;
import com.afrotransact.ai.application.Caller;
import com.afrotransact.ai.application.OrgUsageService;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link AiUsageApi}: the use case behind {@code GET /v1/ai/usage/orgs}, entered under the calling
 * module's principal. The caller is built from the actor in force (the calling module, its scopes; no token to relay), as the
 * controller builds it from the request's actor; {@link OrgUsageService} requires {@code ai:usage.read} (or staff) as for a token.
 */
@Component
class AiUsageApiAdapter implements AiUsageApi {

    private final OrgUsageService usage;
    private final ActorResolver actors;
    private final ModuleEntry entry;

    AiUsageApiAdapter(OrgUsageService usage, ActorResolver actors) {
        this.usage = usage;
        this.actors = actors;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<OrgDayUsage> usage(LocalDate from, LocalDate to) {
        return entry.run(() -> {
            Actor actor = actors.current();
            Caller caller = new Caller(actor.subject(), actor.type().name(), actor.orgId().orElse(null),
                    actor.permissions(), null);
            return usage.usage(caller, from, to).stream()
                    .map(c -> new OrgDayUsage(c.orgId(), c.day(), c.credits())).toList();
        });
    }
}
