package com.afrotransact.offers.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import com.afrotransact.platform.events.consumer.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every listener of offers dead-letters a record it cannot read: an unparsable payload, or a record without a usable
 * {@code event-id} header where the listener de-duplicates by it, makes the listener throw
 * {@link NonRetryableEventException} (the shared error handler of platform-events writes it to the DLQ at once).
 * Event types the listener does not handle are still ignored, and a readable payload is not rejected.
 */
class ListenersDeadLetterTest {

    static Stream<Arguments> listeners() {
        return Stream.of(
                Arguments.of(CatalogEventListener.class, "catalog.product.created", "onMessage", true, "some.other.event"),
                Arguments.of(InventoryEventListener.class, "inventory.availability.changed", "onMessage", true, "some.other.event"),
                Arguments.of(InventoryInboundEventListener.class, "inventory.inbound.received", "onMessage", true, "some.other.event"),
                Arguments.of(OrderStockEventListener.class, "orders.order.placed", "onMessage", true, "some.other.event"),
                Arguments.of(RetailEventListener.class, "retail.item.updated", "onMessage", true, "some.other.event"),
                Arguments.of(SellerEventListener.class, "seller.org.approved", "onMessage", true, "some.other.event")
        );
    }

    @ParameterizedTest(name = "{0}.{2}")
    @MethodSource("listeners")
    void unparsablePayloadIsDeadLettered(Class<?> type, String eventType, String method, boolean needsEventId,
            String unhandled) {
        Fixture f = new Fixture(type, method);

        assertThatThrownBy(() -> f.deliver(rec(eventType, UUID.randomUUID().toString(), "not json")))
                .isInstanceOf(NonRetryableEventException.class)
                .hasMessageContaining("Malformed " + eventType);
        f.verifyServicesUntouched();
    }

    @ParameterizedTest(name = "{0}.{2}")
    @MethodSource("listeners")
    void recordWithoutUsableEventIdIsDeadLetteredWhereTheInboxNeedsIt(Class<?> type, String eventType, String method,
                                                                    boolean needsEventId, String unhandled) {
        Fixture f = new Fixture(type, method);

        if (needsEventId) {
            assertThatThrownBy(() -> f.deliver(rec(eventType, null, "{}")))
                    .isInstanceOf(NonRetryableEventException.class)
                    .hasMessageContaining("event-id");
            assertThatThrownBy(() -> f.deliver(rec(eventType, "not-a-uuid", "{}")))
                    .isInstanceOf(NonRetryableEventException.class);
            f.verifyServicesUntouched();
        } else {
            assertThatCode(() -> f.deliver(rec(eventType, null, "{}"))).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest(name = "{0}.{2}")
    @MethodSource("listeners")
    void eventTypesTheListenerDoesNotHandleAreIgnoredEvenWhenUnreadable(Class<?> type, String eventType,
                                                                      String method, boolean needsEventId,
            String unhandled) {
        Fixture f = new Fixture(type, method);

        assertThatCode(() -> f.deliver(rec(unhandled, null, "not json"))).doesNotThrowAnyException();
        f.verifyServicesUntouched();
    }

    @ParameterizedTest(name = "{0}.{2}")
    @MethodSource("listeners")
    void readablePayloadIsNotRejected(Class<?> type, String eventType, String method, boolean needsEventId,
            String unhandled) {
        Fixture f = new Fixture(type, method);

        assertThatCode(() -> f.deliver(rec(eventType, UUID.randomUUID().toString(), "{}"))).doesNotThrowAnyException();
    }

    private static ConsumerRecord<String, String> rec(String eventType, String eventId, String value) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.test.events", 0, 0L, "key", value);
        r.headers().add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
        if (eventId != null) {
            r.headers().add("event-id", eventId.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    /** The listener built with a real JsonMapper and mocked use cases, and its Kafka entry point. */
    private static final class Fixture {

        private final Object listener;
        private final Method entry;
        private final Object[] services;

        Fixture(Class<?> type, String method) {
            try {
                Constructor<?> ctor = type.getDeclaredConstructors()[0];
                ctor.setAccessible(true);
                Class<?>[] params = ctor.getParameterTypes();
                Object[] args = new Object[params.length];
                Object[] mocks = new Object[params.length];
                int n = 0;
                for (int i = 0; i < params.length; i++) {
                    if (params[i] == JsonMapper.class) {
                        args[i] = JsonMapper.builder().build();
                    } else {
                        args[i] = mock(params[i]);
                        mocks[n++] = args[i];
                    }
                }
                this.services = Arrays.copyOf(mocks, n);
                this.listener = ctor.newInstance(args);
                this.entry = type.getDeclaredMethod(method, ConsumerRecord.class);
                this.entry.setAccessible(true);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }

        void deliver(ConsumerRecord<String, String> rec) throws Throwable {
            try {
                entry.invoke(listener, rec);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }

        void verifyServicesUntouched() {
            assertThat(services).isNotEmpty();
            verifyNoInteractions(services);
        }
    }
}
