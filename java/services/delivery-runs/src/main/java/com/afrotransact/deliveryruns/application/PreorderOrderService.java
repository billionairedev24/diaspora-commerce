package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.PreorderOrder;
import com.afrotransact.deliveryruns.domain.PreorderSummary;
import com.afrotransact.deliveryruns.port.ProductSearch;

/** Staff: a window's orders, analytics, CSV exports and late adds (S270, S271). */
public interface PreorderOrderService {

    /** Orders tab (delivery-runs:read), newest first. */
    OrderPage orders(UUID windowId, OrderFilter filter, int page, int size);

    /**
     * Products customer service can add late (delivery-runs:read): live products of the window's categories in its
     * market, from search-service (502 when search is unavailable).
     */
    List<ProductSearch.Product> products(UUID windowId);

    /** Overview + Analytics (delivery-runs:read). */
    PreorderSummary summary(UUID windowId);

    /** A CSV export (preorders:export); publishes the audit event preorder.export.created. */
    CsvExport export(UUID windowId, ExportKind kind, OrderFilter filter);

    /**
     * Places a late order through orders-service (preorders:late-add) while the window is OPEN (assisted) or
     * CLOSED; refused once DELIVERED. Recorded as {@code LATE_ADD}, channel {@code AGENT_LATE}.
     */
    LateOrderResult addLateOrder(UUID windowId, LateOrderCommand command);

    /**
     * @param items rows of this page
     * @param total rows matching the filter
     * @param page  zero-based page
     * @param size  page size (12 in the console)
     */
    record OrderPage(List<PreorderOrder> items, int total, int page, int size) {
    }

    /**
     * @param order          the projected late add
     * @param ordersStatus   orders-service status (e.g. AWAITING_PAYMENT for pay-now links)
     * @param paymentLinkUrl pay-now link sent by SMS: AfroTransact's pay page with its signed token
     */
    record LateOrderResult(PreorderOrder order, String ordersStatus, String paymentLinkUrl) {
    }
}
