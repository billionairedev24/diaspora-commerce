package com.afrotransact.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.TourProgress;
import com.afrotransact.identity.domain.TourProgress.Status;
import com.afrotransact.identity.domain.TourProgressRules;
import com.afrotransact.identity.port.TourMetrics;
import com.afrotransact.identity.port.TourProgressRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;

class TourProgressServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final FakeRepository repo = new FakeRepository();
    private final List<String> events = new ArrayList<>();
    private final TourMetrics metrics = events::add;

    private TourProgressServiceImpl as(UUID user, boolean autoStart) {
        return new TourProgressServiceImpl(
                new CurrentUser(() -> new Actor(user.toString(), PrincipalType.SELLER_MEMBER, Optional.empty(), Set.of(),
                        Set.of())), repo, metrics, Clock.fixed(NOW, ZoneOffset.UTC), autoStart);
    }

    private static SaveTourProgressCommand cmd(String id, int version, Status status, String step) {
        return new SaveTourProgressCommand(id, version, status, step);
    }

    @Test
    void anAccountWithNoProgressHasNoEntriesAndTheAutoStartFlagIsReported() {
        assertThat(as(me, true).snapshot().items()).isEmpty();
        assertThat(as(me, true).snapshot().autoStartEnabled()).isTrue();
        assertThat(as(me, false).snapshot().autoStartEnabled()).isFalse();
    }

    @Test
    void savingIsAnIdempotentUpsertStampedByTheServerClock() {
        TourProgressServiceImpl service = as(me, true);
        TourProgressView first = service.save(cmd("sc.orders", 1, Status.COMPLETED, "tools"));
        TourProgressView again = service.save(cmd("sc.orders", 1, Status.COMPLETED, "tools"));
        assertThat(again).isEqualTo(first);
        assertThat(first.updatedAt()).isEqualTo(NOW);
        assertThat(service.snapshot().items()).containsExactly(first);
        assertThat(events).containsExactly("completed");
    }

    @Test
    void aReplayInProgressNeverUndoesACompletedOrDismissedTourOfTheSameVersion() {
        TourProgressServiceImpl service = as(me, true);
        service.save(cmd("sc.orders", 2, Status.DISMISSED, "table"));
        TourProgressView kept = service.save(cmd("sc.orders", 2, Status.IN_PROGRESS, "page"));
        assertThat(kept.status()).isEqualTo(Status.DISMISSED);
        assertThat(kept.lastStep()).isEqualTo("table");
        TourProgressView done = service.save(cmd("sc.orders", 2, Status.COMPLETED, "tools"));
        assertThat(done.status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void aNewerContentVersionStartsOverAndAnOlderOneIsIgnored() {
        TourProgressServiceImpl service = as(me, true);
        service.save(cmd("sc.orders", 1, Status.COMPLETED, null));
        assertThat(service.save(cmd("sc.orders", 2, Status.IN_PROGRESS, "page")).contentVersion()).isEqualTo(2);
        TourProgressView ignored = service.save(cmd("sc.orders", 1, Status.COMPLETED, null));
        assertThat(ignored.contentVersion()).isEqualTo(2);
        assertThat(ignored.status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(events).containsExactly("completed", "started");
    }

    @Test
    void peopleOnlySeeAndChangeTheirOwnProgress() {
        as(me, true).save(cmd("sc.orders", 1, Status.COMPLETED, null));
        as(other, true).save(cmd("sc.orders", 1, Status.DISMISSED, null));
        assertThat(as(me, true).snapshot().items()).extracting(TourProgressView::status).containsExactly(Status.COMPLETED);
        as(other, true).resetAll();
        assertThat(as(other, true).snapshot().items()).isEmpty();
        assertThat(as(me, true).snapshot().items()).hasSize(1);
    }

    @Test
    void mergeCopiesWhatTheBrowserKeptWithoutOverwritingNewerServerState() {
        TourProgressServiceImpl service = as(me, true);
        service.save(cmd("sc.orders", 2, Status.COMPLETED, null));
        TourProgressSnapshot merged = service.merge(List.of(
                cmd("sc.orders", 1, Status.DISMISSED, null),
                cmd("sc.payouts", 1, Status.COMPLETED, null),
                cmd("st.settings", 1, Status.DISMISSED, null)));
        assertThat(merged.items()).extracting(TourProgressView::tourId).containsExactly("sc.orders", "sc.payouts",
                "st.settings");
        assertThat(merged.items().get(0).contentVersion()).isEqualTo(2);
        assertThat(merged.items().get(0).status()).isEqualTo(Status.COMPLETED);
        // the same version already on the server stands, even when the browser says something else
        assertThat(service.merge(List.of(cmd("sc.payouts", 1, Status.DISMISSED, null))).items().get(1).status())
                .isEqualTo(Status.COMPLETED);
        // merging again changes nothing
        assertThat(service.merge(List.of(cmd("sc.payouts", 1, Status.COMPLETED, null))).items()).hasSize(3);
    }

    @Test
    void badIdsVersionsAndStatusesAreRejectedWithFieldErrorsAndNothingIsSaved() {
        TourProgressServiceImpl service = as(me, true);
        assertThatThrownBy(() -> service.save(cmd("Not A Tour", 0, null, "BAD STEP")))
                .isInstanceOfSatisfying(IdentityRuleViolation.class, ex -> {
                    assertThat(ex.code()).isEqualTo("identity.invalid-tour-progress");
                    assertThat(ex.fieldErrors()).containsKeys("tourId", "contentVersion", "status", "lastStep");
                });
        assertThatThrownBy(() -> service.merge(List.of(cmd("sc.ok", 1, Status.COMPLETED, null),
                cmd("bad id", 1, Status.COMPLETED, null)))).isInstanceOf(IdentityRuleViolation.class);
        assertThat(service.snapshot().items()).isEmpty();
        assertThatThrownBy(() -> service.reset("../x")).isInstanceOf(IdentityRuleViolation.class);
    }

    @Test
    void aPersonCannotKeepMoreThanTheLimitOfTours() {
        TourProgressServiceImpl service = as(me, true);
        for (int i = 0; i < TourProgressRules.MAX_TOURS; i++) {
            service.save(cmd("sc.tour" + i, 1, Status.COMPLETED, null));
        }
        assertThatThrownBy(() -> service.save(cmd("sc.one-too-many", 1, Status.COMPLETED, null)))
                .isInstanceOfSatisfying(IdentityRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("identity.tour-progress-limit"));
        // an existing tour can still change
        assertThat(service.save(cmd("sc.tour0", 2, Status.IN_PROGRESS, null)).contentVersion()).isEqualTo(2);
    }

    @Test
    void mergeIsBoundedInSize() {
        List<SaveTourProgressCommand> many = new ArrayList<>();
        for (int i = 0; i <= TourProgressRules.MAX_IMPORT; i++) {
            many.add(cmd("sc.t" + i, 1, Status.COMPLETED, null));
        }
        assertThatThrownBy(() -> as(me, true).merge(many)).isInstanceOf(IdentityRuleViolation.class);
    }

    @Test
    void resettingOneOrAllIsIdempotentAndCountedOnlyWhenSomethingWasRemoved() {
        TourProgressServiceImpl service = as(me, true);
        service.save(cmd("sc.orders", 1, Status.COMPLETED, null));
        service.save(cmd("sc.payouts", 1, Status.COMPLETED, null));
        events.clear();
        service.reset("sc.orders");
        service.reset("sc.orders");
        assertThat(service.snapshot().items()).extracting(TourProgressView::tourId).containsExactly("sc.payouts");
        service.resetAll();
        service.resetAll();
        assertThat(service.snapshot().items()).isEmpty();
        assertThat(events).containsExactly("reset", "reset");
    }

    @Test
    void metricsCarryNoTourOrUserIdentity() {
        as(me, true).save(cmd("sc.orders", 1, Status.IN_PROGRESS, "page"));
        as(me, true).save(cmd("sc.orders", 1, Status.COMPLETED, "tools"));
        assertThat(events).containsExactly("started", "completed");
    }

    /** In-memory port, keyed like the table (user, tour). */
    private static final class FakeRepository implements TourProgressRepository {

        private final Map<String, TourProgress> rows = new LinkedHashMap<>();

        private static String key(UUID user, String tour) {
            return user + "/" + tour;
        }

        @Override
        public List<TourProgress> findAll(UUID userId) {
            return rows.values().stream().filter(p -> p.userId().equals(userId))
                    .sorted((a, b) -> a.tourId().compareTo(b.tourId())).toList();
        }

        @Override
        public Optional<TourProgress> find(UUID userId, String tourId) {
            return Optional.ofNullable(rows.get(key(userId, tourId)));
        }

        @Override
        public long count(UUID userId) {
            return findAll(userId).size();
        }

        @Override
        public void upsert(TourProgress progress) {
            rows.put(key(progress.userId(), progress.tourId()), progress);
        }

        @Override
        public boolean delete(UUID userId, String tourId) {
            return rows.remove(key(userId, tourId)) != null;
        }

        @Override
        public int deleteAll(UUID userId) {
            List<TourProgress> mine = findAll(userId);
            mine.forEach(p -> rows.remove(key(p.userId(), p.tourId())));
            return mine.size();
        }
    }
}
