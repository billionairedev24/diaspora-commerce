package com.afrotransact.content.domain.market;

/**
 * Outcome of an eligibility check, used by checkout (block delivery in disabled states) and by onboarding
 * (reject non-live countries and offer the launch list).
 *
 * @param country           ISO country code asked about
 * @param countryStatus     its status, or null when unsupported
 * @param region            resolved state / province, or null
 * @param onboardingAllowed sellers and providers can onboard here
 * @param deliveryAllowed   orders can be delivered here (marketplace on)
 * @param capabilities      the region's switches (all off when not live)
 * @param reason            stable reason code ({@code ok}, {@code country-not-live}, {@code region-disabled}…)
 * @param message           human-readable explanation
 * @param launchNote        country launch note
 * @param launchListOpen    the visitor can join the country's launch list
 * @param marketsVersion    published markets version the answer is based on
 */
public record Eligibility(String country, MarketStatus countryStatus, String region, boolean onboardingAllowed,
                          boolean deliveryAllowed, Capabilities capabilities, String reason, String message,
                          String launchNote, boolean launchListOpen, int marketsVersion) {
}
