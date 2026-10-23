package com.afrotransact.taxreturns.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.afrotransact.taxreturns.domain.tax.NexusRow;

/**
 * Nexus tracker: for every state with sales (or a recorded setting), the sales shipped there over the trailing twelve
 * months against its thresholds, and whether AfroTransact is registered. It tells staff when a state is close or past
 * its threshold and nothing is registered; it does not register anyone, and the thresholds are defaults staff confirm
 * with their tax advisor.
 */
public interface TaxNexusService {

    /** Default sales threshold when staff haven't set one (a common state figure: $100,000). */
    long DEFAULT_SALES_THRESHOLD_MINOR = 10_000_000L;

    /** Months of sales measured. */
    int WINDOW_MONTHS = 12;

    /** The tracker, most urgent first: exceeded, approaching, registered, below (tax:read). */
    List<NexusRow> tracker(YearMonth through);

    /** One state (tax:read). */
    NexusRow state(YearMonth through, String jurisdiction);

    /** Records a state's thresholds and registration (tax:configure). */
    NexusRow update(UpdateNexus command);

    /**
     * @param jurisdiction          US-TX
     * @param transactionsThreshold null when the state has no transaction-count test
     * @param registeredOn          required when registered
     */
    record UpdateNexus(YearMonth through, String jurisdiction, long salesThresholdMinor, Integer transactionsThreshold,
                       boolean registered, String registrationNumber, LocalDate registeredOn, String note) {
    }
}
