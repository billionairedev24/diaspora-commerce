package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.identity.domain.RoleKeys;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.PasswordHasher;
import com.afrotransact.identity.port.UserAccessRepository;
import com.afrotransact.identity.port.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BootstrapServiceImpl implements BootstrapService {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapServiceImpl.class);
    private static final String SYSTEM = "system";

    private final UserAccountRepository users;
    private final UserAccessRepository access;
    private final PasswordHasher hasher;
    private final AccessChangeLog changeLog;
    private final Clock clock;

    BootstrapServiceImpl(UserAccountRepository users, UserAccessRepository access, PasswordHasher hasher,
                         AccessChangeLog changeLog, Clock clock) {
        this.users = users;
        this.access = access;
        this.hasher = hasher;
        this.changeLog = changeLog;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void ensureSuperAdmin(String email, String password, String firstName, String lastName, String phone) {
        String normalised = UserAccount.normaliseEmail(email);
        if (users.findByEmail(normalised).isPresent()) {
            return;
        }
        Instant now = Instant.now(clock);
        UserAccount admin = users.insert(new UserAccount(UUID.randomUUID(), firstName, lastName, normalised, true,
                phone, hasher.hash(password), true, false, now, null, now, now, 0));
        access.grantRole(admin.id(), RoleKeys.SUPER_ADMIN, SYSTEM);
        changeLog.append(SYSTEM, "role.granted", admin.id(), RoleKeys.SUPER_ADMIN, RoleKeys.SUPER_ADMIN,
                "bootstrap super administrator");
        LOG.info("Bootstrapped super administrator {}", normalised);
    }
}
