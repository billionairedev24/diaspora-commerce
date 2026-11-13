package com.afrotransact.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.home.HomeCaps;
import com.afrotransact.content.domain.home.HomeSettings;
import com.afrotransact.content.domain.home.ModuleRule;
import com.afrotransact.content.port.HomeSettingsRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HomeSettingsServiceTest {

    private final List<HomeSettingsRepository.Stored> rows = new ArrayList<>();
    private ContentAccess access;
    private AuditTrail audit;
    private HomeSettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        HomeSettingsRepository repo = new HomeSettingsRepository() {
            @Override
            public Optional<Stored> latest() {
                return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(rows.size() - 1));
            }

            @Override
            public Optional<Stored> find(int version) {
                return rows.stream().filter(s -> s.settings().version() == version).findFirst();
            }

            @Override
            public List<Stored> history() {
                List<Stored> out = new ArrayList<>(rows);
                Collections.reverse(out);
                return out;
            }

            @Override
            public boolean insert(Stored version) {
                return rows.add(version);
            }
        };
        access = mock(ContentAccess.class);
        Actor actor = mock(Actor.class);
        when(actor.subject()).thenReturn("stf-30");
        when(access.require(ContentPermissions.HOME_EDIT)).thenReturn(actor);
        audit = mock(AuditTrail.class);
        service = new HomeSettingsServiceImpl(repo, access, audit, Clock.fixed(Instant.parse("2026-11-13T10:00:00Z"), ZoneOffset.UTC));
    }

    private HomeSettings with(int minUnits, int share) {
        Map<String, ModuleRule> modules = new HashMap<>(HomeSettings.defaultModules());
        modules.put("communities", new ModuleRule(true, 0, 0, minUnits, 8, 2, 0, 40, 6, "discovery"));
        HomeCaps c = HomeCaps.defaults();
        return new HomeSettings(0, null, modules, new HomeCaps(c.maxModules(), c.minModules(), share, c.sponsoredPerOrganic(),
                c.poolMultiplier(), c.noveltyLoads(), c.noveltyPenalty(), c.visitWindowMinutes(), c.hysteresis(),
                c.dealMinPercent(), c.navBarSize()), Map.of(), null, null);
    }

    @Test
    void defaultsLoadWhenNoRowExists() {
        HomeSettings s = service.current();
        assertThat(s.version()).isZero();
        assertThat(s.modules().get("communities").unitMinProducts()).isEqualTo(8);
    }

    @Test
    void aReasonNeedsAtLeastTenCharactersToSaveOrRevert() {
        assertThatThrownBy(() -> service.save(0, with(4, 2), "too short"))
                .isInstanceOfSatisfying(ContentRuleViolation.class, e -> {
                    assertThat(e.fieldErrors().get("reason")).isEqualTo("Say why you are changing this (10 to 300 characters)");
                });
        assertThatThrownBy(() -> service.save(0, with(4, 2), "   padded   "))
                .as("spaces around a nine character reason do not count")
                .isInstanceOf(ContentRuleViolation.class);
        assertThatThrownBy(() -> service.revert(1, "nine char"))
                .isInstanceOf(ContentRuleViolation.class);
        assertThat(rows).isEmpty();
        assertThat(service.save(0, with(4, 2), "ten chars!").version()).isEqualTo(1);
    }

    @Test
    void aReasonIsMandatoryAndInvalidValuesAreRefusedWithFields() {
        assertThatThrownBy(() -> service.save(0, with(4, 2), " "))
                .isInstanceOfSatisfying(ContentRuleViolation.class, e -> assertThat(e.fieldErrors()).containsKey("reason"));
        HomeSettings bad = with(-1, 2);
        assertThatThrownBy(() -> service.save(0, bad, "Tighten the community bar"))
                .isInstanceOfSatisfying(ContentRuleViolation.class,
                        e -> assertThat(e.fieldErrors()).containsKey("modules.communities.minUnits"));
        assertThat(rows).isEmpty();
    }

    @Test
    void everyChangeIsAnAuditedVersionWithWhoWhyAndWhatChanged() {
        HomeSettings saved = service.save(0, with(4, 2), "Wait for a fourth community");
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.updatedBy()).isEqualTo("stf-30");
        assertThat(rows.get(0).reason()).isEqualTo("Wait for a fourth community");
        assertThat(rows.get(0).changes()).anySatisfy(c -> assertThat(c.get("field")).isEqualTo("modules.communities.minUnits"));
        verify(audit).record(eq("stf-30"), eq("home-settings.updated"), eq("home-settings"), eq("1"), any());
        assertThat(service.current().modules().get("communities").minUnits()).isEqualTo(4);
    }

    @Test
    void aStaleExpectedVersionIsAConflictAndNothingIsSaved() {
        service.save(0, with(4, 2), "First version here");
        assertThatThrownBy(() -> service.save(0, with(5, 2), "Second version here")).isInstanceOf(ConflictException.class);
        assertThat(rows).hasSize(1);
    }

    @Test
    void revertRestoresAnEarlierVersionAsANewOneWithAReason() {
        service.save(0, with(4, 2), "Tighter community bar");
        service.save(1, with(4, 3), "More share per seller");
        HomeSettings back = service.revert(1, "share was too generous");
        assertThat(back.version()).isEqualTo(3);
        assertThat(back.caps().sellerShare()).isEqualTo(2);
        assertThat(rows.get(2).revertOf()).isEqualTo(1);
        assertThatThrownBy(() -> service.revert(1, "")).isInstanceOf(ContentRuleViolation.class);
    }

    @Test
    void aSaveThatChangesNothingCreatesNoVersion() {
        HomeSettings same = service.save(0, HomeSettings.defaults(), "No change at all");
        assertThat(same.version()).isZero();
        assertThat(rows).isEmpty();
    }
}
