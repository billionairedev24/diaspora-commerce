package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.Plan;
import com.afrotransact.seller.domain.PlanInput;
import com.afrotransact.seller.domain.PlanStatus;
import com.afrotransact.seller.domain.PlanVersion;

/**
 * Plan catalogue owned by AfroTransact: plan metadata, immutable versions (prices per cadence and default trial) and
 * the Stripe Product / Price ids the sync stored.
 */
public interface PlanRepository {

    /** Published, not retired plans (any visibility), with their current version's prices; {@code kind} optional. */
    List<Plan> findOpen(OrgKind kind);

    /** Every plan (drafts and retired too), for staff. */
    List<Plan> findAll();

    /** A plan with its current version's prices (a never-published plan has version 0 and zero prices). */
    Optional<Plan> find(String code);

    /** A plan priced at {@code version} (what a subscriber on that version pays). */
    Optional<Plan> find(String code, int version);

    /** Locks the plan row (staff edits and publishing). */
    Optional<Plan> lock(String code);

    void insert(PlanInput in, String actor, Instant at);

    /** Metadata update with optimistic locking ({@code expectedVersion} = the plan row's version). */
    void update(PlanInput in, PlanStatus status, long expectedVersion, String actor, Instant at);

    long rowVersion(String code);

    void setStatus(String code, PlanStatus status, String actor, Instant at);

    void setCurrentVersion(String code, int version, String actor, Instant at);

    List<PlanVersion> versions(String code);

    Optional<PlanVersion> version(String code, int version);

    Optional<PlanVersion> draft(String code);

    int nextVersionNumber(String code);

    /** Inserts a draft version with its prices and lookup keys. */
    void insertVersion(PlanVersion v);

    /** Replaces a draft version's prices and trial. */
    void updateDraft(PlanVersion v);

    /** DRAFT → PUBLISHED and the previously published version → SUPERSEDED. */
    void publishVersion(String code, int version, Instant at);

    List<PriceRow> prices(String code, int version);

    /** Every price of every version, for the drift report. */
    List<PriceRow> allPrices();

    void recordStripePrice(String code, int version, Cadence cadence, String stripePriceId, Instant at);

    void recordSyncError(String code, int version, Cadence cadence, String error);

    void setStripeProduct(String code, String productId);

    /** The plan's Stripe Product id, or null. */
    String stripeProduct(String code);

    /** Which plan version and cadence a Stripe Price id belongs to. */
    Optional<PriceRef> byStripePrice(String stripePriceId);

    /** Fallback for prices not yet adopted: the current version holding {@code lookupKey}. */
    Optional<PriceRef> byLookupKey(String lookupKey);

    List<String> invites(String code);

    void setInvites(String code, List<String> orgIds, String actor, Instant at);

    /** Plans an organisation was invited to. */
    List<String> invitedPlans(String orgId);

    /** Records a staff migration of one organisation to {@code toVersion} (MIGRATED or FAILED with the error). */
    void recordMigration(String orgId, String planCode, int fromVersion, int toVersion, String actor, String status,
                         String error, Instant at);

    /** Subscriptions per (plan, version), for the catalogue and the migration preview. */
    List<VersionCount> subscriberCounts();

    /** A plan version and cadence. */
    record PriceRef(String planCode, int version, Cadence cadence) {
    }

    /** One version's price for a cadence and its Stripe state. */
    record PriceRow(String planCode, int version, Cadence cadence, long amountCents, String lookupKey,
                    String stripePriceId, Instant syncedAt, String syncError) {
    }

    /** Subscribers on a plan version. */
    record VersionCount(String planCode, int version, long subscribers) {
    }
}
