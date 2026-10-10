package com.afrotransact.taxreturns.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.taxreturns.*}.
 *
 * @param tax       tax provider and product-type → tax-category mapping
 * @param returns   platform return policy and label settings
 * @param upstreams payments, inventory, carrier-gateway and catalog URLs and the client-credentials client
 * @param consumers Kafka topics consumed
 * @param jobs      background retries and SLA auto-approval
 */
@ConfigurationProperties(prefix = "at.taxreturns")
public record TaxReturnsProperties(Tax tax, Returns returns, Upstreams upstreams, Consumers consumers, Jobs jobs) {

    public TaxReturnsProperties {
        tax = tax == null ? new Tax(null, null) : tax;
        returns = returns == null ? new Returns(0, null, null, 0, 0, null, 0, 0, 0, null, null, null) : returns;
        upstreams = upstreams == null ? new Upstreams(null, null, null, null, null, null, null, null, null, null)
                : upstreams;
        consumers = consumers == null ? new Consumers(null, null, null) : consumers;
        jobs = jobs == null ? new Jobs(null, null, 0) : jobs;
    }

    /** Which {@code TaxCalculator} adapter answers quotes. Only {@code RULES} ships; the others need an adapter. */
    public enum TaxProvider {
        RULES,
        STRIPE_TAX,
        TAXJAR,
        AVALARA
    }

    /**
     * @param provider                tax engine (default RULES, no external calls)
     * @param categoryByProductType   catalog product type → GENERAL | GROCERY | PREPARED_FOOD (default GENERAL)
     */
    public record Tax(TaxProvider provider, Map<String, String> categoryByProductType) {

        public Tax {
            provider = provider == null ? TaxProvider.RULES : provider;
            categoryByProductType = categoryByProductType == null ? Map.of() : Map.copyOf(categoryByProductType);
        }
    }

    /** Return policy defaults; see {@code ReturnPolicySettings}. */
    public record Returns(int defaultWindowDays, Map<String, Integer> windowDaysByProductType,
                          List<String> nonReturnableProductTypes, long lowValueThresholdMinor,
                          long firstScanRefundMaxMinor, Duration sellerApprovalSla, int dropOffDays,
                          int maxRestockingFeeBps, int maxEvidence, Address returnCentre, Label label,
                          Integer pickupReadyDays) {

        public Returns {
            defaultWindowDays = defaultWindowDays <= 0 ? 30 : defaultWindowDays;
            windowDaysByProductType = windowDaysByProductType == null ? Map.of() : Map.copyOf(windowDaysByProductType);
            nonReturnableProductTypes = nonReturnableProductTypes == null ? List.of()
                    : List.copyOf(nonReturnableProductTypes);
            sellerApprovalSla = sellerApprovalSla == null ? Duration.ofHours(48) : sellerApprovalSla;
            dropOffDays = dropOffDays <= 0 ? 14 : dropOffDays;
            maxRestockingFeeBps = maxRestockingFeeBps <= 0 ? 2000 : maxRestockingFeeBps;
            maxEvidence = maxEvidence <= 0 ? 6 : maxEvidence;
            returnCentre = returnCentre == null ? new Address(null, null, null, null, null, null, null, null, null, null)
                    : returnCentre;
            label = label == null ? new Label(null, null, 0, null, null, null) : label;
            pickupReadyDays = pickupReadyDays == null ? 1 : Math.max(0, pickupReadyDays);
        }
    }

    /**
     * AfroTransact return centre (destination of AfroTransact-fulfilled returns): its carrier-gateway origin node and
     * the inventory node (warehouse) that receives the parcels ({@code inventoryNode}, sent as receivingNodeId).
     */
    public record Address(String node, String name, String line1, String line2, String city, String state,
                          String postalCode, String country, String phone, String inventoryNode) {
    }

    /**
     * Return labels from carrier-gateway {@code POST /v1/return-labels}.
     *
     * @param carrier    carrier for rateChoice SERVICE
     * @param service    service for rateChoice SERVICE
     * @param rateChoice CHEAPEST (default), FASTEST or SERVICE (carrier + service above)
     * @param carriers   carriers rate shopping may use (empty = all enabled)
     * @param boxFree    OFF (printed label, default) or PREFERRED (label-free QR drop-off where a carrier offers it,
     *                   else a printed label)
     */
    public record Label(String carrier, String service, double weightLb, String rateChoice, List<String> carriers,
                        String boxFree) {

        public Label {
            carrier = carrier == null || carrier.isBlank() ? "ups" : carrier;
            service = service == null || service.isBlank() ? "03" : service;
            weightLb = weightLb <= 0 ? 2.0 : weightLb;
            rateChoice = rateChoice == null || rateChoice.isBlank() ? "CHEAPEST" : rateChoice.trim();
            carriers = carriers == null ? List.of() : List.copyOf(carriers);
            boxFree = boxFree == null || boxFree.isBlank() ? "OFF" : boxFree.trim();
        }
    }

    /**
     * @param tokenUri      identity-service token endpoint
     * @param clientId      client-credentials client ({@code tax-returns-service})
     * @param clientSecret  only from the environment; blank disables tokens (local profile)
     * @param timeout       connect + read timeout per call
     * @param ordersUrl     orders-service (order lookup, {@code orders:lookup})
     * @param fulfilmentUrl fulfilment-service (return pickups, {@code delivery:returns})
     */
    public record Upstreams(String tokenUri, String clientId, String clientSecret, Duration timeout,
                            String paymentsUrl, String inventoryUrl, String carrierUrl, String catalogUrl,
                            String ordersUrl, String fulfilmentUrl) {

        public Upstreams {
            clientId = clientId == null || clientId.isBlank() ? "tax-returns-service" : clientId;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    /** Consumed topics. */
    public record Consumers(String orderTopic, String paymentTopic, String carrierTopic) {

        public Consumers {
            orderTopic = orderTopic == null ? "at.order.events" : orderTopic;
            paymentTopic = paymentTopic == null ? "at.payment.events" : paymentTopic;
            carrierTopic = carrierTopic == null ? "at.carrier-shipment.events" : carrierTopic;
        }
    }

    /**
     * @param enabled     run the background jobs
     * @param interval    pause between runs
     * @param batchSize   items per job and run
     */
    public record Jobs(Boolean enabled, Duration interval, int batchSize) {

        public Jobs {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            interval = interval == null ? Duration.ofSeconds(60) : interval;
            batchSize = batchSize <= 0 ? 50 : batchSize;
        }
    }
}
