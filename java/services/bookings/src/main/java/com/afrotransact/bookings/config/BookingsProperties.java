package com.afrotransact.bookings.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.bookings.*}.
 *
 * @param holdTtl              slot hold while the customer authorises the deposit
 * @param requestSla           provider answer window for requests (design: 4 h)
 * @param autoCompleteAfter    confirmed bookings auto-complete this long after they end (design: 48 h)
 * @param lateProviderFeeMinor provider cancellation under 24 h (design: $15)
 * @param manageUrlBase        customer link to manage a booking (absolute; built from PUBLIC_WEB_URL)
 * @param fees                 fallback commission and card fees while payments' fee schedule is unavailable
 * @param balance              long-dated bookings: balance release and off-session charge
 * @param payments             payments-service client
 * @param geo                  geo-service client (service areas for ZIP search)
 * @param jobs                 background jobs
 * @param retailOrgId          AfroTransact Retail (1P): no commission, no late-cancellation fee or strike
 */
@ConfigurationProperties(prefix = "at.bookings")
public record BookingsProperties(Duration holdTtl, Duration requestSla, Duration autoCompleteAfter,
                                 Long lateProviderFeeMinor, String manageUrlBase, Fees fees, Balance balance,
                                 Payments payments, Geo geo, Jobs jobs, String retailOrgId) {

    public BookingsProperties {
        holdTtl = holdTtl == null ? Duration.ofMinutes(15) : holdTtl;
        requestSla = requestSla == null ? Duration.ofHours(4) : requestSla;
        autoCompleteAfter = autoCompleteAfter == null ? Duration.ofHours(48) : autoCompleteAfter;
        lateProviderFeeMinor = lateProviderFeeMinor == null ? 1500L : lateProviderFeeMinor;
        manageUrlBase = manageUrlBase == null ? "http://localhost:3000/account/appointments/" : manageUrlBase;
        fees = fees == null ? new Fees(null, null, null, null) : fees;
        balance = balance == null ? new Balance(null, null, null, null) : balance;
        payments = payments == null ? new Payments(null, null, null, null, null) : payments;
        geo = geo == null ? new Geo(null, null) : geo;
        jobs = jobs == null ? new Jobs(null) : jobs;
        retailOrgId = retailOrgId == null || retailOrgId.isBlank() ? "org_afrotransact_retail" : retailOrgId.trim();
    }

    /**
     * Take-home and earnings fees come from payments ({@code GET /v1/fee-schedules/effective}); these values are
     * the fallback while payments cannot answer.
     *
     * @param cacheTtl how long payments' answer is reused per organisation
     */
    public record Fees(Integer commissionBps, Integer cardPctBps, Long cardFixedMinor, Duration cacheTtl) {

        public Fees {
            commissionBps = commissionBps == null ? 1000 : commissionBps;
            cardPctBps = cardPctBps == null ? 290 : cardPctBps;
            cardFixedMinor = cardFixedMinor == null ? 30L : cardFixedMinor;
            cacheTtl = cacheTtl == null ? Duration.ofMinutes(5) : cacheTtl;
        }
    }

    /**
     * Long-dated bookings.
     *
     * @param authorisationWindow release the balance hold right after the deposit capture when the appointment is
     *                            further away than this (card holds last ~7 days; completion can follow the end
     *                            by up to the auto-complete delay)
     * @param chargeBefore        charge the balance off-session this long before the appointment
     * @param maxAttempts         declined off-session attempts before the booking is flagged
     * @param retryDelay          wait between declined attempts
     */
    public record Balance(Duration authorisationWindow, Duration chargeBefore, Integer maxAttempts,
                          Duration retryDelay) {

        public Balance {
            authorisationWindow = authorisationWindow == null ? Duration.ofDays(5) : authorisationWindow;
            chargeBefore = chargeBefore == null ? Duration.ofDays(2) : chargeBefore;
            maxAttempts = maxAttempts == null ? 3 : maxAttempts;
            retryDelay = retryDelay == null ? Duration.ofHours(6) : retryDelay;
        }
    }

    /** geo-service (public reads, no token). */
    public record Geo(String baseUrl, Duration timeout) {

        public Geo {
            baseUrl = baseUrl == null ? "http://localhost:8205" : baseUrl;
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        }
    }

    /**
     * payments-service and the client-credentials token used to call it.
     *
     * @param clientSecret blank = call payments without a token (local profile, security off)
     */
    public record Payments(String baseUrl, String tokenUri, String clientId, String clientSecret, Duration timeout) {

        public Payments {
            baseUrl = baseUrl == null ? "http://localhost:8105" : baseUrl;
            tokenUri = tokenUri == null ? "http://localhost:8100/oauth2/token" : tokenUri;
            clientId = clientId == null ? "bookings-service" : clientId;
            clientSecret = clientSecret == null ? "" : clientSecret;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }
    }

    /** Background jobs. */
    public record Jobs(Boolean enabled) {

        public Jobs {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }
    }
}
