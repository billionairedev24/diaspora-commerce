package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.EffectiveFees;
import com.afrotransact.payments.domain.FeeSchedule;

/** "Fees & commissions": versioned commission per category, card fee and FBA fee with effective dates. */
public interface FeeScheduleService {

    /** Every version, newest first (history is never rewritten). */
    List<FeeSchedule> history();

    /** The version in force at {@code at} (null = now). */
    FeeSchedule activeAt(Instant at);

    /**
     * Fees payments applies to {@code orgId} in {@code category} under the version in force at {@code at} (null =
     * now): commission (seller override, category or default; none for AfroTransact Retail), card-fee estimate and
     * FBA fee. Readable by calling services (payments:service) and staff.
     */
    EffectiveFees effective(String category, String orgId, Instant at);

    /** Super Admin / Finance (payments:configure): a new version for orders placed from its effective date. */
    FeeSchedule create(NewFeeScheduleCommand command);

    /**
     * Super Admin / Finance (payments:configure): withdraws a scheduled version before its effective date. The version
     * is kept and marked cancelled (who, when, why), never deleted or rewritten; it then never applies.
     */
    FeeSchedule cancel(UUID id, String reason);
}
