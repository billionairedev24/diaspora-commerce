package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.offers.application.OfferBatchService.Outcome;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.port.CatalogClient.CatalogUnavailableException;
import com.afrotransact.offers.port.OfferAuditRepository;
import com.afrotransact.offers.port.OfferBatchMetrics;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.DomainException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class OfferBatchServiceImpl implements OfferBatchService {

    private static final Logger LOG = LoggerFactory.getLogger(OfferBatchServiceImpl.class);
    private static final String SOURCE_OFFER = "IMPORT_OFFER";
    private static final String SOURCE_PRICE_QTY = "IMPORT_PRICE_QTY";
    private static final long MAX_PRICE_MINOR = 100_000_000L;
    static final String HELD_MESSAGE = "We are checking the tax details for this item. It goes on sale as soon as that is done.";

    private final OfferService offerService;
    private final OfferRepository offers;
    private final OfferAuditRepository audit;
    private final BuyBoxService buyBox;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final DomainEventPublisher events;
    private final OfferBatchMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate tx;

    OfferBatchServiceImpl(OfferService offerService, OfferRepository offers, OfferAuditRepository audit,
                          BuyBoxService buyBox, PermissionChecker permissions, ResourceAccessChecker access,
                          DomainEventPublisher events, OfferBatchMetrics metrics, Clock clock,
                          PlatformTransactionManager transactions) {
        this.offerService = offerService;
        this.offers = offers;
        this.audit = audit;
        this.buyBox = buyBox;
        this.permissions = permissions;
        this.access = access;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    // ------------------------------------------------------------------ create or change offers

    @Override
    public List<RowResult> upsert(UpsertBatch batch) {
        Actor actor = permissions.require(OffersPermissions.WRITE);
        String org = scope(actor, batch.sellerOrgId());
        requireSize(batch.rows().size());
        List<RowResult> out = new ArrayList<>();
        for (UpsertRow row : batch.rows()) {
            RowResult result = upsertRow(actor, org, batch, row);
            metrics.row("UPSERT", result.outcome().name());
            out.add(result);
        }
        return out;
    }

    private RowResult upsertRow(Actor actor, String org, UpsertBatch batch, UpsertRow row) {
        try {
            OfferCondition condition = row.condition() == null ? OfferCondition.NEW : row.condition();
            Fulfilment fulfilment = row.fulfilment();
            if (fulfilment == null) {
                return failed(row.rowKey(), row.atin(), "offers.rule-violation", "Choose how the item is fulfilled.");
            }
            Offer existing = offers.findOpen(org, row.atin(), condition, fulfilment.fulfilledByAfroTransact())
                    .orElse(null);
            if (existing == null) {
                OfferView created = offerService.create(new CreateOfferCommand(row.atin(), null, org, row.sellerSku(),
                        null, condition, row.priceMinor(), row.listPriceMinor(), "USD",
                        row.shippingMinor() == null ? 0 : row.shippingMinor(), fulfilment, row.quantity(),
                        row.handlingDays(), row.shipFrom(), row.conditionNote(), row.packaging(), Map.of(), Set.of()));
                Offer o = created.offer();
                boolean held = o.status() == OfferStatus.TAX_HELD;
                recordAudit(actor, batch.importId(), batch.uploadedBy(), SOURCE_OFFER, row.rowKey(), o,
                        held ? "HELD" : "CREATED", Map.of("priceMinor", o.priceMinor(), "quantity", o.sellerQuantity(),
                                "condition", o.condition().name(), "fulfilment", o.fulfilment().name()));
                return held ? held(row.rowKey(), o) : ok(Outcome.CREATED, row.rowKey(), o);
            }
            Map<String, Object> changes = changes(existing, row);
            if (changes.isEmpty()) {
                return existing.status() == OfferStatus.TAX_HELD ? held(row.rowKey(), existing)
                        : ok(Outcome.UNCHANGED, row.rowKey(), existing);
            }
            OfferView updated = offerService.update(existing.id(), new UpdateOfferCommand(null, row.priceMinor(),
                    row.listPriceMinor(), row.shippingMinor(), fulfilment, row.quantity(), row.handlingDays(),
                    row.shipFrom(), null, row.conditionNote(), row.packaging(), Set.of()));
            Offer o = updated.offer();
            recordAudit(actor, batch.importId(), batch.uploadedBy(), SOURCE_OFFER, row.rowKey(), o, "UPDATED", changes);
            return o.status() == OfferStatus.TAX_HELD ? held(row.rowKey(), o)
                    : ok(Outcome.UPDATED, row.rowKey(), o);
        } catch (RuntimeException ex) {
            return failure(row.rowKey(), row.atin(), ex);
        }
    }

    /** Terms the row would change on the stored offer, as field to {from, to}; empty when nothing differs. */
    private static Map<String, Object> changes(Offer o, UpsertRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (row.priceMinor() != o.priceMinor()) {
            out.put("priceMinor", Map.of("from", o.priceMinor(), "to", row.priceMinor()));
        }
        if (row.listPriceMinor() != null && !row.listPriceMinor().equals(o.listPriceMinor() == null ? 0L
                : o.listPriceMinor())) {
            out.put("listPriceMinor", Map.of("from", o.listPriceMinor() == null ? 0L : o.listPriceMinor(), "to",
                    row.listPriceMinor()));
        }
        if (row.shippingMinor() != null && row.shippingMinor() != o.shippingMinor()) {
            out.put("shippingMinor", Map.of("from", o.shippingMinor(), "to", row.shippingMinor()));
        }
        if (row.fulfilment() != null && row.fulfilment() != o.fulfilment()) {
            out.put("fulfilment", Map.of("from", o.fulfilment().name(), "to", row.fulfilment().name()));
        }
        if (row.quantity() != null && o.fulfilment().sellerStock() && row.quantity() != o.sellerQuantity()) {
            out.put("quantity", Map.of("from", o.sellerQuantity(), "to", row.quantity()));
        }
        if (row.handlingDays() != null && row.handlingDays() != o.handlingDays()) {
            out.put("handlingDays", Map.of("from", o.handlingDays(), "to", row.handlingDays()));
        }
        if (row.shipFrom() != null && !row.shipFrom().equals(o.shipFrom()) && row.shipFrom().country() != null) {
            out.put("shipFrom", Map.of("from", Objects.toString(o.shipFrom() == null ? null : o.shipFrom().label(), ""),
                    "to", row.shipFrom().label()));
        }
        if (row.conditionNote() != null && !row.conditionNote().strip().equals(Objects.toString(o.conditionNote(), ""))) {
            out.put("conditionNote", Map.of("from", Objects.toString(o.conditionNote(), ""), "to",
                    row.conditionNote().strip()));
        }
        return out;
    }

    // ------------------------------------------------------------------ prices and stock

    @Override
    public List<RowResult> setPricesAndStock(PriceStockBatch batch) {
        Actor actor = permissions.require(OffersPermissions.WRITE);
        String org = scope(actor, batch.sellerOrgId());
        requireSize(batch.rows().size());
        List<RowResult> out = new ArrayList<>();
        Set<String> changedAtins = new LinkedHashSet<>();
        for (PriceStockRow row : batch.rows()) {
            RowResult result;
            try {
                result = Objects.requireNonNull(tx.execute(s -> priceStockRow(actor, org, batch, row, changedAtins)));
            } catch (RuntimeException ex) {
                result = failure(row.rowKey(), row.atin(), ex);
            }
            metrics.row("PRICES_AND_STOCK", result.outcome().name());
            out.add(result);
        }
        if (!changedAtins.isEmpty()) {
            try {
                buyBox.recompute(List.copyOf(changedAtins), OffersEvents.OFFER_UPDATED);
            } catch (RuntimeException ex) {
                // The offers are saved; the buy box is recomputed by the next change or the scheduled refresh.
                LOG.warn("Buy box recompute after a bulk price and stock batch failed for {} ATINs", changedAtins.size(), ex);
            }
        }
        return out;
    }

    private RowResult priceStockRow(Actor actor, String org, PriceStockBatch batch, PriceStockRow row,
                                    Set<String> changedAtins) {
        String sku = row.sellerSku() == null ? "" : row.sellerSku().strip().toUpperCase(Locale.ROOT);
        String atin = row.atin() == null ? "" : row.atin().strip().toUpperCase(Locale.ROOT);
        if (sku.isEmpty() && atin.isEmpty()) {
            return failed(row.rowKey(), null, "offers.rule-violation", "Give the seller SKU or the ATIN.");
        }
        Map<String, String> problems = new LinkedHashMap<>();
        if (row.priceMinor() != null && (row.priceMinor() <= 0 || row.priceMinor() > MAX_PRICE_MINOR)) {
            problems.put("price", "The price must be above zero and below " + MAX_PRICE_MINOR / 100);
        }
        if (row.listPriceMinor() != null && (row.listPriceMinor() < 0 || row.listPriceMinor() > MAX_PRICE_MINOR)) {
            problems.put("listPrice", "The list price is not valid");
        }
        if (row.quantity() != null && (row.quantity() < 0 || row.quantity() > OfferServiceImpl.MAX_SELLER_QUANTITY)) {
            problems.put("quantity", "The quantity must be between 0 and " + OfferServiceImpl.MAX_SELLER_QUANTITY);
        }
        if (!problems.isEmpty()) {
            return failed(row.rowKey(), atin.isEmpty() ? null : atin, "offers.rule-violation",
                    String.join(". ", problems.values()) + ".");
        }
        // Only this organisation's open offers are ever read: another seller's SKU looks exactly like a missing one.
        List<Offer> owned = sku.isEmpty() ? offers.findOpenBySellerAndAtin(org, atin) : offers.findOpenBySellerSku(org, sku);
        if (!sku.isEmpty() && !atin.isEmpty()) {
            owned = owned.stream().filter(o -> o.atin().equals(atin)).toList();
        }
        if (owned.isEmpty()) {
            return failed(row.rowKey(), atin.isEmpty() ? null : atin, "offers.sku-not-found",
                    sku.isEmpty() ? "You have no open offer on " + atin + "."
                            : "None of your open offers uses the SKU " + sku + ".");
        }
        if (row.quantity() != null && owned.stream().noneMatch(o -> o.fulfilment().sellerStock())) {
            return failed(row.rowKey(), owned.getFirst().atin(), "offers.stock-managed-by-inventory",
                    "This item is Fulfilled by AfroTransact, so its stock comes from the fulfilment centre. "
                            + "Leave the quantity blank.");
        }
        Instant now = Instant.now(clock);
        Offer first = null;
        boolean anyChange = false;
        boolean anyHeld = false;
        for (Offer o : owned) {
            Offer.Terms t = o.terms();
            long price = row.priceMinor() == null ? t.priceMinor() : row.priceMinor();
            Long list = row.listPriceMinor() == null ? t.listPriceMinor()
                    : row.listPriceMinor() == 0 ? null : row.listPriceMinor();
            if (list != null && list < price) {
                return failed(row.rowKey(), o.atin(), "offers.rule-violation", "The list price must be at least the "
                        + "price. Change both, or leave the list price at 0 to remove it.");
            }
            int quantity = row.quantity() != null && o.fulfilment().sellerStock() ? row.quantity() : t.sellerQuantity();
            first = first == null ? o : first;
            anyHeld |= o.status() == OfferStatus.TAX_HELD;
            Map<String, Object> diff = new LinkedHashMap<>();
            if (price != t.priceMinor()) {
                diff.put("priceMinor", Map.of("from", t.priceMinor(), "to", price));
            }
            if (!Objects.equals(list, t.listPriceMinor())) {
                diff.put("listPriceMinor", Map.of("from", t.listPriceMinor() == null ? 0L : t.listPriceMinor(), "to",
                        list == null ? 0L : list));
            }
            if (quantity != t.sellerQuantity()) {
                diff.put("quantity", Map.of("from", t.sellerQuantity(), "to", quantity));
            }
            if (diff.isEmpty()) {
                continue;
            }
            // revise keeps the status, the tax basis and the hold: this path can never put an offer on sale.
            Offer updated = offers.update(o.revise(new Offer.Terms(t.condition(), price, list, t.shippingMinor(),
                    t.fulfilment(), quantity, t.handlingDays(), t.shipFrom(), t.inventorySku()), now));
            events.publish(OffersEvents.OFFER_UPDATED, OffersEvents.AGGREGATE, updated.atin(),
                    OfferEventPayload.of(updated));
            recordAudit(actor, batch.importId(), batch.uploadedBy(), SOURCE_PRICE_QTY, row.rowKey(), updated, "UPDATED",
                    diff);
            changedAtins.add(updated.atin());
            anyChange = true;
        }
        UUID offerId = first.id();
        if (anyHeld) {
            return new RowResult(row.rowKey(), Outcome.HELD, offerId, first.atin(), OfferStatus.TAX_HELD,
                    Offer.HOLD_DECISION_MISSING, HELD_MESSAGE);
        }
        return new RowResult(row.rowKey(), anyChange ? Outcome.UPDATED : Outcome.UNCHANGED, offerId, first.atin(),
                first.status(), null, anyChange ? "Saved." : "No change.");
    }

    // ------------------------------------------------------------------ helpers

    /** Who the rows are for: a seller's own organisation only; staff and service callers name it. */
    private String scope(Actor actor, String requested) {
        String org;
        if (Actors.isStaff(actor)) {
            if (requested == null || requested.isBlank()) {
                throw new OfferRuleViolation("Name the seller organisation", Map.of("sellerOrgId", "Required"));
            }
            org = requested.strip();
        } else {
            org = actor.orgId().orElseThrow(() -> new OfferRuleViolation(
                    "Your account is not linked to a seller organisation"));
            if (requested != null && !requested.isBlank() && !requested.strip().equals(org)) {
                throw new ForbiddenActionException("offers.not-your-organisation",
                        "You can only change your own organisation's offers");
            }
        }
        access.requireForOrg(OffersPermissions.WRITE, OffersRelations.ORG_MANAGE_PRODUCTS, org);
        return org;
    }

    private static void requireSize(int rows) {
        if (rows == 0 || rows > MAX_ROWS) {
            throw new OfferRuleViolation("Send between 1 and " + MAX_ROWS + " rows per call",
                    Map.of("rows", "Between 1 and " + MAX_ROWS));
        }
    }

    private void recordAudit(Actor actor, UUID importId, String uploadedBy, String source, String rowKey, Offer o,
                             String action, Map<String, Object> changes) {
        audit.record(new OfferAuditRepository.Entry(UUID.randomUUID(), o.id(), o.sellerOrgId(), o.atin(), action,
                source, importId, rowKey, actor.subject(), uploadedBy, changes, Instant.now(clock)));
    }

    private static RowResult ok(Outcome outcome, String rowKey, Offer o) {
        return new RowResult(rowKey, outcome, o.id(), o.atin(), o.status(), null,
                outcome == Outcome.UNCHANGED ? "No change." : "Saved.");
    }

    private static RowResult held(String rowKey, Offer o) {
        return new RowResult(rowKey, Outcome.HELD, o.id(), o.atin(), o.status(),
                o.heldReason() == null ? Offer.HOLD_DECISION_MISSING : o.heldReason(), HELD_MESSAGE);
    }

    private static RowResult failed(String rowKey, String atin, String code, String message) {
        return new RowResult(rowKey, Outcome.FAILED, null, atin, null, code, message);
    }

    /** A refused row says why in plain words; the stable code is for the caller, never shown alone. */
    private static RowResult failure(String rowKey, String atin, RuntimeException ex) {
        if (ex instanceof OfferRuleViolation v) {
            String fields = v.fieldErrors().isEmpty() ? "" : " " + String.join(". ", v.fieldErrors().values()) + ".";
            return failed(rowKey, atin, "offers.rule-violation", v.getMessage() + (v.getMessage().endsWith(".") ? "" : ".")
                    + fields);
        }
        if (ex instanceof DomainException d) {
            return failed(rowKey, atin, d.code(), d.getMessage());
        }
        if (ex instanceof CatalogUnavailableException) {
            return failed(rowKey, atin, "offers.catalog-unavailable", "We could not check this product right now. "
                    + "Upload the file again in a few minutes.");
        }
        LOG.warn("Bulk offer row {} failed unexpectedly", rowKey, ex);
        return failed(rowKey, atin, "offers.unexpected", "This row could not be saved. Upload the file again.");
    }
}
