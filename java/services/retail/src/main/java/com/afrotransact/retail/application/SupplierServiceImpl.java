package com.afrotransact.retail.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.retail.domain.RetailStateConflict;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierStatus;
import com.afrotransact.retail.port.Page;
import com.afrotransact.retail.port.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository suppliers;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final Clock clock;

    SupplierServiceImpl(SupplierRepository suppliers, PermissionChecker permissions, DomainEventPublisher events,
                        Clock clock) {
        this.suppliers = suppliers;
        this.permissions = permissions;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Supplier create(SupplierCommand c) {
        permissions.require(RetailPermissions.SUPPLIERS_WRITE);
        Supplier draft = Supplier.onboard(UUID.randomUUID(), c.code(), c.name(), c.country(), c.city(), c.currency(),
                c.paymentTerms(), c.leadTimeDays(), c.certifications(), c.contacts(), Instant.now(clock));
        if (suppliers.findByCode(draft.code()).isPresent()) {
            throw new ConflictException("retail.supplier-code-taken", "Supplier code " + draft.code()
                    + " is already used");
        }
        Supplier created = suppliers.insert(draft);
        publish(RetailEvents.SUPPLIER_CREATED, created);
        return created;
    }

    @Override
    @Transactional
    public Supplier update(UUID id, SupplierCommand c, Long expectedVersion) {
        permissions.require(RetailPermissions.SUPPLIERS_WRITE);
        Supplier current = find(id);
        checkVersion(current, expectedVersion);
        Supplier updated = suppliers.update(current.update(c.name(), c.city(), c.currency(), c.paymentTerms(),
                c.leadTimeDays(), c.certifications(), c.contacts(), Instant.now(clock)));
        publish(RetailEvents.SUPPLIER_UPDATED, updated);
        return updated;
    }

    @Override
    @Transactional
    public Supplier changeStatus(UUID id, SupplierStatus target, String reason, Long expectedVersion) {
        permissions.require(RetailPermissions.SUPPLIERS_WRITE);
        Supplier current = find(id);
        checkVersion(current, expectedVersion);
        Supplier updated = suppliers.update(current.changeStatus(target, reason, Instant.now(clock)));
        publish(RetailEvents.SUPPLIER_STATUS_CHANGED, updated);
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public Supplier get(UUID id) {
        permissions.require(RetailPermissions.READ);
        return find(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Supplier> list(SupplierStatus status, String query, int page, int size) {
        permissions.require(RetailPermissions.READ);
        return suppliers.page(status, query, Math.max(0, page), Math.clamp(size, 1, 200));
    }

    private Supplier find(UUID id) {
        return suppliers.findById(id)
                .orElseThrow(() -> new NotFoundException("retail.supplier-not-found", "Supplier not found"));
    }

    private static void checkVersion(Supplier s, Long expected) {
        if (expected != null && expected != s.version()) {
            throw new RetailStateConflict("retail.stale-version", "Supplier " + s.code() + " is at version "
                    + s.version() + ", not " + expected + "; reload and retry");
        }
    }

    private void publish(String type, Supplier s) {
        events.publish(type, RetailEvents.AGGREGATE, s.id().toString(), RetailEventPayloads.SupplierEvent.of(s));
    }
}
