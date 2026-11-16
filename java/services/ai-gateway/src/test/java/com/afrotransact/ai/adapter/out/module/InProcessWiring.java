package com.afrotransact.ai.adapter.out.module;

import java.util.Set;

import com.afrotransact.ai.port.BookingHistory;
import com.afrotransact.ai.port.CatalogReader;
import com.afrotransact.ai.port.OfferReader;
import com.afrotransact.ai.port.OrderHistory;
import com.afrotransact.ai.port.OrderTracker;
import com.afrotransact.ai.port.ReturnHistory;
import com.afrotransact.ai.port.ServiceDirectory;
import com.afrotransact.bookings.api.ServiceSearchApi;
import com.afrotransact.bookings.api.ShopperBookingsApi;
import com.afrotransact.catalog.api.PublicProductApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.offers.api.PublicOffersApi;
import com.afrotransact.orders.api.ShopperOrdersApi;
import com.afrotransact.taxreturns.api.ShopperReturnsApi;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Test-only factory for the package-private in-process readers of ai-gateway over stand-ins for the called modules' published APIs. The
 * caller principal is ai-gateway's own, as the principal registry of core gives it (the three delegation scopes of {@code application.yml}).
 */
final class InProcessWiring {

    static final CallerPrincipal AI_GATEWAY = new CallerPrincipal("ai-gateway", "ai-gateway-service",
            Set.of("orders:mine.read", "bookings:mine.read", "returns:mine.read"));

    private InProcessWiring() {
    }

    private static <A> DefaultListableBeanFactory beans(Class<A> type, A api) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("api", api);
        beans.registerSingleton("callers", (CallerPrincipals) module -> AI_GATEWAY);
        beans.registerSingleton("upstream", new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class)));
        return beans;
    }

    static CatalogReader catalog(PublicProductApi api) {
        DefaultListableBeanFactory b = beans(PublicProductApi.class, api);
        return new InProcessCatalogReader(b.getBeanProvider(PublicProductApi.class), b.getBean(ModuleUpstream.class), b);
    }

    static OfferReader offers(PublicOffersApi api) {
        DefaultListableBeanFactory b = beans(PublicOffersApi.class, api);
        return new InProcessOfferReader(b.getBeanProvider(PublicOffersApi.class), b.getBean(ModuleUpstream.class), b);
    }

    static OrderTracker tracker(ShopperOrdersApi api) {
        DefaultListableBeanFactory b = beans(ShopperOrdersApi.class, api);
        return new InProcessOrderTracker(b.getBeanProvider(ShopperOrdersApi.class), b.getBean(ModuleUpstream.class), b);
    }

    static OrderHistory orders(ShopperOrdersApi api) {
        DefaultListableBeanFactory b = beans(ShopperOrdersApi.class, api);
        return new InProcessOrderHistory(b.getBeanProvider(ShopperOrdersApi.class), b.getBean(ModuleUpstream.class), b);
    }

    static ServiceDirectory services(ServiceSearchApi api) {
        DefaultListableBeanFactory b = beans(ServiceSearchApi.class, api);
        return new InProcessServiceDirectory(b.getBeanProvider(ServiceSearchApi.class), b.getBean(ModuleUpstream.class), b);
    }

    static BookingHistory bookings(ShopperBookingsApi api) {
        DefaultListableBeanFactory b = beans(ShopperBookingsApi.class, api);
        return new InProcessBookingHistory(b.getBeanProvider(ShopperBookingsApi.class), b.getBean(ModuleUpstream.class), b);
    }

    static ReturnHistory returns(ShopperReturnsApi api) {
        DefaultListableBeanFactory b = beans(ShopperReturnsApi.class, api);
        return new InProcessReturnHistory(b.getBeanProvider(ShopperReturnsApi.class), b.getBean(ModuleUpstream.class), b);
    }
}
