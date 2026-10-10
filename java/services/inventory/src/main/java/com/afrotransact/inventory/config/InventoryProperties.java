package com.afrotransact.inventory.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.inventory.*} settings.
 *
 * @param retailOrgId          owner organisation of AfroTransact Retail (1P) stock
 * @param reservationTtl       checkout hold time before automatic release (default 15 minutes)
 * @param fefoMinRemainingDays FEFO skips lots expiring within this many days
 * @param shelfLife            receipt short-dated rule
 * @param jobs                 scheduled job settings
 * @param storage              FBA storage metering
 * @param catalog              catalog-service (AT-FN unit labels, label rendering; S257)
 * @param carrierGateway       carrier-gateway (inbound labels and tracking; S257)
 * @param auth                 client-credentials token of inventory-service for those calls
 */
@ConfigurationProperties(prefix = "at.inventory")
public record InventoryProperties(String retailOrgId, Duration reservationTtl, Integer fefoMinRemainingDays,
                                  ShelfLife shelfLife, Jobs jobs, Storage storage, Endpoint catalog,
                                  Endpoint carrierGateway, Auth auth) {

    public InventoryProperties {
        retailOrgId = retailOrgId == null ? "org_afrotransact_retail" : retailOrgId;
        reservationTtl = reservationTtl == null ? Duration.ofMinutes(15) : reservationTtl;
        fefoMinRemainingDays = fefoMinRemainingDays == null ? 7 : fefoMinRemainingDays;
        shelfLife = shelfLife == null ? new ShelfLife(null, null) : shelfLife;
        jobs = jobs == null ? new Jobs(null, null) : jobs;
        storage = storage == null ? new Storage(null) : storage;
        catalog = catalog == null ? new Endpoint("http://localhost:8101", null) : catalog;
        carrierGateway = carrierGateway == null ? new Endpoint("http://localhost:8209", null) : carrierGateway;
        auth = auth == null ? new Auth(null, null, null, null) : auth;
    }

    /** A downstream service. */
    public record Endpoint(String baseUrl, Duration timeout) {

        public Endpoint {
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    /** OAuth client of inventory-service; a blank secret disables the token (local profile). */
    public record Auth(String tokenUri, String clientId, String clientSecret, String scopes) {

        public Auth {
            tokenUri = tokenUri == null ? "http://localhost:8100/oauth2/token" : tokenUri;
            clientId = clientId == null ? "inventory-service" : clientId;
        }
    }

    /** FBA storage metering: volume billed for a unit whose SKU has no dimensions yet (default 0.25 ft³). */
    public record Storage(BigDecimal defaultUnitCubicFeet) {

        public Storage {
            defaultUnitCubicFeet = defaultUnitCubicFeet == null ? new BigDecimal("0.25") : defaultUnitCubicFeet;
        }
    }

    /** Short-dated rule: remaining life below max(minDays, minFraction × shelf life) is quarantined. */
    public record ShelfLife(Integer minDays, Double minFraction) {

        public ShelfLife {
            minDays = minDays == null ? 90 : minDays;
            minFraction = minFraction == null ? 0.5 : minFraction;
        }
    }

    /** Job switches; cron expressions are read directly by the scheduler annotations. */
    public record Jobs(Boolean enabled, Integer reservationSweepBatch) {

        public Jobs {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            reservationSweepBatch = reservationSweepBatch == null ? 200 : reservationSweepBatch;
        }
    }
}
