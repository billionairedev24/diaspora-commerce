package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingsStateConflict;
import com.afrotransact.bookings.domain.Party;
import com.afrotransact.bookings.domain.Review;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.bookings.port.ReviewRepository;
import com.afrotransact.bookings.port.TeamRepository;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReviewServiceImpl implements ReviewService {

    private final BookingRepository bookings;
    private final ReviewRepository reviews;
    private final TeamRepository team;
    private final BookingsAccess access;
    private final Clock clock;

    ReviewServiceImpl(BookingRepository bookings, ReviewRepository reviews, TeamRepository team,
                      BookingsAccess access, Clock clock) {
        this.bookings = bookings;
        this.reviews = reviews;
        this.team = team;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Review rate(UUID bookingId, int rating, String text) {
        Booking b = bookings.find(bookingId).orElseThrow(() -> new NotFoundException("bookings.booking-not-found",
                "Booking " + bookingId + " not found"));
        Party party = access.partyFor(b, BookingsPermissions.READ, team.find(b.memberId()).orElse(null));
        if (party != Party.CUSTOMER) {
            throw new ForbiddenActionException("bookings.customer-only", "Only the customer can rate a booking");
        }
        if (reviews.forBooking(bookingId).isPresent()) {
            throw new BookingsStateConflict("bookings.already-reviewed", "This booking has already been rated");
        }
        return reviews.insert(Review.of(b, rating, text, Instant.now(clock)));
    }
}
