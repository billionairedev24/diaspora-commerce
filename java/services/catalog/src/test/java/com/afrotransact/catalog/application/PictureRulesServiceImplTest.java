package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.pictures.PictureOverrides;
import com.afrotransact.catalog.domain.pictures.PictureProfile;
import com.afrotransact.catalog.domain.pictures.PictureRules;
import com.afrotransact.catalog.port.PictureRulesRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PictureRulesServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private final PictureRulesRepository repository = mock(PictureRulesRepository.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final Actor staff = new Actor("user_staff", PrincipalType.STAFF, Optional.empty(), Set.of(),
            Set.of(CatalogPermissions.PICTURE_RULES_MANAGE));
    private PictureRulesServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PictureRulesServiceImpl(repository, PictureRules.defaults(PictureProfile.STARTUP), permissions,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.find()).thenReturn(Optional.empty());
        when(repository.history(any(Integer.class))).thenReturn(List.of());
    }

    @Test
    void withNothingSavedTheEnvironmentDefaultIsInForce() {
        PictureRules r = service.current();

        assertThat(r.profile()).isEqualTo(PictureProfile.STARTUP);
        assertThat(r.version()).isZero();
        assertThat(r.updatedBy()).isNull();
    }

    @Test
    void theSavedCopyWinsOverTheEnvironmentDefault() {
        when(repository.find()).thenReturn(Optional.of(PictureRules.strict().savedBy("user_a", NOW, 4)));

        assertThat(service.current().profile()).isEqualTo(PictureProfile.STRICT);
        assertThat(service.current().version()).isEqualTo(4);
    }

    @Test
    void currentIsCachedBrieflyAndDroppedWhenStaffSave() {
        service.current();
        service.current();
        verify(repository, times(1)).find();

        when(permissions.require(CatalogPermissions.PICTURE_RULES_MANAGE)).thenReturn(staff);
        when(repository.save(any(), anyLong(), anyString())).thenReturn(PictureRules.strict().savedBy("user_staff", NOW, 1));
        service.update(PictureProfile.STRICT, PictureOverrides.none(), "Product asked for the standard", 0);
        service.current();

        verify(repository, times(2)).find();
    }

    @Test
    void savingNeedsTheManagePermission() {
        when(permissions.require(CatalogPermissions.PICTURE_RULES_MANAGE)).thenThrow(new ForbiddenActionException("forbidden", "no"));

        assertThatThrownBy(() -> service.update(PictureProfile.TESTING, PictureOverrides.none(), "unblock testing", 0))
                .isInstanceOf(ForbiddenActionException.class);
        verify(repository, never()).save(any(), anyLong(), anyString());
    }

    @Test
    void aSaveNeedsAReasonAndValidKnobs() {
        when(permissions.require(CatalogPermissions.PICTURE_RULES_MANAGE)).thenReturn(staff);

        assertThatThrownBy(() -> service.update(PictureProfile.STARTUP, PictureOverrides.none(), " ", 0))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKey("reason"));
        assertThatThrownBy(() -> service.update(PictureProfile.STARTUP,
                new PictureOverrides(11, null, null, 99, null, null, null, null, null, null), "valid reason", 0))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKeys("minImages", "maxMegabytes"));
        verify(repository, never()).save(any(), anyLong(), anyString());
    }

    @Test
    void theSaveCarriesWhoWhenAndWhy() {
        when(permissions.require(CatalogPermissions.PICTURE_RULES_MANAGE)).thenReturn(staff);
        when(repository.save(any(), eq(2L), eq("Product wants the Amazon rules for the beta"))).thenAnswer(i -> i.getArgument(0));

        PictureRules saved = service.update(PictureProfile.STRICT, PictureOverrides.none(),
                "  Product wants the Amazon rules for the beta ", 2);

        assertThat(saved.updatedBy()).isEqualTo("user_staff");
        assertThat(saved.updatedAt()).isEqualTo(NOW);
        assertThat(saved.version()).isEqualTo(3);
    }

    @Test
    void aStaleVersionIsAConflict() {
        when(permissions.require(CatalogPermissions.PICTURE_RULES_MANAGE)).thenReturn(staff);
        when(repository.save(any(), anyLong(), anyString())).thenThrow(new StaleVersionException("old"));

        assertThatThrownBy(() -> service.update(PictureProfile.TESTING, PictureOverrides.none(), "unblock testing", 1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void viewIsForModeratorsAndTellsWhetherTheCallerMayChange() {
        when(permissions.require(CatalogPermissions.PICTURE_RULES_MANAGE)).thenThrow(new ForbiddenActionException("forbidden", "no"));
        when(permissions.require(CatalogPermissions.MODERATE)).thenReturn(staff);

        PictureRulesService.View view = service.view();

        assertThat(view.canChange()).isFalse();
        assertThat(view.defaults()).hasSize(3);
        assertThat(view.environmentDefault()).isEqualTo(PictureProfile.STARTUP);
    }

    @Test
    void viewIsRefusedWithoutAnyPermission() {
        when(permissions.require(anyString())).thenThrow(new ForbiddenActionException("forbidden", "no"));

        assertThatThrownBy(service::view).isInstanceOf(ForbiddenActionException.class);
    }
}
