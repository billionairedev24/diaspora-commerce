package com.afrotransact.payments.application;

import java.util.List;

/**
 * The payments settings staff can read in the console's Settings: what governs holds, payouts and Stripe. They come
 * from the deployment's configuration, so this is a read-only view.
 */
public interface SettingsSummaryService {

    /** (payments:read) */
    List<SettingLine> summary();

    /**
     * @param key    stable id, e.g. {@code settlement.hold}
     * @param value  what is in force, in words ("7 days after delivery")
     * @param source where it is set (the environment setting to change it)
     */
    record SettingLine(String key, String label, String value, String help, String source) {
    }
}
