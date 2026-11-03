package com.afrotransact.orders.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.afrotransact.orders.application.PreorderCancellationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DeliveryRunsEventsListenerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<String> calls = new ArrayList<>();
    private final DeliveryRunsEventsListener listener = new DeliveryRunsEventsListener(
            new PreorderCancellationService() {
                @Override
                public Result cancelWindow(String windowId, String reason) {
                    calls.add("window:" + windowId + ":" + reason);
                    return new Result(2, 0, 0, 0);
                }

                @Override
                public Result cancelOrders(Collection<String> orderIds, String reason) {
                    calls.add("orders:" + String.join(",", orderIds) + ":" + reason);
                    return new Result(1, 0, 0, 0);
                }
            }, JSON);

    private static ConsumerRecord<String, String> record(String type, String payload) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.delivery-runs.events", 0, 7L, "key", payload);
        r.headers().add(new RecordHeader("event-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        r.headers().add(new RecordHeader("event-type", type.getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    @Test
    void aCancelledWindowRefundsItsOrdersWithTheStaffReason() {
        var result = listener.handle(record("preorder.window.cancelled", """
                {"windowId":"3f1a6c1e-0000-4000-8000-000000000001","code":"LBK-7","status":"CANCELLED",
                 "orders":[{"orderId":"o-1","customerId":"c-1","fulfilment":"DOOR"}],
                 "decision":{"kind":"CANCEL","reason":"Not enough orders","actor":"staff-1","orders":1,"minimum":10}}
                """));

        assertThat(result.cancelled()).isEqualTo(2);
        assertThat(calls).containsExactly("window:3f1a6c1e-0000-4000-8000-000000000001:"
                + "Pre-order window cancelled: Not enough orders");
    }

    @Test
    void aCancelledRunRefundsTheOrdersItListed() {
        listener.handle(record("delivery-runs.run.cancelled", """
                {"runId":"r-1","status":"CANCELLED","reason":"No driver","orders":[
                  {"orderId":"o-1","customerId":"c-1","packages":1,"status":"CANCELLED"},
                  {"orderId":"o-2","customerId":"c-2","packages":1,"status":"CANCELLED"}]}
                """));

        assertThat(calls).containsExactly("orders:o-1,o-2:Delivery run cancelled: No driver");
    }

    @Test
    void otherEventsAndEmptyOnesAreIgnored() {
        assertThat(listener.handle(record("preorder.window.closed", "{\"windowId\":\"w\"}"))).isNull();
        assertThat(listener.handle(record("delivery-runs.run.confirmed", "{\"orders\":[{\"orderId\":\"o\"}]}")))
                .isNull();
        assertThat(listener.handle(record("preorder.window.cancelled", "{}"))).isNull();
        assertThat(listener.handle(record("delivery-runs.run.cancelled", "{\"orders\":[]}"))).isNull();
        assertThat(calls).isEmpty();
    }
}
