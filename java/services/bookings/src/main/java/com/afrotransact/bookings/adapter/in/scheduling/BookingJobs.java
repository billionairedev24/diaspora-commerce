package com.afrotransact.bookings.adapter.in.scheduling;

import com.afrotransact.bookings.application.BalanceChargeService;
import com.afrotransact.bookings.application.BookingMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background jobs: expire unpaid slot holds and unanswered requests (releasing the deposit hold), auto-complete
 * appointments after {@code at.bookings.auto-complete-after}, and charge the balance of long-dated bookings
 * off-session before the appointment. Every replica may run them: each booking is processed under its row lock and
 * re-checked (the balance job skips rows another replica holds: {@code FOR UPDATE SKIP LOCKED}).
 */
@Component
@ConditionalOnProperty(prefix = "at.bookings.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
class BookingJobs {

    private static final Logger LOG = LoggerFactory.getLogger(BookingJobs.class);

    private final BookingMaintenanceService maintenance;
    private final BalanceChargeService balances;

    BookingJobs(BookingMaintenanceService maintenance, BalanceChargeService balances) {
        this.maintenance = maintenance;
        this.balances = balances;
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

    @Scheduled(fixedDelayString = "${at.bookings.jobs.balance-interval:5m}",
            initialDelayString = "${at.bookings.jobs.interval:60s}")
    void chargeBalances() {
        int charged = balances.chargeDueBalances();
        if (charged > 0) {
            LOG.info("Bookings jobs: {} off-session balance charges submitted or flagged", charged);
        }
    }
}
