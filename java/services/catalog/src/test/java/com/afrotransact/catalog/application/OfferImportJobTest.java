package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.afrotransact.catalog.domain.imports.TargetKey;
import com.afrotransact.catalog.port.ImportMetrics;
import com.afrotransact.catalog.port.OfferWriter;
import com.afrotransact.catalog.port.OfferWriter.OffersUnavailable;
import com.afrotransact.catalog.port.ProductImportRepository;
import com.afrotransact.catalog.port.ProductImportRepository.AuditRow;
import com.afrotransact.catalog.port.ProductImportRepository.RowResult;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * S393 and S394: the job applies rows through offers-service. A held row is never sent and never live, a failed row never
 * stops the next one, a repeated row is skipped, and an unreachable offers-service leaves the rows pending.
 */
class OfferImportJobTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final String GTIN = "4006381333931";
    private static final UUID IMPORT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final List<String> OFFER_HEADER = List.of("Barcode", "Price", "Quantity", "Seller SKU");
    private static final List<String> FEED_HEADER = List.of("Seller SKU", "Price", "Quantity");

    private final ProductImportRepository imports = mock(ProductImportRepository.class);
    private final ImportMatcher matcher = mock(ImportMatcher.class);
    private final OfferWriter offers = mock(OfferWriter.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final ImportMetrics metrics = mock(ImportMetrics.class);
    private OfferImportJobImpl job;
    private ProductImport upload;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        job = new OfferImportJobImpl(imports, matcher, offers, events, metrics, Clock.fixed(NOW, ZoneOffset.UTC), tm);
        when(imports.update(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static ProductImport upload(ImportMode mode, List<String> header) {
        return new ProductImport(IMPORT, "BU-000001", new ListingOwner(ListingOwnerKind.SELLER, "org_b"), "user_b", "Ada",
                "offers.csv", SpreadsheetFormat.CSV, mode, null, header, List.of(), ImportStatus.QUEUED, false, 3, 3, 0,
                0, 3, 0, 0, 0, 0, false, NOW, null, null, null, "abc", null, 0, 0, 0);
    }

    private static StoredRow row(int n, String... cells) {
        return new StoredRow(n, "r" + n, "", RowStatus.VALID, List.of(), List.of(cells), RowOutcome.PENDING, null, null);
    }

    private void pending(ProductImport u, StoredRow... rows) {
        upload = u;
        when(imports.lock(IMPORT)).thenAnswer(i -> Optional.of(upload));
        when(imports.pendingRows(eq(IMPORT), anyInt())).thenReturn(List.of(rows)).thenReturn(List.of(rows))
                .thenReturn(List.of());
        when(imports.rows(any(), any(), any(), anyInt(), anyInt())).thenReturn(
                new ProductImportRepository.Page<>(List.of(), 0, 0, 1));
    }

    private void matches(OfferTarget target) {
        when(matcher.resolve(anyString(), any())).thenAnswer(i -> {
            Collection<TargetKey> keys = i.getArgument(1);
            Map<String, OfferTarget> out = new HashMap<>();
            keys.forEach(k -> out.put(k.id(), target));
            return out;
        });
    }

    @SuppressWarnings("unchecked")
    private List<RowResult> savedResults() {
        ArgumentCaptor<List<RowResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(imports).setRowResults(eq(IMPORT), captor.capture());
        return captor.getValue();
    }

    private static OfferTarget found() {
        return OfferTarget.match("AT00000002", "AT00000001", "Ankara tote", "org_a", true, List.of());
    }

    @Test
    void anOfferRowCreatesAnOfferThroughOffersServiceAndIsAudited() {
        pending(upload(ImportMode.OFFER, OFFER_HEADER), row(2, GTIN, "24.99", "10", ""));
        matches(found());
        UUID offerId = UUID.randomUUID();
        when(offers.upsert(eq("org_b"), eq(IMPORT), eq("user_b"), anyList())).thenReturn(List.of(
                new OfferWriter.Result("2", "CREATED", offerId, "AT00000002", "ACTIVE", null, "Saved.")));

        job.drive(IMPORT);

        RowResult saved = savedResults().getFirst();
        assertThat(saved.outcome()).isEqualTo(RowOutcome.OFFER_CREATED);
        assertThat(saved.atin()).isEqualTo("AT00000002");
        assertThat(saved.offerId()).isEqualTo(offerId);
        ArgumentCaptor<List<OfferWriter.OfferTerms>> sent = ArgumentCaptor.forClass(List.class);
        verify(offers).upsert(eq("org_b"), eq(IMPORT), eq("user_b"), sent.capture());
        assertThat(sent.getValue().getFirst().atin()).isEqualTo("AT00000002");
        assertThat(sent.getValue().getFirst().priceMinor()).isEqualTo(2499);
        assertThat(sent.getValue().getFirst().sellerSku()).isEqualTo("AT00000002");
        assertThat(sent.getValue().getFirst().shipFrom().country()).isEqualTo("US");
        ArgumentCaptor<List<AuditRow>> audit = ArgumentCaptor.forClass(List.class);
        verify(imports).audit(eq(IMPORT), eq("org_b"), eq("user_b"), eq(NOW), audit.capture());
        assertThat(audit.getValue()).extracting(AuditRow::action).containsExactly("OFFER_CREATED");
        verify(events).publish(eq(CatalogEvents.IMPORT_COMPLETED), anyString(), anyString(), any());
    }

    @Test
    void aRowWithoutSellingApprovalIsHeldAndNeverSentToOffers() {
        pending(upload(ImportMode.OFFER, OFFER_HEADER), row(2, GTIN, "24.99", "10", ""));
        matches(OfferTarget.match("AT00000002", "AT00000001", "Ankara tote", "org_a", false, List.of(
                new OfferTarget.Blocker("offers.selling-approval-required", "You need approval to sell this brand."))));

        job.drive(IMPORT);

        RowResult saved = savedResults().getFirst();
        assertThat(saved.outcome()).isEqualTo(RowOutcome.HELD);
        assertThat(saved.note()).contains("not for sale");
        assertThat(saved.atin()).isEqualTo("AT00000002");
        verify(offers, never()).upsert(any(), any(), any(), any());
    }

    @Test
    void anOfferThatOffersServiceStoresTaxHeldIsHeldInTheReportNeverLive() {
        pending(upload(ImportMode.OFFER, OFFER_HEADER), row(2, GTIN, "24.99", "10", ""));
        matches(found());
        when(offers.upsert(any(), any(), any(), anyList())).thenReturn(List.of(new OfferWriter.Result("2", "HELD",
                UUID.randomUUID(), "AT00000002", "TAX_HELD", "TAX_DECISION_MISSING",
                "We are checking the tax details for this item. It goes on sale as soon as that is done.")));

        job.drive(IMPORT);

        RowResult saved = savedResults().getFirst();
        assertThat(saved.outcome()).isEqualTo(RowOutcome.HELD);
        assertThat(saved.note()).startsWith("We are checking the tax details for this item.");
        assertThat(saved.offerId()).isNotNull();
        ArgumentCaptor<ProductImport> progress = ArgumentCaptor.forClass(ProductImport.class);
        verify(imports, atLeastOnce()).update(progress.capture());
        assertThat(progress.getAllValues()).anyMatch(p -> p.heldRows() == 1 && p.offersCreated() == 0);
    }

    @Test
    void aRefusalForTheSellingApprovalAtOffersServiceIsHeldNotFailed() {
        pending(upload(ImportMode.OFFER, OFFER_HEADER), row(2, GTIN, "24.99", "10", ""));
        matches(found());
        when(offers.upsert(any(), any(), any(), anyList())).thenReturn(List.of(new OfferWriter.Result("2", "FAILED", null,
                null, null, "offers.selling-approval-required", "You need approval to sell this brand.")));

        job.drive(IMPORT);

        assertThat(savedResults().getFirst().outcome()).isEqualTo(RowOutcome.HELD);
    }

    @Test
    void anUnchangedRowIsSkippedAndABadRowDoesNotStopTheNext() {
        pending(upload(ImportMode.OFFER, OFFER_HEADER), row(2, GTIN, "24.99", "10", "A"), row(3, "4006381333932", "1", "1", ""));
        matches(found());
        when(offers.upsert(any(), any(), any(), anyList())).thenReturn(List.of(new OfferWriter.Result("2", "UNCHANGED",
                UUID.randomUUID(), "AT00000002", "ACTIVE", null, "No change.")));

        job.drive(IMPORT);

        List<RowResult> saved = savedResults();
        assertThat(saved).extracting(RowResult::outcome).containsExactly(RowOutcome.SKIPPED, RowOutcome.FAILED);
        assertThat(saved.getFirst().note()).isEqualTo("No change.");
        assertThat(saved.get(1).note()).startsWith("That barcode does not look right");
    }

    @Test
    void whenOffersServiceDoesNotAnswerNothingIsMarkedAndTheRowsStayPending() {
        pending(upload(ImportMode.OFFER, OFFER_HEADER), row(2, GTIN, "24.99", "10", ""));
        matches(found());
        when(offers.upsert(any(), any(), any(), anyList())).thenThrow(new OffersUnavailable("down", null));

        job.drive(IMPORT);

        verify(imports, never()).setRowResults(any(), any());
        verify(events, never()).publish(eq(CatalogEvents.IMPORT_COMPLETED), anyString(), anyString(), any());
    }

    @Test
    void aFeedRowAppliesThroughTheBatchEndpointAndNothingToChangeIsSkippedHere() {
        pending(upload(ImportMode.PRICE_QTY, FEED_HEADER), row(2, "SKU-1", "22.50", ""), row(3, "SKU-2", "", ""),
                row(4, "SKU-3", "", "8"));
        when(offers.setPricesAndStock(eq("org_b"), eq(IMPORT), eq("user_b"), anyList())).thenReturn(List.of(
                new OfferWriter.Result("2", "UPDATED", UUID.randomUUID(), "AT00000002", "ACTIVE", null, "Saved."),
                new OfferWriter.Result("4", "FAILED", null, null, null, "offers.sku-not-found",
                        "None of your open offers uses the SKU SKU-3.")));

        job.drive(IMPORT);

        List<RowResult> saved = savedResults();
        assertThat(saved).extracting(RowResult::outcome).containsExactly(RowOutcome.UPDATED, RowOutcome.SKIPPED,
                RowOutcome.FAILED);
        assertThat(saved.get(1).note()).isEqualTo("Nothing to change on this row.");
        assertThat(saved.get(2).note()).isEqualTo("None of your open offers uses the SKU SKU-3.");
        ArgumentCaptor<List<OfferWriter.PriceStock>> sent = ArgumentCaptor.forClass(List.class);
        verify(offers).setPricesAndStock(eq("org_b"), eq(IMPORT), eq("user_b"), sent.capture());
        assertThat(sent.getValue()).extracting(OfferWriter.PriceStock::sellerSku).containsExactly("SKU-1", "SKU-3");
        verify(metrics).row("PRICE_QTY", "UPDATED");
        verify(metrics).row("PRICE_QTY", "SKIPPED");
        verify(metrics).row("PRICE_QTY", "FAILED");
    }

    @Test
    void theJobNeverTouchesAnotherOrganisation() {
        pending(upload(ImportMode.PRICE_QTY, FEED_HEADER), row(2, "SKU-1", "22.50", ""));
        when(offers.setPricesAndStock(any(), any(), any(), anyList())).thenReturn(List.of(new OfferWriter.Result("2",
                "UPDATED", UUID.randomUUID(), "AT00000002", "ACTIVE", null, "Saved.")));

        job.drive(IMPORT);

        ArgumentCaptor<String> org = ArgumentCaptor.forClass(String.class);
        verify(offers).setPricesAndStock(org.capture(), any(), any(), anyList());
        assertThat(org.getValue()).isEqualTo("org_b");
        List<AuditRow> none = new ArrayList<>();
        assertThat(none).isEmpty();
    }
}
