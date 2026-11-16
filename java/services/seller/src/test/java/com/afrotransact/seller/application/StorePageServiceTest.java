package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.Organisation;
import com.afrotransact.seller.domain.StorePage;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.StorePageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StorePageServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final String ORG = "org_1";

    private final StorePageRepository pages = mock(StorePageRepository.class);
    private final OrganisationRepository orgs = mock(OrganisationRepository.class);
    private final AccessGuard guard = mock(AccessGuard.class);
    private StorePageService service;

    @BeforeEach
    void wire() {
        service = new StorePageServiceImpl(pages, orgs, guard, Clock.fixed(NOW, ZoneOffset.UTC));
        when(guard.liveOrgRole(eq(ORG), any(), any())).thenReturn(
                new Actor("user_1", PrincipalType.CUSTOMER, Optional.of(ORG), Set.of(), Set.of()));
        approved(OrgStatus.APPROVED);
    }

    private void approved(OrgStatus status) {
        when(orgs.findById(ORG)).thenReturn(Optional.of(
                new Organisation(ORG, OrgKind.SELLER, "Store LLC", "Store", "US", null, status, NOW, NOW, 0)));
    }

    private void provider(OrgStatus status) {
        when(orgs.findById(ORG)).thenReturn(Optional.of(
                new Organisation(ORG, OrgKind.PROVIDER, "Braids LLC", "Braids", "US", null, status, NOW, NOW, 0)));
    }

    @Test
    void aProviderPublishesItsPageLikeASellerOnceApproved() {
        provider(OrgStatus.APPROVED);
        when(pages.find(ORG)).thenReturn(Optional.of(row(1, null, null)));
        when(pages.publish(eq(ORG), eq(1L), any(), anyString())).thenReturn(true);
        service.publish(ORG, 1);
        verify(pages).publish(eq(ORG), eq(1L), any(), anyString());
        verify(pages).audit(eq(ORG), eq("PUBLISHED"), anyString(), eq(1L), any());
        when(pages.find(ORG)).thenReturn(Optional.of(row(2, "{\"a\":1}", 2L)));
        assertThat(service.publishedFor(ORG)).isPresent();
    }

    @Test
    void aProviderThatIsNotApprovedCannotPublishAndHasNoPublicPage() {
        provider(OrgStatus.PENDING_REVIEW);
        assertThatThrownBy(() -> service.publish(ORG, 1)).isInstanceOf(BusinessRuleException.class);
        verify(pages, never()).publish(any(), anyLong(), any(), any());
        when(pages.find(ORG)).thenReturn(Optional.of(row(2, "{\"a\":1}", 2L)));
        assertThat(service.publishedFor(ORG)).isEmpty();
    }

    @Test
    void theDraftReadsBeforeAnySaveOrPublishAndTheUnpublishedPageIsNeverPublic() {
        // The seller center's preview is built from this read: it must work for a new, not yet published page.
        when(pages.find(ORG)).thenReturn(Optional.empty());
        StorePageService.StorePageView view = service.get(ORG);
        assertThat(view.page()).isNull();
        assertThat(view.approved()).isTrue();
        assertThat(service.publishedFor(ORG)).isEmpty();
        when(pages.find(ORG)).thenReturn(Optional.of(row(1, null, null)));
        assertThat(service.get(ORG).page()).isNotNull();
        assertThat(service.publishedFor(ORG)).isEmpty();
    }

    private static StorePage row(long version, String published, Long publishedVersion) {
        return new StorePage(ORG, "{\"a\":1}", published, version, publishedVersion, NOW, "user_1",
                published == null ? null : NOW);
    }

    @Test
    void firstSaveCreatesTheRowAtVersionOne() {
        when(pages.find(ORG)).thenReturn(Optional.empty(), Optional.of(row(1, null, null)));
        when(pages.insert(eq(ORG), anyString(), any(), anyString())).thenReturn(true);
        assertThat(service.saveDraft(ORG, "{\"a\":1}", 0).page().version()).isEqualTo(1);
        verify(pages).audit(eq(ORG), eq("DRAFT_SAVED"), eq("user_1"), eq(1L), any());
    }

    @Test
    void aStaleVersionIsAConflict() {
        when(pages.find(ORG)).thenReturn(Optional.of(row(3, null, null)));
        when(pages.updateDraft(eq(ORG), anyString(), eq(2L), any(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.saveDraft(ORG, "{\"a\":1}", 2)).isInstanceOf(ConflictException.class);
        verify(pages, never()).audit(anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void aDocumentThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> service.saveDraft(ORG, "[1]", 0)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void onlyAnApprovedOrganisationPublishes() {
        approved(OrgStatus.PENDING_REVIEW);
        assertThatThrownBy(() -> service.publish(ORG, 1)).isInstanceOf(BusinessRuleException.class);
        verify(pages, never()).publish(anyString(), anyLong(), any(), anyString());
    }

    @Test
    void publishWritesAnAuditEntry() {
        when(pages.find(ORG)).thenReturn(Optional.of(row(2, "{\"a\":1}", 2L)));
        when(pages.publish(eq(ORG), eq(2L), any(), eq("user_1"))).thenReturn(true);
        service.publish(ORG, 2);
        verify(pages).audit(eq(ORG), eq("PUBLISHED"), eq("user_1"), eq(2L), any());
    }

    @Test
    void publicReadNeedsPublishedAndApproved() {
        when(pages.find(ORG)).thenReturn(Optional.of(row(2, null, null)));
        assertThat(service.publishedFor(ORG)).isEmpty();
        when(pages.find(ORG)).thenReturn(Optional.of(row(2, "{\"a\":1}", 2L)));
        assertThat(service.publishedFor(ORG)).isPresent();
        approved(OrgStatus.SUSPENDED);
        assertThat(service.publishedFor(ORG)).isEmpty();
    }
}
