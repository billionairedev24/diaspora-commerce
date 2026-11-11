package com.afrotransact.seller.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.seller.domain.ApplicationCase;
import com.afrotransact.seller.domain.ReviewStage;

/** Who holds each application. Callers lock the application row first ({@code ApplicationRepository.lockById}). */
public interface ApplicationCaseRepository {

    Optional<ApplicationCase> find(String applicationId);

    /** Inserts or replaces the case. */
    ApplicationCase save(ApplicationCase workflow);

    /** Cases in staff hands that are not at the top escalation level yet, oldest first. */
    List<String> escalationCandidates(int limit);

    /** Open cases held by each person at {@code stage} (the load for LEAST_LOADED). */
    Map<String, Integer> openLoad(ReviewStage stage);
}
