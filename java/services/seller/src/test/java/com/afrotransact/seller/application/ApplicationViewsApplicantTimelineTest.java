package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.TimelineEntry;
import org.junit.jupiter.api.Test;

class ApplicationViewsApplicantTimelineTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    private static TimelineEntry entry(ReviewAction action, ApplicationStatus to, String note) {
        return new TimelineEntry(UUID.randomUUID(), "app_1", action, ApplicationStatus.PENDING_REVIEW, to, "staff-1",
                "REASON", note, List.of("A clearer photo of your ID"), NOW, "Ada Obi", "key-1");
    }

    @Test
    void theApplicantNeverSeesInternalStepsOrWhoDecided() {
        List<TimelineEntry> all = List.of(
                entry(ReviewAction.SUBMITTED, ApplicationStatus.PENDING_REVIEW, null),
                entry(ReviewAction.APPROVE, ApplicationStatus.AWAITING_FINAL_APPROVAL, "internal: docs look fine"),
                entry(ReviewAction.FLAG, ApplicationStatus.FLAGGED, "internal: possible duplicate account"),
                entry(ReviewAction.SEND_BACK, ApplicationStatus.PENDING_REVIEW, "internal: check the EIN"),
                entry(ReviewAction.REQUEST_INFO, ApplicationStatus.INFO_REQUESTED, "Please upload a clearer photo of your ID"));

        List<TimelineEntry> seen = ApplicationViews.forApplicant(all);

        assertThat(seen).extracting(TimelineEntry::action).containsExactly(ReviewAction.SUBMITTED, ReviewAction.REQUEST_INFO);
        assertThat(seen).allSatisfy(t -> {
            assertThat(t.actorId()).isNull();
            assertThat(t.actorName()).isNull();
            assertThat(t.requestKey()).isNull();
        });
        assertThat(seen.get(1).note()).isEqualTo("Please upload a clearer photo of your ID");
        assertThat(seen.get(1).items()).containsExactly("A clearer photo of your ID");
    }

    @Test
    void aRejectionKeepsItsReasonForTheApplicantButAnApprovalNoteIsInternal() {
        List<TimelineEntry> seen = ApplicationViews.forApplicant(List.of(
                entry(ReviewAction.REJECT, ApplicationStatus.REJECTED, "We cannot verify your business"),
                entry(ReviewAction.FINAL_APPROVE, ApplicationStatus.APPROVED, "internal: fast track")));

        assertThat(seen.get(0).note()).isEqualTo("We cannot verify your business");
        assertThat(seen.get(1).note()).isNull();
    }
}
