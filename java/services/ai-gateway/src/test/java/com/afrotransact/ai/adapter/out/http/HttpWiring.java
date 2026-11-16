package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;

import com.afrotransact.ai.port.BookingHistory;
import com.afrotransact.ai.port.CatalogReader;
import com.afrotransact.ai.port.OfferReader;
import com.afrotransact.ai.port.OrderHistory;
import com.afrotransact.ai.port.OrderTracker;
import com.afrotransact.ai.port.ReturnHistory;
import com.afrotransact.ai.port.ServiceDirectory;
import org.springframework.web.client.RestClient;

/** Test-only factory for the package-private HTTP readers, so the parity test in the module package can run both ways of reading. */
public final class HttpWiring {

    private HttpWiring() {
    }

    public static CatalogReader catalog(String url, Duration timeout) {
        return new HttpCatalogReader(RestClient.builder(), url, timeout);
    }

    public static OfferReader offers(String url, Duration timeout) {
        return new HttpOfferReader(RestClient.builder(), url, timeout);
    }

    public static OrderTracker tracker(String url, Duration timeout) {
        return new HttpOrderTracker(RestClient.builder(), url, timeout);
    }

    public static ServiceDirectory services(String url, Duration timeout) {
        return new HttpServiceDirectory(RestClient.builder(), url, timeout);
    }

    public static OrderHistory orders(String url, Duration timeout) {
        return new HttpOrderHistory(RestClient.builder(), url, timeout);
    }

    public static BookingHistory bookings(String url, Duration timeout) {
        return new HttpBookingHistory(RestClient.builder(), url, timeout);
    }

    public static ReturnHistory returns(String url, Duration timeout) {
        return new HttpReturnHistory(RestClient.builder(), url, timeout);
    }
}
