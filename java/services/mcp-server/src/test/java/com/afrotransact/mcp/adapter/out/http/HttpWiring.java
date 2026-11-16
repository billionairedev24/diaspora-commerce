package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;

import com.afrotransact.mcp.port.ApplicationsApi;
import com.afrotransact.mcp.port.BookingsApi;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.InventoryApi;
import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.mcp.port.PaymentsApi;
import com.afrotransact.mcp.port.SellerApi;
import com.afrotransact.mcp.port.TaxApi;
import org.springframework.web.client.RestClient;

/** Test-only factory for the package-private HTTP adapters of mcp-server, all pointed at one base URL (one scripted callee). */
public final class HttpWiring {

    private HttpWiring() {
    }

    public static CatalogApi catalog(String url, Duration timeout) {
        return new HttpCatalogApi(RestClient.builder(), url, timeout);
    }

    public static InventoryApi inventory(String url, Duration timeout) {
        return new HttpInventoryApi(RestClient.builder(), url, timeout);
    }

    public static OrdersApi orders(String url, Duration timeout) {
        return new HttpOrdersApi(RestClient.builder(), url, timeout);
    }

    public static PaymentsApi payments(String url, Duration timeout) {
        return new HttpPaymentsApi(RestClient.builder(), url, timeout);
    }

    public static SellerApi seller(String url, Duration timeout) {
        return new HttpSellerApi(RestClient.builder(), url, timeout);
    }

    public static ApplicationsApi applications(String url, Duration timeout) {
        return new HttpApplicationsApi(RestClient.builder(), url, timeout);
    }

    public static BookingsApi bookings(String url, Duration timeout) {
        return new HttpBookingsApi(RestClient.builder(), url, timeout);
    }

    public static TaxApi tax(String url, Duration timeout) {
        return new HttpTaxApi(RestClient.builder(), url, timeout);
    }
}
