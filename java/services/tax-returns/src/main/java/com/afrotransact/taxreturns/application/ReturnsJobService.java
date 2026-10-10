package com.afrotransact.taxreturns.application;

/** Background work: seller SLA auto-approval and retries of labels, refunds and inventory postings. */
public interface ReturnsJobService {

    Report runOnce();

    /** What one run did. */
    record Report(int autoApproved, int labels, int refunds, int stock) {
    }
}
