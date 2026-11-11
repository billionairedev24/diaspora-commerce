package com.afrotransact.seller.application;

import java.util.List;

import com.afrotransact.seller.domain.ReviewSettings;
import com.afrotransact.seller.port.ReviewSettingsRepository.SettingsChange;

/** SLAs, escalation chains and assignment mode of the application review. */
public interface ReviewSettingsService {

    /** Settings and the last changes; readable by anyone who views applications. */
    Current current();

    /** Saves new settings with a reason (applications:assign); validated, audited. */
    Current update(ReviewSettings next, String reason, String actorName);

    /** Names shown for the chains' people, and the last changes. */
    record Current(ReviewSettings settings, List<String> reviewLevel1People, List<String> reviewLevel2People,
                   List<String> approvalLevel1People, List<String> approvalLevel2People,
                   List<SettingsChange> changes) {

        public Current {
            reviewLevel1People = List.copyOf(reviewLevel1People);
            reviewLevel2People = List.copyOf(reviewLevel2People);
            approvalLevel1People = List.copyOf(approvalLevel1People);
            approvalLevel2People = List.copyOf(approvalLevel2People);
            changes = List.copyOf(changes);
        }
    }
}
