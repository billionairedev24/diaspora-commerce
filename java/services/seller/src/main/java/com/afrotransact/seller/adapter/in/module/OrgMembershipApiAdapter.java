package com.afrotransact.seller.adapter.in.module;

import java.util.Optional;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.seller.api.OrgMembershipApi;
import com.afrotransact.seller.application.OrgMembershipService;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of the published {@link OrgMembershipApi}: the use case behind
 * {@code GET /v1/orgs/{orgId}/members/{userId}/role}, entered under the calling module's principal. It adds no rule of its
 * own: {@link OrgMembershipService} requires {@code sellers:membership.read} and a service principal exactly as it does for a
 * token. It refuses to run without a caller, and when the actor in force is not that caller, so a misconfigured application
 * fails closed instead of running as whoever is on the thread.
 */
@Component
class OrgMembershipApiAdapter implements OrgMembershipApi {

    private final OrgMembershipService membership;
    private final ActorResolver actors;

    OrgMembershipApiAdapter(OrgMembershipService membership, ActorResolver actors) {
        this.membership = membership;
        this.actors = actors;
    }

    @Override
    public Optional<String> roleOf(String orgId, String userId) {
        CallerPrincipal caller = InProcessCalls.require();
        String subject = actors.current().subject();
        if (!caller.clientId().equals(subject)) {
            throw new IllegalStateException("The actor in force (" + subject + ") is not the in-process caller ("
                    + caller.clientId() + "): the ActorResolver does not honour in-process callers");
        }
        return membership.roleOf(orgId, userId);
    }
}
