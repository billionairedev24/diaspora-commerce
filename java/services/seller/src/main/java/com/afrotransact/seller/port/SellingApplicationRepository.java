package com.afrotransact.seller.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.seller.domain.DocumentAccess;
import com.afrotransact.seller.domain.SellingApplication;
import com.afrotransact.seller.domain.SellingEvaluator.Filed;
import com.afrotransact.seller.domain.SellingScope;
import com.afrotransact.seller.domain.TimelineEntry;

/** Selling applications (staff cases), their timeline and the document access log. */
public interface SellingApplicationRepository {

    String nextReference();

    /** @return false when the organisation already has an application in progress for the scope */
    boolean insert(SellingApplication application);

    /** Optimistic: a stale version fails with a conflict. */
    SellingApplication update(SellingApplication application);

    Optional<SellingApplication> findById(String id);

    Optional<SellingApplication> lockById(String id);

    Optional<SellingApplication> findOpen(String orgId, SellingScope scope);

    List<SellingApplication> forOrg(String orgId);

    /** The latest application of the organisation for each scope. */
    Map<SellingScope, Filed> latest(String orgId, Collection<SellingScope> scopes);

    Page<SellingCaseRow> searchCases(CaseSearch search);

    void appendTimeline(TimelineEntry entry);

    List<TimelineEntry> timeline(String applicationId);

    Optional<TimelineEntry> timelineEntry(String applicationId, String requestKey);

    void logAccess(DocumentAccess access);

    List<DocumentAccess> accessLog(String applicationId);
}
