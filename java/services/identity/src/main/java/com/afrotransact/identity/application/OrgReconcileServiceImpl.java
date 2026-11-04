package com.afrotransact.identity.application;

import java.util.Optional;

import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.SellerOrgSource;
import com.afrotransact.identity.port.SellerOrgSource.SellerOrgPage;
import com.afrotransact.identity.port.SellerOrgSource.SellerOrgState;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class OrgReconcileServiceImpl implements OrgReconcileService {

    static final int PAGE_SIZE = 200;

    private static final Logger LOG = LoggerFactory.getLogger(OrgReconcileServiceImpl.class);

    private final SellerOrgSource source;
    private final SellerOrgSyncService sync;
    private final OrganizationRepository orgs;
    private final CurrentUser current;

    OrgReconcileServiceImpl(SellerOrgSource source, SellerOrgSyncService sync, OrganizationRepository orgs,
                            CurrentUser current) {
        this.source = source;
        this.sync = sync;
        this.orgs = orgs;
        this.current = current;
    }

    @Override
    public OrgReconcileSummary reconcileAll() {
        int scanned = 0;
        int repaired = 0;
        int failed = 0;
        for (int page = 0; ; page++) {
            SellerOrgPage p = source.approvedOrSuspended(page, PAGE_SIZE);
            for (SellerOrgState state : p.items()) {
                scanned++;
                try {
                    if (sync.reconcile(change(state))) {
                        repaired++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                    LOG.warn("Organisation {} could not be reconciled: {}", state.orgId(), ex.getMessage(), ex);
                }
            }
            if (p.items().isEmpty() || (long) (page + 1) * PAGE_SIZE >= p.total()) {
                break;
            }
        }
        if (repaired > 0 || failed > 0) {
            LOG.info("Organisation reconcile: {} checked, {} repaired, {} failed", scanned, repaired, failed);
        }
        return new OrgReconcileSummary(scanned, repaired, failed);
    }

    @Override
    public OrgReconcileSummary reconcileAllOnRequest() {
        if (!privileged(current.actor())) {
            throw new ForbiddenActionException("identity.orgs-manage-required",
                    "Reconciling organisations needs a service or staff token with orgs:manage");
        }
        return reconcileAll();
    }

    @Override
    public OrgSyncResult sync(String orgId) {
        Actor actor = current.actor();
        Optional<SellerOrgState> state = source.org(orgId);
        if (state.isEmpty() || !(privileged(actor) || concerns(state.get(), actor.subject()))) {
            throw new NotFoundException("identity.org-not-found", "Organisation " + orgId + " not found");
        }
        boolean repaired = sync.reconcile(change(state.get()));
        String status = orgs.findById(orgId).map(Organization::status).map(Enum::name).orElse(null);
        return new OrgSyncResult(orgId, status, repaired);
    }

    private static boolean privileged(Actor actor) {
        return (actor.type() == PrincipalType.SERVICE || actor.type() == PrincipalType.STAFF)
                && actor.can(IdentityPermissions.ORGS_MANAGE);
    }

    private static boolean concerns(SellerOrgState state, String subject) {
        return subject != null && (subject.equals(state.applicantUserId()) || state.ownerUserIds().contains(subject));
    }

    private static SellerOrgChange change(SellerOrgState s) {
        return new SellerOrgChange(null, s.orgId(), s.kind(), s.primaryKind(), s.legalName(), s.displayName(),
                s.country(), s.status(), s.ownerUserIds(), s.applicantUserId(), s.updatedAt());
    }
}
