package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.payments.domain.FeeSchedule;

/** "Fees & commissions": versioned commission per category, card fee and FBA fee with effective dates. */
public interface FeeScheduleService {

    /** Every version, newest first (history is never rewritten). */
    List<FeeSchedule> history();

    /** The version in force at {@code at} (null = now). */
    FeeSchedule activeAt(Instant at);

    /** Super Admin / Finance (payments:configure): a new version for orders placed from its effective date. */
    FeeSchedule create(NewFeeScheduleCommand command);
}
