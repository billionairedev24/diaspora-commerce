package com.afrotransact.payments.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.payments.domain.MoneySettings;
import com.afrotransact.payments.port.MoneySettingsRepository;

/**
 * Settings, Money: gift card amount limits and terms, and the credit approval limit. Changed by Finance and Super Admin
 * (payments:configure) without a release, each change with a reason and kept in a history.
 */
public interface MoneySettingsService {

    /** The settings in force (staff with payments:read). */
    MoneySettings get();

    /**
     * Replaces the settings; {@code expectedVersion} is the version the editor saw (a concurrent change is a 409).
     * The reason is kept with the change.
     */
    MoneySettings update(UpdateCommand command);

    /** Who changed what and why, newest first (staff with payments:read). */
    List<MoneySettingsRepository.Change> history(int limit);

    record UpdateCommand(long giftCardMinMinor, long giftCardMaxMinor, List<Long> giftCardPresets,
                         Map<String, String> giftCardTerms, long creditApprovalLimitMinor, String reason,
                         long expectedVersion) {
    }
}
