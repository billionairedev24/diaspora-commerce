package com.afrotransact.retail.application;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.retail.domain.Currencies;
import com.afrotransact.retail.domain.DispatchChannel;
import com.afrotransact.retail.domain.DispatchStatus;
import com.afrotransact.retail.domain.PoDispatch;
import com.afrotransact.retail.domain.PoLine;
import com.afrotransact.retail.domain.PoStatus;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierContact;
import com.afrotransact.retail.domain.SupplierEdiProfile;
import com.afrotransact.retail.port.EdiTransport;
import com.afrotransact.retail.port.EdiTransportException;
import com.afrotransact.retail.port.ItemRepository;
import com.afrotransact.retail.port.MediaStore;
import com.afrotransact.retail.port.MediaUnavailableException;
import com.afrotransact.retail.port.PoDispatchRepository;
import com.afrotransact.retail.port.PurchaseOrderDocuments;
import com.afrotransact.retail.port.PurchaseOrderPdfRenderer;
import com.afrotransact.retail.port.PurchaseOrderRepository;
import com.afrotransact.retail.port.SupplierEdiProfileRepository;
import com.afrotransact.retail.port.SupplierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Each due dispatch is worked off in its own transaction under a row lock ({@code FOR UPDATE SKIP LOCKED}), so
 * replicas never deliver the same dispatch twice. Failures are recorded on the dispatch in that transaction (the
 * outbox events of a delivery are only written once every external call succeeded).
 */
@Service
class PoDispatchServiceImpl implements PoDispatchService {

    private static final Logger LOG = LoggerFactory.getLogger(PoDispatchServiceImpl.class);
    private static final int BATCH = 20;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final PoDispatchRepository dispatches;
    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final ItemRepository items;
    private final SupplierEdiProfileRepository ediProfiles;
    private final PurchaseOrderPdfRenderer pdfRenderer;
    private final PurchaseOrderDocuments documents;
    private final MediaStore media;
    private final EdiTransport edi;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final TransactionOperations transactions;
    private final DispatchSettings settings;
    private final Clock clock;

    PoDispatchServiceImpl(PoDispatchRepository dispatches, PurchaseOrderRepository orders,
                          SupplierRepository suppliers, ItemRepository items,
                          SupplierEdiProfileRepository ediProfiles, PurchaseOrderPdfRenderer pdfRenderer,
                          PurchaseOrderDocuments documents, MediaStore media, EdiTransport edi,
                          PermissionChecker permissions, DomainEventPublisher events,
                          TransactionOperations transactions, DispatchSettings settings, Clock clock) {
        this.dispatches = dispatches;
        this.orders = orders;
        this.suppliers = suppliers;
        this.items = items;
        this.ediProfiles = ediProfiles;
        this.pdfRenderer = pdfRenderer;
        this.documents = documents;
        this.media = media;
        this.edi = edi;
        this.permissions = permissions;
        this.events = events;
        this.transactions = transactions;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] pdf(UUID poId) {
        permissions.require(RetailPermissions.READ);
        PurchaseOrder po = po(poId);
        if (po.status() == PoStatus.DRAFT || po.status() == PoStatus.PENDING_APPROVAL) {
            throw new RetailRuleViolation("retail.po-not-approved", "PO " + po.number()
                    + " is not approved; nothing is sent to the supplier yet");
        }
        return pdfRenderer.render(po, supplier(po.supplierId()), items(po));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PoDispatch> dispatches(UUID poId) {
        permissions.require(RetailPermissions.READ);
        po(poId);
        return dispatches.byPo(poId);
    }

    @Override
    @Transactional
    public List<PoDispatch> retryFailed(UUID poId) {
        permissions.require(RetailPermissions.PO_WRITE);
        po(poId);
        Instant now = Instant.now(clock);
        Supplier supplier = null;
        List<PoDispatch> out = new ArrayList<>();
        for (PoDispatch d : dispatches.byPo(poId)) {
            if (d.status() != DispatchStatus.FAILED) {
                out.add(d);
                continue;
            }
            PoDispatch retried = d.retry(now);
            if (d.channel() == DispatchChannel.EMAIL) {
                // The buyer may have fixed the supplier contact in the meantime.
                supplier = supplier == null ? supplier(po(poId).supplierId()) : supplier;
                String email = supplier.purchaseOrderContact().map(SupplierContact::email).orElse(null);
                retried = new PoDispatch(retried.id(), retried.poId(), retried.poNumber(), retried.channel(),
                        retried.status(), retried.attempts(), retried.nextAttemptAt(), retried.lastError(), email,
                        retried.assetId(), retried.linkExpiresAt(), retried.controlNumber(), retried.transmissionId(),
                        retried.createdAt(), retried.updatedAt(), retried.version());
            }
            out.add(dispatches.update(retried));
        }
        return out;
    }

    @Override
    public int dispatchDue() {
        List<UUID> due = dispatches.due(Instant.now(clock), BATCH);
        int attempted = 0;
        for (UUID id : due) {
            Boolean done = transactions.execute(status -> dispatchOne(id));
            if (Boolean.TRUE.equals(done)) {
                attempted++;
            }
        }
        return attempted;
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierEdiProfile ediProfile(UUID supplierId) {
        permissions.require(RetailPermissions.READ);
        supplier(supplierId);
        return ediProfiles.find(supplierId).orElseThrow(() -> new NotFoundException("retail.edi-profile-not-found",
                "Supplier " + supplierId + " has no EDI settings"));
    }

    @Override
    @Transactional
    public SupplierEdiProfile setEdiProfile(UUID supplierId, EdiProfileCommand c) {
        Actor actor = permissions.require(RetailPermissions.SUPPLIERS_WRITE);
        supplier(supplierId);
        long version = ediProfiles.find(supplierId).map(SupplierEdiProfile::version).orElse(0L);
        return ediProfiles.save(new SupplierEdiProfile(supplierId, c.enabled(), c.protocol(), c.interchangeId(),
                c.endpoint(), actor.subject(), Instant.now(clock), version));
    }

    /** One attempt; returns false when another replica holds the dispatch or it is no longer pending. */
    private boolean dispatchOne(UUID id) {
        PoDispatch d = dispatches.claim(id).orElse(null);
        if (d == null) {
            return false;
        }
        Instant now = Instant.now(clock);
        PoDispatch result;
        try {
            result = d.channel() == DispatchChannel.EMAIL ? email(d, now) : transmit(d, now);
        } catch (MediaUnavailableException e) {
            result = d.attemptFailed(e.getMessage(), false, settings.maxAttempts(), now);
        } catch (EdiTransportException e) {
            result = d.attemptFailed(e.getMessage(), !e.retryable(), settings.maxAttempts(), now);
        } catch (RetailRuleViolation e) {
            result = d.attemptFailed(e.getMessage(), true, settings.maxAttempts(), now);
        } catch (RuntimeException e) {
            LOG.warn("Dispatch {} of {} failed unexpectedly", d.channel(), d.poNumber(), e);
            result = d.attemptFailed(e.getClass().getSimpleName() + ": " + e.getMessage(), false,
                    settings.maxAttempts(), now);
        }
        PoDispatch saved = dispatches.update(result);
        if (saved.status() == DispatchStatus.DONE) {
            events.publish(RetailEvents.PO_DISPATCHED, RetailEvents.AGGREGATE, saved.poId().toString(),
                    RetailEventPayloads.PoDispatchEvent.of(saved));
        } else if (saved.status() == DispatchStatus.FAILED) {
            LOG.warn("{} dispatch of {} failed: {}", saved.channel(), saved.poNumber(), saved.lastError());
            events.publish(RetailEvents.PO_DISPATCH_FAILED, RetailEvents.AGGREGATE, saved.poId().toString(),
                    RetailEventPayloads.PoDispatchEvent.of(saved));
        }
        return true;
    }

    /** PDF → media → signed link → notification.requested to the supplier contact. */
    private PoDispatch email(PoDispatch d, Instant now) {
        PurchaseOrder po = po(d.poId());
        Supplier supplier = supplier(po.supplierId());
        SupplierContact contact = supplier.purchaseOrderContact().orElseThrow(() -> new RetailRuleViolation(
                "retail.supplier-no-contact", "Supplier " + supplier.code() + " has no contact with an email; add one "
                + "and retry"));
        String fileName = po.number() + ".pdf";
        MediaStore.MediaAsset asset = media.store(MediaStore.PURPOSE_PURCHASE_ORDER, fileName, "application/pdf",
                pdfRenderer.render(po, supplier, items(po)));
        MediaStore.SignedLink link = media.signedLink(asset.id(), settings.linkTtl());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("poId", po.id().toString());
        data.put("poNumber", po.number());
        data.put("supplierName", supplier.name());
        data.put("supplierCode", supplier.code());
        data.put("contactName", contact.name());
        data.put("orderDate", DATE.format(po.orderDate()));
        data.put("deliverBy", po.expectedDate() == null ? "" : DATE.format(po.expectedDate()));
        data.put("incoterm", po.incoterm());
        data.put("paymentTerms", supplier.paymentTerms() == null ? "" : supplier.paymentTerms());
        data.put("shipTo", po.destinationNodeId());
        data.put("lineCount", po.lines().size());
        data.put("units", po.lines().stream().mapToInt(PoLine::qtyOrdered).sum());
        data.put("totalMinor", po.totalMinor());
        data.put("currency", po.currency());
        data.put("total", po.currency() + " " + Currencies.toMajor(po.totalMinor(), po.currency()).toPlainString());
        data.put("documentUrl", link.url());
        data.put("documentExpiresAt", link.expiresAt().toString());
        data.put("documentAssetId", asset.id().toString());
        data.put("attachments", List.of(new RetailEventPayloads.Attachment(asset.id(), fileName, "application/pdf",
                link.url(), link.expiresAt())));
        events.publish(RetailEvents.NOTIFICATION_REQUESTED, RetailEvents.AGGREGATE, po.id().toString(),
                new RetailEventPayloads.NotificationRequested(settings.emailTemplate(), List.of("email"),
                        new RetailEventPayloads.Recipient(contact.email(), contact.name()), "en", data));
        return d.emailed(contact.email(), asset.id(), link.expiresAt(), now);
    }

    /** X12 850 addressed to the partner's interchange id → EDI transport. */
    private PoDispatch transmit(PoDispatch d, Instant now) {
        PurchaseOrder po = po(d.poId());
        Supplier supplier = supplier(po.supplierId());
        SupplierEdiProfile profile = ediProfiles.find(supplier.id()).filter(SupplierEdiProfile::enabled)
                .orElseThrow(() -> new RetailRuleViolation("retail.edi-not-enabled", "Supplier " + supplier.code()
                        + " is not EDI-enabled any more"));
        String payload = documents.edi850(po, supplier, items(po), profile.interchangeId());
        String control = controlNumber(payload);
        String receipt = edi.transmit(new EdiTransport.EdiMessage(supplier.code(), profile.interchangeId(),
                profile.protocol(), profile.endpoint(), control, "850", po.number(), payload));
        return d.transmitted(profile.interchangeId(), control, receipt, now);
    }

    /** ISA13, the interchange control number (the ISA segment has fixed positions). */
    static String controlNumber(String x12) {
        String[] isa = x12.substring(0, x12.indexOf('~')).split("\\*", -1);
        return isa.length > 13 ? isa[13] : "";
    }

    private Map<UUID, RetailItem> items(PurchaseOrder po) {
        return items.findByIds(po.lines().stream().map(PoLine::itemId).toList()).stream()
                .collect(Collectors.toMap(RetailItem::id, Function.identity()));
    }

    private PurchaseOrder po(UUID id) {
        return orders.findById(id).orElseThrow(() -> new NotFoundException("retail.po-not-found",
                "Purchase order not found"));
    }

    private Supplier supplier(UUID id) {
        return suppliers.findById(id).orElseThrow(() -> new NotFoundException("retail.supplier-not-found",
                "Supplier not found"));
    }
}
