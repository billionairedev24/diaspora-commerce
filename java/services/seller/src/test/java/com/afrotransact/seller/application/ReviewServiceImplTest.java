package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationAlreadyDecided;
import com.afrotransact.seller.domain.ApplicationData;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.ReasonCode;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.MediaClient;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.ReasonCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/** Review decisions are idempotent: a repeat answers the current state and changes nothing. */
class ReviewServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-12T14:03:00Z");

    private final ApplicationRepository apps = mock(ApplicationRepository.class);
    private final OrganisationRepository orgs = mock(OrganisationRepository.class);
    private final ReasonCodeRepository reasons = mock(ReasonCodeRepository.class);
    private final ApplicationViews views = mock(ApplicationViews.class);
    private final BillingService billing = mock(BillingService.class);
    private final AccessGuard access = mock(AccessGuard.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final ReviewServiceImpl service = new ReviewServiceImpl(apps, orgs, reasons, mock(MediaClient.class),
            views, billing, access, events, TransactionOperations.withoutTransaction(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final Application approved = Application.start("app_1", "APP-30200", "org_1", "applicant",
                    OrgKind.SELLER, ApplicationData.empty(), Set.of(), NOW).submit(NOW)
            .review(ReviewAction.APPROVE, "reviewer", "ALL_CHECKS_PASSED", null, null, NOW)
            .decide(ReviewAction.FINAL_APPROVE, "approver", "ALL_CHECKS_PASSED", NOW);
    private final TimelineEntry finalApproval = new TimelineEntry(UUID.randomUUID(), "app_1",
            ReviewAction.FINAL_APPROVE, ApplicationStatus.AWAITING_FINAL_APPROVAL, ApplicationStatus.APPROVED,
            "approver", "ALL_CHECKS_PASSED", null, null, NOW, "Ada Approver", "key-first-attempt");

    @BeforeEach
    void setUp() {
        when(reasons.find("ALL_CHECKS_PASSED")).thenReturn(Optional.of(mock(ReasonCode.class)));
        when(apps.lockById("app_1")).thenReturn(Optional.of(approved));
        when(apps.timeline("app_1")).thenReturn(List.of(finalApproval));
        when(views.view(any(), any(), any(), any())).thenReturn(mock(ApplicationView.class));
    }

    private void as(String subject, String... permissions) {
        Actor actor = new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of(permissions));
        when(access.staff(anyString())).thenReturn(actor);
    }

    private static ReviewCommand finalApprove(String key) {
        return new ReviewCommand(ReviewAction.FINAL_APPROVE, "ALL_CHECKS_PASSED", null, null, null, key, null);
    }

    @Test
    void aRetryWithTheSameIdempotencyKeyAnswersTheCurrentStateWithoutEventsOrActivation() {
        as("approver", SellerPermissions.APPLICATIONS_APPROVE);
        when(apps.timelineEntry("app_1", "key-first-attempt")).thenReturn(Optional.of(finalApproval));

        assertThat(service.decide("app_1", finalApprove("key-first-attempt"))).isNotNull();

        verify(apps, never()).update(any());
        verify(apps, never()).appendTimeline(any());
        verify(events, never()).publish(anyString(), anyString(), anyString(), any());
        verify(billing, never()).activate(anyString());
    }

    @Test
    void theSameApproverRepeatingFinalApprovalWithoutAKeyIsAlsoAReplay() {
        as("approver", SellerPermissions.APPLICATIONS_APPROVE);
        when(apps.timelineEntry(anyString(), anyString())).thenReturn(Optional.empty());

        service.decide("app_1", finalApprove("a-new-key-from-a-second-click"));
        service.decide("app_1", finalApprove(null));

        verify(apps, never()).update(any());
        verify(events, never()).publish(anyString(), anyString(), anyString(), any());
        verify(billing, never()).activate(anyString());
    }

    @Test
    void anotherApproverIsToldWhoApprovedItAndWhen() {
        as("someone-else", SellerPermissions.APPLICATIONS_APPROVE);
        when(apps.timelineEntry(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide("app_1", finalApprove("k-other")))
                .isInstanceOfSatisfying(ApplicationAlreadyDecided.class, e -> {
                    assertThat(e.code()).isEqualTo("seller.already-decided");
                    assertThat(e.getMessage()).isEqualTo(
                            "This application is already approved (by Ada Approver, 12 Oct 2026, 14:03 UTC)");
                });
        verify(events, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aKeyReusedForADifferentDecisionIsRefused() {
        as("approver", SellerPermissions.APPLICATIONS_APPROVE);
        when(apps.timelineEntry("app_1", "key-first-attempt")).thenReturn(Optional.of(finalApproval));

        assertThatThrownBy(() -> service.decide("app_1", new ReviewCommand(ReviewAction.REJECT, "ALL_CHECKS_PASSED",
                null, null, null, "key-first-attempt", null)))
                .isInstanceOfSatisfying(SellerStateConflict.class,
                        e -> assertThat(e.code()).isEqualTo("seller.idempotency-key-reused"));
    }

    @Test
    void stepOneOnAnApprovedApplicationNamesTheFinalApprover() {
        as("reviewer", SellerPermissions.APPLICATIONS_REVIEW);
        when(apps.timelineEntry(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review("app_1", new ReviewCommand(ReviewAction.APPROVE, "ALL_CHECKS_PASSED",
                null, null, null))).hasMessageStartingWith("This application is already approved (by Ada Approver");
        verify(apps, never()).appendTimeline(any());
    }
}
