package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.identity.application.OrgReconcileService.OrgReconcileSummary;
import com.afrotransact.identity.application.OrgReconcileService.OrgSyncResult;
import com.afrotransact.identity.domain.OrgKind;
import com.afrotransact.identity.domain.OrgStatus;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.identity.port.SellerOrgSource;
import com.afrotransact.identity.port.SellerOrgSource.SellerOrgPage;
import com.afrotransact.identity.port.SellerOrgSource.SellerOrgState;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;

/* The reconcile job reads every approved or suspended organisation of the seller service page by page; the on-demand
   sync is open to the organisation's owners and approved applicant, and to services and staff with orgs:manage. */
class OrgReconcileServiceImplTest {

    private static final Instant AT = Instant.parse("2026-10-12T05:00:00Z");
    private static final String OWNER = "6db5a0a1-e0e7-4a58-8b78-9bc6492dd307";

    private final SellerOrgSource source = mock(SellerOrgSource.class);
    private final SellerOrgSyncService sync = mock(SellerOrgSyncService.class);
    private final OrganizationRepository orgs = mock(OrganizationRepository.class);
    private Actor caller = new Actor(OWNER, PrincipalType.CUSTOMER, Optional.empty(), Set.of("account.customer"), Set.of());
    private final OrgReconcileServiceImpl service = new OrgReconcileServiceImpl(source, sync, orgs,
            new CurrentUser(() -> caller));

    private static SellerOrgState state(String id) {
        return new SellerOrgState(id, "SELLER", "SELLER", "Legal", "Store", "US", "APPROVED", List.of(OWNER), OWNER, AT);
    }

    @Test
    void reconcilesEveryPageAndCountsRepairsAndFailures() {
        List<SellerOrgState> all = new ArrayList<>();
        for (int i = 0; i < OrgReconcileServiceImpl.PAGE_SIZE + 2; i++) {
            all.add(state(String.format("org_rec%015d", i)));
        }
        when(source.approvedOrSuspended(0, OrgReconcileServiceImpl.PAGE_SIZE))
                .thenReturn(new SellerOrgPage(all.subList(0, OrgReconcileServiceImpl.PAGE_SIZE), all.size()));
        when(source.approvedOrSuspended(1, OrgReconcileServiceImpl.PAGE_SIZE))
                .thenReturn(new SellerOrgPage(all.subList(OrgReconcileServiceImpl.PAGE_SIZE, all.size()), all.size()));
        when(sync.reconcile(any())).thenAnswer(i -> {
            SellerOrgChange c = i.getArgument(0);
            if (c.orgId().endsWith("1")) {
                throw new IllegalStateException("database unavailable");
            }
            return c.orgId().endsWith("0");
        });

        OrgReconcileSummary s = service.reconcileAll();
        assertThat(s.scanned()).isEqualTo(all.size());
        assertThat(s.failed()).isEqualTo(21);
        assertThat(s.repaired()).isEqualTo(21);
    }

    @Test
    void anOwnerSyncsTheirOrganisation() {
        String id = "org_malobranding000001";
        when(source.org(id)).thenReturn(Optional.of(state(id)));
        when(sync.reconcile(any())).thenReturn(true);
        when(orgs.findById(id)).thenReturn(Optional.of(new Organization(id, OrgKind.SELLER, "Store", OrgStatus.APPROVED,
                "US", AT)));
        assertThat(service.sync(id)).isEqualTo(new OrgSyncResult(id, "APPROVED", true));
    }

    @Test
    void anyoneElseGetsNotFoundAndNothingChanges() {
        String id = "org_someoneelse0000001";
        when(source.org(id)).thenReturn(Optional.of(new SellerOrgState(id, "SELLER", "SELLER", "L", "S", "US",
                "APPROVED", List.of("someone-else"), "someone-else", AT)));
        assertThatThrownBy(() -> service.sync(id)).isInstanceOf(NotFoundException.class);
        when(source.org("org_unknown")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sync("org_unknown")).isInstanceOf(NotFoundException.class);
        verify(sync, never()).reconcile(any());
    }

    @Test
    void reconcilingEverythingOnRequestNeedsOrgsManage() {
        assertThatThrownBy(service::reconcileAllOnRequest).isInstanceOf(ForbiddenActionException.class);
        caller = new Actor("seller-service", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of("orgs:manage"));
        when(source.approvedOrSuspended(0, OrgReconcileServiceImpl.PAGE_SIZE)).thenReturn(new SellerOrgPage(List.of(), 0));
        assertThat(service.reconcileAllOnRequest()).isEqualTo(new OrgReconcileSummary(0, 0, 0));
    }
}
