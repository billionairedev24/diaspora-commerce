package com.afrotransact.deliveryruns.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.delivery-runs.*} settings.
 *
 * @param publicWebUrl       storefront origin ({@code PUBLIC_WEB_URL}); links in events, advertorials and pages
 * @param locationPath       storefront path of location pages
 * @param reminderLead       cutoff reminder lead time
 * @param confirmationTtl    email double opt-in link lifetime
 * @param nextRunSearchLimit later runs a full / closed run's order may roll over to
 * @param origin             default run origin (FC / hub) for economics
 * @param geo                geo-service client
 * @param economics          driver cost model
 * @param jobs               cutoff / reminder / auto-open job
 */
@ConfigurationProperties(prefix = "at.delivery-runs")
public record DeliveryRunsProperties(String publicWebUrl, String locationPath, Duration reminderLead,
                                     Duration confirmationTtl, Integer nextRunSearchLimit, Origin origin, Geo geo,
                                     Economics economics, Jobs jobs) {

    public DeliveryRunsProperties {
        publicWebUrl = publicWebUrl == null || publicWebUrl.isBlank() ? "http://localhost:3000" : publicWebUrl;
        locationPath = locationPath == null || locationPath.isBlank() ? "/delivery-runs" : locationPath;
        reminderLead = reminderLead == null ? Duration.ofHours(24) : reminderLead;
        confirmationTtl = confirmationTtl == null ? Duration.ofHours(72) : confirmationTtl;
        nextRunSearchLimit = nextRunSearchLimit == null ? 5 : nextRunSearchLimit;
        origin = origin == null ? new Origin(null, null) : origin;
        geo = geo == null ? new Geo(null, null, null, null, null, null) : geo;
        economics = economics == null ? new Economics(null, null, null, null, null, null, null) : economics;
        jobs = jobs == null ? new Jobs(null, null, null) : jobs;
    }

    /** Default origin of runs (Austin, TX unless configured). */
    public record Origin(Double lat, Double lng) {

        public Origin {
            lat = lat == null ? 30.2672 : lat;
            lng = lng == null ? -97.7431 : lng;
        }
    }

    /**
     * geo-service client.
     *
     * @param zoneType     zone type of run-area polygons and coverage lookups
     * @param tokenUri     identity token endpoint (client credentials, scope geo:zones:write)
     * @param clientId     OAuth client of this service
     * @param clientSecret its secret (env only)
     */
    public record Geo(String baseUrl, Duration timeout, String zoneType, String tokenUri, String clientId,
                      String clientSecret) {

        public Geo {
            baseUrl = baseUrl == null ? "http://localhost:8205" : baseUrl;
            timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
            zoneType = zoneType == null || zoneType.isBlank() ? "SERVICE_AREA" : zoneType;
            tokenUri = tokenUri == null ? "http://localhost:8100/oauth2/token" : tokenUri;
            clientId = clientId == null ? "delivery-runs-service" : clientId;
            clientSecret = clientSecret == null ? "" : clientSecret;
        }
    }

    /** Driver cost model (see {@code EconomicsSettings}). */
    public record Economics(Long driverCostPerHourMinor, Long driverCostPerMileMinor, Double collectionMinutes,
                            Double perStopMinutes, Double pickupPointStopMinutes, Double handoverMinutesPerOrder,
                            Boolean roundTrip) {

        public Economics {
            driverCostPerHourMinor = driverCostPerHourMinor == null ? 2500L : driverCostPerHourMinor;
            driverCostPerMileMinor = driverCostPerMileMinor == null ? 70L : driverCostPerMileMinor;
            collectionMinutes = collectionMinutes == null ? 45.0 : collectionMinutes;
            perStopMinutes = perStopMinutes == null ? 6.0 : perStopMinutes;
            pickupPointStopMinutes = pickupPointStopMinutes == null ? 30.0 : pickupPointStopMinutes;
            handoverMinutesPerOrder = handoverMinutesPerOrder == null ? 1.0 : handoverMinutesPerOrder;
            roundTrip = roundTrip == null || roundTrip;
        }
    }

    /**
     * Scheduled job.
     *
     * @param enabled   run the job in this process
     * @param interval  delay between ticks
     * @param batchSize runs per step per tick
     */
    public record Jobs(Boolean enabled, Duration interval, Integer batchSize) {

        public Jobs {
            enabled = enabled == null || enabled;
            interval = interval == null ? Duration.ofSeconds(60) : interval;
            batchSize = batchSize == null ? 50 : batchSize;
        }
    }
}
