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
 * @param media            media-service endpoint (PO PDFs, shipment documents)
 * @param dispatch         sending POs to suppliers
 * @param edi              EDI transport (off by default)
 */
@ConfigurationProperties(prefix = "at.retail")
public record RetailProperties(String baseCurrency, String gs1CompanyPrefix, String retailOwnerOrg, Approval approval,
                               Receiving receiving, Match match, Map<String, BigDecimal> fxRates, Endpoint catalog,
                               Endpoint inventory, Auth auth, Endpoint media, Dispatch dispatch, Edi edi) {

    public RetailProperties {
        baseCurrency = baseCurrency == null ? "USD" : baseCurrency;
        gs1CompanyPrefix = gs1CompanyPrefix == null ? "04000" : gs1CompanyPrefix;
        retailOwnerOrg = retailOwnerOrg == null || retailOwnerOrg.isBlank() ? null : retailOwnerOrg;
        approval = approval == null ? new Approval(null) : approval;
        receiving = receiving == null ? new Receiving(null, null, null, null, null, null) : receiving;
        match = match == null ? new Match(null) : match;
        fxRates = fxRates == null ? Map.of() : Map.copyOf(fxRates);
        catalog = catalog == null ? new Endpoint("http://localhost:8101", null) : catalog;
        inventory = inventory == null ? new Endpoint("http://localhost:8103", null) : inventory;
        auth = auth == null ? new Auth(null, null, null, null) : auth;
        media = media == null ? new Endpoint("http://localhost:8207", null) : media;
        dispatch = dispatch == null ? new Dispatch(null, null, null, null, null, null) : dispatch;
        edi = edi == null ? new Edi(false, null, null, null, null, null, null) : edi;
    }

    /**
     * Sending POs to suppliers (S225).
     *
     * @param maxAttempts   attempts per channel before a dispatch fails
     * @param linkTtl       validity asked for the emailed PDF link
     * @param emailTemplate notifications template key of the supplier email
     * @param buyerName     buyer printed on the PDF
     * @param buyerAddress  buyer address printed on the PDF
     * @param senderId      our ISA sender id
     */
    public record Dispatch(Integer maxAttempts, Duration linkTtl, String emailTemplate, String buyerName,
                           String buyerAddress, String senderId) {

        public Dispatch {
            maxAttempts = maxAttempts == null ? 5 : maxAttempts;
            linkTtl = linkTtl == null ? Duration.ofDays(14) : linkTtl;
            emailTemplate = emailTemplate == null ? "retail.purchase_order.sent" : emailTemplate;
            buyerName = buyerName == null ? "AfroTransact Retail" : buyerName;
            buyerAddress = buyerAddress == null ? "Houston, TX, USA" : buyerAddress;
            senderId = senderId == null ? "AFROTRANSACT" : senderId;
        }
    }

    /**
     * EDI transport: off by default; SFTP and AS2 adapters are skeletons until a client library is wired.
     *
     * @param enabled           transmit 850s at all
     * @param sftpUsername      SFTP login
     * @param sftpPrivateKeyPath SFTP private key (mounted secret)
     * @param sftpKnownHostsPath pinned host keys
     * @param as2From           our AS2 id
     * @param as2KeystorePath   signing / decryption keystore (mounted secret)
     * @param timeout           transport timeout
     */
    public record Edi(boolean enabled, String sftpUsername, String sftpPrivateKeyPath, String sftpKnownHostsPath,
                      String as2From, String as2KeystorePath, Duration timeout) {

        public Edi {
            timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        }
    }

    /** POs above {@code financeThresholdMinor} (base currency minor units) need Finance approval. */
    public record Approval(Long financeThresholdMinor) {

        public Approval {
            financeThresholdMinor = financeThresholdMinor == null ? 1_000_000L : financeThresholdMinor;
        }
    }

    /**
     * Over-receipt tolerance and minimum remaining shelf life at the dock, and the warehouse calendar (S226): node
     * time zones come from inventory-service (cached {@code zoneCacheTtl}); {@code warehouseZones} are used for nodes
     * inventory has not answered for, then {@code defaultZone}.
     */
    public record Receiving(BigDecimal overTolerancePercent, Integer minRemainingDays, Double minRemainingFraction,
                            Map<String, String> warehouseZones, String defaultZone, Duration zoneCacheTtl) {

        public Receiving {
            overTolerancePercent = overTolerancePercent == null ? BigDecimal.valueOf(2) : overTolerancePercent;
            minRemainingDays = minRemainingDays == null ? 90 : minRemainingDays;
            minRemainingFraction = minRemainingFraction == null ? 0.5 : minRemainingFraction;
            warehouseZones = warehouseZones == null ? Map.of() : Map.copyOf(warehouseZones);
            defaultZone = defaultZone == null || defaultZone.isBlank() ? "UTC" : defaultZone;
            zoneCacheTtl = zoneCacheTtl == null ? Duration.ofHours(1) : zoneCacheTtl;
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
