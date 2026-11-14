package com.afrotransact.taxreturns.domain.tax;

/**
 * What a "Test connection" found out about a provider. Never carries a credential.
 *
 * @param configured    the credentials are set (when false nothing was asked)
 * @param authenticated the provider accepted the credentials
 * @param accountName   the account or user name the provider reported, or null
 * @param environment   the environment asked ({@code sandbox} or {@code production}), or null
 * @param message       plain words for the owner: what worked, or what to fix
 */
public record ProviderConnection(TaxProviderId provider, boolean configured, boolean authenticated, String accountName,
                                 String environment, String message) {

    public static ProviderConnection notConfigured(TaxProviderId provider, String message) {
        return new ProviderConnection(provider, false, false, null, null, message);
    }

    public static ProviderConnection unsupported(TaxProviderId provider) {
        return new ProviderConnection(provider, false, false, null, null,
                "A connection test is not available for this provider");
    }
}
