package com.afrotransact.retail.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.retail.*} settings.
 *
 * @param baseCurrency     currency of landed cost, GL and approval thresholds
 * @param gs1CompanyPrefix UPC company prefix for GTINs issued to 1P items
 * @param retailOwnerOrg   inventory owner of 1P stock (null: inventory's AfroTransact Retail default)
 * @param approval         PO approval thresholds
 * @param receiving        dock validation rules
 * @param match            three-way match tolerance
 * @param fxRates          supplier-currency units per base unit
 * @param catalog          catalog-service endpoint
 * @param inventory        inventory-service endpoint
 * @param auth             client-credentials settings of retail-service
 */
@ConfigurationProperties(prefix = "at.retail")
public record RetailProperties(String baseCurrency, String gs1CompanyPrefix, String retailOwnerOrg, Approval approval,
                               Receiving receiving, Match match, Map<String, BigDecimal> fxRates, Endpoint catalog,
                               Endpoint inventory, Auth auth) {

    public RetailProperties {
        baseCurrency = baseCurrency == null ? "USD" : baseCurrency;
        gs1CompanyPrefix = gs1CompanyPrefix == null ? "86001" : gs1CompanyPrefix;
        retailOwnerOrg = retailOwnerOrg == null || retailOwnerOrg.isBlank() ? null : retailOwnerOrg;
        approval = approval == null ? new Approval(null) : approval;
        receiving = receiving == null ? new Receiving(null, null, null) : receiving;
        match = match == null ? new Match(null) : match;
        fxRates = fxRates == null ? Map.of() : Map.copyOf(fxRates);
        catalog = catalog == null ? new Endpoint("http://localhost:8101", null) : catalog;
        inventory = inventory == null ? new Endpoint("http://localhost:8103", null) : inventory;
        auth = auth == null ? new Auth(null, null, null, null) : auth;
    }

    /** POs above {@code financeThresholdMinor} (base currency minor units) need Finance approval. */
    public record Approval(Long financeThresholdMinor) {

        public Approval {
            financeThresholdMinor = financeThresholdMinor == null ? 1_000_000L : financeThresholdMinor;
        }
    }

    /** Over-receipt tolerance and minimum remaining shelf life at the dock. */
    public record Receiving(BigDecimal overTolerancePercent, Integer minRemainingDays, Double minRemainingFraction) {

        public Receiving {
            overTolerancePercent = overTolerancePercent == null ? BigDecimal.valueOf(2) : overTolerancePercent;
            minRemainingDays = minRemainingDays == null ? 90 : minRemainingDays;
            minRemainingFraction = minRemainingFraction == null ? 0.5 : minRemainingFraction;
        }
    }

    /** Invoice vs PO price tolerance in percent. */
    public record Match(BigDecimal priceTolerancePercent) {

        public Match {
            priceTolerancePercent = priceTolerancePercent == null ? BigDecimal.ONE : priceTolerancePercent;
        }
    }

    /** Base URL and read timeout of a downstream service. */
    public record Endpoint(String baseUrl, Duration timeout) {

        public Endpoint {
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    /** OAuth client of retail-service; a blank secret disables the token (local profile). */
    public record Auth(String tokenUri, String clientId, String clientSecret, String scopes) {

        public Auth {
            tokenUri = tokenUri == null ? "http://localhost:8100/oauth2/token" : tokenUri;
            clientId = clientId == null ? "retail-service" : clientId;
        }
    }
}
