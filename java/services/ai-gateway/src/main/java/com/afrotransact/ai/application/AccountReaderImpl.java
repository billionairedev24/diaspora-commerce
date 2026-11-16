package com.afrotransact.ai.application;

import java.util.List;

import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.BookingHistory;
import com.afrotransact.ai.port.CartReader;
import com.afrotransact.ai.port.OrderHistory;
import com.afrotransact.ai.port.ReturnHistory;
import com.afrotransact.ai.port.Shopper;
import org.springframework.stereotype.Component;

/**
 * The shopper's account as Lola reads it: one facade over one port per edge (orders, bookings, tax-returns and the Go cart service),
 * so each edge has its own adapter and its own in-process or HTTP mode.
 */
@Component
class AccountReaderImpl implements AccountReader {

    private final OrderHistory orders;
    private final BookingHistory bookings;
    private final ReturnHistory returns;
    private final CartReader cart;

    AccountReaderImpl(OrderHistory orders, BookingHistory bookings, ReturnHistory returns, CartReader cart) {
        this.orders = orders;
        this.bookings = bookings;
        this.returns = returns;
        this.cart = cart;
    }

    @Override
    public Result<List<OrderSummary>> recentOrders(Shopper shopper, int limit) {
        return orders.recent(shopper, limit);
    }

    @Override
    public Result<CartSummary> cart(Shopper shopper) {
        return cart.cart(shopper);
    }

    @Override
    public Result<List<BookingSummary>> bookings(Shopper shopper) {
        return bookings.mine(shopper);
    }

    @Override
    public Result<List<ReturnSummary>> returns(Shopper shopper, String orderNumber) {
        return returns.mine(shopper, orderNumber);
    }
}
