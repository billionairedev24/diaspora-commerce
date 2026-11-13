package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ImportStatus;
import com.afrotransact.catalog.domain.imports.OfferTarget;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.port.ProductImportRepository;
import com.afrotransact.catalog.port.ProductImportRepository.RowResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** S393: the product import job hands matched, mixed and unchanged rows of a CREATE file to this job. */
class MatchedRowsJobTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final UUID IMPORT = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final List<String> HEADER = List.of("Parent SKU", "SKU", "Barcode (GTIN/UPC/EAN)", "Price (USD)",
            "Quantity", "Options");

    private final ProductImportRepository imports = mock(ProductImportRepository.class);
    private final OfferRowProcessor offers = mock(OfferRowProcessor.class);
    private final VariantRequests variants = mock(VariantRequests.class);
    private final ImportMatcher matcher = mock(ImportMatcher.class);
    private MatchedRowsJobImpl job;

    @BeforeEach
    void setUp() {
        job = new MatchedRowsJobImpl(imports, offers, variants, matcher);
    }

    private static ProductImport upload() {
        return new ProductImport(IMPORT, "BU-000001", new ListingOwner(ListingOwnerKind.SELLER, "org_b"), "user_b", "Ada",
                "products.csv", SpreadsheetFormat.CSV, ImportMode.CREATE, null, HEADER, List.of(), ImportStatus.RUNNING,
                false, 3, 3, 0, 0, 3, 0, 0, 0, 0, false, NOW, NOW, null, NOW, "abc", null, 0, 0, 0);
    }

    private static StoredRow row(int n, String group, String parent, String sku, String gtin, String options) {
        return new StoredRow(n, group, sku, RowStatus.WARNING, List.of(), List.of(parent, sku, gtin, "10.00", "5", options),
                RowOutcome.PENDING, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMatchedRowGoesToTheOfferProcessorAndItsResultsAreSaved() {
        StoredRow r = row(2, "offer:2", "", "A", "4006381333931", "");
        RowResult result = new RowResult(2, RowOutcome.OFFER_CREATED, null, "AT00000002", UUID.randomUUID(), null);
        when(offers.offers(any(), any())).thenReturn(List.of(result));

        job.apply(upload(), "offer:2", List.of(r));

        verify(offers).offers(any(), eq(List.of(r)));
        verify(offers).save(IMPORT, List.of(result));
        verify(variants, never()).file(any(), any(), any());
    }

    @Test
    void anUnchangedRowIsSkippedWithAPlainNoteAndNothingIsSentAnywhere() {
        StoredRow r = row(2, "skip:2", "", "A", "", "");

        job.apply(upload(), "skip:2", List.of(r));

        ArgumentCaptor<List<RowResult>> saved = ArgumentCaptor.forClass(List.class);
        verify(offers).save(eq(IMPORT), saved.capture());
        assertThat(saved.getValue().getFirst().outcome()).isEqualTo(RowOutcome.SKIPPED);
        assertThat(saved.getValue().getFirst().note()).isEqualTo("No change: this row was imported from an earlier file.");
        verify(offers, never()).offers(any(), any());
        verify(variants, never()).file(any(), any(), any());
    }

    @Test
    void anUnmatchedChildOfAMixedGroupIsFiledOnThePageItsSiblingsAreOn() {
        StoredRow offerRow = new StoredRow(2, "offer:2", "A-S", RowStatus.WARNING, List.of(), List.of("PARENT-1", "A-S",
                "4006381333931", "10.00", "5", ""), RowOutcome.OFFER_CREATED, null, null);
        StoredRow variant = row(3, "variant:3", "PARENT-1", "A-L", "", "Size: L");
        when(imports.allRows(IMPORT)).thenReturn(List.of(offerRow, variant));
        when(matcher.resolve(any(), any())).thenReturn(Map.of("gtin:4006381333931", OfferTarget.match("AT00000002",
                "AT00000001", "Page", "org_a", true, List.of())));
        RowResult filed = new RowResult(3, RowOutcome.HELD, "This size or colour is not on the page yet. We sent it for review.",
                "AT00000001", null, null);
        when(variants.file(any(), eq(variant), eq("AT00000001"))).thenReturn(filed);

        job.apply(upload(), "variant:3", List.of(variant));

        verify(variants).file(any(), eq(variant), eq("AT00000001"));
        verify(offers).save(IMPORT, List.of(filed));
    }

    @Test
    void rowsAlreadyFinishedAreNotTouched() {
        StoredRow done = new StoredRow(2, "offer:2", "A", RowStatus.WARNING, List.of(), List.of(), RowOutcome.OFFER_CREATED,
                null, null);

        job.apply(upload(), "offer:2", List.of(done));

        verify(offers, never()).offers(any(), any());
        verify(offers, never()).save(any(), any());
    }

    @Test
    void theGroupsTheProductJobHandsOverAreRecognisedByTheirKey() {
        assertThat(MatchedRowsJob.handles("offer:2")).isTrue();
        assertThat(MatchedRowsJob.handles("variant:3")).isTrue();
        assertThat(MatchedRowsJob.handles("skip:4")).isTrue();
        assertThat(MatchedRowsJob.handles("ANKARA-WRAP")).isFalse();
        assertThat(MatchedRowsJob.handles(null)).isFalse();
    }
}
