package com.afrotransact.deliveryruns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.deliveryruns.domain.DeliveryFeeChange;
import com.afrotransact.deliveryruns.domain.DeliveryFeeRule;
import com.afrotransact.deliveryruns.domain.DeliveryFees;
import com.afrotransact.deliveryruns.domain.FeeBand;
import com.afrotransact.deliveryruns.domain.FeeMethod;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.FeeSource;
import com.afrotransact.deliveryruns.domain.RuleViolation;
import com.afrotransact.deliveryruns.domain.WindowDistances;
import com.afrotransact.deliveryruns.port.ConcurrentUpdateException;
import com.afrotransact.deliveryruns.port.DeliveryFeeRuleRepository;
import com.afrotransact.deliveryruns.port.PreorderWindowRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Staff change delivery fees with a reason; every version is kept; resolving never needs the HTTP layer. */
class DeliveryFeeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final Actor STAFF = new Actor("staff-7", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("*"));

    /** In-memory rules and audit trail. */
    private static final class Memory implements DeliveryFeeRuleRepository {

        final List<DeliveryFeeRule> rules = new ArrayList<>();
        final List<DeliveryFeeChange> log = new ArrayList<>();

        @Override
        public List<DeliveryFeeRule> findAll() {
            return List.copyOf(rules);
        }

        @Override
        public Optional<DeliveryFeeRule> find(FeeMethod method, String currency) {
            return rules.stream().filter(r -> r.method() == method && r.currency().equals(currency)).findFirst();
        }

        @Override
        public Optional<DeliveryFeeRule> lock(FeeMethod method, String currency) {
            return find(method, currency);
        }

        @Override
        public void insert(DeliveryFeeRule rule) {
            rules.add(rule);
        }

        @Override
        public void update(DeliveryFeeRule next) {
            DeliveryFeeRule old = find(next.method(), next.currency()).orElseThrow();
            if (old.version() != next.version() - 1) {
                throw new ConcurrentUpdateException("changed");
            }
            rules.set(rules.indexOf(old), next);
        }

        @Override
        public void log(DeliveryFeeChange change) {
            log.add(change);
        }

        @Override
        public List<DeliveryFeeChange> changes(FeeMethod method, String currency, int limit) {
            return log.stream().filter(c -> method == null || c.method() == method)
                    .filter(c -> currency == null || c.currency().equals(currency))
                    .sorted(Comparator.comparing(DeliveryFeeChange::at).thenComparing(DeliveryFeeChange::version).reversed())
                    .limit(limit).toList();
        }
    }

    private Memory memory;
    private PermissionChecker permissions;
    private DeliveryFeeServiceImpl service;

    @BeforeEach
    void setUp() {
        memory = new Memory();
        permissions = mock(PermissionChecker.class);
        when(permissions.require(any())).thenReturn(STAFF);
        FeeBook book = mock(FeeBook.class);
        when(book.current()).thenAnswer(i -> new DeliveryFees(memory.findAll(), WindowDistances.UNKNOWN));
        service = new DeliveryFeeServiceImpl(memory, mock(PreorderWindowRepository.class), book, permissions,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static List<FeeBand> bands(long first, long second) {
        return List.of(new FeeBand(BigDecimal.ZERO, BigDecimal.TEN, first),
                new FeeBand(BigDecimal.TEN, BigDecimal.valueOf(50), second));
    }

    private DeliveryFeeRule seedDoor() {
        return service.replace(FeeMethod.AFROTRANSACT_DELIVERY, "USD",
                new FeeRuleCommand(bands(599, 799), null, null, null, null, "Launch rates"), 0L);
    }

    @Test
    void theFirstSaveCreatesVersionOneAndLogsWhoAndWhy() {
        DeliveryFeeRule r = seedDoor();
        assertThat(r.version()).isEqualTo(1);
        assertThat(r.updatedBy()).isEqualTo("staff-7");
        assertThat(r.reason()).isEqualTo("Launch rates");
        assertThat(memory.log).singleElement().satisfies(c -> {
            assertThat(c.before()).isNull();
            assertThat(c.after()).isEqualTo(r);
            assertThat(c.actor()).isEqualTo("staff-7");
            assertThat(c.reason()).isEqualTo("Launch rates");
            assertThat(c.at()).isEqualTo(NOW);
        });
    }

    @Test
    void everyChangeIsANewVersionWithTheRuleBeforeAndAfter() {
        DeliveryFeeRule v1 = seedDoor();
        DeliveryFeeRule v2 = service.replace(FeeMethod.AFROTRANSACT_DELIVERY, "usd",
                new FeeRuleCommand(bands(649, 849), null, null, null, null, "  Fuel went up  "), 1L);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.id()).isEqualTo(v1.id());
        assertThat(v2.reason()).as("trimmed").isEqualTo("Fuel went up");
        assertThat(memory.log).hasSize(2);
        DeliveryFeeChange change = service.changes(FeeMethod.AFROTRANSACT_DELIVERY, "USD", 10).getFirst();
        assertThat(change.version()).isEqualTo(2);
        assertThat(change.before().bands().getFirst().feeMinor()).isEqualTo(599);
        assertThat(change.after().bands().getFirst().feeMinor()).isEqualTo(649);
    }

    @Test
    void aSaveThatChangesNothingMakesNoNewVersion() {
        DeliveryFeeRule v1 = seedDoor();
        DeliveryFeeRule again = service.replace(FeeMethod.AFROTRANSACT_DELIVERY, "USD",
                new FeeRuleCommand(bands(599, 799), null, null, null, null, "Checked, no change"), null);
        assertThat(again).isEqualTo(v1);
        assertThat(memory.log).hasSize(1);
    }

    @Test
    void aReasonIsRequiredAndBoundedAndNothingIsWrittenWithout() {
        for (String reason : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.replace(FeeMethod.PICKUP, "USD",
                    new FeeRuleCommand(null, 0L, null, null, null, reason), null))
                    .isInstanceOf(RuleViolation.class)
                    .hasFieldOrPropertyWithValue("code", "delivery-runs.reason-required");
        }
        assertThatThrownBy(() -> service.replace(FeeMethod.PICKUP, "USD",
                new FeeRuleCommand(null, 0L, null, null, null, "x".repeat(501)), null))
                .isInstanceOf(RuleViolation.class);
        assertThat(memory.rules).isEmpty();
        assertThat(memory.log).isEmpty();
    }

    @Test
    void aStaleVersionIsRefusedAndNumbersThatAreNotARuleAreRefused() {
        seedDoor();
        assertThatThrownBy(() -> service.replace(FeeMethod.AFROTRANSACT_DELIVERY, "USD",
                new FeeRuleCommand(bands(1, 2), null, null, null, null, "Stale"), 0L))
                .isInstanceOf(VersionMismatchException.class)
                .satisfies(e -> assertThat(((VersionMismatchException) e).current()).isEqualTo(1));
        assertThatThrownBy(() -> service.replace(FeeMethod.AFROTRANSACT_DELIVERY, "USD",
                new FeeRuleCommand(List.of(new FeeBand(BigDecimal.ONE, BigDecimal.TEN, 599)), null, null, null, null,
                        "Gap at the start"), 1L)).isInstanceOf(RuleViolation.class);
        assertThat(memory.rules.getFirst().version()).isEqualTo(1);
    }

    @Test
    void changingNeedsPreordersManageAndReadingNeedsDeliveryRunsRead() {
        when(permissions.require("preorders:manage")).thenThrow(new ForbiddenActionException("forbidden", "no"));
        assertThatThrownBy(this::seedDoor).isInstanceOf(ForbiddenActionException.class);
        assertThat(memory.rules).isEmpty();
        verify(permissions, never()).require("delivery-runs:read");
        service.rules();
        verify(permissions).require("delivery-runs:read");
    }

    @Test
    void previewResolvesLikeCheckoutForEveryMethod() {
        seedDoor();
        service.replace(FeeMethod.PICKUP, "USD", new FeeRuleCommand(null, 150L, null, null, null, "Pickup"), 0L);
        service.replace(FeeMethod.CARRIER, "USD", new FeeRuleCommand(null, null, 100L, 700L, 5000L, "Carrier"), 0L);

        FeeSnapshot near = service.preview(new FeePreviewCommand(FeeMethod.AFROTRANSACT_DELIVERY, "USD",
                new BigDecimal("3"), null, null, null)).orElseThrow();
        assertThat(near.amountMinor()).isEqualTo(599);
        assertThat(near.band()).isEqualTo("0–10 mi");
        assertThat(service.preview(new FeePreviewCommand(FeeMethod.AFROTRANSACT_DELIVERY, "USD", new BigDecimal("75"),
                null, null, null))).as("beyond coverage").isEmpty();
        assertThat(service.preview(new FeePreviewCommand(FeeMethod.PICKUP, "usd", null, null, null, null))
                .orElseThrow().amountMinor()).isEqualTo(150);
        FeeSnapshot carrier = service.preview(new FeePreviewCommand(FeeMethod.CARRIER, "USD", null, null, 900L, 1000L))
                .orElseThrow();
        assertThat(carrier.amountMinor()).isEqualTo(1000);
        assertThat(carrier.source()).isEqualTo(FeeSource.CARRIER_QUOTE);
        assertThat(service.carrier("USD", null, 6000).orElseThrow().amountMinor()).as("free over $50").isZero();
        verify(permissions).require("delivery-runs:reserve");
        assertThatThrownBy(() -> service.preview(new FeePreviewCommand(null, "USD", null, null, null, null)))
                .isInstanceOf(RuleViolation.class);
        assertThatThrownBy(() -> service.preview(new FeePreviewCommand(FeeMethod.AFROTRANSACT_DELIVERY, "USD",
                new BigDecimal("-1"), null, null, null))).isInstanceOf(RuleViolation.class);
    }
}
