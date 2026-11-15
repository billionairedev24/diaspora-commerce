package com.afrotransact.bookings.adapter.in.module;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.api.ReviewModerationApi;
import com.afrotransact.bookings.application.ReviewService;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.BookingsStateConflict;
import com.afrotransact.bookings.domain.ReviewRejectReason;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ReviewModerationApi}: the use cases behind the review approve and reject endpoints under the
 * calling module's principal; {@link ReviewService} checks the moderation permission as for a token.
 */
@Component
class ReviewModerationApiAdapter implements ReviewModerationApi {

    private final ReviewService reviews;
    private final ModuleEntry entry;

    ReviewModerationApiAdapter(ReviewService reviews, ActorResolver actors) {
        this.reviews = reviews;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public void approve(UUID bookingId, String note) {
        entry.runVoid(() -> reviews.approve(bookingId, note), ReviewModerationApiAdapter::map);
    }

    @Override
    public void reject(UUID bookingId, String reasonCode, String note) {
        entry.runVoid(() -> reviews.reject(bookingId, ReviewRejectReason.valueOf(reasonCode), note),
                ReviewModerationApiAdapter::map);
    }

    /** The mapping {@code BookingsErrorAdvice} applies to rule violations and state conflicts. */
    private static Optional<ModuleApiException> map(RuntimeException ex) {
        if (ex instanceof BookingsRuleViolation rule) {
            return Optional.of(new ModuleApiException(422, rule.code(), ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof BookingsStateConflict state) {
            return Optional.of(new ModuleApiException(409, state.code(), ex.getMessage(), List.of(), ex));
        }
        if (ex instanceof IllegalArgumentException) {
            return Optional.of(new ModuleApiException(400, "bookings.invalid-input", ex.getMessage(), List.of(), ex));
        }
        return Optional.empty();
    }
}
