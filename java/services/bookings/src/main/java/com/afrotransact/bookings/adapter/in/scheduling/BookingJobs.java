package com.afrotransact.bookings.adapter.in.scheduling;

import com.afrotransact.bookings.application.BookingMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background jobs: expire unpaid slot holds and unanswered requests (releasing the deposit hold), auto-complete
 * appointments after {@code at.bookings.auto-complete-after}. Every replica may run them: each booking is processed
 * under its row lock and re-checked.
 */
@Component
@ConditionalOnProperty(prefix = "at.bookings.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class BookingJobs {

    private static final Logger LOG = LoggerFactory.getLogger(BookingJobs.class);

    private final BookingMaintenanceService maintenance;

    BookingJobs(BookingMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @Scheduled(fixedDelayString = "${at.bookings.jobs.interval:60s}", initialDelayString = "${at.bookings.jobs.interval:60s}")
    void run() {
        int holds = maintenance.expireHolds();
        int requests = maintenance.expireRequests();
        int completed = maintenance.autoComplete();
        if (holds + requests + completed > 0) {
            LOG.info("Bookings jobs: {} holds expired, {} requests expired, {} auto-completed", holds, requests,
                    completed);
        }
    }
}
