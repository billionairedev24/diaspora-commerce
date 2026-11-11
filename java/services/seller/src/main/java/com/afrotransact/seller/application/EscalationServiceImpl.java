package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.seller.port.ApplicationCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class EscalationServiceImpl implements EscalationService {

    private static final Logger LOG = LoggerFactory.getLogger(EscalationServiceImpl.class);
    private static final int BATCH = 500;

    private final ApplicationCaseRepository cases;
    private final ReviewAssignmentService assignments;
    private final Clock clock;

    EscalationServiceImpl(ApplicationCaseRepository cases, ReviewAssignmentService assignments, Clock clock) {
        this.cases = cases;
        this.assignments = assignments;
        this.clock = clock;
    }

    @Override
    public int runDue() {
        Instant now = Instant.now(clock);
        int raised = 0;
        for (String id : cases.escalationCandidates(BATCH)) {
            try {
                // One transaction per case: the application row is locked, so replicas running at once escalate once.
                if (assignments.escalateIfDue(id, now)) {
                    raised++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Escalation check of application {} failed; it is retried on the next run", id, e);
            }
        }
        return raised;
    }
}
