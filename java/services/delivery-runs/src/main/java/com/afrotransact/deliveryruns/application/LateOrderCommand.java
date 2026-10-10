package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.LateAddReason;
import com.afrotransact.deliveryruns.domain.LatePaymentMethod;
import com.afrotransact.deliveryruns.port.OrdersGateway.Address;
import com.afrotransact.deliveryruns.port.OrdersGateway.Item;
import com.afrotransact.deliveryruns.port.OrdersGateway.NewAddress;

/**
 * Customer service adds a late order to a window.
 *
 * @param idempotencyKey retries with the same key create one order (generated when null)
 * @param customerId     identity subject of the customer
 * @param addressId      a saved address, or null with {@code newAddress}
 * @param newAddress     a new address verified with Google Places, or null with {@code addressId}
 * @param address        the delivery address snapshot (recipient, street, city, state, postal code) the console /
 *                       BFF knows for the saved or new address; city / state default to the window's first city
 * @param phoneConfirmed must be true: the agent confirmed the phone number
 * @param items          window products and quantities
 * @param payment        saved card or payment link
 * @param reason         why the order is late
 * @param note           agent note (≤ 500)
 */
public record LateOrderCommand(String idempotencyKey, String customerId, String addressId, NewAddress newAddress,
                               Address address, Boolean phoneConfirmed, List<Item> items, LatePaymentMethod payment,
                               LateAddReason reason, String note) {
}
