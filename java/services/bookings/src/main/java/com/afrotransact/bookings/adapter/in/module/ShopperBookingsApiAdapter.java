package com.afrotransact.bookings.adapter.in.module;

import java.util.List;

import com.afrotransact.bookings.api.ShopperBookingsApi;
import com.afrotransact.bookings.application.BookingService;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.module.EndUserActor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ShopperBookingsApi}: the use case behind {@code GET /v1/bookings/mine}, entered for the signed-in
 * person. {@link BookingService#mine()} reads the customer from the actor in force, which inside {@code runAsUser} is the person's token
 * subject (never the calling module), so it lists their own bookings and nobody else's.
 */
@Component
class ShopperBookingsApiAdapter implements ShopperBookingsApi {

    private final BookingService bookings;
    private final ModuleEntry entry;

    ShopperBookingsApiAdapter(BookingService bookings, ActorResolver actors) {
        this.bookings = bookings;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<BookingSummary> mine(EndUserActor user) {
        return entry.runAsUser(user, CALLER_SCOPE, () -> bookings.mine().stream().map(ShopperBookingsApiAdapter::summary).toList(),
                BookingsFailures::map);
    }

    private static BookingSummary summary(Booking b) {
        return new BookingSummary(b.reference(), b.service().name(), b.status().label(), b.startAt(), b.service().priceMinor(),
                b.service().depositMinor());
    }
}
