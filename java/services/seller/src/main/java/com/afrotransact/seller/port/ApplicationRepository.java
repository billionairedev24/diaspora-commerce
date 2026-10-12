package com.afrotransact.seller.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.DocumentAccess;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.TimelineEntry;

/** Onboarding applications, their timeline and the document / PII access log. */
public interface ApplicationRepository {

    /** Inserts unless the applicant already has an application; returns false on that conflict. */
    boolean insert(Application application);

    /** Optimistic update on {@code version}. */
    Application update(Application application);

    Optional<Application> findById(String id);

    Optional<Application> findByApplicant(String userId);

    Optional<Application> findByOrg(String orgId);

    /** {@code SELECT … FOR UPDATE}: serialises concurrent reviewer and approver decisions. */
    Optional<Application> lockById(String id);

    Optional<Application> lockByApplicant(String userId);

    Page<Application> search(List<ApplicationStatus> statuses, OrgKind kind, int page, int size);

    /** Human reference such as {@code APP-30131}. */
    String nextReference();

    void appendTimeline(TimelineEntry entry);

    List<TimelineEntry> timeline(String applicationId);

    /** The timeline entry a review decision with this Idempotency-Key wrote, if any. */
    Optional<TimelineEntry> timelineEntry(String applicationId, String requestKey);

    void logAccess(DocumentAccess access);

    List<DocumentAccess> accessLog(String applicationId);
}
