package com.afrotransact.offers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import com.afrotransact.offers.application.OfferBatchService.Outcome;
import com.afrotransact.offers.application.OfferBatchService.PriceStockBatch;
import com.afrotransact.offers.application.OfferBatchService.PriceStockRow;
import com.afrotransact.offers.application.OfferBatchService.RowResult;
import com.afrotransact.offers.application.OfferBatchService.UpsertBatch;
import com.afrotransact.offers.application.OfferBatchService.UpsertRow;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.domain.StockStatus;
import com.afrotransact.offers.port.OfferAuditRepository;
import com.afrotransact.offers.port.OfferBatchMetrics;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.BusinessRuleException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** S393 and S394: the batch endpoints' rules, scope, hold and audit, without a database. */
class OfferBatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    private static final String ATIN = "AT00000002";
    private static final UUID IMPORT = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final ShipFrom HOUSTON = new ShipFrom("Houston", "TX", "US", "77002");

    private final OfferService offerService = mock(OfferService.class);
    private final OfferRepository offers = mock(OfferRepository.class);
    private final OfferAuditRepository audit = mock(OfferAuditRepository.class);
    private final BuyBoxService buyBox = mock(BuyBoxService.class);
    private final PermissionChecker permissions = mock(PermissionChecker.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final OfferBatchMetrics metrics = mock(OfferBatchMetrics.class);
    private OfferBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        asServiceCaller();
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new OfferBatchServiceImpl(offerService, offers, audit, buyBox, permissions,
                mock(ResourceAccessChecker.class), events, metrics, Clock.fixed(NOW, ZoneOffset.UTC), tm);
        when(offers.update(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void asServiceCaller() {
        when(permissions.require(anyString())).thenReturn(new Actor("catalog-service", PrincipalType.SERVICE,
                Optional.empty(), Set.of(), Set.of(OffersPermissions.WRITE)));
    }

    private void asSeller(String org) {
        when(permissions.require(anyString())).thenReturn(new Actor("user_b", PrincipalType.SELLER_MEMBER,
                Optional.of(org), Set.of(), Set.of(OffersPermissions.WRITE)));
    }

    private static Offer offer(String org, String sku, OfferStatus status, Fulfilment fulfilment, long price,
                               Long list, int quantity) {
        return new Offer(UUID.randomUUID(), ATIN, "AT00000001", UUID.randomUUID(), null, UUID.randomUUID(), org,
                SellerKind.SELLER, sku, sku, OfferCondition.NEW, price, list, "USD", 0, fulfilment, quantity, 2, HOUSTON,
                status, true, NOW, NOW, 3, null, null, Map.of(),
                status == OfferStatus.TAX_HELD ? Offer.HOLD_DECISION_MISSING : null,
                status == OfferStatus.TAX_HELD ? null : Offer.TAX_BASIS_SELLER_DECISION);
    }

    private PriceStockBatch priceStock(PriceStockRow... rows) {
        return new PriceStockBatch("org_b", IMPORT, "Ada", List.of(rows));
    }

    private OfferAuditRepository.Entry auditEntry() {
        ArgumentCaptor<OfferAuditRepository.Entry> captor = ArgumentCaptor.forClass(OfferAuditRepository.Entry.class);
        verify(audit).record(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- S394 prices and stock

    @Test
    void aBarcodeNamesTheListingOnlyWhenItMatchesExactlyOneOfTheSellersOwn() {
        Offer o = offer("org_b", "SKU-1", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, null, 5);
        when(offers.findOpenBySellerAndGtin("org_b", "00036000291452")).thenReturn(List.of(o));

        RowResult r = service.setPricesAndStock(priceStock(new PriceStockRow("2", null, null, 900L, null, null,
                "036000291452"))).getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.UPDATED);
        verify(offers).findOpenBySellerAndGtin("org_b", "00036000291452");
        verify(offers, never()).findOpenBySellerSku(anyString(), anyString());
    }

    @Test
    void aBarcodeThatMatchesMoreThanOneListingFailsThePlainRowAndChangesNothing() {
        Offer first = offer("org_b", "SKU-1", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, null, 5);
        Offer second = offer("org_b", "SKU-1-USED", OfferStatus.ACTIVE, Fulfilment.SELLER, 800, null, 2);
        when(offers.findOpenBySellerAndGtin("org_b", "00036000291452")).thenReturn(List.of(first, second));

        RowResult r = service.setPricesAndStock(priceStock(new PriceStockRow("2", null, null, 900L, null, null,
                "00036000291452"))).getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.code()).isEqualTo("offers.barcode-ambiguous");
        assertThat(r.message()).isEqualTo("This barcode matches more than one of your listings. Use your SKU.");
        verify(offers, never()).update(any());
    }

    @Test
    void aBarcodeOfNoListingOfTheSellerLooksLikeAMissingOneWhoeverElseSellsIt() {
        when(offers.findOpenBySellerAndGtin("org_b", "00036000291452")).thenReturn(List.of());

        RowResult r = service.setPricesAndStock(priceStock(new PriceStockRow("2", null, null, 900L, null, null,
                "036000291452"))).getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.code()).isEqualTo("offers.sku-not-found");
        assertThat(r.message()).endsWith("Use your SKU.");
        verify(offers, never()).update(any());
    }

    @Test
    void theSkuWinsOverABarcodeOnTheSameRow() {
        Offer o = offer("org_b", "SKU-1", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, null, 5);
        when(offers.findOpenBySellerSku("org_b", "SKU-1")).thenReturn(List.of(o));

        RowResult r = service.setPricesAndStock(priceStock(new PriceStockRow("2", "SKU-1", null, 900L, null, null,
                "036000291452"))).getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.UPDATED);
        verify(offers, never()).findOpenBySellerAndGtin(anyString(), anyString());
    }

    @Test
    void blankFieldsStayAndChangedFieldsAreAuditedWithTheImportId() {
        Offer o = offer("org_b", "SKU-1", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, 1500L, 5);
        when(offers.findOpenBySellerSku("org_b", "SKU-1")).thenReturn(List.of(o));

        RowResult r = service.setPricesAndStock(priceStock(new PriceStockRow("2", "sku-1", null, 900L, null, null)))
                .getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.UPDATED);
        ArgumentCaptor<Offer> saved = ArgumentCaptor.forClass(Offer.class);
        verify(offers).update(saved.capture());
        assertThat(saved.getValue().priceMinor()).isEqualTo(900);
        assertThat(saved.getValue().listPriceMinor()).isEqualTo(1500L);
        assertThat(saved.getValue().sellerQuantity()).isEqualTo(5);
        OfferAuditRepository.Entry entry = auditEntry();
        assertThat(entry.importId()).isEqualTo(IMPORT);
        assertThat(entry.rowKey()).isEqualTo("2");
        assertThat(entry.action()).isEqualTo("UPDATED");
        assertThat(entry.source()).isEqualTo("IMPORT_PRICE_QTY");
        assertThat(entry.actor()).isEqualTo("catalog-service");
        assertThat(entry.onBehalfOf()).isEqualTo("Ada");
        assertThat(entry.changes()).containsOnlyKeys("priceMinor");
        verify(buyBox).recompute(List.of(ATIN), OffersEvents.OFFER_UPDATED);
    }

    @Test
    void theSameRowAgainChangesNothingAndWritesNoAudit() {
        Offer o = offer("org_b", "SKU-1", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, 1500L, 5);
        when(offers.findOpenBySellerSku("org_b", "SKU-1")).thenReturn(List.of(o));

        RowResult r = service.setPricesAndStock(priceStock(new PriceStockRow("2", "SKU-1", null, 1000L, 1500L, 5)))
                .getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.UNCHANGED);
        verify(offers, never()).update(any());
        verify(audit, never()).record(any());
        verify(buyBox, never()).recompute(any(), anyString());
    }

    @Test
    void aSkuOfAnotherSellerLooksLikeAMissingOne() {
        when(offers.findOpenBySellerSku("org_b", "THEIRS")).thenReturn(List.of());

        RowResult r = service.setPricesAndStock(priceStock(new PriceStockRow("2", "THEIRS", null, 900L, null, null)))
                .getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.code()).isEqualTo("offers.sku-not-found");
        assertThat(r.message()).isEqualTo("None of your open offers uses the SKU THEIRS.");
        verify(offers, never()).update(any());
        // Only the caller's organisation was ever asked for.
        verify(offers).findOpenBySellerSku("org_b", "THEIRS");
    }

    @Test
    void aSellerCannotNameAnotherOrganisation() {
        asSeller("org_b");

        assertThatThrownBy(() -> service.setPricesAndStock(new PriceStockBatch("org_other", IMPORT, null,
                List.of(new PriceStockRow("2", "SKU-1", null, 900L, null, null)))))
                .isInstanceOfSatisfying(ForbiddenActionException.class,
                        e -> assertThat(e.code()).isEqualTo("offers.not-your-organisation"));
        verify(offers, never()).findOpenBySellerSku(anyString(), anyString());
    }

    @Test
    void aSellerActsForItsOwnOrganisationEvenWhenNoneIsNamed() {
        asSeller("org_b");
        Offer o = offer("org_b", "SKU-1", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, null, 5);
        when(offers.findOpenBySellerSku("org_b", "SKU-1")).thenReturn(List.of(o));

        RowResult r = service.setPricesAndStock(new PriceStockBatch(null, IMPORT, null,
                List.of(new PriceStockRow("2", "SKU-1", null, null, null, 9)))).getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.UPDATED);
    }

    @Test
    void aServiceCallerMustNameTheOrganisation() {
        assertThatThrownBy(() -> service.setPricesAndStock(new PriceStockBatch(null, IMPORT, null,
                List.of(new PriceStockRow("2", "SKU-1", null, 900L, null, null)))))
                .isInstanceOf(OfferRuleViolation.class);
    }

    @Test
    void stockOfAFulfilledByAfroTransactOfferComesFromInventory() {
        Offer o = offer("org_b", "SKU-1", OfferStatus.ACTIVE, Fulfilment.AFROTRANSACT, 1000, null, 0);
        when(offers.findOpenBySellerSku("org_b", "SKU-1")).thenReturn(List.of(o));

        RowResult r = service.setPricesAndStock(priceStock(new PriceStockRow("2", "SKU-1", null, null, null, 4)))
                .getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.code()).isEqualTo("offers.stock-managed-by-inventory");
    }

    @Test
    void aPriceAboveTheListPriceIsARowErrorNotADatabaseError() {
        Offer o = offer("org_b", "SKU-1", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, 1500L, 5);
        when(offers.findOpenBySellerSku("org_b", "SKU-1")).thenReturn(List.of(o));

        RowResult r = service.setPricesAndStock(priceStock(new PriceStockRow("2", "SKU-1", null, 1600L, null, null)))
                .getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.message()).startsWith("The list price must be at least the price");
        verify(offers, never()).update(any());
    }

    @Test
    void aTaxHeldOfferGetsItsNewPriceButStaysHeldAndIsReportedHeld() {
        Offer o = offer("org_b", "SKU-1", OfferStatus.TAX_HELD, Fulfilment.SELLER, 1000, null, 5);
        when(offers.findOpenBySellerSku("org_b", "SKU-1")).thenReturn(List.of(o));

        RowResult r = service.setPricesAndStock(priceStock(new PriceStockRow("2", "SKU-1", null, 800L, null, null)))
                .getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.HELD);
        assertThat(r.status()).isEqualTo(OfferStatus.TAX_HELD);
        ArgumentCaptor<Offer> saved = ArgumentCaptor.forClass(Offer.class);
        verify(offers).update(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(OfferStatus.TAX_HELD);
        assertThat(saved.getValue().priceMinor()).isEqualTo(800);
    }

    @Test
    void anOversizedBatchIsRefused() {
        List<PriceStockRow> rows = IntStream.range(0, OfferBatchService.MAX_ROWS + 1)
                .mapToObj(i -> new PriceStockRow("r" + i, "S" + i, null, 100L, null, null)).toList();

        assertThatThrownBy(() -> service.setPricesAndStock(new PriceStockBatch("org_b", IMPORT, null, rows)))
                .isInstanceOf(OfferRuleViolation.class);
    }

    // ---------------------------------------------------------------- S393 offers

    private static UpsertRow upsertRow(long price, Integer quantity) {
        return new UpsertRow("3", ATIN, "AT00000002", OfferCondition.NEW, price, null, 0L, Fulfilment.SELLER, quantity,
                2, HOUSTON, null, null);
    }

    private UpsertBatch upsert(UpsertRow row) {
        return new UpsertBatch("org_b", IMPORT, "Ada", List.of(row));
    }

    @Test
    void aNewOfferIsCreatedThroughTheOfferServiceAndAudited() {
        when(offers.findOpen(anyString(), anyString(), any(), anyBoolean())).thenReturn(Optional.empty());
        Offer created = offer("org_b", "AT00000002", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, null, 5);
        when(offerService.create(any())).thenReturn(view(created));

        RowResult r = service.upsert(upsert(upsertRow(1000, 5))).getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.CREATED);
        ArgumentCaptor<CreateOfferCommand> cmd = ArgumentCaptor.forClass(CreateOfferCommand.class);
        verify(offerService).create(cmd.capture());
        assertThat(cmd.getValue().sellerOrgId()).isEqualTo("org_b");
        assertThat(cmd.getValue().unknownFields()).isEmpty();
        OfferAuditRepository.Entry entry = auditEntry();
        assertThat(entry.action()).isEqualTo("CREATED");
        assertThat(entry.source()).isEqualTo("IMPORT_OFFER");
        assertThat(entry.importId()).isEqualTo(IMPORT);
    }

    @Test
    void anOfferWithoutTheSellersTaxDecisionIsHeldNeverLive() {
        when(offers.findOpen(anyString(), anyString(), any(), anyBoolean())).thenReturn(Optional.empty());
        Offer held = offer("org_b", "AT00000002", OfferStatus.TAX_HELD, Fulfilment.SELLER, 1000, null, 5);
        when(offerService.create(any())).thenReturn(view(held));

        RowResult r = service.upsert(upsert(upsertRow(1000, 5))).getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.HELD);
        assertThat(r.status()).isEqualTo(OfferStatus.TAX_HELD);
        assertThat(r.code()).isEqualTo("TAX_DECISION_MISSING");
        assertThat(r.message()).startsWith("We are checking the tax details for this item.");
        assertThat(auditEntry().action()).isEqualTo("HELD");
    }

    @Test
    void theSameOfferRowAgainIsUnchangedAndCreatesNothing() {
        Offer existing = offer("org_b", "AT00000002", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, null, 5);
        when(offers.findOpen("org_b", ATIN, OfferCondition.NEW, false)).thenReturn(Optional.of(existing));

        RowResult r = service.upsert(upsert(upsertRow(1000, 5))).getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(r.offerId()).isEqualTo(existing.id());
        verify(offerService, never()).create(any());
        verify(offerService, never()).update(any(), any());
        verify(audit, never()).record(any());
    }

    @Test
    void aChangedTermUpdatesTheExistingOfferInsteadOfDuplicatingIt() {
        Offer existing = offer("org_b", "AT00000002", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, null, 5);
        when(offers.findOpen("org_b", ATIN, OfferCondition.NEW, false)).thenReturn(Optional.of(existing));
        Offer changed = offer("org_b", "AT00000002", OfferStatus.ACTIVE, Fulfilment.SELLER, 900, null, 5);
        when(offerService.update(any(), any())).thenReturn(view(changed));

        RowResult r = service.upsert(upsert(upsertRow(900, 5))).getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.UPDATED);
        verify(offerService, never()).create(any());
        OfferAuditRepository.Entry entry = auditEntry();
        assertThat(entry.action()).isEqualTo("UPDATED");
        assertThat(entry.changes()).containsOnlyKeys("priceMinor");
    }

    @Test
    void aRefusalOfTheOfferServiceIsTheRowsMessageAndNothingIsAudited() {
        when(offers.findOpen(anyString(), anyString(), any(), anyBoolean())).thenReturn(Optional.empty());
        when(offerService.create(any())).thenThrow(new BusinessRuleException("offers.selling-approval-required",
                "You need approval to sell this brand. Apply under Listings."));

        RowResult r = service.upsert(upsert(upsertRow(1000, 5))).getFirst();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.code()).isEqualTo("offers.selling-approval-required");
        assertThat(r.message()).isEqualTo("You need approval to sell this brand. Apply under Listings.");
        verify(audit, never()).record(any());
    }

    @Test
    void oneBadRowDoesNotStopTheNextOne() {
        when(offers.findOpen(anyString(), anyString(), any(), anyBoolean())).thenReturn(Optional.empty());
        Offer created = offer("org_b", "AT00000002", OfferStatus.ACTIVE, Fulfilment.SELLER, 1000, null, 5);
        when(offerService.create(any())).thenThrow(new OfferRuleViolation("The offer is not valid"))
                .thenReturn(view(created));

        List<RowResult> results = service.upsert(new UpsertBatch("org_b", IMPORT, null,
                List.of(upsertRow(1000, 5), upsertRow(1000, 5))));

        assertThat(results).extracting(RowResult::outcome).containsExactly(Outcome.FAILED, Outcome.CREATED);
    }

    private static OfferView view(Offer o) {
        return new OfferView(o, "Title", 0, StockStatus.of(o.sellerQuantity(), 5), 1, false);
    }
}
