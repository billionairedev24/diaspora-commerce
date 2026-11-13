package com.afrotransact.payments.adapter.out.seller;

import java.util.Optional;

import com.afrotransact.payments.port.OrgMembershipDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * No live membership check ({@code at.payments.membership-check.mode=off}): the local profile, where there is no seller
 * service or identity to ask. Every other profile runs the {@code live} mode (the default).
 */
@Component
@ConditionalOnProperty(prefix = "at.payments.membership-check", name = "mode", havingValue = "off")
class LocalOrgMembershipDirectory implements OrgMembershipDirectory {

    LocalOrgMembershipDirectory(Environment env) {
        if (!env.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException("at.payments.membership-check.mode=off is for the local profile only");
        }
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Optional<String> roleOf(String orgId, String userId) {
        throw new UnsupportedOperationException("Live membership checks are off");
    }
}
