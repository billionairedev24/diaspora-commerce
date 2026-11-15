package com.afrotransact.orders.adapter.out.module;

import java.util.Set;

import com.afrotransact.catalog.api.ProductListingApi;
import com.afrotransact.content.api.MarketEligibilityApi;
import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.offers.api.CheckoutOffersApi;
import com.afrotransact.orders.application.OrdersSettings;
import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.orders.port.InventoryClient;
import com.afrotransact.orders.port.MarketEligibility;
import com.afrotransact.orders.port.MemberBenefits;
import com.afrotransact.orders.port.OfferInventory;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.ProductFactsClient;
import com.afrotransact.orders.port.TaxQuoteClient;
import com.afrotransact.payments.api.MembershipBenefitsApi;
import com.afrotransact.payments.api.OrderPaymentsApi;
import com.afrotransact.taxreturns.api.TaxQuoteApi;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Test-only factory for the package-private in-process adapters of orders, so tests in other packages (the checkout saga
 * tests live next to the activities) wire the REAL adapters over a stand-in for the called module's published API. The
 * caller principal is orders' own, as the principal registry of core gives it.
 */
public final class InProcessWiring {

    /** The principal orders runs in-process calls as (the client id identity grants the orders service). */
    public static final CallerPrincipal ORDERS = new CallerPrincipal("orders", "orders-service",
            Set.of("inventory:reserve", "inventory:read", "payments:service", "tax:quote", "offers:read",
                    "sellers:ship-from.read"));

    private InProcessWiring() {
    }

    private static <A> DefaultListableBeanFactory beans(Class<A> type, A api) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("api", api);
        beans.registerSingleton("callers", (CallerPrincipals) module -> ORDERS);
        beans.registerSingleton("upstream", new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class)));
        return beans;
    }

    public static InventoryClient inventory(ReservationApi api) {
        DefaultListableBeanFactory b = beans(ReservationApi.class, api);
        return new InProcessInventoryClient(b.getBeanProvider(ReservationApi.class), b.getBean(ModuleUpstream.class), b);
    }

    public static PaymentsClient payments(OrderPaymentsApi api) {
        DefaultListableBeanFactory b = beans(OrderPaymentsApi.class, api);
        return new InProcessPaymentsClient(b.getBeanProvider(OrderPaymentsApi.class), b.getBean(ModuleUpstream.class), b);
    }

    public static CouponsClient coupons(CheckoutOffersApi api) {
        DefaultListableBeanFactory b = beans(CheckoutOffersApi.class, api);
        return new InProcessCouponsClient(b.getBeanProvider(CheckoutOffersApi.class), b.getBean(ModuleUpstream.class), b);
    }

    public static OfferInventory offerInventory(CheckoutOffersApi api) {
        DefaultListableBeanFactory b = beans(CheckoutOffersApi.class, api);
        return new InProcessOfferInventory(b.getBeanProvider(CheckoutOffersApi.class), b.getBean(ModuleUpstream.class), b);
    }

    public static TaxQuoteClient tax(TaxQuoteApi api) {
        DefaultListableBeanFactory b = beans(TaxQuoteApi.class, api);
        return new InProcessTaxQuoteClient(b.getBeanProvider(TaxQuoteApi.class), b.getBean(ModuleUpstream.class), b);
    }

    public static ProductFactsClient productFacts(ProductListingApi api, OrdersSettings settings) {
        DefaultListableBeanFactory b = beans(ProductListingApi.class, api);
        return new InProcessProductFactsClient(b.getBeanProvider(ProductListingApi.class),
                b.getBean(ModuleUpstream.class), settings, b);
    }

    public static MarketEligibility markets(MarketEligibilityApi api) {
        DefaultListableBeanFactory b = beans(MarketEligibilityApi.class, api);
        return new InProcessMarketEligibility(b.getBeanProvider(MarketEligibilityApi.class),
                b.getBean(ModuleUpstream.class), b);
    }

    public static MemberBenefits benefits(MembershipBenefitsApi api) {
        DefaultListableBeanFactory b = beans(MembershipBenefitsApi.class, api);
        return new InProcessMemberBenefits(b.getBeanProvider(MembershipBenefitsApi.class),
                b.getBean(ModuleUpstream.class), b);
    }
}
