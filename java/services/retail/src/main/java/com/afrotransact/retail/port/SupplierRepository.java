package com.afrotransact.retail.port;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierStatus;

/** Persistence port for suppliers (optimistic locking on {@code version}). */
public interface SupplierRepository {

    Supplier insert(Supplier supplier);

    Supplier update(Supplier supplier);

    Optional<Supplier> findById(UUID id);

    Optional<Supplier> findByCode(String code);

    List<Supplier> findByIds(Collection<UUID> ids);

    Page<Supplier> page(SupplierStatus status, String query, int page, int size);
}
