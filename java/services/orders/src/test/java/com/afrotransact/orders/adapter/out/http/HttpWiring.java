package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;

import com.afrotransact.orders.application.OrdersSettings;
import com.afrotransact.orders.port.AddressBook;
import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.orders.port.InventoryClient;
import com.afrotransact.orders.port.MarketEligibility;
import com.afrotransact.orders.port.MemberBenefits;
import com.afrotransact.orders.port.OfferInventory;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.PickupLocations;
import com.afrotransact.orders.port.ProductFactsClient;
import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.ShipFromAddresses;
import com.afrotransact.orders.port.StaffDirectory;
import com.afrotransact.orders.port.TaxQuoteClient;
import org.springframework.web.client.RestClient;

/** Test-only factory for the package-private HTTP adapters of orders, all pointed at one base URL (one scripted callee). */
public final class HttpWiring {

    private static final ServiceTokenProvider TOKEN = () -> "test-token";

    private HttpWiring() {
    }

    public static InventoryClient inventory(String url, Duration timeout) {
        return new HttpInventoryClient(RestClient.builder(), url, timeout, TOKEN);
    }

    public static PaymentsClient payments(String url, Duration timeout) {
        return new HttpPaymentsClient(RestClient.builder(), url, timeout, TOKEN);
    }

    public static CouponsClient coupons(String url, Duration timeout) {
        return new HttpCouponsClient(RestClient.builder(), url, timeout, TOKEN);
    }

    public static OfferInventory offerInventory(String url, Duration timeout) {
        return new HttpOfferInventory(RestClient.builder(), url, timeout, TOKEN);
    }

    public static TaxQuoteClient tax(String url, Duration timeout) {
        return new HttpTaxQuoteClient(RestClient.builder(), url, timeout, TOKEN);
    }

    public static ProductFactsClient productFacts(String url, Duration timeout, OrdersSettings settings) {
        return new HttpProductFactsClient(RestClient.builder(), url, timeout, settings);
    }

    public static MarketEligibility markets(String url, Duration timeout) {
        return new HttpMarketEligibility(RestClient.builder(), url, timeout);
    }

    public static MemberBenefits benefits(String url, Duration timeout) {
        return new HttpMemberBenefits(RestClient.builder(), url, timeout, TOKEN);
    }

    public static PickupLocations pickupLocations(String url, Duration timeout) {
        return new HttpPickupLocations(RestClient.builder(), url, url, timeout, TOKEN);
    }

    public static AddressBook addressBook(String url, Duration timeout) {
        return new HttpAddressBook(RestClient.builder(), url, timeout);
    }

    public static StaffDirectory staff(String url, Duration timeout) {
        return new HttpStaffDirectory(RestClient.builder(), url, timeout, TOKEN);
    }

    public static ShipFromAddresses shipFrom(String url, Duration timeout) {
        return new HttpShipFromAddresses(RestClient.builder(), url, url, timeout, TOKEN);
    }
}
