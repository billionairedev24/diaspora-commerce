package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.SellingApproval;
import com.afrotransact.seller.domain.SellingApprovalStatus;
import com.afrotransact.seller.domain.SellingScope;
import com.afrotransact.seller.domain.SellingScopeType;

/** Approvals per organisation and scope. */
public interface SellingApprovalRepository {

    Optional<SellingApproval> find(String orgId, SellingScope scope);

    /** Locks the row for the rest of the transaction (empty when there is none). */
    Optional<SellingApproval> lock(String orgId, SellingScope scope);

    Optional<SellingApproval> findById(UUID id);

    Optional<SellingApproval> lockById(UUID id);

    Map<SellingScope, SellingApproval> find(String orgId, Collection<SellingScope> scopes);

    List<SellingApproval> forOrg(String orgId);

    /** Inserts or updates by (organisation, scope); an update checks the version. */
    SellingApproval save(SellingApproval approval);

    Page<SellingApproval> search(String orgId, SellingApprovalStatus status, SellingScopeType type, int page, int size);

    /** Approvals whose expiry is at or before {@code horizon} and that may still need a warning, an expiry or a pause. */
    List<UUID> lifecycleCandidates(Instant horizon, int limit);

    /** Organisations that hold a valid approval for the scope (used when a rule changes: who is affected). */
    List<String> orgsWith(SellingScope scope, Instant now);
}
