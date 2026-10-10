package com.afrotransact.content.domain.site;

/**
 * The storefront's default market.
 *
 * @param code     market code
 * @param currency ISO 4217
 * @param country  ISO 3166-1
 */
public record MarketDefault(String code, String currency, String country) {
}
