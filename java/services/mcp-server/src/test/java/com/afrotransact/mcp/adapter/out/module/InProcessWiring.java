package com.afrotransact.mcp.adapter.out.module;

import java.util.Set;

import com.afrotransact.bookings.api.UserServiceSearchApi;
import com.afrotransact.catalog.api.CatalogUserApi;
import com.afrotransact.inventory.api.InventoryUserApi;
import com.afrotransact.mcp.port.ApplicationsApi;
import com.afrotransact.mcp.port.BookingsApi;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.InventoryApi;
import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.mcp.port.PaymentsApi;
import com.afrotransact.mcp.port.SellerApi;
import com.afrotransact.mcp.port.TaxApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.orders.api.OrdersUserApi;
import com.afrotransact.payments.api.PayoutReadApi;
import com.afrotransact.seller.api.SellerUserApi;
import com.afrotransact.taxreturns.api.TaxReviewReadApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Test-only factory for the package-private in-process adapters of mcp-server, so tests in other packages wire the REAL adapters over a
 * stand-in for the called module's published API. The caller principal is mcp-server's own, as core's principal registry gives it.
 */
public final class InProcessWiring {

    /** The principal mcp-server runs in-process calls as: the delegation scopes core grants it, nothing else. */
    public static final CallerPrincipal MCP_SERVER = new CallerPrincipal("mcp-server", "mcp-server-service",
            Set.of(CatalogUserApi.READ_SCOPE, CatalogUserApi.WRITE_SCOPE, InventoryUserApi.READ_SCOPE, OrdersUserApi.READ_SCOPE,
                    OrdersUserApi.WRITE_SCOPE, PayoutReadApi.READ_SCOPE, SellerUserApi.READ_SCOPE, UserServiceSearchApi.READ_SCOPE,
                    TaxReviewReadApi.READ_SCOPE));

    private InProcessWiring() {
    }

    private static <A, P> P wire(Class<A> type, A api, Factory<A, P> factory) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("api", api);
        beans.registerSingleton("callers", (CallerPrincipals) module -> MCP_SERVER);
        return factory.create(beans.getBeanProvider(type), new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class)), beans);
    }

    private interface Factory<A, P> {
        P create(ObjectProvider<A> api, ModuleUpstream upstream,
                 ListableBeanFactory beans);
    }

    public static CatalogApi catalog(CatalogUserApi api) {
        return wire(CatalogUserApi.class, api, InProcessCatalogApi::new);
    }

    public static InventoryApi inventory(InventoryUserApi api) {
        return wire(InventoryUserApi.class, api, InProcessInventoryApi::new);
    }

    public static OrdersApi orders(OrdersUserApi api) {
        return wire(OrdersUserApi.class, api, InProcessOrdersApi::new);
    }

    public static PaymentsApi payments(PayoutReadApi api) {
        return wire(PayoutReadApi.class, api, InProcessPaymentsApi::new);
    }

    public static SellerApi seller(SellerUserApi api) {
        return wire(SellerUserApi.class, api, InProcessSellerApi::new);
    }

    public static ApplicationsApi applications(SellerUserApi api) {
        return wire(SellerUserApi.class, api, InProcessApplicationsApi::new);
    }

    public static BookingsApi bookings(UserServiceSearchApi api) {
        return wire(UserServiceSearchApi.class, api, InProcessBookingsApi::new);
    }

    public static TaxApi tax(TaxReviewReadApi api) {
        return wire(TaxReviewReadApi.class, api, InProcessTaxApi::new);
    }
}
