package com.afrotransact.bookings.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.afrotransact.bookings.api.ShopperBookingsApi;
import com.afrotransact.bookings.application.BookingService;
import com.afrotransact.bookings.domain.BookedService;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingStatus;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/** The shopper-bookings API runs bookings' own use case as the signed-in person, for a module that holds the delegation scope. */
class ShopperBookingsApiAdapterTest {

    private final List<String> actorsInForce = new ArrayList<>();
    private final BookingService bookings = mock(BookingService.class);
    private final ShopperBookingsApi api = new ShopperBookingsApiAdapter(bookings, () -> {
        var actor = InProcessKit.ACTORS.current();
        actorsInForce.add(actor.subject() + "/" + actor.type());
        return actor;
    });
    private final EndUserActor shopper = InProcessKit.user("customer-1", "pt", "CUSTOMER");

    private <T> T asAiGateway(Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("ai-gateway", ShopperBookingsApi.CALLER_SCOPE), "bookings", call);
    }

    @Test
    void listsTheBookingsOfThePersonWithTheCustomerFacingStatus() {
        Booking b = mock(Booking.class);
        BookedService service = mock(BookedService.class);
        when(service.name()).thenReturn("Knotless braids");
        when(service.priceMinor()).thenReturn(15000L);
        when(service.depositMinor()).thenReturn(3000L);
        Instant start = Instant.parse("2026-10-25T14:00:00Z");
        when(b.reference()).thenReturn("BK-77");
        when(b.service()).thenReturn(service);
        when(b.status()).thenReturn(BookingStatus.CONFIRMED);
        when(b.startAt()).thenReturn(start);
        when(bookings.mine()).thenReturn(List.of(b));
        List<ShopperBookingsApi.BookingSummary> out = asAiGateway(() -> api.mine(shopper));
        assertThat(out).containsExactly(new ShopperBookingsApi.BookingSummary("BK-77", "Knotless braids",
                BookingStatus.CONFIRMED.label(), start, 15000, 3000));
        assertThat(actorsInForce).containsExactly("customer-1/CUSTOMER");
    }

    @Test
    void aModuleWithoutTheDelegationScopeIsRefusedBeforeAnyRead() {
        assertThatThrownBy(() -> InProcessCalls.as(InProcessKit.caller("ai-gateway", "bookings:read"), "bookings",
                () -> api.mine(shopper))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("auth.missing-permission");
                });
        verifyNoInteractions(bookings);
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.mine(shopper)).isInstanceOf(IllegalStateException.class);
    }
}
