package com.afrotransact.retail.application;

import java.util.UUID;

import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierStatus;
import com.afrotransact.retail.port.Page;

/** Supplier records, lifecycle and scorecards (S069). */
public interface SupplierService {

    Supplier create(SupplierCommand command);

    Supplier update(UUID id, SupplierCommand command, Long expectedVersion);

    Supplier changeStatus(UUID id, SupplierStatus target, String reason, Long expectedVersion);

    Supplier get(UUID id);

    Page<Supplier> list(SupplierStatus status, String query, int page, int size);
}
