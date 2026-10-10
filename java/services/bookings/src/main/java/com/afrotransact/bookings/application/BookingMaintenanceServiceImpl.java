package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingStatus;
import com.afrotransact.bookings.domain.Party;
import com.afrotransact.bookings.domain.Transition;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.bookings.port.ProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class BookingMaintenanceServiceImpl implements BookingMaintenanceService {

    private static final Logger LOG = LoggerFactory.getLogger(BookingMaintenanceServiceImpl.class);
    private static final int BATCH = 100;

    private final BookingRepository bookings;
    private final ProviderRepository providers;
    private final BookingMoney money;
    private final BookingEventPublisher events;
    private final BookingsSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    BookingMaintenanceServiceImpl(BookingRepository bookings, ProviderRepository providers, BookingMoney money,
                                  BookingEventPublisher events, BookingsSettings settings,
                                  PlatformTransactionManager transactionManager, Clock clock) {
        this.bookings = bookings;
        this.providers = providers;
        this.money = money;
        this.events = events;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public int expireHolds() {
        Instant now = Instant.now(clock);
        return run(bookings.dueForExpiry(BookingStatus.PENDING_PAYMENT, now, BATCH),
                BookingStatus.PENDING_PAYMENT, (b, t) -> b.expireHold(t));
    }

    @Override
    public int expireRequests() {
        Instant now = Instant.now(clock);
        return run(bookings.dueForExpiry(BookingStatus.REQUESTED, now, BATCH),
                BookingStatus.REQUESTED, (b, t) -> b.expireRequest(t));
    }

    @Override
    public int autoComplete() {
        Instant now = Instant.now(clock);
        return run(bookings.dueForCompletion(now.minus(settings.autoCompleteAfter()), BATCH),
                BookingStatus.CONFIRMED, (b, t) -> b.complete(Party.SYSTEM, t));
    }

    private int run(List<UUID> ids, BookingStatus expected, BiFunction<Booking, Instant, Transition> change) {
        int done = 0;
        for (UUID id : ids) {
            try {
                Boolean changed = tx.execute(status -> {
                    Booking b = bookings.lock(id).orElse(null);
                    if (b == null || b.status() != expected) {
                        return false;
                    }
                    Instant now = Instant.now(clock);
                    Transition t = change.apply(b, now);
                    money.execute(b, t.moves(), now);
                    Booking saved = bookings.update(b);
                    events.publish(t, saved, providers.find(saved.orgId()).orElseThrow());
                    return true;
                });
                if (Boolean.TRUE.equals(changed)) {
                    done++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Booking {} not processed this round; will retry: {}", id, e.getMessage());
            }
        }
        return done;
    }
}
