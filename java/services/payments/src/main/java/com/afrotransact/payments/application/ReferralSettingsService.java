package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.ReferralReward;
import com.afrotransact.payments.domain.ReferralSettings;
import com.afrotransact.payments.port.ReferralSettingsRepository;

/**
 * Settings for the referral program: what the referrer earns and what the friend earns (each credit or membership days),
 * the qualifying event, minimum order value, return-window delay, per-customer caps, validity window and on/off. Changed
 * by Finance and Super Admin (payments:configure) without a release, each change with a reason and kept in a history.
 * AfroTransact funds the rewards (ADR 0006); store credit is never convertible to cash.
 */
public interface ReferralSettingsService {

    /** The settings in force (staff with payments:read). */
    ReferralSettings get();

    /**
     * Replaces the settings; {@code expectedVersion} is the version the editor saw (a concurrent change is a 409). The
     * reason (at least 15 characters) is kept with the change.
     */
    ReferralSettings update(UpdateCommand command);

    /** Who changed what and why, newest first (staff with payments:read). */
    List<ReferralSettingsRepository.Change> history(int limit);

    record UpdateCommand(boolean enabled, ReferralReward referrer, ReferralReward referred,
                         ReferralSettings.QualifyingEvent qualifyingEvent, long minOrderMinor, int returnWindowDays,
                         int maxRewardedPerReferrer, long maxCreditPerCustomerMinor, int validityDays, String reason,
                         long expectedVersion) {
    }
}
