package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.OnboardingStep;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.PhoneNumbers;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.CallerProfile;
import com.afrotransact.seller.port.CallerProfileProvider;
import com.afrotransact.seller.port.LaunchListRepository;
import com.afrotransact.seller.port.OrganisationRepository;
import org.junit.jupiter.api.Test;

/**
 * Services are the one separate application: a seller whose account is approved applies to offer services on the same
 * sign-in (one application per kind), but not while another application is still being prepared or reviewed.
 */
class OnboardingStartPerKindTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final String USER = "user_1";

    private final ApplicationRepository apps = mock(ApplicationRepository.class);
    private final OrganisationRepository orgs = mock(OrganisationRepository.class);
    private final AccessGuard access = mock(AccessGuard.class);
    private final CallerProfileProvider profiles = mock(CallerProfileProvider.class);
    private final OnboardingServiceImpl service = new OnboardingServiceImpl(apps, orgs,
            mock(LaunchListRepository.class), null, mock(ApplicationViews.class), null, profiles,
            mock(PhoneNumbers.class), access, mock(DomainEventPublisher.class), null,
            Clock.fixed(NOW, ZoneOffset.UTC), mock(ReviewAssignmentService.class));

    OnboardingStartPerKindTest() {
        when(access.applicant()).thenReturn(new Actor(USER, PrincipalType.CUSTOMER, Optional.empty(), Set.of(),
                Set.of()));
        when(profiles.current()).thenReturn(CallerProfile.empty());
        when(orgs.insert(any())).thenAnswer(call -> call.getArgument(0));
        when(apps.insert(any())).thenReturn(true);
        when(apps.nextReference()).thenReturn("APP-1");
    }

    private static Application application(OrgKind kind, ApplicationStatus status) {
        return new Application("app_" + kind, "APP-0", "org_" + kind, USER, kind, status, OnboardingStep.values()[0],
                null, Set.of(), null, null, null, null, List.of(), List.of(), null, 0, null, NOW, NOW, 0);
    }

    @Test
    void anApprovedSellerCanApplyForServicesOnTheSameAccount() {
        when(apps.findByApplicant(USER)).thenReturn(Optional.of(application(OrgKind.SELLER, ApplicationStatus.APPROVED)));
        service.start(OrgKind.PROVIDER);
        verify(apps).insert(any());
    }

    @Test
    void anotherKindStillInProgressMustBeFinishedFirst() {
        when(apps.findByApplicant(USER)).thenReturn(Optional.of(application(OrgKind.SELLER, ApplicationStatus.DRAFT)));
        assertThatThrownBy(() -> service.start(OrgKind.PROVIDER)).isInstanceOfSatisfying(SellerStateConflict.class,
                e -> assertThat(e.code()).isEqualTo("seller.application-exists"));
        verify(apps, never()).insert(any());
    }
}
