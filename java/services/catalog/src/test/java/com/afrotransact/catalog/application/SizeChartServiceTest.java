package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.application.SizeChartService.ChartOutcome;
import com.afrotransact.catalog.application.SizeChartService.ChartView;
import com.afrotransact.catalog.application.SizeChartService.ImportAction;
import com.afrotransact.catalog.application.SizeChartService.ImportReport;
import com.afrotransact.catalog.application.SizeChartService.Suggestions;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.SheetRow;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import com.afrotransact.catalog.domain.sizing.ChartContent;
import com.afrotransact.catalog.domain.sizing.ChartRow;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.domain.sizing.SizingFixtures;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SizeChartRepository;
import com.afrotransact.catalog.port.SpreadsheetReader;
import com.afrotransact.catalog.port.SpreadsheetWriter;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Who may do what with size charts, and the chart rules as the use cases apply them. */
class SizeChartServiceTest {

    private static final String SELLER = "org_adire";
    private static final String OTHER = "org_kente";
    private static final String RETAIL = "org_retail";

    private final InMemoryCharts charts = new InMemoryCharts();
    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final ResourceAccessChecker access = mock(ResourceAccessChecker.class);
    private final SpreadsheetReader reader = mock(SpreadsheetReader.class);
    private final SpreadsheetWriter writer = mock(SpreadsheetWriter.class);
    private SizeChartServiceImpl service;
    private Actor actor;

    private static Actor seller(String org) {
        return new Actor("user-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of(CatalogPermissions.WRITE));
    }

    @BeforeEach
    void setUp() {
        actor = seller(SELLER);
        when(permissions.require(CatalogPermissions.WRITE)).thenAnswer(i -> actor);
        when(categories.findProductType("dress")).thenReturn(Optional.of(SizingFixtures.DRESS));
        when(categories.findProductType("shoes")).thenReturn(Optional.of(SizingFixtures.SHOES));
        when(categories.findProductType("grocery")).thenReturn(Optional.of(SizingFixtures.SPICES));
        service = new SizeChartServiceImpl(charts, products, categories, permissions, access,
                new RetailAwareOwnerResolver(RETAIL), reader, writer,
                Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC));
    }

    private static ChartContent content(String name, String... sizes) {
        List<ChartRow> rows = new ArrayList<>();
        for (String size : sizes) {
            rows.add(SizingFixtures.row(size, "84", "88", "bust", "waist"));
        }
        return new ChartContent(name, MeasurementUnit.CM, "dress", null,
                SizingFixtures.columns(MeasurementUnit.CM, "Bust", "Waist"), rows, FitNote.TRUE_TO_SIZE, null);
    }

    @Test
    void aSellerCreatesAChartForTheirOrganisation() {
        ChartView created = service.create(content("Adire dresses", "S", "M"));

        assertThat(created.chart().ownerOrg()).isEqualTo(SELLER);
        assertThat(created.chart().version()).isZero();
        assertThat(charts.findById(created.chart().id())).isPresent();
        assertThat(created.productCount()).isZero();
    }

    @Test
    void aChartIsValidatedWithFieldNames() {
        assertThatThrownBy(() -> service.create(content("Adire dresses", "S", "s")))
                .isInstanceOfSatisfying(CatalogRuleViolation.class,
                        e -> assertThat(e.fields()).containsExactly("rows[1].size"));
    }

    @Test
    void theNameMustBeFreeAmongTheSellersActiveChartsIgnoringCase() {
        service.create(content("Adire dresses", "S"));

        assertThatThrownBy(() -> service.create(content("ADIRE DRESSES", "S")))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fields()).containsExactly("name"));
        actor = seller(OTHER);
        assertThat(service.create(content("Adire dresses", "S")).chart().ownerOrg()).isEqualTo(OTHER);
    }

    @Test
    void aChartMadeForACategoryRecordsItsProductType() {
        UUID node = UUID.randomUUID();
        when(categories.findById(node)).thenReturn(Optional.of(new CategoryNode(node,
                null, "dresses", "Dresses", CategoryLevel.PRODUCT_TYPE, "dress",
                "Fashion › Women › Dresses", 0, true, 0, null, null)));
        ChartContent c = content("Adire dresses", "S");

        ChartView created = service.create(new ChartContent(c.name(), c.unitSystem(), null, node, c.columns(), c.rows(),
                c.fitNote(), null));

        assertThat(created.chart().productTypeCode()).isEqualTo("dress");
        assertThat(created.chart().categoryId()).isEqualTo(node);
    }

    @Test
    void anotherSellersChartDoesNotExistForTheCaller() {
        UUID id = service.create(content("Adire dresses", "S")).chart().id();
        actor = seller(OTHER);

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.update(id, null, content("Adire dresses", "S", "M")))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.retire(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.duplicate(id, null)).isInstanceOf(NotFoundException.class);
        assertThat(service.list(null, null, null, false, null, 0, 25).items()).isEmpty();
    }

    @Test
    void staffReadAnyOrganisationsChartsButNeverChangeThem() {
        UUID id = service.create(content("Adire dresses", "S")).chart().id();
        actor = new Actor("staff-1", PrincipalType.STAFF, Optional.empty(), Set.of("platform.catalog"),
                Set.of(CatalogPermissions.WRITE));

        assertThat(service.get(id).chart().ownerOrg()).isEqualTo(SELLER);
        assertThat(service.list(SELLER, null, null, false, null, 0, 25).items()).hasSize(1);
        assertThatThrownBy(() -> service.update(id, null, content("Adire dresses", "S", "M")))
                .isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> service.create(content("Staff chart", "S"))).isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> service.retire(id)).isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void retailStaffWriteTheChartsOfAfroTransactRetail() {
        actor = new Actor("staff-2", PrincipalType.STAFF, Optional.empty(), Set.of("platform.catalog"),
                Set.of(CatalogPermissions.WRITE, RetailAwareOwnerResolver.RETAIL_PERMISSION));

        assertThat(service.create(content("Retail dresses", "S")).chart().ownerOrg()).isEqualTo(RETAIL);
    }

    @Test
    void updateReplacesTheContentAndBumpsTheVersion() {
        ChartView created = service.create(content("Adire dresses", "S"));

        ChartView updated = service.update(created.chart().id(), 0L, content("Adire dresses (2026)", "S", "M"));

        assertThat(updated.chart().rows()).hasSize(2);
        assertThat(updated.chart().name()).isEqualTo("Adire dresses (2026)");
        assertThat(updated.chart().version()).isEqualTo(1);
    }

    @Test
    void aStaleVersionIsAConflict() {
        ChartView created = service.create(content("Adire dresses", "S"));
        service.update(created.chart().id(), 0L, content("Adire dresses", "S", "M"));

        assertThatThrownBy(() -> service.update(created.chart().id(), 0L, content("Adire dresses", "S")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("changed since you opened it");
    }

    @Test
    void aSizeThatProductsStillSellCannotBeRemoved() {
        ChartView created = service.create(content("Adire dresses", "S", "M", "UK 12"));
        Product sells = productSelling(created.chart().id(), "UK 12", "S");
        when(products.findBySizeChart(eq(created.chart().id()), anyInt())).thenReturn(List.of(sells));

        assertThatThrownBy(() -> service.update(created.chart().id(), null, content("Adire dresses", "S", "M")))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, e -> assertThat(e.fieldErrors().get("rows"))
                        .contains("UK 12 (Ankara Wrap Dress, Puff Sleeve)").doesNotContain("S ("));
        assertThat(service.update(created.chart().id(), null, content("Adire dresses", "S", "M", "UK 12", "L"))
                .chart().rows()).hasSize(4);
    }

    @Test
    void retiringKeepsTheChartButFreesItsNameAndBlocksEditing() {
        ChartView created = service.create(content("Adire dresses", "S"));

        ChartView retired = service.retire(created.chart().id());

        assertThat(retired.chart().retired()).isTrue();
        assertThat(service.retire(created.chart().id()).chart().retired()).isTrue();
        assertThat(charts.findById(created.chart().id())).isPresent();
        assertThat(service.list(null, null, null, false, null, 0, 25).items()).isEmpty();
        assertThat(service.list(null, null, null, true, null, 0, 25).items()).hasSize(1);
        assertThat(service.create(content("Adire dresses", "S")).chart().id()).isNotEqualTo(created.chart().id());
        assertThatThrownBy(() -> service.update(created.chart().id(), null, content("Adire dresses", "S", "M")))
                .isInstanceOf(ConflictException.class).hasMessageContaining("retired");
    }

    @Test
    void duplicatingCopiesTheContentUnderAFreeName() {
        ChartView created = service.create(content("Adire dresses", "S", "M"));

        ChartView copy = service.duplicate(created.chart().id(), null);
        ChartView second = service.duplicate(created.chart().id(), null);
        ChartView named = service.duplicate(created.chart().id(), "Kente dresses");

        assertThat(copy.chart().name()).isEqualTo("Adire dresses (copy)");
        assertThat(second.chart().name()).isEqualTo("Adire dresses (copy 2)");
        assertThat(named.chart().name()).isEqualTo("Kente dresses");
        assertThat(copy.chart().rows()).isEqualTo(created.chart().rows());
        assertThat(copy.chart().id()).isNotEqualTo(created.chart().id());
        assertThatThrownBy(() -> service.duplicate(created.chart().id(), "adire dresses"))
                .isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void suggestionsComeFromTheSizingTraitOfTheProductType() {
        Suggestions dress = service.suggestions(null, "dress");

        assertThat(dress.sized()).isTrue();
        assertThat(dress.sizeChartRequired()).isFalse();
        assertThat(dress.sizeDimension()).isEqualTo("Size");
        assertThat(dress.sizes()).containsExactly("XS", "S", "M", "L", "XL");
        assertThat(dress.sizesAllowCustomValues()).isTrue();
        assertThat(dress.measurements()).extracting(m -> m.label()).containsExactly("Bust", "Waist", "Hip");
        Suggestions groceries = service.suggestions(null, "grocery");
        assertThat(groceries.sized()).isFalse();
        assertThat(groceries.sizes()).isEmpty();
        assertThat(groceries.measurements()).isEmpty();
        assertThatThrownBy(() -> service.suggestions(null, null)).isInstanceOf(CatalogRuleViolation.class);
    }

    // ------------------------------------------------------------------ bulk upload

    private static final List<String> HEADER = List.of("Size chart name *", "Unit", "Size *", "Bust *", "Waist *");

    private void file(String[]... rows) {
        List<SheetRow> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            out.add(new SheetRow(i + 2, List.of(rows[i])));
        }
        when(reader.read(any(), eq(SpreadsheetFormat.CSV))).thenReturn(new ParsedSheet(HEADER, out));
    }

    private ImportReport upload(boolean dryRun) {
        return service.importCharts("size-charts.csv", new byte[] {1}, "dress", null, dryRun);
    }

    @Test
    void anUploadCreatesChartsAndAReUploadChangesNothing() {
        file(new String[] {"Adire dresses", "cm", "S", "84-88", "66-70"}, new String[] {"Adire dresses", "", "M", "89-93",
            "71-75"}, new String[] {"Kente tops", "in", "M", "35", "28"});

        ImportReport first = upload(false);
        ImportReport again = upload(false);

        assertThat(first.applied()).isTrue();
        assertThat(first.charts()).extracting(ChartOutcome::action).containsExactly(ImportAction.CREATE, ImportAction.CREATE);
        assertThat(charts.all()).hasSize(2);
        assertThat(charts.findActiveByName(SELLER, "adire dresses").orElseThrow().productTypeCode()).isEqualTo("dress");
        assertThat(charts.findActiveByName(SELLER, "Kente tops").orElseThrow().unitSystem()).isEqualTo(MeasurementUnit.IN);
        assertThat(again.charts()).extracting(ChartOutcome::action)
                .containsExactly(ImportAction.UNCHANGED, ImportAction.UNCHANGED);
        assertThat(charts.all()).hasSize(2);
        assertThat(charts.findActiveByName(SELLER, "Kente tops").orElseThrow().version()).isZero();
    }

    @Test
    void aReUploadWithChangesUpdatesTheChartMatchedByName() {
        file(new String[] {"Adire dresses", "cm", "S", "84-88", "66-70"});
        upload(false);
        file(new String[] {"ADIRE DRESSES", "cm", "S", "85-89", "66-70"}, new String[] {"ADIRE DRESSES", "", "M", "89-93",
            "71-75"});

        ImportReport report = upload(false);

        assertThat(report.charts()).singleElement().satisfies(c -> assertThat(c.action()).isEqualTo(ImportAction.UPDATE));
        assertThat(charts.all()).hasSize(1);
        SizeChart chart = charts.all().getFirst();
        assertThat(chart.rows()).hasSize(2);
        assertThat(chart.version()).isEqualTo(1);
    }

    @Test
    void aDryRunReportsWithoutSaving() {
        file(new String[] {"Adire dresses", "cm", "S", "84-88", "66-70"});

        ImportReport report = upload(true);

        assertThat(report.applied()).isFalse();
        assertThat(report.charts()).singleElement().satisfies(c -> {
            assertThat(c.action()).isEqualTo(ImportAction.CREATE);
            assertThat(c.chartId()).isNull();
        });
        assertThat(charts.all()).isEmpty();
    }

    @Test
    void chartsWithRowErrorsAreReportedAndNotSavedWhileTheOthersAre() {
        file(new String[] {"Good chart", "cm", "S", "84-88", "66-70"}, new String[] {"Bad chart", "cm", "S", "abc", "66"});

        ImportReport report = upload(false);

        assertThat(report.charts()).extracting(ChartOutcome::name, ChartOutcome::action).containsExactly(
                tuple("Good chart", ImportAction.CREATE),
                tuple("Bad chart", ImportAction.FAILED));
        assertThat(charts.all()).extracting(SizeChart::name).containsExactly("Good chart");
        assertThat(report.rows().get(1).messages()).isNotEmpty();
    }

    @Test
    void aReUploadCannotDropASizeThatProductsStillSell() {
        file(new String[] {"Adire dresses", "cm", "S", "84-88", "66-70"}, new String[] {"Adire dresses", "", "UK 12", "89",
            "71"});
        upload(false);
        SizeChart saved = charts.all().getFirst();
        when(products.findBySizeChart(eq(saved.id()), anyInt())).thenReturn(List.of(productSelling(saved.id(), "UK 12")));
        file(new String[] {"Adire dresses", "cm", "S", "84-88", "66-70"});

        ImportReport report = upload(false);

        assertThat(report.charts()).singleElement().satisfies(c -> {
            assertThat(c.action()).isEqualTo(ImportAction.FAILED);
            assertThat(c.message()).contains("UK 12");
        });
        assertThat(charts.all().getFirst().rows()).hasSize(2);
    }

    @Test
    void anotherSellersChartWithTheSameNameIsNeverTouched() {
        actor = seller(OTHER);
        file(new String[] {"Adire dresses", "cm", "S", "10-12", "10"});
        upload(false);
        actor = seller(SELLER);
        file(new String[] {"Adire dresses", "cm", "S", "84-88", "66-70"});

        ImportReport report = upload(false);

        assertThat(report.charts()).singleElement().satisfies(c -> assertThat(c.action()).isEqualTo(ImportAction.CREATE));
        assertThat(charts.all()).hasSize(2);
        assertThat(charts.all()).extracting(SizeChart::ownerOrg).containsExactlyInAnyOrder(SELLER, OTHER);
    }

    @Test
    void anUnsupportedFileNameIsRejected() {
        assertThatThrownBy(() -> service.importCharts("charts.pdf", new byte[] {1}, null, null, false))
                .isInstanceOf(SpreadsheetRejected.class);
    }

    // ------------------------------------------------------------------ helpers

    private Product productSelling(UUID chartId, String... sizes) {
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) {
            variants.add(new Variant(UUID.randomUUID(), new Atin("AT0000" + String.format("%04d", i + 1)), "SKU-" + i, null,
                    true, Map.of("Size", sizes[i]), 4900, "USD", 3, null, List.of()));
        }
        Instant now = Instant.parse("2026-11-01T10:00:00Z");
        return new Product(UUID.randomUUID(), new Atin("AT00000000"), new ListingOwner(ListingOwnerKind.SELLER, SELLER),
                UUID.randomUUID(), null, "dress", "Ankara Wrap Dress, Puff Sleeve", Map.of(), List.of(), "Size", variants,
                ProductStatus.LIVE, null, now, now, 0);
    }

    /** Charts held in memory with the repository's optimistic locking and unique active names. */
    private static final class InMemoryCharts implements SizeChartRepository {
        private final Map<UUID, SizeChart> byId = new LinkedHashMap<>();

        List<SizeChart> all() {
            return new ArrayList<>(byId.values());
        }

        @Override
        public SizeChart insert(SizeChart chart, String createdBy) {
            byId.put(chart.id(), chart);
            return chart;
        }

        @Override
        public SizeChart update(SizeChart chart) {
            SizeChart stored = byId.get(chart.id());
            if (stored.version() != chart.version()) {
                throw new ConflictException("catalog.size-chart-changed", "changed since you opened it");
            }
            SizeChart next = new SizeChart(chart.id(), chart.ownerOrg(), chart.name(), chart.unitSystem(),
                    chart.productTypeCode(), chart.categoryId(), chart.columns(), chart.rows(), chart.fitNote(),
                    chart.howToMeasure(), chart.retiredAt(), chart.createdAt(), chart.updatedAt(), chart.version() + 1);
            byId.put(chart.id(), next);
            return next;
        }

        @Override
        public Optional<SizeChart> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<SizeChart> findActiveByName(String ownerOrg, String name) {
            return byId.values().stream().filter(c -> c.ownerOrg().equals(ownerOrg) && !c.retired()
                    && c.name().equalsIgnoreCase(name.strip())).findFirst();
        }

        @Override
        public Map<String, SizeChart> activeByNames(String ownerOrg, Collection<String> names) {
            Map<String, SizeChart> out = new HashMap<>();
            names.forEach(n -> findActiveByName(ownerOrg, n).ifPresent(c -> out.put(c.name().toLowerCase(Locale.ROOT), c)));
            return out;
        }

        @Override
        public ChartPage list(ChartQuery q) {
            List<SizeChart> items = byId.values().stream()
                    .filter(c -> q.ownerOrg() == null || c.ownerOrg().equals(q.ownerOrg()))
                    .filter(c -> q.includeRetired() || !c.retired()).toList();
            return new ChartPage(items, items.size(), q.page(), q.size());
        }
    }
}
