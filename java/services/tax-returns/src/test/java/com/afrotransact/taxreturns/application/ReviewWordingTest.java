package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.afrotransact.taxreturns.domain.profile.RecordStatus;
import org.junit.jupiter.api.Test;

/** Sellers and staff read why an item is not approved in words: no status codes and no ids. */
class ReviewWordingTest {

    @Test
    void everyStatusButApprovedHasAPlainExplanation() {
        for (RecordStatus status : RecordStatus.values()) {
            if (status == RecordStatus.APPROVED) {
                assertThatThrownBy(() -> ReviewWording.notApproved(status)).isInstanceOf(IllegalArgumentException.class);
                continue;
            }
            assertThat(ReviewWording.notApproved(status)).as("status %s", status).isNotBlank().doesNotContain("_")
                    .doesNotContain("AT1");
        }
        assertThat(ReviewWording.notApproved(RecordStatus.PENDING_REVIEW)).contains("tax reviewer");
    }
}
