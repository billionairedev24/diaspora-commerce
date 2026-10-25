package com.afrotransact.deliveryruns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.deliveryruns.domain.CostModel;
import com.afrotransact.deliveryruns.domain.DefaultRunEconomicsCalculator;
import com.afrotransact.deliveryruns.domain.DropMode;
import com.afrotransact.deliveryruns.domain.EconomicsChange;
import com.afrotransact.deliveryruns.domain.EconomicsRevision;
import com.afrotransact.deliveryruns.domain.EconomicsSettings;
import com.afrotransact.deliveryruns.domain.RouteEstimate;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.EconomicsSettingsRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** S265: staff edit the driver cost model with a reason; every version is kept; the calculator follows at once. */
class EconomicsSettingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final Actor STAFF = new Actor("staff-9", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("*"));
    private static final EconomicsSettings DEFAULTS = new EconomicsSettings(2500, 70, 45, 6, 30, 1, true);

    /** In-memory settings row and audit trail. */
    private static final class Memory implements EconomicsSettingsRepository {

        EconomicsRevision row;
        final List<EconomicsChange> log = new ArrayList<>();

        @Override
        public Optional<EconomicsRevision> find() {
            return Optional.ofNullable(row);
        }

        @Override
        public Optional<EconomicsRevision> lock() {
            return find();
        }

        @Override
        public void insert(EconomicsRevision first) {
            if (row != null) {
                throw new ConcurrentUpdateException("changed");
            }
            row = first;
        }

        @Override
        public void update(EconomicsRevision next) {
            if (row == null || row.version() != next.version() - 1) {
                throw new ConcurrentUpdateException("changed");
            }
            row = next;
        }

        @Override
        public void log(EconomicsChange change) {
            log.add(0, change);
        }

        @Override
        public List<EconomicsChange> changes(int limit) {
            return log.stream().limit(limit).toList();
        }
    }

    private Memory memory;
    private PermissionChecker permissions;
    private EconomicsBook book;
    private EconomicsSettingsServiceImpl service;

    @BeforeEach
    void setUp() {
        memory = new Memory();
        permissions = mock(PermissionChecker.class);
        when(permissions.require(any())).thenReturn(STAFF);
        book = new EconomicsBook(memory, DEFAULTS);
        service = new EconomicsSettingsServiceImpl(memory, book, permissions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static EconomicsSettingsCommand command(long perHour, long perMile, String reason) {
        return new EconomicsSettingsCommand(perHour, perMile, 45.0, 6.0, 30.0, 1.0, true, reason);
    }

    @Test
    void untilStaffSaveTheEnvironmentDefaultsApplyAsVersionZero() {
        EconomicsRevision current = service.current();
        assertThat(current.version()).isZero();
        assertThat(current.saved()).isFalse();
        assertThat(current.settings()).isEqualTo(DEFAULTS);
        assertThat(current.updatedBy()).isNull();
        assertThat(service.changes(10)).isEmpty();
    }

    @Test
    void theFirstSaveIsVersionOneAndLogsTheDefaultsAsTheBefore() {
        EconomicsRevision saved = service.replace(command(3000, 80, "Austin driver rate went up"), 0L);
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.saved()).isTrue();
        assertThat(saved.updatedBy()).isEqualTo("staff-9");
        assertThat(saved.updatedAt()).isEqualTo(NOW);
        assertThat(saved.settings().driverCostPerHourMinor()).isEqualTo(3000);
        assertThat(service.changes(10)).singleElement().satisfies(c -> {
            assertThat(c.before()).isEqualTo(DEFAULTS);
            assertThat(c.after()).isEqualTo(saved.settings());
            assertThat(c.actor()).isEqualTo("staff-9");
            assertThat(c.reason()).isEqualTo("Austin driver rate went up");
            assertThat(c.version()).isEqualTo(1);
        });
    }

    @Test
    void everyChangeIsANewVersionWithTheNumbersBeforeAndAfter() {
        EconomicsRevision v1 = service.replace(command(3000, 80, "First"), 0L);
        EconomicsRevision v2 = service.replace(command(3200, 80, "  Second  "), 1L);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.reason()).isEqualTo("Second");
        List<EconomicsChange> changes = service.changes(10);
        assertThat(changes).extracting(EconomicsChange::version).containsExactly(2L, 1L);
        assertThat(changes.get(0).before()).isEqualTo(v1.settings());
        assertThat(changes.get(0).after()).isEqualTo(v2.settings());
        assertThat(service.current().settings().driverCostPerHourMinor()).isEqualTo(3200);
    }

    @Test
    void aSaveThatChangesNothingMakesNoNewVersion() {
        EconomicsRevision v1 = service.replace(command(3000, 80, "First"), 0L);
        EconomicsRevision again = service.replace(command(3000, 80, "Looked again"), 1L);
        assertThat(again).isEqualTo(v1);
        assertThat(service.changes(10)).hasSize(1);
    }

    @Test
    void aReasonIsRequiredAndLimited() {
        assertThatThrownBy(() -> service.replace(command(3000, 80, "  "), 0L)).isInstanceOfSatisfying(
                RuleViolation.class, e -> assertThat(e.code()).isEqualTo("delivery-runs.reason-required"));
        assertThatThrownBy(() -> service.replace(command(3000, 80, null), 0L)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> service.replace(command(3000, 80, "x".repeat(501)), 0L)).isInstanceOfSatisfying(
                RuleViolation.class, e -> assertThat(e.code()).isEqualTo("delivery-runs.invalid-reason"));
        assertThat(memory.row).isNull();
    }

    @Test
    void numbersOutOfRangeAreRefusedWithAClearCode() {
        assertThatThrownBy(() -> service.replace(command(-1, 80, "Typo"), 0L)).isInstanceOfSatisfying(
                RuleViolation.class, e -> assertThat(e.code()).isEqualTo("delivery-runs.invalid-economics"));
        assertThatThrownBy(() -> service.replace(command(100_001, 80, "Typo"), 0L)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> service.replace(command(3000, 10_001, "Typo"), 0L)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> service.replace(new EconomicsSettingsCommand(3000L, 80L, 1_441.0, 6.0, 30.0, 1.0,
                true, "Typo"), 0L)).isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> service.replace(new EconomicsSettingsCommand(3000L, 80L, 45.0, 6.0, 30.0, 1.0,
                null, "No answer"), 0L)).isInstanceOf(RuleViolation.class);
        assertThat(memory.row).isNull();
    }

    @Test
    void aStaleVersionIsRefusedAndTellsWhatIsCurrent() {
        service.replace(command(3000, 80, "First"), 0L);
        assertThatThrownBy(() -> service.replace(command(3100, 80, "Stale"), 0L))
                .isInstanceOfSatisfying(VersionMismatchException.class, e -> assertThat(e.current()).isEqualTo(1));
        assertThat(service.replace(command(3100, 80, "Any version"), null).version()).isEqualTo(2);
    }

    @Test
    void changingNeedsPermissionAndReadingDoesNot() {
        when(permissions.require(DeliveryRunsPermissions.PREORDERS_MANAGE)).thenThrow(
                new ForbiddenActionException("forbidden", "Needs preorders:manage"));
        assertThatThrownBy(() -> service.replace(command(3000, 80, "No"), 0L))
                .isInstanceOf(ForbiddenActionException.class);
        assertThat(service.current().version()).isZero();
    }

    @Test
    void theCalculatorFollowsWhatStaffSavedAtOnce() {
        DefaultRunEconomicsCalculator calculator = new DefaultRunEconomicsCalculator(book);
        RouteEstimate bastrop = new RouteEstimate(32, 40, "ROAD_OSRM");
        CostModel before = calculator.costModel(bastrop, DropMode.DOORSTEP);
        service.replace(new EconomicsSettingsCommand(5000L, 70L, 45.0, 6.0, 30.0, 1.0, true, "Overtime rate"), 0L);
        CostModel after = calculator.costModel(bastrop, DropMode.DOORSTEP);
        assertThat(after.fixedCostMinor()).isGreaterThan(before.fixedCostMinor());
        assertThat(after.perOrderCostMinor()).isEqualTo(500.0);
        assertThat(before.perOrderCostMinor()).isEqualTo(250.0);
    }
}
