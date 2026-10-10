package com.afrotransact.retail.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.retail.domain.ApprovalPolicy;
import com.afrotransact.retail.domain.ApprovalTier;
import com.afrotransact.retail.domain.FxRate;
import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.InvoiceStatus;
import com.afrotransact.retail.domain.PoLine;
import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierInvoice;
import com.afrotransact.retail.domain.SupplierItem;
import com.afrotransact.retail.port.FxRateProvider;
import com.afrotransact.retail.port.InvoiceRepository;
import com.afrotransact.retail.port.ItemRepository;
import com.afrotransact.retail.port.Page;
import com.afrotransact.retail.port.PurchaseOrderDocuments;
import com.afrotransact.retail.port.PurchaseOrderRepository;
import com.afrotransact.retail.port.ReceiptRepository;
import com.afrotransact.retail.port.ReferenceIssuer;
import com.afrotransact.retail.port.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PurchaseOrderServiceImpl implements PurchaseOrderService {

    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final ItemRepository items;
    private final InvoiceRepository invoices;
    private final ReceiptRepository receipts;
    private final ReferenceIssuer refs;
    private final FxRateProvider fxRates;
    private final ApprovalPolicy approvals;
    private final PurchaseOrderDocuments documents;
    private final PoDispatchQueue dispatchQueue;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final RetailSettings settings;
    private final Clock clock;

    PurchaseOrderServiceImpl(PurchaseOrderRepository orders, SupplierRepository suppliers, ItemRepository items,
                             InvoiceRepository invoices, ReceiptRepository receipts, ReferenceIssuer refs,
                             FxRateProvider fxRates, ApprovalPolicy approvals, PurchaseOrderDocuments documents,
                             PoDispatchQueue dispatchQueue, PermissionChecker permissions, DomainEventPublisher events,
                             RetailSettings settings, Clock clock) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.items = items;
        this.invoices = invoices;
        this.receipts = receipts;
        this.refs = refs;
        this.fxRates = fxRates;
        this.approvals = approvals;
        this.documents = documents;
        this.dispatchQueue = dispatchQueue;
        this.permissions = permissions;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PurchaseOrder create(CreatePoCommand c) {
        Actor actor = permissions.require(RetailPermissions.PO_WRITE);
        Supplier supplier = suppliers.findById(c.supplierId()).orElseThrow(() -> new RetailRuleViolation(
                "retail.supplier-not-found", "Supplier " + c.supplierId() + " not found"));
        Instant now = Instant.now(clock);
        LocalDate orderDate = c.orderDate() == null ? LocalDate.ofInstant(now, ZoneOffset.UTC) : c.orderDate();
        FxRate fx = fx(supplier.currency(), c.fxRate(), orderDate);
        List<PoLine> lines = lines(supplier, c.lines());
        PurchaseOrder draft = PurchaseOrder.draft(UUID.randomUUID(), refs.nextPurchaseOrderNumber(), supplier, fx,
                c.destinationNodeId(), c.incoterm(), orderDate, c.expectedDate(), lines, ApprovalTier.STANDARD,
                actor.subject(), now);
        PurchaseOrder created = orders.insert(withTier(draft, actor, now));
        publish(RetailEvents.PO_CREATED, created, actor, null);
        return created;
    }

    @Override
    @Transactional
    public PurchaseOrder replaceLines(UUID id, List<PoLineInput> input, Long expectedVersion) {
        Actor actor = permissions.require(RetailPermissions.PO_WRITE);
        PurchaseOrder po = find(id, expectedVersion);
        Supplier supplier = supplier(po.supplierId());
        List<PoLine> lines = lines(supplier, input);
        PurchaseOrder edited = po.replaceLines(lines, ApprovalTier.STANDARD, actor.subject(), Instant.now(clock));
        PurchaseOrder updated = orders.update(withTier(edited, actor, Instant.now(clock)));
        publish(RetailEvents.PO_UPDATED, updated, actor, null);
        return updated;
    }

    @Override
    @Transactional
    public PurchaseOrder submit(UUID id, Long expectedVersion) {
        Actor actor = permissions.require(RetailPermissions.PO_WRITE);
        PurchaseOrder po = find(id, expectedVersion);
        supplier(po.supplierId()).ensureCanReceivePurchaseOrders();
        return save(RetailEvents.PO_SUBMITTED, po.submit(actor.subject(), Instant.now(clock)), actor, null);
    }

    @Override
    @Transactional
    public PurchaseOrder approve(UUID id, String note, Long expectedVersion) {
        Actor actor = permissions.require(RetailPermissions.PO_APPROVE);
        PurchaseOrder po = find(id, expectedVersion);
        ApprovalTier tier = approvals.tierFor(po.totalBaseMinor());
        if (tier == ApprovalTier.FINANCE && !actor.can(RetailPermissions.FINANCE)) {
            throw new ForbiddenActionException("retail.finance-approval-required", "PO " + po.number() + " is worth "
                    + po.totalBaseMinor() + " " + settings.baseCurrency() + " minor units, above the threshold: "
                    + "Finance (" + RetailPermissions.FINANCE + ") must approve");
        }
        supplier(po.supplierId()).ensureCanReceivePurchaseOrders();
        return save(RetailEvents.PO_APPROVED, po.approve(actor.subject(), note, Instant.now(clock)), actor, note);
    }

    @Override
    @Transactional
    public PurchaseOrder sendBack(UUID id, String note, Long expectedVersion) {
        Actor actor = permissions.require(RetailPermissions.PO_APPROVE);
        PurchaseOrder po = find(id, expectedVersion);
        return save(RetailEvents.PO_SENT_BACK, po.sendBack(actor.subject(), note, Instant.now(clock)), actor, note);
    }

    @Override
    @Transactional
    public PurchaseOrder send(UUID id, Long expectedVersion) {
        Actor actor = permissions.require(RetailPermissions.PO_WRITE);
        PurchaseOrder po = find(id, expectedVersion);
        Instant now = Instant.now(clock);
        PurchaseOrder sent = save(RetailEvents.PO_SENT, po.send(actor.subject(), now), actor, null);
        // S225: email the PDF to the supplier contact and, for EDI suppliers, transmit the 850 (dispatch job).
        dispatchQueue.enqueue(sent, supplier(sent.supplierId()), now);
        return sent;
    }

    @Override
    @Transactional
    public PurchaseOrder confirm(UUID id, String note, Long expectedVersion) {
        Actor actor = permissions.require(RetailPermissions.PO_WRITE);
        PurchaseOrder po = find(id, expectedVersion);
        return save(RetailEvents.PO_CONFIRMED, po.confirm(actor.subject(), note, Instant.now(clock)), actor, note);
    }

    @Override
    @Transactional
    public PurchaseOrder cancel(UUID id, String reason, Long expectedVersion) {
        Actor actor = permissions.require(RetailPermissions.PO_WRITE);
        PurchaseOrder po = find(id, expectedVersion);
        return save(RetailEvents.PO_CANCELLED, po.cancel(actor.subject(), reason, Instant.now(clock)), actor, reason);
    }

    @Override
    @Transactional
    public PurchaseOrder close(UUID id, Long expectedVersion) {
        Actor actor = permissions.require(RetailPermissions.FINANCE);
        PurchaseOrder po = find(id, expectedVersion);
        long openClaims = receipts.findByPo(po.id()).stream().mapToLong(GoodsReceipt::openClaims).sum();
        if (openClaims > 0) {
            throw new RetailRuleViolation("retail.claims-open", openClaims + " receiving claim(s) on " + po.number()
                    + " are still open; settle them before releasing payment");
        }
        List<SupplierInvoice> poInvoices = invoices.findByPo(po.id());
        List<String> pending = poInvoices.stream()
                .filter(i -> i.status() == InvoiceStatus.EXCEPTION || i.status() == InvoiceStatus.ON_HOLD)
                .map(SupplierInvoice::invoiceNumber).toList();
        if (!pending.isEmpty()) {
            throw new RetailRuleViolation("retail.invoices-unresolved", "Resolve invoices " + pending
                    + " (exception or on hold) before closing");
        }
        Instant now = Instant.now(clock);
        PurchaseOrder closed = save(RetailEvents.PO_CLOSED, po.close(actor.subject(), now), actor, null);
        List<RetailEventPayloads.PayableInvoice> released = new ArrayList<>();
        for (SupplierInvoice inv : poInvoices) {
            if (inv.status().payable()) {
                invoices.update(inv.releasePayment(now));
                released.add(new RetailEventPayloads.PayableInvoice(inv.id(), inv.invoiceNumber(), inv.currency(),
                        inv.totalMinor()));
            }
        }
        long total = released.stream().mapToLong(RetailEventPayloads.PayableInvoice::amountMinor).sum();
        events.publish(RetailEvents.PAYMENT_RELEASED, RetailEvents.AGGREGATE, closed.id().toString(),
                new RetailEventPayloads.PaymentReleased(closed.id(), closed.number(), closed.supplierId(),
                        closed.supplierCode(), closed.currency(), total, released));
        return closed;
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseOrder get(UUID id) {
        permissions.require(RetailPermissions.READ);
        return find(id, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PurchaseOrder> list(PoStatus status, UUID supplierId, int page, int size) {
        permissions.require(RetailPermissions.READ);
        return orders.page(status, supplierId, Math.max(0, page), Math.clamp(size, 1, 200));
    }

    @Override
    @Transactional(readOnly = true)
    public String edi850(UUID id) {
        permissions.require(RetailPermissions.READ);
        PurchaseOrder po = find(id, null);
        if (po.status() == PoStatus.DRAFT || po.status() == PoStatus.PENDING_APPROVAL) {
            throw new RetailRuleViolation("retail.po-not-approved", "PO " + po.number()
                    + " is not approved; nothing is sent to the supplier yet");
        }
        Map<UUID, RetailItem> byId = items.findByIds(po.lines().stream().map(PoLine::itemId).toList()).stream()
                .collect(Collectors.toMap(RetailItem::id, Function.identity()));
        return documents.edi850(po, supplier(po.supplierId()), byId);
    }

    private FxRate fx(String currency, BigDecimal given, LocalDate date) {
        if (currency.equals(settings.baseCurrency())) {
            return FxRate.identity(currency, date);
        }
        BigDecimal rate = given != null ? given : fxRates.unitsPerBase(currency, date).orElseThrow(() ->
                new RetailRuleViolation("retail.fx-missing", "No " + currency + "/" + settings.baseCurrency()
                        + " rate for " + date + "; enter the rate on the PO"));
        return new FxRate(currency, settings.baseCurrency(), rate, date);
    }

    private List<PoLine> lines(Supplier supplier, List<PoLineInput> input) {
        if (input == null || input.isEmpty()) {
            throw new RetailRuleViolation("retail.po-invalid", "A purchase order needs at least one line");
        }
        Map<UUID, RetailItem> byId = items.findByIds(input.stream().map(PoLineInput::itemId).toList()).stream()
                .collect(Collectors.toMap(RetailItem::id, Function.identity()));
        List<PoLine> lines = new ArrayList<>();
        int n = 1;
        for (PoLineInput in : input) {
            RetailItem item = byId.get(in.itemId());
            if (item == null) {
                throw new RetailRuleViolation("retail.item-not-found", "Line " + n + ": item " + in.itemId()
                        + " not found");
            }
            SupplierItem si = item.supplierItem(supplier.id()).orElseThrow(() -> new RetailRuleViolation(
                    "retail.item-not-supplied", item.sku() + " has no supplier SKU for " + supplier.code()));
            long cost;
            if (in.unitCostMinor() != null) {
                cost = in.unitCostMinor();
            } else if (si.currency().equals(supplier.currency())) {
                cost = si.unitCostMinor();
            } else {
                throw new RetailRuleViolation("retail.po-line-invalid", "Line " + n + ": enter the unit price in "
                        + supplier.currency());
            }
            if (si.minOrderQty() > 0 && in.qty() < si.minOrderQty()) {
                throw new RetailRuleViolation("retail.po-below-moq", "Line " + n + ": " + item.sku()
                        + " has a minimum order of " + si.minOrderQty());
            }
            lines.add(PoLine.of(n++, item.id(), item.sku(), item.name(), si.supplierSku(), in.qty(), cost));
        }
        return lines;
    }

    private PurchaseOrder withTier(PurchaseOrder po, Actor actor, Instant now) {
        ApprovalTier tier = approvals.tierFor(po.totalBaseMinor());
        return new PurchaseOrder(po.id(), po.number(), po.supplierId(), po.supplierCode(), po.currency(), po.fx(),
                po.destinationNodeId(), po.incoterm(), po.orderDate(), po.expectedDate(), po.lines(), po.status(), tier,
                po.shipmentBooked(), po.createdBy() == null ? actor.subject() : po.createdBy(), po.approvedBy(),
                po.history(), po.createdAt(), now, po.version());
    }

    private PurchaseOrder save(String type, PurchaseOrder changed, Actor actor, String note) {
        PurchaseOrder saved = orders.update(changed);
        publish(type, saved, actor, note);
        return saved;
    }

    private Supplier supplier(UUID id) {
        return suppliers.findById(id).orElseThrow(() -> new NotFoundException("retail.supplier-not-found",
                "Supplier not found"));
    }

    private PurchaseOrder find(UUID id, Long expectedVersion) {
        PurchaseOrder po = orders.findById(id).orElseThrow(() -> new NotFoundException("retail.po-not-found",
                "Purchase order not found"));
        po.ensureVersion(expectedVersion);
        return po;
    }

    private void publish(String type, PurchaseOrder po, Actor actor, String note) {
        events.publish(type, RetailEvents.AGGREGATE, po.id().toString(),
                RetailEventPayloads.PoEvent.of(po, actor.subject(), note));
    }
}
