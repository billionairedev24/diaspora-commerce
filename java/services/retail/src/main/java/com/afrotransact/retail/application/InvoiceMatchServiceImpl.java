package com.afrotransact.retail.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.retail.domain.FxRate;
import com.afrotransact.retail.domain.GlPostingRules;
import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.InvoiceLine;
import com.afrotransact.retail.domain.InvoiceStatus;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalSource;
import com.afrotransact.retail.domain.MatchResult;
import com.afrotransact.retail.domain.PoLine;
import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.SupplierInvoice;
import com.afrotransact.retail.domain.ThreeWayMatcher;
import com.afrotransact.retail.port.FxRateProvider;
import com.afrotransact.retail.port.IdempotencyStore;
import com.afrotransact.retail.port.InvoiceRepository;
import com.afrotransact.retail.port.Page;
import com.afrotransact.retail.port.PurchaseOrderRepository;
import com.afrotransact.retail.port.ReceiptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InvoiceMatchServiceImpl implements InvoiceMatchService {

    private static final String SCOPE = "invoice";

    private final InvoiceRepository invoices;
    private final PurchaseOrderRepository orders;
    private final ReceiptRepository receipts;
    private final ThreeWayMatcher matcher;
    private final FxRateProvider fxRates;
    private final GlPostingRules gl;
    private final JournalPoster journal;
    private final IdempotencyStore idempotency;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final RetailSettings settings;
    private final Clock clock;

    InvoiceMatchServiceImpl(InvoiceRepository invoices, PurchaseOrderRepository orders, ReceiptRepository receipts,
                            ThreeWayMatcher matcher, FxRateProvider fxRates, GlPostingRules gl, JournalPoster journal,
                            IdempotencyStore idempotency, PermissionChecker permissions, DomainEventPublisher events,
                            RetailSettings settings, Clock clock) {
        this.invoices = invoices;
        this.orders = orders;
        this.receipts = receipts;
        this.matcher = matcher;
        this.fxRates = fxRates;
        this.gl = gl;
        this.journal = journal;
        this.idempotency = idempotency;
        this.permissions = permissions;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SupplierInvoice register(RegisterInvoiceCommand c, String idempotencyKey) {
        Actor actor = permissions.require(RetailPermissions.FINANCE);
        Optional<StoredInvoice> prior = idempotency.claim(SCOPE, idempotencyKey, Fingerprint.of(c),
                StoredInvoice.class);
        if (prior.isPresent()) {
            return find(prior.get().invoiceId());
        }
        if (c.invoiceDate() == null) {
            throw new RetailRuleViolation("retail.invoice-invalid", "Invoice date is required");
        }
        PurchaseOrder po = orders.findById(c.poId()).orElseThrow(() -> new RetailRuleViolation(
                "retail.po-not-found", "Purchase order " + c.poId() + " not found"));
        if (po.status() == PoStatus.DRAFT || po.status() == PoStatus.PENDING_APPROVAL
                || po.status() == PoStatus.CANCELLED || po.status() == PoStatus.CLOSED) {
            throw new RetailRuleViolation("retail.po-not-invoiceable", "PO " + po.number() + " is " + po.status());
        }
        String number = c.invoiceNumber() == null ? null : c.invoiceNumber().trim();
        invoices.findBySupplierAndNumber(po.supplierId(), number).ifPresent(dup -> {
            throw new ConflictException("retail.invoice-duplicate", "Invoice " + number
                    + " of this supplier is already registered");
        });
        String currency = c.currency() == null ? po.currency() : c.currency();
        FxRate fx = invoiceFx(po, currency, c.fxRate(), c);
        MatchResult result = matcher.match(po, fx.currency(), c.lines(), openClaims(po), settings.matchTolerance());
        SupplierInvoice invoice = invoices.insert(SupplierInvoice.register(UUID.randomUUID(), po, number,
                c.invoiceDate(), currency, fx, c.lines(), result, actor.subject(), Instant.now(clock)));
        SupplierInvoice out = afterMatch(invoice, po, actor);
        idempotency.complete(SCOPE, idempotencyKey, new StoredInvoice(out.id()));
        return out;
    }

    @Override
    @Transactional
    public SupplierInvoice rematch(UUID id) {
        Actor actor = permissions.require(RetailPermissions.FINANCE);
        SupplierInvoice invoice = find(id);
        PurchaseOrder po = orders.findById(invoice.poId()).orElseThrow();
        MatchResult result = matcher.match(po, invoice.currency(), invoice.lines(), openClaims(po),
                settings.matchTolerance());
        SupplierInvoice updated = invoices.update(invoice.rematch(result, Instant.now(clock)));
        return afterMatch(updated, po, actor);
    }

    @Override
    @Transactional
    public SupplierInvoice approveException(UUID id, String note) {
        Actor actor = permissions.require(RetailPermissions.FINANCE);
        SupplierInvoice invoice = find(id);
        PurchaseOrder po = orders.findById(invoice.poId()).orElseThrow();
        SupplierInvoice approved = invoices.update(invoice.approveException(actor.subject(), note,
                Instant.now(clock)));
        publish(RetailEvents.INVOICE_APPROVED, approved, po);
        return post(approved, po, actor);
    }

    @Override
    @Transactional
    public SupplierInvoice reject(UUID id, String note) {
        Actor actor = permissions.require(RetailPermissions.FINANCE);
        SupplierInvoice invoice = find(id);
        PurchaseOrder po = orders.findById(invoice.poId()).orElseThrow();
        SupplierInvoice rejected = invoices.update(invoice.reject(actor.subject(), note, Instant.now(clock)));
        publish(RetailEvents.INVOICE_REJECTED, rejected, po);
        return rejected;
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierInvoice get(UUID id) {
        permissions.require(RetailPermissions.READ);
        return find(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierInvoice> list(InvoiceStatus status, UUID poId, int page, int size) {
        permissions.require(RetailPermissions.READ);
        return invoices.page(status, poId, Math.max(0, page), Math.clamp(size, 1, 200));
    }

    private SupplierInvoice afterMatch(SupplierInvoice invoice, PurchaseOrder po, Actor actor) {
        return switch (invoice.status()) {
            case MATCHED -> {
                publish(RetailEvents.INVOICE_MATCHED, invoice, po);
                yield post(invoice, po, actor);
            }
            case EXCEPTION -> {
                publish(RetailEvents.MATCH_EXCEPTION, invoice, po);
                yield invoice;
            }
            case ON_HOLD -> {
                publish(RetailEvents.INVOICE_HELD, invoice, po);
                yield invoice;
            }
            default -> invoice;
        };
    }

    /**
     * Payable invoice: record invoiced quantities on the PO and post Dr 2110 GR/IR (PO price, PO rate), purchase
     * price variance (invoice rate), FX difference, Cr 2100 AP (invoice rate).
     */
    private SupplierInvoice post(SupplierInvoice invoice, PurchaseOrder po, Actor actor) {
        Instant now = Instant.now(clock);
        long grirLocal = 0;
        long varianceLocal = 0;
        for (InvoiceLine l : invoice.lines()) {
            PoLine pl = po.line(l.poLineNo());
            grirLocal = Math.addExact(grirLocal, Math.multiplyExact((long) l.qty(), pl.unitCostMinor()));
            varianceLocal = Math.addExact(varianceLocal,
                    Math.multiplyExact((long) l.qty(), l.unitPriceMinor() - pl.unitCostMinor()));
        }
        long grir = po.fx().toBase(grirLocal);
        long variance = invoice.fx().toBase(varianceLocal);
        long payable = invoice.fx().toBase(invoice.totalMinor());
        JournalEntry entry = journal.post(JournalSource.INVOICE_MATCH, invoice.invoiceNumber(),
                "Supplier invoice " + invoice.invoiceNumber() + " matched to " + po.number(),
                gl.invoiceMatched(grir, variance, payable), actor.subject());
        orders.update(po.recordInvoiced(invoice.qtyByPoLine(), invoice.invoiceNumber(), actor.subject(), now));
        return invoices.update(invoice.posted(entry.id(), now));
    }

    private FxRate invoiceFx(PurchaseOrder po, String currency, BigDecimal given, RegisterInvoiceCommand c) {
        String base = settings.baseCurrency();
        String code = currency.trim().toUpperCase(Locale.ROOT);
        if (code.equals(base)) {
            return FxRate.identity(base, c.invoiceDate());
        }
        if (given != null) {
            return new FxRate(code, base, given, c.invoiceDate());
        }
        Optional<BigDecimal> configured = fxRates.unitsPerBase(code, c.invoiceDate());
        if (configured.isPresent()) {
            return new FxRate(code, base, configured.get(), c.invoiceDate());
        }
        if (code.equals(po.currency())) {
            return po.fx();
        }
        throw new RetailRuleViolation("retail.fx-missing", "No " + code + "/" + base + " rate for "
                + c.invoiceDate() + "; enter the invoice rate");
    }

    private long openClaims(PurchaseOrder po) {
        return receipts.findByPo(po.id()).stream().mapToLong(GoodsReceipt::openClaims).sum();
    }

    private void publish(String type, SupplierInvoice invoice, PurchaseOrder po) {
        events.publish(type, RetailEvents.AGGREGATE, invoice.id().toString(),
                RetailEventPayloads.InvoiceEvent.of(invoice, po.createdBy()));
    }

    private SupplierInvoice find(UUID id) {
        return invoices.findById(id).orElseThrow(() -> new NotFoundException("retail.invoice-not-found",
                "Supplier invoice not found"));
    }

    /** Response remembered for an Idempotency-Key. */
    record StoredInvoice(UUID invoiceId) {
    }
}
