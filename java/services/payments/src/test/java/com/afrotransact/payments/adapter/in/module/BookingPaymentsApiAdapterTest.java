package com.afrotransact.payments.adapter.in.module;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.afrotransact.payments.api.BookingPaymentsApi;
import com.afrotransact.payments.application.FeeScheduleService;
import com.afrotransact.payments.application.OffSessionChargeService;
import com.afrotransact.payments.application.PaymentService;
import com.afrotransact.payments.application.SettlementService;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** S6 for the booking payment calls: every method needs {@code payments:service}; a caller without it is refused with 403. */
class BookingPaymentsApiAdapterTest {

    private static final BookingPaymentsApi API = new BookingPaymentsApiAdapter(
            InProcessKit.guarded(PaymentService.class, "payments:service"),
            InProcessKit.guarded(OffSessionChargeService.class, "payments:service"),
            InProcessKit.guarded(FeeScheduleService.class, "payments:service"),
            InProcessKit.guarded(SettlementService.class, "payments:service"), InProcessKit.ACTORS);

    private static final UUID ID = UUID.fromString("6f1c1a3e-0000-4000-8000-000000000001");

    static Stream<Arguments> everyMethod() {
        return Stream.of(
                Arguments.of("create", (Runnable) () -> API.create(new BookingPaymentsApi.NewBookingPayment("b1", "org",
                        5000, "USD", List.of(new BookingPaymentsApi.Line("l1", 5000)), Map.of(), Instant.now(), null, false,
                        "services"), "k")),
                Arguments.of("get", (Runnable) () -> API.get(ID, false)),
                Arguments.of("capture", (Runnable) () -> API.capture(ID, "c1", List.of("l1"), 100, false, "k")),
                Arguments.of("cancel", (Runnable) () -> API.cancel(ID, "why", "k")),
                Arguments.of("release", (Runnable) () -> API.release(ID, "why", "k")),
                Arguments.of("chargeOffSession", (Runnable) () -> API.chargeOffSession(ID, "c1", null, List.of(), "k")),
                Arguments.of("refund", (Runnable) () -> API.refund(ID, "l1", 100, "why", "k")),
                Arguments.of("serviceCompleted", (Runnable) () -> API.serviceCompleted("b1", Instant.now())),
                Arguments.of("connectionFees", (Runnable) () -> API.connectionFees("services", "org")));
    }

    @ParameterizedTest(name = "{0} without payments:service is 403")
    @MethodSource("everyMethod")
    void everyMethodRefusesACallerWithoutTheScope(String method, Runnable call) {
        InProcessKit.assertForbidden(InProcessKit.unscoped("bookings"), call);
    }
}
