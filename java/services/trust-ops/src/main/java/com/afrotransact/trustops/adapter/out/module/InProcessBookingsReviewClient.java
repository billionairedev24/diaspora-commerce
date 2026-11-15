package com.afrotransact.trustops.adapter.out.module;

import java.util.UUID;

import com.afrotransact.bookings.api.ReviewModerationApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.port.BookingsReviewClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link BookingsReviewClient} through bookings' published {@link ReviewModerationApi} inside this JVM (mode
 * {@code in-process}), entered as trust-ops' caller principal. Notes are cut at 1000 characters as in the HTTP client; both calls
 * are idempotent, and bookings' refusals keep their status and code in the {@code UpstreamException}.
 */
@Component
@ConditionalOnModuleMode(caller = "trust-ops", callee = "bookings", value = ModuleMode.IN_PROCESS)
class InProcessBookingsReviewClient implements BookingsReviewClient {

    private static final String SERVICE = "bookings";
    private static final int MAX_NOTE = 1000;

    private final ObjectProvider<ReviewModerationApi> api;
    private final ModuleUpstream upstream;

    InProcessBookingsReviewClient(ObjectProvider<ReviewModerationApi> api, ModuleUpstream upstream,
                                  ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ReviewModerationApi.class, true, false).length > 0,
                "trust-ops", "bookings", "at.trustops.bookings.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public void approve(UUID bookingId, String note) {
        upstream.run(SERVICE, () -> api.getObject().approve(bookingId, truncate(note)));
    }

    @Override
    public void reject(UUID bookingId, ModerationReasonCode reasonCode, String note) {
        upstream.run(SERVICE, () -> api.getObject().reject(bookingId, reasonCode.name(), truncate(note)));
    }

    private static String truncate(String note) {
        return note == null ? null : note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note;
    }
}
