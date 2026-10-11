package com.afrotransact.content.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.privacy.PrivacyRequest;
import com.afrotransact.content.domain.privacy.PrivacyRequestStatus;

/** Privacy requests (Your Privacy Choices). */
public interface PrivacyRequestRepository {

    /** Next number for a {@code PR-######} reference. */
    long nextSequence();

    void insert(PrivacyRequest request);

    Optional<PrivacyRequest> findByReference(String reference);

    /** Newest first; {@code status} null for every status. */
    List<PrivacyRequest> list(PrivacyRequestStatus status, int limit);

    /** Saves a resolution when the stored version still matches; false when someone else changed it first. */
    boolean update(PrivacyRequest request);
}
