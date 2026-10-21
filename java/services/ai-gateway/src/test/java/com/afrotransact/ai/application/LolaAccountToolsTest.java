package com.afrotransact.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;

import com.afrotransact.ai.domain.guardrail.GroundingFacts;
import com.afrotransact.ai.domain.guardrail.InjectionFilter;
import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.AiMetrics;
import com.afrotransact.ai.port.CatalogReader;
import com.afrotransact.ai.port.DeliveryPromises;
import com.afrotransact.ai.port.OfferReader;
import com.afrotransact.ai.port.OrderTracker;
import com.afrotransact.ai.port.ProductSearch;
import com.afrotransact.ai.port.ServiceDirectory;
import com.afrotransact.ai.port.ToolCallRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Lola's read tools for the shopper's own account: order history, cart and bookings. */
class LolaAccountToolsTest {

    private AccountReader.Result<List<AccountReader.OrderSummary>> orders = AccountReader.Result.of(List.of());
    private AccountReader.Result<AccountReader.CartSummary> cart = AccountReader.Result.unavailable();
    private AccountReader.Result<List<AccountReader.BookingSummary>> bookings = AccountReader.Result.of(List.of());

    private AccountReader.Result<List<AccountReader.ReturnSummary>> returns = AccountReader.Result.of(List.of());

    private final LolaToolkit toolkit = new LolaToolkitImpl(mock(ProductSearch.class), mock(CatalogReader.class),
            mock(OfferReader.class), mock(DeliveryPromises.class), mock(OrderTracker.class), mock(ServiceDirectory.class),
            new AccountReader() {
                @Override
                public Result<List<OrderSummary>> recentOrders(String bearerToken, int limit) {
                    return orders;
                }

                @Override
                public Result<CartSummary> cart(String bearerToken) {
                    return cart;
                }

                @Override
                public Result<List<BookingSummary>> bookings(String bearerToken) {
                    return bookings;
                }

                @Override
                public Result<List<ReturnSummary>> returns(String bearerToken, String orderNumber) {
                    return returns;
                }
            }, new InjectionFilter() {
                @Override
                public FilteredText sanitize(String untrusted, int maxLength) {
                    return new FilteredText(untrusted == null ? "" : untrusted, 0);
                }

                @Override
                public String quote(String source, String sanitizedContent) {
                    return "<untrusted source=\"" + source + "\">" + sanitizedContent + "</untrusted>";
                }
            }, JsonMapper.builder().build(), mock(AiSettings.class), mock(AiMetrics.class));

    private final ToolContext ctx = new ToolContext(new Caller("u1", "CUSTOMER", null, Set.of(), "token"), "lola_session",
            "77002", new GroundingFacts());

    private ToolOutcome run(String tool, String args) {
        return toolkit.execute(new ToolCallRequest("c1", tool, args), ctx);
    }

    @Test
    void everyAccountToolIsOffered() {
        assertThat(toolkit.specs()).extracting(s -> s.name()).contains("my_orders", "view_cart", "my_bookings", "return_status",
                "refund_status");
    }

    @Test
    void ordersCarryNumberStatusAndTotalAndAnEmptyListSaysSo() {
        assertThat(run("my_orders", "{}").modelContent()).contains("no orders yet");
        orders = AccountReader.Result.of(List.of(new AccountReader.OrderSummary("AT-2026-000123", "SHIPPED",
                "2026-10-12", 4599, "USD", 2)));
        ToolOutcome o = run("my_orders", "{\"limit\":3}");
        assertThat(o.success()).isTrue();
        assertThat(o.modelContent()).contains("AT-2026-000123", "SHIPPED", "$45.99");
        orders = AccountReader.Result.unavailable();
        assertThat(run("my_orders", "{}").success()).isFalse();
        assertThat(run("my_orders", "{}").modelContent()).contains("not available yet");
    }

    @Test
    void cartShowsLinesWarningsAndTheSubtotal() {
        assertThat(run("view_cart", "{}").modelContent()).contains("not available right now");
        cart = AccountReader.Result.of(new AccountReader.CartSummary(3, 2598, "USD", true, List.of(
                new AccountReader.CartLine("AT12345678", "Garri 2kg", 2, 899, false, true),
                new AccountReader.CartLine("AT87654321", "Egusi", 1, 800, true, false))));
        String content = run("view_cart", "{}").modelContent();
        assertThat(content).contains("Garri 2kg", "$8.99", "the price changed", "unavailable: left out of checkout",
                "$25.98");
    }

    @Test
    void bookingsListReferenceServiceAndStatus() {
        bookings = AccountReader.Result.of(List.of(new AccountReader.BookingSummary("BK-77", "Knotless braids",
                "Confirmed", "2026-10-25T14:00:00Z", 15000, 3000)));
        assertThat(run("my_bookings", "{}").modelContent()).contains("BK-77", "Knotless braids", "Confirmed", "$150.00",
                "$30.00");
    }

    @Test
    void returnStatusShowsItemsProgressAndRefund() {
        assertThat(run("return_status", "{}").modelContent()).contains("No returns found");
        returns = AccountReader.Result.of(List.of(new AccountReader.ReturnSummary("RMA-1A2B3C4D", "AT-2026-000123",
                "received", "DROP_OFF", "pending", 2599, "USD", "2026-10-30", "2026-10-20", List.of(
                new AccountReader.ReturnLine("Garri 2kg", 2, "DAMAGED", "")))));
        String content = run("return_status", "{\"orderId\":\"AT-2026-000123\"}").modelContent();
        assertThat(content).contains("RMA-1A2B3C4D", "received", "Garri 2kg", "DAMAGED", "2026-10-30", "pending",
                "$25.99");
    }

    @Test
    void refundStatusIsRefundFocusedAndSaysWhenNothingIsIssued() {
        returns = AccountReader.Result.of(List.of(new AccountReader.ReturnSummary("RMA-9", "AT-2026-000124",
                "label_issued", "DROP_OFF", "", 1200, "USD", "", "2026-10-21", List.of())));
        ToolOutcome o = run("refund_status", "{}");
        assertThat(o.modelContent()).contains("RMA-9", "not issued yet", "$12.00", "label_issued");
        assertThat(o.modelContent()).doesNotContain("dropOffBy");
        returns = AccountReader.Result.unavailable();
        assertThat(run("refund_status", "{}").success()).isFalse();
    }
}
