package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.afrotransact.orders.domain.OrderLine;
import org.junit.jupiter.api.Test;

/** A pre-order package mixes sellers, so every line keeps its own seller and the placed event carries it (S264). */
class LineSellerPayloadTest {

    @Test
    void theLineSummaryOfThePlacedEventNamesTheLinesSeller() {
        OrderLine line = new OrderLine(UUID.randomUUID().toString(), 2, "ATIN-2", "P-2", "Egusi", null, 3, 900, 74, 0,
                "groceries", false, "US-TX", "FOOD", null, 0, "org_mama", "Mama Tee's Pantry");
        OrdersEventPayloads.LineSummary summary = OrdersEventPayloads.LineSummary.of(line);
        assertThat(summary.sellerOrgId()).isEqualTo("org_mama");
        assertThat(summary.sellerName()).isEqualTo("Mama Tee's Pantry");
        assertThat(summary.quantity()).isEqualTo(3);
    }

    @Test
    void orderLinesFromBeforeTheSellerWasKeptHaveNone() {
        OrderLine line = new OrderLine(UUID.randomUUID().toString(), 1, "ATIN-1", "P-1", "Rice", null, 1, 500, 41, 0,
                "groceries", false, "US-TX");
        assertThat(line.sellerOrgId()).isNull();
        assertThat(OrdersEventPayloads.LineSummary.of(line).sellerName()).isNull();
    }
}
