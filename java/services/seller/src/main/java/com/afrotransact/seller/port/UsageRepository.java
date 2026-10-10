package com.afrotransact.seller.port;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.seller.domain.UsageDay;
import com.afrotransact.seller.domain.UsageMeter;

/** Metered usage per organisation, meter and day, the meter events sent to Stripe and the metering runs (S206). */
public interface UsageRepository {

    /**
     * Sets the day's quantity (re-runs overwrite it with the source's current figure).
     *
     * @return true when the row was created or its quantity changed
     */
    boolean upsert(String orgId, UsageMeter meter, LocalDate day, long quantity, Instant at);

    /** Rows of {@code meter} in [from, to]. */
    List<UsageDay> byMeter(UsageMeter meter, LocalDate from, LocalDate to);

    /** One organisation's rows in [from, to], ordered by meter and day. */
    List<UsageDay> byOrg(String orgId, LocalDate from, LocalDate to);

    /** Rows in [from, to] whose quantity differs from what Stripe received. */
    List<UsageDay> unreported(LocalDate from, LocalDate to);

    /** Locks one row for the rest of the transaction. */
    Optional<UsageDay> lock(String orgId, UsageMeter meter, LocalDate day);

    /** Records a sent meter event and moves the row to {@code reportedQuantity} / {@code revision}. */
    void markReported(UsageDay day, long reportedQuantity, int revision, String identifier, long value,
                      String customerId, Instant at);

    /** Records why the row could not be reported (null clears it). */
    void markError(UsageDay day, String error, Instant at);

    /** Which of {@code orgIds} are known organisations. */
    Set<String> knownOrgs(Collection<String> orgIds);

    /** Meters whose metered price is already on {@code subscriptionId}. */
    Set<UsageMeter> meteredItems(String orgId, String subscriptionId);

    /** The meter whose metered price is the subscription item {@code stripeItemId}. */
    Optional<UsageMeter> meterOfItem(String stripeItemId);

    void saveMeteredItem(String orgId, UsageMeter meter, String subscriptionId, String itemId, Instant at);

    /** Takes the metering lease for {@code ttl} unless another holder has it. */
    boolean tryLease(UUID holder, Instant now, Duration ttl);

    void releaseLease(UUID holder);

    void saveRun(UsageRun run);

    List<UsageRun> runs(int limit);

    /** A metering run (status RUNNING, COMPLETED, PARTIAL or FAILED; trigger JOB or STAFF). */
    record UsageRun(UUID id, LocalDate from, LocalDate to, String trigger, String actorId, String status,
                    int rowsChanged, int reported, int pending, int corrections, List<String> failedSources,
                    Instant startedAt, Instant finishedAt) {

        public UsageRun {
            failedSources = failedSources == null ? List.of() : List.copyOf(failedSources);
        }
    }
}
