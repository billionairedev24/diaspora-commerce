package com.afrotransact.payments.adapter.out.module;

import java.util.Optional;

import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.payments.port.OrgMembershipDirectory;
import com.afrotransact.payments.port.OrgMembershipUnavailableException;
import com.afrotransact.seller.api.OrgMembershipApi;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link OrgMembershipDirectory} through seller's published API inside this JVM (mode {@code in-process}): the live member list,
 * entered as payments' caller principal (scope {@code sellers:membership.read}). Not cached: there is no round trip to save.
 * Anything that is not a clear answer (the seller module refusing the principal, a failure) is
 * {@link OrgMembershipUnavailableException}, as over HTTP, so the money action is refused and never allowed.
 *
 * <p>The API is looked up on each call: payments and seller reach each other through ports, so a constructor dependency would be
 * a circular bean reference.
 */
public class InProcessOrgMembershipDirectory implements OrgMembershipDirectory {

    static final String MODULE = "payments";

    private final ObjectProvider<OrgMembershipApi> api;
    private final ObjectProvider<CallerPrincipals> callers;

    public InProcessOrgMembershipDirectory(ObjectProvider<OrgMembershipApi> api,
                                           ObjectProvider<CallerPrincipals> callers) {
        this.api = api;
        this.callers = callers;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Optional<String> roleOf(String orgId, String userId) {
        CallerPrincipal caller = callers.getObject().principalFor(MODULE);
        try {
            return InProcessCalls.as(caller, "seller", () -> api.getObject().roleOf(orgId, userId));
        } catch (RuntimeException ex) {
            throw new OrgMembershipUnavailableException("seller refused or failed the in-process membership call: "
                    + ex.getMessage(), ex);
        }
    }
}
