package com.afrotransact.seller.application;

import java.util.Map;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.OrgDirectoryEntry;
import com.afrotransact.seller.port.OrgDirectoryReader;
import com.afrotransact.seller.port.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrgDirectoryServiceImpl implements OrgDirectoryService {

    private final OrgDirectoryReader reader;
    private final PermissionChecker permissions;

    OrgDirectoryServiceImpl(OrgDirectoryReader reader, PermissionChecker permissions) {
        this.reader = reader;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrgDirectoryEntry> directory(int page, int size, Set<OrgStatus> statuses) {
        Actor actor = permissions.require(DIRECTORY_READ);
        if (actor.type() != PrincipalType.SERVICE && actor.type() != PrincipalType.STAFF) {
            throw new ForbiddenActionException("seller.directory-service-only",
                    "The organisation directory is for services and staff");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new SellerRuleViolation("seller.invalid-input", "page must be ≥ 0 and size 1 to " + MAX_PAGE_SIZE,
                    Map.of("size", "1 to " + MAX_PAGE_SIZE));
        }
        return reader.page(page, size, statuses == null ? Set.of() : statuses);
    }
}
