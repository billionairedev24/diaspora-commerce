package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.catalog.application.ImageSettings.Mode;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.images.ImageCheckResult;
import com.afrotransact.catalog.domain.images.ImageFinding;
import com.afrotransact.catalog.domain.images.ImageQuality;
import com.afrotransact.catalog.domain.images.ImageQualityRecord;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageQualityRepository;
import com.afrotransact.catalog.port.ImageQualityRepository.Candidate;
import com.afrotransact.catalog.port.ImageVerifier;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** S392: grandfathered listings are scored, shown as a task, and limited only after the grace period and only by flag. */
class ImageQualityServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final String SMALL = "This photo is too small. The main photo must be at least 1000 pixels on its "
            + "longest side so shoppers can zoom. Yours is 800 by 800.";

    /** The table in memory. */
    private static final class MemoryScores implements ImageQualityRepository {

        final Map<UUID, ImageQualityRecord> rows = new LinkedHashMap<>();
        final List<Candidate> waiting = new ArrayList<>();

        @Override
        public List<Candidate> candidates(Instant staleBefore, int limit) {
            return waiting.stream().filter(c -> !rows.containsKey(c.productId())
                    || rows.get(c.productId()).checkedAt().isBefore(staleBefore)
                    || !String.valueOf(c.mainImageUrl()).equals(rows.get(c.productId()).mainImageUrl())).limit(limit).toList();
        }

        @Override
        public long unscored() {
            return waiting.stream().filter(c -> !rows.containsKey(c.productId())).count();
        }

        @Override
        public Optional<ImageQualityRecord> find(UUID productId) {
            return Optional.ofNullable(rows.get(productId));
        }

        @Override
        public void save(ImageQualityRecord record) {
            rows.put(record.productId(), record);
        }

        @Override
        public void remove(UUID productId) {
            rows.remove(productId);
        }

        @Override
        public List<ImageQualityRecord> tasks(String ownerOrg) {
            return rows.values().stream().filter(r -> r.ownerOrg().equals(ownerOrg) && r.needsAttention())
                    .sorted((a, b) -> a.state() == b.state() ? 0 : a.state() == ImageQuality.FAIL ? -1 : 1).toList();
        }

        @Override
        public Map<ImageQuality, Long> counts() {
            Map<ImageQuality, Long> out = ImageQualityRepository.emptyCounts();
            rows.values().forEach(r -> out.merge(r.state(), 1L, Long::sum));
            return out;
        }

        @Override
        public List<ImageQualityRecord> failingSince(Instant cutoff, int limit) {
            return rows.values().stream().filter(r -> r.state() == ImageQuality.FAIL && r.hiddenAt() == null
                    && !r.failingSince().isAfter(cutoff)).limit(limit).toList();
        }

        @Override
        public long countDue(Instant cutoff) {
            return failingSince(cutoff, Integer.MAX_VALUE).size();
        }

        @Override
        public List<ImageQualityRecord> failing(int limit) {
            return rows.values().stream().filter(r -> r.state() == ImageQuality.FAIL).limit(limit).toList();
        }

        @Override
        public void markHidden(UUID productId, Instant at) {
            ImageQualityRecord r = rows.get(productId);
            rows.put(productId, new ImageQualityRecord(r.productId(), r.ownerOrg(), r.atin(), r.title(), r.state(), r.reasons(),
                    r.mainImageUrl(), r.checkedAt(), r.failingSince(), at));
        }

        @Override
        public Set<UUID> hidden(Collection<UUID> productIds) {
            return rows.values().stream().filter(r -> r.hiddenAt() != null && productIds.contains(r.productId()))
                    .map(ImageQualityRecord::productId).collect(Collectors.toSet());
        }
    }

    private final MemoryScores scores = new MemoryScores();
    private final ImageVerifier verifier = mock(ImageVerifier.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductEvents productEvents = mock(ProductEvents.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final ResourceAccessChecker access = mock(ResourceAccessChecker.class);
    private final OwnerResolver owners = mock(OwnerResolver.class);

    private ImageQualityService service(Mode mode, boolean hide) {
        ImageSettings settings = new ImageSettings(mode, 60, hide, 50, Duration.ofSeconds(30), Duration.ofDays(30));
        return new ImageQualityServiceImpl(scores, verifier, settings, products, productEvents, events, permissions, access,
                owners, Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));
    }

    @BeforeEach
    void actors() {
        Actor seller = new Actor("member-1", PrincipalType.SELLER_MEMBER, Optional.of("org_a"), Set.of(), Set.of("catalog:write"));
        when(permissions.require(CatalogPermissions.WRITE)).thenReturn(seller);
        when(owners.ownerFor(seller)).thenReturn(new ListingOwner(ListingOwnerKind.SELLER, "org_a"));
    }

    private Candidate listing(String org, String atin, String image) {
        return new Candidate(UUID.randomUUID(), org, "APPAREL_DRESS", "Title " + atin, atin, image);
    }

    private Candidate wait(Candidate c) {
        scores.waiting.add(c);
        return c;
    }

    private static ImageCheckResult passes(String url) {
        return new ImageCheckResult(url, true, "PASS", List.of(), 1600, 1600, 1, "image/jpeg", true);
    }

    private static ImageCheckResult warns(String url) {
        return new ImageCheckResult(url, true, "WARN", List.of(new ImageFinding("FILL_OK_NEAR", "WARN", "The product could fill more.")),
                1600, 1600, 1, "image/jpeg", true);
    }

    private static ImageCheckResult fails(String url) {
        return new ImageCheckResult(url, true, "REJECT", List.of(new ImageFinding("MAIN_TOO_SMALL", "REJECT", SMALL),
                new ImageFinding("LARGER_BETTER", "INFO", "Larger zooms best.")), 800, 800, 1, "image/jpeg", true);
    }

    @Test
    void nothingIsAskedOrStoredWhileTheImageRulesAreOff() {
        wait(listing("org_a", "AT00000001", "https://cdn/a.jpg"));

        assertThat(service(Mode.OFF, false).rescoreBatch()).isZero();

        verifyNoInteractions(verifier, events);
        assertThat(scores.rows).isEmpty();
    }

    @Test
    void eachListingIsScoredForTheMainSlotAndOnlyChangesAreAnnounced() {
        Candidate ok = wait(listing("org_a", "AT00000001", "https://cdn/ok.jpg"));
        Candidate improve = wait(listing("org_a", "AT00000002", "https://cdn/improve.jpg"));
        Candidate fail = wait(listing("org_b", "AT00000003", "https://cdn/fail.jpg"));
        when(verifier.rescore(any())).thenReturn(List.of(passes("https://cdn/ok.jpg"), warns("https://cdn/improve.jpg"),
                fails("https://cdn/fail.jpg")));

        assertThat(service(Mode.ENFORCE, false).rescoreBatch()).isEqualTo(3);

        verify(verifier).rescore(List.of(new ImageVerifier.Item("https://cdn/ok.jpg", "MAIN", "APPAREL_DRESS"),
                new ImageVerifier.Item("https://cdn/improve.jpg", "MAIN", "APPAREL_DRESS"),
                new ImageVerifier.Item("https://cdn/fail.jpg", "MAIN", "APPAREL_DRESS")));
        assertThat(scores.rows.get(ok.productId()).state()).isEqualTo(ImageQuality.OK);
        assertThat(scores.rows.get(improve.productId()).state()).isEqualTo(ImageQuality.IMPROVE);
        ImageQualityRecord failed = scores.rows.get(fail.productId());
        assertThat(failed.state()).isEqualTo(ImageQuality.FAIL);
        assertThat(failed.failingSince()).isEqualTo(NOW);
        assertThat(failed.reasons()).extracting(ImageFinding::code).containsExactly("MAIN_TOO_SMALL"); // information is not a reason
        // The task event is for listings that need attention; a passing listing says nothing.
        verify(events, never()).publish(eq(CatalogEvents.LISTING_IMAGE_TASK), any(), eq(ok.productId().toString()), any());
        verify(events).publish(eq(CatalogEvents.LISTING_IMAGE_TASK), eq("product"), eq(fail.productId().toString()), any());
        verify(events).publish(eq(CatalogEvents.LISTING_IMAGE_TASK), eq("product"), eq(improve.productId().toString()), any());
    }

    @Test
    void aListingThatKeepsFailingKeepsItsGraceClockAndSaysNothingNew() {
        Candidate c = wait(listing("org_a", "AT00000003", "https://cdn/fail.jpg"));
        Instant earlier = NOW.minus(Duration.ofDays(40));
        scores.save(new ImageQualityRecord(c.productId(), "org_a", "AT00000003", "T", ImageQuality.FAIL, List.of(), "https://cdn/fail.jpg",
                NOW.minus(Duration.ofDays(31)), earlier, null));
        when(verifier.rescore(any())).thenReturn(List.of(fails("https://cdn/fail.jpg")));

        service(Mode.ENFORCE, false).rescoreBatch();

        assertThat(scores.rows.get(c.productId()).failingSince()).as("the 60 days run from the first failure").isEqualTo(earlier);
        verifyNoInteractions(events);
    }

    @Test
    void aListingWithoutAMainPictureFailsAndMediaIsNotAskedAboutIt() {
        Candidate c = wait(listing("org_a", "AT00000004", null));

        service(Mode.ENFORCE, false).rescoreBatch();

        verifyNoInteractions(verifier);
        assertThat(scores.rows.get(c.productId()).reasons()).extracting(ImageFinding::code).containsExactly("NO_MAIN_IMAGE");
    }

    @Test
    void aPictureMediaDoesNotKnowFailsAsNotVerified() {
        Candidate c = wait(listing("org_a", "AT00000005", "https://seed.example.com/x.jpg"));
        when(verifier.rescore(any())).thenReturn(List.of(ImageCheckResult.unknown("https://seed.example.com/x.jpg")));

        service(Mode.ENFORCE, false).rescoreBatch();

        assertThat(scores.rows.get(c.productId()).state()).isEqualTo(ImageQuality.FAIL);
        assertThat(scores.rows.get(c.productId()).reasons()).extracting(ImageFinding::code).containsExactly("NOT_VERIFIED");
    }

    @Test
    void whenMediaIsDownTheBatchIsLeftForTheNextRun() {
        wait(listing("org_a", "AT00000006", "https://cdn/a.jpg"));
        when(verifier.rescore(any())).thenThrow(new ImageServiceUnavailable("down", null));

        assertThat(service(Mode.ENFORCE, false).rescoreBatch()).isZero();

        assertThat(scores.rows).isEmpty();
        assertThat(scores.unscored()).isEqualTo(1);
    }

    @Test
    void aListingWhoseMainPictureChangedIsMeasuredAgainAtOnce() {
        Candidate c = wait(listing("org_a", "AT00000007", "https://cdn/new.jpg"));
        scores.save(new ImageQualityRecord(c.productId(), "org_a", "AT00000007", "T", ImageQuality.FAIL, List.of(), "https://cdn/old.jpg",
                NOW.minusSeconds(60), NOW.minus(Duration.ofDays(10)), null));
        when(verifier.rescore(any())).thenReturn(List.of(passes("https://cdn/new.jpg")));

        service(Mode.ENFORCE, false).rescoreBatch();

        assertThat(scores.rows.get(c.productId()).state()).isEqualTo(ImageQuality.OK);
        assertThat(scores.rows.get(c.productId()).failingSince()).isNull();
    }

    @Test
    void sellersSeeTheirOwnTasksFailingFirstWithTheDaysLeftOfTheGracePeriod() {
        scores.save(new ImageQualityRecord(UUID.randomUUID(), "org_a", "AT00000008", "Warns", ImageQuality.IMPROVE,
                List.of(new ImageFinding("FILL_OK_NEAR", "WARN", "The product could fill more.")), "u1", NOW, null, null));
        scores.save(new ImageQualityRecord(UUID.randomUUID(), "org_a", "AT00000009", "Fails", ImageQuality.FAIL,
                List.of(new ImageFinding("MAIN_TOO_SMALL", "REJECT", SMALL)), "u2", NOW, NOW.minus(Duration.ofDays(20)), null));
        scores.save(new ImageQualityRecord(UUID.randomUUID(), "org_a", "AT00000010", "Fine", ImageQuality.OK, List.of(), "u3", NOW, null, null));
        scores.save(new ImageQualityRecord(UUID.randomUUID(), "org_b", "AT00000011", "Theirs", ImageQuality.FAIL, List.of(), "u4", NOW, NOW, null));

        List<ImageQualityService.Task> tasks = service(Mode.ENFORCE, false).myTasks();

        assertThat(tasks).extracting(ImageQualityService.Task::title).containsExactly("Fails", "Warns");
        ImageQualityService.Task failing = tasks.getFirst();
        assertThat(failing.reasons()).singleElement().satisfies(r -> {
            assertThat(r.code()).isEqualTo("MAIN_TOO_SMALL");
            assertThat(r.message()).isEqualTo(SMALL);
        });
        assertThat(failing.daysLeft()).isEqualTo(40);
        assertThat(failing.hideAfter()).isEqualTo(NOW.minus(Duration.ofDays(20)).plus(Duration.ofDays(60)));
        assertThat(failing.hidden()).isFalse();
        assertThat(tasks.get(1).daysLeft()).isNull();
        verify(access).requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_VIEW_PRODUCTS, "org_a");
    }

    private ImageQualityRecord failingFor(int days, String atin) {
        UUID id = UUID.randomUUID();
        ImageQualityRecord r = new ImageQualityRecord(id, "org_a", atin, "T" + atin, ImageQuality.FAIL,
                List.of(new ImageFinding("NOT_WHITE", "REJECT", "Needs a white background.")), "u", NOW, NOW.minus(Duration.ofDays(days)), null);
        scores.save(r);
        Product p = new Product(id, new Atin("AT00000001"), new ListingOwner(ListingOwnerKind.SELLER, "org_a"), UUID.randomUUID(), null,
                "dress", "T", Map.of(), List.of(), "", List.of(), ProductStatus.LIVE, null, NOW, NOW, 0);
        when(products.findById(id)).thenReturn(Optional.of(p));
        return r;
    }

    @Test
    void theHideStepIsOffByDefaultSoNothingIsLimitedEvenAfterTheGracePeriod() {
        ImageQualityRecord old = failingFor(90, "AT00000012");

        ImageQualityService.HideResult result = service(Mode.ENFORCE, false).hideDue();

        assertThat(result).isEqualTo(new ImageQualityService.HideResult(false, false, 0));
        assertThat(scores.rows.get(old.productId()).hiddenAt()).isNull();
        verifyNoInteractions(productEvents);
    }

    @Test
    void whenTheFlagIsOnOnlyListingsPastTheGracePeriodAreLimitedAndNothingIsDeleted() {
        ImageQualityRecord old = failingFor(61, "AT00000013");
        ImageQualityRecord recent = failingFor(59, "AT00000014");

        ImageQualityService.HideResult result = service(Mode.ENFORCE, true).hideDue();

        assertThat(result).isEqualTo(new ImageQualityService.HideResult(false, true, 1));
        assertThat(scores.rows.get(old.productId()).hiddenAt()).isEqualTo(NOW);
        assertThat(scores.rows.get(recent.productId()).hiddenAt()).isNull();
        verify(productEvents).publish(eq(CatalogEvents.PRODUCT_HIDDEN), any(Product.class));
        assertThat(scores.rows).hasSize(2);
        verify(products, never()).update(any());
    }

    @Test
    void aDryRunCountsWithoutChangingAnythingWhateverTheFlagSaysAndNeedsStaff() {
        failingFor(100, "AT00000015");
        failingFor(10, "AT00000016");
        ImageQualityService off = service(Mode.ENFORCE, false);

        ImageQualityService.HideResult dry = off.hideFailing(true);

        assertThat(dry).isEqualTo(new ImageQualityService.HideResult(true, false, 1));
        verify(permissions).require(CatalogPermissions.MODERATE);
        assertThat(scores.rows.values()).allMatch(r -> r.hiddenAt() == null);
        // Without dryRun the flag still decides.
        assertThat(off.hideFailing(false).listings()).isZero();
        when(permissions.require(CatalogPermissions.MODERATE)).thenThrow(new ForbiddenActionException("catalog.forbidden", "no"));
        assertThatThrownBy(() -> off.hideFailing(true)).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(off::report).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(off::rescoreNow).isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void aHiddenListingComesBackWhenItsMainImageNowPasses() {
        Candidate c = wait(listing("org_a", "AT00000017", "https://cdn/fixed.jpg"));
        scores.save(new ImageQualityRecord(c.productId(), "org_a", "AT00000017", "T", ImageQuality.FAIL, List.of(), "https://cdn/old.jpg",
                NOW.minusSeconds(60), NOW.minus(Duration.ofDays(70)), NOW.minus(Duration.ofDays(5))));
        Product p = new Product(c.productId(), new Atin("AT00000017"), new ListingOwner(ListingOwnerKind.SELLER, "org_a"), UUID.randomUUID(),
                null, "dress", "T", Map.of(), List.of(), "", List.of(), ProductStatus.LIVE, null, NOW, NOW, 0);
        when(products.findById(c.productId())).thenReturn(Optional.of(p));
        when(verifier.rescore(any())).thenReturn(List.of(passes("https://cdn/fixed.jpg")));

        service(Mode.ENFORCE, true).rescoreBatch();

        ImageQualityRecord back = scores.rows.get(c.productId());
        assertThat(back.state()).isEqualTo(ImageQuality.OK);
        assertThat(back.hiddenAt()).isNull();
        assertThat(back.failingSince()).isNull();
        verify(productEvents).publish(CatalogEvents.PRODUCT_RESTORED, p);
    }

    @Test
    void theStaffReportShowsCountsReasonsAndWhatTheHideStepWouldDo() {
        failingFor(90, "AT00000018");
        failingFor(5, "AT00000019");
        scores.save(new ImageQualityRecord(UUID.randomUUID(), "org_a", "AT00000020", "Warns", ImageQuality.IMPROVE, List.of(), "u", NOW, null, null));
        scores.save(new ImageQualityRecord(UUID.randomUUID(), "org_a", "AT00000021", "Fine", ImageQuality.OK, List.of(), "u", NOW, null, null));
        wait(listing("org_a", "AT00000022", "https://cdn/not-scored.jpg"));

        ImageQualityService.Report report = service(Mode.ENFORCE, false).report();

        assertThat(report.counts()).containsEntry("FAIL", 2L).containsEntry("IMPROVE", 1L).containsEntry("OK", 1L);
        assertThat(report.notScored()).isEqualTo(1);
        assertThat(report.reasons()).containsEntry("NOT_WHITE", 2L);
        assertThat(report.wouldHide()).isEqualTo(1);
        assertThat(report.hideFlagOn()).isFalse();
        assertThat(report.graceDays()).isEqualTo(60);
        assertThat(report.failing()).extracting(ImageQualityService.ReportRow::pastGrace).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void theRecordKnowsItsGraceClock() {
        ImageQualityRecord r = new ImageQualityRecord(UUID.randomUUID(), "o", "A", "T", ImageQuality.FAIL, List.of(), "u", NOW,
                NOW.minus(Duration.ofDays(59)).minusSeconds(1), null);
        assertThat(r.daysLeft(60, NOW)).isZero();
        assertThat(new HashSet<>(List.of(ImageQuality.of(passes("u")), ImageQuality.of(warns("u")), ImageQuality.of(fails("u")),
                ImageQuality.of(ImageCheckResult.unknown("u"))))).containsExactlyInAnyOrder(ImageQuality.OK, ImageQuality.IMPROVE,
                ImageQuality.FAIL);
    }
}
