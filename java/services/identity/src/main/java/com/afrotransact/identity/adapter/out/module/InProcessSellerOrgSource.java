package com.afrotransact.identity.adapter.out.module;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.afrotransact.identity.port.SellerOrgSource;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.seller.api.OrgMirrorApi;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link SellerOrgSource} through seller's published {@link OrgMirrorApi} inside this JVM (mode {@code in-process}), entered as
 * identity's caller principal (scope {@code sellers:directory}). A plain data call: identity's own token validation (JWT and
 * JWKS) is not part of it and is unchanged. As over HTTP an unknown organisation is empty and any other refusal is a failure the
 * reconcile job already handles (it logs and retries on the next run).
 */
public class InProcessSellerOrgSource implements SellerOrgSource {

    static final String MODULE = "identity";

    private final ObjectProvider<OrgMirrorApi> api;
    private final ObjectProvider<CallerPrincipals> callers;

    public InProcessSellerOrgSource(ObjectProvider<OrgMirrorApi> api, ObjectProvider<CallerPrincipals> callers) {
        this.api = api;
        this.callers = callers;
    }

    @Override
    public SellerOrgPage approvedOrSuspended(int page, int size) {
        OrgMirrorApi.OrgMirrorPage p = call(() -> api.getObject().page(page, size, List.of("APPROVED", "SUSPENDED")),
                "the org mirror");
        return new SellerOrgPage(p.items().stream().map(InProcessSellerOrgSource::state).toList(), p.total());
    }

    @Override
    public Optional<SellerOrgState> org(String orgId) {
        return call(() -> api.getObject().one(orgId), orgId).map(InProcessSellerOrgSource::state);
    }

    private <T> T call(Supplier<T> call, String what) {
        try {
            return InProcessCalls.as(callers.getObject().principalFor(MODULE), "seller", call);
        } catch (ModuleApiException e) {
            throw new IllegalStateException("seller module answered " + e.status() + " (" + e.code() + ") for " + what, e);
        }
    }

    private static SellerOrgState state(OrgMirrorApi.OrgMirror o) {
        return new SellerOrgState(o.orgId(), o.kind(), o.primaryKind(), o.legalName(), o.displayName(), o.country(),
                o.status(), o.ownerUserIds(), o.applicantUserId(), o.occurredAt());
    }
}
