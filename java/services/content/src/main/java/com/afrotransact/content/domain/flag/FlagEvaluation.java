package com.afrotransact.content.domain.flag;

/**
 * Result of evaluating one flag.
 *
 * @param key     flag key
 * @param value   on or off
 * @param reason  why
 * @param variant {@code on} or {@code off}
 * @param version flag version evaluated
 */
public record FlagEvaluation(String key, boolean value, FlagReason reason, String variant, long version) {

    public static FlagEvaluation of(FeatureFlag flag, boolean value, FlagReason reason) {
        return new FlagEvaluation(flag.key(), value, reason, value ? "on" : "off", flag.version());
    }
}
