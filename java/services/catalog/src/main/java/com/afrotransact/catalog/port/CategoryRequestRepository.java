package com.afrotransact.catalog.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.CategoryRequestStatus;

/** Persistence port for category requests. */
public interface CategoryRequestRepository {

    CategoryRequest insert(CategoryRequest request);

    CategoryRequest update(CategoryRequest request);

    Optional<CategoryRequest> findById(UUID id);

    Optional<CategoryRequest> findByReference(String reference);

    List<CategoryRequest> findByStatus(CategoryRequestStatus status);

    List<CategoryRequest> findByOrg(String orgId);
}
