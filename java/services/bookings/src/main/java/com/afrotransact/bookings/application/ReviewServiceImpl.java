package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.BookingsStateConflict;
import com.afrotransact.bookings.domain.Party;
import com.afrotransact.bookings.domain.RatingSummary;
import com.afrotransact.bookings.domain.Review;
import com.afrotransact.bookings.domain.ReviewRejectReason;
import com.afrotransact.bookings.domain.ReviewStatus;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.bookings.port.OfferingRepository;
import com.afrotransact.bookings.port.OrgVisibilityRepository;
import com.afrotransact.bookings.port.ReviewRepository;
import com.afrotransact.bookings.port.ReviewRepository.ReviewPage;
import com.afrotransact.bookings.port.TeamRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReviewServiceImpl implements ReviewService {

    static final int MAX_PAGE_SIZE = 50;

    private final BookingRepository bookings;
    private final ReviewRepository reviews;
    private final TeamRepository team;
    private final OfferingRepository offerings;
    private final OrgVisibilityRepository visibility;
    private final BookingsAccess access;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final Clock clock;

    ReviewServiceImpl(BookingRepository bookings, ReviewRepository reviews, TeamRepository team,
                      OfferingRepository offerings, OrgVisibilityRepository visibility, BookingsAccess access,
                      PermissionChecker permissions, DomainEventPublisher events, Clock clock) {
        this.bookings = bookings;
        this.reviews = reviews;
        this.team = team;
        this.offerings = offerings;
        this.visibility = visibility;
        this.access = access;
        this.permissions = permissions;
        this.events = events;
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
            throw new BookingsStateConflict("bookings.already-reviewed",
                    "This booking has already been rated");
        }
        Review review = reviews.insert(Review.of(b, rating, text, Instant.now(clock)));
        publish(BookingsEvents.REVIEW_SUBMITTED, review);
        return review;
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewPage moderationQueue(ReviewStatus status, int page, int size) {
        moderator();
        return reviews.byStatus(status == null ? ReviewStatus.PENDING : status, page(page), size(size));
    }

    @Override
    @Transactional(readOnly = true)
    public Review forModeration(UUID bookingId) {
        moderator();
        return find(bookingId);
    }

    @Override
    @Transactional
    public Review approve(UUID bookingId, String note) {
        Actor actor = moderator();
        Review current = find(bookingId);
        Review decided = current.approve(actor.subject(), note, Instant.now(clock));
        if (decided == current) {
            return current;
        }
        Review saved = reviews.update(decided);
        publish(BookingsEvents.REVIEW_APPROVED, saved);
        return saved;
    }

    @Override
    @Transactional
    public Review reject(UUID bookingId, ReviewRejectReason reason, String note) {
        Actor actor = moderator();
        Review current = find(bookingId);
        Review decided = current.reject(actor.subject(), reason, note, Instant.now(clock));
        if (decided == current) {
            return current;
        }
        Review saved = reviews.update(decided);
        publish(BookingsEvents.REVIEW_REJECTED, saved);
        return saved;
    }

    @Override
    @Transactional
    public Review reply(String orgId, UUID bookingId, String text) {
        ProviderScope scope = access.providerWide(orgId, BookingsPermissions.MANAGE);
        Review current = find(bookingId);
        if (!current.orgId().equals(orgId)) {
            throw notFound(bookingId);
        }
        Review saved = reviews.update(current.replyBy(scope.actor().subject(), text, Instant.now(clock)));
        publish(BookingsEvents.REVIEW_REPLIED, saved);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewPage providerReviews(String orgId, ReviewStatus status, int page, int size) {
        access.provider(orgId, BookingsPermissions.READ);
        return reviews.byOrg(orgId, status, page(page), size(size));
    }

    @Override
    @Transactional(readOnly = true)
    public PublicReviews publicForProvider(String orgId, int page, int size) {
        if (visibility.isHidden(orgId)) {
            throw new NotFoundException("bookings.provider-not-found", "Provider " + orgId + " not found");
        }
        RatingSummary rating = reviews.providerSummary(orgId);
        return new PublicReviews(rating, reviews.published(orgId, null, page(page), size(size)));
    }

    @Override
    @Transactional(readOnly = true)
    public PublicReviews publicForService(UUID serviceId, int page, int size) {
        ServiceOffering o = offerings.find(serviceId).filter(ServiceOffering::active)
                .filter(s -> !visibility.isHidden(s.orgId()))
                .orElseThrow(() -> new NotFoundException("bookings.service-not-found", "Service " + serviceId
                        + " is not available"));
        RatingSummary rating = reviews.summaries(List.of(o.id())).getOrDefault(o.id(), RatingSummary.NONE);
        return new PublicReviews(rating, reviews.published(null, o.id(), page(page), size(size)));
    }

    /** Staff or the trust-ops service client with {@code bookings:reviews:moderate}. */
    private Actor moderator() {
        Actor actor = permissions.require(BookingsPermissions.REVIEWS_MODERATE);
        if (!BookingsAccess.isStaff(actor) && !actor.permissions().contains("*")) {
            throw new ForbiddenActionException("bookings.moderation-staff-only",
                    "Only trust and safety staff can moderate reviews");
        }
        return actor;
    }

    private Review find(UUID bookingId) {
        return reviews.forBooking(bookingId).orElseThrow(() -> notFound(bookingId));
    }

    private void publish(String type, Review r) {
        events.publish(type, BookingsEvents.AGGREGATE, r.bookingId().toString(), ReviewEventPayload.of(r));
    }

    private static NotFoundException notFound(UUID bookingId) {
        return new NotFoundException("bookings.review-not-found", "No review for booking " + bookingId);
    }

    private static int page(int page) {
        if (page < 0) {
            throw new BookingsRuleViolation("bookings.invalid-page", "page must be 0 or more");
        }
        return page;
    }

    private static int size(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BookingsRuleViolation("bookings.invalid-page", "size must be 1 to " + MAX_PAGE_SIZE);
        }
        return size;
    }
}
