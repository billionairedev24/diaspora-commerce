package com.afrotransact.identity.config;

import com.afrotransact.identity.application.OrganizationMirrored;
import com.afrotransact.identity.application.TestUserReport;
import com.afrotransact.identity.application.TestUserService;
import com.afrotransact.identity.application.TestUserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * S276: creates the local test accounts at start-up (after the bootstrap admin) and links the organisation personas as
 * soon as {@code make seed} creates their organisations. Does nothing where {@link TestUserSettings} is off.
 */
@Component
class TestUserProvisioning implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(TestUserProvisioning.class);

    private final TestUserSettings settings;
    private final TestUserService testUsers;

    TestUserProvisioning(TestUserSettings settings, TestUserService testUsers) {
        this.settings = settings;
        this.testUsers = testUsers;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!settings.enabled()) {
            return;
        }
        TestUserReport r = testUsers.ensureAll();
        LOG.info("[local] {} test users (@afrotransact.test, shared password: see docs/engineering/local-development.md):"
                        + " {} created, {} repaired, {} roles granted, {} memberships linked, {} addresses added{}",
                r.accounts(), r.created(), r.repaired(), r.rolesGranted(), r.membershipsLinked(), r.addressesAdded(),
                r.awaitingOrgs().isEmpty() ? "" : "; waiting for " + r.awaitingOrgs() + " (make seed creates them)");
    }

    @TransactionalEventListener(fallbackExecution = true)
    void organizationMirrored(OrganizationMirrored event) {
        if (!settings.enabled()) {
            return;
        }
        int linked = testUsers.linkOrganisation(event.orgId());
        if (linked > 0) {
            LOG.info("[local] linked {} test user(s) to {}", linked, event.orgId());
        }
    }
}
